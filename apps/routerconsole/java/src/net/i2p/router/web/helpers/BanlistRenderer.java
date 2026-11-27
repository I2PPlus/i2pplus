package net.i2p.router.web.helpers;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Banlist;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.web.Messages;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 * Renders HTML display of banned routers for the router console.
 * Displays banlist entries including expiration times, reasons,
 * and provides management interface for router bans.
 * Moved from Banlist.java for separation of concerns.
 */
class BanlistRenderer {
    /** The router context. */
    private final RouterContext _context;
    /** Pattern normalizing an IP or hostname for display. */
    private static final Pattern IP_HOSTNAME_NORMALIZE = Pattern.compile("[.:]");
    /** Pattern splitting a ban record on the pipes. */
    private static final Pattern PIPE_SPLIT = Pattern.compile("\\s*\\|\\s*");
    /** Pattern matching the local update suffix of a version. */
    private static final Pattern VERSION_LU_SUFFIX = Pattern.compile("\\s*\\(0\\.9\\.\\d+(?:\\.\\d+)?(?:-\\d+)?\\s*/\\s*LU\\)\\s*$");
    /** Pattern matching the trailing junk of a version string. */
    private static final Pattern VERSION_SUFFIX = Pattern.compile("\\s*\\(0\\.9\\.\\d+(?:\\.\\d+)?(?:-\\d+)?\\)\\s*$");
    /** Pattern matching the leading junk of a version string. */
    private static final Pattern VERSION_PREFIX = Pattern.compile("\\s*/\\s*0\\.9\\.\\d+(?:\\.\\d+)?(?:-\\d+)?\\s*$");
    /** Pattern extracting the version numbers from a string. */
    private static final Pattern VERSION_EXTRACT = Pattern.compile("(?:^|\\()(\\d+\\.\\d+(?:\\.\\d+)?(?:-\\d+)?)");

    /**
     * Render rows in a single write up to this count; above it, flush every {@link #STREAM_BATCH} rows so the page paints progressively.
     */
    private static final int MAX_BEFORE_STREAMING = 100;
    /** Rows rendered per flush when streaming a large table. */
    private static final int STREAM_BATCH = 100;
    /** Length of the truncated base64 hash used as a per-row data-key in fragment mode; 16 chars = 96 bits, collision-free at 10K-20K rows. */
    private static final int KEY_LEN = 16;
    /** Highest port number, the upper bound of the 16-bit port space. */
    private static final int MAX_PORT = 65535;

    /**
     * Stores the router context used to reach the banlist and network database.
     *
     * @param context the router context
     */
    public BanlistRenderer(RouterContext context) {
        _context = context;
    }

    /**
     * Looks up the hostname for a banned IP.
     *
     * @param ip the address to look up
     * @return the hostname, null if not found
     */
    private String reverseLookup(String ip) {
        if (ip == null || ip.isEmpty()) return null;
        // Use fast local lookup — never blocks on network DNS
        String hostname = _context.commSystem().getLocalHostName(ip);
        // If result is null, "unknown", or still looks like an IP, return null
        if (hostname == null || hostname.isEmpty() || "unknown".equals(hostname)) {
            return null;
        }
        // Check if the hostname is just the IP in different form
        String lcIp = ip.toLowerCase(Locale.US);
        String lcHostname = hostname.toLowerCase(Locale.US);
        String normalizedIp = IP_HOSTNAME_NORMALIZE.matcher(lcIp).replaceAll("");
        String normalizedHostname = IP_HOSTNAME_NORMALIZE.matcher(lcHostname).replaceAll("");
        if (normalizedHostname.equals(normalizedIp) || normalizedHostname.contains(normalizedIp)) {
            return null;
        }
        return hostname;
    }

