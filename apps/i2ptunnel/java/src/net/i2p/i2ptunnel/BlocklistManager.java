package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Manages HTTP blocklists for request filtering.
 * <p>
 * This class handles two types of blocklists:
 * <ul>
 *   <li><b>URL Blocklist</b> (http_blocklist.txt) - Contains patterns to match
 *       against request URLs. Requests matching these patterns are blocked.</li>
 *   <li><b>Client Blocklist</b> (http_blocklist_clients.txt) - Tracks destinations
 *       that have been blocked, to prevent repeated abuse.</li>
 * </ul>
 * <p>
 * The blocklist files are located in the I2P config directory and are monitored
 * for changes. The URL blocklist is compiled into a regex pattern for efficient
 * matching.
 * </p>
 *
 * @since 0.10.0
 */
public class BlocklistManager {

    /** Default filename for the URL blocklist. */
    public static final String HTTP_BLOCKLIST = "http_blocklist.txt";

    /** Default filename for the client blocklist. */
    public static final String HTTP_BLOCKLIST_CLIENTS = "http_blocklist_clients.txt";

    /** Default maximum number of entries in the client blocklist. */
    public static final int DEFAULT_CLIENT_LIMIT = 512;

    /**
     * Sentinel pattern that matches nothing, standing in for "the file has no
     * usable entries" so an empty or comment-only blocklist is not re-read and
     * re-compiled on every request.
     */
    private static final Pattern NO_PATTERNS = Pattern.compile("(?!)");

    /** Logger for this class. */
    private final Log _log;
    /** Directory the blocklist file is read from. */
    private final File _configDir;
    /**
     * Compiled URL pattern together with the file mtime it was built from, as
     * one immutable holder swapped in a single volatile write — connection
     * threads must never see a pattern paired with another load's mtime.
     */
    private volatile UrlPattern _urlPattern;
    /** The blocked addresses read from the file. Guarded by the instance monitor. */
    private List<String> _clientBlockList = new ArrayList<>();
    /** File timestamp this manager's client list was last read at. Instance state: each tunnel's manager tracks its own file view. */
    private long _blocklistClientsLastModified;
    /** Size of this manager's client list, -1 before first read. */
    private int _cachedClientBlockListSize = -1;
    /** Most clients this tunnel allows, from the blocklist. */
    private int _clientLimit;

    /**
     * Creates a new BlocklistManager.
     *
     * @param log the Log instance for logging blocklist events
     * @param clientLimit the maximum number of entries to keep in the client blocklist;
     *                    if 0 or negative, uses DEFAULT_CLIENT_LIMIT
     */
    public BlocklistManager(Log log, int clientLimit) {
        _log = log;
        _configDir = I2PAppContext.getGlobalContext().getConfigDir();
        _clientLimit = clientLimit > 0 ? clientLimit : DEFAULT_CLIENT_LIMIT;
    }

    /**
     * Checks if a request should be blocked based on the URL blocklist.
     * <p>
     * This method checks if the request URL matches any pattern in the
     * http_blocklist.txt file. The blocklist file is monitored for changes
     * and will be reloaded if modified.
     * </p>
     * <p>
     * Each line in the blocklist file is treated as a literal string (not regex),
     * case-insensitive matching is applied.
     * </p>
     *
     * @param command the HTTP request command containing the URL
     * @return true if the request should be blocked, false otherwise
     */
    public boolean shouldBlockRequest(StringBuilder command) {
        File blocklistFile = new File(_configDir, HTTP_BLOCKLIST);
        if (!blocklistFile.exists()) {
            _urlPattern = null;
            return false;
        }

        long currentLastModified = blocklistFile.lastModified();
        UrlPattern holder = _urlPattern;
        if (holder == null || holder.lastModified != currentLastModified) {
            Pattern p = compileRegexPattern(blocklistFile);
            // NO_PATTERNS for an empty or unreadable file still records the
            // mtime, so the file is read once per edit, not once per request.
            holder = new UrlPattern(p != null ? p : NO_PATTERNS, currentLastModified);
            _urlPattern = holder;
        }

        String lcCommand = command.toString().toLowerCase(Locale.US);
        Matcher matcher = holder.pattern.matcher(lcCommand);
        return matcher.find();
    }

    /**
     * Gets the matched string from the blocklist for a request.
     * <p>
     * If {@link #shouldBlockRequest} returns true, this method can be called
     * to retrieve the specific pattern that matched.
     * </p>
     *
     * @param command the HTTP request command containing the URL
     * @return the matched blocklist string, or null if no match
     */
    public String getMatchedBlocklistString(StringBuilder command) {
        UrlPattern holder = _urlPattern;
        if (holder == null || holder.pattern == NO_PATTERNS) {return null;}
        String lcCommand = command.toString().toLowerCase(Locale.US);
        Matcher matcher = holder.pattern.matcher(lcCommand);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    /**
     * Reads and compiles the blocklist file into a regex pattern.
     * <p>
     * Each non-empty, non-comment line is treated as a literal string
     * that will be matched against request URLs. The resulting pattern
     * is case-insensitive.
     * </p>
     *
     * @param blocklistFile the blocklist file to read
     * @return compiled Pattern, or null if the file has no usable entries or could not be read
     */
    @SuppressWarnings("PMD.AvoidRecompilingPatterns")
    private Pattern compileRegexPattern(File blocklistFile) {
        StringBuilder regexBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new BufferedInputStream(new FileInputStream(blocklistFile)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {continue;}

                StringBuilder regex = new StringBuilder();
                regex.append("(?i)");
                regex.append(Pattern.quote(line));

                if (regexBuilder.length() > 0) {regexBuilder.append("|");}
                regexBuilder.append(regex);
            }
        } catch (IOException e) {
            if (_log.shouldError()) {
                _log.error("[HTTPServer] Error reading blocklist file (" + e.getMessage() + ")");
            }
            return null;
        }

        if (regexBuilder.length() == 0) {return null;}

        try {
            return Pattern.compile(regexBuilder.toString());
        } catch (Exception e) {
            if (_log.shouldError()) {
                _log.error("[HTTPServer] Error compiling regex pattern (" + e.getMessage() + ")");
            }
            return null;
        }
    }

