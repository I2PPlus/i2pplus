package net.i2p.router.web.helpers;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
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
    private final RouterContext _context;
    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    private static final Pattern IP_HOSTNAME_NORMALIZE = Pattern.compile("[.:]");
    private static final Pattern PIPE_SPLIT = Pattern.compile("\\s*\\|\\s*");
    private static final Pattern VERSION_LU_SUFFIX = Pattern.compile("\\s*\\(0\\.9\\.\\d+(?:\\.\\d+)?(?:-\\d+)?\\s*/\\s*LU\\)\\s*$");
    private static final Pattern VERSION_SUFFIX = Pattern.compile("\\s*\\(0\\.9\\.\\d+(?:\\.\\d+)?(?:-\\d+)?\\)\\s*$");
    private static final Pattern VERSION_PREFIX = Pattern.compile("\\s*/\\s*0\\.9\\.\\d+(?:\\.\\d+)?(?:-\\d+)?\\s*$");
    private static final Pattern VERSION_EXTRACT = Pattern.compile("(?:^|\\()(\\d+\\.\\d+(?:\\.\\d+)?(?:-\\d+)?)");

    public BanlistRenderer(RouterContext context) {
        _context = context;
    }

    private boolean enableReverseLookups() {
        return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);
    }

    private String reverseLookup(String ip) {
        if (ip == null || ip.isEmpty()) return null;
        // Use fast local lookup — never blocks on network DNS
        String hostname = _context.commSystem().getLocalHostName(ip);
        // If result is null, "unknown", or still looks like an IP, return null
        if (hostname == null || hostname.isEmpty() || "unknown".equals(hostname)) {
            return null;
        }
        // Check if the hostname is just the IP in different form
        String normalizedIp = IP_HOSTNAME_NORMALIZE.matcher(ip.toLowerCase()).replaceAll("");
        String normalizedHostname = IP_HOSTNAME_NORMALIZE.matcher(hostname.toLowerCase()).replaceAll("");
        if (normalizedHostname.equals(normalizedIp) || normalizedHostname.contains(normalizedIp)) {
            return null;
        }
        return hostname;
    }

    /**
     * Read sessionbans.txt once and build all three data structures in a single pass.
     * Replaces the three separate readSessionBans* methods that each opened the file independently.
     *
     * @return array of [Map<String,String> ipMap, Map<String,String> hostnameMap, List<IPBanEntry> ipOnlyBans]
     */
    private Object[] readSessionBans() {
        Map<String, String> ipMap = new HashMap<>();
        Map<String, String> hostnameMap = new HashMap<>();
        Map<String, String> capsMap = new HashMap<>();
        List<IPBanEntry> ipBans = new ArrayList<>();
        Set<String> seenIPs = new HashSet<>();
        File logDir = new File(_context.getRouterDir(), "sessionbans");
        File logFile = new File(logDir, "sessionbans.txt");
        if (!logFile.exists()) {
            return new Object[] { ipMap, hostnameMap, capsMap, ipBans };
        }
        long now = _context.clock().now();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
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
        return new Object[] { ipMap, hostnameMap, capsMap, ipBans };
    }

    /**
     * Extract IP address from IP:PORT format.
     * Handles both IPv4 (1.2.3.4:port) and IPv6 ([::1]:port) formats.
     */
    private String extractIP(String ipPort) {
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
     */
    private String extractPort(String ipPort) {
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
     * Parse duration string to expiration timestamp.
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
     * Entry for IP-only bans from sessionbans.txt
     */
    private static class IPBanEntry {
        final String ip;
        final String hostname;
        final String reason;
        final long expires;
        final String durationStr;

        IPBanEntry(String ip, String hostname, String reason, long expires, String durationStr) {
            this.ip = ip;
            this.hostname = hostname;
            this.reason = reason;
            this.expires = expires;
            this.durationStr = durationStr;
        }
    }

    /**
     *  Render the compact banlist HTML table.
     *  @since 0.9.59+
     */
    public void renderBanlistCompact(Writer out) throws IOException {
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

        entries.putAll(_context.banlist().getEntries());
        if (entries.isEmpty() && ipOnlyBans.isEmpty()) {
            buf.append("<p class=infohelp><i>").append(_t("No bans currently active")).append("</i></p>\n");
            out.append(buf);
            return;
        }

        // Column order: Country, Router, Caps, Version, IP, Port, Host, Reason, Expiry
        buf.append("<div class=tablewrap id=sessionBanned>\n<table id=sbans>\n<thead><tr><th class=country>")
           .append(_t("Country"))
           .append("</th><th class=hash data-sort-use-group=true>")
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
           .append("<th class=hostname>").append(_t("Host")).append("</th>");
        buf.append("<th class=reason>")
           .append(_t("Reason"))
           .append("</th><th class=expires data-sort-method=number data-sort-direction=ascending>")
           .append(_t("Expiry"))
           .append("</th></tr></thead>\n<tbody id=sessionBanlist>\n");
        int tempBanned = 0;

        // Render hash-based bans
        for (Map.Entry<Hash, Banlist.Entry> e : entries.entrySet()) {
            Hash key = e.getKey();
            Banlist.Entry entry = e.getValue();
            long expires = entry.expireOn - _context.clock().now();
            String expireString = DataHelper.formatDuration2(expires);
            if (expires <= 0 || key.equals(Hash.FAKE_HASH) || entry.cause == null ||
                (entry.cause.toLowerCase().contains("hash") &&
                 !entry.cause.toLowerCase().contains("hashpatterndetector"))) {
                continue;
            }
            String caps = capsMap.get(key.toBase64());
            if (caps == null) {
                caps = getRouterCaps(key);
            }
            buf.append("<tr");
            if (entry.cause.toLowerCase().contains("floodfill") ||
                (caps != null && caps.indexOf('f') >= 0)) {
                buf.append(" class=\"banFF\"");
            }
            buf.append(">");
            String reason = _t(entry.cause, entry.causeCode).trim();
            // Get IP from hash if not in reason
            String ip = null;
            String port = null;
            byte[] ipBytes = TransportImpl.getIP(key);
            if (ipBytes != null) {
                ip = Addresses.toString(ipBytes);
            }
            String lcReason = reason.toLowerCase();
            String extractedIP = null;
            if (lcReason.startsWith("blocklist")) {
                int parenOpen = reason.indexOf('(');
                int parenClose = reason.indexOf(')');
                if (parenOpen > 0 && parenClose > parenOpen) {
                    extractedIP = reason.substring(parenOpen + 1, parenClose);
                }
                reason = "Blocklist";
            } else if (lcReason.startsWith("sybil analysis")) {
                int parenOpen = reason.indexOf('(');
                int parenClose = reason.indexOf(')');
                if (parenOpen > 0 && parenClose > parenOpen) {
                    extractedIP = reason.substring(parenOpen + 1, parenClose);
                }
                reason = "Sybil Analysis";
            }
            // Always try to get IP:PORT from sessionbans log first, as it contains port info
            String ipPort = ipMap.get(key.toBase64());
            if (ipPort != null) {
                ip = extractIP(ipPort);
                port = extractPort(ipPort);
             } else {
                 // Debug: Check if hash exists in banlist but not in sessionbans
                 if (_context.logManager().getLog(BanlistRenderer.class).shouldLog(Log.DEBUG)) {
                     _context.logManager().getLog(BanlistRenderer.class).debug(
                         "Hash not found in ipMap: " + key.toBase64().substring(0, 8));
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
            String hostname = hostnameMap.get(key.toBase64());
            if (hostname == null && ip != null && !ip.isEmpty()) {
                hostname = reverseLookup(ip);
            }
            // GeoIP lookup for country flag - try hash first, then IP
            String countryCode = "xx";
            String geoCountry = _context.commSystem().getCountry(key);
            if (geoCountry != null && !geoCountry.isEmpty() && !"xx".equals(geoCountry)) {
                countryCode = geoCountry.toLowerCase();
            }
            // Fallback to IP lookup if hash lookup failed or returned "xx"
            if (("xx".equals(countryCode) || countryCode.isEmpty()) && ip != null && !ip.isEmpty()) {
                // Check if ipPort was actually in the map (for debugging)
                String ipPortDebug = ipMap.get(key.toBase64());
                if (_context.logManager().getLog(BanlistRenderer.class).shouldLog(Log.DEBUG)) {
                    _context.logManager().getLog(BanlistRenderer.class).debug(
                        "GeoIP lookup for hash " + key.toBase64().substring(0, 8) +
                        ": ipPort=" + ipPortDebug + ", extractedIP=" + ip +
                        ", ipFromMap=" + (ipMap.get(key.toBase64()) != null ? "yes" : "no"));
                }
                // Queue the IP for GeoIP lookup if not already in database
                byte[] geoIpBytes = Addresses.getIPOnly(ip);
                if (geoIpBytes != null) {
                    _context.commSystem().queueLookup(geoIpBytes);
                }
                String ipGeoCountry = _context.commSystem().getCountry(ip);
                if (ipGeoCountry != null && !ipGeoCountry.isEmpty() && !"xx".equals(ipGeoCountry)) {
                    countryCode = ipGeoCountry.toLowerCase();
                } else if (_context.logManager().getLog(BanlistRenderer.class).shouldLog(Log.DEBUG)) {
                    _context.logManager().getLog(BanlistRenderer.class).debug(
                        "GeoIP lookup failed for IP: " + ip + ", geoCountry=" + ipGeoCountry);
                }
            }
            // Final fallback to xx if unknown
            if ("unknown".equals(countryCode)) {
                countryCode = "xx";
            }

            String countryName =  _context.commSystem().getCountryName(countryCode);
            // Get router version from NetDB or reason, then clean reason
            String routerVersion = getRouterVersion(key, reason);
            String cleanedReason = cleanReason(reason, routerVersion);
            buf.append("<td class=country data-sort=").append(countryCode).append(">")
               .append("<img width=28 height=21 title=\"").append(countryName)
               .append("\" src=\"/flags.jsp?c=").append(countryCode).append("\">")
               .append("</td><td class=hash>")
               .append("<span class=b64>").append(key.toBase64()).append("</span>")
               .append("</td><td class=caps>").append(caps != null ? caps : "")
               .append("</td><td class=routerversion>").append(routerVersion != null ? routerVersion : "")
               .append("</td><td class=ip>")
               .append(ip != null ? ip : "")
               .append("</td><td class=port data-sort=").append(port != null ? port : "0").append(">")
               .append(port != null ? port : "")
               .append("</td>")
               .append("<td class=hostname>")
               .append(hostname != null && !hostname.isEmpty() && !"unknown".equals(hostname) ? hostname : "")
               .append("</td>");
            buf.append("<td class=reason>")
               .append(cleanedReason)
               .append("</td><td class=expires data-sort=").append(expires).append(">")
               .append(expireString)
               .append("</td></tr>\n");
            tempBanned++;
        }

        // Render IP-only bans
        for (IPBanEntry ipBan : ipOnlyBans) {
            String expireString = DataHelper.formatDuration2(ipBan.expires - _context.clock().now());
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
                 if (geoCountry != null && !geoCountry.isEmpty() && !"xx".equals(geoCountry)) {
                     countryCode = geoCountry.toLowerCase();
                 }
             }
            if ("unknown".equals(countryCode)) {
                countryCode = "xx";
            }
            String countryName =  _context.commSystem().getCountryName(countryCode);
            buf.append("<tr class=ipOnly>")
               .append("<td class=country data-sort=").append(countryCode).append(">")
               .append("<img width=28 height=21 title=\"").append(countryName)
               .append("\" src=\"/flags.jsp?c=").append(countryCode).append("\">")
                .append("</td>").append("<td class=hash></td>")
                .append("<td class=caps></td>")
                .append("<td class=routerversion></td>")
                .append("<td class=ip>")
               .append(ip != null ? ip : "")
               .append("</td><td class=port data-sort=").append(port != null ? port : "0").append(">")
               .append(port != null ? port : "")
               .append("</td>")
               .append("<td class=hostname>")
               .append(hostname != null && !hostname.isEmpty() && !"unknown".equals(hostname) ? hostname : "")
               .append("</td>");
            buf.append("<td class=reason>")
               .append(ipBan.reason.isEmpty() ? "IP Ban" : ipBan.reason)
               .append("</td><td class=expires data-sort=").append(ipBan.expires).append(">")
               .append(expireString)
               .append("</td></tr>\n");
            tempBanned++;
        }

        buf.append("</tbody>\n<tfoot id=sessionBanlistFooter><tr><th colspan=")
           .append("9")
           .append(">")
           .append(_t("Total session-only bans"))
           .append(": ").append(tempBanned)
           .append("</th></tr></tfoot>\n</table>\n</div>\n");
        out.append(buf);
        out.flush();
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _t(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

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
