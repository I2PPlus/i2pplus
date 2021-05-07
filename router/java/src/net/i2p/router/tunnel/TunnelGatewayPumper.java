package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.TryCache;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.SimpleTimer;

import net.i2p.util.SystemVersion;

/**
 * straight pumping for multithreaded inbound receivers
 * queueing for outbound I2CP receivers
**/

class TunnelGatewayPumper implements Runnable {
    private final RouterContext _context;
    private final Set<PumpedTunnelGateway> _backlogged;
    private final Set<PumpedTunnelGateway> _livepumps;
    private final Set<PumpedTunnelGateway> _inbound;
    private final Set<PumpedTunnelGateway> _outbound;
    private volatile boolean _stop;

    /**
     *  Wait just a little, but this lets the pumper queue back up.
     *  See additional comments in PTG.
     */
    private static final long REQUEUE_TIME = 50;
/*
    private static final long REQUEUE_TIME = (SystemVersion.getMaxMemory() < 1024*1024*1024 ||
                                              SystemVersion.getCores() <= 4 || SystemVersion.isSlow()) ? 50 : 30;
*/
    private static final TryCache<List<PendingGatewayMessage>> _bufferCache = new TryCache<>(new BufferFactory(), 16);

    private static class BufferFactory implements TryCache.ObjectFactory<List<PendingGatewayMessage>> {
        public List<PendingGatewayMessage> newInstance() {
            return new ArrayList<PendingGatewayMessage>(32);
        }
    }

    /** Creates a new instance of TunnelGatewayPumper */
    public TunnelGatewayPumper(RouterContext ctx) {
        _context = ctx;
        _backlogged = new ConcurrentHashSet<PumpedTunnelGateway>(16);
        _livepumps = new ConcurrentHashSet<PumpedTunnelGateway>(16);
        _inbound = new ConcurrentHashSet<PumpedTunnelGateway>(16);
        _outbound = new LinkedHashSet<PumpedTunnelGateway>(16);
//        new I2PThread(this, "Tunnel GW pumper ", true).start();
        I2PThread gwPumper = new I2PThread(this, "Tunnel GW pumper", true);
        gwPumper.setPriority(Thread.MAX_PRIORITY - 1);
        gwPumper.start();
    }

    public void stopPumping() {
        _stop = true;
        synchronized (_outbound) {
            _outbound.notify();
        }
        _backlogged.clear();
        _livepumps.clear();
        _inbound.clear();
        _outbound.clear();
    }

    public void wantsPumping(PumpedTunnelGateway gw) {
        if (!_backlogged.contains(gw) && !_stop) {
            if (gw._isInbound) {
                if (_inbound.add(gw)) { // not queued up already
                    // in the extremely unlikely case of a race
                    // we will have an additional empty pump() blocking shortly
                    // not as expensive as complicated logic here every time
                    if (!_livepumps.add(gw)) // let others return early
                        return; // somebody else working already
                    List<PendingGatewayMessage> queueBuf = _bufferCache.acquire();
                    while (_inbound.remove(gw) && !_stop) {
                        _livepumps.add(gw);
                        if (gw.pump(queueBuf)) { // extremely unlikely chance of race, pump() will block
                            _backlogged.add(gw);
                            _context.simpleTimer2().addEvent(new Requeue(gw), REQUEUE_TIME);
                        }
                        _livepumps.remove(gw); // _inbound added first, removed last.
                    }
                    _bufferCache.release(queueBuf);
                }
            } else {
                 synchronized (_outbound) { // used reentrant
                     if (_outbound.add(gw))
                         _outbound.notify();
                }
            }
        }
    }

   public void run() {
        // this also needs a livepumps logic if it were multi-threaded
        PumpedTunnelGateway gw = null;
        List<PendingGatewayMessage> queueBuf = _bufferCache.acquire();
        boolean requeue = false;
        while (!_stop) {
            try {
                synchronized (_outbound) {
                    if (requeue) { // usually happens less than 1 / hour
                        // in case another packet came in
                        _outbound.remove(gw);
                        _backlogged.add(gw);
                        _context.simpleTimer2().addEvent(new Requeue(gw), REQUEUE_TIME);
                    }
                    while (_outbound.isEmpty()) { // spurios wakeup
                        _outbound.wait();
                        if (_stop)
                            return;
                    }
                    Iterator<PumpedTunnelGateway> iter = _outbound.iterator();
                    gw = iter.next();
                    iter.remove();
                }
            } catch (InterruptedException ie) {}
            requeue = gw.pump(queueBuf); // if single thread: average queue length before this < 0.15 on busy router
        }
    }

    private class Requeue implements SimpleTimer.TimedEvent {
        private final PumpedTunnelGateway _ptg;

        public Requeue(PumpedTunnelGateway ptg) {
            _ptg = ptg;
        }

        public void timeReached() {
            _backlogged.remove(_ptg);
            wantsPumping(_ptg);
        }
    }
}
