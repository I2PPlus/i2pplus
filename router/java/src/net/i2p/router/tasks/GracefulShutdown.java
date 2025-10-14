package net.i2p.router.tasks;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Manages the graceful shutdown process of the router in a dedicated thread.
 * This thread waits indefinitely until a graceful shutdown is initiated, then
 * monitors the shutdown conditions, logging progress and triggering the actual
 * shutdown once conditions are met.
 * <p>
 * The thread supports clean interruption and can be externally stopped if needed.
 *
 * @since 0.8.12 Moved from Router
 */
public class GracefulShutdown implements Runnable {
    private final RouterContext _context;
    private final Object lock = new Object();
    private volatile boolean running = true;

    /**
     * Creates a new GracefulShutdown instance tied to the given RouterContext.
     *
     * @param ctx the RouterContext providing router state and services
     */
    public GracefulShutdown(RouterContext ctx) {
        _context = ctx;
    }

    /**
     * Main execution loop of the graceful shutdown thread. Waits indefinitely
     * until a shutdown is initiated, then monitors whether shutdown conditions
     * are satisfied (such as no active tunnels or specific exit codes). Logs
     * status updates and triggers shutdown when ready.
     */
    @Override
    public void run() {
        Log log = _context.logManager().getLog(Router.class);

        while (running) {
            boolean shutdownInProgress = _context.router().gracefulShutdownInProgress();

            if (shutdownInProgress) {
                int exitCode = _context.router().scheduledGracefulExitCode();

                if (shouldShutdown(exitCode)) {
                    logShutdownMessage(exitCode, log);
                    waitOrSleep(10_000); // Allow UI updates or cleanup time
                    _context.router().shutdown(exitCode);
                    running = false;
                } else {
                    waitOrSleep(10_000); // Wait and re-check conditions
                }
            } else {
                waitIndefinitely(); // Wait until notified of shutdown start
            }
        }
    }

    /**
     * Determines if the router should proceed with shutdown based on the exit code
     * and current tunnel participation.
     *
     * @param code scheduled graceful exit code
     * @return true if shutdown conditions are met, false otherwise
     */
    private boolean shouldShutdown(int code) {
        return code == Router.EXIT_HARD || code == Router.EXIT_HARD_RESTART ||
               (code == Router.EXIT_GRACEFUL && _context.tunnelManager().getParticipatingCount() <= 0);
    }

    /**
     * Logs an appropriate message based on the shutdown exit code.
     *
     * @param code the exit code determining shutdown type
     * @param log  the log instance to use for logging the messages
     */
    private void logShutdownMessage(int code, Log log) {
        switch (code) {
            case Router.EXIT_HARD:
                log.log(Log.CRIT, "Shutting down after a brief delay...");
                break;
            case Router.EXIT_HARD_RESTART:
                log.log(Log.CRIT, "Restarting after a brief delay...");
                break;
            case Router.EXIT_GRACEFUL:
                log.log(Log.CRIT, "Graceful restart -> No active transit tunnels, restarting...");
                break;
            default:
                log.log(Log.CRIT, "Graceful shutdown -> No active transit tunnels, starting final shutdown...");
                break;
        }
    }

    /**
     * Sleeps for the specified duration in milliseconds, handling interrupts by
     * restoring the interrupt status.
     *
     * @param millis number of milliseconds to sleep
     */
    private void waitOrSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits indefinitely until notified, handling interrupts by restoring the
     * interrupt status.
     */
    private void waitIndefinitely() {
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Stops the graceful shutdown thread. This method notifies the thread to exit
     * any waiting state and end its loop cleanly.
     */
    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * Notifies the graceful shutdown thread to wake up from waiting state, e.g.,
     * when a shutdown is initiated.
     */
    public void notifyShutdownStarted() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
