package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.util.EventLog;
import net.i2p.util.Log;

/**
 * Emergency shutdown hook for JVM termination.
 * 
 * This hook is registered with the JVM Runtime to ensure clean
 * router shutdown even if the normal shutdown process is bypassed
 * or interrupted. It serves as a failsafe mechanism to handle
 * unexpected JVM termination scenarios such as:
 * <ul>
 *   <li>System.exit() calls outside normal shutdown flow</li>
 *   <li>JVM crashes or fatal errors</li>
 *   <li>Process termination signals (SIGTERM, etc.)</li>
 *   <li>Power loss or system shutdown</li>
 * </ul>
 * 
 * The hook logs the shutdown event and performs a hard shutdown
 * to prevent the Java Service Wrapper from becoming confused about
 * the router's state.
 * 
 * Note: During normal router shutdown, this hook should be cancelled
 * to avoid unnecessary emergency shutdown procedures.
 *
 *  @since 0.8.12 moved from Router.java
 */
public class ShutdownHook extends Thread {
    private final RouterContext _context;
    private static final AtomicInteger __id = new AtomicInteger();
    private final int _id;

    /**
     * Create a new shutdown hook for emergency router shutdown.
     * This hook is registered with the JVM to ensure clean shutdown
     * even if the normal shutdown process is bypassed.
     * 
     * @param ctx router context for accessing router services
     * @since 0.8.12 moved from Router.java
     */
    public ShutdownHook(RouterContext ctx) {
        _context = ctx;
        _id = __id.incrementAndGet();
    }

    /**
     * Perform emergency shutdown when JVM is terminating.
     * 
     * This method is called by the JVM during shutdown to ensure
     * the router shuts down cleanly even in abnormal termination
     * scenarios. It logs the shutdown and performs a hard shutdown.
     * 
     * This is a failsafe mechanism - normal shutdown should cancel
     * this hook to avoid unnecessary emergency procedures.
     */
    @Override
    public void run() {
        setName("Router " + _id + " shutdown");
        Log l = _context.logManager().getLog(Router.class);
        l.log(Log.CRIT, "Shutting down the router...");
        // Needed to make the wrapper happy, otherwise it gets confused
        // and thinks we haven't shut down, possibly because it
        // prevents other shutdown hooks from running
        _context.router().eventLog().addEvent(EventLog.CRASHED, RouterVersion.FULL_VERSION);
        _context.router().setKillVMOnEnd(false);
        _context.router().shutdown2(Router.EXIT_HARD);
    }
}
