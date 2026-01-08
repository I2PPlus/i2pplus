package net.i2p.i2ptunnel;

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
 * </p>
 * <p>
 * The blocklist files are located in the I2P config directory and are monitored
 * for changes. The URL blocklist is compiled into a regex pattern for efficient
 * matching.
 * </p>
 *
 * @since 0.10.0
 */
public class BlocklistManager {

    /** Default filename for the URL blocklist */
    public static final String HTTP_BLOCKLIST = "http_blocklist.txt";

    /** Default filename for the client blocklist */
    public static final String HTTP_BLOCKLIST_CLIENTS = "http_blocklist_clients.txt";

    /** Default maximum number of entries in the client blocklist */
    public static final int DEFAULT_CLIENT_LIMIT = 512;

    private final Log _log;
    private final File _configDir;
    private Pattern _regexPattern = null;
    private long _blocklistLastModified;
    private List<String> _clientBlockList = new ArrayList<>();
    private static long _blocklistClientsLastModified;
    private static int _cachedClientBlockListSize = -1;
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
            _regexPattern = null;
            return false;
        }

        long currentLastModified = blocklistFile.lastModified();
        if (currentLastModified != _blocklistLastModified || _regexPattern == null) {
            _regexPattern = compileRegexPattern(blocklistFile);
            _blocklistLastModified = currentLastModified;
        }

        if (_regexPattern == null) {return false;}

        String lcCommand = command.toString().toLowerCase(Locale.US);
        Matcher matcher = _regexPattern.matcher(lcCommand);
        return matcher.find();
    }

    /**
     * Gets the matched string from the blocklist for a request.
     * <p>
     * If shouldBlockRequest() returns true, this method can be called
     * to retrieve the specific pattern that matched.
     * </p>
     *
     * @param command the HTTP request command containing the URL
     * @return the matched blocklist string, or null if no match
     */
    public String getMatchedBlocklistString(StringBuilder command) {
        if (_regexPattern == null) {return null;}
        String lcCommand = command.toString().toLowerCase(Locale.US);
        Matcher matcher = _regexPattern.matcher(lcCommand);
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
     * @return compiled Pattern, or null on error
     */
    private Pattern compileRegexPattern(File blocklistFile) {
        StringBuilder regexBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(blocklistFile), StandardCharsets.UTF_8))) {
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
     * the oldest entry is removed before adding a new one.
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
            }
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(blocklistClients, true), StandardCharsets.UTF_8))) {
                writer.write(destination);
                writer.newLine();
            } catch (IOException e) {
                _log.error("[HTTPServer] Error logging blocked destination (" + e.getMessage() + ")");
            }
            _blocklistClientsLastModified = blocklistClients.lastModified();
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
                    new InputStreamReader(new FileInputStream(blocklistClients), StandardCharsets.UTF_8))) {
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
     * Gets the current size of the client blocklist.
     *
     * @return the number of entries in the client blocklist, or -1 if not initialized
     */
    public int getClientBlocklistSize() {
        return _cachedClientBlockListSize;
    }
}
