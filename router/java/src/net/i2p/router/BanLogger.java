package net.i2p.router;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import net.i2p.data.Hash;
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
    private SimpleDateFormat _dateFormat;
    private volatile int _banCount;
    private long _startTime;
    private final Object _writeLock = new Object();
    private HashPatternDetector _patternDetector;

    private static final String LOG_DIR = "sessionbans";
    private static final String LOG_FILENAME = "sessionbans.txt";
    private static final String ARCHIVE_PREFIX = "sessionbans-";
    private static final String PROP_MAX_ARCHIVES = "router.banlogger.maxArchives";
    private static final int DEFAULT_MAX_ARCHIVES = 10;
    private static volatile PrintWriter _writer;
    private static volatile FileDescriptor _fd;
    private static volatile boolean _initialized = false;
    private static volatile boolean _globalArchiveDone = false;
    private static volatile boolean _headerWritten = false;
    private static final Set<String> _loggedHashes = Collections.synchronizedSet(new HashSet<String>());

    public BanLogger(RouterContext context) {
        _dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        _dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        initialize(context);
    }

    /**
     * Initialize the logger. Safe to call multiple times.
     */
    public void initialize(RouterContext context) {
        if (context == null) {return;}
        if (_initialized) {return;}
        synchronized (_writeLock) {
            if (_initialized) {return;}
            _context = context;
            _log = context.logManager().getLog(BanLogger.class);
            File dataDir = context.getRouterDir();
            File logDir = new File(dataDir, LOG_DIR);
            if (!logDir.exists()) {
                if (!logDir.mkdirs()) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Failed to create ban log directory: " + logDir);
                    return;
                }
            }
            _logFile = new File(logDir, LOG_FILENAME);
            _startTime = System.currentTimeMillis();
            if (!_globalArchiveDone) {
                archiveExisting();
                deleteLegacySessionBansFile(dataDir);
                _globalArchiveDone = true;
            }
            openWriter();
            if (!_headerWritten) {
                logStartTime();
                _headerWritten = true;
            }
            _patternDetector = new HashPatternDetector(context);
            _patternDetector.startScanner();
            _initialized = true;
        }
    }

    /**
     * Delete legacy sessionbans.txt file from data directory root.
     * Consolidates all session bans to sessionbans/sessionbans.txt.
     */
    private void deleteLegacySessionBansFile(File dataDir) {
        File legacyFile = new File(dataDir, "sessionbans.txt");
        if (legacyFile.exists()) {
            if (legacyFile.delete()) {
                if (_log != null && _log.shouldLog(Log.INFO))
                    _log.info("Deleted legacy sessionbans.txt file");
            } else {
                if (_log != null && _log.shouldLog(Log.WARN))
                    _log.warn("Failed to delete legacy sessionbans.txt file");
            }
        }
    }

    /**
     * Check if the logger is initialized.
     */
    public static boolean isInitialized() {
        return _initialized;
    }

    /**
     * Get router uptime in milliseconds.
     */
    private long getUptime() {
        if (_startTime > 0) {
            return System.currentTimeMillis() - _startTime;
        }
        return 0;
    }

    /**
     * Archive an existing log file from a previous session.
     * Called during initialization before opening a new writer.
     */
    private void archiveExisting() {
        if (!_logFile.exists()) {return;}
        long mtime = _logFile.lastModified();
        if (mtime <= 0) {mtime = System.currentTimeMillis();}
        String timestamp = _dateFormat.format(new Date(mtime));
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
        if (_banCount <= 0) {return;}
        String timestamp = _dateFormat.format(new Date(_startTime));
        String safeTimestamp = timestamp.replace(':', '-').replace('T', '_');
        String archiveName = ARCHIVE_PREFIX + safeTimestamp + ".txt";
        File archiveFile = new File(_logFile.getParentFile(), archiveName);
        if (_logFile.renameTo(archiveFile)) {
            _banCount = 0;
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

        for (int i = maxArchives; i < archives.length; i++) {
            if (!archives[i].delete()) {
                if (_log != null && _log.shouldLog(Log.WARN))
                    _log.warn("Failed to delete old ban log archive: " + archives[i].getName());
            } else {
                if (_log != null && _log.shouldLog(Log.DEBUG))
                    _log.debug("Deleted old ban log archive: " + archives[i].getName());
            }
        }
    }

    /**
     * Log the router start time.
     */
    private void logStartTime() {
        if (_writer == null) {return;}
        _writer.println();
        _writer.println("############################################################");
        _writer.println("# Router started: " + _dateFormat.format(new Date(_startTime)));
        _writer.println("############################################################");
        _writer.println();
        _writer.println("# Ban event log");
        _writer.println("# Format: TIMESTAMP | HASH | IP:PORT | REASON | DURATION");
        _writer.println("# TIMESTAMP: ISO 8601 UTC");
        _writer.println("# HASH: Router hash (base64) or empty");
        _writer.println("# IP:PORT: IP address and port or empty");
        _writer.println("# REASON: Reason for ban");
        _writer.println("# DURATION: Duration (e.g., 8h, 24h, FOREVER)");
        _writer.println();
    }

    /**
     * Initialize the log file writer.
     */
    private void openWriter() {
        synchronized (_writeLock) {
            try {
                FileOutputStream fos = new FileOutputStream(_logFile, true);
                _fd = fos.getFD();
                _writer = new PrintWriter(fos, true);
            } catch (IOException e) {
                if (_log != null && _log.shouldLog(Log.WARN))
                    _log.warn("Failed to open ban log file: " + _logFile, e);
            }
        }
    }

    /**
     * Force sync to disk for data durability.
     */
    private void syncToDisk() {
        if (_writer != null) {
            _writer.flush();
            try {
                if (_fd != null) {
                    _fd.sync();
                }
            } catch (IOException e) {
                if (_log != null && _log.shouldLog(Log.WARN))
                    _log.warn("Failed to sync ban log to disk", e);
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
        String hashStr = hash != null ? hash.toBase64() : "";
        String durationStr = formatDuration(durationMs);
        writeLog(hashStr, ip, reason, durationStr);
    }

    /**
     * Log a ban by IP only (no router hash available).
     *
     * @param ip IP address with port (format: "1.2.3.4:5678" or "ipv6:port")
     * @param reason Reason for the ban
     * @param durationMs Ban duration in milliseconds, or 0 for permanent
     */
    public void logBan(String ip, String reason, long durationMs) {
        logBan(null, ip, reason, durationMs);
    }

    /**
     * Log a permanent ban (forever).
     *
     * @param hash Router hash (may be null)
     * @param ip IP address with port (format: "1.2.3.4:5678" or "ipv6:port")
     * @param reason Reason for the ban
     */
    public void logBanForever(Hash hash, String ip, String reason) {
        String hashStr = hash != null ? hash.toBase64() : "";
        writeLog(hashStr, ip, reason, "FOREVER");
    }

    /**
     * Log a permanent ban by IP only.
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
     * Get IP address from banlist for the given hash.
     */
    private String getIPFromContext(Hash hash, RouterContext context) {
        if (hash == null) {return "";}
        return "";
    }

    /**
     * Check if a router hash matches known attack patterns and should be predictively banned.
     * Call this BEFORE processing messages from a peer to proactively ban algorithmic identities.
     *
     * @param hash Router hash to check
     * @param context RouterContext for banlist operations
     * @return true if predictively banned, false otherwise
     */
    public boolean checkPatternAndPredict(Hash hash, RouterContext context) {
        if (hash == null || _patternDetector == null) {
            return false;
        }
        boolean banned = _patternDetector.analyzeAndPredict(hash, context);
        if (banned) {
            // Log the predictive ban
            String prefix = _patternDetector.getHashPrefix(hash);
            String reason = "Predictive ban: " + prefix;
            writeLog(hash.toBase64(), "", reason, "24h");
        }
        return banned;
    }

    // Column widths for aligned output
    private static final int COL_WIDTH_HASH = 44;    // Base64 router hash length
    private static final int COL_WIDTH_IP = 45;      // Max IPv6:PORT length

    /**
     * Internal method to write the log entry.
     * Skips logging if this hash has already been logged in this session.
     */
    private void writeLog(String hashStr, String ip, String reason, String durationStr) {
        // Skip if we've already logged this hash (prevents duplicate ban logging)
        if (hashStr != null && !hashStr.isEmpty() && !_loggedHashes.add(hashStr)) {
            return; // Already logged this hash
        }

        String timestamp = _dateFormat.format(new Date());
        // Format with fixed-width columns for alignment
        String entry = String.format("%s | %-" + COL_WIDTH_HASH + "s | %-" + COL_WIDTH_IP + "s | %s | %s",
                                     timestamp,
                                     hashStr != null ? hashStr : "",
                                     ip != null ? ip : "",
                                     reason,
                                     durationStr);

        synchronized (_writeLock) {
            if (_writer != null) {
                _writer.println(entry);
                _banCount++;
            }
        }
        syncToDisk();

        // Record pattern for predictive analysis
        if (_patternDetector != null && hashStr != null && !hashStr.isEmpty()) {
            try {
                Hash hash = new Hash();
                hash.fromBase64(hashStr);
                _patternDetector.recordBan(hash, reason);
            } catch (Exception e) {
                // Ignore pattern recording errors
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
     * Get the log file path.
     */
    public File getLogFile() {
        return _logFile;
    }

    /**
     * Flush the log writer.
     */
    public void flush() {
        synchronized (_writeLock) {
            if (_writer != null) {
                _writer.flush();
            }
        }
        syncToDisk();
    }

    /**
     * Close the log writer and archive if there are entries.
     */
    public void close() {
        synchronized (_writeLock) {
            if (_writer != null) {
                syncToDisk();
                _writer.close();
                _writer = null;
            }
        }
        archiveIfNeeded();
    }
}
