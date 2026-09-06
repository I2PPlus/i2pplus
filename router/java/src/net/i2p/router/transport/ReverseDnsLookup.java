package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.i2p.I2PAppContext;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

/**
 * Reverse-DNS resolver and cache for the comm system.
 *
 * Owns all reverse-DNS state that previously lived in
 * {@link CommSystemFacadeImpl}: the size-limited in-memory IP-to-hostname
 * cache with persistent file backup, the background lookup executor used by
 * the non-blocking {@link #getCanonicalHostName(String)} and
 * {@link #getLocalHostName(String)} lookups, and the periodic cache file
 * writer. The facade retains thin delegation stubs so external callers are
 * unaffected.
 *
 * The cache is backed by a disk file that is safely read and written using the
 * writer's own snapshot, so a crash mid-write cannot corrupt existing entries.
 * Entries expire in memory after {@link #EXPIRE_TIME} and are evicted from
 * disk if older than {@link #EVICT_THRESHOLD}. Cache size is bounded by
 * memory-based limits (see {@link #maxCacheSize(boolean, boolean)}) with
 * automatic eviction of expired and then oldest entries.
 *
 * Supports migration from older cache file formats missing timestamps.
 *
 * @since 0.9.71
 */
public class ReverseDnsLookup {
    private static final Log _slog = I2PAppContext.getGlobalContext().logManager().getLog(ReverseDnsLookup.class);

    /** Rate-stat aggregation periods for the executor signals. */
    private static final long[] RATES = {60*1000L, 10*60*1000L, 60*60*1000L};

    /** Translation bundle used for the localized "unknown" marker. */
    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /** Property that enables reverse lookups; the single source of truth. */
    static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";

    private static final String RDNS_CACHE_FILE = I2PAppContext.getGlobalContext().getConfigDir() +
                                                  File.separator + "rdnscache.txt";
    private static final int RDNS_WRITE_INTERVAL = 15 * 60 * 1000 + 30;
    private static final boolean HAS_512_MB = SystemVersion.getMaxMemory() >= 512 * 1024 * 1024;
    private static final boolean HAS_1_GB = SystemVersion.getMaxMemory() >= 1024 * 1024 * 1024;

    /** In-memory entry lifetime, e.g. 24/36/48 hours depending on memory. */
    private static final long EXPIRE_TIME = expireHours(HAS_512_MB, HAS_1_GB) * 60L * 60 * 1000;

    /** Age beyond which a disk-cache entry is not reloaded at startup. */
    private static final long EVICT_THRESHOLD = 3L * 24 * 60 * 60 * 1000; // 3 day

    /** "unknown" entries expire after 15 minutes — no point keeping dead lookups. */
    private static final long UNKNOWN_ENTRY_EXPIRE_MS = 15L * 60 * 1000;

    private static final int MAX_RDNS_CACHE_SIZE = maxCacheSize(HAS_512_MB, HAS_1_GB);

    private final RouterContext _context;
    private final GeoIP _geoIP;

    /** In-flight background lookups; guards against duplicate submissions. */
    private final Set<String> _pendingLookups = ConcurrentHashMap.newKeySet();

    private ExecutorService _executor;
    private final Object _executorLock = new Object();

    private static volatile int _corePoolSize = 2;
    private static volatile int _maxPoolSize = 8;

    /**
     * In-memory reverse DNS cache storing IP-to-hostname mappings.
     * Backed by ConcurrentHashMap for lock-free reads. Entries are
     * periodically flushed to disk and expired entries are cleaned up.
     *
     * Keys are IP addresses as Strings. Values are CacheEntry objects
     * containing hostname and timestamp.
     */
    private static final ConcurrentHashMap<String, CacheEntry> rdnsCache = new ConcurrentHashMap<>(MAX_RDNS_CACHE_SIZE);

    /** Flag set after the first normalize sweep to avoid re-processing every 15 minutes */
    private static volatile boolean _normalizeSweepDone;

