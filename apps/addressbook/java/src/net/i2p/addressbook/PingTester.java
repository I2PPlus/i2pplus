package net.i2p.addressbook;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;

import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.Destination;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Ping tester for address book entries.
 * Tests I2P destinations for reachability and stores results.
 *
 * @since 0.9.68+
 */
public class PingTester {

    private final I2PAppContext _context;
    private final Log _log;
    private final NamingService _namingService;
    private final ScheduledExecutorService _scheduler;
    private final Map<String, PingResult> _pingResults;
    private final AtomicBoolean _running;
    private final File _hostsCheckFile;
    private final I2PSocketManager _socketManager;

    // Configuration defaults
    private static final long DEFAULT_PING_INTERVAL = 8 * 60 * 60 * 1000L; // 8 hours
    private static final long DEFAULT_PING_TIMEOUT = 10 * 1000L; // 10 seconds
    private static final int DEFAULT_MAX_CONCURRENT = 8;

    // Startup delay to allow router and socket manager to fully initialize
    private static final long STARTUP_DELAY_MS = 60 * 1000L; // 1 minute

    private long _pingInterval = DEFAULT_PING_INTERVAL;
    private long _pingTimeout = DEFAULT_PING_TIMEOUT;
    private int _maxConcurrent = DEFAULT_MAX_CONCURRENT;

    /**
     * Ping result data structure
     */
    public static class PingResult {
        public final boolean reachable;
        public final long timestamp;
        public final long responseTime;
        public final String status;

        public PingResult(boolean reachable, long timestamp, long responseTime, String status) {
            this.reachable = reachable;
            this.timestamp = timestamp;
            this.responseTime = responseTime;
            this.status = status;
        }

        @Override
        public String toString() {
            return reachable ? "✔" : "✖";
        }
    }

    /**
     * Construct a PingTester that uses the default naming service
     * This works with the blockfile format (hostsdb.blockfile)
     */
    public PingTester() {
        this(I2PAppContext.getGlobalContext().namingService());
    }

    /**
     * Construct a PingTester for given naming service
     *
     * @param namingService naming service to test entries from
     */
    public PingTester(NamingService namingService) {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(PingTester.class);
        _namingService = namingService;
        _scheduler = Executors.newScheduledThreadPool(2);
        _pingResults = new ConcurrentHashMap<String, PingResult>();
        _running = new AtomicBoolean(false);

        // Use config directory for hosts_check.txt
        _hostsCheckFile = new File(_context.getConfigDir(), "hosts_check.txt");

        // Initialize I2P socket manager for pinging
        try {
            _log.info("Initializing I2PSocketManager for PingTester");
            Properties options = new Properties();
            options.setProperty("i2cp.messageReliability", "BestEffort");
            options.setProperty("inbound.nickname", "PingTester");
            options.setProperty("outbound.nickname", "PingTester");
            options.setProperty("i2cp.ownDest", "true");

            _log.info("Creating I2PSocketManager with options: " + options);
            _socketManager = I2PSocketManagerFactory.createManager(options);

            if (_socketManager == null) {
                _log.error("I2PSocketManagerFactory.createManager() returned null");
                throw new RuntimeException("Failed to create I2PSocketManager");
            }

            _log.info("I2PSocketManager created successfully: " + _socketManager.getClass().getSimpleName());

        } catch (Exception e) {
            _log.error("Failed to initialize I2P socket manager for pinging", e);
            throw new RuntimeException("Failed to initialize ping functionality", e);
        }

        // Load existing ping results
        loadPingResults();
    }

    /**
     * Start periodic ping testing
     */
    public synchronized void start() {
        if (_running.get()) {
            _log.warn("PingTester already running");
            return;
        }

        _running.set(true);
        _log.info("Starting PingTester with interval " + _pingInterval + "ms (" + (_pingInterval/1000/60) + " minutes)");

        // Schedule periodic ping testing with startup delay
        _scheduler.scheduleAtFixedRate(new PingTask(), STARTUP_DELAY_MS, _pingInterval, TimeUnit.MILLISECONDS);
        _log.info("PingTester started - first ping cycle will run after " + (STARTUP_DELAY_MS/1000) + " seconds");
    }

