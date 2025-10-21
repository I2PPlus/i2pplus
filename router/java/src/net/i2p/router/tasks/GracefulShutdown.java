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
    private volatile boolean running = true;
    /** Delay in milliseconds to wait before shutdown/restart after conditions are met. */
    private static final int RESTART_DELAY = 15_000;

    /**
     * Creates a new GracefulShutdown instance tied to the given RouterContext.
     *
     * @param ctx the RouterContext providing router state and services
     */
    public GracefulShutdown(RouterContext ctx) {
        _context = ctx;
    }

    /**
     * Main execution loop of the graceful shutdown thread.
     * Waits indefinitely until a shutdown is initiated,
     * then monitors whether shutdown conditions are satisfied.
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
                    waitOrSleep(RESTART_DELAY); // Allow UI updates or cleanup time
                    _context.router().shutdown(exitCode);
                    running = false;
                } else {
                    waitOrSleep(RESTART_DELAY); // Wait and re-check conditions
                }
            } else {
                // Wait indefinitely on current thread object until notified to start shutdown
                waitIndefinitely();
            }
        }
    }

    private boolean shouldShutdown(int code) {
        return code == Router.EXIT_HARD || code == Router.EXIT_HARD_RESTART ||
               (code == Router.EXIT_GRACEFUL && _context.tunnelManager().getParticipatingCount() <= 0);
    }

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

    private void waitOrSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits indefinitely until notified on the current thread object.
     * This restores the original behavior of waiting and notifying on the
     * thread object, critical to the restart function working properly.
     */
    private void waitIndefinitely() {
        synchronized (Thread.currentThread()) {
            try {
                Thread.currentThread().wait();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Notifies the graceful shutdown thread to wake up from waiting.
     *
     * This method must be called by external code immediately upon shutdown initiation
     * to ensure this thread wakes and begins shutdown processing.
     */
    public void notifyShutdownStarted() {
        synchronized (Thread.currentThread()) {
            Thread.currentThread().notifyAll();
        }
    }

    /**
     * Stops the graceful shutdown thread by ending the loop and
     * interrupting any wait.
     */
    public void stop() {
        running = false;
        notifyShutdownStarted();
    }
}
