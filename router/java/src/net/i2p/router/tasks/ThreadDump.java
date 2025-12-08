package net.i2p.router.tasks;

import java.io.File;

import net.i2p.I2PAppContext;
import net.i2p.util.ShellCommand;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Thread dump utility for debugging hung router instances.
 * 
 * This class provides functionality to request Java Service Wrapper
 * to dump all thread stacks to wrapper.log file. This is essential
 * for diagnosing router hangs, deadlocks, or performance issues.
 * 
 * <strong>Platform Requirements:</strong>
 * <ul>
 *   <li>Requires Java Service Wrapper to be installed and running</li>
 *   <li>Only works on non-Windows platforms (Linux, macOS, Unix)</li>
 *   <li>Does not function on Windows due to wrapper limitations</li>
 * </ul>
 * 
 * The dump operation is asynchronous - this method signals the wrapper
 * to start the dump but does not wait for it to complete. The actual
 * thread dump will appear in wrapper.log shortly after the call returns.
 * 
 * This utility is typically called by the watchdog when router appears
 * to be unresponsive, but can also be invoked manually for debugging.
 *
 *  @since 0.9.3 moved from RouterWatchdog
 */
abstract class ThreadDump {

    /**
     * Signal the wrapper to asynchronously dump threads to wrapper.log.
     * 
     * This method sends a signal to the Java Service Wrapper to dump all
     * thread stacks to the wrapper log file. This is useful for debugging
     * hung or unresponsive router instances.
     * 
     * The method waits for the signal to be sent (which should be fast)
     * but does not wait for the actual thread dump to complete.
     * 
     * Note: Only works on non-Windows platforms with the Java Service Wrapper.
     * 
     * @param context the I2P application context for accessing directories
     * @param secondsToWait maximum seconds to wait for the signal to complete;
     *                     if &lt;= 0, don't wait for completion
     * @return true if successful, false in the following cases:
     *         - Windows platform or no wrapper available
     *         - secondsToWait &gt; 0 and operation timed out
     *         - signal failed for other reasons
     * @since 0.9.3 moved from RouterWatchdog
     */
    public static boolean dump(I2PAppContext context, int secondsToWait) {
        if (SystemVersion.isWindows() || !context.hasWrapper()) {return false;}
        ShellCommand sc = new ShellCommand();
        File i2pr = new File(context.getBaseDir(), "i2prouter");
        String[] args = new String[2];
        args[0] = i2pr.getAbsolutePath();
        args[1] = "dump";
        boolean success = sc.executeSilentAndWaitTimed(args, secondsToWait);
        if (secondsToWait <= 0) {success = true;}
        if (success) {
            Log log = context.logManager().getLog(ThreadDump.class);
            File f = new File(context.getConfigDir(), "wrapper.log");
            String loc = f.exists() ? f.getAbsolutePath() : "wrapper.log";
            log.log(Log.CRIT, "Threads dumped to " + loc);
        }
        return success;
    }

}
