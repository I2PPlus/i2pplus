package net.i2p.router.tasks;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Out-of-memory error handler for emergency router shutdown.
 * 
 * This listener is registered with the I2PThread system to handle
 * OutOfMemoryError events. When the JVM runs out of memory, this class
 * attempts to perform an orderly shutdown of the router to prevent
 * data corruption and hung processes.
 * 
 * The handler is designed to be resilient against additional OOM errors
 * during the shutdown process and will make best-effort attempts to:
 * <ul>
 *   <li>Clear caches to free memory</li>
 *   <li>Log diagnostic information</li>
 *   <li>Generate thread dumps for debugging</li>
 *   <li>Shutdown the router with appropriate exit code</li>
 * </ul>
 *
 *  @since 0.8.12 moved from Router.java
 */
public class OOMListener implements I2PThread.OOMEventListener {
    private final RouterContext _context;
    private final AtomicBoolean _wasCalled = new AtomicBoolean();

    /**
     * Create a new out-of-memory event listener.
     * 
     * @param ctx the router context for accessing router services
     * @since 0.8.12 moved from Router.java
     */
    public OOMListener(RouterContext ctx) {
        _context = ctx;
    }

    /**
     * Handle out-of-memory error by shutting down router gracefully.
     * 
     * This method is called when the JVM runs out of memory. It attempts
     * to perform emergency cleanup and shutdown the router to prevent
     * further corruption or hangs.
     * 
     * The method is designed to be resilient against additional OOM errors
     * during the shutdown process and will attempt to:
     * <ul>
     *   <li>Prevent multiple parallel shutdowns</li>
     *   <li>Increase thread priority to aid shutdown</li>
     *   <li>Clear caches to free memory</li>
     *   <li>Log memory status and configuration hints</li>
     *   <li>Generate thread dump for debugging</li>
     *   <li>Log the event to event log</li>
     *   <li>Shutdown router with OOM exit code</li>
     * </ul>
     * 
     * @param oom the out-of-memory error that triggered this handler
     */
    public void outOfMemory(OutOfMemoryError oom) {
        try {
            // prevent multiple parallel shutdowns (when you OOM, you OOM a lot...)
            if (_context.router().isFinalShutdownInProgress())
                return;
        } catch (OutOfMemoryError oome) {}
        try {
            // Only do this once
            if (_wasCalled.getAndSet(true))
                return;
        } catch (OutOfMemoryError oome) {}

        try {
            // boost priority to help us shut down
            // this may or may not do anything...
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        } catch (OutOfMemoryError oome) {}
        try {
            Router.clearCaches();
        } catch (OutOfMemoryError oome) {}
        Log log = null;
        try {
            log = _context.logManager().getLog(Router.class);
            log.log(Log.CRIT, "Thread ran out of memory, shutting down I2P", oom);
            log.log(Log.CRIT, "free mem: " + Runtime.getRuntime().freeMemory() +
                              " total mem: " + Runtime.getRuntime().totalMemory());
            String path = getWrapperConfigPath(_context);
            if (_context.hasWrapper()) {
                log.log(Log.CRIT, "To prevent future shutdowns, increase wrapper.java.maxmemory in " +
                                  path);
            } else if (!SystemVersion.isWindows()) {
                log.log(Log.CRIT, "To prevent future shutdowns, increase MAXMEMOPT in " +
                                  _context.getBaseDir() + File.separatorChar + "runplain.sh or /usr/bin/i2prouter-nowrapper");
            } else {
                log.log(Log.CRIT, "To prevent future shutdowns, run the restartable version of I2P, and increase wrapper.java.maxmemory in " +
                                  path);
            }
        } catch (OutOfMemoryError oome) {}
        try {
            ThreadDump.dump(_context, 1);
        } catch (OutOfMemoryError oome) {}
        try {
            _context.router().eventLog().addEvent(EventLog.OOM);
        } catch (OutOfMemoryError oome) {}
        try {
            _context.router().shutdown(Router.EXIT_OOM);
        } catch (OutOfMemoryError oome) {}
    }

    /**
     *  Best guess if running from a Debian package
     *  @since 0.9.35
     */
    private static boolean isDebianPackage(RouterContext ctx) {
        boolean isDebian = !SystemVersion.isWindows() && !SystemVersion.isMac() &&
                           !SystemVersion.isGentoo() && !SystemVersion.isAndroid() &&
                           System.getProperty("os.name").startsWith("Linux") &&
                           (new File("/etc/debian_version")).exists();
        return isDebian &&
               ctx.getBaseDir().getPath().equals("/usr/share/i2p") &&
               ctx.getBooleanProperty("router.updateDisabled");
    }

    /**
     * Get the best guess path for wrapper.config file.
     * 
     * This method attempts to determine the location of the wrapper
     * configuration file based on the installation type and platform.
     * Since there's no system property that provides the actual path,
     * this method makes educated guesses based on common installation patterns.
     * 
     * The path determination follows these rules:
     * <ul>
     *   <li>Linux service: /etc/i2p/wrapper.config (or /usr/share/i2p for Gentoo)</li>
     *   <li>Debian package: /etc/i2p/wrapper.config</li>
     *   <li>Other installations: {baseDir}/wrapper.config</li>
     * </ul>
     * 
     * Note: The returned path may not exist - this is just the best guess
     * for where the file should be located.
     * 
     * @param ctx the router context for determining installation type
     * @return the probable path to wrapper.config file
     * @since 0.9.35 consolidated from above and BloomFilterIVValidator
     */
    public static String getWrapperConfigPath(RouterContext ctx) {
        File path;
        if (SystemVersion.isLinuxService()) {
            if (SystemVersion.isGentoo())
                path = new File("/usr/share/i2p");
            else
                path = new File("/etc/i2p");
        } else if (isDebianPackage(ctx)) {
            path = new File("/etc/i2p");
        } else {
            path = ctx.getBaseDir();
        }
        path = new File(path, "wrapper.config");
        return path.getPath();
    }
}
