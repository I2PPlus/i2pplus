package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleTimer;

/**
 * Router instance liveliness marker for multi-instance detection.
 * 
 * This class periodically writes a timestamp to a "ping file" to
 * indicate that this router instance is alive and running. Other
 * router instances attempting to use the same configuration directory
 * can check this file to detect if another instance is already active.
 * 
 * <strong>Purpose:</strong>
 * <ul>
 *   <li>Prevent multiple router instances using same config</li>
 *   <li>Allow detection of active router instances</li>
 *   <li>Provide timestamp-based liveliness indication</li>
 *   <li>Support automatic cleanup on router shutdown</li>
 * </ul>
 * 
 * <strong>Behavior:</strong>
 * <ul>
 *   <li>Updates timestamp periodically while router is alive</li>
 *   <li>Deletes ping file when router shuts down</li>
 *   <li>Logs errors only once to prevent log spam</li>
 *   <li>Uses secure file writing for atomic updates</li>
 * </ul>
 * 
 * The ping file contains only the current timestamp in milliseconds
 * since epoch. Other instances can check the file age to determine
 * if the router is recently active or abandoned.
 *
 * @since 0.8.12 moved from Router.java
 */
public class MarkLiveliness implements SimpleTimer.TimedEvent {
    private final Router _router;
    private final File _pingFile;
    private volatile boolean _errorLogged;

    /**
     * Create a new liveliness marker.
     * 
     * @param router the router instance to monitor for liveliness
     * @param pingFile the file to write timestamps to
     * @since 0.8.12 moved from Router.java
     */
    public MarkLiveliness(Router router, File pingFile) {
        _router = router;
        _pingFile = pingFile;
        _pingFile.deleteOnExit();
    }

    /**
     * Update the ping file with current timestamp if router is alive.
     * 
     * If the router is alive, writes the current timestamp to the ping file.
     * If the router is not alive, deletes the ping file to indicate that
     * this router instance is no longer running.
     * 
     * The ping file allows other router instances using the same configuration
     * to detect if another instance is already running.
     */
    public void timeReached() {
        if (_router.isAlive())
            ping();
        else
            _pingFile.delete();
    }

    private void ping() {
        FileOutputStream fos = null;
        try {
            fos = new SecureFileOutputStream(_pingFile);
            fos.write(DataHelper.getASCII(Long.toString(System.currentTimeMillis())));
        } catch (IOException ioe) {
            if (!_errorLogged) {
                Log log = _router.getContext().logManager().getLog(MarkLiveliness.class);
                log.logAlways(Log.WARN, "Error writing to ping file " + _pingFile + ": " + ioe);
                _errorLogged = true;
            }
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
}

