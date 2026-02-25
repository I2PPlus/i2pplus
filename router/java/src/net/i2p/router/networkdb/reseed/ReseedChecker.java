package net.i2p.router.networkdb.reseed;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.AddressType;
import net.i2p.util.Addresses;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;

/**
 * Checks whether reseeding of the network database is necessary and initiates reseed requests.
 * This class manages reseed triggers based on network status, router uptime/downtime,
 * peer count, and configuration flags.
 *
 * All reseeding must be done through this instance.
 * Access through {@code context.netDb().reseedChecker()}, others should not instantiate this class directly.
 *
 * @since 0.9
 */
public class ReseedChecker {
    private final RouterContext _context;
    private final Log _log;
    private final AtomicBoolean _inProgress = new AtomicBoolean();
    private volatile String _lastStatus = "";
    private volatile String _lastError = "";
    private volatile boolean _networkLogged;
    private volatile boolean _alreadyRun;

    /** Minimum number of router infos before automatic reseed attempted */
    public static final int MINIMUM = 50;

    /** Sidebar notification persistence (3 minutes) */
    private static final long STATUS_CLEAN_TIME = 3*60*1000;

    /** Downtime threshold for forced reseed at startup (7 days) */
    private static final long RESEED_MIN_DOWNTIME = 7*24*60*60*1000L;

    /**
     * Constructor.
     *
     * @param context the router context
     */
    public ReseedChecker(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(ReseedChecker.class);
    }

    /**
     * Checks if a reseed is needed based on peers, uptime, downtime, and system flags.
     * Starts reseed if conditions are met.
     *
     * @param known current number of known routers (includes this router)
     * @return true if reseed started; false otherwise
     */
    public boolean checkReseed(int known) {
        int currentCount = Math.max(_context.netDb().getKnownRouters() - 1, 0);
        int peers = Math.max(known - 1, currentCount);
        boolean isHidden = _context.router().isHidden();
        long uptime = _context.router().getUptime();
        long downtime = _context.getEstimatedDowntime();
        boolean disabled = _context.getBooleanProperty(Reseeder.PROP_DISABLE);
        boolean vmCommSystem = _context.getBooleanProperty("i2p.vmCommSystem");
        boolean shuttingDown = _context.router().gracefulShutdownInProgress();
        boolean networkConnected = _hasNetworkConnection();
        long tenMinutes = 10*60*1000L;

        // Determine if reseed is allowed
        boolean shouldReseed = (peers < MINIMUM && networkConnected && !shuttingDown && !disabled) || !vmCommSystem;

        if (!shouldReseed) {
            if (disabled || vmCommSystem) {logOnceWarning(peers, "disabled by configuration");}
            else if (shuttingDown) {logOnceWarning(peers, "prevented by router shutdown");}
            else if (!networkConnected) {
                if (!_networkLogged) {
                    _log.logAlways(Log.WARN, "Cannot reseed - no network connection");
                    _networkLogged = true;
               }
            } else {_networkLogged = false;}
            return false;
        }

        // Prevent reseed if uptime less than 10 minutes (except hidden routers or minimal peers)
        if (uptime < tenMinutes && known > 1 && !isHidden) {return false;}

        // Control reseed frequency when enough peers are known
        if (known >= MINIMUM) {
            if (_alreadyRun || downtime < RESEED_MIN_DOWNTIME) {
                if (!_alreadyRun) {_alreadyRun = true;}
                return false;
            }
            _alreadyRun = true;
        }

        // Logging reseed reason
        if (known <= 1) {
            _log.logAlways(Log.INFO, "Downloading peer router information for a new I2P installation...");
        } else if (uptime > tenMinutes) {
            if (downtime > RESEED_MIN_DOWNTIME) {
                _log.logAlways(Log.WARN, "Router has been offline for a while - refreshing NetDb...");
            } else if (known < MINIMUM) {
                _log.logAlways(Log.WARN, "Less than " + MINIMUM + " RouterInfos stored on disk -> Initiating reseed...");
            }
        }

        return requestReseed();
    }

    /**
     * Logs a warning message once for reseed prevention reason based on peer count.
     *
     * @param peers number of known peers excluding this router
     * @param reason reason why reseed is disabled or prevented
     */
    private void logOnceWarning(int peers, String reason) {
        String s = (peers > 0)
            ? "Only " + peers + " peers remaining but reseed " + reason
            : "No peers remaining but reseed " + reason;
        if (!s.equals(_lastError)) {
            _lastError = s;
            _log.logAlways(Log.WARN, s);
        }
    }

