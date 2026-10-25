package net.i2p.addressbook;

import java.util.Map;
import java.util.HashMap;

/**
 * Bridge class to provide static access to HostChecker functionality for JSP pages
 * This avoids reflection issues and provides a clean interface.
 */
public class HostCheckerBridge {

    /**
     * Constructor. The checker is held in a static field set by Daemon, so an
     * instance carries no state of its own.
     */
    public HostCheckerBridge() {}

    /** The shared checker, created on first use. */
    private static HostChecker hostChecker;
    /** Guards creating and replacing the checker. */
    private static final Object lock = new Object();

    /**
     * Get singleton HostChecker instance
     * This should be set by Daemon.java to avoid duplicate instances.
     * @return the shared HostChecker, or null before Daemon sets it
     */
    public static HostChecker getInstance() {
        synchronized (lock) {
            return hostChecker;
        }
    }

    /**
     * Set HostChecker instance (should be called by Daemon.java).
     *
     * @param checker the shared HostChecker that addressbook calls will ping through
     */
    public static void setInstance(HostChecker checker) {
        synchronized (lock) {
            hostChecker = checker;
        }
    }

    /**
     * Ping a single host and return as result as a Map.
     * @param host hostname to ping
     * @return Map containing ping results (status, reachable, responseTime, timestamp)
     */
    public static Map<String, String> ping(String host) {
        Map<String, String> result = new HashMap<>();

        HostChecker checker = getInstance();
        if (checker == null) {
            result.put("status", "error");
            result.put("error", "HostChecker not initialized");
            return result;
        }

        try {
            HostChecker.PingResult pingResult = checker.testDestination(host);
            if (pingResult != null) {
                result.put("status", pingResult.reachable ? "success" : "failed");
                result.put("reachable", String.valueOf(pingResult.reachable));
                result.put("responseTime", String.valueOf(pingResult.responseTime));
                result.put("timestamp", String.valueOf(pingResult.timestamp));
            } else {
                result.put("status", "error");
                result.put("error", "No ping result returned");
            }
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Get all current ping results.
     * @return Map of all ping results
     */
    public static Map<String, HostChecker.PingResult> getAllPingResults() {
        HostChecker checker = getInstance();
        if (checker == null) {
            return new HashMap<>();
        }
        return checker.getAllPingResults();
    }

    /**
     * Start periodic ping checking.
     */
    public static void startPeriodicChecking() {
        HostChecker checker = getInstance();
        if (checker != null) {
            checker.start();
        }
    }

    /**
     * Stop periodic ping checking.
     */
    public static void stopPeriodicChecking() {
        HostChecker checker = getInstance();
        if (checker != null) {
            checker.stop();
        }
    }

    /**
     * Run a host check cycle immediately, unless one is already running.
     */
    public static void runCheckNow() {
        HostChecker checker = getInstance();
        if (checker != null) {
            checker.runCheckNow();
        }
    }

    /**
     * Whether a host check cycle is currently in progress.
     *
     * @return true if a cycle is running
     */
    public static boolean isScanRunning() {
        HostChecker checker = getInstance();
        return checker != null && checker.isScanRunning();
    }

    /**
     * Get description for a category.
     * @param category category name
     * @return category description, or category itself if unknown
     */
    public static String getCategoryDescription(String category) {
        HostChecker checker = getInstance();
        if (checker == null) {
            return category;
        }
        return checker.getCategoryDescription(category);
    }

    /**
     * Escape a category name for display in the address book page. Categories
     * come from a remotely downloaded feed over plaintext HTTP, so the value
     * is untrusted: it is rendered into quoted attributes and as element text,
     * and an unescaped quote or angle bracket could break out of either.
     * Callers must also quote any attribute the value is written into.
     *
     * @param category the raw category, may be null
     * @return the HTML-escaped category, or null if the input was null
     * @since 0.9.72+
     */
    public static String escapeForDisplay(String category) {
        return net.i2p.data.DataHelper.escapeHTML(category);
    }

}
