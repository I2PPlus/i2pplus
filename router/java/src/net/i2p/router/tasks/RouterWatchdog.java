package net.i2p.router.tasks;

import net.i2p.data.DataHelper;
import net.i2p.router.Job;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Periodically check to make sure things haven't gone totally haywire (and if
 * they have, restart the JVM)
 *
 */
public class RouterWatchdog implements Runnable {
    private final Log _log;
    private final RouterContext _context;
    private int _consecutiveErrors;
    private volatile boolean _isRunning;
    private long _lastDump;

    private static final long MAX_JOB_RUN_LAG = 60*1000;
    private static final long MIN_DUMP_INTERVAL= 6*60*60*1000;

    public RouterWatchdog(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(RouterWatchdog.class);
        _isRunning = true;
    }

    /** @since 0.8.8 */
    public void shutdown() {_isRunning = false;}

    public boolean verifyJobQueueLiveliness() {
        long when = _context.jobQueue().getLastJobBegin();
        if (when < 0) {return true;}
        long howLongAgo = _context.clock().now() - when;
        if (howLongAgo > MAX_JOB_RUN_LAG) {
            Job cur = _context.jobQueue().getLastJob();
            if (cur != null) {
                if (_log.shouldError()) {
                    _log.error("Last job was queued up " + DataHelper.formatDuration(howLongAgo) + " ago: " + cur);
                }
                return false;
            } else {return true;} // no prob, just normal lag
        } else {return true;}
    }

    public boolean verifyClientLiveliness() {
        return _context.clientManager().verifyClientLiveliness();
    }

    private boolean shutdownOnHang() {
        if (!_context.getBooleanProperty("watchdog.haltOnHang")) {return false;} // prop default false

        // Client manager starts complaining after 10 minutes, and we run every minute,
        // so this will restart 30 minutes after we lose a lease, if the wrapper is present.
        if (_consecutiveErrors >= 20 && SystemVersion.hasWrapper()) {return true;}
        return false;
    }

    private void dumpStatus() {
        if (_log.shouldError()) {
            RateStat rs = _context.statManager().getRate("transport.sendProcessingTime");
            Rate r = null;
            if (rs != null) {r = rs.getRate(60*1000);}
            double processTime = (r != null ? r.getAverageValue() : 0);
            rs = _context.statManager().getRate("bw.sendBps");
            r = null;
            if (rs != null) {r = rs.getRate(60*1000);}
            double bps = (r != null ? r.getAverageValue() : 0);
            long max = Runtime.getRuntime().maxMemory();
            long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            _log.error("Watchdog status:" +
                       "\n* Comm system: " + _context.commSystem().getStatus() +
                       "\n* Peers: " + _context.commSystem().countActivePeers() +
                       "\n* Ready and waiting jobs: " + _context.jobQueue().getReadyCount() +
                       "\n* Lag: " + DataHelper.formatDuration(_context.jobQueue().getMaxLag()) +
                       "\n* Part. tunnels: " + _context.tunnelManager().getParticipatingCount() +
                       "\n* Send processing time: " + DataHelper.formatDuration((long)processTime) +
                       "\n* Send rate: " + DataHelper.formatSize((long)bps) + "Bps" +
                       "\n* Memory: " + DataHelper.formatSize(used) + "B / " + DataHelper.formatSize(max) + 'B');

            if (_consecutiveErrors == 1) {
                _log.log(Log.CRIT, "Router appears hung, or there is severe network congestion. Watchdog starts barking!");
                 _context.router().eventLog().addEvent(EventLog.WATCHDOG);
                // This works on linux...
                // It won't on windows, and we can't call i2prouter.bat either, it does something
                // completely different...
                long now = _context.clock().now();
                if (now - _lastDump > MIN_DUMP_INTERVAL) {
                    _lastDump = now;
                    ThreadDump.dump(_context, 10);
                }
            }
        }
    }

    public void run() {
        while (_isRunning) {
            try {Thread.sleep(60*1000);}
            catch (InterruptedException ie) {}
            monitorRouter();
        }
    }

    public void monitorRouter() {
        boolean ok = verifyJobQueueLiveliness();
        // If we aren't connected to the network that's why there's nobody to talk to
        long netErrors = 0;
        if (_context.commSystem().getStatus() == Status.DISCONNECTED) {netErrors = 10;}
        else {
            RateStat rs = _context.statManager().getRate("udp.sendException");
            if (rs != null) {
                Rate r = rs.getRate(60*1000);
                if (r != null) {netErrors = r.getLastEventCount();}
            }
        }

        ok = ok && (verifyClientLiveliness() || netErrors >= 5);

        if (ok) {_consecutiveErrors = 0;}
        else {
            _consecutiveErrors++;
            dumpStatus();
            if (shutdownOnHang()) {
                _log.log(Log.CRIT, "Router hung! Restart forced by Watchdog!");
                try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
                // halt and not system.exit, since some of the shutdown hooks might be misbehaving
                Runtime.getRuntime().halt(Router.EXIT_HARD_RESTART);
            }
        }
    }

}
