package net.i2p.router;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Detects patterns in router hashes to predictively ban algorithmically-generated identities.
 * Tracks byte patterns from banned routers and applies predictive bans when confidence is high.
 *
 * @since 0.9.68
 */
public class HashPatternDetector implements Serializable {
    private static final long serialVersionUID = 1L;

    private final RouterContext _context;
    private final Log _log;

    // Static BanLogger for sessionbans.txt
    private static final BanLogger _banLogger = new BanLogger(null);

    // Pattern tracking: prefix (hex string) -> statistics
    private final Map<String, PrefixStats> _prefixStats = new ConcurrentHashMap<>();

    // Track predictively banned hashes to avoid re-processing
    private final Set<String> _predictivelyBanned = ConcurrentHashMap.newKeySet();

    // Cache for computed prefixes to avoid repeated Base64 decoding
    private final ConcurrentHashMap<String, String> _prefixCache = new ConcurrentHashMap<>();

    // Recovery tracking
    private final AtomicBoolean _predictiveBanningActive = new AtomicBoolean(false);
    private long _predictiveBanEnableTime = 0;
    private static final long RECOVERY_COOLDOWN = 60 * 60 * 1000L; // 1 hour before allowing disable

    // Configuration
    private static final int PREFIX_LENGTH = 3; // First 3 bytes (6 hex chars) - collision risk
    private static final double BAN_THRESHOLD = 0.80; // 80% confidence for pre-emptive banning
    private static final int MIN_SAMPLES = 15; // Minimum samples before prediction (conservative)
    private static final int MIN_KNOWN_ROUTERS = 1000; // Minimum known routers before enabling (conservative)
    private static final String PATTERN_FILE = "hash-patterns.dat";
    private static final long PREDICTIVE_BAN_DURATION = 48 * 60 * 60 * 1000L; // 48 hours for pre-emptive bans

    // Gap-based detection configuration
    private static final int GAP_CLUSTERING_THRESHOLD = 75; // 75% of gaps within narrow range (conservative)
    private static final int GAP_RANGE = 256; // Gaps within this are considered "clustered"
    private static final int MIN_RUN_LENGTH = 5; // 5+ consecutive increments = scripted (conservative)
    private static final int CROSS_PREFIX_GAP = 0x100; // Gap indicating prefix boundary crossing

    // NetDB Scanner Configuration
    private static final String PROP_HASH_SCAN_FREQUENCY = "router.hashScan.frequency";
    private static final long DEFAULT_SCAN_FREQUENCY = 15 * 60 * 1000L; // 15 minutes - aggressive scanning
    private static final long SCAN_STARTUP_DELAY = 5 * 60 * 1000L; // 5 minutes
    private static final long BAN_DURATION = 48 * 60 * 60 * 1000L; // 48 hours for auto-bans

    // Scanner state
    private final AtomicBoolean _scanInProgress = new AtomicBoolean(false);

    public HashPatternDetector(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(HashPatternDetector.class);
        loadPatterns();
        loadHistoricalBans();

        // Defer pattern check to startScanner() where router is fully initialized
        // We load historical bans but won't enable predictive banning until later
    }

    /**
     * Analyze a router hash and apply predictive ban if pattern matches known attack signatures.
     *
     * @param hash Router hash to analyze
     * @param context Context for banlist operations
     * @return true if predictively banned, false otherwise
     */
    public boolean analyzeAndPredict(Hash hash, RouterContext context) {
        if (hash == null || _predictivelyBanned.contains(hash.toBase64())) {
            return false;
        }

        String hashStr = hash.toBase64();
        String prefix = getPrefix(hashStr);

        PrefixStats stats = _prefixStats.get(prefix);
        if (stats == null) {
            return false;
        }

        // Check if this prefix has high attack correlation
        if (stats.getConfidence() >= BAN_THRESHOLD && stats.getTotalBans() >= MIN_SAMPLES) {
            // Don't re-ban already banned routers
            if (context.banlist().isBanlisted(hash)) {
                return false;
            }

            // Apply predictive ban
            context.banlist().banlistRouter(hash,
                " <b>➜</b> HashPatternDetector (predictive)" + prefix,
                null, null,
                context.clock().now() + PREDICTIVE_BAN_DURATION);

            _predictivelyBanned.add(hashStr);

            if (_log.shouldWarn()) {
                _log.warn("Predictively banning router [" + hash.toBase64().substring(0, 6) +
                         "] with prefix " + prefix + " (Confidence: " +
                         String.format("%.1f%%", stats.getConfidence() * 100) + ")");
            }

            return true;
        }

        return false;
    }

