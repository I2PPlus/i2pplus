package net.i2p.addressbook;

import java.util.Map;
import java.util.HashMap;

/**
 * Bridge class to provide static access to HostChecker functionality for JSP pages
 * This avoids reflection issues and provides a clean interface
 */
public class HostCheckerBridge {

    private static HostChecker hostChecker;
    private static final Object lock = new Object();

    /**
     * Get singleton HostChecker instance
     * This should be set by Daemon.java to avoid duplicate instances
     */
    public static HostChecker getInstance() {
        synchronized (lock) {
            return hostChecker;
        }
    }

    /**
     * Set HostChecker instance (should be called by Daemon.java)
     */
    public static void setInstance(HostChecker checker) {
        synchronized (lock) {
            hostChecker = checker;
        }
    }

    /**
     * Ping a single host and return as result as a Map
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
     * Get all current ping results
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
     * Start periodic ping checking
     */
    public static void startPeriodicChecking() {
        HostChecker checker = getInstance();
        if (checker != null) {
            checker.start();
        }
    }

    /**
     * Stop periodic ping checking
     */
    public static void stopPeriodicChecking() {
        HostChecker checker = getInstance();
        if (checker != null) {
            checker.stop();
        }
    }

    /**
     * Get description for a category
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

}
