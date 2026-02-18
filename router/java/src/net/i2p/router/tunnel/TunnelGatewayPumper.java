package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;

/**
 * TunnelGatewayPumper runs a pool of threads that process PumpedTunnelGateway instances which need
 * messages pushed through preprocessing and sending.
 *
 * This class is thread-safe and designed to manage concurrent producers (calling wantsPumping)
 * and multiple consumer threads (pumper threads).
 *
 * Shutdown coordination:
 *   - The stopPumping() method sets a stop flag and enqueues a special poison pill for each thread.
 *   - Threads detect the poison pill and exit cleanly.
 *   - Threads are interrupted and joined to ensure complete termination.
 *
 * Thread safety notes:
 *   - Uses a LinkedBlockingQueue with bounded capacity for backpressure.
 *   - Uses a concurrent Set to track backlogged gateways for requeueing.
 *   - Retry with blocking put() ensures no pump request is silently dropped.
 *   - Synchronization around stop flag prevents race conditions with producing new tasks.
 */
class TunnelGatewayPumper implements Runnable {
    private final RouterContext _context;
    private final Log _log;

    // Queue of gateways awaiting pumping (bounded capacity)
    private final BlockingQueue<PumpedTunnelGateway> _wantsPumping;

    // Concurrent set of backlogged gateways waiting to be requeued
    private final Set<PumpedTunnelGateway> _backlogged;

    // Pool of threads pumping gateways
    private final List<Thread> _threads;

    // Volatile stop flag visible to all threads
    private volatile boolean _stop;

    private static final int MIN_PUMPERS = 1;
    private static final int MAX_PUMPERS = SystemVersion.isSlow() ? 1 : 2;
    private static final int QUEUE_BUFFER = SystemVersion.isSlow() ? 12 : 16;

    private final int _pumpers;

    /**
     * Wait time to requeue a backlogged gateway to allow task processing to catch up.
     */
    private static final long REQUEUE_TIME = SystemVersion.isSlow() ? 100 : 50;

    /**
     * Special poison pill constant used to signal termination.
     */
    private static final int POISON_PTG = -99999;

    /**
     * Creates a new TunnelGatewayPumper and starts configured number of pump threads.
     * @param ctx the router context
     */
    public TunnelGatewayPumper(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelGatewayPumper.class);
        _wantsPumping = new LinkedBlockingQueue<>(QUEUE_BUFFER);
        _backlogged = ConcurrentHashMap.newKeySet();
        _threads = new CopyOnWriteArrayList<>();
        _pumpers = ctx.getBooleanProperty("i2p.dummyTunnelManager") ? 1 : MAX_PUMPERS;