    /**
     * Record a ban for pattern analysis.
     *
     * @param hash Banned router hash
     * @param reason Ban reason
     */
    public void recordBan(Hash hash, String reason) {
        if (hash == null || reason == null) {
            return;
        }

        String hashStr = hash.toBase64();
        if (hashStr == null || hashStr.isEmpty()) {
            return;
        }
        String prefix = getPrefix(hashStr);

        PrefixStats stats = _prefixStats.computeIfAbsent(prefix, k -> new PrefixStats(prefix));
        double oldConfidence = stats.getConfidence();
        int oldTotal = stats.getTotalBans();
        stats.recordBan(reason);
        double newConfidence = stats.getConfidence();
        int newTotal = stats.getTotalBans();

        // Log when we first cross the pattern detection threshold
        if (oldConfidence < BAN_THRESHOLD && newConfidence >= BAN_THRESHOLD && newTotal >= MIN_SAMPLES) {
            if (_log.shouldWarn()) {
                _log.warn("WARNING! Router hash prefix '" + prefix +
                         "' has reached " + String.format("%.1f%%", newConfidence * 100) +
                         " confidence (" + newTotal + " samples) " +
                         "\n* Automatic predictive banning is now ENABLED for this pattern");
            }
        }

        if (_log.shouldDebug()) {
            _log.debug("Recorded ban for prefix " + prefix + " - confidence now: " +
                      String.format("%.1f%%", stats.getConfidence() * 100));
        }
    }

