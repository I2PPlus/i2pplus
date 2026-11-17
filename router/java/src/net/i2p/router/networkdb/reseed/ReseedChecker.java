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
import net.i2p.util.SimpleTimer2;

/**
 * Checks whether reseeding of the network database is necessary and initiates reseed requests.
 * This class manages reseed triggers based on network status, router uptime/downtime,
 * peer count, and configuration flags.
 * <p>
 * All reseeding must be done through this instance.
 * Access through {@code context.netDb().reseedChecker()}, others should not instantiate this class directly.
 *
 * @since 0.9
 */
public class ReseedChecker {
    /** The router context. */
    private final RouterContext _context;
    /** The log for the checker. */
    private final Log _log;
    /** True while a check runs. */
    private final AtomicBoolean _inProgress = new AtomicBoolean();
    /** The status message of the last check. */
    private volatile String _lastStatus = "";
    /** The error message of the last check. */
    private volatile String _lastError = "";
    /** True once the network error was logged. */
    private volatile boolean _networkLogged;
    /** True once the startup check ran. */
    private volatile boolean _alreadyRun;

    /** Minimum number of router infos before automatic reseed attempted. */
    public static final int MINIMUM = 50;

    /** Sidebar notification persistence (3 minutes). */
    private static final long STATUS_CLEAN_TIME = 3*60*1000L;

    /** Downtime threshold for forced reseed at startup (7 days). */
    private static final long RESEED_MIN_DOWNTIME = 7*24*60*60*1000L;

