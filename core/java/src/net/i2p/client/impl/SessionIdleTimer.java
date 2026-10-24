package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 */

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

import java.util.Properties;

/**
 * Reduce tunnels or shutdown the session on idle if so configured.
 *
 */
class SessionIdleTimer extends SimpleTimer2.TimedEvent {
    /**
     * Lower bound on the configured reduce and shutdown times, in milliseconds.
     */
    public static final long MINIMUM_TIME = (long) 60 * 1000; // allow close time of 60 seconds
    /** Default idle time before reducing tunnels. */
    private static final long DEFAULT_REDUCE_TIME = 20 * (long) 60 * 1000;
    /** Default idle time before closing the session. */
    private static final long DEFAULT_CLOSE_TIME = 30 * (long) 60 * 1000;
    /** Logger for the session idle timer. */
    private final Log _log;
    /** The app context. */
    private final I2PAppContext _context;
    /** The session watched for idleness. */
    private final I2PSessionImpl _session;
    /** True to reduce tunnels when the session idles. */
    private final boolean _reduceEnabled;
    /** Tunnels to reduce by when the session idles. */
    private final int _reduceQuantity;
    /** Idle time before the tunnels are reduced. */
    private final long _reduceTime;
    /** True to close the session when it idles. */
    private final boolean _shutdownEnabled;
    /** Idle time before the session is closed. */
    private final long _shutdownTime;
    /** Shortest interval between the timer runs. */
    private final long _minimumTime;
    /** When the session was last active. */
    private long _lastActive;

    /**
     * Construct a timer that reduces, shuts down, or both.
     *
     * @param context the application context supplying the timer and the log manager
     * @param session the session whose i2cp options supply the idle thresholds and
     * the reduction quantity
     * @param reduce if true, cut the session's tunnel quantity back after the idle time
     * @param shutdown if true, close the session after the idle time
     */
    public SessionIdleTimer(I2PAppContext context, I2PSessionImpl session, boolean reduce, boolean shutdown) {
        super(context.simpleTimer2());
        if (!(reduce || shutdown)) {
            throw new IllegalArgumentException("At least one must be enabled");
        }
        _context = context;
        _log = context.logManager().getLog(SessionIdleTimer.class);
        _session = session;
        Properties props = session.getOptions();
        long minimumTime = Long.MAX_VALUE;
        long reduceTime = 0;
        long shutdownTime = 0;
        int reduceQuantity = 0;
        if (reduce) {
            reduceQuantity = 1;
            String p = props.getProperty("i2cp.reduceQuantity");
            if (p != null) {
                // also check vs. configured quantities?
                try {
                    reduceQuantity = Math.max(Integer.parseInt(p), 1);
                } catch (NumberFormatException nfe) { /* ignored */ }
            }
            reduceTime = DEFAULT_REDUCE_TIME;
            p = props.getProperty("i2cp.reduceIdleTime");
            if (p != null) {
                try {
                    reduceTime = Math.max(Long.parseLong(p), MINIMUM_TIME);
                } catch (NumberFormatException nfe) { /* ignored */ }
            }
            minimumTime = reduceTime;
        }
        if (shutdown) {
            shutdownTime = DEFAULT_CLOSE_TIME;
            String p = props.getProperty("i2cp.closeIdleTime");
            if (p != null) {
                try {
                    shutdownTime = Math.max(Long.parseLong(p), MINIMUM_TIME);
                } catch (NumberFormatException nfe) { /* ignored */ }
            }
            minimumTime = Math.min(minimumTime, shutdownTime);
            if (reduce && shutdownTime <= reduceTime) {
                reduce = false;
            }
        }
        _reduceEnabled = reduce;
        _reduceQuantity = reduceQuantity;
        _reduceTime = reduceTime;
        _shutdownEnabled = shutdown;
        _shutdownTime = shutdownTime;
        _minimumTime = minimumTime;
    }

    /**
     * Check session activity and reduce or destroy on idle.
     */
    @Override
    public void timeReached() {
        if (_session.isClosed()) {
            return;
        }
        long now = _context.clock().now();
        long lastActivity = _session.lastActivity();
        String sessionName = _session.getName();
        if (_log.shouldInfo()) {
            _log.info("Firing idle timer -> Last activity " + DataHelper.formatDuration(now - lastActivity) + " ago");
        }
        long nextDelay = 0;
        if (_shutdownEnabled && now - lastActivity >= _shutdownTime) {
            if (_log.shouldWarn()) {
                _log.warn("Closing tunnels on idle -> " + sessionName);
            }
            _session.destroySession();
            return;
        } else if (lastActivity <= _lastActive && !_shutdownEnabled) {
            if (_log.shouldDebug()) {
                _log.debug("Still idle, sleeping again -> " + sessionName);
            }
            nextDelay = _reduceTime;
        } else if (_reduceEnabled && now - lastActivity >= _reduceTime) {
            if (_log.shouldInfo()) {
                _log.info("Reducing tunnel quantity on idle -> " + sessionName);
            }
            try {
                _session.getProducer().updateTunnels(_session, _reduceQuantity);
            } catch (I2PSessionException ise) {
                _log.error("Error attempting to reduce tunnel count on idle for " + sessionName + " (" + ise.getMessage() + ")");
            }
            _session.setReduced();
            _lastActive = lastActivity;
            if (_shutdownEnabled) {
                nextDelay = _shutdownTime - (now - lastActivity);
            } else {
                nextDelay = _reduceTime;
            }
        } else {
            nextDelay = _minimumTime - (now - lastActivity);
        }
        schedule(nextDelay);
    }
}