    /**
     * Checks for network connection and reseed disable config files.
     *
     * @return true if network is connected and no config disables reseed; false otherwise
     */
    private boolean _hasNetworkConnection() {
        File userHome = new File(System.getProperty("user.home"));
        File configDir = _context.getConfigDir();
        File[] noReseedFiles = {
            new File(userHome, ".i2pnoreseed"),
            new File(userHome, "noreseed.i2p"),
            new File(configDir, ".i2pnoreseed"),
            new File(configDir, "noreseed.i2p")
        };
        for (File f : noReseedFiles) {
            if (f.exists()) {
                logOnceWarning(0, "disabled by config file");
                return false;
            }
        }
        Set<AddressType> addrs = Addresses.getConnectedAddressTypes();
        return addrs.contains(AddressType.IPV4) || addrs.contains(AddressType.IPV6);
    }

    /**
     * Starts a reseed if one is not already in progress.
     *
     * @return true if reseed was started; false if already in progress or failed to start
     */
    public boolean requestReseed() {
        if (_inProgress.compareAndSet(false, true)) {
            _alreadyRun = true;
            try {
                new Reseeder(_context, this).requestReseed();
                return true;
            } catch (Throwable t) {
                _log.error("Reseed failed to start", t);
                done();
                return false;
            }
        } else {
            if (_log.shouldWarn())
                _log.warn("Reseed already in progress...");
            return false;
        }
    }

    /**
     * Starts a reseed from a zip or su3 URI if one is not already in progress.
     *
     * @param url URI to the reseed data file (zip/su3)
     * @return true if reseed was started; false if already in progress or failed to start
     * @throws IllegalArgumentException if URI is invalid or unsupported format
     */
    public boolean requestReseed(URI url) throws IllegalArgumentException {
        if (_inProgress.compareAndSet(false, true)) {
            Reseeder reseeder = new Reseeder(_context, this);
            try {
                reseeder.requestReseed(url);
                return true;
            } catch (IllegalArgumentException iae) {
                if (iae.getMessage() != null)
                    setError(DataHelper.escapeHTML(iae.getMessage()));
                done();
                throw iae;
            } catch (Throwable t) {
                _log.error("Reseed failed to start", t);
                done();
                return false;
            }
        } else {
            if (_log.shouldWarn())
                _log.warn("Reseed already in progress");
            return false;
        }
    }

    /**
     * Performs a blocking reseed from a zip or su3 InputStream.
     *
     * @param in InputStream of reseed data
     * @return number of routers imported
     * @throws IOException if reseed is already in progress or an IO error occurs
     */
    public int requestReseed(InputStream in) throws IOException {
        if (_inProgress.compareAndSet(false, true)) {
            try {
                return new Reseeder(_context, this).requestReseed(in);
            } catch (IOException ioe) {
                if (ioe.getMessage() != null)
                    setError(DataHelper.escapeHTML(ioe.getMessage()));
                throw ioe;
            } finally {
                done();
            }
        } else {
            throw new IOException("Reseed already in progress...");
        }
    }

    /**
     * Checks whether a reseed is currently in progress.
     *
     * @return true if reseed in progress; false otherwise
     */
    public boolean inProgress() {
        return _inProgress.get();
    }

    /**
     * Marks reseed complete, resetting internal state and scheduling status clearance.
     */
    void done() {
        _inProgress.set(false);
        new StatusCleaner(_lastStatus, _lastError, STATUS_CLEAN_TIME);
    }

    /**
     * Gets the status message from the current reseed attempt.
     *
     * @return status message (may contain HTML), never null
     */
    public String getStatus() {
        return _lastStatus;
    }

    /**
     * Sets the status message for the current reseed attempt.
     *
     * @param s status message, non-null but may be empty
     */
    void setStatus(String s) {
        _lastStatus = s;
    }

    /**
     * Gets the error message from the last or current reseed attempt.
     *
     * @return error message (may contain HTML), never null
     */
    public String getError() {
        return _lastError;
    }

    /**
     * Sets the error message for the last or current reseed attempt.
     *
     * @param s error message, non-null but may be empty
     */
    void setError(String s) {
        _lastError = s;
    }

    /**
     * Timer event to clear stale status and error messages after a timeout.
     *
     * @since 0.9
     */
    private class StatusCleaner extends SimpleTimer2.TimedEvent {
        private final String _status, _error;

        public StatusCleaner(String status, String error, long timeoutMs) {
            super(_context.simpleTimer2(), timeoutMs);
            _status = status;
            _error = error;
        }

        @Override
        public void timeReached() {
            if (_status.equals(getStatus()))
                setStatus("");
            if (_error.equals(getError()))
                setError("");
        }
    }
}
