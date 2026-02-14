package net.i2p.addressbook;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.crypto.EncType;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.Job;
import net.i2p.router.JobQueue;
import net.i2p.router.JobTiming;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.ProfileOrganizer;
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
    private ScheduledExecutorService _scheduler;
    private final Map<String, PingResult> _pingResults;
    private final Map<String, Destination> _destinations;
    private Map<String, String> _hostCategories;
    private final AtomicBoolean _running;
    private final AtomicBoolean _categoriesDownloaded;
    private final AtomicBoolean _categoriesDownloadSuccessful;
    private final AtomicBoolean _cycleInProgress;
    private final File _hostsCheckFile;
    private final File _categoriesFile;
    private final File _blacklistFile;
    private Semaphore _pingSemaphore;
    private Set<String> _blacklistedHosts;
    private volatile long _blacklistLastModified;
    private int _categoryRetryCount = 0;

    // Configuration defaults
    private static final long DEFAULT_PING_INTERVAL = 4 * 60 * 60 * 1000L; // 4 hours
    private static final long DEFAULT_PING_TIMEOUT = 60 * 1000L; // 60 seconds
    private static final int DEFAULT_MAX_CONCURRENT = 16;
    private static final long MAX_JOB_LAG_MS = 2 * 1000L; // Skip cycle if job queue lag exceeds 2 seconds

    // Configuration property names
    private static final String PROP_PING_INTERVAL = "pingInterval";
    private static final String PROP_MAX_CONCURRENT = "maxConcurrentPings";

    /**
     * Load configuration from addressbook/config.txt file
     * Reads pingInterval and maxConcurrent properties if present
     *
     * @param configDir addressbook configuration directory
     */
    private void loadConfiguration(File configDir) {
        File configFile = new File(configDir, "config.txt");
        if (!configFile.exists()) {
            if (_log.shouldInfo()) {
                _log.info("No addressbook config.txt found -> Using default interval and concurrency values...");
            }
            return;
        }

        Properties config = new Properties();
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();

                    if (PROP_PING_INTERVAL.equals(key)) {
                        try {
                            String rawValue = value;
                            boolean isMinutes = rawValue.toUpperCase().endsWith("M");
                            if (isMinutes) {
                                rawValue = rawValue.substring(0, rawValue.length() - 1).trim();
                            }
                            long intervalValue = Long.parseLong(rawValue);
                            if (isMinutes) {
                                _pingInterval = intervalValue * 60 * 1000L;
                                if (_log.shouldInfo()) {
                                    _log.info("Loaded ping interval from config: " + value + " (" + intervalValue + " minutes)");
                                }
                            } else {
                                _pingInterval = intervalValue * 60 * 60 * 1000L;
                                if (_log.shouldInfo()) {
                                    _log.info("Loaded ping interval from config: " + value + (intervalValue > 1 ? " hours" : " hour"));
                                }
                            }
                        } catch (NumberFormatException e) {
                            if (_log.shouldWarn()) {
                                _log.warn("Invalid ping interval in config: " + value + ", using default " +
                                           (DEFAULT_PING_INTERVAL / (60 * 60 * 1000L)) + " hours");
                            }
                        }
                    } else if (PROP_MAX_CONCURRENT.equals(key)) {
                        try {
                            int concurrentValue = Integer.parseInt(value);
                            if (concurrentValue > 160) {
                                _log.warn("Configured concurrency " + concurrentValue + " is higher than permitted maximum -> Setting to 160");
                                concurrentValue = 160;
                            }
                            _maxConcurrent = concurrentValue;
                            if (_log.shouldInfo()) {
                                _log.info("Loaded max concurrent from config: " + _maxConcurrent);
                            }
                        } catch (NumberFormatException e) {
                            if (_log.shouldWarn()) {
                                _log.warn("Invalid max concurrent in config: " + value + ", using default " + DEFAULT_MAX_CONCURRENT);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (_log.shouldWarn()) {
                _log.warn("Error loading addressbook config.txt", e);
            }
        }
    }

    // Startup delay to allow router and socket manager to fully initialize
    private static final long STARTUP_DELAY_MS = 10 * 60 * 1000L; // 10 minutes

    // Additional delay for category download to allow HTTP proxy to be ready
    private static final long CATEGORY_DOWNLOAD_DELAY_MS = 90 * 1000L; // 90 seconds

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

    // Defensive mode constants - activated when tunnel build success < 40%
    private static final double TUNNEL_BUILD_SUCCESS_THRESHOLD = 0.40;
    private static final int DEFENSIVE_MAX_CONCURRENT = 1;
    private static final long DEFENSIVE_PING_INTERVAL = 24 * 60 * 60 * 1000L; // 24 hours
    private volatile boolean _defensiveMode = false;
    private volatile long _lastDefensiveModeChange = 0;

    /**
     * Ping result data structure containing the outcome of a host reachability test.
     * Immutable - all fields are final.
     */
    public static class PingResult {
        /** true if the host was reachable via LeaseSet lookup, ping, or HTTP HEAD */
        public final boolean reachable;
        /** timestamp of when this result was recorded (Java time) */
        public final long timestamp;
        /** response time in milliseconds, or -1 if not available */
        public final long responseTime;
        /** the host category from categories.txt, or null */
        public final String category;
        /** LeaseSet encryption types as a string like "[6,4]", or null */
        public final String leaseSetTypes;

        /**
         * Create a new PingResult with all fields.
         * @param reachable true if host was reachable
         * @param timestamp time of the test result
         * @param responseTime response time in ms, or -1
         * @param category the host category
         * @param leaseSetTypes the LeaseSet encryption types
         */
        public PingResult(boolean reachable, long timestamp, long responseTime, String category, String leaseSetTypes) {
            this.reachable = reachable;
            this.timestamp = timestamp;
            this.responseTime = responseTime;
            this.category = category;
            this.leaseSetTypes = leaseSetTypes;
        }

        /**
         * Create a new PingResult without LeaseSet types.
         */
        public PingResult(boolean reachable, long timestamp, long responseTime, String category) {
            this(reachable, timestamp, responseTime, category, null);
        }

        /**
         * Create a new PingResult with minimal fields.
         */
        public PingResult(boolean reachable, long timestamp, long responseTime) {
            this(reachable, timestamp, responseTime, null, null);
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
        _pingResults = new ConcurrentHashMap<String, PingResult>();
        _destinations = new ConcurrentHashMap<String, Destination>();
        _running = new AtomicBoolean(false);
        _categoriesDownloaded = new AtomicBoolean(false);
        _categoriesDownloadSuccessful = new AtomicBoolean(false);
        _cycleInProgress = new AtomicBoolean(false);

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
        _blacklistedHosts = new HashSet<String>();
        loadBlacklist();

        // Load configuration from addressbook/config.txt
        loadConfiguration(addressbookDir);

        // Initialize scheduler and semaphore AFTER loading config to use configured values
        ThreadFactory threadFactory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName("HostChecker");
                thread.setDaemon(true);
                return thread;
            }
        };
        _scheduler = Executors.newScheduledThreadPool(_maxConcurrent + 2, threadFactory);
        _pingSemaphore = new Semaphore(_maxConcurrent);

        // Load existing categories file first (non-blocking)
        loadCategories();
        loadPingResults();

        // Schedule periodic category retries
        scheduleCategoryRetries();
    }

    /**
     * Check if router is under attack based on tunnel build success rate.
     * Uses 10-minute average to avoid fluctuations.
     * @return true if tunnel build success < 40%
     */
    private boolean isUnderAttack() {
        if (!(_context instanceof RouterContext)) {
            return false;
        }
        try {
            RouterContext routerContext = (RouterContext) _context;
            ProfileOrganizer profileOrganizer = routerContext.profileOrganizer();
            if (profileOrganizer == null) {
                return false;
            }
            double buildSuccess = profileOrganizer.getTunnelBuildSuccess();
            boolean underAttack = buildSuccess < TUNNEL_BUILD_SUCCESS_THRESHOLD;
            if (_log.shouldDebug()) {
                _log.debug("Tunnel build success: " + (int)(buildSuccess * 100) + "% -> Under attack: " + underAttack);
            }
            return underAttack;
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Error checking tunnel build success", e);
            }
            return false;
        }
    }

    /**
     * Update defensive mode based on tunnel build success.
     * Uses 10-minute average with 24-hour cycle for restoration check.
     */
    private void updateDefensiveMode() {
        long now = System.currentTimeMillis();
        boolean wasDefensive = _defensiveMode;
        boolean nowDefensive = isUnderAttack();

        if (wasDefensive != nowDefensive) {
            _defensiveMode = nowDefensive;
            _lastDefensiveModeChange = now;

            if (nowDefensive) {
                if (_log.shouldWarn()) {
                    _log.warn("HostChecker entering DEFENSIVE MODE -> Concurrency 1, 24h cycle, LeaseSet-only checks");
                }
            } else {
                if (_log.shouldInfo()) {
                    _log.info("HostChecker exiting DEFENSIVE MODE -> Tunnel build success >= 40%, resuming normal operation");
                }
            }
        }
    }

    /**
     * Get defensive mode status
     * @return true if defensive mode is active
     */
    public boolean isDefensiveMode() {
        return _defensiveMode;
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

        // Create new scheduler if needed (in case start is called after stop)
        if (_scheduler.isShutdown()) {
            ThreadFactory threadFactory = new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setName("HostChecker");
                    thread.setDaemon(true);
                    return thread;
                }
            };
            _scheduler = Executors.newScheduledThreadPool(_maxConcurrent + 2, threadFactory);
            _pingSemaphore = new Semaphore(_maxConcurrent);
            scheduleCategoryRetries();
        }

        // Start category download in a separate thread with delay
        Thread categoryDownloader = new Thread("CategoryDownloader") {
            @Override
            public void run() {
                downloadCategories();
            }
        };
        categoryDownloader.setDaemon(true);
        categoryDownloader.start();

        // Wait for categories to be downloaded before starting ping cycle
        Thread categoryWaiter = new Thread("CategoryDownloadWaiter") {
            @Override
            public void run() {
                try {
                    // Wait up to 5 minutes for category download to complete
                    for (int i = 0; i < 300; i++) {
                        if (_categoriesDownloaded.get()) {
                            _log.info("Categories downloaded successfully from notbob.i2p -> Starting ping cycle...");
                            _scheduler.scheduleAtFixedRate(new PingTask(), STARTUP_DELAY_MS, _pingInterval, TimeUnit.MILLISECONDS);
                            return;
                        }
                        Thread.sleep(1000); // Wait 1 second
                    }

                    // Timeout reached - start ping cycle anyway
                    _log.warn("Categories failed to download from notbob.i2p (timeout) -> Starting ping cycle...");
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
     * Perform LeaseSet lookup as the first check
     * If lookup fails, responseTime is set to -1 and we skip ping/eephead
     *
     * @param hostname hostname to test
     * @param destination destination for the hostname
     * @return PingResult with LeaseSet lookup result (responseTime (preserves existing responseTime) for LeaseSet lookup)
     */
    private PingResult performLeaseSetLookup(String hostname, Destination destination) {
        long startTime = System.currentTimeMillis();
        boolean reachable;
        String leaseSetTypes = "[]";

        if (!(_context instanceof RouterContext)) {
            if (_log.shouldDebug()) {
                _log.debug("Not running in RouterContext for LeaseSet lookup: " + hostname);
            }
            return createPingResult(false, startTime, -1, hostname, leaseSetTypes);
        }

        RouterContext routerContext = (RouterContext) _context;
        NetworkDatabaseFacade netDb = routerContext.netDb();
        Hash destHash = destination.calculateHash();

        PingResult existing = _pingResults.get(hostname);
        long existingResponseTime = (existing != null && existing.responseTime > -1) ? existing.responseTime : -1;

        // Add random delay (2-5s) to avoid overwhelming floodfills with concurrent lookups
        try {
            int randomDelay = 2000 + _context.random().nextInt(3000);
            Thread.sleep(randomDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            LeaseSet leaseSet = netDb.lookupLeaseSetLocally(destHash);

            if (leaseSet != null) {
                leaseSetTypes = formatLeaseSetTypes(leaseSet);

                boolean isCurrent = leaseSet.isCurrent(_context.clock().now());
                reachable = isCurrent;

                // Only save if we have a previous result to preserve
                if (existingResponseTime > -1) {
                    synchronized (_pingResults) {
                        _pingResults.put(hostname, createPingResult(reachable, startTime, existingResponseTime, hostname, leaseSetTypes));
                    }
                }

                if (_log.shouldInfo()) {
                    if (!reachable) {
                        _log.info("HostChecker lset [FAILURE] -> Found expired LeaseSet " + leaseSetTypes + " for " + hostname);
                    }
                }

                if (reachable && existingResponseTime > -1) {
                    savePingResults();
                }
                return createPingResult(reachable, startTime, existingResponseTime, hostname, leaseSetTypes);
            } else {
                return lookupLeaseSetRemotely(hostname, destination, destHash, startTime, existingResponseTime);
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("HostChecker LeaseSet lookup error for " + hostname + " -> " + e.getMessage());
            }

            PingResult result = createPingResult(false, startTime, -1, hostname, leaseSetTypes);
            synchronized (_pingResults) {
                _pingResults.put(hostname, result);
            }

            if (_log.shouldInfo()) {
                _log.info("HostChecker lset [FAILURE] -> LeaseSet lookup error for " + hostname);
            }

            savePingResults();
            return result;
        }
    }

    /**
     * Test a single destination immediately
     * If LeaseSet lookup succeeds, we don't overwrite the result with failed ping/eephead
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

        if (_useLeaseSetCheck) {
            PingResult leaseSetResult = performLeaseSetLookup(hostname, destination);

            if (!leaseSetResult.reachable) {
                return leaseSetResult;
            }

            if (_log.shouldInfo()) {
                _log.info("HostChecker lset [SUCCESS] -> LeaseSet " + leaseSetResult.leaseSetTypes + " found for " + hostname + ", continuing with ping...");
            }

            PingResult pingResult = pingDestination(hostname, destination, leaseSetResult.leaseSetTypes, false);
            if (pingResult.reachable) {
                return pingResult;
            }

            PingResult eepHeadResult = fallbackToEepHead(hostname, pingResult.timestamp, leaseSetResult.leaseSetTypes, false);
            if (eepHeadResult.reachable) {
                return eepHeadResult;
            }
            return leaseSetResult; // don't mark a host as down if it has a valid leaseset but fails ping/eephead tests
        } else {
            PingResult pingResult = pingDestination(hostname, destination);
            if (pingResult.reachable) {
                return pingResult;
            }

            return fallbackToEepHead(hostname, pingResult.timestamp);
        }
    }

    /**
     * Perform remote LeaseSet lookup via floodfill network
     * Always returns responseTime of -1 (we measure host response, not lookup time)
     */
    private PingResult lookupLeaseSetRemotely(String hostname, Destination destination,
                                           Hash destHash, long startTime, long existingResponseTime) {
        try {
            RouterContext routerContext = (RouterContext) _context;
            NetworkDatabaseFacade netDb = routerContext.netDb();

            final boolean[] lookupResult = {false};
            final boolean[] lookupComplete = {false};
            final Exception[] lookupError = {null};

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
                    lookupResult[0] = false;
                    lookupComplete[0] = true;
                    synchronized (lookupComplete) {
                        lookupComplete.notify();
                    }
                }
            };

            // Double timeout when under attack (defensive mode) to give floodfills more time
            long lookupTimeout = _defensiveMode ? 30000 : 15000;
            netDb.lookupLeaseSet(destHash, onSuccess, onFailure, lookupTimeout);

            synchronized (lookupComplete) {
                if (!lookupComplete[0]) {
                    lookupComplete.wait(lookupTimeout);
                }
            }

            String leaseSetTypes = "[]";

            if (lookupResult[0] && lookupComplete[0]) {
                LeaseSet leaseSet = netDb.lookupLeaseSetLocally(destHash);
                if (leaseSet != null) {
                    leaseSetTypes = formatLeaseSetTypes(leaseSet);
                }

                if (existingResponseTime > -1) {
                    PingResult result = createPingResult(true, startTime, existingResponseTime, hostname, leaseSetTypes);
                    synchronized (_pingResults) {
                        _pingResults.put(hostname, result);
                    }
                    savePingResults();
                    return result;
                }

                PingResult result = createPingResult(true, startTime, -1, hostname, leaseSetTypes);
                synchronized (_pingResults) {
                    _pingResults.put(hostname, result);
                }
                return result;
            } else {
                PingResult result = createPingResult(false, startTime, -1, hostname, leaseSetTypes);
                synchronized (_pingResults) {
                    _pingResults.put(hostname, result);
                }

                if (_log.shouldInfo()) {
                    _log.info("HostChecker lset [FAILURE] -> No LeaseSet found for " + hostname);
                }

                savePingResults();
                return result;
            }

        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("HostChecker LeaseSet lookup error for " + hostname + " -> " + e.getMessage());
            }

            PingResult result = createPingResult(false, startTime, -1, hostname, "[]");
            synchronized (_pingResults) {
                _pingResults.put(hostname, result);
            }

            savePingResults();
            return result;
        }
    }

    /**
     * Ping destination using single tunnel and I2Ping-style retry handling
     * Falls back to EepHead HTTP HEAD request if ping fails, accepts leaseSetTypes parameter
     */
    private PingResult pingDestination(String hostname, Destination destination) {
        return pingDestination(hostname, destination, null);
    }

    /**
     * Ping destination using single tunnel and I2Ping-style retry handling
     * Falls back to EepHead HTTP HEAD request if ping fails, accepts leaseSetTypes parameter
     */
    private PingResult pingDestination(String hostname, Destination destination, String leaseSetTypes) {
        return pingDestination(hostname, destination, leaseSetTypes, true);
    }

    /**
     * Ping destination using single tunnel and I2Ping-style retry handling
     * Falls back to EepHead HTTP HEAD request if ping fails, accepts leaseSetTypes parameter
     * @param saveResult whether to save the result to _pingResults (false when LeaseSet lookup already succeeded)
     */
    private PingResult pingDestination(String hostname, Destination destination, String leaseSetTypes, boolean saveResult) {
        long startTime = System.currentTimeMillis();
        I2PSocketManager pingSocketManager = null;
        String displayHostname = hostname;
        if (hostname.length() > 30) {displayHostname = hostname.substring(0,29) + "&hellip;";}

        if (leaseSetTypes == null) {
            leaseSetTypes = "[]";
        }

        // Check router load before attempting to build tunnels
        if (_context instanceof RouterContext) {
            RouterContext routerContext = (RouterContext) _context;
            long maxLag = routerContext.jobQueue().getMaxLag();
            if (maxLag > MAX_JOB_LAG_MS) {
                // Router under load - skip this ping check to avoid tunnel build failures
                if (_log.shouldInfo()) {
                    _log.info("HostChecker ping SKIPPED for " + displayHostname + " - router under load (job lag: " + maxLag + "ms)");
                }
                return createPingResult(false, startTime, System.currentTimeMillis() - startTime, hostname, leaseSetTypes);
            }
        }

        try {
            long tunnelBuildStart = System.currentTimeMillis();
            Properties options = new Properties();
            options.setProperty("i2cp.host", "127.0.0.1");
            options.setProperty("i2cp.port", "7611");
            options.setProperty("inbound.nickname", "Ping [" + hostname.replace(".i2p", "") + "]");
            options.setProperty("outbound.nickname", "Ping [" + hostname.replace(".i2p", "") + "]");
            options.setProperty("inbound.shouldTest", "false");
            options.setProperty("outbound.shouldTest", "false");
            options.setProperty("inbound.quantity", "1");
            options.setProperty("outbound.quantity", "1");
            options.setProperty("inbound.backupQuantity", "0");
            options.setProperty("outbound.backupQuantity", "0");
            options.setProperty("i2cp.leaseSetType", "3");
            options.setProperty("i2cp.leaseSetEncType", "6,4");
            options.setProperty("i2cp.dontPublishLeaseSet", "true");
            // Short tunnel build timeout for HostChecker - fail fast and move on to eephead
            options.setProperty("i2cp.tunnelBuildTimeout", "60");

            pingSocketManager = I2PSocketManagerFactory.createManager(options);
            boolean tunnelBuildFailed = (pingSocketManager == null);

            if (tunnelBuildFailed) {
                if (_log.shouldWarn()) {
                    _log.warn("Failed to create SocketManager for HostChecker ping -> " + displayHostname + " [6,4], will try eephead");
                }
                // Don't return - try eephead even when tunnel build fails
            } else {
                long tunnelBuildTime = System.currentTimeMillis() - tunnelBuildStart;
                if (_log.shouldDebug()) {
                    _log.debug("SocketManager ready for HostChecker ping in " + tunnelBuildTime + "ms -> " + displayHostname + " [6,4]");
                }

                long tunnelReadyTimeout = System.currentTimeMillis() + 30000;
                while (System.currentTimeMillis() < tunnelReadyTimeout) {
                    if (pingSocketManager != null && !pingSocketManager.isDestroyed()) {
                        try {
                            net.i2p.client.I2PSession session = pingSocketManager.getSession();
                            if (session != null && !session.isClosed()) {
                                if (_useLeaseSetCheck && _context instanceof RouterContext) {
                                    RouterContext routerContext = (RouterContext) _context;
                                    net.i2p.data.Hash destHash = destination.calculateHash();
                                    net.i2p.data.LeaseSet ls = routerContext.clientNetDb(destHash).lookupLeaseSetLocally(destHash);
                                    if (ls != null && routerContext.tunnelManager().getOutboundClientTunnelCount(destHash) > 0) {
                                        long timeToExpire = ls.getEarliestLeaseDate() - _context.clock().now();
                                        if ((timeToExpire >= 0) && ls.isCurrent(0)) {
                                            break;
                                        }
                                    }
                                } else {
                                    break;
                                }
                            }
                        } catch (Exception e) {
                        }
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                long pingStart = System.currentTimeMillis();
                boolean reachable = pingSocketManager.ping(destination, 0, 0, _pingTimeout);
                long pingTime = System.currentTimeMillis() - pingStart;

                if (reachable) {
                    PingResult result = createPingResult(true, startTime, pingTime, hostname, leaseSetTypes);
                    if (saveResult) {
                        synchronized (_pingResults) {
                            _pingResults.put(hostname, result);
                        }
                        if (_log.shouldInfo()) {
                            _log.info("HostChecker ping [SUCCESS] -> Received response from " + displayHostname + " " + leaseSetTypes + " in " + pingTime + "ms");
                        }
                        savePingResults();
                    } else {
                        synchronized (_pingResults) {
                            _pingResults.put(hostname, result);
                        }
                        savePingResults();
                    }
                    return result;
                } else {
                    if (_log.shouldInfo()) {
                        _log.info("HostChecker ping [FAILURE] -> No response from " + displayHostname + " " + leaseSetTypes + ", trying eephead...");
                    }
                    // Continue to eephead fallback below
                }
            }

            // Try eephead fallback (reaches here if tunnel build failed or ping failed)
            PingResult eepheadResult = fallbackToEepHead(hostname, startTime, leaseSetTypes, saveResult, tunnelBuildFailed);

            // If eephead succeeded, destination is up
            if (eepheadResult.reachable) {
                return eepheadResult;
            }

            // If tunnel build failed AND eephead failed, don't mark destination as down
            // This is a local tunnel issue, not the destination's fault
            if (tunnelBuildFailed) {
                if (_log.shouldInfo()) {
                    _log.info("HostChecker check INCONCLUSIVE for " + displayHostname +
                              " - both tunnel build and eephead failed (likely local issue, not marking as down)");
                }
                // Return the result but DON'T save it to pingResults
                // This preserves the previous status until we can test again
                return eepheadResult;
            }

            // Tunnel built successfully but both ping and eephead failed - destination is down
            return eepheadResult;

        } catch (Exception e) {
            PingResult result = createPingResult(false, startTime, -1, hostname, leaseSetTypes);
            if (saveResult) {
                synchronized (_pingResults) {
                    _pingResults.put(hostname, result);
                }
                savePingResults();
                if (_log.shouldWarn()) {
                    _log.warn("HostChecker ping error for " + displayHostname + " -> " + e.getMessage());
                }
            }
            return result;
        } finally {
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
     * Defensive mode: Check only LeaseSet availability without ping/eephead tests.
     * Marks host as UP if LeaseSet found, DOWN otherwise.
     * Does NOT update ping times - preserves existing timestamps.
     */
    private void checkLeaseSetOnly(String hostname) {
        if (!(_context instanceof RouterContext)) {
            return;
        }

        try {
            Destination dest = getDestination(hostname);
            if (dest == null) {
                return;
            }

            RouterContext routerContext = (RouterContext) _context;
            Hash destHash = dest.calculateHash();

            // Look up LeaseSet locally
            net.i2p.data.LeaseSet ls = routerContext.netDb().lookupLeaseSetLocally(destHash);

            long now = System.currentTimeMillis();
            PingResult existingResult = _pingResults.get(hostname);
            long existingTimestamp = (existingResult != null) ? existingResult.timestamp : 0;
            long existingResponseTime = (existingResult != null) ? existingResult.responseTime : -1;
            String existingCategory = (existingResult != null) ? existingResult.category : null;
            String existingLeaseSetTypes = (existingResult != null) ? existingResult.leaseSetTypes : null;

            if (ls != null && ls.isCurrent(0)) {
                // LeaseSet found - mark as UP, preserve existing response time
                String leaseSetTypes = formatLeaseSetTypes(ls);
                PingResult result = new PingResult(true, existingTimestamp, existingResponseTime, existingCategory, leaseSetTypes);
                _pingResults.put(hostname, result);
                if (_log.shouldDebug()) {
                    _log.debug("HostChecker LeaseSet UP [DEFENSIVE]: " + hostname + " " + leaseSetTypes);
                }
            } else {
                // No LeaseSet - mark as DOWN
                String leaseSetTypes = (existingResult != null) ? existingResult.leaseSetTypes : "[]";
                PingResult result = new PingResult(false, existingTimestamp, -1, existingCategory, leaseSetTypes);
                _pingResults.put(hostname, result);
                if (_log.shouldDebug()) {
                    _log.debug("HostChecker LeaseSet DOWN [DEFENSIVE]: " + hostname);
                }
            }

            savePingResults();
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Error in checkLeaseSetOnly for " + hostname + ": " + e.getMessage());
            }
        }
    }

    /**
      * Fallback method to use EepHead for HTTP HEAD request testing
     * Marks site as up if any response is received
     */
    private PingResult fallbackToEepHead(String hostname, long startTime) {
        return fallbackToEepHead(hostname, startTime, null, true, false);
    }

    /**
     * Fallback method to use EepHead for HTTP HEAD request testing
     * Marks site as up if any response is received
     */
    private PingResult fallbackToEepHead(String hostname, long startTime, String leaseSetTypes) {
        return fallbackToEepHead(hostname, startTime, leaseSetTypes, true, false);
    }

    /**
     * Fallback method to use EepHead for HTTP HEAD request testing
     * Marks site as up if any response is received
     * @param saveResult whether to save the result to _pingResults
     */
    private PingResult fallbackToEepHead(String hostname, long startTime, String leaseSetTypes, boolean saveResult) {
        return fallbackToEepHead(hostname, startTime, leaseSetTypes, saveResult, false);
    }

    /**
     * Fallback method to use EepHead for HTTP HEAD request testing
     * Marks site as up if any response is received
     * @param saveResult whether to save the result to _pingResults (false when LeaseSet lookup already succeeded)
     * @param tunnelBuildFailed true if tunnel build failed, to avoid marking destination as down for local issues
     */
    private PingResult fallbackToEepHead(String hostname, long startTime, String leaseSetTypes, boolean saveResult, boolean tunnelBuildFailed) {
        try {
            String url = "http://" + hostname;
            EepHead eepHead = new EepHead(_context, "127.0.0.1", 4444, 1, url);

            if (leaseSetTypes == null) {
                leaseSetTypes = "[]";
            }

            int eepHeadTimeout = 30000;
            long tunnelReadyTimeout = System.currentTimeMillis() + 30000;
            boolean httpProxyReady = false;
            while (System.currentTimeMillis() < tunnelReadyTimeout && !httpProxyReady) {
                if (_useLeaseSetCheck && _context instanceof RouterContext) {
                    try {
                        RouterContext routerContext = (RouterContext) _context;
                        net.i2p.router.ClientManagerFacade cm = routerContext.clientManager();
                        for (net.i2p.data.Destination clientDest : cm.listClients()) {
                            net.i2p.data.Hash clientHash = clientDest.calculateHash();
                            net.i2p.data.LeaseSet ls = routerContext.clientNetDb(clientHash).lookupLeaseSetLocally(clientHash);
                            if (ls != null && routerContext.tunnelManager().getOutboundClientTunnelCount(clientHash) > 0) {
                                long timeToExpire = ls.getEarliestLeaseDate() - _context.clock().now();
                                if ((timeToExpire >= 0) && ls.isCurrent(0)) {
                                    httpProxyReady = true;
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                    }
                    if (!httpProxyReady) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } else {
                    try {
                        Thread.sleep(2000);
                        httpProxyReady = true;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            long eepHeadStart = System.currentTimeMillis();
            boolean success = eepHead.fetch(eepHeadTimeout, -1, eepHeadTimeout);
            long responseTime = success ? System.currentTimeMillis() - eepHeadStart : -1;

            PingResult result = createPingResult(success, startTime, responseTime, hostname, leaseSetTypes);

            // Determine if we should save this result
            // Don't save failure if tunnel build failed - it's a local issue, not the destination's fault
            boolean shouldSaveResult = saveResult || success;
            if (!success && tunnelBuildFailed) {
                // Both tunnel build and eephead failed - don't mark destination as down
                shouldSaveResult = false;
                if (_log.shouldInfo()) {
                    _log.info("HostChecker head [FAILURE] -> No response from " + hostname + " " + leaseSetTypes +
                              " (tunnel build also failed - not marking as down)");
                }
            }

            if (shouldSaveResult) {
                synchronized (_pingResults) {
                    _pingResults.put(hostname, result);
                }

                if (_log.shouldInfo()) {
                    if (success) {
                        _log.info("HostChecker head [SUCCESS] -> Received response from " + hostname + " " + leaseSetTypes + " in " + responseTime + "ms");
                    } else {
                        _log.info("HostChecker head [FAILURE] -> No response from " + hostname + " " + leaseSetTypes);
                    }
                }

                savePingResults();
            }
            return result;

        } catch (Exception e) {
            if (_log.shouldInfo()) {
                _log.info("HostChecker eephead probe error for " + hostname + " -> " + e.getMessage());
            }

            PingResult result = createPingResult(false, startTime, -1, hostname, leaseSetTypes != null ? leaseSetTypes : "[]");
            // Don't save exception result if tunnel build failed - it's likely a local issue
            if (saveResult && !tunnelBuildFailed) {
                synchronized (_pingResults) {
                    _pingResults.put(hostname, result);
                }

                savePingResults();
            }
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

                String[] parts = line.split(",", 6);
                boolean isValid = false;

                if (parts.length >= 3) {
                    // Validate timestamp before parsing
                    if (parts[0] != null && !parts[0].isEmpty()) {
                        try {
                            long timestamp = Long.parseLong(parts[0]);
                            String hostname = parts[1];
                            boolean reachable = "y".equals(parts[2]);
                            String category = parts.length > 3 ? parts[3] : null;
                            long responseTime = parts.length > 4 ? Long.parseLong(parts[4]) : -1;
                            String leaseSetTypes = parts.length > 5 ? parts[5] : null;
                            PingResult result = new PingResult(reachable, timestamp, responseTime, category, leaseSetTypes);
                            _pingResults.put(hostname, result);
                            isValid = true;
                        } catch (NumberFormatException e) {
                            // Invalid timestamp or responseTime - mark for removal
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
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (!created && _log.shouldWarn()) {
                    _log.warn("Failed to create parent directory: " + parentDir.getAbsolutePath());
                }
            }

            // Write file with explicit error handling
            StringBuilder content = new StringBuilder();
            content.append("# I2P+ Address Book Host Check\n");
            content.append("# Format: timestamp,host,reachable,category,responseTime,leaseSetTypes\n");
            content.append("# Generated: ").append(new java.util.Date()).append("\n\n");

            java.util.List<Map.Entry<String, PingResult>> sortedEntries = new java.util.ArrayList<>(_pingResults.entrySet());
            sortedEntries.sort(java.util.Comparator.comparingLong(e -> e.getValue().timestamp));

            for (Map.Entry<String, PingResult> entry : sortedEntries) {
                String hostname = entry.getKey();
                PingResult result = entry.getValue();
                content.append(result.timestamp).append(",").append(hostname)
                       .append(",").append(result.reachable ? "y" : "n")
                       .append(",").append(result.category != null ? result.category : "unknown")
                       .append(",").append(result.responseTime)
                       .append(",").append(result.leaseSetTypes != null ? result.leaseSetTypes : "[]")
                       .append("\n");
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
        long existingSize = hadExistingFile ? _categoriesFile.length() : 0;

        // Download to a temporary file first for better control
        File tempFile = new File(_categoriesFile.getAbsolutePath() + ".tmp");

        try {
            String categoryUrl = "http://notbob.i2p/graphs/cats.txt";

            // Wait for HTTP proxy to be ready
            if (_log.shouldInfo()) {
                _log.info("Waiting " + (CATEGORY_DOWNLOAD_DELAY_MS/1000) + "s before downloading categories from " + categoryUrl + "...");
            }
            Thread.sleep(CATEGORY_DOWNLOAD_DELAY_MS);

            if (_log.shouldInfo()) {
                _log.info("Downloading categories from " + categoryUrl + " to temp file");
            }

            // Delete any existing temp file
            if (tempFile.exists()) {
                tempFile.delete();
            }

            EepGet get = new EepGet(_context, "127.0.0.1", 4444, 3, tempFile.getAbsolutePath(), categoryUrl);
            get.addHeader("User-Agent", "I2P+ HostChecker");
            boolean fetchSuccess = get.fetch(CATEGORY_CONNECT_TIMEOUT, CATEGORY_TOTAL_TIMEOUT, CATEGORY_INACTIVITY_TIMEOUT);

            if (fetchSuccess && tempFile.exists() && tempFile.length() > 0) {
                if (_log.shouldInfo()) {
                    _log.info("Downloaded categories to temp file: " + tempFile.length() + " bytes");
                }

                // Read the downloaded content to verify it's valid
                List<String> downloadedLines = new ArrayList<>();
                int dataLineCount = 0;
                try (BufferedReader reader = new BufferedReader(new FileReader(tempFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        downloadedLines.add(line);
                        if (!line.trim().isEmpty() && !line.startsWith("#")) {
                            dataLineCount++;
                        }
                    }
                }

                if (dataLineCount > 0) {
                    if (_log.shouldInfo()) {
                        _log.info("Downloaded " + dataLineCount + " entries from notbob.i2p");
                    }

                    // Now write to the final file with header
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

                        // Write all downloaded data lines (skipping any existing headers in the download)
                        for (String line : downloadedLines) {
                            if (!line.trim().isEmpty() && !line.startsWith("#")) {
                                writer.write(line);
                                writer.newLine();
                            }
                        }
                    }

                    // Delete temp file
                    tempFile.delete();

                    // Reload categories into memory
                    loadCategories();

                    // Update hosts_check.txt with new categories and add new hosts
                    updateHostsWithCategories();

                    downloadSuccess = true;

                    if (_log.shouldInfo()) {
                        _log.info("Successfully downloaded categories to " + _categoriesFile.getAbsolutePath() +
                                 " (" + _categoriesFile.length() + " bytes, was " + existingSize + " bytes)");
                    }
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn("Downloaded categories file has no valid entries");
                    }
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
        } finally {
            // Clean up temp file
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }

        // If download failed but we have an existing stale file, continue with that
        if (!downloadSuccess && hadExistingFile) {
            if (_log.shouldInfo()) {
                _log.info("Using stale categories.txt file at " + _categoriesFile.getAbsolutePath());
            }
            // Still update hosts_check.txt with existing categories
            updateHostsWithCategories();
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
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                        lineCount++;
                    }
                    if (_log.shouldInfo()) {
                        _log.info("addCategoriesHeader: Read " + lineCount + " lines from categories.txt");
                    }
                }
            }

            // Count non-comment, non-empty lines that will be written
            int dataLineCount = 0;
            for (String line : lines) {
                if (!line.startsWith("#") && !line.trim().isEmpty()) {
                    dataLineCount++;
                }
            }
            if (_log.shouldInfo()) {
                _log.info("addCategoriesHeader: Found " + dataLineCount + " data entries to preserve");
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
                int writtenCount = 0;
                for (String line : lines) {
                    if (!line.startsWith("#") && !line.trim().isEmpty()) {
                        writer.write(line);
                        writer.newLine();
                        writtenCount++;
                    }
                }
                if (_log.shouldInfo()) {
                    _log.info("addCategoriesHeader: Wrote " + writtenCount + " data entries to categories.txt");
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
            int lineCount = 0;
            int entryCount = 0;
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
                    String category = parts[1].trim().toLowerCase(Locale.US);
                    _hostCategories.put(hostname, category);
                    validLines.add(line);
                    entryCount++;
                }
                lineCount++;
            }

            if (_log.shouldInfo()) {
                _log.info("Loaded " + entryCount + " host categories from " + _categoriesFile.getName() + " (total lines: " + lineCount + ")");
            }

            // Clean up the file if we found empty lines
            if (hasEmptyLines) {
                if (_log.shouldInfo()) {
                    _log.info("Cleaning up " + validLines.size() + " lines from categories.txt (removing empty lines)");
                }
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
     * Get description for a category
     * @param category the category name
     * @return the category description, or the category itself if unknown
     */
    public String getCategoryDescription(String category) {
        if (category == null || category.isEmpty()) {
            category = "unknown";
        }
        switch (category) {
            case "cryptocoin": return _t("Cryptocurrency related services");
            case "drugs": return _t("Controlled substances marketplaces");
            case "ebook": return _t("E-book libraries and publishing");
            case "filehost": return _t("File hosting and storage services");
            case "fileshare": return _t("Peer-to-peer file sharing");
            case "forum": return _t("Discussion forums and message boards");
            case "gallery": return _t("Image galleries and photo sharing");
            case "game": return _t("Gaming servers and communities");
            case "git": return _t("Git repositories and code hosting");
            case "help": return _t("Help and support resources");
            case "humanrights": return _t("Human rights and civil liberties");
            case "i2p": return _t("Official I2P infrastructure services");
            case "news": return _t("News and media outlets");
            case "pastebin": return _t("Text/code paste services");
            case "personal": return _t("Personal websites and blogs");
            case "radio": return _t("Internet radio and streaming");
            case "search": return _t("Search engines and directories");
            case "software": return _t("Software repositories and downloads");
            case "stats": return _t("Statistics and analytics");
            case "tool": return _t("Utilities and web tools");
            case "tracker": return _t("Torrent and content trackers");
            case "uhoh": return _t("Conspiracy / Religious content");
            case "unknown": return _t("Unclassified or pending review");
            case "untested": return _t("Not yet categorized");
            case "video": return _t("Video streaming and media");
            case "wiki": return _t("Wiki and collaborative documentation");
            case "wip": return _t("Work in progress / development");
            default: return category;
        }
    }

    /**
     * Helper method to create PingResult with category
     */
    private PingResult createPingResult(boolean reachable, long timestamp, long responseTime, String hostname) {
        String category = _hostCategories.get(hostname);
        return new PingResult(reachable, timestamp, responseTime, category, null);
    }

    /**
     * Helper method to create PingResult with category and leaseSetTypes
     */
    private PingResult createPingResult(boolean reachable, long timestamp, long responseTime, String hostname, String leaseSetTypes) {
        String category = _hostCategories.get(hostname);

        PingResult existing = _pingResults.get(hostname);
        if (existing != null && existing.leaseSetTypes != null && !existing.leaseSetTypes.equals("[]")) {
            if (leaseSetTypes == null || leaseSetTypes.equals("[]") || existing.leaseSetTypes.equals(leaseSetTypes)) {
                leaseSetTypes = existing.leaseSetTypes;
            }
        }

        return new PingResult(reachable, timestamp, responseTime, category, leaseSetTypes);
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
        // Download to a temporary file first for better control
        File tempFile = new File(_categoriesFile.getAbsolutePath() + ".tmp");

        try {
            String categoryUrl = "http://notbob.i2p/graphs/cats.txt";

            // Delete any existing temp file
            if (tempFile.exists()) {
                tempFile.delete();
            }

            EepGet get = new EepGet(_context, "127.0.0.1", 4444, 3, tempFile.getAbsolutePath(), categoryUrl);
            get.addHeader("User-Agent", "I2P+ HostChecker");
            boolean fetchSuccess = get.fetch(CATEGORY_CONNECT_TIMEOUT, CATEGORY_TOTAL_TIMEOUT, CATEGORY_INACTIVITY_TIMEOUT);

            if (fetchSuccess && tempFile.exists() && tempFile.length() > 0) {
                // Read the downloaded content to verify it's valid
                List<String> downloadedLines = new ArrayList<>();
                int dataLineCount = 0;
                try (BufferedReader reader = new BufferedReader(new FileReader(tempFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        downloadedLines.add(line);
                        if (!line.trim().isEmpty() && !line.startsWith("#")) {
                            dataLineCount++;
                        }
                    }
                }

                if (dataLineCount > 0) {
                    // Now write to the final file with header
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

                        // Write all downloaded data lines (skipping any existing headers in the download)
                        for (String line : downloadedLines) {
                            if (!line.trim().isEmpty() && !line.startsWith("#")) {
                                writer.write(line);
                                writer.newLine();
                            }
                        }
                    }

                    // Delete temp file
                    tempFile.delete();

                    if (_log.shouldInfo()) {
                        _log.info("Successfully downloaded categories on retry to " + _categoriesFile.getAbsolutePath() +
                                 " (" + dataLineCount + " entries)");
                    }
                    return true;
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn("Downloaded categories file on retry has no valid entries");
                    }
                    return false;
                }
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Failed to download categories on retry, result: " + fetchSuccess);
                }
                return false;
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Exception during category download retry", e);
            }
            return false;
        } finally {
            // Clean up temp file
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * Refresh categories at the start of each HostChecker cycle
     * Attempts to download fresh categories from notbob.i2p, falls back to existing file
     */
    private void refreshCategories() {
        boolean downloadSuccess = false;
        boolean hadExistingFile = _categoriesFile.exists();

        // Download to a temporary file first for better control
        File tempFile = new File(_categoriesFile.getAbsolutePath() + ".tmp");

        try {
            String categoryUrl = "http://notbob.i2p/graphs/cats.txt";

            if (_log.shouldInfo()) {
                _log.info("Refreshing categories from " + categoryUrl + "...");
            }

            // Delete any existing temp file
            if (tempFile.exists()) {
                tempFile.delete();
            }

            EepGet get = new EepGet(_context, "127.0.0.1", 4444, 1, tempFile.getAbsolutePath(), categoryUrl);
            get.addHeader("User-Agent", "I2P+ HostChecker");
            boolean fetchSuccess = get.fetch(CATEGORY_CONNECT_TIMEOUT, CATEGORY_TOTAL_TIMEOUT, CATEGORY_INACTIVITY_TIMEOUT);

            if (fetchSuccess && tempFile.exists() && tempFile.length() > 0) {
                // Read the downloaded content to verify it's valid
                List<String> downloadedLines = new ArrayList<>();
                int dataLineCount = 0;
                try (BufferedReader reader = new BufferedReader(new FileReader(tempFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        downloadedLines.add(line);
                        if (!line.trim().isEmpty() && !line.startsWith("#")) {
                            dataLineCount++;
                        }
                    }
                }

                if (dataLineCount > 0) {
                    // Now write to the final file with header
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

                        // Write all downloaded data lines (skipping any existing headers in the download)
                        for (String line : downloadedLines) {
                            if (!line.trim().isEmpty() && !line.startsWith("#")) {
                                writer.write(line);
                                writer.newLine();
                            }
                        }
                    }

                    // Delete temp file
                    tempFile.delete();

                    downloadSuccess = true;
                    if (_log.shouldInfo()) {
                        _log.info("Successfully refreshed categories from notbob.i2p (" + dataLineCount + " entries)");
                    }
                } else {
                    if (_log.shouldInfo()) {
                        _log.info("Downloaded categories file has no valid entries, using existing");
                    }
                }
            } else {
                if (_log.shouldInfo()) {
                    _log.info("Failed to refresh categories from notbob.i2p, using existing categories.txt");
                }
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Exception refreshing categories", e);
            }
        } finally {
            // Clean up temp file
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }

        // Always reload categories into memory (either from new download or existing file)
        if (downloadSuccess || hadExistingFile) {
            loadCategories();
            updateHostsWithCategories();
        }
    }

    /**
     * Update hosts_check.txt with category information after successful category download
     */
    private void updateHostsWithCategories() {
        try {
            int updatedCount = 0;
            int addedCount = 0;

            for (Map.Entry<String, PingResult> entry : _pingResults.entrySet()) {
                String hostname = entry.getKey();
                PingResult existingResult = entry.getValue();
                String category = _hostCategories.get(hostname);

                if (category != null && !category.equals(existingResult.category)) {
                    PingResult updatedResult = new PingResult(existingResult.reachable,
                                                           existingResult.timestamp,
                                                           existingResult.responseTime,
                                                           category,
                                                           existingResult.leaseSetTypes);
                    _pingResults.put(hostname, updatedResult);
                    updatedCount++;

                    if (_log.shouldDebug()) {
                        _log.debug("Updated category for " + hostname + ": " + category);
                    }
                }
            }

            for (Map.Entry<String, String> entry : _hostCategories.entrySet()) {
                String hostname = entry.getKey();
                if (!_pingResults.containsKey(hostname)) {
                    String category = entry.getValue();
                    long timestamp = System.currentTimeMillis();
                    PingResult newResult = new PingResult(false, timestamp, -1, category);
                    _pingResults.put(hostname, newResult);
                    addedCount++;

                    if (_log.shouldDebug()) {
                        _log.debug("Added new host with category: " + hostname + " -> " + category);
                    }
                }
            }

            if (updatedCount > 0 || addedCount > 0) {
                savePingResults();

                if (_log.shouldInfo()) {
                    String msg = "Updated categories for " + updatedCount + " hosts";
                    if (addedCount > 0) {
                        msg += ", added " + addedCount + " new hosts from categories.txt";
                    }
                    _log.info(msg);
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

            // Skip if previous cycle is still in progress
            if (!_cycleInProgress.compareAndSet(false, true)) {
                if (_log.shouldInfo()) {
                    _log.info("Skipping HostChecker cycle - previous cycle still in progress");
                }
                return;
            }

            // Update defensive mode based on tunnel build success
            updateDefensiveMode();

            // Check router load - skip entire cycle if severely overloaded
            int dynamicMaxConcurrent;
            if (_context instanceof RouterContext) {
                RouterContext routerContext = (RouterContext) _context;
                JobQueue jobQueue = routerContext.jobQueue();
                long maxLag = jobQueue.getMaxLag();
                // Skip entire cycle if router is severely overloaded (>2s lag)
                if (maxLag > MAX_JOB_LAG_MS) {
                    if (_log.shouldWarn()) {
                        _log.warn("HostChecker cycle SKIPPED -> Router overloaded (Job queue lag: " + maxLag + "ms)");
                    }
                    _cycleInProgress.set(false);
                    return;
                }
                // Scale concurrency from 1 (high lag) to _maxConcurrent (low lag)
                if (maxLag > MAX_JOB_LAG_MS/2) {
                    // At 1s lag: throttle to 1 concurrent
                    dynamicMaxConcurrent = 1;
                    if (_log.shouldWarn()) {
                        _log.warn("Router under load (Job queue lag: " + maxLag + "ms) -> Throttling HostChecker to 1 concurrent check...");
                    }
                } else if (maxLag < 1000) {
                    // At <1s lag: scale linearly from 2 to _maxConcurrent
                    dynamicMaxConcurrent = Math.max(2, (int) (_maxConcurrent * (1.0 - (maxLag - 1000.0) / 1000.0)));
                } else {
                    dynamicMaxConcurrent = _maxConcurrent;
                }
            } else {
                dynamicMaxConcurrent = _maxConcurrent;
            }

            // Apply defensive mode restrictions: reduce concurrency to 1 when under attack
            if (_defensiveMode) {
                dynamicMaxConcurrent = DEFENSIVE_MAX_CONCURRENT;
                if (_log.shouldWarn()) {
                    _log.warn("HostChecker in DEFENSIVE MODE -> Reducing concurrency to 1");
                }
            }

            // Refresh categories from notbob.i2p at the start of each cycle
            refreshCategories();

            // Refresh blacklist at the start of each cycle
            loadBlacklist();

            // Create dynamic semaphore based on current router load
            final Semaphore dynamicSemaphore = new Semaphore(dynamicMaxConcurrent);

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
                _cycleInProgress.set(false);
                return;
            }

            long cycleStartTime = System.currentTimeMillis();

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

                        final String hostnameToCheck = hostname;

                        // Submit ping task for concurrent execution with semaphore limit
                        java.util.concurrent.Future<Void> future = _scheduler.submit(new java.util.concurrent.Callable<Void>() {
                            @Override
                            public Void call() throws Exception {
                                try {
                                    // Acquire semaphore permit to limit concurrent pings
                                    dynamicSemaphore.acquire();

                                    // In defensive mode, skip ping/eephead, just check LeaseSet availability
                                    if (_defensiveMode) {
                                        if (_log.shouldDebug()) {
                                            _log.debug("Defensive mode: checking LeaseSet for " + hostnameToCheck);
                                        }
                                        checkLeaseSetOnly(hostnameToCheck);
                                        return null;
                                    }

                                    // Then add random delay (up to 5s)
                                    int randomDelay = 2000 + _context.random().nextInt(3000);
                                    Thread.sleep(randomDelay);

                                    if (_log.shouldInfo()) {
                                        _log.info("Starting HostChecker for " + hostnameToCheck + "...");
                                    }

                                    testDestination(hostnameToCheck);
                                    return null;
                                } finally {
                                    // Always release semaphore permit
                                    dynamicSemaphore.release();
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

                // Calculate cycle duration
                long cycleDuration = System.currentTimeMillis() - cycleStartTime;
                long cycleDurationMinutes = cycleDuration / 60000;
                long cycleDurationSeconds = (cycleDuration / 1000) % 60;

                if (_log.shouldInfo()) {
                    String durationStr = cycleDurationMinutes > 0
                        ? cycleDurationMinutes + "m " + cycleDurationSeconds + "s"
                        : cycleDurationSeconds + "s";
                    _log.info("HostChecker cycle completed in " + durationStr + ": " +
                              success + " / " + total + " reachable (" + skipped + " blacklisted hosts skipped)");
                }

                // Adjust interval if cycle takes too long
                long configuredIntervalMinutes = _pingInterval / 60000;
                long bufferMinutes = 3;

                if (cycleDurationMinutes > 0 && configuredIntervalMinutes > 0) {
                    if (cycleDurationMinutes > configuredIntervalMinutes) {
                        long newInterval = cycleDuration + (bufferMinutes * 60000);
                        if (_log.shouldWarn()) {
                            _log.warn("HostChecker cycle took " + cycleDurationMinutes + " minutes, which exceeds configured interval of " +
                                      configuredIntervalMinutes + " minutes -> Adjusting interval to " + (newInterval / 60000) + " minutes");
                        }
                        _pingInterval = newInterval;
                    }
                }

                // Apply defensive mode interval: 24 hours when under attack
                if (_defensiveMode) {
                    if (_pingInterval != DEFENSIVE_PING_INTERVAL) {
                        if (_log.shouldWarn()) {
                            _log.warn("HostChecker entering DEFENSIVE MODE interval: 24 hours");
                        }
                        _pingInterval = DEFENSIVE_PING_INTERVAL;
                    }
                } else {
                    // Restore normal interval if we were in defensive mode
                    long normalInterval = DEFAULT_PING_INTERVAL;
                    if (_pingInterval == DEFENSIVE_PING_INTERVAL) {
                        if (_log.shouldInfo()) {
                            _log.info("HostChecker restoring normal interval: " + (normalInterval / 60000) + " minutes");
                        }
                        _pingInterval = normalInterval;
                    }
                }

                // Save ping results to file after each cycle
                savePingResults();
            } catch (Exception e) {
                if (_log.shouldWarn()) {
                    _log.warn("Error during HostChecker cycle", e);
                }
            } finally {
                _cycleInProgress.set(false);
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
        synchronized (this) {
            return _blacklistedHosts.contains(hostname.toLowerCase());
        }
    }

    /**
     * Format LeaseSet encryption types as a string like "[0]" or "[4,0]"
     */
    private static String formatLeaseSetTypes(net.i2p.data.LeaseSet leaseSet) {
        if (leaseSet == null) {
            return "[]";
        }

        java.util.List<Integer> types = new java.util.ArrayList<>();

        // Check for LeaseSet2 type (multiple encryption keys)
        if (leaseSet.getType() == DatabaseEntry.KEY_TYPE_LS2) {
            // LeaseSet2 supports multiple encryption keys
            try {
                java.lang.reflect.Method getEncryptionKeys = leaseSet.getClass().getMethod("getEncryptionKeys");
                @SuppressWarnings("unchecked")
                java.util.List<net.i2p.data.PublicKey> keys = (java.util.List<net.i2p.data.PublicKey>) getEncryptionKeys.invoke(leaseSet);

                if (keys != null && !keys.isEmpty()) {
                    for (net.i2p.data.PublicKey key : keys) {
                        try {
                            java.lang.reflect.Method getType = key.getClass().getMethod("getType");
                            EncType encType = (EncType) getType.invoke(key);
                            if (encType != null) {
                                types.add(encType.getCode());
                            }
                        } catch (Exception e) {
                            // Ignore errors getting encryption type
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore reflection errors
            }
        } else {
            // LeaseSet type 1 - single encryption key
            try {
                java.lang.reflect.Method getEncryptionKey = leaseSet.getClass().getMethod("getEncryptionKey");
                net.i2p.data.PublicKey key = (net.i2p.data.PublicKey) getEncryptionKey.invoke(leaseSet);
                if (key != null) {
                    try {
                        java.lang.reflect.Method getType = key.getClass().getMethod("getType");
                        EncType encType = (EncType) getType.invoke(key);
                        if (encType != null) {
                            types.add(encType.getCode());
                        }
                    } catch (Exception e) {
                        // Ignore errors getting encryption type
                    }
                }
            } catch (Exception e) {
                // Ignore reflection errors
            }
        }

        if (types.isEmpty()) {
            return "[]";
        }

        // Join without spaces to match format [6,4] not [6, 4]
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(types.get(i));
        }
        return "[" + sb.toString() + "]";
    }


    /** translate */
    private static String _t(String s) {return Messages.getString(s);}

    /** translate */
    private static String _t(String s, Object o) {return Messages.getString(s, o);}

    /** translate */
    private static String _t(String s, Object o, Object o2) {return Messages.getString(s, o, o2);}

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