/*
 * Simple BlacklistBean for I2PTunnel HTTP Client
 * Standalone implementation to avoid susidns dependencies
 */

package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Pattern;
import net.i2p.util.Log;

import java.nio.charset.StandardCharsets;
/**
 * Simple blacklist checker for HTTP proxy.
 * Loads blacklist from addressbook/blacklist.txt and checks if addresses are blacklisted.
 * Standalone implementation to avoid susidns dependencies.
 */
public class BlacklistBean {

    /** Default constructor */
    public BlacklistBean() {}

    private static final Log _log = new Log(BlacklistBean.class);
    private String content;
    private static final Pattern NEWLINE_SPLIT = Pattern.compile("\\n");

    /**
     * Get the blacklist file, searching common I2P directory locations.
     *
     * @return the blacklist File object
     */
    private File blacklistFile() {
        // Try common router directory locations
        String[] possiblePaths = {
            System.getProperty("user.home") + "/.i2p/addressbook/blacklist.txt",
            System.getProperty("user.home") + "/i2p/addressbook/blacklist.txt",
            "addressbook/blacklist.txt",
            "../addressbook/blacklist.txt"
        };

        for (String path : possiblePaths) {
            File file = new File(path);
            if (file.exists() || file.getParentFile().exists()) {
                return file;
            }
        }
        // Default to relative path
        return new File("addressbook/blacklist.txt");
    }

    /**
     * Reload the blacklist content from file into memory.
     */
    private void reloadBlacklist() {
        File file = blacklistFile();
        if (file.isFile()) {
            StringBuilder buf = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(file)), StandardCharsets.UTF_8))) {
                String line;
                while((line = br.readLine()) != null) {
                    buf.append(line);
                    buf.append("\n");
                }
                content = buf.toString();
            } catch (IOException e) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error loading blacklist", e);
                content = "";
            }
        } else {
            content = "";
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(file))) {
                // Create empty blacklist file
            } catch (IOException e) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Cannot create blacklist file: " + file.getAbsolutePath(), e);
            }
        }
    }

    /**
     * Check if an address is blacklisted.
     *
     * @param address the I2P address to check, may be a hostname or base32/Base64 address
     * @return true if the address is in the blacklist, false otherwise
     * @since 1.0
     */
    public boolean isBlacklisted(String address) {
        if (address == null) {
            return false;
        }
        // Ensure blacklist content is loaded
        if (content == null) {
            reloadBlacklist();
        }
        if (content == null) {
            return false;
        }
        address = address.trim().toLowerCase();
        String[] lines = NEWLINE_SPLIT.split(content);
        for (String line : lines) {
            if (line.trim().equalsIgnoreCase(address)) {
                return true;
            }
        }
        return false;
    }
}
