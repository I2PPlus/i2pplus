package net.i2p.addressbook;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.text.SimpleDateFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.Job;
import net.i2p.router.JobTiming;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.EepGet;
import net.i2p.util.EepHead;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

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
    private Map<String, String> _hostCategories;
    private final AtomicBoolean _running;
    private final AtomicBoolean _categoriesDownloaded;
    private final AtomicBoolean _categoriesDownloadSuccessful;
    private final File _hostsCheckFile;
    private final File _categoriesFile;
    private final File _blacklistFile;
    private final Semaphore _pingSemaphore;
    private Set<String> _blacklistedHosts;
    private volatile long _blacklistLastModified;
    private int _categoryRetryCount = 0;

    // Configuration defaults
    private static final long DEFAULT_PING_INTERVAL = 8 * 60 * 60 * 1000L; // 8 hours
    private static final long DEFAULT_PING_TIMEOUT = 90 * 1000L; // 90 seconds
    private static final int DEFAULT_MAX_CONCURRENT = 12;

    // Startup delay to allow router and socket manager to fully initialize
    private static final long STARTUP_DELAY_MS = 60 * 1000L; // 1 minute

    // Additional delay for category download to allow HTTP proxy to be ready
    private static final long CATEGORY_DOWNLOAD_DELAY_MS = 120 * 1000L; // 2 minutes

    // Category download timeout settings
    private static final int CATEGORY_CONNECT_TIMEOUT = 60 * 1000; // 1 minute
    private static final int CATEGORY_INACTIVITY_TIMEOUT = 60 * 1000; // 1 minute
    private static final int CATEGORY_TOTAL_TIMEOUT = 120 * 1000; // 2 minutes

    // Category retry settings
    private static final long CATEGORY_RETRY_INTERVAL = 10 * 60 * 1000L; // 10 minutes
    private static final int MAX_CATEGORY_RETRIES = -1; // -1 for infinite retries

    private long _pingInterval = DEFAULT_PING_INTERVAL;
    private long _pingTimeout = DEFAULT_PING_TIMEOUT;
    private int _maxConcurrent = DEFAULT_MAX_CONCURRENT;
    private boolean _useLeaseSetCheck = true;

    /**
     * Ping result data structure
     */
    public static class PingResult {
        public final boolean reachable;
        public final long timestamp;
        public final long responseTime;
        public final String category;

        public PingResult(boolean reachable, long timestamp, long responseTime, String category) {
            this.reachable = reachable;
            this.timestamp = timestamp;
            this.responseTime = responseTime;
            this.category = category;
        }

        public PingResult(boolean reachable, long timestamp, long responseTime) {
            this(reachable, timestamp, responseTime, null);
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
        _categoriesDownloaded = new AtomicBoolean(false);
        _categoriesDownloadSuccessful = new AtomicBoolean(false);
        _pingSemaphore = new Semaphore(_maxConcurrent);

        // Only enable LeaseSet checking if we have access to the network database
        // This will be true when running inside the router (RouterContext)
        _useLeaseSetCheck = (_context instanceof RouterContext);

        if (_log.shouldInfo()) {
            _log.info("HostChecker initialized with LeaseSet checking " +
                     (_useLeaseSetCheck ? "enabled" : "disabled"));
        }

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
        _categoriesFile = new File(addressbookDir, "categories.txt");
        _blacklistFile = new File(addressbookDir, "blacklist.txt");
        _blacklistedHosts = new ConcurrentHashMap<String, String>().keySet();
        loadBlacklist();

        // Download categories and load existing ping results
        downloadCategories();
        loadPingResults();
        loadCategories();

        // Schedule periodic category retries
        scheduleCategoryRetries();
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

        // Wait for categories to be downloaded before starting ping cycle
        Thread categoryWaiter = new Thread("CategoryDownloadWaiter") {
            @Override
            public void run() {
                try {
                    // Wait up to 5 minutes for category download to complete
                    for (int i = 0; i < 300; i++) {
                        if (_categoriesDownloaded.get()) {
                            if (_log.shouldInfo()) {
                                _log.info("Categories downloaded successfully from notbob.i2p -> Starting ping cycle...");
                            }
                            _scheduler.scheduleAtFixedRate(new PingTask(), STARTUP_DELAY_MS, _pingInterval, TimeUnit.MILLISECONDS);
                            return;
                        }
                        Thread.sleep(1000); // Wait 1 second
                    }

                    // Timeout reached - start ping cycle anyway
                    if (_log.shouldWarn()) {
                        _log.warn("Categories failed to download from notbob.i2p (timeout) -> Starting ping cycle...");
                    }
                    _scheduler.scheduleAtFixedRate(new PingTask(), STARTUP_DELAY_MS, _pingInterval, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (_log.shouldWarn()) {
                        _log.warn("Category download waiter interrupted");
                    }
                }
            }
        };
        categoryWaiter.setDaemon(true);
        categoryWaiter.start();
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
        if (isHostBlacklisted(hostname)) {
            if (_log.shouldInfo()) {
                _log.info("Skipping blacklisted host in testDestination: " + hostname);
            }
            return createPingResult(false, System.currentTimeMillis(), -1, hostname);
        }

        Destination destination = getDestination(hostname);
        if (destination == null) {
            return createPingResult(false, System.currentTimeMillis(), -1, hostname);
        }

        // First try ping (I2PSocketManager)
        PingResult pingResult = pingDestination(hostname, destination);
        if (pingResult.reachable) {
            return pingResult; // Ping successful, host is reachable
        }

        // If ping failed, try EepHead HTTP HEAD request
        PingResult eepHeadResult = fallbackToEepHead(hostname, pingResult.timestamp);
        if (eepHeadResult.reachable) {
            return eepHeadResult; // EepHead successful, host is reachable
        }

        // Both ping and EepHead failed - perform final LeaseSet check
        if (_useLeaseSetCheck) {
            if (_log.shouldInfo()) {
                _log.info("HostChecker head [FAILURE] -> No response from " + hostname + ", trying LeaseSet lookup...");
            }
            return checkLeaseSetFinal(hostname, destination);
        } else {
            // No LeaseSet checking available, return the failed EepHead result
            return eepHeadResult;
        }
    }

    /**
     * Perform final reachability check using LeaseSet lookup via floodfill
     * This is the definitive check - if no LeaseSet is found anywhere in the network,
     * the host is considered permanently offline and should be removed from the addressbook
     */
    private PingResult checkLeaseSetFinal(String hostname, Destination destination) {
        long startTime = System.currentTimeMillis();

        try {
            // Get the network database (only available in RouterContext)
            if (!(_context instanceof RouterContext)) {
                if (_log.shouldDebug()) {
                    _log.debug("Not running in RouterContext for LeaseSet check: " + hostname);
                }
                return pingDestination(hostname, destination); // fallback to ping
            }

            RouterContext routerContext = (RouterContext) _context;
            NetworkDatabaseFacade netDb = routerContext.netDb();

            // Look up the LeaseSet for this destination via floodfill
            Hash destHash = destination.calculateHash();

            // Try local lookup first (faster)
            LeaseSet leaseSet = netDb.lookupLeaseSetLocally(destHash);

            if (leaseSet != null) {
                long responseTime = System.currentTimeMillis() - startTime;

                // Check if leases are still current and valid
                boolean isCurrent = leaseSet.isCurrent(_context.clock().now());

                PingResult result = createPingResult(isCurrent, startTime, responseTime, hostname);
                synchronized (_pingResults) {
                    _pingResults.put(hostname, result);
                }

                savePingResults();
                return result;
            } else {
                // LeaseSet not found locally, perform remote lookup via floodfill
                return lookupLeaseSetRemotely(hostname, destination, destHash, startTime);
            }

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            if (_log.shouldWarn()) {
                _log.warn("HostChecker LeaseSet lookup error for " + hostname + " -> " + e.getMessage());
            }

            // LeaseSet lookup error - HOST IS PERMANENTLY OFFLINE
            PingResult result = createPingResult(false, startTime, responseTime, hostname);
            synchronized (_pingResults) {
                _pingResults.put(hostname, result);
            }

            savePingResults();
            return result;
        }
    }

    /**
     * Perform remote LeaseSet lookup via floodfill network
     */
    private PingResult lookupLeaseSetRemotely(String hostname, Destination destination,
                                           Hash destHash, long startTime) {
        try {
            if (!(_context instanceof RouterContext)) {
                return pingDestination(hostname, destination);
            }

            RouterContext routerContext = (RouterContext) _context;
            NetworkDatabaseFacade netDb = routerContext.netDb();

            // Use a simple callback approach to wait for the lookup result
            final boolean[] lookupResult = {false};
            final boolean[] lookupComplete = {false};
            final Exception[] lookupError = {null};

            // Create success and failure callbacks
            Job onSuccess = new Job() {
                public String getName() { return "LeaseSet lookup success"; }
                public long getJobId() { return System.currentTimeMillis(); }
                public JobTiming getTiming() { return new JobTiming(routerContext); }
                public void runJob() {
                    lookupResult[0] = true;
                    lookupComplete[0] = true;
                    synchronized (lookupComplete) {
                        lookupComplete.notify();
                    }
                }
                public void dropped() {
                    // Handle job being dropped
                    lookupResult[0] = false;
                    lookupComplete[0] = true;
                    synchronized (lookupComplete) {
                        lookupComplete.notify();
                    }
                }
            };

            Job onFailure = new Job() {
                public String getName() { return "LeaseSet lookup failure"; }
                public long getJobId() { return System.currentTimeMillis(); }
                public JobTiming getTiming() { return new JobTiming(routerContext); }
                public void runJob() {
                    lookupResult[0] = false;
                    lookupComplete[0] = true;
                    synchronized (lookupComplete) {
                        lookupComplete.notify();
                    }
                }
                public void dropped() {
                    // Handle job being dropped
                    lookupResult[0] = false;
                    lookupComplete[0] = true;
                    synchronized (lookupComplete) {
                        lookupComplete.notify();
                    }
                }
            };

            // Start the lookup with a timeout
            netDb.lookupLeaseSet(destHash, onSuccess, onFailure, 15000); // 15 second timeout

            // Wait for lookup to complete with timeout
            synchronized (lookupComplete) {
                if (!lookupComplete[0]) {
                    lookupComplete.wait(15000);
                }
            }

            long responseTime = System.currentTimeMillis() - startTime;

            if (lookupResult[0] && lookupComplete[0]) {
                // Successfully found LeaseSet via floodfill
                PingResult result = createPingResult(true, startTime, responseTime, hostname);
                synchronized (_pingResults) {
                    _pingResults.put(hostname, result);
                }

                if (_log.shouldInfo()) {
                    _log.info("HostChecker lset [SUCCESS] -> Found LeaseSet for " +
                             hostname + " in " + responseTime + "ms");
                }

                savePingResults();
                return result;
            } else {
                // LeaseSet not found via floodfill - HOST IS PERMANENTLY OFFLINE
            PingResult result = createPingResult(false, startTime, responseTime, hostname);
                synchronized (_pingResults) {
                    _pingResults.put(hostname, result);
                }

                if (_log.shouldInfo()) {
                    _log.info("HostChecker lset [FAILURE] -> No LeaseSet found for " +
                              hostname + " in " + responseTime + "ms");
                }

                savePingResults();
                return result;
            }

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            if (_log.shouldWarn()) {
                _log.warn("HostChecker LeaseSet lookup error for " + hostname + " -> " + e.getMessage());
            }

            // LeaseSet lookup error - host is down
            PingResult result = createPingResult(false, startTime, responseTime, hostname);
            synchronized (_pingResults) {
                _pingResults.put(hostname, result);
            }

            savePingResults();
            return result;
        }
    }

    /**
     * Ping destination using single tunnel and I2Ping-style retry handling
     * Falls back to EepHead HTTP HEAD request if ping fails
     */
    private PingResult pingDestination(String hostname, Destination destination) {
        long startTime = System.currentTimeMillis();
        I2PSocketManager pingSocketManager = null;
        String displayHostname = hostname;
        if (hostname.length() > 30) {displayHostname = hostname.substring(0,29) + "&hellip;";}

        try {
            long tunnelBuildStart = System.currentTimeMillis();
            Properties options = new Properties();
            // Use a unique port to avoid conflicts with I2Ping and other clients
            options.setProperty("i2cp.host", "127.0.0.1");
            options.setProperty("i2cp.port", "7611");  // Different from default 7654
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
                    _log.warn("Failed to create SocketManager for HostChecker ping -> " + displayHostname + " [6,4]");
                }
                return createPingResult(false, startTime, System.currentTimeMillis() - startTime, hostname);
            }

            long tunnelBuildTime = System.currentTimeMillis() - tunnelBuildStart;
            if (_log.shouldDebug()) {
                _log.debug("SocketManager ready for HostChecker ping in " + tunnelBuildTime + "ms -> " + displayHostname + " [6,4]");
            }

            // Use I2PSocketManager ping method like I2Ping does
            // Parameters: destination, localPort, remotePort, timeout
            long pingStart = System.currentTimeMillis();
            boolean reachable = pingSocketManager.ping(destination, 0, 0, _pingTimeout);
            long pingTime = System.currentTimeMillis() - pingStart;

            if (reachable) {
                PingResult result = createPingResult(true, startTime, pingTime, hostname);
                synchronized (_pingResults) {
                    _pingResults.put(hostname, result);
                }
                if (_log.shouldInfo()) {
                    _log.info("HostChecker ping [SUCCESS] -> Received response from " + displayHostname + " [6,4] in " + pingTime + "ms");
                }
                savePingResults();
                return result;
            } else {
                if (_log.shouldInfo()) {
                    _log.info("HostChecker ping [FAILURE] -> No response from " + displayHostname + " [6,4], trying eephead...");
                }
                return fallbackToEepHead(hostname, startTime);
            }

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            if (_log.shouldWarn()) {
                _log.warn("HostChecker ping error for " + displayHostname + " -> " + e.getMessage());
            }
            return createPingResult(false, startTime, responseTime, hostname);
        } finally {
            // Clean up the single socket manager
            if (pingSocketManager != null) {
                try {
                    pingSocketManager.destroySocketManager();
                    if (_log.shouldDebug()) {
                        _log.debug("Destroyed SocketManager for HostChecker ping for " + displayHostname);
                    }
                } catch (Exception e) {
                    if (_log.shouldWarn()) {
                        _log.warn("Error destroying SocketManager HostChecker for " + displayHostname + ": " + e.getMessage());
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
            PingResult result = createPingResult(success, startTime, responseTime, hostname);
            synchronized (_pingResults) {
                _pingResults.put(hostname, result);
            }

            if (_log.shouldInfo()) {
                if (success) {
                    _log.info("HostChecker head [SUCCESS] -> Received response from " + hostname + " in " + responseTime + "ms");
                } else {
                    _log.info("HostChecker head [FAILURE] -> No response from " + hostname + " in " + responseTime + "ms");
                }
            }

            savePingResults();
            return result;

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            if (_log.shouldInfo()) {
                _log.info("HostChecker eephead probe error for " + hostname + " -> " + e.getMessage());
            }

            PingResult result = createPingResult(false, startTime, responseTime, hostname);
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
     * Set whether to use LeaseSet checking
     *
     * @param useLeaseSetCheck true to use LeaseSet lookup first
     */
    public void setUseLeaseSetCheck(boolean useLeaseSetCheck) {
        _useLeaseSetCheck = useLeaseSetCheck;
    }

    /**
     * Get whether to use LeaseSet checking
     *
     * @return true if LeaseSet checking is enabled
     */
    public boolean getUseLeaseSetCheck() {
        return _useLeaseSetCheck;
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
                            String category = parts.length > 3 ? parts[3] : null;
                            PingResult result = new PingResult(reachable, timestamp, -1, category);
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
                _log.info("Loaded " + _pingResults.size() + " HostChecker results from " + _hostsCheckFile.getName());
            }

            // Clean up stale hosts after loading to remove entries no longer in the address book
            cleanupStaleHosts();

            // Save the cleaned-up results back to file immediately to remove stale entries from hosts_check.txt
            savePingResults();
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Error loading HostChecker results from " + _hostsCheckFile.getAbsolutePath(), e);
            }
        }
    }

    /**
     * Save ping results to hosts_check.txt file
     */
    private void savePingResults() {
        try {
            // Clean up stale hosts before saving to ensure we don't persist entries for deleted hosts
            cleanupStaleHosts();

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
            content.append("# Format: timestamp,host,reachable,category\n");
            content.append("# Generated: ").append(new java.util.Date()).append("\n\n");

            for (Map.Entry<String, PingResult> entry : _pingResults.entrySet()) {
                String hostname = entry.getKey();
                PingResult result = entry.getValue();
                content.append(result.timestamp).append(",").append(hostname)
                       .append(",").append(result.reachable ? "y" : "n");
                if (result.category != null) {
                    content.append(",").append(result.category);
                }
                content.append("\n");
            }

            // Write using Files.write with explicit options
            java.nio.file.Path path = java.nio.file.Paths.get(absolutePath);
            java.nio.file.Files.write(path, content.toString().getBytes(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);

        } catch (java.io.IOException e) {
            if (_log.shouldError()) {
                _log.error("IOException saving HostChecker results: " + e.getMessage() +
                           "\n* File path: " + _hostsCheckFile.getAbsolutePath());
            }
        } catch (Exception e) {
            _log.error("Unexpected error saving HostChecker results: " + e.getMessage(), e);
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
     * Remove hosts from _pingResults that are no longer in the address book
     * This prevents stale entries from accumulating in hosts_check.txt
     */
    private void cleanupStaleHosts() {
        try {
            Set<String> currentHostnames = getAllHostnames();
            Set<String> cachedHostnames = new HashSet<String>(_pingResults.keySet());

            // Safety check: only run cleanup if we have current hosts from the naming service
            // This prevents removing all hosts during startup when naming service might not be ready
            if (currentHostnames.isEmpty() && !cachedHostnames.isEmpty()) {
                if (_log.shouldDebug()) {
                    _log.debug("HostChecker cleanup skipped: no current hosts available...");
                }
                return;
            }

            // Remove hosts from cache that are no longer in the address book
            int removedCount = 0;
            for (String cachedHostname : cachedHostnames) {
                if (!currentHostnames.contains(cachedHostname)) {
                    _pingResults.remove(cachedHostname);
                    _destinations.remove(cachedHostname);
                    removedCount++;

                    if (_log.shouldDebug()) {
                        _log.debug("Removed stale host from HostChecker cache: " + cachedHostname);
                    }
                }
            }

            if (removedCount > 0 && _log.shouldInfo()) {
                _log.info("HostChecker cleanup -> Removed " + removedCount + " stale hosts from hosts_check.txt");
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Error during HostChecker stale host cleanup", e);
            }
        }
    }

    /**
     * Download categories file from notbob.i2p
     * Falls back to existing stale categories.txt if download fails
     */
    private void downloadCategories() {
        boolean downloadSuccess = false;
        boolean hadExistingFile = _categoriesFile.exists();

        try {
            String categoryUrl = "http://notbob.i2p/graphs/cats.txt";

            // Wait for HTTP proxy to be ready
            if (_log.shouldInfo()) {
                _log.info("Waiting " + (CATEGORY_DOWNLOAD_DELAY_MS/1000) + "s before downloading categories from " + categoryUrl + "...");
            }
            Thread.sleep(CATEGORY_DOWNLOAD_DELAY_MS);

            if (_log.shouldInfo()) {
                _log.info("Downloading categories from " + categoryUrl);
            }

            EepGet get = new EepGet(_context, "127.0.0.1", 4444, 3, _categoriesFile.getAbsolutePath(), categoryUrl);
            get.addHeader("User-Agent", "I2P+ HostChecker");
            downloadSuccess = get.fetch(CATEGORY_CONNECT_TIMEOUT, CATEGORY_TOTAL_TIMEOUT, CATEGORY_INACTIVITY_TIMEOUT);

            if (downloadSuccess) {
                // Add header after successful download
                addCategoriesHeader();
                if (_log.shouldInfo()) {
                    _log.info("Successfully downloaded categories to " + _categoriesFile.getAbsolutePath());
                }
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Failed to download categories from notbob.i2p -> Retrying...");
                }
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Exception downloading categories", e);
            }
        }

        // If download failed but we have an existing stale file, continue with that
        if (!downloadSuccess && hadExistingFile) {
            if (_log.shouldInfo()) {
                _log.info("Using stale categories.txt file at " + _categoriesFile.getAbsolutePath());
            }
        }

        // Mark category download as complete and track success
        _categoriesDownloaded.set(true);
        _categoriesDownloadSuccessful.set(downloadSuccess);
    }

    /**
     * Add header to categories file with timestamp
     */
    private void addCategoriesHeader() {
        try {
            // Read current content
            List<String> lines = new ArrayList<>();
            if (_categoriesFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(_categoriesFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }
            }

            // Write file with header
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(_categoriesFile))) {
                writer.write("# I2P+ Address Book Host Categories");
                writer.newLine();
                writer.write("# Format: hostname,category");
                writer.newLine();
                writer.write("# Source: http://notbob.i2p/graphs/cats.txt");
                writer.newLine();
                writer.write("# Generated: " + new SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US).format(new Date()));
                writer.newLine();
                writer.newLine();

                // Write original content, skipping any existing header lines and empty lines
                for (String line : lines) {
                    if (!line.startsWith("#") && !line.trim().isEmpty()) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Failed to add header to categories file", e);
            }
        }
    }

    /**
     * Write cleaned categories.txt file with only valid entries (no empty lines)
     */
    private void writeCleanCategoriesFile(List<String> validLines) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(_categoriesFile))) {
            for (String line : validLines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    /**
     * Load categories from categories.txt file
     */
    private void loadCategories() {
        if (_hostCategories == null) {
            _hostCategories = new ConcurrentHashMap<String, String>();
        }

        if (!_categoriesFile.exists()) {
            if (_log.shouldInfo()) {
                _log.info("Categories file not found at " + _categoriesFile.getAbsolutePath());
            }
            return;
        }

        List<String> validLines = new ArrayList<>();
        boolean hasEmptyLines = false;

        try (BufferedReader reader = new BufferedReader(new FileReader(_categoriesFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty()) {
                    hasEmptyLines = true;
                    continue;
                }
                if (trimmedLine.startsWith("#")) {
                    validLines.add(line);
                    continue;
                }

                // Format: hostname,category
                String[] parts = trimmedLine.split(",", 2);
                if (parts.length == 2) {
                    String hostname = parts[0].trim();
                    String category = parts[1].trim();
                    _hostCategories.put(hostname, category);
                    validLines.add(line);
                }
            }

            if (_log.shouldInfo()) {
                _log.info("Loaded " + _hostCategories.size() + " host categories from " + _categoriesFile.getName());
            }

            // Clean up the file if we found empty lines
            if (hasEmptyLines) {
                try {
                    writeCleanCategoriesFile(validLines);
                    if (_log.shouldInfo()) {
                        _log.info("Cleaned up empty lines from categories.txt");
                    }
                } catch (Exception e) {
                    if (_log.shouldWarn()) {
                        _log.warn("Failed to clean up categories.txt file", e);
                    }
                }
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Error loading categories from " + _categoriesFile.getAbsolutePath(), e);
            }
        }
    }

    /**
     * Get category for a hostname
     */
    public String getCategory(String hostname) {
        return _hostCategories.get(hostname);
    }

    /**
     * Helper method to create PingResult with category
     */
    private PingResult createPingResult(boolean reachable, long timestamp, long responseTime, String hostname) {
        String category = _hostCategories.get(hostname);
        return new PingResult(reachable, timestamp, responseTime, category);
    }

    /**
     * Get all host categories
     */
    public Map<String, String> getAllCategories() {
        return new HashMap<String, String>(_hostCategories);
    }

    /**
     * Schedule periodic category download retries
     */
    private void scheduleCategoryRetries() {
        _scheduler.scheduleWithFixedDelay(new CategoryRetryTask(),
            CATEGORY_RETRY_INTERVAL, CATEGORY_RETRY_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * Task to retry category downloads if they initially failed
     */
    private class CategoryRetryTask implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().setName("CategoryRetry");

            if (!_running.get()) {
                return;
            }

            // Only retry if categories download was not successful
            if (!_categoriesDownloadSuccessful.get()) {
                // Check if we've exceeded max retries (if set)
                if (MAX_CATEGORY_RETRIES > 0 && _categoryRetryCount >= MAX_CATEGORY_RETRIES) {
                    if (_log.shouldDebug()) {
                        _log.debug("Category retry limit reached (" + MAX_CATEGORY_RETRIES + "), stopping retries");
                    }
                    return;
                }

                _categoryRetryCount++;
                if (_log.shouldInfo()) {
                    _log.info("Retrying category download (attempt " + _categoryRetryCount + ")...");
                }

                boolean retrySuccess = attemptCategoryDownload();

                if (retrySuccess) {
                    _categoriesDownloadSuccessful.set(true);

                    // Reload categories after successful download
                    loadCategories();

                    // Update hosts_check.txt with new category information
                    updateHostsWithCategories();

                    if (_log.shouldInfo()) {
                        _log.info("Category download successful on retry " + _categoryRetryCount +
                                 ", updated hosts_check.txt with categories");
                    }
                } else {
                    if (_log.shouldInfo()) {
                        _log.info("Category download retry " + _categoryRetryCount + " failed");
                    }
                }
            } else {
                if (_log.shouldDebug()) {
                    _log.debug("Categories already successfully downloaded, no retry needed");
                }
            }
        }
    }

    /**
     * Attempt to download categories file
     * Returns true if successful, false otherwise
     */
    private boolean attemptCategoryDownload() {
        try {
            String categoryUrl = "http://notbob.i2p/graphs/cats.txt";

            EepGet get = new EepGet(_context, "127.0.0.1", 4444, 3, _categoriesFile.getAbsolutePath(), categoryUrl);
            get.addHeader("User-Agent", "I2P+ HostChecker");
            boolean downloadSuccess = get.fetch(CATEGORY_CONNECT_TIMEOUT, CATEGORY_TOTAL_TIMEOUT, CATEGORY_INACTIVITY_TIMEOUT);

            if (downloadSuccess) {
                addCategoriesHeader();
                if (_log.shouldInfo()) {
                    _log.info("Successfully downloaded categories on retry to " + _categoriesFile.getAbsolutePath());
                }
                return true;
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Failed to download categories on retry, result: " + downloadSuccess);
                }
                return false;
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Exception during category download retry", e);
            }
            return false;
        }
    }

    /**
     * Update hosts_check.txt with category information after successful category download
     */
    private void updateHostsWithCategories() {
        try {
            int updatedCount = 0;

            // Iterate through existing ping results and update them with category information
            for (Map.Entry<String, PingResult> entry : _pingResults.entrySet()) {
                String hostname = entry.getKey();
                PingResult existingResult = entry.getValue();
                String category = _hostCategories.get(hostname);

                // Create new PingResult with category information
                if (category != null && !category.equals(existingResult.category)) {
                    PingResult updatedResult = new PingResult(existingResult.reachable,
                                                           existingResult.timestamp,
                                                           existingResult.responseTime,
                                                           category);
                    _pingResults.put(hostname, updatedResult);
                    updatedCount++;

                    if (_log.shouldDebug()) {
                        _log.debug("Updated category for " + hostname + ": " + category);
                    }
                }
            }

            if (updatedCount > 0) {
                // Save the updated results to hosts_check.txt
                savePingResults();

                if (_log.shouldInfo()) {
                    _log.info("Updated " + updatedCount + " hosts with category information in hosts_check.txt");
                }
            } else {
                if (_log.shouldInfo()) {
                    _log.info("No host categories needed updating in hosts_check.txt");
                }
            }

        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Error updating hosts with categories", e);
            }
        }
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


                    // Check if router is shutting down or restarting
                    if (_context instanceof RouterContext) {
                        RouterContext routerContext = (RouterContext) _context;
                        Router router = routerContext.router();
                        boolean isShuttingDown = router.gracefulShutdownInProgress() || router.isFinalShutdownInProgress();
                        boolean isRestarting = router.isRestarting();

                        // Check for graceful shutdown in progress
                        if (isShuttingDown || isRestarting) {
                            if (_log.shouldInfo()) {
                                _log.info("HostChecker stopping -> Router is " + (isShuttingDown ? "shutting down" : "restarting") + "...");
                            }
                            break;
                        }
                    }

                    Destination dest = getDestination(hostname);
                    boolean isBlacklisted = isHostBlacklisted(hostname);

                    if (dest != null && !isBlacklisted) {
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
                                        _log.info("Starting HostChecker for " + hostname + "...");
                                    }

                                    testDestination(hostname);
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
                        if (_log.shouldInfo()) {
                            if (dest == null) {
                                _log.info("Skipping entry with empty destination: " + hostname);
                            } else {
                                _log.info("Skipping blacklisted host: " + hostname);
                            }
                        }
                    }
                }

                // Wait for all pings to complete
                for (java.util.concurrent.Future<Void> future : futures) {
                    try {
                        future.get();
                    } catch (Exception e) {
                        if (_log.shouldWarn()) {
                            _log.warn("Error waiting for HostChecker completion -> " + e.getMessage(), e);
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
                    _log.info("HostChecker cycle completed: " + success + " / " + total + " reachable (" + skipped + " blacklisted hosts skipped)");
                }

                // Save ping results to file after each cycle
                savePingResults();
            } catch (Exception e) {
                if (_log.shouldWarn()) {
                    _log.warn("Error during HostChecker cycle", e);
                }
            }
        }
    }

    private void loadBlacklist() {
        try {
            if (!_blacklistFile.exists()) {
                _blacklistedHosts.clear();
                _blacklistLastModified = 0;
                return;
            }

            long lastModified = _blacklistFile.lastModified();
            if (lastModified == _blacklistLastModified) {
                return;
            }

            Set<String> newBlacklist = new HashSet<String>();
            List<String> lines = java.nio.file.Files.readAllLines(_blacklistFile.toPath());
            for (String line : lines) {
                line = line.trim().toLowerCase();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    newBlacklist.add(line);
                }
            }

            synchronized (this) {
                _blacklistedHosts = newBlacklist;
                _blacklistLastModified = lastModified;
            }

            if (_log.shouldInfo()) {
                _log.info("Loaded " + _blacklistedHosts.size() + " blacklisted hosts from " + _blacklistFile.getName());
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Error loading blacklist from " + _blacklistFile.getAbsolutePath(), e);
            }
        }
    }

    private boolean isHostBlacklisted(String hostname) {
        try {
            loadBlacklist();
            synchronized (this) {
                return _blacklistedHosts.contains(hostname.toLowerCase());
            }
        } catch (Exception e) {
            if (_log.shouldDebug()) {
                _log.debug("Error checking blacklist for " + hostname + ": " + e.getMessage());
            }
            return false;
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

        log.info("HostChecker results:");
        for (Map.Entry<String, PingResult> entry : tester.getAllPingResults().entrySet()) {
            PingResult result = entry.getValue();
            log.info(entry.getKey() + ": " + result + (result.reachable ? " (" + result.responseTime + "ms)" : ""));
        }
    }
}