    /**
     * Read sessionbans.txt once and build all three data structures in a single pass.
     * Replaces the three separate readSessionBans* methods that each opened the file independently.
     *
     * @return array of [Map&lt;String,String&gt; ipMap, Map&lt;String,String&gt; hostnameMap, List&lt;IPBanEntry&gt; ipOnlyBans]
     */
    private Object[] readSessionBans() {
        Map<String, String> ipMap = new HashMap<>();
        Map<String, String> hostnameMap = new HashMap<>();
        Map<String, String> capsMap = new HashMap<>();
        Map<String, String> versionMap = new HashMap<>();
        List<IPBanEntry> ipBans = new ArrayList<>();
        Set<String> seenIPs = new HashSet<>();
        File logDir = new File(_context.getRouterDir(), "sessionbans");
        File logFile = new File(logDir, "sessionbans.txt");
        if (!logFile.exists()) {
        return new Object[] { ipMap, hostnameMap, capsMap, ipBans, versionMap };
        }
        long now = _context.clock().now();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(logFile)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] parts = PIPE_SPLIT.split(line);
                if (parts.length < 3) continue;
                String hash = parts[1].trim();
                String ipField = parts[2].trim();
                // Extract IP:PORT and hostname from "IP:PORT (hostname)" format
                String ipPort = ipField;
                String hostname = null;
                if (!ipPort.isEmpty() && ipField.contains(" (") && ipField.endsWith(")")) {
                    ipPort = ipField.substring(0, ipField.indexOf(" ("));
                    hostname = ipField.substring(ipField.indexOf(" (") + 2, ipField.length() - 1);
                }
                // Build ipMap and hostnameMap for hash-based entries
                if (!hash.isEmpty() && !hash.equals("UNKNOWN")) {
                    if (!ipPort.isEmpty() && ipMap.get(hash) == null) {
                        ipMap.put(hash, ipPort);
                    }
                    if (hostname != null && !hostname.isEmpty() && hostnameMap.get(hash) == null) {
                        hostnameMap.put(hash, hostname);
                    }
                    // Read caps from 6th field (available in new entries only)
                    if (parts.length >= 6 && capsMap.get(hash) == null) {
                        String caps = parts[5].trim();
                        if (!caps.isEmpty()) {
                            capsMap.put(hash, caps);
                        }
                    }
                    // Read version from 7th field
                    if (parts.length >= 7 && versionMap.get(hash) == null) {
                        String version = parts[6].trim();
                        if (!version.isEmpty() && !version.equals("null") && !version.equals("unknown")) {
                            versionMap.put(hash, version);
                        }
                    }
                }
                // Build ipOnlyBans for entries with no hash
                if (parts.length >= 4 && (hash.isEmpty() || hash.equals("UNKNOWN")) && !ipPort.isEmpty()) {
                    String reason = parts[3].trim();
                    String durationStr = parts.length >= 5 ? parts[4].trim() : "";
                    long expires = parseDuration(durationStr, now);
                    if (expires > now) {
                        String ipOnly = extractIP(ipPort);
                        if (!seenIPs.contains(ipOnly)) {
                            seenIPs.add(ipOnly);
                            ipBans.add(new IPBanEntry(ipPort, hostname, reason, expires, durationStr));
                        }
                    }
                }
            }
        } catch (IOException e) { /* ignored */ }
        return new Object[] { ipMap, hostnameMap, capsMap, ipBans, versionMap };
    }

    /**
     * Extract IP address from IP:PORT format.
     * Handles both IPv4 (1.2.3.4:port) and IPv6 ([::1]:port) formats.
     *
     * @param ipPort the address string to split, may be null or empty
     * @return the IP without the port; the input unchanged when null, empty or portless
     */
    static String extractIP(String ipPort) {
        if (ipPort == null || ipPort.isEmpty()) {
            return ipPort;
        }
        // Handle IPv6 [addr]:port format
        if (ipPort.startsWith("[")) {
            int bracketEnd = ipPort.indexOf("]");
            if (bracketEnd > 0) {
                return ipPort.substring(1, bracketEnd);
            }
        }
        // Handle IPv4 addr:port format
        // IPv6 addresses without brackets are treated as complete addresses (no port)
        int colonCount = 0;
        for (int i = 0; i < ipPort.length(); i++) {
            if (ipPort.charAt(i) == ':') colonCount++;
        }

        // If IPv6 (multiple colons without brackets), it's a complete address
        if (colonCount >= 2) {
            return ipPort;
        }

        // IPv4 with port (single colon)
        int lastColonIdx = ipPort.lastIndexOf(':');
        if (lastColonIdx > 0) {
            return ipPort.substring(0, lastColonIdx);
        }

        // No port found, return as-is
        return ipPort;
    }

    /**
     * Extract port from IP:PORT format.
     * Returns null if no port is found.
     *
     * @param ipPort the address string to split, may be null or empty
     * @return the port as a string, or null when no port is present
     */
    static String extractPort(String ipPort) {
        if (ipPort == null || ipPort.isEmpty()) {
            return null;
        }
        // Handle IPv6 [addr]:port format
        if (ipPort.startsWith("[")) {
            int bracketEnd = ipPort.indexOf("]");
            if (bracketEnd > 0 && ipPort.length() > bracketEnd + 1 && ipPort.charAt(bracketEnd + 1) == ':') {
                return ipPort.substring(bracketEnd + 2);
            }
        }
        // Handle IPv4 addr:port format
        // IPv6 addresses without brackets are treated as complete addresses (no port)
        int colonCount = 0;
        for (int i = 0; i < ipPort.length(); i++) {
            if (ipPort.charAt(i) == ':') colonCount++;
        }

        // If IPv6 (multiple colons without brackets), it's a complete address
        if (colonCount >= 2) {
            return null;
        }

        // IPv4 with port (single colon)
        int lastColonIdx = ipPort.lastIndexOf(':');
        if (lastColonIdx > 0 && lastColonIdx < ipPort.length() - 1) {
            return ipPort.substring(lastColonIdx + 1);
        }

        // No port found
        return null;
    }

    /**
     * The address a "Blocklist (1.2.3.4:5678)" or "Sybil Analysis (...)" cause
     * carries in its parentheses, if the text there is an address at all. The
     * parentheses have also carried a peer's own version string - up to 255
     * bytes of whatever it chose to write - and that text was cut out of the
     * cause and rendered into the table's IP column as if it were an address.
     *
     * @param reason the ban cause as logged, null yields nothing
     * @return the address from the parentheses, or null if it is not one
     * @since 0.9.72+
     */
    static String causeAddress(String reason) {
        if (reason == null) {return null;}
        int parenOpen = reason.indexOf('(');
        int parenClose = reason.indexOf(')');
        if (parenOpen <= 0 || parenClose <= parenOpen) {return null;}
        String value = reason.substring(parenOpen + 1, parenClose);
        return isAddress(value) ? value : null;
    }

    /**
     * Is this text an IP address, with or without a port? The bracketed IPv6
     * form and the plain IPv4 one are both accepted; a version string, a peer's
     * family name or anything else that is not an address is not.
     *
     * @param s the text to check, null or empty is not an address
     * @return true if the text can be used as an address
     * @since 0.9.72+
     */
    static boolean isAddress(String s) {
        if (s == null || s.isEmpty()) {return false;}
        String ip = extractIP(s);
        if (ip == null || !Addresses.isIPAddress(ip)) {return false;}
        String port = extractPort(s);
        if (port == null) {return true;}
        if (port.length() > 5) {return false;}
        int p = 0;
        for (int i = 0; i < port.length(); i++) {
            char c = port.charAt(i);
            if (c < '0' || c > '9') {return false;}
            p = p * 10 + (c - '0');
        }
        return p > 0 && p <= MAX_PORT;
    }

    /**
     * Is this a ban on a hash rather than on a router? The Blocklist bans
     * addresses by hash and writes "Hash" into the cause, and those entries do
     * not belong in the router table or its count. The hash-pattern detector's
     * own bans are the exception: its cause holds "hash" as well, and the peer
     * it banned is a router, so that one stays in.
     *
     * @param cause the ban cause as logged, not null
     * @return true if the entry is a hash ban to leave out of the router table
     * @since 0.9.72+
     */
    static boolean isHashBan(String cause) {
        String lc = cause.toLowerCase(Locale.US);
        return lc.contains("hash") && !lc.contains("hashpatterndetector");
    }

    /**
     * Is the banned router a floodfill? The cause says so in words, or the
     * router's capabilities carry the floodfill flag.
     *
     * @param cause the ban cause as logged, not null
     * @param caps the router's capability string, null when it is not known
     * @return true if the banned router is a floodfill
     * @since 0.9.72+
     */
    static boolean isFloodfillBan(String cause, String caps) {
        return cause.toLowerCase(Locale.US).contains("floodfill") ||
               (caps != null && caps.indexOf('f') >= 0);
    }

    /**
     * A GeoIP country code in the form the flag image and the table's sort key
     * use: lower-case ASCII, or {@code xx} when the lookup knows nothing. The
     * fold names a locale because the codes are ASCII - under a Turkish default
     * locale Israel's {@code IL} folds to a dotless ı, and the flag image and
     * the country name both miss it.
     *
     * @param geoCountry the code from the lookup, null or empty for no answer
     * @return the code to render, never null
     * @since 0.9.72+
     */
    static String normalizeCountryCode(String geoCountry) {
        if (geoCountry == null || geoCountry.isEmpty() || "xx".equals(geoCountry)) {return "xx";}
        return geoCountry.toLowerCase(Locale.US);
    }

    /**
     * Escapes a value for the ban table. Most of the table's columns carry text
     * a peer wrote about itself - its version, its capabilities, a hostname, an
     * address taken out of a ban cause - and the console's CSP stops a script
     * in that text but not markup, so it has to be escaped where it is written
     * out.
     *
     * @param s the value, null renders as nothing
     * @return the value safe for a cell or a quoted attribute
     * @since 0.9.72+
     */
    private static String esc(String s) {
        return s == null ? "" : DataHelper.escapeHTML(s);
    }

    /**
     * Parse duration string to expiration timestamp.
     *
     * @param durationStr the duration to parse, e.g. "7d", "12h", "30m", "500ms" or "FOREVER"
     * @param now the current time in ms the expiration is based on
     * @return the expiration time in ms; FOREVER means 180 days out, an
     *         unparseable or unit-less value means now
     */
    private long parseDuration(String durationStr, long now) {
        if (durationStr.equals("FOREVER")) {
            return now + 180L * 24 * 60 * 60 * 1000;
        }
        try {
            if (durationStr.endsWith("d")) {
                return now + Long.parseLong(durationStr.substring(0, durationStr.length() - 1)) * 24 * 60 * 60 * 1000;
            } else if (durationStr.endsWith("h")) {
                return now + Long.parseLong(durationStr.substring(0, durationStr.length() - 1)) * 60 * 60 * 1000;
            } else if (durationStr.endsWith("m")) {
                return now + Long.parseLong(durationStr.substring(0, durationStr.length() - 1)) * 60 * 1000;
            } else if (durationStr.endsWith("ms")) {
                return now + Long.parseLong(durationStr.substring(0, durationStr.length() - 2));
            }
        } catch (NumberFormatException e) { /* ignored */ }
        return now;
    }

    /**
     * Entry for IP-only bans from sessionbans.txt.
     */
    private static class IPBanEntry {
        /** The banned address. */
        final String ip;
        /** The resolved hostname, if any. */
        final String hostname;
        /** The reason for the ban. */
        final String reason;
        /** When the ban expires. */
        final long expires;
        /** The ban duration for display. */
        final String durationStr;

        /**
         * One row of the ban table.
         *
         * @param ip the banned address
         * @param hostname the resolved hostname, null if none
         * @param reason the reason for the ban
         * @param expires when the ban expires
         * @param durationStr the ban duration for display
         */
        IPBanEntry(String ip, String hostname, String reason, long expires, String durationStr) {
            this.ip = ip;
            this.hostname = hostname;
            this.reason = reason;
            this.expires = expires;
            this.durationStr = durationStr;
        }
    }

    /**
     * Render the compact banlist HTML table.
     *
     * @param out the writer to render to
     * @throws IOException if writing fails
     * @since 0.9.59+
     */
    public void renderBanlistCompact(Writer out) throws IOException {
        renderBanlist(out, false);
    }

    /**
     * Count active bans of floodfill-capable peers, matching the banFF
     * classification used by {@link #renderBanlist(Writer, boolean)}:
     * caps from the session log or netdb, or a "floodfill" cause.
     *
     * @return how many active bans are floodfills
     * @since 0.9.71+
     */
    public int countBannedFloodfills() {
        Object[] sessionBans = readSessionBans();
        @SuppressWarnings("unchecked")
        Map<String, String> capsMap = (Map<String, String>) sessionBans[2];
        int count = 0;
        long now = _context.clock().now();
        for (Map.Entry<Hash, Banlist.Entry> e : _context.banlist().getEntries().entrySet()) {
            Hash key = e.getKey();
            Banlist.Entry entry = e.getValue();
            long expires = entry.expireOn - now;
            if (expires <= 0 || key.equals(Hash.FAKE_HASH) || entry.cause == null ||
                isHashBan(entry.cause)) {
                continue;
            }
            String b64 = key.toBase64();
            String caps = capsMap.get(b64);
            if (caps == null) {caps = getRouterCaps(key);}
            if (isFloodfillBan(entry.cause, caps)) {count++;}
        }
        return count;
    }

    /**
     * Render only the {@code <tbody id=sessionBanlist>} for the contentonly
     * fragment mode, with a data-key per row for the worker-side row diff.
     * The full page keeps the wrapper div, thead, and tfoot.
     *
     * @param out the writer to render to
     * @throws IOException if writing fails
     * @since 0.9.70+
     */
    public void renderBanlistFragment(Writer out) throws IOException {
        renderBanlist(out, true);
    }

    /**
     * Render the compact banlist table; both {@link #renderBanlistCompact(Writer)} and
     * {@link #renderBanlistFragment(Writer)} delegate here.
     *
     * @param out the writer to render to
     * @param fragmentKeys when true, emit only the tbody with data-key rows
     * @throws IOException if writing fails
     */
    private void renderBanlist(Writer out, boolean fragmentKeys) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        Map<Hash, Banlist.Entry> entries = new TreeMap<>(new HashComparator());
        Object[] sessionBans = readSessionBans();
        @SuppressWarnings("unchecked")
        Map<String, String> ipMap = (Map<String, String>) sessionBans[0];
        @SuppressWarnings("unchecked")
        Map<String, String> hostnameMap = (Map<String, String>) sessionBans[1];
        @SuppressWarnings("unchecked")
        List<IPBanEntry> ipOnlyBans = (List<IPBanEntry>) sessionBans[3];
        @SuppressWarnings("unchecked")
        Map<String, String> capsMap = sessionBans.length > 3 ? (Map<String, String>) sessionBans[2] : new HashMap<String, String>();
        @SuppressWarnings("unchecked")
        Map<String, String> versionMap = sessionBans.length > 4 ? (Map<String, String>) sessionBans[4] : new HashMap<String, String>();

        entries.putAll(_context.banlist().getEntries());
        if (entries.isEmpty() && ipOnlyBans.isEmpty()) {
            if (fragmentKeys) {buf.append("<tbody id=sessionBanlist>\n</tbody>\n");}
            else {buf.append("<p class=infohelp><i>").append(_t("No bans currently active")).append("</i></p>\n");}
            out.append(buf);
            return;
        }

        if (fragmentKeys) {buf.append("<tbody id=sessionBanlist>\n");}
        else {
        // Column order: Country, Router, Caps, Version, IP, Port, Host, Reason, Expiry
        buf.append("<div class=tablewrap id=sessionBanned>\n<table id=sbans>\n<thead><tr><th class=country>")
           .append(_t("Country"))
           .append("</th><th class=hash>")
           .append(_t("Router"))
           .append("</th><th class=caps>")
           .append(_t("Caps"))
           .append("</th><th class=routerversion>")
           .append(_t("Version"))
           .append("</th><th class=ip>")
           .append(_t("IP Address"))
           .append("</th><th class=port data-sort-method=number>")
           .append(_t("Port"))
           .append("</th>")
           .append("<th class=hostname data-sort-method=string data-sort-caseinsensitive>")
           .append(_t("Host")).append("</th>")
           .append("<th class=reason>")
           .append(_t("Reason"))
           .append("</th><th class=expires data-sort-method=number data-sort-direction=ascending>")
           .append(_t("Expiry"))
           .append("</th></tr></thead>\n<tbody id=sessionBanlist>\n");
        }
        int tempBanned = 0;
        boolean stream = entries.size() + ipOnlyBans.size() > MAX_BEFORE_STREAMING;
        if (stream) {
            out.append(buf);
            out.flush();
            buf.setLength(0);
        }
        int rowsSinceFlush = 0;
        long now = _context.clock().now();

        // Render hash-based bans
        for (Map.Entry<Hash, Banlist.Entry> e : entries.entrySet()) {
            Hash key = e.getKey();
            Banlist.Entry entry = e.getValue();
            long expires = entry.expireOn - now;
            String expireString = DataHelper.formatDuration2(expires);
            if (expires <= 0 || key.equals(Hash.FAKE_HASH) || entry.cause == null ||
                isHashBan(entry.cause)) {
                continue;
            }
            String b64 = key.toBase64();
            String caps = capsMap.get(b64);
            if (caps == null) {
                caps = getRouterCaps(key);
            }
            buf.append("<tr");
            if (fragmentKeys) {buf.append(" data-key=\"").append(b64, 0, KEY_LEN).append("\"");}
            if (isFloodfillBan(entry.cause, caps)) {buf.append(" class=\"banFF\"");}
            buf.append(">");
            String reason = _t(entry.cause, entry.causeCode).trim();
            // Get IP from hash if not in reason
            String ip = null;
            String port = null;
            byte[] ipBytes = TransportImpl.getIP(key);
            if (ipBytes != null) {
                ip = Addresses.toString(ipBytes);
            }
            String lcReason = reason.toLowerCase(Locale.US);
            String extractedIP = null;
            if (lcReason.startsWith("blocklist")) {
                extractedIP = causeAddress(reason);
                reason = "Blocklist";
            } else if (lcReason.startsWith("sybil analysis")) {
                extractedIP = causeAddress(reason);
                reason = "Sybil Analysis";
            }
            // Always try to get IP:PORT from sessionbans log first, as it contains port info
            String ipPort = ipMap.get(b64);
            if (ipPort != null) {
                ip = extractIP(ipPort);
                port = extractPort(ipPort);
             } else {
                 // Debug: Check if hash exists in banlist but not in sessionbans
                 if (_context.logManager().getLog(BanlistRenderer.class).shouldLog(Log.DEBUG)) {
                     _context.logManager().getLog(BanlistRenderer.class).debug(
                         "Hash not found in ipMap: " + b64.substring(0, 8));
                 }
             }
            // If still no IP, try extractedIP from reason
            if ((ip == null || ip.isEmpty()) && extractedIP != null && !extractedIP.isEmpty()) {
                ip = extractIP(extractedIP);
                port = extractPort(extractedIP);
            }
            // Fallback to TransportImpl IP if still no IP
            if (ip == null || ip.isEmpty()) {
                ip = Addresses.toString(ipBytes);
            }
            if (ip == null || ip.equalsIgnoreCase("null") || ip.equalsIgnoreCase("unknown")) {
                ip = "";
            }
            // First try to get hostname from log (already includes IP and rdns), fallback to reverse lookup
            String hostname = hostnameMap.get(b64);
            if (hostname == null && ip != null && !ip.isEmpty()) {
                hostname = reverseLookup(ip);
            }
            // GeoIP lookup for country flag - try hash first, then IP
            String countryCode = "xx";
            String geoCountry = _context.commSystem().getCountry(key);
            if (geoCountry != null) {countryCode = normalizeCountryCode(geoCountry);}
            // Fallback to IP lookup if hash lookup failed or returned "xx"
            if (("xx".equals(countryCode) || countryCode.isEmpty()) && ip != null && !ip.isEmpty()) {
                // Check if ipPort was actually in the map (for debugging)
                String ipPortDebug = ipMap.get(b64);
                if (_context.logManager().getLog(BanlistRenderer.class).shouldLog(Log.DEBUG)) {
                    _context.logManager().getLog(BanlistRenderer.class).debug(
                        "GeoIP lookup for hash " + b64.substring(0, 8) +
                        ": ipPort=" + ipPortDebug + ", extractedIP=" + ip +
                        ", ipFromMap=" + (ipMap.get(b64) != null ? "yes" : "no"));
                }
                // Queue the IP for GeoIP lookup if not already in database
                byte[] geoIpBytes = Addresses.getIPOnly(ip);
                if (geoIpBytes != null) {
                    _context.commSystem().queueLookup(geoIpBytes);
                }
                String ipGeoCountry = _context.commSystem().getCountry(ip);
                countryCode = normalizeCountryCode(ipGeoCountry);
                if ("xx".equals(countryCode) &&
                    _context.logManager().getLog(BanlistRenderer.class).shouldLog(Log.DEBUG)) {
                    _context.logManager().getLog(BanlistRenderer.class).debug(
                        "GeoIP lookup failed for IP: " + ip + ", geoCountry=" + ipGeoCountry);
                }
            }
            // Final fallback to xx if unknown
            if ("unknown".equals(countryCode)) {
                countryCode = "xx";
            }

            String countryName =  _context.commSystem().getCountryName(countryCode);
            // Get router version from sessionbans log first, then NetDB or reason
            String routerVersion = versionMap.get(b64);
            if (routerVersion == null) {
                routerVersion = getRouterVersion(key, reason);
            }
            String cleanedReason = cleanReason(reason, routerVersion);
            buf.append("<td class=country data-sort=").append(countryCode).append(">")
               .append("<img width=28 height=21 title=\"").append(esc(countryName))
               .append("\" src=\"/flags.jsp?c=").append(countryCode).append("\" loading=lazy>")
               .append("</td><td class=hash>")
               .append("<span class=b64>").append(b64).append("</span>")
               .append("</td><td class=caps>").append(esc(caps))
               .append("</td><td class=routerversion>").append(esc(routerVersion))
               .append("</td><td class=ip>")
               .append(esc(ip))
               .append("</td><td class=port")
               .append(port != null && !port.equals("0") ? " data-sort=" + esc(port) : "").append(">")
               .append(port != null && !port.equals("0") ? esc(port) : "")
               .append("</td>")
               .append("<td class=hostname>")
               .append(hostname != null && !hostname.isEmpty() && !"unknown".equals(hostname) ? esc(hostname) : "")
               .append("</td>")
               .append("<td class=reason>")
               .append(esc(cleanedReason))
               .append("</td><td class=expires data-sort=").append(expires).append(">")
               .append(expireString)
               .append("</td></tr>\n");
            tempBanned++;
            if (stream && ++rowsSinceFlush >= STREAM_BATCH) {
                out.append(buf);
                out.flush();
                buf.setLength(0);
                rowsSinceFlush = 0;
            }
        }

        // Render IP-only bans
        for (IPBanEntry ipBan : ipOnlyBans) {
            long msLeft = ipBan.expires - now;
            String expireString = DataHelper.formatDuration2(msLeft);
            // Extract IP and port from the IP field
            String ip = extractIP(ipBan.ip);
            String port = extractPort(ipBan.ip);
            // Use hostname from log if available, fallback to reverse lookup
            String hostname = ipBan.hostname;
            if (hostname == null) {
                hostname = reverseLookup(ip);
            }
             // GeoIP lookup for country flag - IP only (no hash available for IP-only bans)
             String countryCode = "xx";
             if (ip != null && !ip.isEmpty()) {
                 // Queue the IP for GeoIP lookup if not already in database
                 byte[] geoIpBytes = Addresses.getIPOnly(ip);
                 if (geoIpBytes != null) {
                     _context.commSystem().queueLookup(geoIpBytes);
                 }
                 String geoCountry = _context.commSystem().getCountry(ip);
                 if (geoCountry != null) {countryCode = normalizeCountryCode(geoCountry);}
             }
            if ("unknown".equals(countryCode)) {
                countryCode = "xx";
            }
            String countryName =  _context.commSystem().getCountryName(countryCode);
            buf.append("<tr");
            if (fragmentKeys) {buf.append(" data-key=\"").append(esc(ip)).append("\"");}
            buf.append(" class=ipOnly>")
               .append("<td class=country data-sort=").append(countryCode).append(">")
               .append("<img width=28 height=21 title=\"").append(esc(countryName))
               .append("\" src=\"/flags.jsp?c=").append(countryCode).append("\" loading=lazy>")
               .append("</td>").append("<td class=hash></td>")
               .append("<td class=caps></td>")
               .append("<td class=routerversion></td>")
               .append("<td class=ip>")
               .append(esc(ip))
               .append("</td><td class=port")
               .append(port != null && !port.equals("0") ? " data-sort=" + esc(port) : "").append(">")
               .append(port != null && !port.equals("0") ? esc(port) : "")
               .append("</td>")
               .append("<td class=hostname>")
               .append(hostname != null && !hostname.isEmpty() && !"unknown".equals(hostname) ? esc(hostname) : "")
               .append("</td>")
               .append("<td class=reason>")
               .append(ipBan.reason.isEmpty() ? "IP Ban" : esc(ipBan.reason))
               .append("</td><td class=expires data-sort=").append(msLeft).append(">")
               .append(expireString)
               .append("</td></tr>\n");
            tempBanned++;
            if (stream && ++rowsSinceFlush >= STREAM_BATCH) {
                out.append(buf);
                out.flush();
                buf.setLength(0);
                rowsSinceFlush = 0;
            }
        }

        if (fragmentKeys) {buf.append("</tbody>\n");}
        else {buf.append("</tbody>\n<tfoot id=sessionBanlistFooter><tr><th colspan=")
                   .append("9")
                   .append(">")
                   .append(_t("Total session-only bans"))
                   .append(": ").append(tempBanned)
                   .append("</th></tr></tfoot>\n</table>\n</div>\n");}
        out.append(buf);
        out.flush();
    }

    /**
     * Translate a string.
     *
     * @param s the string to translate
     * @return the translated string
     */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /**
     * Translate a string with a parameter
     * This is a lot more expensive than _t(s), so use sparingly.
     *
     * @param s string to be translated containing {0}
     * The {0} will be replaced by the parameter.
     * Single quotes must be doubled, i.e. ' -&gt; '' in the string.
     * @param o parameter, not translated.
     * To translate parameter also, use _t("foo {0} bar", _t("baz"))
     * Do not double the single quotes in the parameter.
     * Use autoboxing to call with ints, longs, floats, etc.
     *
     * @return the translated string with the first {0} replaced by the parameter
     */
    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    /**
     * Router version for a banned hash, from NetDB or parsed out of the ban reason.
     *
     * @param hash the router hash to look up, null yields no version
     * @param reason the ban reason string, searched for a version pattern
     * @return the version string, or null if neither source carries one
     */
    private String getRouterVersion(Hash hash, String reason) {
        if (hash == null) {
            return null;
        }
        // First try NetDB
        try {
            NetworkDatabaseFacade netDb = _context.netDb();
            if (netDb != null) {
                RouterInfo ri = netDb.lookupRouterInfoLocally(hash);
                if (ri != null) {
                    return ri.getVersion();
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }
        // Fallback: extract from reason string (e.g., "LU Router (0.9.56)" or "0.9.57 / LU")
        if (reason != null && !reason.isEmpty()) {
            Matcher m = VERSION_EXTRACT.matcher(reason);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * Get router capabilities string from NetDB.
     *
     * @param hash the router hash to look up, null yields the empty string
     * @return caps string or empty string
     */
    private String getRouterCaps(Hash hash) {
        if (hash == null) return "";
        try {
            NetworkDatabaseFacade netDb = _context.netDb();
            if (netDb != null) {
                RouterInfo ri = netDb.lookupRouterInfoLocally(hash);
                if (ri != null) {
                    String caps = ri.getCapabilities();
                    return caps != null ? caps : "";
                }
            }
        } catch (Exception e) { /* ignore */ }
        return "";
    }

    /**
     * Rewrites a ban reason for display.
     *
     * @param reason the reason as logged
     * @param routerVersion the version string extracted from it
     * @return the reason cleaned for display
     */
    private String cleanReason(String reason, String routerVersion) {
        if (reason == null) {
            return reason;
        }
        String cleaned = reason;
        // First, try to remove version patterns like "(0.9.57 / LU)" or "(0.9.57)"
cleaned = VERSION_LU_SUFFIX.matcher(cleaned).replaceAll("");
        cleaned = VERSION_SUFFIX.matcher(cleaned).replaceAll("");
        cleaned = VERSION_PREFIX.matcher(cleaned).replaceAll("");
        // If we have routerVersion from NetDB, use that too
        if (routerVersion != null) {
            cleaned = cleaned.replaceAll("\\s*\\(" + Pattern.quote(routerVersion) + "\\)\\s*$", "");
            cleaned = cleaned.replaceAll("\\s*/\\s*" + Pattern.quote(routerVersion) + "\\s*$", "");
        }
        return cleaned.isEmpty() ? reason : cleaned;
    }
}
