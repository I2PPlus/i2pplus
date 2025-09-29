package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;

/**
 * Run through the tunnel gateways that have had messages added to them and push
 * those messages through the preprocessing and sending process.
 */
class TunnelGatewayPumper implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    private final BlockingQueue<PumpedTunnelGateway> _wantsPumping;
    private final ConcurrentHashMap<PumpedTunnelGateway, Boolean> _backlogged;
    private final List<Thread> _threads;
    private volatile boolean _stop;
    private static final int MIN_PUMPERS = 1;
    private static final int MAX_PUMPERS = SystemVersion.isSlow() ? 2 : 4;
    private static final int QUEUE_BUFFER = SystemVersion.isSlow() ? 16 : 32;
    private final int _pumpers;

    /**
     * Wait just a little, but this lets the pumper queue back up.
     */
    private static final long REQUEUE_TIME = SystemVersion.isSlow() ? 50 : 30;

    /** Creates a new instance of TunnelGatewayPumper */
    public TunnelGatewayPumper(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelGatewayPumper.class);
        _wantsPumping = new LinkedBlockingQueue<>(QUEUE_BUFFER);
        _backlogged = new ConcurrentHashMap<>(QUEUE_BUFFER);
        _threads = new CopyOnWriteArrayList<>();
        _pumpers = ctx.getBooleanProperty("i2p.dummyTunnelManager") ? 1 : MAX_PUMPERS;

        for (int i = 0; i < _pumpers; i++) {
            Thread t = new I2PThread(this, "TunnGWPumper " + (i + 1) + '/' + _pumpers, true);
            t.setPriority(I2PThread.MAX_PRIORITY - 1);
            _threads.add(t);
            t.start();
        }
    }

    public void stopPumping() {
        _stop = true;

        // Clear the pumping request queue
        _wantsPumping.clear();

        // Signal threads to terminate
        for (int i = 0; i < _pumpers; i++) {
            PumpedTunnelGateway poison = new PoisonPTG(_context);
            wantsPumping(poison); // Block thread until it stops if necessary
        }

        long adjusted = (SystemVersion.getCPULoadAvg() > 95) ? REQUEUE_TIME * 3 / 2 : REQUEUE_TIME;
        if (_log.shouldWarn() && adjusted != REQUEUE_TIME) {
            _log.warn("Router JVM under sustained high CPU load, increasing pump interval from " + REQUEUE_TIME + " to " + adjusted + "ms");
        }

        // Allow threads to process remaining tasks
        for (Thread t : _threads) {t.interrupt();}
        _threads.clear();
        _backlogged.clear();
    }

    public void wantsPumping(PumpedTunnelGateway gw) {
        if (!_stop && !_backlogged.containsKey(gw)) {
            _wantsPumping.offer(gw);
        }
    }

    public void run() {
        try {run2();}
        catch (Exception e) {_log.error("Error in TunnelGatewayPumper run", e);}
        finally {_threads.remove(Thread.currentThread());}
    }

    private void run2() {
        PumpedTunnelGateway gw = null;
        List<PendingGatewayMessage> queueBuf = new ArrayList<>(QUEUE_BUFFER);
        boolean requeue;

        while (!_stop) {
            try {
                gw = _wantsPumping.take(); // Blocks until an item is available
                if (gw.getMessagesSent() == POISON_PTG) {break;}
                requeue = gw.pump(queueBuf);
                if (requeue) {handleRequeue(gw);}
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); // Restore interrupted status
                break;
            } catch (Exception e) {_log.error("Error processing gateway: " + gw, e);}
        }
    }

    private void handleRequeue(PumpedTunnelGateway gw) {
        if (_backlogged.putIfAbsent(gw, true) == null) {
            _context.simpleTimer2().addEvent(new Requeue(gw), REQUEUE_TIME);
        }
    }

    private class Requeue implements SimpleTimer.TimedEvent {
        private final PumpedTunnelGateway _ptg;

        public Requeue(PumpedTunnelGateway ptg) {_ptg = ptg;}

        public void timeReached() {
            _backlogged.remove(_ptg);
            _wantsPumping.offer(_ptg);
        }
    }

    private static final int POISON_PTG = -99999;

    private static class PoisonPTG extends PumpedTunnelGateway {
        public PoisonPTG(RouterContext ctx) {
            super(ctx, null, null, null, null);
        }

        @Override
        public int getMessagesSent() {return POISON_PTG;}
    }

}