    /**
     * Get the hex prefix of a base64 hash.
     * Results are cached to avoid repeated Base64 decoding.
     */
    private String getPrefix(String base64Hash) {
        if (base64Hash == null || base64Hash.isEmpty()) {
            return "0000";
        }
        return _prefixCache.computeIfAbsent(base64Hash, hash -> {
            try {
                byte[] bytes = net.i2p.data.Base64.decode(hash.substring(0, 8));
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(bytes.length, PREFIX_LENGTH); i++) {
                    hex.append(String.format("%02x", bytes[i] & 0xFF));
                }
                return hex.toString();
            } catch (Exception e) {
                return "0000";
            }
        });
    }

    /**
     * Check if predictive banning is currently active and within recovery period.
     *
     * @return true if predictive banning should be applied
     */
    public boolean isPredictiveBanningActive() {
        if (!_predictiveBanningActive.get()) {
            return false;
        }

        // Check if we've passed the recovery cooldown period
        long timeSinceEnable = _context.clock().now() - _predictiveBanEnableTime;
        if (timeSinceEnable > RECOVERY_COOLDOWN) {
            // After cooldown, re-evaluate based on current peer count
            Set<RouterInfo> routers = _context.netDb().getRouters();
            if (routers != null && routers.size() < 500) {
                if (_log.shouldWarn()) {
                    _log.warn("Disabling predictive banning -> Peer pool too small (" +
                             routers.size() + " peers, minimum 500 required)");
                }
                _predictiveBanningActive.set(false);
                return false;
            }
        }
        return true;
    }

    /**
     * Disable predictive banning manually or after recovery period.
     */
    public void disablePredictiveBanning() {
        _predictiveBanningActive.set(false);
        if (_log.shouldWarn()) {
            _log.warn("Predictive banning has been disabled");
        }
    }

    /**
     * Get remaining recovery time in milliseconds.
     *
     * @return remaining time, or 0 if not in recovery
     */
    public long getRemainingRecoveryTime() {
        if (!_predictiveBanningActive.get()) {
            return 0;
        }
        long elapsed = _context.clock().now() - _predictiveBanEnableTime;
        return Math.max(0, RECOVERY_COOLDOWN - elapsed);
    }

    /**
     * Public method to get the hex prefix of a hash.
     * Used for logging predictive bans.
     */
    public String getHashPrefix(Hash hash) {
        if (hash == null) {
            return "0000";
        }
        return getPrefix(hash.toBase64());
    }

    /**
     * Save pattern statistics to disk.
     */
    /**
      * Load historical ban data from sessionbans.txt and archives.
      */
    private void loadHistoricalBans() {
        File logDir = new File(_context.getRouterDir(), "sessionbans");
        if (!logDir.exists() || !logDir.isDirectory()) {
            return;
        }

        int loadedCount = 0;

        // Load current sessionbans.txt
        File currentLog = new File(logDir, "sessionbans.txt");
        if (currentLog.exists()) {
            loadedCount += parseBanLogFile(currentLog);
        }

        // Load archived files
        File[] archives = logDir.listFiles((dir, name) -> name.startsWith("sessionbans-") && name.endsWith(".txt"));
        if (archives != null) {
            for (File archive : archives) {
                loadedCount += parseBanLogFile(archive);
            }
        }

        if (_log.shouldInfo() && loadedCount > 0) {
            _log.info("Loaded " + loadedCount + " historical bans for pattern analysis from " +
                     (1 + (archives != null ? archives.length : 0)) + " files");
        }
    }

    /**
     * Parse a ban log file and record patterns.
     */
    private int parseBanLogFile(File file) {
        int count = 0;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Parse format: TIMESTAMP | HASH | IP:PORT | REASON | DURATION
                String[] parts = line.split("\\s*\\|\\s*");
                if (parts.length >= 5) {
                    String hashStr = parts[1].trim();
                    String reason = parts[3].trim();

                    // Skip if not a valid hash
                    if (!"UNKNOWN".equals(hashStr) && hashStr.length() > 10) {
                        try {
                            Hash hash = new Hash();
                            hash.fromBase64(hashStr);
                            recordBan(hash, reason);
                            count++;
                        } catch (Exception e) {
                            // Skip invalid hashes
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (_log.shouldDebug()) {
                _log.debug("Failed to parse ban log: " + file.getName());
            }
        }
        return count;
    }

    /**
      * Load pattern statistics from disk.
      * Legacy file is deleted on startup if found.
      */
     @SuppressWarnings("unchecked")
     private void loadPatterns() {
         File file = new File(_context.getRouterDir(), PATTERN_FILE);
         if (!file.exists()) {
             return;
         }

         if (_log.shouldWarn()) {
             _log.warn("Deleting legacy hash-patterns.dat file");
         }
         file.delete();
     }

    /**
     * Check if recorded prefixes show sequential/counter patterns indicating algorithmic generation.
     * This detects botnets that generate identities with incrementing counters.
     *
     * @return true if sequential pattern detected with high confidence
     */
    public boolean hasSequentialPattern() {
        if (_prefixStats.size() < 5) {
            return false;
        }

        // Convert prefixes to integers and sort - use simple loop instead of streams
        int[] values = new int[_prefixStats.size()];
        int idx = 0;
        for (String p : _prefixStats.keySet()) {
            if (!p.equals("0000")) {
                values[idx++] = Integer.parseInt(p.substring(0, Math.min(4, p.length())), 16);
            }
        }
        if (idx < 5) {
            return false;
        }

        // Simple insertion sort (more efficient for small arrays < 100 elements)
        for (int i = 1; i < idx; i++) {
            int key = values[i];
            int j = i - 1;
            while (j >= 0 && values[j] > key) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = key;
        }

        // Look for sequences where values are close together (within 0x1000 = 4096)
        int sequenceCount = 0;
        for (int i = 1; i < idx; i++) {
            int diff = values[i] - values[i - 1];
            if (diff > 0 && diff <= 0x1000) {
                sequenceCount++;
            }
        }

        // If 60% of consecutive values are within close range, likely sequential
        double ratio = (double) sequenceCount / (idx - 1);
        return ratio >= GAP_CLUSTERING_THRESHOLD / 100.0;
    }

    /**
     * Compute the gap between two hash values.
     * Returns the absolute difference as an integer.
     *
     * @param hash1 First hash string
     * @param hash2 Second hash string
     * @return Gap between hashes, or -1 on error
     */
    private long computeGap(String hash1, String hash2) {
        if (hash1 == null || hash2 == null) {
            return -1;
        }
        try {
            byte[] b1 = net.i2p.data.Base64.decode(hash1.substring(0, 8));
            byte[] b2 = net.i2p.data.Base64.decode(hash2.substring(0, 8));
            long v1 = 0, v2 = 0;
            for (int i = 0; i < Math.min(b1.length, 4); i++) {
                v1 = (v1 << 8) | (b1[i] & 0xFF);
            }
            for (int i = 0; i < Math.min(b2.length, 4); i++) {
                v2 = (v2 << 8) | (b2[i] & 0xFF);
            }
            return Math.abs(v1 - v2);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Detect sequential patterns by analyzing gaps between sorted hashes.
     * Returns true if gap clustering suggests algorithmic generation.
     *
     * @param hashes List of router hashes to analyze
     * @return true if sequential pattern detected
     */
    public boolean detectSequentialPatterns(java.util.List<String> hashes) {
        if (hashes == null || hashes.size() < MIN_SAMPLES) {
            return false;
        }

        // Parse and sort hashes by first 4 bytes
        java.util.List<Long> values = new java.util.ArrayList<>();
        for (String hash : hashes) {
            try {
                byte[] bytes = net.i2p.data.Base64.decode(hash.substring(0, 8));
                long v = 0;
                for (int i = 0; i < Math.min(bytes.length, 4); i++) {
                    v = (v << 8) | (bytes[i] & 0xFF);
                }
                values.add(v);
            } catch (Exception e) {
                // Skip invalid hashes
            }
        }

        if (values.size() < MIN_SAMPLES) {
            return false;
        }

        // Sort values
        values.sort(Long::compareTo);

        // Count gaps within clustering range
        int clusteredGaps = 0;
        int totalGaps = values.size() - 1;
        int runLength = 1;
        int maxRunLength = 1;

        for (int i = 1; i < values.size(); i++) {
            long gap = values.get(i) - values.get(i - 1);
            if (gap > 0 && gap <= GAP_RANGE) {
                clusteredGaps++;
                runLength++;
            } else {
                maxRunLength = Math.max(maxRunLength, runLength);
                runLength = 1;
            }
        }
        maxRunLength = Math.max(maxRunLength, runLength);

        // Check thresholds
        double clusteringRatio = (double) clusteredGaps / totalGaps;
        boolean hasClustering = clusteringRatio >= (GAP_CLUSTERING_THRESHOLD / 100.0);
        boolean hasLongRuns = maxRunLength >= MIN_RUN_LENGTH;

        return hasClustering || hasLongRuns;
    }

    /**
     * Check for cross-prefix sequential patterns (e.g., 0xAA01FF -> 0xAA0200).
     * Indicates scripted IDs crossing byte boundaries.
     *
     * @param hashes List of router hashes to analyze
     * @return true if cross-prefix patterns detected
     */
    public boolean detectCrossPrefixPatterns(java.util.List<String> hashes) {
        if (hashes == null || hashes.size() < MIN_SAMPLES) {
            return false;
        }

        // Group by 2-byte prefix and check transitions
        java.util.Map<String, java.util.List<Long>> prefixGroups = new java.util.HashMap<>();
        for (String hash : hashes) {
            try {
                String prefix = hash.substring(0, 4);
                byte[] bytes = net.i2p.data.Base64.decode(hash.substring(0, 8));
                long v = 0;
                for (int i = 0; i < Math.min(bytes.length, 4); i++) {
                    v = (v << 8) | (bytes[i] & 0xFF);
                }
                prefixGroups.computeIfAbsent(prefix, k -> new java.util.ArrayList<>()).add(v);
            } catch (Exception e) {
                // Skip invalid hashes
            }
        }

        // Check for sequential transitions between prefixes
        int crossPrefixSequential = 0;
        int totalTransitions = 0;

        for (java.util.List<Long> group : prefixGroups.values()) {
            if (group.size() < 2) continue;
            group.sort(Long::compareTo);
            for (int i = 1; i < group.size(); i++) {
                long gap = group.get(i) - group.get(i - 1);
                // Check for gaps near prefix boundary (e.g., 0x01FF -> 0x0200)
                if ((group.get(i - 1) & 0xFF) >= 0xF0 && gap >= 0x100 - 16 && gap <= 0x100 + 16) {
                    crossPrefixSequential++;
                }
                totalTransitions++;
            }
        }

        if (totalTransitions < MIN_SAMPLES) {
            return false;
        }

        return (double) crossPrefixSequential / totalTransitions >= 0.8;
    }

    /**
     * Quick check for scripted patterns - returns true if any pattern detected.
     *
     * @param hashes List of router hashes to check
     * @return true if scripted pattern detected
     */
    public boolean hasScriptedPattern(java.util.List<String> hashes) {
        return detectSequentialPatterns(hashes) || detectCrossPrefixPatterns(hashes);
    }

    /**
     * Get suspicious prefixes that are part of a sequential pattern.
     *
     * @return Set of suspicious prefix strings
     */
    public Set<String> getSuspiciousPrefixes() {
        Set<String> suspicious = new HashSet<>();

        for (Map.Entry<String, PrefixStats> entry : _prefixStats.entrySet()) {
            String prefix = entry.getKey();
            PrefixStats stats = entry.getValue();

            // High confidence threshold with minimum samples
            if (stats.getConfidence() >= BAN_THRESHOLD && stats.getTotalBans() >= MIN_SAMPLES) {
                suspicious.add(prefix);
            }
        }

        return suspicious;
    }

    /**
     * Initialize the NetDB scanner with configured frequency.
     * Called after constructor to ensure all dependencies are ready.
     *
     * @since 0.9.68
     */
    public void startScanner() {
        // Predictive banning check is deferred to the scan task itself
        // to ensure router is fully initialized

        long frequency = getHashScanFrequency();
        if (frequency <= 0) {
            if (_log.shouldWarn())
                _log.warn("NetDB hash scanner disabled (invalid frequency)");
            return;
        }

        _context.simpleTimer2().addPeriodicEvent(
            new NetDbScanTask(),
            SCAN_STARTUP_DELAY,
            frequency
        );

        if (_log.shouldInfo())
            _log.info("NetDB hash scanner starting in " + (SCAN_STARTUP_DELAY / 60000) + " minutes, interval: " + formatFrequency(frequency));
    }

    /**
     * Check if predictive banning should be enabled.
     * Called after router is fully initialized.
     *
     * @return true if predictive banning should be enabled
     */
    private boolean shouldEnablePredictiveBanning() {
        // Check minimum known routers threshold - handle null netDb gracefully
        try {
            Set<RouterInfo> routers = _context.netDb().getRouters();
            if (routers == null || routers.size() < MIN_KNOWN_ROUTERS) {
                if (_log.shouldWarn()) {
                    _log.warn("Not enough known routers to enable predictive banning: " +
                             (routers != null ? routers.size() : 0) + " < " + MIN_KNOWN_ROUTERS);
                }
                return false;
            }
        } catch (NullPointerException e) {
            // NetDB not ready yet, defer the check
            if (_log.shouldWarn()) {
                _log.warn("NetDB not yet available, deferring predictive banning check");
            }
            return false;
        }

        return hasSequentialPattern();
    }

    /**
     * Stop the NetDB scanner.
     *
     * @since 0.9.68
     */
    public void stopScanner() {
        // SimpleTimer2 periodic events are uncancellable via SimpleTimer.TimedEvent
        // The scanner will continue running but will check _scanInProgress flag
        if (_log.shouldInfo())
            _log.info("NetDB hash scanner stop requested (events will finish naturally)");
    }

    /**
     * Get configured scan frequency from properties.
     *
     * @return frequency in milliseconds
     */
    private long getHashScanFrequency() {
        String prop = _context.getProperty(PROP_HASH_SCAN_FREQUENCY, "1");

        try {
            if (prop.length() > 1 && (prop.endsWith("m") || prop.endsWith("M"))) {
                int minutes = Integer.parseInt(prop.substring(0, prop.length() - 1));
                return minutes * 60 * 1000L;
            } else {
                int hours = Integer.parseInt(prop);
                return hours * 60 * 60 * 1000L;
            }
        } catch (NumberFormatException e) {
            if (_log.shouldWarn())
                _log.warn("Invalid router.hashScan.frequency value: " + prop + ", using default", e);
            return DEFAULT_SCAN_FREQUENCY;
        }
    }

    /**
     * Format frequency for logging.
     */
    private String formatFrequency(long ms) {
        if (ms >= 60 * 60 * 1000L) {
            return (ms / (60 * 60 * 1000L)) + " hour(s)";
        } else {
            return (ms / (60 * 1000L)) + " minute(s)";
        }
    }

    /**
     * Scan netDB for routers matching suspicious patterns and auto-ban them.
     * Uses gap-based detection for pre-emptive banning.
     *
     * @since 0.9.68
     */
    public void scanNetDBForPatterns() {
        if (!_scanInProgress.compareAndSet(false, true)) {
            if (_log.shouldWarn())
                _log.warn("NetDB scan already in progress, skipping");
            return;
        }

        long startTime = _context.clock().now();
        int bannedCount = 0;
        int errorCount = 0;
        int skippedCount = 0;
        int gapDetectionCount = 0;

        try {
            Set<RouterInfo> routers = _context.netDb().getRouters();
            if (routers == null) {
                if (_log.shouldWarn())
                    _log.warn("NetDB returned null router set");
                return;
            }

            int totalRouters = routers.size();
            if (_log.shouldInfo())
                _log.info("Starting NetDB hash pattern scan of " + totalRouters + " routers");

            // Collect all router hashes for gap analysis
            java.util.List<String> allHashes = new java.util.ArrayList<>(totalRouters);
            for (RouterInfo router : routers) {
                if (router != null && router.getIdentity() != null) {
                    Hash identityHash = router.getIdentity().getHash();
                    if (identityHash != null) {
                        allHashes.add(identityHash.toBase64());
                    }
                }
            }

            // Run gap-based pattern detection
            boolean hasScriptedPattern = hasScriptedPattern(allHashes);
            if (hasScriptedPattern && _log.shouldWarn()) {
                _log.warn("Gap-based detection: Scripted router pattern detected in NetDB");
            }

            int processed = 0;
            for (RouterInfo router : routers) {
                processed++;
                try {
                    if (router == null || router.getIdentity() == null) {
                        skippedCount++;
                        continue;
                    }

                    Hash identityHash = router.getIdentity().getHash();
                    if (identityHash == null) {
                        skippedCount++;
                        continue;
                    }

                    String routerHash = identityHash.toBase64();

                    // Skip if already banned
                    if (_context.banlist().isBanlisted(identityHash)) {
                        skippedCount++;
                        continue;
                    }

                    // Check for gap-based suspicious patterns (pre-emptive)
                    if (isGapSuspicious(routerHash, allHashes)) {
                        if (banRouter(router, identityHash, routerHash, true)) {
                            bannedCount++;
                            gapDetectionCount++;
                        }
                    }
                    // Fallback to traditional pattern detection
                    else if (isPatternSuspicious(routerHash)) {
                        if (banRouter(router, identityHash, routerHash, false)) {
                            bannedCount++;
                        }
                    }

                    // Progress logging every 500 routers
                    if (_log.shouldDebug() && processed % 500 == 0) {
                        _log.debug("HashPatternDetector scan progress: " + processed + "/" + totalRouters);
                    }

                } catch (Exception e) {
                    errorCount++;
                    if (_log.shouldError())
                        _log.error("Error scanning router #" + processed + " in netDB", e);
                }
            }

            long duration = _context.clock().now() - startTime;
            if (_log.shouldInfo() || bannedCount > 0 || errorCount > 0 || gapDetectionCount > 0) {
                _log.info("HashPatternDetector: NetDB scan complete in " + (duration / 1000) + "s - " +
                          "banned: " + bannedCount + " (gap-based: " + gapDetectionCount + "), " +
                          "errors: " + errorCount + ", skipped: " + skippedCount);
            }

        } finally {
            _scanInProgress.set(false);
        }
    }

    /**
     * Check if a router hash is suspicious based on gap analysis.
     *
     * @param routerHash Base64 router hash
     * @param allHashes List of all hashes in NetDB for context
     * @return true if gap pattern suggests scripting
     */
    private boolean isGapSuspicious(String routerHash, java.util.List<String> allHashes) {
        if (allHashes == null || allHashes.size() < MIN_SAMPLES) {
            return false;
        }

        // Group hashes by 2-byte prefix
        String prefix = routerHash.substring(0, 4);
        java.util.List<String> prefixHashes = new java.util.ArrayList<>();
        for (String hash : allHashes) {
            if (hash.startsWith(prefix)) {
                prefixHashes.add(hash);
            }
        }

        // Need minimum samples in this prefix for analysis
        if (prefixHashes.size() < MIN_SAMPLES) {
            return false;
        }

        // Check for sequential patterns within this prefix group
        return detectSequentialPatterns(prefixHashes) || detectCrossPrefixPatterns(prefixHashes);
    }

    /**
     * Check if a router hash matches suspicious patterns.
     *
     * @param routerHash Base64 router hash
     * @return true if pattern is suspicious
     */
    private boolean isPatternSuspicious(String routerHash) {
        String prefix = getPrefix(routerHash);
        PrefixStats stats = _prefixStats.get(prefix);

        if (stats == null) {
            return false;
        }

        // High confidence threshold with minimum samples
        return stats.getConfidence() >= BAN_THRESHOLD && stats.getTotalBans() >= MIN_SAMPLES;
    }

    /**
     * Ban a router for pre-emptive detection.
     *
     * @param router RouterInfo to ban
     * @param identityHash Router identity hash
     * @param routerHash Base64 router hash string
     * @param isGapBased True if detected via gap analysis (pre-emptive)
     * @return true if ban was applied
     */
    private boolean banRouter(RouterInfo router, Hash identityHash, String routerHash, boolean isGapBased) {
        try {
            long expireOn = _context.clock().now() + BAN_DURATION;
            String reason = isGapBased
                ? " <b>➜</b> HashPatternDetector (Heuristic)"
                : " <b>➜</b> HashPatternDetector (NetDb)";

            boolean wasBanned = _context.banlist().banlistRouter(
                identityHash, reason, null, null, expireOn
            );

            if (wasBanned || _context.banlist().isBanlisted(identityHash)) {
                if (_log.shouldWarn())
                    _log.warn("Pre-emptively banned router: " + routerHash.substring(0, 12) + "..." +
                             (isGapBased ? " (gap-based detection)" : ""));
                // Extract IP from router addresses
                String ip = getRouterIP(router);
                // Log to sessionbans.txt via BanLogger
                String banReason = isGapBased
                    ? "HashPatternDetector (Heuristic)"
                    : "HashPatternDetector (NetDb)";
                _banLogger.logBan(identityHash, ip, banReason, BAN_DURATION);
                return true;
            }

            return false;
        } catch (Exception e) {
            if (_log.shouldError())
                _log.error("Failed to ban router: " + routerHash, e);
            return false;
        }
    }

    /**
     * Extract IP address from RouterInfo if available.
     */
    private String getRouterIP(RouterInfo router) {
        if (router == null) { return ""; }
        try {
            for (RouterAddress addr : router.getAddresses()) {
                if (addr != null && addr.getHost() != null) {
                    return addr.getHost();
                }
            }
        } catch (Exception e) {
            // Ignore extraction errors
        }
        return "";
    }

    /**
      * Timed event for periodic NetDB scanning with gap-based pre-emptive detection.
      *
      * @since 0.9.68
      */
     private class NetDbScanTask implements SimpleTimer.TimedEvent {
         public void timeReached() {
             // Enable predictive banning on first scan (gap-based detection doesn't need warmup)
             if (!_predictiveBanningActive.get()) {
                 _predictiveBanningActive.set(true);
                 _predictiveBanEnableTime = _context.clock().now();
                 if (_log.shouldWarn()) {
                     _log.warn("Pre-emptive gap-based router detection ENABLED" +
                              "\n* Algorithmic router patterns will be banned before causing harm");
                 }
             }

             scanNetDBForPatterns();
         }
     }

    /**
     * Statistics for a particular hash prefix.
     */
    private static class PrefixStats implements Serializable {
        private static final long serialVersionUID = 1L;

        final String prefix;
        int totalBans;
        int suspiciousBans; // Bans for algorithmic/scripted behavior
        final Map<String, Integer> reasonCounts = new HashMap<>();

        PrefixStats(String prefix) {
            this.prefix = prefix;
        }

        void recordBan(String reason) {
            totalBans++;

            // Track specific suspicious patterns
            if (reason != null && (
                reason.contains("Unsolicited") ||
                reason.contains("Hostile") ||
                reason.contains("Excessive") ||
                reason.contains("Invalid") ||
                reason.contains("No version") ||
                reason.contains("Corrupt") ||
                reason.contains("Spoofed")
            )) {
                suspiciousBans++;
            }

            reasonCounts.merge(reason != null ? reason : "unknown", 1, Integer::sum);
        }

        double getConfidence() {
            if (totalBans == 0) {
                return 0.0;
            }
            return (double) suspiciousBans / totalBans;
        }

        int getTotalBans() {
            return totalBans;
        }
    }
}