package net.i2p.router.tasks;

/**
 * Shutdown delay thread to ensure complete router termination.
 * 
 * This non-daemon thread keeps the JVM alive during shutdown
 * processes to prevent premature termination. During router shutdown,
 * various cleanup tasks, file operations, and network shutdown
 * procedures need time to complete properly.
 * 
 * <strong>Purpose:</strong>
 * <ul>
 *   <li>Prevents JVM from exiting before shutdown tasks finish</li>
 *   <li>Provides 5-minute window for complete cleanup</li>
 *   <li>Ensures file operations and network graceful shutdown complete</li>
 *   <li>Allows time for shutdown hooks to execute fully</li>
 * </ul>
 * 
 * As a non-daemon thread, the JVM will not terminate until this
 * thread completes its 5-minute sleep or is interrupted. This is
 * especially important for ensuring router.info files are written
 * correctly and network connections are closed gracefully.
 *
 *  @since 0.8.12 moved from Router.java
 */
public class Spinner extends Thread {

    /**
     * Create a new shutdown spinner thread.
     * This is a non-daemon thread that keeps the JVM alive
     * during shutdown processes to ensure they complete fully.
     * 
     * @since 0.8.12 moved from Router.java
     */
    public Spinner() {
        super();
        setName("Shutdown Spinner");
        setDaemon(false);
    }

    /**
     * Keep JVM alive for shutdown tasks to complete.
     * 
     * This thread simply sleeps for 5 minutes to provide time
     * for shutdown tasks to finish. As a non-daemon thread,
     * it prevents the JVM from exiting until it completes.
     * 
     * The thread can be interrupted to terminate early if needed.
     */
    @Override
    public void run() {
        try {
            sleep(5*60*1000);
        } catch (InterruptedException ie) {}
    }
}