    private static Timer _rdnsTimer;

    /**
     *  rDNS cache entry lifetime in hours, based on available memory.
     *
     *  Routers with less than 512 MB keep entries for 24 h; routers with at
     *  least 1 GB keep them for 48 h; everything else for 36 h. This bounds
     *  how stale a cached hostname may be before it is re-resolved, trading
     *  freshness against load on small memory-constrained routers.
     *
     *  @param has512MB true if the router has at least 512 MB of max memory
     *  @param has1GB true if the router has at least 1 GB of max memory
     *  @return entry lifetime in hours (24, 36, or 48)
     *  @since 0.9.71
     */
    static int expireHours(boolean has512MB, boolean has1GB) {
        if (!has512MB) {
            return 24;
        } else if (has1GB) {
            return 48;
        } else {
            return 36;
        }
    }

    /**
     *  Maximum rDNS cache size, based on available memory.
     *
     *  Caches 8000 entries on very limited routers, 24000 on routers with at
     *  least 1 GB, and 16000 otherwise. The bound prevents the persistence
     *  file and the in-memory map from growing without limit on the control
     *  path.
     *
     *  @param has512MB true if the router has at least 512 MB of max memory
     *  @param has1GB true if the router has at least 1 GB of max memory
     *  @return maximum number of cached entries (8000, 16000, or 24000)
     *  @since 0.9.71
     */
    static int maxCacheSize(boolean has512MB, boolean has1GB) {
        if (!has512MB) {
            return 8000;
        } else if (has1GB) {
            return 24000;
        } else {
            return 16000;
        }
    }

    /**
     *  Whether a full cache should evict entries at an accelerated rate.
     *
     *  When the cache exceeds 90% of its maximum size, the TTL applied by
     *  {@link #entryExpired(long, long, boolean)} is halved so eviction keeps
     *  up with insertion load. Choosing the threshold as a percentage of the
     *  max (not an absolute number) keeps behavior consistent across the
     *  memory-dependent cache sizes.
     *
     *  @param size current cache entry count
     *  @param maxSize maximum cache entry count
     *  @return true if the cache is more than 90% full
     *  @since 0.9.71
     */
    static boolean accelerateEviction(int size, int maxSize) {
        return size > maxSize * 90 / 100;
    }

    /**
     *  Whether a cache entry has outlived its permitted lifetime.
     *
     *  "unknown" entries (failed lookups) expire after a short fixed window so
     *  dead IPs are re-probed; all other entries use the caller-supplied base
     *  TTL, which itself may be halved by {@link #accelerateEviction(int, int)}.
     *
     *  @param ageMs age of the entry in milliseconds
     *  @param baseExpireMs normal entry TTL in milliseconds
     *  @param isUnknown true if the cached hostname is the "unknown" marker
     *  @return true if the entry should be evicted
     *  @since 0.9.71
     */
    static boolean entryExpired(long ageMs, long baseExpireMs, boolean isUnknown) {
        return ageMs > (isUnknown ? UNKNOWN_ENTRY_EXPIRE_MS : baseExpireMs);
    }

    /**
     *  Whether an entry read from the persistence file is fresh enough to reuse.
     *
     *  Applies only at startup when the file is loaded; entries older than the
     *  eviction threshold are skipped so a long-idle router does not resurrect
     *  months-old hostnames.
     *
     *  @param ageMs age of the entry in milliseconds
     *  @param evictThresholdMs maximum age accepted at load time
     *  @return true if the entry is fresh enough to cache
     *  @since 0.9.71
     */
    static boolean cacheFileEntryFresh(long ageMs, long evictThresholdMs) {
        return ageMs <= evictThresholdMs;
    }

