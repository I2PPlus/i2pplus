package net.i2p.router.tasks;

import net.i2p.data.DataHelper;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Job;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Router health monitor and automatic recovery system.
 *
 * This watchdog runs continuously to monitor router health and detect
 * potentially hung or unresponsive states. It checks various system
 * components including job queue processing, client responsiveness,
 * and network connectivity.
 *
 * <strong>Monitoring Checks:</strong>
 * <ul>
 *   <li>Job queue liveliness - detects stuck jobs</li>
 *   <li>Client manager responsiveness - ensures clients are alive</li>
 *   <li>Network error rates - detects connectivity issues</li>
 *   <li>Communication system status - checks network health</li>
 * </ul>
 *
 * <strong>Recovery Actions:</strong>
 * <ul>
 *   <li>Logs detailed system status when problems detected</li>
 *   <li>Generates thread dumps for debugging hung states</li>
 *   <li>May force JVM restart after consecutive failures</li>
 *   <li>Configurable via watchdog.haltOnHang property</li>
 * </ul>
 *
 * The watchdog runs every minute and will attempt recovery
 * if problems persist across multiple consecutive checks.
 * This helps prevent router from becoming completely unresponsive
 * and ensures automatic recovery from transient issues.
 */
public class RouterWatchdog implements Runnable {
    private final Log _log;
    private final RouterContext _context;
    private int _consecutiveErrors;
    private volatile boolean _isRunning;
    private long _lastDump;

    private static final long MAX_JOB_RUN_LAG = 60*1000;
    private static final long MIN_DUMP_INTERVAL= 6*60*60*1000;

    /**
     * Create a new router watchdog.
     * The watchdog monitors router health and can force a restart if the router
     * appears to be hung or unresponsive.
     *
     * @param ctx the router context for accessing router services and logging
     */
    public RouterWatchdog(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(RouterWatchdog.class);
        _isRunning = true;
    }

    /**
     * Shutdown the watchdog gracefully.
     * Sets the running flag to false to stop the monitoring loop.
     * @since 0.8.8
     */
    public void shutdown() {_isRunning = false;}

    /**
     * Verify that the job queue is processing jobs normally.
     * Checks both if any job has been running for too long AND if jobs
     * are building up in the ready queue without being processed.
     *
     * @return true if job queue appears healthy, false if a job has been
     *         running longer than the maximum allowed time OR if ready jobs
     *         are accumulating without being processed
     */
    public boolean verifyJobQueueLiveliness() {
        // Check 1: Current running job timeout
        long when = _context.jobQueue().getLastJobBegin();
        long uptime = _context.router().getUptime();
        if (when >= 0) {
            long howLongAgo = _context.clock().now() - when;
            if (howLongAgo > MAX_JOB_RUN_LAG) {
                Job cur = _context.jobQueue().getLastJob();
                if (cur != null) {
                    if (_log.shouldError()) {
                        _log.error("Last job was queued up " + DataHelper.formatDuration(howLongAgo) + " ago: " + cur);
                    }
                    return false;
                }
            }
        }

        // Check 2: Jobs accumulating in ready queue
        int readyCount = _context.jobQueue().getReadyCount();
        long maxLag = _context.jobQueue().getMaxLag();

        // If we have jobs waiting and they're getting old, the queue is stuck
        if (readyCount > 0 && maxLag > MAX_JOB_RUN_LAG && uptime > 5*60*1000) {
            if (_log.shouldError()) {
                _log.error("Job queue appears stuck - " + readyCount + " ready jobs with max lag " +
                          DataHelper.formatDuration(maxLag));
            }
            return false;
        }

        // Check 3: Excessive job buildup (even if not timed out yet)
        if (readyCount > 128) { // Configurable threshold for job buildup
            if (_log.shouldWarn()) {
                _log.warn("High job queue backlog detected - " + readyCount + " ready jobs waiting");
            }
            // Only fail if this persists and lag is significant
            if (maxLag > MAX_JOB_RUN_LAG / 2) {
                if (_log.shouldError()) {
                    _log.error("Excessive job buildup with lag " + DataHelper.formatDuration(maxLag) +
                              " - queue may be hung");
                }
                return false;
            }
        }

        return true;
    }

    /**
     * Verify that client applications are responsive.
     * Delegates to the client manager to check if all client applications
     * are still responding properly.
     *
     * @return true if all clients appear healthy, false otherwise
     */
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
            if (rs != null) {r = rs.getRate(RateConstants.ONE_MINUTE);}
            double processTime = (r != null ? r.getAverageValue() : 0);
            rs = _context.statManager().getRate("bw.sendBps");
            r = null;
            if (rs != null) {r = rs.getRate(RateConstants.ONE_MINUTE);}
            double bps = (r != null ? r.getAverageValue() : 0);
            long max = Runtime.getRuntime().maxMemory();
            long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            _log.error("Watchdog status:" +
                       "\n* Comm system: " + _context.commSystem().getStatus() +
                       "\n* Peers: " + _context.commSystem().countActivePeers() +
                       "\n* Ready and waiting jobs: " + _context.jobQueue().getReadyCount() +
                       "\n* Lag (Avg / Max): " + DataHelper.formatDuration(_context.jobQueue().getAvgLag()) +
                                                 DataHelper.formatDuration(_context.jobQueue().getMaxLag()) +
                       "\n* Transit tunnels: " + _context.tunnelManager().getParticipatingCount() +
                       "\n* Send processing time: " + DataHelper.formatDuration((long)processTime) +
                       "\n* Send rate: " + DataHelper.formatSize((long)bps) + "Bps" +
                       "\n* Memory Usage: " + DataHelper.formatSize(used) + "B / " + DataHelper.formatSize(max) + 'B');

            if (_consecutiveErrors == 1) {
                _log.log(Log.CRIT, "Router appears hung, or there is severe network congestion. Watchdog starts barking!");
                 _context.router().eventLog().addEvent(EventLog.WATCHDOG);
                // This works on Linux...
                // It won't on Windows, and we can't call i2prouter.bat either, it does something completely different...
                long now = _context.clock().now();
                if (now - _lastDump > MIN_DUMP_INTERVAL) {
                    _lastDump = now;
                    ThreadDump.dump(_context, 10);
                }
            }
        }
    }

    /**
     * Main watchdog monitoring loop.
     * Runs continuously while the watchdog is active, checking router health
     * every minute and taking action if problems are detected.
     */
    public void run() {
        while (_isRunning) {
            try {Thread.sleep(60*1000);}
            catch (InterruptedException ie) {}
            monitorRouter();
        }
    }

    /**
     * Monitor router health and take action if needed.
     * Checks job queue liveliness, client responsiveness, and network status.
     * If problems are detected, logs status and may force a restart after
     * consecutive failures.
     */
    public void monitorRouter() {
        boolean ok = verifyJobQueueLiveliness();
        // If we aren't connected to the network that's why there's nobody to talk to
        long netErrors = 0;
        if (_context.commSystem().getStatus() == Status.DISCONNECTED) {netErrors = 10;}
        else {
            RateStat rs = _context.statManager().getRate("udp.sendException");
            if (rs != null) {
                Rate r = rs.getRate(RateConstants.ONE_MINUTE);
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
