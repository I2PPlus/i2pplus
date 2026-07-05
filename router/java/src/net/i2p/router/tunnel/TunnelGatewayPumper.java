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
 *   - Non-blocking offer() with timeout prevents I2CP reader stalls.
 *   - Gateway drops are tracked via tunnel.pumperQueueFull stat.
 */
class TunnelGatewayPumper implements Runnable {
    private final RouterContext _context;
    private final Log _log;

    // Queue of gateways awaiting pumping (bounded capacity)
    private final BlockingQueue<PumpedTunnelGateway> _wantsPumping;

    // Concurrent set of gateways already in the queue, prevents duplicate offers
    private final Set<PumpedTunnelGateway> _inQueue;

    // Concurrent set of backlogged gateways waiting to be requeued
    private final Set<PumpedTunnelGateway> _backlogged;

    // Pool of threads pumping gateways
    private final List<Thread> _threads;

    // Volatile stop flag visible to all threads
    private volatile boolean _stop;

    private static final int MAX_PUMPERS;
    private static final int QUEUE_BUFFER;

    private final int _pumpers;

    /**
     * Wait time to requeue a backlogged gateway to allow task processing to catch up.
     */
    private static volatile long _requeueTime;

    /**
     * Max time to wait for pumper queue space before dropping the request.
     * Prevents blocking the I2CP reader thread indefinitely.
     */
    private static final long OFFER_TIMEOUT_MS = 50;

    static {
        int cores = SystemVersion.getCores();
        long maxMem = SystemVersion.getMaxMemory();

        // Scale pumpers: 25% of cores, min 1, max 6
        // Leave most CPU for other router operations
        int calculatedPumps = Math.max(1, cores / 4);
        if (SystemVersion.isSlow()) {
            MAX_PUMPERS = 1;  // Slow systems: single pumper
        } else {
            MAX_PUMPERS = Math.min(6, calculatedPumps);
        }

        // Scale queue buffer based on memory: 16 for <256MB, 24 for <512MB, 32 otherwise
        if (maxMem < 256 * 1024 * 1024L) {
            QUEUE_BUFFER = 16;
        } else if (maxMem < 512 * 1024 * 1024L) {
            QUEUE_BUFFER = 24;
        } else {
            QUEUE_BUFFER = 32;
        }

        // Scale requeue time inversely with cores: faster requeue with more cores
        _requeueTime = Math.max(25, 100 - (cores * 5));
    }

    /** @since 0.9.70+ */
    public static long getRequeueTime() { return _requeueTime; }

    /** @since 0.9.70+ */
    public static void setRequeueTime(long ms) { _requeueTime = Math.max(10, Math.min(200, ms)); }

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
        _inQueue = ConcurrentHashMap.newKeySet();
        _backlogged = ConcurrentHashMap.newKeySet();
        _threads = new CopyOnWriteArrayList<>();
        _pumpers = ctx.getBooleanProperty("i2p.dummyTunnelManager") ? 1 : MAX_PUMPERS;

        for (int i = 0; i < _pumpers; i++) {
            Thread t = new I2PThread(this, "TunnGWPumper " + (i + 1) + '/' + _pumpers, true);
            t.setPriority(Thread.MAX_PRIORITY);
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

        // Enqueue poison pills to unblock and stop each pumping thread
        // (do NOT clear queue - process pending items for throughput)
        for (int i = 0; i < _pumpers; i++) {
            try {
                _wantsPumping.put(new PoisonPTG(_context));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                _log.error("Interrupted while enqueuing poison pill", ie);
            }
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
        _inQueue.clear();
        _backlogged.clear();
    }

    /**
     * Requests pumping for a gateway. Uses a short timeout to avoid blocking
     * the I2CP reader thread when the pumper queue is full.
     * @param gw the gateway to pump
     */
    public void wantsPumping(PumpedTunnelGateway gw) {
        if (_stop) {
            return;
        }
        if (!_inQueue.add(gw)) {
            return; // already in queue, skip duplicate offer
        }
        try {
            if (!_wantsPumping.offer(gw, OFFER_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                _inQueue.remove(gw);
                _context.statManager().addRateData("tunnel.pumperQueueFull", 1);
                if (_log.shouldWarn())
                    _log.warn("Pumper queue full after " + OFFER_TIMEOUT_MS + "ms, gateway dropped: " + gw);
            }
        } catch (InterruptedException ie) {
            _inQueue.remove(gw);
            Thread.currentThread().interrupt();
            // Offer interrupted — gateway lost, but flag is restored for upstream handling
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
        }
    }

    private void run2() {
        PumpedTunnelGateway gw = null;
        List<PendingGatewayMessage> queueBuf = new ArrayList<>(QUEUE_BUFFER);
        boolean requeue;
        int pumpCount = 0;

        while (!_stop) {
            try {
                gw = _wantsPumping.take(); // Blocks until an item is available
                _inQueue.remove(gw);
                if (gw.getMessagesSent() == POISON_PTG) {
                    break; // poison pill detected, exit thread
                }
                requeue = gw.pump(queueBuf);
                if (requeue) {
                    handleRequeue(gw);
                }
                // Report queue depth every 100 pumps
                if (++pumpCount % 100 == 0) {
                    _context.statManager().addRateData("tunnel.pumperQueueDepth", _wantsPumping.size());
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
            _context.simpleTimer2().addEvent(new Requeue(gw), _requeueTime);
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
            if (!_stop) {
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
