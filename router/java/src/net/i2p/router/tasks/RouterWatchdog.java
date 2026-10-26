package net.i2p.router.tasks;

import net.i2p.data.DataHelper;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.util.EventLog;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Router health monitor and automatic recovery system.
 * <p>
 * This watchdog runs continuously to monitor router health and detect
 * potentially hung or unresponsive states. It checks various system
 * components including job queue processing, client responsiveness,
 * and network connectivity.
 * <p>
 * <strong>Monitoring Checks:</strong>
 * <ul>
 * <li>Job queue liveliness - detects stuck jobs</li>
 * <li>Client manager responsiveness - ensures clients are alive</li>
 * <li>Network error rates - detects connectivity issues</li>
 * <li>Communication system status - checks network health</li>
 * </ul>
 * <p>
 * <strong>Recovery Actions:</strong>
 * <ul>
 * <li>Logs detailed system status when problems detected</li>
 * <li>Generates thread dumps for debugging hung states</li>
 * <li>May force JVM restart after consecutive failures</li>
 * <li>Configurable via watchdog.haltOnHang property</li>
 * </ul>
 * <p>
 * The watchdog runs every minute and will attempt recovery
 * if problems persist across multiple consecutive checks.
 * This helps prevent router from becoming completely unresponsive
 * and ensures automatic recovery from transient issues.
 */
public class RouterWatchdog implements Runnable {
    /** The log for the watchdog. */
    private final Log _log;
    /** The router context. */
    private final RouterContext _context;
    /** Consecutive check errors so far. */
    private int _consecutiveErrors;
    /** True while the watchdog runs. */
    private volatile boolean _isRunning;
    /** When the status was last dumped. */
    private long _lastDump;

    /** Most a job may lag before a hang. */
    private static final long MAX_JOB_RUN_LAG = 60*1000L;
    /** Least interval between the status dumps. */
    private static final long MIN_DUMP_INTERVAL= 6*60*60*1000L;

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
     *
     * @since 0.8.8
     */
    public void shutdown() {_isRunning = false;}

    /**
     * Verify that the job queue is processing jobs normally.
     * <p>Only a job that is in flight can be running too long, so this reads
     * the longest-running in-flight job together with the instant it began, as
     * one value. A queue whose runners are all parked between jobs is healthy:
     * the start time of a job that finished a while ago says nothing about a
     * run in progress, and treating it as if it did reported a healthy, idle
     * queue as a 61-second stuck job.
     *
     * @return true if job queue appears healthy, false if a job has been
     *         running longer than the maximum allowed time
     */
    public boolean verifyJobQueueLiveliness() {
        JobQueue.InFlight f = _context.jobQueue().getLongestRunningJob();
        if (f == null) {return true;}
        long running = runningTooLong(f._beganOn, _context.clock().now(), MAX_JOB_RUN_LAG);
        if (running < 0) {return true;}
        if (_log.shouldError()) {
            _log.error("Job running too long -> Job: " + f._job.getName()
                       + " * Running: " + DataHelper.formatDuration(running));
        }
        return false;
    }

    /**
     * Pure decision: has the job that has been running longest run past the
     * limit?
     *
     * @param beganOn the adjusted ms timestamp the run began; 0 or -1 when
     *                nothing is in flight, which is never a hang
     * @param now the current adjusted time in ms
     * @param limitMs how long a job may run before it looks stuck
     * @return the milliseconds the job has been running, when that exceeds
     *         {@code limitMs}, otherwise -1 for nothing to report
     * @since 0.9.72+
     */
    static long runningTooLong(long beganOn, long now, long limitMs) {
        if (beganOn <= 0) {return -1;}
        long running = now - beganOn;
        return running > limitMs ? running : -1;
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

    /**
     * Whether a hang should restart the router.
     *
     * @return true with enough consecutive errors and a wrapper
     */
    private boolean shutdownOnHang() {
        if (!_context.getBooleanProperty("watchdog.haltOnHang")) {return false;} // prop default false

        // Client manager starts complaining after 10 minutes, and we run every minute,
        // so this will restart 30 minutes after we lose a lease, if the wrapper is present.
        return _consecutiveErrors >= 20 && SystemVersion.hasWrapper();
    }

    /** Writes the router status to the log. */
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

    /**
     * Main watchdog monitoring loop.
     * Runs continuously while the watchdog is active, checking router health
     * every minute and taking action if problems are detected.
     */
    @Override
    public void run() {
        while (_isRunning) {
            // Check how close leases are to expiration and adjust sleep time
            long sleepTime = getSleepTimeForLeaseExpiry();
            try {Thread.sleep(sleepTime);}
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            monitorRouter();
        }
    }

    /**
     * Calculate sleep time based on how close leases are to expiration.
     *
     * @return sleep time in milliseconds
     */
    private long getSleepTimeForLeaseExpiry() {
        // Default sleep time: 60 seconds
        long sleepTime = 60 * 1000L;

        // Check client managers for lease expiry proximity
        try {
            ClientManagerFacade cmf = _context.clientManager();
            if (cmf instanceof ClientManagerFacadeImpl) {
                ClientManagerFacadeImpl impl = (ClientManagerFacadeImpl) cmf;
                // Get the minimum time to lease expiration across all clients
                long minTimeToExpiry = impl.getMinTimeToLeaseExpiry();

                if (minTimeToExpiry < 30L * 1000) {
                    // Less than 30 seconds to expiry: check every 2 seconds (critical)
                    sleepTime = 2L * 1000;
                } else if (minTimeToExpiry < 60L * 1000) {
                    // Less than 1 minute to expiry: check every 3 seconds
                    sleepTime = 3L * 1000;
                } else if (minTimeToExpiry < 2L * 60 * 1000) {
                    // Less than 2 minutes to expiry: check every 5 seconds
                    sleepTime = 5L * 1000;
                } else if (minTimeToExpiry < 5L * 60 * 1000) {
                    // Less than 5 minutes to expiry: check every 15 seconds
                    sleepTime = 15L * 1000;
                }
            }
        } catch (Exception e) {
            // If we can't check lease expiry, use default sleep time
            if (_log.shouldWarn()) {
                _log.warn("Error checking lease expiry time, using default sleep", e);
            }
        }

        return sleepTime;
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
                try { Thread.sleep(30L*1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                // halt and not system.exit, since some of the shutdown hooks might be misbehaving
                Runtime.getRuntime().halt(Router.EXIT_HARD_RESTART);
            }
        }
    }

}