    /**
     * Stop periodic ping testing
     */
    public synchronized void stop() {
        if (!_running.get()) {
            _log.debug("PingTester.stop() called but not running");
            return;
        }

        _log.info("Stopping PingTester");
        _running.set(false);

        // Save ping results before shutting down
        savePingResults();

        if (_socketManager != null) {
            try {
                _log.info("Destroying I2PSocketManager");
                _socketManager.destroySocketManager();
                _log.info("I2PSocketManager destroyed successfully");
            } catch (Exception e) {
                _log.error("Error closing socket manager: " + e.getMessage(), e);
            }
        }

        _log.info("Shutting down scheduler");
        _scheduler.shutdown();
        try {
            if (!_scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                _log.warn("Scheduler did not terminate gracefully, forcing shutdown");
                _scheduler.shutdownNow();
            } else {
                _log.info("Scheduler terminated gracefully");
            }
        } catch (InterruptedException e) {
            _log.warn("Interrupted while waiting for scheduler termination");
            _scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        _log.info("PingTester stopped");
    }

    /**
     * Test a single destination immediately
     *
     * @param hostname hostname to test
     * @return ping result
     */
    public PingResult testDestination(String hostname) {
        Destination destination = getDestination(hostname);
        if (destination == null) {
            return new PingResult(false, System.currentTimeMillis(), -1, "Entry not found");
        }

        long startTime = System.currentTimeMillis();
        try {
            boolean reachable = pingDestination(destination);
            long responseTime = System.currentTimeMillis() - startTime;
            return new PingResult(reachable, startTime, responseTime, reachable ? "Reachable" : "Not reachable");
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            return new PingResult(false, startTime, responseTime, "Error: " + e.getMessage());
        }
    }

    /**
     * Get ping result for a hostname
     *
     * @param hostname hostname
     * @return ping result, or null if no test has been performed
     */
    public PingResult getPingResult(String hostname) {
        return _pingResults.get(hostname);
    }

    /**
     * Get all ping results
     *
     * @return map of hostname to ping result
     */
    public Map<String, PingResult> getAllPingResults() {
        return new ConcurrentHashMap<String, PingResult>(_pingResults);
    }

    /**
     * Set ping interval in milliseconds
     *
     * @param interval interval in milliseconds
     */
    public void setPingInterval(long interval) {
        _pingInterval = interval;
    }

    /**
     * Set ping timeout in milliseconds
     *
     * @param timeout timeout in milliseconds
     */
    public void setPingTimeout(long timeout) {
        _pingTimeout = timeout;
    }

    /**
     * Set maximum concurrent pings
     *
     * @param max maximum concurrent pings
     */
    public void setMaxConcurrent(int max) {
        _maxConcurrent = max;
    }

    /**
     * Check if ping tester is running
     *
     * @return true if running
     */
    public boolean isRunning() {
        return _running.get();
    }

    /**
     * Load ping results from hosts_check.txt file
     */
    private void loadPingResults() {
        if (!_hostsCheckFile.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(_hostsCheckFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\s+", 4);
                if (parts.length >= 4) {
                    String hostname = parts[0];
                    boolean reachable = Boolean.parseBoolean(parts[1]);
                    long timestamp = Long.parseLong(parts[2]);
                    long responseTime = Long.parseLong(parts[3]);
                    String status = parts.length > 4 ? parts[4] : (reachable ? "Reachable" : "Not reachable");

                    PingResult result = new PingResult(reachable, timestamp, responseTime, status);
                    _pingResults.put(hostname, result);
                }
            }

            if (_log.shouldLog(Log.INFO)) {
                _log.info("Loaded " + _pingResults.size() + " ping results from " + _hostsCheckFile.getName());
            }
        } catch (Exception e) {
            _log.error("Error loading ping results from " + _hostsCheckFile.getAbsolutePath(), e);
        }
    }

    /**
     * Save ping results to hosts_check.txt file
     */
    private void savePingResults() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(_hostsCheckFile))) {
            writer.write("# I2P Address Book Ping Results");
            writer.newLine();
            writer.write("# Format: hostname reachable timestamp responseTime status");
            writer.newLine();
            writer.write("# Generated: " + new java.util.Date());
            writer.newLine();
            writer.newLine();

            for (Map.Entry<String, PingResult> entry : _pingResults.entrySet()) {
                String hostname = entry.getKey();
                PingResult result = entry.getValue();

                writer.write(hostname + " " + result.reachable + " " + result.timestamp +
                          " " + result.responseTime + " " + result.status);
                writer.newLine();
            }
             if (_log.shouldLog(Log.INFO)) {
                _log.info("Saved " + _pingResults.size() + " ping results to " + _hostsCheckFile.getName());
            }
        } catch (Exception e) {
            _log.error("Error saving ping results to " + _hostsCheckFile.getAbsolutePath(), e);
        }
    }

    /**
     * Get destination for a hostname from naming service
     */
    private Destination getDestination(String hostname) {
        return _namingService.lookup(hostname);
    }

    /**
     * Get all hostnames from the naming service
     */
    private Set<String> getAllHostnames() {
        Set<String> hostnames = new HashSet<String>();

        // Use NamingService.getNames() to get all hostnames
        Properties options = new Properties();
        Set<String> names = _namingService.getNames(options);
        if (names != null) {
            hostnames.addAll(names);
        }

        return hostnames;
    }

    /**
     * Ping destination with retry logic
     */
    private PingResult pingDestinationWithRetry(String hostname, Destination destination) {
        long startTime = System.currentTimeMillis();
        boolean reachable = false;
        int retryCount = 0;
        int maxRetries = 3;

        // Retry logic with exponential backoff
        while (retryCount < maxRetries && !reachable) {
            try {
                reachable = pingDestination(hostname, destination);

               if (reachable) {
                    long responseTime = System.currentTimeMillis() - startTime;
                    PingResult result = new PingResult(true, startTime, responseTime, "Reachable");
                    _pingResults.put(hostname, result);
                    _log.info("✓ " + hostname + " reachable in " + responseTime + "ms (attempt " + (retryCount + 1) + ")");
                    return result;
                } else {
                    retryCount++;
                    if (retryCount < maxRetries) {
                        long backoffMs = 1000 * (1L << retryCount); // 1s, 2s, 4s
                        _log.info("✗ " + hostname + " failed, retry " + (retryCount + 1) + "/" + maxRetries + " in " + backoffMs + "ms");
                        Thread.sleep(backoffMs);
                    }
                }
            } catch (Exception e) {
                retryCount++;
                String errorMsg = "Error: " + e.getMessage();
                _log.error("✗ " + hostname + " ERROR (attempt " + retryCount + "): " + errorMsg, e);

                if (retryCount >= maxRetries) {
                    long responseTime = System.currentTimeMillis() - startTime;
                    PingResult result = new PingResult(false, startTime, responseTime, errorMsg);
                    _pingResults.put(hostname, result);
                }

                if (retryCount < maxRetries) {
                    try {
                        Thread.sleep(1000); // 1 second delay between retries
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (!reachable) {
            _log.warn("✗ " + hostname + " failed after " + maxRetries + " attempts");
        }

        // Return final result
        return _pingResults.get(hostname);
    }

    /**
     * Ping destination using I2P socket manager ping method
     * Performs actual network ping using I2PSocketManager.ping() like i2ping
     * This creates separate tunnels that appear in router console sidebar
     */
    private boolean pingDestination(Destination destination) {
        // Try to get hostname from destination for logging and tunnel naming
        String hostname = destination.toBase64(); // fallback
        for (Map.Entry<String, Destination> entry : _destinations.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equals(destination)) {
                hostname = entry.getKey();
                break;
            }
        }

        String originalName = null;
        try {
            // Set tunnel nickname to show hostname in console sidebar
            originalName = _socketManager.getName();
            _socketManager.setName("Ping [" + hostname + "]");

            _log.info("Pinging destination: " + hostname + " with timeout: " + _pingTimeout + "ms");

            // Use I2PSocketManager.ping() method like i2ping does
            // This creates individual tunnels that appear in router console sidebar
            boolean result = _socketManager.ping(destination, 0, 0, _pingTimeout);

            if (result) {
                _log.debug("Ping successful for: " + hostname);
            } else {
                _log.debug("Ping failed for: " + hostname);
            }

            return result;
        } catch (Exception e) {
            _log.error("Ping failed with exception: " + e.getMessage(), e);
            return false;
        } finally {
            // Restore original name
            try {
                if (originalName != null) {
                    _socketManager.setName(originalName);
                }
            } catch (Exception e) {
                _log.debug("Error restoring socket manager name: " + e.getMessage());
            }
        }
    }

    /**
     * Task to ping all destinations in address book
     */
    private class PingTask implements Runnable {
        @Override
        public void run() {
            System.out.println("[PingTask] PingTask.run() called!");

            if (!_running.get()) {
                _log.warn("PingTask.run() called but _running is false");
                System.err.println("[PingTask] ERROR: _running is false");
                return;
            }

            // Get all hostnames
            Set<String> allHostnames = getAllHostnames();
            int addressBookSize = allHostnames.size();

            System.out.println("[PingTask] Address book has " + addressBookSize + " entries");

            // Wait for socket manager to be fully ready
            try {
                Thread.sleep(2000); // 2 second delay before first ping
                _log.info("Starting ping cycle for address book with " + addressBookSize + " entries");
                System.out.println("[PingTask] Starting ping cycle for address book with " + addressBookSize + " entries");
            } catch (InterruptedException e) {
                _log.warn("PingTask startup delay interrupted", e);
                System.err.println("[PingTask] ERROR: Startup delay interrupted: " + e.getMessage());
                return;
            }

            try {
                int total = 0;
                int success = 0;
                int skipped = 0;

                // Create a list of ping tasks to run concurrently
                java.util.List<java.util.concurrent.Future<Void>> futures = new java.util.ArrayList<java.util.concurrent.Future<Void>>();

                for (String hostname : allHostnames) {
                    if (!_running.get()) {
                        _log.info("Ping cycle interrupted - stopping");
                        break;
                    }

                    Destination dest = getDestination(hostname);

                    if (dest != null) {
                        total++;
                        _log.info("Processing entry " + total + "/" + addressBookSize + ": " + hostname);

                        // Submit ping task for concurrent execution
                        java.util.concurrent.Future<Void> future = _scheduler.submit(new java.util.concurrent.Callable<Void>() {
                            @Override
                            public Void call() throws Exception {
                                pingDestinationWithRetry(hostname, dest);
                                return null;
                            }
                        });
                        futures.add(future);
                    } else {
                        skipped++;
                        if (_log.shouldLog(Log.DEBUG)) {
                            _log.debug("Skipping entry with empty destination: " + hostname);
                        }
                    }
                }

                // Wait for all pings to complete
                for (java.util.concurrent.Future<Void> future : futures) {
                    try {
                        future.get();
                    } catch (Exception e) {
                        _log.error("Error waiting for ping completion: " + e.getMessage(), e);
                    }
                }

                // Count successful pings from results
                for (PingResult result : _pingResults.values()) {
                    if (result.reachable) {
                        success++;
                    }
                }

                _log.info("Ping cycle completed: " + success + "/" + total + " reachable, " + skipped + " skipped");
                savePingResults();
            } catch (Exception e) {
                _log.error("Error during ping cycle", e);
            }
        }
    }

    /**
     * Test method
     */
    public static void main(String[] args) throws Exception {
        // Use default naming service (blockfile format)
        PingTester tester = new PingTester();

        tester.start();
        // Run for 5 minutes for testing
        Thread.sleep(5 * 60 * 1000);
        tester.stop();
        // Print results
        System.out.println("\nPing Results:");
        for (Map.Entry<String, PingResult> entry : tester.getAllPingResults().entrySet()) {
            PingResult result = entry.getValue();
            System.out.println(entry.getKey() + ": " + result +
                            (result.reachable ? " (" + result.responseTime + "ms)" : ""));
        }
    }
}