    /**
     * Logs a blocked destination to the client blocklist.
     * <p>
     * This method adds the destination to the http_blocklist_clients.txt file
     * if not already present. The client blocklist is used to track destinations
     * that have been blocked, to prevent repeated abuse attempts.
     * </p>
     * <p>
     * The client blocklist has a maximum size limit. When the limit is reached,
     * the oldest entry is removed before adding a new one, and the file is
     * rewritten from the bounded list so evicted entries do not return on the
     * next reload.
     * </p>
     *
     * @param destination the Base32-encoded destination to log
     * @throws IOException if an I/O error occurs
     */
    public synchronized void logBlockedDestination(String destination) throws IOException {
        File blocklistClients = new File(_configDir, HTTP_BLOCKLIST_CLIENTS);

        if (!blocklistClients.exists()) {
            try {
                blocklistClients.createNewFile();
                _blocklistClientsLastModified = blocklistClients.lastModified();
            } catch (IOException e) {
                _log.error("[HTTPServer] Error creating file for blocked destination (" + e.getMessage() + ")");
                return;
            }
        }

        refreshClientBlocklist(blocklistClients);

        if (!_clientBlockList.contains(destination)) {
            if (_clientBlockList.size() >= _clientLimit) {
                _clientBlockList.remove(0);
                // Record our own write in the in-memory list first, or the
                // next refresh skips its own append (the mtime is already
                // current) and a repeatedly blocked peer appends a duplicate
                // line per request.
                _clientBlockList.add(destination);
                // The file is otherwise append-only, so a memory-side eviction
                // would resurrect on the next reload — rewrite it from the
                // bounded list instead.
                rewriteClientBlocklist(blocklistClients);
            } else {
                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(blocklistClients, true)), StandardCharsets.UTF_8))) {
                    writer.write(destination);
                    writer.newLine();
                } catch (IOException e) {
                    _log.error("[HTTPServer] Error logging blocked destination (" + e.getMessage() + ")");
                    return;
                }
                _clientBlockList.add(destination);
            }
            _cachedClientBlockListSize = _clientBlockList.size();
            _blocklistClientsLastModified = blocklistClients.lastModified();
        }
    }

    /**
     * Rewrites the client blocklist file from the current in-memory list, so
     * entries evicted by the size cap stay gone after a reload.
     * Throws on failure; the caller leaves the in-memory list unchanged.
     *
     * @param blocklistClients the client blocklist file to rewrite
     * @throws IOException if the file could not be written
     */
    private void rewriteClientBlocklist(File blocklistClients) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(blocklistClients, false)), StandardCharsets.UTF_8))) {
            for (String dest : _clientBlockList) {
                writer.write(dest);
                writer.newLine();
            }
        }
    }

    /**
     * Refreshes the in-memory client blocklist from disk if the file has changed.
     *
     * @param blocklistClients the client blocklist file
     * @throws IOException if an I/O error occurs
     */
    private synchronized void refreshClientBlocklist(File blocklistClients) throws IOException {
        long currentLastModified = blocklistClients.lastModified();
        if (currentLastModified != _blocklistClientsLastModified) {
            _clientBlockList.clear();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new BufferedInputStream(new FileInputStream(blocklistClients)), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !_clientBlockList.contains(line)) {
                        _clientBlockList.add(line);
                    }
                }
            } catch (IOException e) {
                _log.error("[HTTPServer] Error reading client blocklist file (" + e.getMessage() + ")");
                throw e;
            }
            _blocklistClientsLastModified = currentLastModified;
            _cachedClientBlockListSize = _clientBlockList.size();
        }
    }

    /**
     * Checks if a destination exists in the client blocklist.
     * <p>
     * This method checks if the given destination has been previously
     * blocked. The blocklist file is monitored for changes and will
     * be reloaded if modified.
     * </p>
     *
     * @param destination the Base32-encoded destination to check
     * @return true if the destination is in the blocklist, false otherwise
     * @throws IOException if an I/O error occurs
     */
    public synchronized boolean existsInClientBlocklist(String destination) throws IOException {
        File blocklistClients = new File(_configDir, HTTP_BLOCKLIST_CLIENTS);
        if (!blocklistClients.exists()) {return false;}

        refreshClientBlocklist(blocklistClients);
        return _clientBlockList.contains(destination);
    }

    /**
     * The number of entries in the client blocklist.
     *
     * @return the number of entries in the client blocklist, or -1 if not initialized
     */
    public int getClientBlocklistSize() {
        return _cachedClientBlockListSize;
    }

    /**
     * Immutable pair of the compiled URL pattern and the file mtime it was
     * built from, swapped as one volatile write so readers never mix halves
     * of two different reloads.
     *
     * @since 0.9.72+
     */
    private static class UrlPattern {
        /** Compiled pattern matching blocked URL substrings, never null. */
        final Pattern pattern;
        /** Blocklist file mtime the pattern was compiled from. */
        final long lastModified;

        /**
         * Pair a compiled pattern with the mtime it was built from.
         *
         * @param pattern the compiled pattern, never null (NO_PATTERNS for an empty file)
         * @param lastModified the blocklist file mtime the pattern reflects
         */
        UrlPattern(Pattern pattern, long lastModified) {
            this.pattern = pattern;
            this.lastModified = lastModified;
        }
    }
}
