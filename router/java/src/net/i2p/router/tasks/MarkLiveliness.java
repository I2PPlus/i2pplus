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
 *  Write a timestamp to the ping file where
 *  other routers trying to use the same configuration can see it
 *
 * @since 0.8.12 moved from Router.java
 */
public class MarkLiveliness implements SimpleTimer.TimedEvent {
    private final Router _router;
    private final File _pingFile;
    private volatile boolean _errorLogged;

    public MarkLiveliness(Router router, File pingFile) {
        _router = router;
        _pingFile = pingFile;
        _pingFile.deleteOnExit();
    }

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

