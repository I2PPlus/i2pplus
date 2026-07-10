package net.i2p.router.transport.ntcp;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.router.RouterContext;
import net.i2p.router.Tuner;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Pool of running threads which will transform the next I2NP message into
 * something ready to be transferred over an NTCP connection, including encryption of the data read.
 * Provides efficient message serialization and connection lifecycle coordination.
 * Supports dynamic thread count adjustment via {@link #adjustThreads()}.
 *
 * @since 0.9.16 (dynamic resizing since 0.9.70+)
 */
public class Writer {
    private final Log _log;
    private final Set<NTCPConnection> _pendingConnections;
    private final Set<NTCPConnection> _liveWrites;
    private final Set<NTCPConnection> _writeAfterLive;
    private final CopyOnWriteArrayList<Runner> _runners;
    private static volatile int _threadCount = SystemVersion.isSlow() ? 2 : Math.max(SystemVersion.getCores() / 2, 3);
    private static final int MIN_THREADS = 1;
    private static final int MAX_THREADS = 16;
    private static final AtomicInteger _threadNum = new AtomicInteger();
    /** Tracks how many runner threads are actively processing (not parked). */
    private final AtomicInteger _activeCount = new AtomicInteger();

    public Writer(RouterContext ctx) {
        _log = ctx.logManager().getLog(getClass());
        _pendingConnections = new LinkedHashSet<>(128);
        _runners = new CopyOnWriteArrayList<>();
        _liveWrites = new HashSet<>(16);
        _writeAfterLive = new HashSet<>(16);
    }

    /** Get the writer thread count */
    public static int getThreadCount() { return _threadCount; }

    /** Get the number of threads currently processing (not parked). */
    public int getActiveCount() { return _activeCount.get(); }

    /**
     * Get writer pool utilization as a ratio (0.0-1.0).
     * Returns NaN if no writers are active (pool not started).
     */
    public double getUtilization() {
        int size = _runners.size();
        return size > 0 ? (double) _activeCount.get() / size : Double.NaN;
    }

    /** Set the writer thread count, bounded by MIN_THREADS-MAX_THREADS */
    public static void setThreadCount(int count) { _threadCount = Math.max(MIN_THREADS, Math.min(MAX_THREADS, count)); }

    public synchronized void startWriting(int numWriters) {
        for (int i = 1; i <= numWriters; i++) {
            startRunner();
        }
    }

    private void startRunner() {
        Runner r = new Runner();
        _runners.add(r);
        I2PThread t = new I2PThread(r, "NTCPWriter." + _threadNum.incrementAndGet(), true);
        t.start();
    }

    /**
     * Dynamically adjust thread count to match the target.
     * @since 0.9.70+
     */
    public void adjustThreads() {
        int target = _threadCount;
        int current = _runners.size();
        if (target > current) {
            for (int i = current; i < target; i++) {
                startRunner();
            }
        } else if (target < current) {
            for (int i = current - 1; i >= target; i--) {
                Runner r = _runners.remove(i);
                r.stop();
            }
            synchronized (_pendingConnections) {
                _writeAfterLive.clear();
                _pendingConnections.notifyAll();
            }
        }
    }

    public synchronized void stopWriting() {
        while (!_runners.isEmpty()) {
            Runner r = _runners.remove(0);
            r.stop();
        }
        synchronized (_pendingConnections) {
            _writeAfterLive.clear();
            _pendingConnections.notifyAll();
        }
    }

    public void wantsWrite(NTCPConnection con, String source) {
        boolean already = false;
        boolean pending = false;
        synchronized (_pendingConnections) {
            if (_liveWrites.contains(con)) {
                _writeAfterLive.add(con);
                already = true;
            } else {
                pending = _pendingConnections.add(con);
                _pendingConnections.notify();
            }
        }
        if (_log.shouldDebug())
            _log.debug("wantsWrite: " + con + " already live? " + already + " added to pending? " + pending + ": " + source);
    }

    public void connectionClosed(NTCPConnection con) {
        synchronized (_pendingConnections) {
            _writeAfterLive.remove(con);
            _pendingConnections.remove(con);
            // necessary?
            _pendingConnections.notify();
        }
    }

    private class Runner implements Runnable {

        /** a scratch space to serialize and encrypt messages */
        private final NTCPConnection.PrepBuffer _prepBuffer;

        private volatile boolean _stop;

        public Runner() {
            _prepBuffer = new NTCPConnection.PrepBuffer();
        }

        public void stop() { _stop = true; }

        public void run() {
            if (_log.shouldInfo()) _log.info("Starting writer");
            NTCPConnection con = null;
            while (!_stop) {
                Tuner.adjustHandlerPriority();
                try {
                    synchronized (_pendingConnections) {
                        boolean keepWriting = (con != null) && _writeAfterLive.remove(con);
                        if (keepWriting) {
                            // keep on writing the same one
                            if (_log.shouldDebug())
                                _log.debug("Keep writing on the same connection: " + con);
                        } else {
                            _liveWrites.remove(con);
                            con = null;
                            if (_pendingConnections.isEmpty()) {
                                if (_log.shouldDebug())
                                    _log.debug("Done writing, but nothing pending; waiting...");
                                _pendingConnections.wait();
                            } else {
                                Iterator<NTCPConnection> iter = _pendingConnections.iterator();
                                if (iter.hasNext()) {
                                    con = iter.next();
                                    iter.remove();
                                    _liveWrites.add(con);
                                } else {
                                    con = null;
                                }
                                if (_log.shouldDebug())
                                    _log.debug("Switch to writing on: " + con);
                            }
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (!_stop && (con != null)) {
                    _activeCount.incrementAndGet();
                    try {
                        if (_log.shouldDebug())
                            _log.debug("Prepare next write on: " + con);
                        _prepBuffer.init();
                        con.prepareNextWrite(_prepBuffer);
                    } catch (RuntimeException re) {
                        _log.log(Log.CRIT, "Error in the ntcp writer on " + con, re);
                    } finally {
                        _activeCount.decrementAndGet();
                    }
                }
            }
            if (_log.shouldInfo()) _log.info("Stopping writer");
        }
    }
}
