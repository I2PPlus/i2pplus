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
import net.i2p.util.SimpleTimer2;

/**
 * Router instance liveliness marker for multi-instance detection.
 * <p>
 * This class periodically writes a timestamp to a "ping file" to
 * indicate that this router instance is alive and running. Other
 * router instances attempting to use the same configuration directory
 * can check this file to detect if another instance is already active.
 * <p>
 * <strong>Purpose:</strong>
 * <ul>
 *   <li>Prevent multiple router instances using same config</li>
 *   <li>Allow detection of active router instances</li>
 *   <li>Provide timestamp-based liveliness indication</li>
 *   <li>Support automatic cleanup on router shutdown</li>
 * </ul>
 * <p>
 * <strong>Behavior:</strong>
 * <ul>
 *   <li>Updates timestamp periodically while router is alive</li>
 *   <li>Deletes ping file when router shuts down</li>
 *   <li>Logs errors only once to prevent log spam</li>
 *   <li>Uses secure file writing for atomic updates</li>
 * </ul>
 * <p>
 * The ping file contains only the current timestamp in milliseconds
 * since epoch. Other instances can check the file age to determine
 * if the router is recently active or abandoned.
 *
 * @since 0.8.12 moved from Router.java
 */
public class MarkLiveliness extends SimpleTimer2.TimedEvent {
    /** The router whose liveliness is marked. */
    private final Router _router;
    /** File rewritten to mark the router alive. */
    private final File _pingFile;
    /** How often the ping file is written, in ms. */
    private final long _period;
    /** True once a write failure has been logged. */
    private volatile boolean _errorLogged;

    /**
     * Create a new liveliness marker.
     *
     * @param router the router, whose context supplies the simple timer
     * @param pingFile the file to write timestamps to
     * @param period how often to ping (ms)
     * @since 0.8.12 moved from Router.java
     */
    public MarkLiveliness(Router router, File pingFile, long period) {
        super(router.getContext().simpleTimer2());
        _router = router;
        _pingFile = pingFile;
        _period = period;
        _pingFile.deleteOnExit();
    }

    /**
     * Update the ping file with current timestamp if router is alive.
     * <p>
     * If the router is alive, writes the current timestamp to the ping file.
     * If the router is not alive, deletes the ping file to indicate that
     * this router instance is no longer running.
     * <p>
     * The ping file allows other router instances using the same configuration
     * to detect if another instance is already running.
     */
    @Override
    public void timeReached() {
        if (_router.isAlive())
            ping();
        else
            _pingFile.delete(); // NOSONAR false positive S899
        schedule(_period);
    }

    /** Writes the ping file. */
    private void ping() {
        try (FileOutputStream fos = new SecureFileOutputStream(_pingFile)) {
            fos.write(DataHelper.getASCII(Long.toString(System.currentTimeMillis())));
        } catch (IOException ioe) {
            if (!_errorLogged) {
                Log log = _router.getContext().logManager().getLog(MarkLiveliness.class);
                log.logAlways(Log.WARN, "Error writing to ping file " + _pingFile + ": " + ioe);
                _errorLogged = true;
            }
        }
    }
}

