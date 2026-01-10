package net.i2p.router.tasks;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * GracefulShutdown manages the router's graceful shutdown/restart lifecycle.
 * Uses configurable timings to control when to re-check state and how long to wait
 * before finalizing a restart/shutdown after signaling.
 */
public class GracefulShutdown implements Runnable {
    private final RouterContext _context;
    private volatile boolean running = true;

    /** Delay before finalizing a restart/shutdown after a signaling condition is met (ms) */
    private static final int RESTART_DELAY_MS = 20_000;

    /** Interval between status checks while a restart/shutdown is in progress (ms) */
    private static final int CHECK_STATUS_INTERVAL_MS = 5_000;

    /**
     * Create a new graceful shutdown handler.
     * 
     * @param ctx the router context for accessing router state and services
     */
    public GracefulShutdown(RouterContext ctx) {
        _context = ctx;
    }

    @Override
    public void run() {
        Log log = _context.logManager().getLog(Router.class);
        while (running) {
            boolean shutdownInProgress = _context.router().gracefulShutdownInProgress();

            if (shutdownInProgress) {
                int exitCode = _context.router().scheduledGracefulExitCode();

                if (exitCode == Router.EXIT_HARD || exitCode == Router.EXIT_HARD_RESTART ||
                    (_context.tunnelManager().getParticipatingCount() <= 0 &&
                     !net.i2p.i2ptunnel.TunnelControllerGroup.isDelayedShutdownInProgress())) {

                    if (log.shouldWarn()) {
                        if (exitCode == Router.EXIT_HARD)
                            log.warn("Shutting down after a brief delay...");
                        else if (exitCode == Router.EXIT_HARD_RESTART)
                            log.warn("Restarting after a brief delay...");
                        else
                            log.warn("Graceful shutdown progress: No more tunnels, starting final shutdown...");
                    }

                    // Brief pause for UI/state cleanup, then finalize shutdown/restart
                    try {
                        synchronized (Thread.currentThread()) {
                            Thread.currentThread().wait(RESTART_DELAY_MS);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    _context.router().shutdown(exitCode);
                    return;
                } else {
                    // Re-check at a short, deterministic cadence
                    try {
                        Thread.sleep(CHECK_STATUS_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else {
                // Idle: wait indefinitely until signaled to re-check
                synchronized (Thread.currentThread()) {
                    try {
                        Thread.currentThread().wait();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * Wake up the shutdown thread when a relevant state change occurs.
     * This method should be called when the router's shutdown state changes
     * to prompt the graceful shutdown thread to re-evaluate the current situation.
     */
    public void wakeUp() {
        synchronized (Thread.currentThread()) {
            Thread.currentThread().notifyAll();
        }
    }

    /**
     * Stop the shutdown thread gracefully.
     * Sets the running flag to false and wakes up the thread to allow it
     * to exit cleanly. This should be called during router shutdown to
     * properly terminate the graceful shutdown monitor.
     */
    public void stop() {
        running = false;
        wakeUp();
    }
}
