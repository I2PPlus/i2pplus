/*
 * Simple BlacklistBean for I2PTunnel HTTP Client
 * Standalone implementation to avoid susidns dependencies
 */

package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Simple blacklist checker for HTTP proxy.
 * Loads blacklist from addressbook/blacklist.txt and checks if addresses are blacklisted.
 * Standalone implementation to avoid susidns dependencies.
 */
public class BlacklistBean {
    private String content;
    private static final Pattern I2P_ADDRESS_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9.-]+\\.i2p$|" +                    // hostname.i2p
        "^[a-zA-Z2-7]{52,53}\\.b32\\.i2p$|" +          // b32 addresses (52-53 chars)
        "^[a-zA-Z0-9+/]{387}={0,2}\\.b64\\.i2p$"       // b64 addresses (387+ chars)
    );

    /**
     * Get blacklist file - look for it in typical I2P locations
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
     * Reload blacklist from file
     */
    private void reloadBlacklist() {
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
                content = buf.toString();
            } catch (IOException e) {
                System.err.println("[BlacklistBean] Error loading blacklist: " + e.getMessage());
                content = "";
            }
            finally {
                if (br != null) {
                    try {br.close();}
                    catch (IOException ioe) {}
                }
            }
        } else {
            content = "";
            try {
                // Create parent directories if they don't exist
                File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                // Create empty blacklist file
                new FileOutputStream(file).close();
            } catch (IOException e) {
                System.err.println("[BlacklistBean] Could not create blacklist file: " + file.getAbsolutePath() + " - " + e.getMessage());
            }
        }
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
            reloadBlacklist();
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
}