/*
 * This file is part of SusiDNS project for I2P+
 * Created on Dec 16, 2025
 * License: GPL2 or later
 */

package i2p.susi.dns;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.servlet.RequestWrapper;

/**
 * Bean for managing address book blacklist entries.
 * Supports hostnames, b32, and b64 addresses.
 */
public class BlacklistBean extends BaseBean {
    private String fileName, content;
    private static String cachedContent = null;
    private static long lastModified = 0;
    private static final String BLACKLIST_FILE = "blacklist.txt";
    private static final Log _log = new Log(BlacklistBean.class);

    // Pattern to validate I2P addresses (hostnames, b32, b64)
    private static final Pattern I2P_ADDRESS_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9.-]+\\.i2p$|" +                    // hostname.i2p
        "^[a-zA-Z2-7]{52,53}\\.b32\\.i2p$|" +          // b32 addresses (52-53 chars)
        "^[a-zA-Z0-9+/]{387}={0,2}\\.b64\\.i2p$"       // b64 addresses (387+ chars)
    );

    public String getFileName() {
        loadConfig();
        fileName = blacklistFile().toString();
        debug("Blacklist file path: " + fileName);
        return fileName;
    }

    /**
     * Get the blacklist file
     */
    private File blacklistFile() {
        return new File(addressbookDir(), BLACKLIST_FILE);
    }

    /**
     * Reload blacklist from file
     */
    private void reloadBlacklist() {
        synchronized(BlacklistBean.class) {locked_reloadBlacklist();}
    }

    private void locked_reloadBlacklist() {
        File file = blacklistFile();
        if (file.isFile()) {
            long currentModified = file.lastModified();
            // Use cached content if file hasn't changed
            if (cachedContent != null && currentModified == lastModified) {
                content = cachedContent;
                return;
            }

            StringBuilder buf = new StringBuilder();
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
                String line;
                while((line = br.readLine()) != null) {
                    buf.append(line);
                    buf.append("\n");
                }
                content = buf.toString();
                cachedContent = content;
                lastModified = currentModified;

                // Count valid entries (non-empty, non-comment lines)
                int validEntries = 0;
                String[] entryLines = content.split("\\n");
                for (String entryLine : entryLines) {
                    String trimmed = entryLine.trim();
                    if (trimmed.length() > 0 && !trimmed.startsWith("#")) {
                        validEntries++;
                    }
                }

                debug("Loaded blacklist from file: " + file.getAbsolutePath() + " (" + validEntries + " entries)");
            } catch (IOException e) {
                warn(e);
                content = "";
                cachedContent = content;
            }
            finally {
                if (br != null) {
                    try {br.close();}
                    catch (IOException ioe) {}
                }
            }
        } else {
            content = "";
            cachedContent = content;
            debug("Blacklist file does not exist: " + file.getAbsolutePath());
        }
    }

    /**
     * Save blacklist to file
     */
    private void save() {
        synchronized(BlacklistBean.class) {locked_save();}
    }

    private void locked_save() {
        File file = blacklistFile();
        try {
            // trim, validate, and sort
            List<String> entries = new ArrayList<String>();
            InputStream in = new ByteArrayInputStream(content.getBytes("UTF-8"));
            String line;
            while ((line = DataHelper.readLine(in)) != null) {
                line = line.trim();
                if (line.length() > 0 && isValidI2PAddress(line)) {
                    entries.add(line);
                }
            }
            Collections.sort(entries, String.CASE_INSENSITIVE_ORDER);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "UTF-8"));
            for (String entry : entries) {out.println(entry);}
            out.close();
            if (out.checkError()) {throw new IOException("Failed write to " + file);}
            // Update static cache after saving
            cachedContent = null; // Force reload on next read
            lastModified = 0; // Reset modification time to trigger reload
            debug("Saved blacklist to file: " + file.getAbsolutePath() + " (" + entries.size() + " entries)");
        } catch (IOException e) {warn(e);}
    }

    /**
     * Check if an address is a valid I2P address
     */
    private boolean isValidI2PAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }
        return I2P_ADDRESS_PATTERN.matcher(address.trim()).matches();
    }

    /**
     * Check if an address is blacklisted
     */
    public boolean isBlacklisted(String address) {
        if (address == null) {
            return false;
        }
        // Ensure blacklist content is loaded
        if (content == null) {
            getContent();
        }
        if (content == null) {
            return false;
        }
        address = address.trim().toLowerCase();
        String[] lines = content.split("\\n");
        for (String line : lines) {
            if (line.trim().toLowerCase().equals(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a hostname is blacklisted (exact match)
     */
    public boolean isHostnameBlacklisted(String hostname) {
        return isBlacklisted(hostname);
    }

    /**
     * Check if a b32 address is blacklisted (exact match or hostname match)
     */
    public boolean isB32Blacklisted(String b32) {
        if (b32 == null) return false;

        // Check exact b32 match
        if (isBlacklisted(b32)) {
            return true;
        }

        // Try to resolve b32 to hostname and check if that hostname is blacklisted
        try {
            net.i2p.data.Destination dest = _context.namingService().lookup(b32);
            List<String> names = dest != null ? _context.namingService().reverseLookupAll(dest.calculateHash()) : null;
            if (names != null) {
                for (String name : names) {
                    if (isBlacklisted(name)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Log at debug level only for HostChecker to avoid spam
            if (_log.shouldDebug()) {
                _log.debug("Error checking b32 blacklist for " + b32 + ": " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Check if a b64 address is blacklisted (exact match or hostname/b32 match)
     */
    public boolean isB64Blacklisted(String b64) {
        if (b64 == null) return false;

        // Check exact b64 match
        if (isBlacklisted(b64)) {
            return true;
        }

        try {
            // Convert b64 to Destination and try reverse lookups
            net.i2p.data.Destination dest = new net.i2p.data.Destination(b64);
            net.i2p.data.Hash hash = dest.calculateHash();

            // Try b32 lookup
            String b32 = hash.toBase32() + ".b32.i2p";
            if (isBlacklisted(b32)) {
                return true;
            }

            // Try hostname reverse lookup
            List<String> names = _context.namingService().reverseLookupAll(hash);
            if (names != null) {
                for (String name : names) {
                    if (isBlacklisted(name)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Log at debug level only for HostChecker to avoid spam
            if (_log.shouldDebug()) {
                _log.debug("Error checking b64 blacklist for " + b64.substring(0, Math.min(10, b64.length())) + "...: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Enhanced blacklist check that tries hostname, b32, and b64 variations
     */
    public boolean isBlacklistedByAnyForm(String address) {
        if (isBlacklisted(address)) {
            return true;
        }

        // Try as b32
        if (address.endsWith(".b32.i2p")) {
            return isB32Blacklisted(address);
        }

        // Try as b64
        if (address.length() > 500) { // Rough b64 length check
            return isB64Blacklisted(address);
        }

        // Try as hostname - check if any of its destinations are blacklisted
        try {
            List<net.i2p.data.Destination> dests = _context.namingService().lookupAll(address);
            if (dests != null) {
                for (net.i2p.data.Destination dest : dests) {
                    if (isB64Blacklisted(dest.toBase64())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Log at debug level only for HostChecker to avoid spam
            if (_log.shouldDebug()) {
                _log.debug("Error checking hostname blacklist for " + address + ": " + e.getMessage());
            }
        }

        return false;
    }

    public String getMessages() {
        String message = "";
        if (action != null) {
            if ("POST".equals(method) && (_context.getBooleanProperty(PROP_PW_ENABLE) || (serial != null && serial.equals(lastSerial)))) {
                if (action.equals(_t("Save"))) {
                    save();
                    message = _t("Blacklist saved.");
                }
                if (action.equals(_t("Reload"))) {
                    reloadBlacklist();
                    message = _t("Blacklist reloaded from file.");
                }
            } else {
                message = _t("Invalid form submission, probably because you used the \"back\" or \"reload\" button on your browser. Please resubmit.") + ' ' +
                          _t("If the problem persists, verify that you have cookies enabled in your browser.");
            }
            if (message.length() > 0) {message = "<p class=\"messages\">" + message + "</p>";}
        }
        return message;
    }

    public void setContent(String content) {
        this.content = DataHelper.stripHTML(content);
    }

    public String getContent() {
        if (content == null) {
            reloadBlacklist();
        }
        return content != null ? content : "";
    }

    /**
      * Add entries to the blacklist
      * @param entries List of hostnames or addresses to add to the blacklist
      * @return Number of entries successfully added
      */
    public int addEntries(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        // Load current content from file (not cache) to avoid static field issues
        String currentContent = loadContentFromFile();
        int added = 0;
        List<String> newEntries = new ArrayList<String>();
        for (String entry : entries) {
            if (entry != null && entry.trim().length() > 0) {
                String trimmedEntry = entry.trim();
                if (isValidI2PAddress(trimmedEntry) && !isEntryInContent(trimmedEntry, currentContent)) {
                    newEntries.add(trimmedEntry);
                    added++;
                    debug("Added to blacklist: " + trimmedEntry);
                }
            }
        }
        if (added > 0) {
            appendEntriesToFile(newEntries, currentContent);
        }
        return added;
    }

    /**
     * Load content directly from file, bypassing cache
     */
    private String loadContentFromFile() {
        File file = blacklistFile();
        if (file.isFile()) {
            StringBuilder buf = new StringBuilder();
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
                String line;
                while((line = br.readLine()) != null) {
                    buf.append(line);
                    buf.append("\n");
                }
                debug("Loaded blacklist from file: " + file.getAbsolutePath());
            } catch (IOException e) {
                warn(e);
            }
            finally {
                if (br != null) {
                    try {br.close();}
                    catch (IOException ioe) {}
                }
            }
            return buf.toString();
        }
        return "";
    }

    /**
     * Check if an entry exists in the given content string
     */
    private boolean isEntryInContent(String entry, String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String[] lines = content.split("\\n");
        for (String line : lines) {
            if (line.trim().equalsIgnoreCase(entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Append new entries to file
     */
    private void appendEntriesToFile(List<String> newEntries, String currentContent) {
        File file = blacklistFile();
        try {
            List<String> allEntries = new ArrayList<String>();
            // Add existing entries
            if (currentContent != null && !currentContent.isEmpty()) {
                String[] lines = currentContent.split("\\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.length() > 0 && isValidI2PAddress(line)) {
                        allEntries.add(line);
                    }
                }
            }
            // Add new entries
            allEntries.addAll(newEntries);
            // Sort all entries
            Collections.sort(allEntries, String.CASE_INSENSITIVE_ORDER);
            // Write to file
            PrintWriter out = new PrintWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "UTF-8"));
            for (String entry : allEntries) {out.println(entry);}
            out.close();
            if (out.checkError()) {throw new IOException("Failed write to " + file);}
            debug("Saved blacklist to file: " + file.getAbsolutePath() + " (" + allEntries.size() + " entries)");
            // Update static cache after saving
            synchronized(BlacklistBean.class) {
                cachedContent = null;
                lastModified = 0;
            }
        } catch (IOException e) {warn(e);}
    }
}