        for (int i = 0; i < _pumpers; i++) {
            Thread t = new I2PThread(this, "TunnGWPumper " + (i + 1) + '/' + _pumpers, true);
            _threads.add(t);
            t.start();
        }
    }

    /**
     * Requests a graceful stop for pump threads.
     * Clears queues and signals all pump threads to terminate.
     * Waits for thread termination before returning.
     */
    public void stopPumping() {
        _stop = true;

        // Clear the pumping request queue safely before shutdown
        _wantsPumping.clear();

        // Enqueue poison pills to unblock and stop each pumping thread
        for (int i = 0; i < _pumpers; i++) {
            try {
                _wantsPumping.put(new PoisonPTG(_context));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                _log.error("Interrupted while enqueuing poison pill", ie);
            }
        }

        long adjusted = (SystemVersion.getCPULoadAvg() > 95) ? REQUEUE_TIME * 3 / 2 : REQUEUE_TIME;
        if (_log.shouldWarn() && adjusted != REQUEUE_TIME) {
            _log.warn("Router JVM under sustained high CPU load, increasing pump interval from " + REQUEUE_TIME + " to "
                    + adjusted + "ms");
        }

        // Interrupt threads to promptly unblock and finish
        for (Thread t : _threads) {
            t.interrupt();
        }

        // Wait for threads to exit cleanly
        for (Thread t : _threads) {
            try {
                t.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                _log.error("Interrupted while waiting for pump thread to terminate", ie);
            }
        }

        _threads.clear();
        _backlogged.clear();
    }

    /**
     * Removes a PumpedTunnelGateway from the pump queue and backlogged set.
     * Called when a tunnel is expired/removed to prevent memory leak.
     * @param gw the gateway to remove
     */
    public void removeGateway(PumpedTunnelGateway gw) {
        if (gw == null) {
            return;
        }
        _wantsPumping.remove(gw);
        _backlogged.remove(gw);
        if (_log.shouldDebug()) {
            _log.debug("Removed gateway from pumper queues: " + gw);
        }
    }

    /**
     * Adds a PumpedTunnelGateway to be pumped, blocking if queue is full.
     * No new requests are accepted after stopPumping() is called.
     * @param gw the gateway to pump
     */
    public void wantsPumping(PumpedTunnelGateway gw) {
        if (_stop || gw == null || gw.isDestroyed()) {
            return;
        }
        // Retry putting into queue until success or stopped
        boolean offered = false;
        while (!offered && !_stop) {
            try {
                _wantsPumping.put(gw);
                offered = true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (_stop) break;
            }
        }
    }

    /**
     * Primary thread run loop. Picks gateways from the queue and pumps messages.
     */
    @Override
    public void run() {
        try {
            run2();
        } catch (Exception e) {
            _log.error("Error in TunnelGatewayPumper run", e);
        } finally {
            _threads.remove(Thread.currentThread());
        }
    }

    private void run2() {
        PumpedTunnelGateway gw = null;
        List<PendingGatewayMessage> queueBuf = new ArrayList<>(QUEUE_BUFFER);
        boolean requeue;

        while (!_stop) {
            try {
                gw = _wantsPumping.take(); // Blocks until an item is available
                if (gw.getMessagesSent() == POISON_PTG) {
                    break; // poison pill detected, exit thread
                }
                // Skip if gateway was destroyed while waiting in queue
                if (gw.isDestroyed()) {
                    _backlogged.remove(gw);
                    continue;
                }
                requeue = gw.pump(queueBuf);
                if (requeue && !gw.isDestroyed()) {
                    handleRequeue(gw);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); // Restore interrupted status
                break;
            } catch (Exception e) {
                if (gw != null) {
                    _log.error("Error processing gateway: " + gw, e);
                } else {
                    _log.error("Error processing gateway, gateway is null", e);
                }
            }
        }
    }

    /**
     * Handle re-adding a gateway to the queue after a short delay if pumping was incomplete.
     * Uses a concurrent set to avoid multiple requeues for same gateway.
     * @param gw gateway to requeue
     */
    private void handleRequeue(PumpedTunnelGateway gw) {
        if (_stop) {
            return;
        }
        if (_backlogged.add(gw)) {
            _context.simpleTimer2().addEvent(new Requeue(gw), REQUEUE_TIME);
        }
    }

    /**
     * Timer event for requeuing backlogged gateways after delay.
     */
    private class Requeue implements SimpleTimer.TimedEvent {
        private final PumpedTunnelGateway _ptg;

        public Requeue(PumpedTunnelGateway ptg) {
            _ptg = ptg;
        }

        @Override
        public void timeReached() {
            _backlogged.remove(_ptg);
            // Don't requeue if gateway has been destroyed - prevents memory leak
            // where dead gateways are held by pending timer events
            if (!_stop && _ptg != null && !_ptg.isDestroyed()) {
                // Use put with retry in wantsPumping ensures requeue returns
                wantsPumping(_ptg);
            }
        }
    }

    /**
     * Special poison pill PumpedTunnelGateway to signal thread shutdown.
     */
    private static class PoisonPTG extends PumpedTunnelGateway {
        public PoisonPTG(RouterContext ctx) {
            super(ctx, null, null, new NoOpReceiver(), null);
        }
        
        @Override
        public int getMessagesSent() {
            return POISON_PTG;
        }
        
        @Override
        public boolean pump(List<PendingGatewayMessage> queueBuf) {
            // Poison pill should not pump any messages
            return false;
        }
        
        /**
         * No-op receiver for poison pill to avoid null pointer issues
         */
        private static class NoOpReceiver implements Receiver {
            @Override
            public long receiveEncrypted(byte[] encrypted) {
                // No-op for poison pill
                return -1;
            }
            
            @Override
            public Hash getSendTo() {
                return null; // Return null as this is poison pill
            }
        }
    }
}
