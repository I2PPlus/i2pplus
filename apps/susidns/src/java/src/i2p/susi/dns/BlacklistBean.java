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
import net.i2p.util.SecureFileOutputStream;
import net.i2p.servlet.RequestWrapper;

/**
 * Bean for managing address book blacklist entries.
 * Supports hostnames, b32, and b64 addresses.
 */
public class BlacklistBean extends BaseBean {
    private String fileName, content;
    private static final String BLACKLIST_FILE = "blacklist.txt";

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
                debug("Loaded blacklist from file: " + file.getAbsolutePath() + " (" + content.length() + " chars)");
            } catch (IOException e) {
                warn(e);
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
}