    /**
     * Opens a log for this router's reseed checker.
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
        boolean noReseedFile = _hasNoReseedFile();
        boolean vmCommSystem = _context.getBooleanProperty("i2p.vmCommSystem");
        boolean shuttingDown = _context.router().gracefulShutdownInProgress();
        boolean networkConnected = _hasNetworkConnection();
        long tenMinutes = 10*60*1000L;

        // Determine if reseed is allowed
        boolean shouldReseed = reseedAllowed(disabled, noReseedFile, shuttingDown, vmCommSystem,
                                             networkConnected);

        if (!shouldReseed) {
            if (disabled) {logOnceWarning(peers, "disabled by configuration");}
            else if (noReseedFile) {logOnceWarning(peers, "disabled by config file");}
            else if (shuttingDown) {logOnceWarning(peers, "prevented by router shutdown");}
            else if (!networkConnected) {
                if (!_networkLogged) {
                    _log.logAlways(Log.WARN, "Cannot reseed - no network connection");
                    _networkLogged = true;
               }
            }
            return false;
        }
        _networkLogged = false;

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
            _log.logAlways(Log.INFO, "Downloading peer RouterInfos for a new I2P installation...");
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
     * Is a reseed allowed at all, before the peer count and the downtime decide
     * how often it happens? The four opt-outs are absolute: the configuration
     * switch, either marker file in the home or config directory, a shutdown in
     * progress, and a test JVM running the VM comm system. A clearnet address
     * family is needed to fetch over.
     * <p>
     * The peer count deliberately does not appear here. A router with enough
     * peers is let through to the checks in {@link #checkReseed(int)}, which
     * reseed it once after a long downtime; written as
     * {@code (few peers and healthy) || !vmCommSystem}, the expression was true
     * on every real router, so none of the opt-outs stopped anything and a
     * disabled router reseeded anyway.
     *
     * @param disabled true if {@code router.reseedDisable} is set
     * @param noReseedFile true if one of the {@code .i2pnoreseed} marker files exists
     * @param shuttingDown true if a graceful shutdown is in progress
     * @param vmCommSystem true in a test JVM with no real network stack
     * @param networkConnected true if the host has an IPv4 or IPv6 address
     * @return true if a reseed may be attempted
     * @since 0.9.72+
     */
    static boolean reseedAllowed(boolean disabled, boolean noReseedFile, boolean shuttingDown,
                                 boolean vmCommSystem, boolean networkConnected) {
        return !disabled && !noReseedFile && !shuttingDown && !vmCommSystem && networkConnected;
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
     * Is one of the marker files that stop reseeding present? Either name in
     * either the home or the config directory is enough: they are the
     * operator's way of stopping the clearnet fetch without editing the
     * configuration.
     *
     * @return true if a marker file exists
     * @since 0.9.72+
     */
    private boolean _hasNoReseedFile() {
        File userHome = new File(System.getProperty("user.home"));
        File configDir = _context.getConfigDir();
        File[] noReseedFiles = {
            new File(userHome, ".i2pnoreseed"),
            new File(userHome, "noreseed.i2p"),
            new File(configDir, ".i2pnoreseed"),
            new File(configDir, "noreseed.i2p")
        };
        for (File f : noReseedFiles) {
            if (f.exists()) {return true;}
        }
        return false;
    }

    /**
     * Do we have a clearnet address family to fetch a reseed over?
     *
     * @return true if the host has an IPv4 or IPv6 address
     */
    private boolean _hasNetworkConnection() {
        Set<AddressType> addrs = Addresses.getConnectedAddressTypes();
        return addrs.contains(AddressType.IPV4) || addrs.contains(AddressType.IPV6);
    }

    /**
     * Starts a reseed if one is not already in progress and the configuration
     * allows reseeding at all.
     *
     * @return true if reseed was started; false if reseeding is disabled by
     *         configuration, already in progress, or failed to start
     */
    public boolean requestReseed() {
        if (_context.getBooleanProperty(Reseeder.PROP_DISABLE)) {
            _log.warnThrottled("reseed-disabled",
                               "Reseed request refused -> Disabled by configuration");
            return false;
        }
        if (_inProgress.compareAndSet(false, true)) {
            _alreadyRun = true;
            try {
                new Reseeder(_context, this).requestReseed();
                return true;
            } catch (Exception t) {
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
     * Starts a reseed from a zip or su3 URI if one is not already in progress
     * and the configuration allows reseeding at all. A reseed file uploaded
     * through {@link #requestReseed(java.io.InputStream)} is not refused here,
     * as the operator handed that file over by hand.
     *
     * @param url URI to the reseed data file (zip/su3)
     * @return true if reseed was started; false if reseeding is disabled by
     *         configuration, already in progress, or failed to start
     * @throws IllegalArgumentException if URI is invalid or unsupported format
     */
    public boolean requestReseed(URI url) throws IllegalArgumentException {
        if (_context.getBooleanProperty(Reseeder.PROP_DISABLE)) {
            _log.warnThrottled("reseed-disabled",
                               "Reseed request refused -> Disabled by configuration");
            return false;
        }
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
            } catch (Exception t) {
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
     * @param in {@link InputStream} of reseed data
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
        new StatusCleaner(_context, _lastStatus, _lastError).schedule(STATUS_CLEAN_TIME);
    }

    /**
     * Status message from the current reseed attempt.
     *
     * @return status message (may contain HTML), never null
     */
    public String getStatus() {
        return _lastStatus;
    }

    /**
     * Status message for the current reseed attempt.
     *
     * @param s status message, non-null but may be empty
     */
    void setStatus(String s) {
        _lastStatus = s;
    }

    /**
     * Error message from the last or current reseed attempt.
     *
     * @return error message (may contain HTML), never null
     */
    public String getError() {
        return _lastError;
    }

    /**
     * Error message for the last or current reseed attempt.
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
        /** The status message to clear. */
        private final String _status;
        /** The error message to clear. */
        private final String _error;

        /**
         * Clears the messages after a delay.
         *
         * @param ctx the router context
         * @param status the status message to clear
         * @param error the error message to clear
         */
        public StatusCleaner(RouterContext ctx, String status, String error) {
            super(ctx.simpleTimer2());
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