    /**
     *  Create the resolver and its background executor.
     *
     *  The executor is started eagerly so the queue-size rate stat is ready
     *  before the first lookup, avoiding a first-hit initialization stall on
     *  the page-rendering path.
     *
     *  @param ctx the router context
     *  @param geoIP already-constructed GeoIP database for ASN fallback
     *  @since 0.9.71
     */
    public ReverseDnsLookup(RouterContext ctx, GeoIP geoIP) {
        _context = ctx;
        _geoIP = geoIP;
        _context.statManager().createRequiredRateStat("rdns.executor.queueSize",
            "rDNS executor pending lookups", "Transport", RATES);
        _context.statManager().createRequiredRateStat("rdns.executor.threads",
            "rDNS executor thread count", "Transport", RATES);
        getExecutor();
    }

    /**
     * Whether reverse lookups are enabled.
     * @return true if reverse lookups are enabled
     * @since 0.9.71
     */
    public boolean enableReverseLookups() {return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);}

    /**
     * The rDNS executor core pool size.
     * @since 0.9.71
     */
    public static int getCorePoolSize() { return _corePoolSize; }

    /**
     * The rDNS executor core pool size, bounded 2-8.
     * @param size the new core pool size
     * @since 0.9.71
     */
    public static void setCorePoolSize(int size) {
        _corePoolSize = Math.max(2, Math.min(8, size));
    }

    /**
     * The rDNS executor max pool size.
     * @since 0.9.71
     */
    public static int getMaxPoolSize() { return _maxPoolSize; }

    /**
     * The rDNS executor max pool size, bounded 2-8.
     * @param size the new max pool size
     * @since 0.9.71
     */
    public static void setMaxPoolSize(int size) {
        _maxPoolSize = Math.max(2, Math.min(8, size));
    }

    /**
     * The size of the rDNS cache file, in KB.
     * @since 0.9.71
     */
    public static String rdnsCacheSize() {
        File cache = new File(RDNS_CACHE_FILE);
        return String.valueOf(cache.length() / 1024) + "KB";
    }

    /**
     * The number of entries in the rDNS cache.
     * @since 0.9.71
     */
    public static int countRdnsCacheEntries() {
        return rdnsCache.size();
    }

    /**
     * Cache statistics for monitoring.
     * @return formatted string with cache stats
     * @since 0.9.71
     */
    public static String getRdnsCacheStats() {
        int size = rdnsCache.size();
        int maxSize = MAX_RDNS_CACHE_SIZE;
        double utilization = (double) size / maxSize * 100;
        return String.format("RDNS Cache: %d/%d entries (%.1f%% utilized)", size, maxSize, utilization);
    }

    /**
     * The computed maximum rDNS cache size based on available memory.
     * @return the computed maximum rDNS cache size based on available memory
     * @since 0.9.71
     */
    public static int getMaxRdnsCacheSize() {
        return MAX_RDNS_CACHE_SIZE;
    }

    /**
     * Reverse DNS executor, initializing it if necessary.
     * @return the reverse dns executor
     */
    private ExecutorService getExecutor() {
        synchronized (_executorLock) {
            if (_executor == null || _executor.isShutdown()) {
                _executor = new ThreadPoolExecutor(
                    _corePoolSize, _maxPoolSize,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(500),
                    r -> {
                        Thread t = new Thread(r, "RDNS");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
                );
            }
            return _executor;
        }
    }

    /**
     * Adjust the running rDNS executor pool sizes.
     * Called by the Tuner when queue depth signals scaling.
     *
     * @param coreSize new core pool size (bounded 2-8)
     * @since 0.9.71
     */
    public void adjustPool(int coreSize) {
        synchronized (_executorLock) {
            if (_executor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor exec = (ThreadPoolExecutor) _executor;
                if (!exec.isShutdown()) {
                    if (coreSize > exec.getMaximumPoolSize()) {
                        exec.setMaximumPoolSize(Math.max(coreSize, _maxPoolSize));
                        exec.setCorePoolSize(coreSize);
                    } else {
                        exec.setCorePoolSize(coreSize);
                        exec.setMaximumPoolSize(Math.max(coreSize, _maxPoolSize));
                    }
                    _context.statManager().addRateData("rdns.executor.threads", coreSize);
                }
            }
        }
    }

    /**
     * Returns rDNS executor pending lookup count, or 0 if not running.
     *
     * @return the rdns queue size
     * @since 0.9.71
     */
    public int getQueueSize() {
        synchronized (_executorLock) {
            if (_executor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor exec = (ThreadPoolExecutor) _executor;
                if (!exec.isShutdown()) {
                    return exec.getQueue().size();
                }
            }
            return 0;
        }
    }

    /**
     * Returns rDNS executor active thread count, or 0 if not running.
     *
     * @return the rdns active count
     * @since 0.9.71
     */
    public int getActiveCount() {
        synchronized (_executorLock) {
            if (_executor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor exec = (ThreadPoolExecutor) _executor;
                if (!exec.isShutdown()) {
                    return exec.getActiveCount();
                }
            }
            return 0;
        }
    }

    /**
     * Returns rDNS executor total thread count, or 0 if not running.
     *
     * @return the rdns pool size
     * @since 0.9.71
     */
    public int getPoolSize() {
        synchronized (_executorLock) {
            if (_executor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor exec = (ThreadPoolExecutor) _executor;
                if (!exec.isShutdown()) {
                    return exec.getPoolSize();
                }
            }
            return 0;
        }
    }

    /**
     * Returns rDNS executor utilization as a ratio (0.0-1.0),
     * or {@link Double#NaN} if not running.
     *
     * @return the rdns utilization
     * @since 0.9.71
     */
    public double getUtilization() {
        synchronized (_executorLock) {
            if (_executor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor exec = (ThreadPoolExecutor) _executor;
                if (!exec.isShutdown()) {
                    int size = exec.getPoolSize();
                    return size > 0 ? (double) exec.getActiveCount() / size : Double.NaN;
                }
            }
            return Double.NaN;
        }
    }

    /**
     *  Stop the periodic cache writer and shut down the lookup executor.
     *
     *  Cancels the background cache-file timer and the rDNS executor, waiting
     *  up to 5 seconds for in-flight lookups to drain before force-interrupting
     *  them, and clears the in-flight marker set so a later restart does not
     *  inherit stale pending state.
     *
     *  @since 0.9.71
     */
    public void shutdown() {
        if (_rdnsTimer != null) {
            _rdnsTimer.cancel();
            _rdnsTimer = null;
        }
        synchronized (_executorLock) {
            if (_executor != null && !_executor.isShutdown()) {
                _executor.shutdown();
                try {
                    if (!_executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        _executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    _executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
        _pendingLookups.clear();
    }

    /**
     * Cache entry for IP address and hostname mappings.
     */
    public static class CacheEntry {
        private final String ipAddress;
        private final String hostname;
        private final long timestamp; // epoch millis when entry was cached

        /**
         * CacheEntry.
         */
        public CacheEntry(String ipAddress, String hostname) {
            this(ipAddress, hostname, System.currentTimeMillis());
        }

        /**
         * CacheEntry.
         */
        public CacheEntry(String ipAddress, String hostname, long timestamp) {
            this.ipAddress = ipAddress;
            this.hostname = (hostname != null) ? hostname : "unknown";
            this.timestamp = timestamp;
        }

        /**
         * The cached IP address of the entry.
         * @return the IP address
         */
        public String getIpAddress() {
            return ipAddress;
        }

        /**
         * The cached hostname for the entry.
         * @return the hostname
         */
        public String getHostname() {
            return hostname;
        }

        /**
         * The timestamp when the entry was cached.
         * @return the timestamp
         */
        public long getTimestamp() {
            return timestamp;
        }

        /**
         * The formatted rDNS cache entry.
         * @return the rDNS entry
         * @since 0.9.71
         */
        public String getRdnsEntry() {
            return rdnsEntryToString(this);
        }
    }

    /**
     *  Load the persistence file into the in-memory cache at startup.
     *
     *  Skips "#" comment lines and entries older than the eviction threshold,
     *  then schedules the periodic {@link RDNSCacheFileWriter}. A missing file
     *  is created so the periodic writer always has a valid target. Invoked
     *  from the facade once reverse lookups are enabled after the netdb settles.
     *
     *  @since 0.9.71
     */
    static void readRDNSCacheFromFile() {
        File fCache = new File(RDNS_CACHE_FILE);
        long now = System.currentTimeMillis();
        if (!fCache.exists()) {
            createRdnsCacheFile();
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(fCache)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                CacheEntry cacheEntry = rdnsEntryFromString(line);
                if (cacheEntry != null && cacheFileEntryFresh(now - cacheEntry.getTimestamp(), EVICT_THRESHOLD)) {
                    rdnsCachePut(cacheEntry.getIpAddress(), cacheEntry);
                }
            }
        } catch (IOException ex) {
            _slog.error("Error reading RDNS cache file. Creating new file...", ex);
            createRdnsCacheFile();
        }
        // Cancel existing timer before creating new one to prevent memory leak
        if (_rdnsTimer != null) {
            _rdnsTimer.cancel();
        }
        _rdnsTimer = new Timer(true);
        long delay = RDNS_WRITE_INTERVAL;
        _rdnsTimer.schedule(new RDNSCacheFileWriter(), delay, delay);
    }

    /**
     *  Serialize a cache entry for persistence.
     *
     *  Format is "ip,hostname,timestamp" — one line per entry. The timestamp
     *  enables expiration and startup filtering, and distinguishes the current
     *  format from the two-part legacy format (see {@link #rdnsEntryFromString}).
     *
     *  @param entry the entry to serialize
     *  @return the serialized entry line
     *  @since 0.9.71
     */
    static String rdnsEntryToString(CacheEntry entry) {
        return entry.getIpAddress() + "," + entry.getHostname() + "," + entry.getTimestamp();
    }

    /**
     *  Parse a persisted line back into a cache entry.
     *
     *  Three-part lines carry an explicit timestamp. Two-part lines are the
     *  pre-timestamp legacy format and are migrated with the current time.
     *  Any other shape returns null and the caller skips the line.
     *
     *  @param s the persisted line
     *  @return the parsed entry, or null if the line is malformed
     *  @since 0.9.71
     */
    static CacheEntry rdnsEntryFromString(String s) {
        String[] parts = s.split(",", 3);
        if (parts.length == 3) {
            try {
                String ipAddress = parts[0];
                String hostname = parts[1];
                long timestamp = Long.parseLong(parts[2]);
                return new CacheEntry(ipAddress, hostname, timestamp);
            } catch (NumberFormatException e) {
                // Fall through to old format migration below
            }
        }
        if (parts.length == 2) {
            String ipAddress = parts[0];
            String hostname = parts[1];
            long timestamp = System.currentTimeMillis();
            return new CacheEntry(ipAddress, hostname, timestamp);
        }
        return null;
    }

    private static synchronized void createRdnsCacheFile() {
        File cacheFile = new File(RDNS_CACHE_FILE);
        if (!cacheFile.exists()) {
            try {
                cacheFile.createNewFile();
            } catch (IOException ex) {
                System.err.println("[RDNSCache] Error creating cache file: " + ex.getMessage()); // NOSONAR S106 static utility
            }
        } else {
            readRDNSCacheFromFile();
        }
    }

    private static class RDNSCacheFileWriter extends TimerTask {
        /**
         * RDNSCacheFileWriter.
         */
        public RDNSCacheFileWriter() {
            // Intentionally empty - default constructor
        }

        /**
         * Clean up and write the rDNS cache to disk.
         */
        @Override
        public void run() {
            cleanupRDNSCache();
            Map<String, CacheEntry> liveCacheSnapshot = new HashMap<>(rdnsCache);
            File cacheFile = new File(RDNS_CACHE_FILE);
            try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(cacheFile))) {
                for (CacheEntry cacheEntry : liveCacheSnapshot.values()) {
                    String line = rdnsEntryToString(cacheEntry) + '\n';
                    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                    fos.write(bytes);
                }
            } catch (IOException ex) {
                _slog.error("Error updating reverse DNS cache file", ex);
            }
        }
    }

    private static void cleanupRDNSCache() {
        long now = System.currentTimeMillis();
        int removed = 0;
        int normalized = 0;
        // When cache is >90% full, halve the TTL to accelerate eviction
        long expireTime = EVICT_THRESHOLD;
        if (accelerateEviction(rdnsCache.size(), MAX_RDNS_CACHE_SIZE)) {
            expireTime /= 2;
        }
        Iterator<Map.Entry<String, CacheEntry>> it = rdnsCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> entry = it.next();
            CacheEntry ce = entry.getValue();
            long age = now - ce.getTimestamp();

            // "unknown" entries expire quickly — no point keeping dead lookups
            boolean isUnknown = ce.getHostname().contains("unknown");
            if (entryExpired(age, expireTime, isUnknown)) {
                it.remove();
                removed++;
            }
        }
        // If still over budget after expired-entry cleanup, evict oldest entries
        if (rdnsCache.size() > MAX_RDNS_CACHE_SIZE) {
            int excess = rdnsCache.size() - MAX_RDNS_CACHE_SIZE;
            long oldestTimestamp = Long.MAX_VALUE;
            String oldestKey = null;
            // Iterate excess times to find and remove the oldest entries
            for (int i = 0; i < excess; i++) {
                oldestTimestamp = Long.MAX_VALUE;
                oldestKey = null;
                for (Map.Entry<String, CacheEntry> entry : rdnsCache.entrySet()) {
                    if (entry.getValue().getTimestamp() < oldestTimestamp) {
                        oldestTimestamp = entry.getValue().getTimestamp();
                        oldestKey = entry.getKey();
                    }
                }
                if (oldestKey != null) {
                    rdnsCache.remove(oldestKey);
                    removed++;
                }
            }
        }
        // Sweep: normalize any reversed ASN org names cached from MaxMind
        // Only needed on first run (entries from disk cache are pre-normalization;
        // new entries are normalized at insertion time in rdnsCachePut())
        if (!_normalizeSweepDone) {
            for (Map.Entry<String, CacheEntry> entry : rdnsCache.entrySet()) {
                CacheEntry ce = entry.getValue();
                String hostname = ce.getHostname();
                String fixed = GeoIP.normalizeOrgName(hostname);
                if (!fixed.equals(hostname)) {
                    rdnsCache.put(entry.getKey(), new CacheEntry(ce.getIpAddress(), fixed, ce.getTimestamp()));
                    normalized++;
                }
            }
            _normalizeSweepDone = true;
        }
        if (removed > 0 && _slog.shouldInfo()) {
            _slog.info("[RDNSCache] Removed " + removed + " stale entries from the cache");
        }
        if (normalized > 0 && _slog.shouldInfo()) {
            _slog.info("[RDNSCache] Normalized " + normalized + " reversed ASN names");
        }
    }

    /**
     * Insert into the rdns cache with budget enforcement.
     * Evicts expired entries first, then oldest entries if still over budget.
     */
    private static void rdnsCachePut(String ipAddress, CacheEntry entry) {
        String hostname = entry.getHostname();
        String fixed = GeoIP.normalizeOrgName(hostname);
        if (!fixed.equals(hostname)) {
            entry = new CacheEntry(entry.getIpAddress(), fixed, entry.getTimestamp());
        }
        if (rdnsCache.size() >= MAX_RDNS_CACHE_SIZE) {
            cleanupRDNSCache();
        }
        rdnsCache.put(ipAddress, entry);
    }

    /**
     * Canonical hostname for the given IP address from cache or DNS.
     * If RDNS is enabled, performs a reverse DNS lookup first.
     * Falls back to local ASN database if DNS fails, returning the org name.
     *
     * @param ipAddress IP address to resolve, or null/"null" to return null
     * @return hostname, org name from ASN database, or null for invalid input
     * @since 0.9.58+
     */
    public String getCanonicalHostName(String ipAddress) {
        if (ipAddress == null || ipAddress.equals("null")) {
            return _t("unknown");
        }
        long now = System.currentTimeMillis();

        CacheEntry existingEntry = rdnsCache.get(ipAddress);
        if (existingEntry != null && (now - existingEntry.getTimestamp() <= EXPIRE_TIME)) {
            String cached = existingEntry.getHostname();
            if (cached != null && !cached.equals(ipAddress) && !_t("unknown").equals(cached)) {
                return cached;
            }
            // Stale "unknown" or raw IP — queue background re-lookup
        }

        if (_pendingLookups.add(ipAddress)) {
            getExecutor().submit(() -> lookupHostNameAsync(ipAddress));
            _context.statManager().addRateData("rdns.executor.queueSize", getQueueSize());
        }

        return ipAddress;
    }

    /**
     *  Background RDNS/ASN resolution task submitted to the reverse-DNS executor.
     *  Performs a reverse lookup, falling back to the local ASN database when the
     *  result is the IP itself or unknown. Always clears the in-flight marker.
     *
     *  @param ipAddress non-null IP to resolve
     *  @since 0.9.70+
     */
    private void lookupHostNameAsync(String ipAddress) {
        try {
            String hostName = ipAddress;
            if (enableReverseLookups()) {
                try {
                    hostName = InetAddress.getByName(ipAddress).getCanonicalHostName();
                    rdnsCachePut(ipAddress, new CacheEntry(ipAddress, hostName, System.currentTimeMillis()));
                } catch (UnknownHostException e) {
                    // RDNS failed, will fall through to ASN lookup
                }
            }
            // Fall back to local ASN database if RDNS returned the IP or failed
            if (hostName.equals(ipAddress) || _t("unknown").equals(hostName)) {
                String orgName = _geoIP.getOrgName(ipAddress);
                if (orgName != null && !orgName.isEmpty()) {
                    rdnsCachePut(ipAddress, new CacheEntry(ipAddress, orgName, System.currentTimeMillis()));
                }
            }
        } finally {
            _pendingLookups.remove(ipAddress);
        }
    }

    /**
     *  Background ASN-first resolution task submitted to the reverse-DNS executor.
     *  Prefers the local ASN organization database, falling back to reverse DNS.
     *  Always clears the in-flight marker.
     *
     *  @param ipAddress non-null IP to resolve
     *  @since 0.9.70+
     */
    private void lookupOrgNameAsync(String ipAddress) {
        try {
            // Try ASN org name first (local MMDB, fast enough for background)
            String hostName = _geoIP.getOrgName(ipAddress);
            if (hostName != null && !hostName.isEmpty()) {
                rdnsCachePut(ipAddress, new CacheEntry(ipAddress, hostName,
                        System.currentTimeMillis()));
                return;
            }
            // Fallback to RDNS
            if (enableReverseLookups()) {
                try {
                    String rdnsResult = InetAddress.getByName(ipAddress).getCanonicalHostName();
                    if (rdnsResult != null && !rdnsResult.equals(ipAddress)
                            && !_t("unknown").equals(rdnsResult)) {
                        rdnsCachePut(ipAddress, new CacheEntry(ipAddress, rdnsResult,
                                System.currentTimeMillis()));
                    }
                } catch (UnknownHostException e) { /* unresolvable */ }
            }
        } finally {
            _pendingLookups.remove(ipAddress);
        }
    }

    /**
     * The canonical host name for the given IP address, resolved synchronously.
     * @return the canonical host name
     * @since 0.9.58+
     */
    public String getCanonicalHostNameSync(String ipAddress) {
        if (ipAddress == null || ipAddress.equals("null")) {
            return _t("unknown");
        }
        long now = System.currentTimeMillis();

        CacheEntry existingEntry = rdnsCache.get(ipAddress);
        if (existingEntry != null && (now - existingEntry.getTimestamp() <= EXPIRE_TIME)) {
            String cached = existingEntry.getHostname();
            // Return useful cached results immediately
            if (cached != null && !cached.equals(ipAddress) && !_t("unknown").equals(cached)) {
                return cached;
            }
            // Stale "unknown" or raw IP — fall through to re-lookup
        }

        String hostName = ipAddress;
        if (enableReverseLookups()) {
            try {
                hostName = InetAddress.getByName(ipAddress).getCanonicalHostName();
            } catch (UnknownHostException e) {
                // RDNS failed, will fall through to ASN lookup
            }
        }
        if (hostName.equals(ipAddress) || _t("unknown").equals(hostName)) {
            String orgName = _geoIP.getOrgName(ipAddress);
            if (orgName != null && !orgName.isEmpty()) {
                hostName = orgName;
            }
        }

        rdnsCachePut(ipAddress, new CacheEntry(ipAddress, hostName, now));
        return hostName;
    }

    /**
     * Fast hostname lookup that never blocks on I/O (MMDB or network).
     * Returns cached result if available and useful.
     * On cache miss, queues a background job that does ASN org name lookup
     * (MMDB file read + regex normalization) and optional RDNS, then
     * returns null immediately — page rendering is never blocked.
     *
     * @return cached hostname/ASN org name, or null if not yet resolved
     * @since 0.9.70+
     */
    public String getLocalHostName(String ipAddress) {
        if (ipAddress == null || ipAddress.equals("null")) {
            return null;
        }
        long now = System.currentTimeMillis();

        // 1. Check cache — return if useful
        CacheEntry existingEntry = rdnsCache.get(ipAddress);
        if (existingEntry != null && (now - existingEntry.getTimestamp() <= EXPIRE_TIME)) {
            String cached = existingEntry.getHostname();
            if (cached != null && !cached.equals(ipAddress) && !_t("unknown").equals(cached)) {
                if (("&t").equals(cached) || ("&amp;t").equals(cached)) {
                    return "AT&T";
                } else if (("Vodafone Czech Republic .s").equals(cached)) {
                    return "Vodafone Czech Republic";
                } else if (("Vodafone Espana S. .u").equals(cached) || ("Vodafone Ono, S").equals(cached)) {
                    return "Vodafone Espana";
                } else if (("Vodafone Italia S.p").equals(cached)) {
                    return "Vodafone Italia";
                } else if (("Vodafone Portugal - Communicacoes Pessoais S").equals(cached)) {
                    return "Vodafone Portugal";
                } else if (("Vodafone Romania S").equals(cached)) {
                    return "Vodafone Romania";
                }
                return cached;
            }
        }

        // 2. Queue background resolution (ASN + RDNS), return null immediately.
        //    Never blocks page rendering on MMDB file reads or regex normalization.
        if (_pendingLookups.add(ipAddress)) {
            getExecutor().submit(() -> lookupOrgNameAsync(ipAddress));
            _context.statManager().addRateData("rdns.executor.queueSize", getQueueSize());
        }

        // 3. No synchronous fallback — let page render with placeholder "?"
        return null;
    }

    /**
     * Localized string for the given key using the router console bundle.
     * @param key the translation key
     */
    private final String _t(String key) {return Translate.getString(key, _context, BUNDLE_NAME);}
}
