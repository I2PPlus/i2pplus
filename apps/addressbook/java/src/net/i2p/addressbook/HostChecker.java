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
import java.util.concurrent.Semaphore;

import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.Destination;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.EepHead;

/**
 * Ping tester for address book entries.
 * Tests I2P destinations for reachability and stores results.
 *
 * @since 0.9.68+
 */
public class HostChecker {

    private final I2PAppContext _context;
    private final Log _log;
    private final NamingService _namingService;
    private final ScheduledExecutorService _scheduler;
    private final Map<String, PingResult> _pingResults;
    private final Map<String, Destination> _destinations;
    private final AtomicBoolean _running;
    private final File _hostsCheckFile;
    private final Semaphore _pingSemaphore;

    // Configuration defaults
    private static final long DEFAULT_PING_INTERVAL = 8 * 60 * 60 * 1000L; // 8 hours
    private static final long DEFAULT_PING_TIMEOUT = 2 * 60 * 1000L; // 2 minutes
    private static final int DEFAULT_MAX_CONCURRENT = 12;

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

        public PingResult(boolean reachable, long timestamp, long responseTime) {
            this.reachable = reachable;
            this.timestamp = timestamp;
            this.responseTime = responseTime;
        }
    }

    /**
     * Construct a HostChecker that uses the default naming service
     * This works with the blockfile format (hostsdb.blockfile)
     */
    public HostChecker() {
        this(I2PAppContext.getGlobalContext().namingService());
    }

    /**
     * Construct a HostChecker for given naming service
     *
     * @param namingService naming service to test entries from
     */
    public HostChecker(NamingService namingService) {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(HostChecker.class);
        _namingService = namingService;
        _scheduler = Executors.newScheduledThreadPool(_maxConcurrent + 2);
        _pingResults = new ConcurrentHashMap<String, PingResult>();
        _destinations = new ConcurrentHashMap<String, Destination>();
        _running = new AtomicBoolean(false);
        _pingSemaphore = new Semaphore(_maxConcurrent);

        // Use config directory/addressbook for hosts_check.txt
        File configDir = _context.getConfigDir();
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        File addressbookDir = new File(configDir, "addressbook");
        if (!addressbookDir.exists()) {
            addressbookDir.mkdirs();
        }
        _hostsCheckFile = new File(addressbookDir, "hosts_check.txt");

        // Load existing ping results
        loadPingResults();
    }

    /**
     * Start periodic ping testing
     */
    public synchronized void start() {
        if (_running.get()) {
            return;
        }

        _running.set(true);
        if (_log.shouldInfo()) {
            _log.info("Starting HostChecker with a " + (_pingInterval/1000/60) + " minute interval...");
        }

        // Schedule periodic ping testing with startup delay
        _scheduler.scheduleAtFixedRate(new PingTask(), STARTUP_DELAY_MS, _pingInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop periodic ping testing
     */
    public synchronized void stop() {
        if (!_running.get()) {
            return;
        }

        if (_log.shouldDebug()) {
            _log.debug("Stopping HostChecker...");
        }

        _running.set(false);
        // Save ping results before shutting down
        savePingResults();
        _scheduler.shutdown();

        try {
            if (!_scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                _scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            _scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
            return new PingResult(false, System.currentTimeMillis(), -1);
        }

        return pingDestination(hostname, destination);
    }

    /**
     * Ping destination using single tunnel and I2Ping-style retry handling
     * Falls back to EepHead HTTP HEAD request if ping fails
     */
    private PingResult pingDestination(String hostname, Destination destination) {
        long startTime = System.currentTimeMillis();
        I2PSocketManager pingSocketManager = null;
        if (hostname.length() > 30) {hostname = hostname.substring(0,29) + "&hellip;";}

        try {
            long tunnelBuildStart = System.currentTimeMillis();
            Properties options = new Properties();
            options.setProperty("inbound.nickname", "Ping [" + hostname.replace(".i2p", "") + "]");
            options.setProperty("outbound.nickname", "Ping [" + hostname.replace(".i2p", "") + "]");
            options.setProperty("inbound.quantity", "1");
            options.setProperty("outbound.quantity", "1");
            options.setProperty("i2cp.leaseSetType", "3");
            options.setProperty("i2cp.leaseSetEncType", "6,4");
            options.setProperty("i2cp.dontPublishLeaseSet", "true");

            pingSocketManager = I2PSocketManagerFactory.createManager(options);

            if (pingSocketManager == null) {
                if (_log.shouldWarn()) {
                    _log.warn("Failed to create HostChecker SocketManager for ping: " + hostname);
                }
                return fallbackToEepHead(hostname, startTime);
            }

            long tunnelBuildTime = System.currentTimeMillis() - tunnelBuildStart;
            if (_log.shouldDebug()) {
                _log.debug("SocketManager ready for HostChecker in " + tunnelBuildTime + "ms -> " + hostname);
            }

            // Use I2PSocketManager ping method like I2Ping does
            // Parameters: destination, localPort, remotePort, timeout
            long pingStart = System.currentTimeMillis();
            boolean reachable = pingSocketManager.ping(destination, 0, 0, _pingTimeout);
            long pingTime = System.currentTimeMillis() - pingStart;

            if (reachable) {
                PingResult result = new PingResult(true, startTime, pingTime);
                synchronized (_pingResults) {
                    _pingResults.put(hostname, result);
                }
                if (_log.shouldInfo()) {
                    _log.info("HostChecker ping [SUCCESS] -> Received response from " + hostname + " in " + pingTime + "ms");
                }
                savePingResults();
                return result;
            } else {
                if (_log.shouldInfo()) {
                    _log.info("HostChecker ping [FAILURE] -> No response from " + hostname + ", performing eephead probe...");
                }
                return fallbackToEepHead(hostname, startTime);
            }

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            String errorMsg = "Error: " + e.getMessage();
            if (_log.shouldInfo()) {
                _log.info("HostChecker ping [ERROR] -> No response from " + hostname + " (" + errorMsg + "), performing eephead probe...");
            }

            return fallbackToEepHead(hostname, startTime);

        } finally {
            // Clean up the single socket manager
            if (pingSocketManager != null) {
                try {
                    pingSocketManager.destroySocketManager();
                    if (_log.shouldDebug()) {
                        _log.debug("Destroyed HostChecker SocketManager for: " + hostname);
                    }
                } catch (Exception e) {
                    if (_log.shouldWarn()) {
                        _log.warn("Error destroying HostChecker SocketManager for " + hostname + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Fallback method to use EepHead for HTTP HEAD request testing
     * Marks site as up if any response is received
     */
    private PingResult fallbackToEepHead(String hostname, long startTime) {
        long eepHeadStart = System.currentTimeMillis();
        try {
            String url = "http://" + hostname;
            EepHead eepHead = new EepHead(_context, "127.0.0.1", 4444, 1, url);

            // Use a shorter timeout for the fallback
            int eepHeadTimeout = 30000; // 30 seconds

            boolean success = eepHead.fetch(eepHeadTimeout, -1, eepHeadTimeout);
            long responseTime = System.currentTimeMillis() - startTime;

            // Any response (even errors like 404, 500, etc.) indicates the site is up
            PingResult result = new PingResult(success, startTime, responseTime);
            synchronized (_pingResults) {
                _pingResults.put(hostname, result);
            }

            if (_log.shouldInfo()) {
                if (success) {
                    _log.info("HostChecker eephead fallback [SUCCESS] -> Received response from " + hostname + " in " + responseTime + "ms");
                } else {
                    _log.info("HostChecker eephead fallback [FAILURE] -> No response from " + hostname + " in " + responseTime + "ms");
                }
            }

            savePingResults();
            return result;

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            if (_log.shouldInfo()) {
                _log.info("HostChecker eephead probe error for " + hostname + " -> " + e.getMessage());
            }

            PingResult result = new PingResult(false, startTime, responseTime);
            synchronized (_pingResults) {
                _pingResults.put(hostname, result);
            }

            savePingResults();
            return result;
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

        // Read all lines first and identify valid ones
        List<String> validLines = new ArrayList<>();
        boolean hasInvalidLines = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(_hostsCheckFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    validLines.add(line);
                    continue;
                }

                String[] parts = line.split(",", 4);
                boolean isValid = false;

                if (parts.length >= 3) {
                    // Validate timestamp before parsing
                    if (parts[0] != null && !parts[0].isEmpty()) {
                        try {
                            long timestamp = Long.parseLong(parts[0]);
                            String hostname = parts[1];
                            boolean reachable = "y".equals(parts[2]);
                            PingResult result = new PingResult(reachable, timestamp, -1);
                            _pingResults.put(hostname, result);
                            isValid = true;
                        } catch (NumberFormatException e) {
                            // Invalid timestamp - mark for removal
                        }
                    }
                }

                if (isValid) {
                    validLines.add(line);
                } else {
                    hasInvalidLines = true;
                    if (_log.shouldWarn()) {
                        _log.warn("Removing malformed line from hosts_check.txt: " + line);
                    }
                }
            }

            // Clean up the file if we found invalid lines
            if (hasInvalidLines) {
                try {
                    writeCleanHostsCheckFile(validLines);
                    if (_log.shouldInfo()) {
                        _log.info("Cleaned up malformed entries from hosts_check.txt");
                    }
                } catch (Exception e) {
                    if (_log.shouldWarn()) {
                        _log.warn("Failed to clean up hosts_check.txt file", e);
                    }
                }
            }

            if (_log.shouldInfo()) {
                _log.info("Loaded " + _pingResults.size() + " HostChecker ping results from " + _hostsCheckFile.getName());
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Error loading HostChecker ping results from " + _hostsCheckFile.getAbsolutePath(), e);
            }
        }
    }

    /**
     * Save ping results to hosts_check.txt file
     */
    private void savePingResults() {
        try {
            // Ensure we have absolute path and directory exists
            String absolutePath = _hostsCheckFile.getAbsolutePath();

            // Ensure parent directory exists
            File parentDir = _hostsCheckFile.getParentFile();
            if (!parentDir.exists()) {
                boolean created = parentDir.mkdirs();
            }

            // Write file with explicit error handling
            StringBuilder content = new StringBuilder();
            content.append("# I2P+ Address Book Host Check\n");
            content.append("# Format: timestamp,host,reachable\n");
            content.append("# Generated: ").append(new java.util.Date()).append("\n\n");

            for (Map.Entry<String, PingResult> entry : _pingResults.entrySet()) {
                String hostname = entry.getKey();
                PingResult result = entry.getValue();
                content.append(result.timestamp).append(",").append(hostname)
                       .append(",").append(result.reachable ? "y" : "n")
                       .append("\n");
            }

            // Write using Files.write with explicit options
            java.nio.file.Path path = java.nio.file.Paths.get(absolutePath);
            java.nio.file.Files.write(path, content.toString().getBytes(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);

            if (_log.shouldInfo() && _pingResults.size() % 10 == 0) {
                int upCount = 0;
                int downCount = 0;
                for (PingResult result : _pingResults.values()) {
                    if (result.reachable) {
                        upCount++;
                    } else {
                        downCount++;
                    }
                }
                _log.info("HostChecker results saved to: " + absolutePath +
                          " -> Total hosts: " + _pingResults.size() + " (Up: " + upCount + " / Down: " + downCount + ")");
            }

        } catch (java.io.IOException e) {
            if (_log.shouldError()) {
                _log.error("IOException saving HostChecker ping results: " + e.getMessage() +
                           "\n* File path: " + _hostsCheckFile.getAbsolutePath());
            }
        } catch (Exception e) {
            _log.error("Unexpected error saving HostChecker ping results: " + e.getMessage(), e);
        }
    }

    /**
     * Write cleaned hosts_check.txt file with only valid entries
     */
    private void writeCleanHostsCheckFile(List<String> validLines) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(_hostsCheckFile))) {
            for (String line : validLines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    /**
     * Get destination for a hostname from naming service
     */
    private Destination getDestination(String hostname) {
        Destination dest = _namingService.lookup(hostname);
        if (dest != null) {
            _destinations.put(hostname, dest);
        }
        return dest;
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
     * Task to ping all destinations in address book
     */
    private class PingTask implements Runnable {
        @Override
        public void run() {
            // Set custom thread name for better debugging
            Thread.currentThread().setName("HostChecker");

            if (!_running.get()) {
                return;
            }

            // Get all hostnames and randomize order
            Set<String> allHostnamesSet = getAllHostnames();
            java.util.List<String> allHostnames = new java.util.ArrayList<String>(allHostnamesSet);
            java.util.Collections.shuffle(allHostnames, _context.random());
            int addressBookSize = allHostnames.size();

            // Wait for socket manager to be fully ready
            try {
                Thread.sleep(5000);
                if (_log.shouldInfo()) {
                    _log.info("Starting HostChecker cycle for address book with " + addressBookSize + " entries...");
                }
            } catch (InterruptedException e) {
                if (_log.shouldWarn()) {
                    _log.warn("HostChecker startup delay interrupted", e);
                }
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
                        break;
                    }

                    Destination dest = getDestination(hostname);

                    if (dest != null) {
                        total++;

                        // Submit ping task for concurrent execution with semaphore limit
                        java.util.concurrent.Future<Void> future = _scheduler.submit(new java.util.concurrent.Callable<Void>() {
                            @Override
                            public Void call() throws Exception {
                                try {
                                    // Acquire semaphore permit to limit concurrent pings
                                    _pingSemaphore.acquire();

                                    // Then add random delay (up to 5s)
                                    int randomDelay = 2000 + _context.random().nextInt(3000);
                                    Thread.sleep(randomDelay);

                                    if (_log.shouldInfo()) {
                                        _log.info("Starting HostChecker ping for: " + hostname + "...");
                                    }

                                    pingDestination(hostname, dest);
                                    return null;
                                } finally {
                                    // Always release semaphore permit
                                    _pingSemaphore.release();
                                }
                            }
                        });
                        futures.add(future);
                    } else {
                        skipped++;
                        if (_log.shouldDebug()) {
                            _log.debug("Skipping entry with empty destination: " + hostname);
                        }
                    }
                }

                // Wait for all pings to complete
                for (java.util.concurrent.Future<Void> future : futures) {
                    try {
                        future.get();
                    } catch (Exception e) {
                        if (_log.shouldWarn()) {
                            _log.warn("Error waiting for HostChecker ping completion: " + e.getMessage(), e);
                        }
                    }
                }

                // Count successful pings from results
                for (PingResult result : _pingResults.values()) {
                    if (result.reachable) {
                        success++;
                    }
                }

                if (_log.shouldInfo()) {
                    _log.info("HostChecker cycle completed: " + success + "/" + total + " reachable, " + skipped + " skipped");
                }

                // Save ping results to file after each cycle
                savePingResults();
            } catch (Exception e) {
                if (_log.shouldWarn()) {
                    _log.warn("Error during HostChecker ping cycle", e);
                }
            }
        }
    }

    /**
     * Test method
     */
    public static void main(String[] args) throws Exception {
        // Use default naming service (blockfile format)
        HostChecker tester = new HostChecker();

        tester.start();
        // Run for 5 minutes for testing
        Thread.sleep(5 * 60 * 1000);
        tester.stop();

        // Use I2P's logger for output
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        Log log = ctx.logManager().getLog(HostChecker.class);

        log.info("HostChecker ping results:");
        for (Map.Entry<String, PingResult> entry : tester.getAllPingResults().entrySet()) {
            PingResult result = entry.getValue();
            log.info(entry.getKey() + ": " + result + (result.reachable ? " (" + result.responseTime + "ms)" : ""));
        }
    }
}