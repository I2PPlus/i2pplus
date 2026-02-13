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
    /** Only log status at ERROR level after this many consecutive errors */
    private static final int STATUS_ERROR_THRESHOLD = 3;
    /** Threshold for attempting runner recovery - after this many consecutive errors */
    private static final int RECOVERY_THRESHOLD = 3;
    /** Number of replacement runners to spawn during recovery */
    private static final int REPLACEMENT_RUNNERS = 4;

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
     * Checks if any job has been running for too long, which could indicate
     * a hung job queue.
     * 
     * @return true if job queue appears healthy, false if a job has been
     *         running longer than the maximum allowed time
     */
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
        // Only log full status dump at ERROR level if we've had multiple consecutive errors
        // This prevents log spam from transient issues while still alerting on persistent problems
        if (_consecutiveErrors < STATUS_ERROR_THRESHOLD) {
            return; // Skip logging for transient errors
        }
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

            if (_consecutiveErrors == STATUS_ERROR_THRESHOLD) {
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
    public void run() {
        while (_isRunning) {
            try {Thread.sleep(60*1000);}
            catch (InterruptedException ie) {}
            monitorRouter();
        }
    }

    /**
     * Attempt to recover from stuck job runners.
     * Interrupts all runners to break potential deadlocks and spawns
     * replacement runners to ensure queue processing continues.
     * 
     * @return true if recovery actions were taken
     * @since 0.9.68+
     */
    private boolean attemptRecovery() {
        // Check if we have stuck runners
        int stuckRunners = _context.jobQueue().getStuckRunnerCount();
        
        if (stuckRunners > 0) {
            _log.log(Log.CRIT, "Watchdog detected " + stuckRunners + " stuck runner(s) -> Attempting recovery");
            
            // 1. Interrupt all runners to break deadlocks
            int interrupted = _context.jobQueue().interruptAllRunners();
            if (interrupted > 0) {
                _log.info("Interrupted " + interrupted + " runner(s) to break potential deadlock");
            }
            
            // 2. Spawn replacement runners
            _context.jobQueue().spawnReplacementRunners(REPLACEMENT_RUNNERS);
            _log.info("Spawned " + REPLACEMENT_RUNNERS + " replacement runners");
            
            return true;
        }
        
        return false;
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
            
            // Attempt recovery before forcing restart
            if (_consecutiveErrors >= RECOVERY_THRESHOLD && _consecutiveErrors < STATUS_ERROR_THRESHOLD + 5) {
                attemptRecovery();
            }
            
            if (shutdownOnHang()) {
                _log.log(Log.CRIT, "Router hung! Restart forced by Watchdog!");
                try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
                // halt and not system.exit, since some of the shutdown hooks might be misbehaving
                Runtime.getRuntime().halt(Router.EXIT_HARD_RESTART);
            }
        }
    }

}
