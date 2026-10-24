package net.i2p.router.web;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.app.ClientAppManager;
import net.i2p.app.NotificationService;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  Periodic check
 *  ref: https://dzone.com/articles/how-detect-java-deadlocks
 * <p>
 *  In routerconsole because java.lang.management is
 *  not available in Android.
 *
 * @since 0.9.55, public since 0.9.56
 */
public class DeadlockDetector extends SimpleTimer2.TimedEvent {

    /** The router being watched. */
    private final RouterContext _context;
    /** Logger for this class. */
    private final Log _log;
    /** Property giving how many hours between deadlock checks. */
    private static final String PROP_INTERVAL = "router.deadlockDetectIntervalHours";
    /** Default hours between deadlock checks. */
    private static final long DEFAULT_INTERVAL = 1;
    /** True while a deadlock is believed to be held. */
    private static final AtomicBoolean _isDeadlocked = new AtomicBoolean();

    /**
     * Start the periodic deadlock poll, scheduled only when enabled.
     *
     * @param ctx the router context to poll and to report deadlocks in
     */
    public DeadlockDetector(RouterContext ctx) {
        super(ctx.simpleTimer2());
        _context = ctx;
        _log = _context.logManager().getLog(DeadlockDetector.class);
        long interval = getInterval();
        if (interval > 0)
            schedule(interval);
    }

    /**
     * Reads the check interval from the config.
     *
     * @return the interval, in hours
     */
    private long getInterval() {
        long rv = _context.getProperty(PROP_INTERVAL, DEFAULT_INTERVAL);
        return rv * 60*60*1000L;
    }

    /**
     * Poll for deadlocks, rescheduling only when none were found.
     */
    public void timeReached() {
        long start = System.currentTimeMillis();
        boolean detected = detect();
        // only reschedule if not detected
        if (!detected) {
            long time = System.currentTimeMillis() - start;
            if (_log.shouldDebug())
                _log.debug("No deadlocks detected, took " + time + "ms");
            long interval = getInterval();
            if (interval > 0)
                schedule(interval);
        }
    }

    /**
     * Looks for a lock cycle among the router's threads.
     *
     * @return true if a deadlock was found
     */
    private boolean detect() {
        return detect(_context);
    }

    /**
     * Check this detector's router context for deadlocked threads.
     *
     * @param ctx the router context to poll and to report deadlocks in
     * @return true if a deadlock is found, false if none or if the check failed
     */
    public static boolean detect(RouterContext ctx) {
        if (_isDeadlocked.get())
            return true;
        try {
            ThreadMXBean mxb = ManagementFactory.getThreadMXBean();
            long[] ids = mxb.findDeadlockedThreads();
            if (ids == null)
                return false;
            ThreadInfo[] infos;
            try {
                // java 10
                // java 6
                infos = mxb.getThreadInfo(ids, true, true);
            } catch (UnsupportedOperationException e) {
                // won't throw
                infos = mxb.getThreadInfo(ids, Integer.MAX_VALUE);
            }
            StringBuilder buf = new StringBuilder(2048);
            String msg1 = Messages.getString("Deadlock detected", ctx) + " - " + Messages.getString("Please report", ctx);
            buf.append(msg1).append("\n\n");
            for (int i = 0; i < infos.length; i++) {
                ThreadInfo info = infos[i];
                if (info == null)
                    continue;
                buf.append("Thread ").append(i).append(':');
                buf.append(info.toString());
                StackTraceElement[] stes = info.getStackTrace();
                buf.append("        Stack Trace:\n");
                for (StackTraceElement ste : stes) {
                    buf.append("        at ").append(ste.toString()).append('\n');
                }
                buf.append('\n');
            }
            String msg2 = Messages.getString("After reporting, please restart your router", ctx);
            buf.append('\n').append(msg2).append('\n');
            Log log = ctx.logManager().getLog(DeadlockDetector.class);
            log.log(Log.CRIT, buf.toString());
            ctx.router().eventLog().addEvent(EventLog.DEADLOCK, infos.length + " threads");
            _isDeadlocked.set(true);
            ClientAppManager cmgr = ctx.clientAppManager();
            if (cmgr != null) {
                NotificationService ns = (NotificationService) cmgr.getRegisteredApp("desktopgui");
                if (ns != null) {
                    ns.notify("Router", null, Log.CRIT, Messages.getString("Router", ctx),
                              msg1 + '\n' + msg2, null);
                }
            }
        } catch (Throwable t) {
            // class not found, unsupportedoperation, ...
            Log log = ctx.logManager().getLog(DeadlockDetector.class);
            log.warn("Fail", t);
            return false;
        }
        return true;
    }

    /**
     * Return the results of the last test. Does not run a new test.
     *
     * @return the last recorded result, false before any test has run
     * @since 0.9.56
     */
    public static boolean isDeadlocked() {
        return _isDeadlocked.get();
    }

}
