package net.i2p.router;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import java.util.Locale;

import java.util.TimeZone;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;

import net.i2p.util.Log;

/**
 * Dedicated logger for all ban events.
 * Writes to sessionbans.txt in the router's data directory.
 * <p>
 * This allows analysis of ban patterns without parsing router.log.
 *
 * @since 0.9.68
 */
public class BanLogger {
    private RouterContext _context;
    private Log _log;
    private File _logFile;
    private final ThreadLocal<DateFormat> _dateFormat = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return fmt;
        }
    };
    private AtomicInteger _banCount;
    private long _startTime;
    private final Object _writeLock = new Object();
    private static BanLogger self;

    private static final String LOG_DIR = "sessionbans";
    private static final String LOG_FILENAME = "sessionbans.txt";
    private static final String ARCHIVE_PREFIX = "sessionbans-";
    private static final String PROP_MAX_ARCHIVES = "router.banlogger.maxArchives";
    private static final Pattern PIPE_SPLIT = Pattern.compile("\\s*\\|\\s*");
    private static final int DEFAULT_MAX_ARCHIVES = 10;
    private static volatile PrintWriter writer;
    private static volatile boolean initialized = false;
    private static volatile boolean globalArchiveDone = false;
    private static volatile boolean headerWritten = false;

    /** No-arg constructor for deferred initialization. */
    public BanLogger() {
        _banCount = new AtomicInteger();
    }

    /**
     * Constructor with context for immediate initialization.
     *
     * @param context the router context
     */
    public BanLogger(RouterContext context) {
        _banCount = new AtomicInteger();
        initialize(context);
    }

    /**
     * Initialize the logger. Safe to call multiple times.
     *
     * @param context the router context
     */
    public void initialize(RouterContext context) {
        if (context == null) {return;}
        if (initialized) {return;}
        synchronized (_writeLock) {
            if (initialized) {return;}
            _context = context;
            _log = context.logManager().getLog(BanLogger.class);
            File dataDir = context.getRouterDir();
            File logDir = new File(dataDir, LOG_DIR);
            if (!logDir.exists() && !logDir.mkdirs()) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Failed to create ban log directory: " + logDir);
                return;
            }
            _logFile = new File(logDir, LOG_FILENAME);
            _startTime = System.currentTimeMillis();
            if (!globalArchiveDone) {
                archiveExisting();
                globalArchiveDone = true;
            }
            openWriter();
            if (!headerWritten) {
                logStartTime();
                headerWritten = true;
            }
            initialized = true;
            self = this;
        }
    }

    /**
     * Check if an IP already has an active ban in sessionbans.txt.
     * @return whether active ban is present
     */
    private boolean hasActiveBan(String ip) {
        if (_logFile == null || !_logFile.exists() || ip == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(_logFile)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] parts = PIPE_SPLIT.split(line);
                if (parts.length >= 5) {
                    String hash = parts[1].trim();
                    String loggedIP = parts[2].trim();
                    String durationStr = parts[4].trim();
                    if ("UNKNOWN".equals(hash) && ip.equals(loggedIP)) {
                        long expires = parseDuration(durationStr, now);
                        if (expires > now) {
                            return true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed to check active ban for " + ip, e);
        }
        return false;
    }

    /**
     * Check if the logger is initialized.
     *
     * @return true if initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * The singleton instance of BanLogger.
     * May return null if not initialized yet.
     *
     * @return the singleton instance, or null if not initialized
     */
    public static BanLogger getInstance() {
        return initialized ? self : null;
    }

    /**
     * Archive an existing log file from a previous session.
     * Called during initialization before opening a new writer.
     */
    private void archiveExisting() {
        if (!_logFile.exists()) {return;}
        long mtime = _logFile.lastModified();
        if (mtime <= 0) {mtime = System.currentTimeMillis();}
        String timestamp = _dateFormat.get().format(new Date(mtime));
        String safeTimestamp = timestamp.replace(':', '-').replace('T', '_');
        String archiveName = ARCHIVE_PREFIX + safeTimestamp + ".txt";
        File archiveFile = new File(_logFile.getParentFile(), archiveName);
        if (_logFile.renameTo(archiveFile)) {
            cleanupOldArchives();
            if (_log != null && _log.shouldLog(Log.INFO))
                _log.info("Archived previous ban log: " + archiveFile.getName());
        } else {
            if (_log != null && _log.shouldLog(Log.WARN))
                _log.warn("Failed to archive previous ban log");
        }
    }

    /**
     * Archive the current log when closing.
     * Uses the router start time to timestamp the archive.
     */
    public void archiveIfNeeded() {
        if (!_logFile.exists()) {return;}
        if (_banCount.get() <= 0) {return;}
        String timestamp = _dateFormat.get().format(new Date(_startTime));
        String safeTimestamp = timestamp.replace(':', '-').replace('T', '_');
        String archiveName = ARCHIVE_PREFIX + safeTimestamp + ".txt";
        File archiveFile = new File(_logFile.getParentFile(), archiveName);
        if (_logFile.renameTo(archiveFile)) {
            _banCount.set(0);
            cleanupOldArchives();
            if (_log != null && _log.shouldLog(Log.INFO))
                _log.info("Archived ban log: " + archiveFile.getName());
        } else {
            if (_log != null && _log.shouldLog(Log.WARN))
                _log.warn("Failed to archive ban log");
        }
    }

    /**
     * Clean up old archives, keeping only the configured maximum.
     */
    private void cleanupOldArchives() {
        if (_context == null || _logFile == null) {return;}
        File logDir = _logFile.getParentFile();
        if (logDir == null) {return;}

        int maxArchives = DEFAULT_MAX_ARCHIVES;
        try {
            String prop = _context.getProperty(PROP_MAX_ARCHIVES);
            if (prop != null) {
                maxArchives = Integer.parseInt(prop);
                if (maxArchives < 0) {maxArchives = DEFAULT_MAX_ARCHIVES;}
            }
        } catch (NumberFormatException e) {
            maxArchives = DEFAULT_MAX_ARCHIVES;
        }

        File[] archives = logDir.listFiles((dir, name) -> name.startsWith(ARCHIVE_PREFIX) && name.endsWith(".txt"));
        if (archives == null || archives.length <= maxArchives) {return;}

        Arrays.sort(archives, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        StringBuilder sb = new StringBuilder();
        for (int i = maxArchives; i < archives.length; i++) {
            if (!archives[i].delete()) {
                if (_log != null && _log.shouldLog(Log.WARN)) {
                    sb.setLength(0);
                    _log.warn(sb.append("Failed to delete old ban log archive: ").append(archives[i].getName()).toString());
                }
            } else {
                if (_log != null && _log.shouldLog(Log.DEBUG)) {
                    sb.setLength(0);
                    _log.debug(sb.append("Deleted old ban log archive: ").append(archives[i].getName()).toString());
                }
            }
        }
    }

    /**
     * Log the router start time.
     */
    private void logStartTime() {
        if (writer == null) {return;}
        writer.println();
        writer.println("############################################################");
        writer.println("# Router started: " + _dateFormat.get().format(new Date(_startTime)));
        writer.println("############################################################");
        writer.println();
        writer.println("# Ban event log");
        writer.println("# Format: TIMESTAMP | HASH | IP:PORT | REASON | DURATION | CAPS | VERSION | COUNTRY | HOST");
        writer.println("# TIMESTAMP: ISO 8601 UTC");
        writer.println("# HASH: Router hash (base64) or UNKNOWN");
        writer.println("# IP:PORT: IP address and port or UNKNOWN");
        writer.println("# REASON: Reason for ban");
        writer.println("# DURATION: Duration (e.g., 8h, 24h, FOREVER)");
        writer.println("# CAPS: Router capabilities (optional, may be empty)");
        writer.println("# VERSION: Router version string (optional, may be empty)");
        writer.println("# COUNTRY: GeoIP country code (optional, may be empty)");
        writer.println("# HOST: Hostname or ASN org name (optional, may be empty)");
        writer.println();
    }

    /**
     * Initialize the log file writer.
     */
    private void openWriter() {
        synchronized (_writeLock) {
            try {
                writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(_logFile, true), StandardCharsets.UTF_8), true);
            } catch (IOException e) {
                if (_log != null && _log.shouldLog(Log.WARN))
                    _log.warn("Failed to open ban log file: " + _logFile, e);
            }
        }
    }

    /**
     * Log a ban by hash with IP address.
     *
     * @param hash Router hash (may be null)
     * @param ip IP address with port (format: "1.2.3.4:5678" or "ipv6:port")
     * @param reason Reason for the ban
     * @param durationMs Ban duration in milliseconds, or 0 for permanent
     */
    public void logBan(Hash hash, String ip, String reason, long durationMs) {
        String hashStr = hash != null ? hash.toBase64() : "UNKNOWN";
        String durationStr = formatDuration(durationMs);
        String caps = hash != null ? getCaps(hash) : "";
        String version = hash != null ? getVersion(hash) : "";
        writeLog(hashStr, ip, reason, durationStr, caps, version, getCountry(ip), getHost(ip));
        if (hash != null && (caps.isEmpty() || version.isEmpty() || "UNKNOWN".equals(ip)) &&
            _context != null && !_context.banlist().isBanlisted(hash)) {
            fetchRouterInfo(hash, reason, durationMs);
        }
    }

    /**
     * Log a ban with RouterInfo for direct caps extraction.
     * Prefer this when the RouterInfo is available at the callsite,
     * to avoid a NetDB lookup that may fail if the RI was never stored.
     *
     * @param hash Router hash (may be null)
     * @param ip IP address with port (format: "1.2.3.4:5678" or "ipv6:port")
     * @param reason Reason for the ban
     * @param durationMs Ban duration in milliseconds, or 0 for permanent
     * @param ri RouterInfo to extract capabilities from (may be null)
     * @since 0.9.70+
     */
    public void logBan(Hash hash, String ip, String reason, long durationMs, RouterInfo ri) {
        String hashStr = hash != null ? hash.toBase64() : "UNKNOWN";
        String durationStr = formatDuration(durationMs);
        String caps = "";
        String version = "";
        if (ri != null) {
            caps = ri.getCapabilities();
            if (caps == null) caps = "";
            version = ri.getVersion();
            if (version == null) version = "";
        } else if (hash != null) {
            caps = getCaps(hash);
            version = getVersion(hash);
        }
        writeLog(hashStr, ip, reason, durationStr, caps, version, getCountry(ip), getHost(ip));
    }

/**
     * Log a ban by IP only (ignores hash).
     *
     * @param ip IP address with port (format: "1.2.3.4:5678" or "ipv6:port")
     * @param reason Reason for the ban
     * @param durationMs Ban duration in milliseconds, or 0 for permanent
     */
    public void logBan(String ip, String reason, long durationMs) {
        logBan(null, ip, reason, durationMs);
    }

    /**
     * Log a ban by IP only (no router hash available).
     * Uses "UNKNOWN" for the hash column.
     *
     * @param ip IP address with port (format: "1.2.3.4:5678" or "ipv6:port")
     * @param reason Reason for the ban
     * @param durationMs Ban duration in milliseconds, or 0 for permanent
     */
    public void logBanIPOnly(String ip, String reason, long durationMs) {
        String durationStr = formatDuration(durationMs);
        writeLog("UNKNOWN", ip, reason, durationStr, "", "", getCountry(ip), getHost(ip));
    }

    /**
     * Log a permanent ban (forever).
     *
     * @param hash Router hash (may be null)
     * @param ip IP address with port (format: "1.2.3.4:5678" or "ipv6:port")
     * @param reason Reason for the ban
     */
    public void logBanForever(Hash hash, String ip, String reason) {
        String hashStr = hash != null ? hash.toBase64() : "UNKNOWN";
        String caps = hash != null ? getCaps(hash) : "";
        String version = hash != null ? getVersion(hash) : "";
        writeLog(hashStr, ip, reason, "FOREVER", caps, version, getCountry(ip), getHost(ip));
        if (hash != null && (caps.isEmpty() || version.isEmpty() || "UNKNOWN".equals(ip)) &&
            _context != null && !_context.banlist().isBanlisted(hash)) {
            fetchRouterInfo(hash, reason, 0L);
        }
    }

    /**
     * Log a permanent ban with RouterInfo for direct caps extraction.
     *
     * @param hash Router hash (may be null)
     * @param ip IP address with port (format: "1.2.3.4:5678" or "ipv6:port")
     * @param reason Reason for the ban
     * @param ri RouterInfo to extract capabilities from (may be null)
     * @since 0.9.70+
     */
    public void logBanForever(Hash hash, String ip, String reason, RouterInfo ri) {
        String hashStr = hash != null ? hash.toBase64() : "UNKNOWN";
        String caps = "";
        String version = "";
        if (ri != null) {
            caps = ri.getCapabilities();
            if (caps == null) caps = "";
            version = ri.getVersion();
            if (version == null) version = "";
        } else if (hash != null) {
            caps = getCaps(hash);
            version = getVersion(hash);
        }
        writeLog(hashStr, ip, reason, "FOREVER", caps, version, getCountry(ip), getHost(ip));
    }

    /**
     * Log a permanent ban by IP only.
     *
     * @param ip IP address with port (format: "1.2.3.4:5678" or "ipv6:port")
     * @param reason Reason for the ban
     */
    public void logBanForever(String ip, String reason) {
        logBanForever(null, ip, reason);
    }

    /**
     * Log a ban by hash with RouterContext (IP will be looked up from banlist).
     *
     * @param hash Router hash (may be null)
     * @param context Router context for IP lookup
     * @param reason Reason for the ban
     * @param durationMs Ban duration in milliseconds, or 0 for permanent
     */
    public void logBan(Hash hash, RouterContext context, String reason, long durationMs) {
        String ip = getIPFromContext(hash, context);
        logBan(hash, ip, reason, durationMs);
    }

    /**
     * Log a permanent ban with RouterContext.
     *
     * @param hash Router hash (may be null)
     * @param context Router context for IP lookup
     * @param reason Reason for the ban
     */
    public void logBanForever(Hash hash, RouterContext context, String reason) {
        String ip = getIPFromContext(hash, context);
        logBanForever(hash, ip, reason);
    }

    /**
     * Capabilities string from RouterInfo for the given hash.
     * @return caps string or empty string
     */
    private String getCaps(Hash hash) {
        if (hash == null || _context == null) return "";
        try {
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(hash);
            if (ri != null) {
                String caps = ri.getCapabilities();
                return caps != null ? caps : "";
            }
        } catch (Exception e) { /* ignore */ }
        return "";
    }

    /**
     * Version string from RouterInfo for the given hash.
     * @return version string or empty string
     */
    private String getVersion(Hash hash) {
        if (hash == null || _context == null) return "";
        try {
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(hash);
            if (ri != null) {
                String version = ri.getVersion();
                return version != null ? version : "";
            }
        } catch (Exception e) { /* ignore */ }
        return "";
    }

    /**
     * Extract the bare IP (strip port) from an "ip:port" or "[ipv6]:port" string.
     */
    private static String stripPort(String ipPort) {
        if (ipPort == null || ipPort.isEmpty() || "UNKNOWN".equals(ipPort))
            return null;
        if (ipPort.startsWith("[")) {
            int end = ipPort.indexOf(']');
            if (end > 0)
                return ipPort.substring(1, end);
        } else {
            int colon = ipPort.lastIndexOf(':');
            if (colon > 0)
                return ipPort.substring(0, colon);
        }
        return ipPort;
    }

    /**
     * Country code from IP using GeoIP.
     * @return country code or empty string
     */
    private String getCountry(String ipPort) {
        if (_context == null)
            return "";
        String ip = stripPort(ipPort);
        if (ip == null)
            return "";
        try {
            String c = _context.commSystem().getCountry(ip);
            return c != null ? c : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Hostname or ASN org name from IP using the local ASN database.
     * Non-blocking — uses cached results or fast local MMDB lookup.
     * @return hostname/ASN or empty string
     */
    private String getHost(String ipPort) {
        if (_context == null)
            return "";
        String ip = stripPort(ipPort);
        if (ip == null)
            return "";
        try {
            String h = _context.commSystem().getLocalHostName(ip);
            return (h != null && !h.equals(ip)) ? h : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * IP address from banlist for the given hash.
     * @return the IP address from context
     */
    private String getIPFromContext(Hash hash, RouterContext context) {
        if (hash == null) {return "UNKNOWN";}
        try {
            RouterInfo ri = context.netDb().lookupRouterInfoLocally(hash);
            if (ri != null) {
                String ipPort = getIPFromRouterInfo(ri);
                if (!ipPort.isEmpty()) {
                    return ipPort;
                }
            }
        } catch (Exception e) {
            // Ignore lookup errors
        }
        return "UNKNOWN";
    }

    /**
     * Schedule an async network lookup for a RouterInfo that was not locally
     * cached when the ban was logged. When the lookup completes, write a second
     * line to sessionbans.txt with the actual IP, caps, and version, then evict
     * the RouterInfo from the netdb (we only fetched it for logging).
     * <p>
     * This is a best-effort operation: silently keep the basic entry on failure.
     *
     * @param hash Router hash (non-null)
     * @param reason reason from the ban
     * @param durationMs ban duration
     * @since 0.9.70+
     */
    private void fetchRouterInfo(Hash hash, String reason, long durationMs) {
        if (_context == null || hash == null) return;
        try {
            final KademliaNetworkDatabaseFacade facade = (KademliaNetworkDatabaseFacade) _context.netDb();
            facade.lookupRouterInfoRemote(hash,
                new JobImpl(_context) {
                    @Override
                    public void runJob() {
                        RouterInfo ri = _context.netDb().lookupRouterInfoLocally(hash);
                        if (ri == null) return;
                        String caps = ri.getCapabilities();
                        if (caps == null) caps = "";
                        String ver = ri.getVersion();
                        if (ver == null) ver = "";
                        String ip = getIPFromRouterInfo(ri);
                        if (ip.isEmpty()) ip = "UNKNOWN";
                        String dur = formatDuration(durationMs);
                        String ts = _dateFormat.get().format(new Date());
                        String entry = String.format("%s | %s | %s | %s | %s | %s | %s | %s | %s",
                            ts, hash.toBase64(), ip, reason, dur,
                            caps, ver, getCountry(ip), getHost(ip));
                        synchronized (_writeLock) {
                            if (writer != null) {
                                writer.println(entry);
                                writer.flush();
                                _banCount.incrementAndGet();
                            }
                        }
                        facade.removeRouterInfo(hash);
                    }
                    @Override
                    public String getName() { return "BanLogger lookup"; }
                },
                new JobImpl(_context) {
                    @Override
                    public void runJob() { /* lookup failed */ }
                    @Override
                    public String getName() { return "BanLogger lookup timeout"; }
                },
                12L * 1000
            );
        } catch (ClassCastException e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Cannot schedule ban log lookup: netDb is not a KademliaNetworkDatabaseFacade", e);
        }
    }

    /**
     * Extract IP address and port from RouterInfo.
     * @return the i p from router info
     */
    private String getIPFromRouterInfo(RouterInfo router) {
        if (router == null) { return ""; }
        try {
            for (RouterAddress addr : router.getAddresses()) {
                if (addr != null && addr.getHost() != null) {
                    String ip = addr.getHost();
                    int port = addr.getPort();
                    if (port > 0) {
                        // Check if it's IPv6 address
                        if (ip.contains(":") && !ip.startsWith("[")) {
                            // IPv6 address needs brackets
                            return '[' + ip + "]:" + port;
                        } else {
                            return ip + ':' + port;
                        }
                    } else {
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore extraction errors
        }
        return "";
    }

    /**
     * Internal method to write the log entry.
     * Skips logging if this IP already has an active ban in sessionbans.txt,
     * or if this hash is already banlisted.
     */
    private void writeLog(String hashStr, String ip, String reason, String durationStr,
                          String caps, String version, String country, String host) {
        // Strip HTML formatting from reason for plain text log file
        if (reason != null) {
            reason = reason.replace("<b>➜</b>", "").replace("  ", " ").trim();
        }
        // Skip if this hash is already banlisted
        if (_context != null && !"UNKNOWN".equals(hashStr)) {
            try {
                Hash hash = new Hash();
                hash.fromBase64(hashStr);
                if (_context.banlist().isBanlisted(hash)) {
                    return;
                }
            } catch (Exception e) {
                // Ignore hash parsing errors
            }
        }
        // Skip if this IP already has an active ban in the file
        if ("UNKNOWN".equals(hashStr) && hasActiveBan(ip)) {
            return;
        }

        String timestamp = _dateFormat.get().format(new Date());
        String capsStr = (caps != null && !caps.isEmpty()) ? caps : "";
        String verStr = (version != null && !version.isEmpty()) ? version : "";
        String countryStr = (country != null && !country.isEmpty()) ? country : "";
        String hostStr = (host != null && !host.isEmpty()) ? host : "";
        String entry = String.format("%s | %s | %s | %s | %s | %s | %s | %s | %s",
                                     timestamp, hashStr, ip, reason, durationStr,
                                     capsStr, verStr, countryStr, hostStr);

        synchronized (_writeLock) {
            if (writer != null) {
                writer.println(entry);
                writer.flush();
                _banCount.incrementAndGet();
            }
        }

        if (_log != null && _log.shouldLog(Log.DEBUG))
            _log.debug("Ban logged: " + entry);
    }

    /**
     * Format duration in milliseconds to human-readable string.
     */
    private static String formatDuration(long durationMs) {
        if (durationMs <= 0) {
            return "FOREVER";
        }
        if (durationMs < 60 * 1000) {
            return durationMs + "ms";
        } else if (durationMs < 60 * 60 * 1000) {
            return (durationMs / (60 * 1000)) + "m";
        } else if (durationMs < 24 * 60 * 60 * 1000) {
            return (durationMs / (60 * 60 * 1000)) + "h";
        } else {
            return (durationMs / (24 * 60 * 60 * 1000)) + "d";
        }
    }

    /**
     * Parse duration string to expiration timestamp.
     */
    private static long parseDuration(String durationStr, long now) {
        if (durationStr == null || durationStr.isEmpty()) {
            return now;
        }
        durationStr = durationStr.trim().toUpperCase();
        if ("FOREVER".equals(durationStr)) {
            return Long.MAX_VALUE;
        }
        long multiplier = 1;
        if (durationStr.endsWith("D")) {
            multiplier = 24L * 60 * 60 * 1000;
            durationStr = durationStr.substring(0, durationStr.length() - 1);
        } else if (durationStr.endsWith("H")) {
            multiplier = 60L * 60 * 1000;
            durationStr = durationStr.substring(0, durationStr.length() - 1);
        } else if (durationStr.endsWith("M")) {
            multiplier = 60L * 1000;
            durationStr = durationStr.substring(0, durationStr.length() - 1);
        } else if (durationStr.endsWith("MS")) {
            durationStr = durationStr.substring(0, durationStr.length() - 2);
        }
        try {
            return now + (Long.parseLong(durationStr) * multiplier);
        } catch (NumberFormatException e) {
            return now;
        }
    }

    /**
     * The log file path.
     *
     * @return the log file
     */
    public File getLogFile() {
        return _logFile;
    }

    /**
     * Shutdown the ban logger — close the writer and release resources.
     * Safe to call multiple times.
     * @since 0.9.70+
     */
    public static void shutdown() {
        if (writer == null && !initialized)
            return;
        PrintWriter writerToClose;
        synchronized (self != null ? self._writeLock : new Object()) {
            writerToClose = writer;
            writer = null;
        }
        if (writerToClose != null) {
            writerToClose.close();
        }
        initialized = false;
        self = null;
    }

    /**
     * Flush the log writer.
     */
    public void flush() {
        synchronized (_writeLock) {
            if (writer != null) {
                writer.flush();
            }
        }
    }

    /**
     * Close the log writer and archive if there are entries.
     */
    public void close() {
        synchronized (_writeLock) {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        }
        archiveIfNeeded();
    }
}
