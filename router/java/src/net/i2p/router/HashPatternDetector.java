package net.i2p.router;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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

    // Configuration
    private static final int PREFIX_LENGTH = 2; // First 2 bytes (4 hex chars)
    private static final double BAN_THRESHOLD = 0.85; // 85% confidence required
    private static final int MIN_SAMPLES = 10; // Minimum bans before prediction
    private static final String PATTERN_FILE = "hash-patterns.dat";
    private static final long PREDICTIVE_BAN_DURATION = 24 * 60 * 60 * 1000L; // 24 hours

    // NetDB Scanner Configuration
    private static final String PROP_HASH_SCAN_FREQUENCY = "router.hashScan.frequency";
    private static final long DEFAULT_SCAN_FREQUENCY = 60 * 60 * 1000L; // 1 hour default
    private static final long SCAN_STARTUP_DELAY = 5 * 60 * 1000L; // 5 minutes - wait for netdb init
    private static final long BAN_DURATION = 24 * 60 * 60 * 1000L; // 24 hours for auto-bans

    // Scanner state
    private final AtomicBoolean _scanInProgress = new AtomicBoolean(false);

    public HashPatternDetector(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(HashPatternDetector.class);
        loadPatterns();
        loadHistoricalBans();

        // Check if bot patterns detected after loading historical data
        if (hasSequentialPattern()) {
            Set<String> suspicious = getSuspiciousPrefixes();
            if (_log.shouldWarn()) {
                _log.warn("WARNING! Algorithmic router identity generation pattern identified!" +
                         "\n* Automatic predictive banning is now ENABLED" +
                         "\n* Suspicious prefixes: " + suspicious +
                         "\n* Future routers matching these patterns will be banned preventively");
            }
        }
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
                " <b>➜</b> Predictive ban: " + prefix,
                null, null,
                context.clock().now() + PREDICTIVE_BAN_DURATION);

            _predictivelyBanned.add(hashStr);

            if (_log.shouldWarn()) {
                _log.warn("Predictively banning router [" + hash.toBase64().substring(0, 6) +
                         "] with prefix " + prefix + " (confidence: " +
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
        if (hash == null) {
            return;
        }

        String hashStr = hash.toBase64();
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

        // Periodically save patterns
        if (stats.getTotalBans() % 10 == 0) {
            savePatterns();
        }
    }

    /**
     * Get the hex prefix of a base64 hash.
     */
    private String getPrefix(String base64Hash) {
        try {
            // Decode first few bytes of base64 to get hex prefix
            byte[] bytes = net.i2p.data.Base64.decode(base64Hash.substring(0, 8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(bytes.length, PREFIX_LENGTH); i++) {
                hex.append(String.format("%02x", bytes[i] & 0xFF));
            }
            return hex.toString();
        } catch (Exception e) {
            return "0000";
        }
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
    private void savePatterns() {
        File file = new File(_context.getRouterDir(), PATTERN_FILE);
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            oos.writeObject(new HashMap<>(_prefixStats));
        } catch (IOException e) {
            if (_log.shouldWarn()) {
                _log.warn("Failed to save hash patterns", e);
            }
        }
    }

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
     */
    @SuppressWarnings("unchecked")
    private void loadPatterns() {
        File file = new File(_context.getRouterDir(), PATTERN_FILE);
        if (!file.exists()) {
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            Map<String, PrefixStats> loaded = (Map<String, PrefixStats>) ois.readObject();
            _prefixStats.putAll(loaded);
            if (_log.shouldInfo()) {
                _log.info("Loaded " + loaded.size() + " hash patterns from disk");
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Failed to load hash patterns", e);
            }
        }
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

        // Convert prefixes to integers and sort
        int[] values = _prefixStats.keySet().stream()
            .filter(p -> !p.equals("0000"))
            .mapToInt(p -> Integer.parseInt(p.substring(0, Math.min(4, p.length())), 16))
            .sorted()
            .toArray();

        if (values.length < 5) {
            return false;
        }

        // Look for sequences where values are close together (within 0x1000 = 4096)
        int sequenceCount = 0;
        for (int i = 1; i < values.length; i++) {
            int diff = values[i] - values[i-1];
            if (diff > 0 && diff <= 0x1000) {
                sequenceCount++;
            }
        }

        // If 70% of consecutive values are within close range, likely sequential
        double ratio = (double) sequenceCount / (values.length - 1);
        return ratio >= 0.70;
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

                    // Analyze for suspicious patterns
                    if (isPatternSuspicious(routerHash)) {
                        if (banRouter(router, identityHash, routerHash)) {
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
            if (_log.shouldInfo() || bannedCount > 0 || errorCount > 0) {
                _log.info("HashPatternDetector: NetDB scan complete in " + (duration / 1000) + "s - " +
                          "banned: " + bannedCount + ", errors: " + errorCount + ", skipped: " + skippedCount);
            }

        } finally {
            _scanInProgress.set(false);
        }
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
     * Ban a router for 24 hours.
     *
     * @param router RouterInfo to ban
     * @param identityHash Router identity hash
     * @param routerHash Base64 router hash string
     * @return true if ban was applied
     */
    private boolean banRouter(RouterInfo router, Hash identityHash, String routerHash) {
        try {
            long expireOn = _context.clock().now() + BAN_DURATION;
            String reason = " <b>➜</b> HashPatternDetector netDB";

            boolean wasBanned = _context.banlist().banlistRouter(
                identityHash, reason, null, null, expireOn
            );

            if (wasBanned || _context.banlist().isBanlisted(identityHash)) {
                if (_log.shouldWarn())
                    _log.warn("Auto-banned router: " + routerHash.substring(0, 12) + "...");
                // Extract IP from router addresses
                String ip = getRouterIP(router);
                // Log to sessionbans.txt via BanLogger
                String banReason = "HashPatternDetector netDB";
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
     * Timed event for periodic NetDB scanning.
     *
     * @since 0.9.68
     */
    private class NetDbScanTask implements SimpleTimer.TimedEvent {
        public void timeReached() {
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