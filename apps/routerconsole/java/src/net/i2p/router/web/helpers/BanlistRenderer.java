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
import java.io.FileReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.Banlist;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.web.Messages;
import net.i2p.util.Addresses;

/**
 * Renders HTML display of banned routers for the router console.
 * Displays banlist entries including expiration times, reasons,
 * and provides management interface for router bans.
 * Moved from Banlist.java for separation of concerns.
 */
class BanlistRenderer {
    private final RouterContext _context;
    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";

    public BanlistRenderer(RouterContext context) {
        _context = context;
    }

    private boolean enableReverseLookups() {
        return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);
    }

    private String reverseLookup(String ip) {
        if (ip == null || ip.isEmpty()) return null;
        String hostname = _context.commSystem().getCanonicalHostName(ip);
        // If result is null, "unknown", or still looks like an IP, return null
        if (hostname == null || hostname.isEmpty() || "unknown".equals(hostname)) {
            return null;
        }
        // Check if the hostname is just the IP in different form
        String normalizedIp = ip.toLowerCase().replaceAll("[.:]", "");
        String normalizedHostname = hostname.toLowerCase().replaceAll("[.:]", "");
        if (normalizedHostname.equals(normalizedIp) || normalizedHostname.contains(normalizedIp)) {
            return null;
        }
        return hostname;
    }

    /**
     * Read sessionbans.txt and build a map of router hash to IP:PORT.
     * Handles format: "IP:PORT (hostname)" or just "IP:PORT"
     */
    private Map<String, String> readSessionBansIPMap() {
        Map<String, String> ipMap = new HashMap<>();
        File logDir = new File(_context.getRouterDir(), "sessionbans");
        File logFile = new File(logDir, "sessionbans.txt");
            if (!logFile.exists()) {
            return ipMap;
        }
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] parts = line.split("\\s*\\|\\s*");
                if (parts.length >= 3) {
                    String hash = parts[1].trim();
                    String ipField = parts[2].trim();
                    // Extract the IP:PORT portion, removing hostname if present
                    String ipPort = ipField;
                    if (!ipPort.isEmpty() && ipField.contains(" (")) {
                        ipPort = ipField.substring(0, ipField.indexOf(" ("));
                    }
                    if (!hash.isEmpty() && !ipPort.isEmpty() && ipMap.get(hash) == null) {
                        ipMap.put(hash, ipPort);
                    }
                }
            }
        } catch (IOException e) {
        }
        return ipMap;
    }

    /**
     * Read sessionbans.txt and build a map of router hash to hostname.
     * Returns hostname if present in "IP (hostname)" format, null otherwise.
     */
    private Map<String, String> readSessionBansHostnameMap() {
        Map<String, String> hostnameMap = new HashMap<>();
        File logDir = new File(_context.getRouterDir(), "sessionbans");
        File logFile = new File(logDir, "sessionbans.txt");
        if (!logFile.exists()) {
            return hostnameMap;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] parts = line.split("\\s*\\|\\s*");
                if (parts.length >= 3) {
                    String hash = parts[1].trim();
                    String ipField = parts[2].trim();
                    // Extract hostname from "IP (hostname)" format
                    String hostname = null;
                    if (!ipField.isEmpty() && ipField.contains(" (") && ipField.endsWith(")")) {
                        hostname = ipField.substring(ipField.indexOf(" (") + 2, ipField.length() - 1);
                    }
                    if (!hash.isEmpty() && hostname != null && !hostname.isEmpty() && hostnameMap.get(hash) == null) {
                        hostnameMap.put(hash, hostname);
                    }
                }
            }
        } catch (IOException e) {
        }
        return hostnameMap;
    }

    /**
     * Read sessionbans.txt and build a list of IP-only bans.
     * Handles format: "IP:PORT (hostname)" or just "IP:PORT"
     */
    private List<IPBanEntry> readSessionBansIPOnly() {
        List<IPBanEntry> ipBans = new ArrayList<>();
        File logDir = new File(_context.getRouterDir(), "sessionbans");
        File logFile = new File(logDir, "sessionbans.txt");
        if (!logFile.exists()) {
            return ipBans;
        }
        long now = _context.clock().now();
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] parts = line.split("\\s*\\|\\s*");
                if (parts.length >= 4) {
                    String hash = parts[1].trim();
                    String ipField = parts[2].trim();
                    String reason = parts[3].trim();
                    String durationStr = parts.length >= 5 ? parts[4].trim() : "";
                    // Extract IP:PORT and hostname from "IP:PORT (hostname)" format
                    String ipPort = ipField;
                    String hostname = null;
                    if (!ipPort.isEmpty() && ipField.contains(" (") && ipField.endsWith(")")) {
                        ipPort = ipField.substring(0, ipField.indexOf(" ("));
                        hostname = ipField.substring(ipField.indexOf(" (") + 2, ipField.length() - 1);
                    }
                    if (hash.isEmpty() && !ipPort.isEmpty()) {
                        long expires = parseDuration(durationStr, now);
                        if (expires > now) {
                            // Store the full IP:PORT string in the ip field
                            ipBans.add(new IPBanEntry(ipPort, hostname, reason, expires, durationStr));
                        }
                    }
                }
            }
        } catch (IOException e) {
        }
        return ipBans;
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
        } catch (NumberFormatException e) {
        }
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

    public void renderStatusHTML(Writer out) throws IOException {
        int bannedCount = _context.banlist().getRouterCount();
        StringBuilder buf = new StringBuilder(bannedCount * 512 + 8192);
        Map<Hash, Banlist.Entry> entries = new TreeMap<Hash, Banlist.Entry>(HashComparator.getInstance());

        entries.putAll(_context.banlist().getEntries());
        if (entries.isEmpty()) {
            buf.append("<i>").append(_t("none").replace("none", "No bans currently active")).append("</i>");
            out.append(buf);
            return;
        }

        buf.append("<ul id=banlist>");

        for (Map.Entry<Hash, Banlist.Entry> e : entries.entrySet()) {
            Hash key = e.getKey();
            Banlist.Entry entry = e.getValue();
            long expires = entry.expireOn-_context.clock().now();
            if (expires <= 0) {continue;}
            if (entries.size() > 300 && (entry.causeCode != null && entry.causeCode.contains("LU")) ||
                (entry.cause != null && entry.cause.contains("LU"))) {
                continue;
            }
            buf.append("<li class=lazy>").append(_context.commSystem().renderPeerHTML(key, false));
            buf.append(' ').append("<span class=banperiod>");
            String expireString = DataHelper.formatDuration2(expires);
            if (key.equals(Hash.FAKE_HASH) || key.equals(Banlist.HASH_ZERORI)) {buf.append(_t("Permanently banned"));}
            else if (expires < 5l*24*60*60*1000) {buf.append(_t("Temporary ban expiring in {0}", expireString));}
            else {buf.append(_t("Banned for {0} / until restart", expireString));}
            buf.append("</span>");
            Set<String> transports = entry.transports;
            if ((transports != null) && (!transports.isEmpty())) {
                buf.append(" on the following transport: ").append(transports);
            }
            if (entry.cause != null) {
                buf.append("<hr>\n");
                if (entry.causeCode != null) {buf.append(_t(entry.cause, entry.causeCode));}
                else {buf.append(_t(entry.cause));}
            }
            if (!key.equals(Hash.FAKE_HASH)) {
                buf.append(" <a href=\"configpeer?peer=").append(key.toBase64())
                   .append("#unsh\" title=\"Unban\">[").append(_t("unban now")).append("]</a>");
            }
            buf.append("</li>\n");
        }
        buf.append("</ul>\n");
        out.append(buf);
        out.flush();
    }

    /* @since 0.9.59+ */
    public void renderBanlistCompact(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        Map<Hash, Banlist.Entry> entries = new TreeMap<Hash, Banlist.Entry>(new HashComparator());
        Map<String, String> ipMap = readSessionBansIPMap();
        Map<String, String> hostnameMap = readSessionBansHostnameMap();
        List<IPBanEntry> ipOnlyBans = readSessionBansIPOnly();

        entries.putAll(_context.banlist().getEntries());
        if (entries.isEmpty() && ipOnlyBans.isEmpty()) {
            buf.append("<p class=infohelp><i>").append(_t("No bans currently active")).append("</i></p>\n");
            out.append(buf);
            return;
        }

        // Column order: Country Flag, Router Hash, IP Address, Port, Hostname, Reason, Expiry
        buf.append("<div class=tablewrap id=sessionBanned>\n<table id=sbans>\n<thead><tr><th class=country>")
           .append(_t("Country"))
           .append("</th><th class=hash data-sort-use-group=true>")
           .append(_t("Router"))
           .append("</th><th class=ip>")
           .append(_t("IP Address"))
           .append("</th><th class=port>")
           .append(_t("Port"))
           .append("</th>");
        if (enableReverseLookups()) {
            buf.append("<th class=hostname>").append(_t("Hostname")).append("</th>");
        }
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
            buf.append("<tr");
            if (entry.cause.toLowerCase().contains("floodfill")) {
                buf.append(" class=\"banFF\"");
            }
            buf.append(">");
            String reason = _t(entry.cause, entry.causeCode)
                .replace("<b>➜</b> ", "")
                .replace("<b> -> </b>", "")
                .replace(" -> ", "")
                .replaceAll("^\\s*[<-]?\\s*", "")
                .trim();
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
                 if (_context.logManager().getLog(BanlistRenderer.class).shouldLog(net.i2p.util.Log.DEBUG)) {
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
            if (hostname == null && ip != null && !ip.isEmpty() && enableReverseLookups()) {
                hostname = reverseLookup(ip);
            }
            // GeoIP lookup for country flag - try hash first, then IP
            String countryCode = "xx";
            if (key != null) {
                String geoCountry = _context.commSystem().getCountry(key);
                if (geoCountry != null && !geoCountry.isEmpty() && !"xx".equals(geoCountry)) {
                    countryCode = geoCountry.toLowerCase();
                }
            }
             // Fallback to IP lookup if hash lookup failed or returned "xx"
             if (("xx".equals(countryCode) || countryCode.isEmpty()) && ip != null && !ip.isEmpty()) {
                  // Check if ipPort was actually in the map (for debugging)
                  String ipPortDebug = ipMap.get(key != null ? key.toBase64() : "");
                  if (_context.logManager().getLog(BanlistRenderer.class).shouldLog(net.i2p.util.Log.DEBUG)) {
                      _context.logManager().getLog(BanlistRenderer.class).debug(
                          "GeoIP lookup for hash " + (key != null ? key.toBase64().substring(0, 8) : "null") +
                          ": ipPort=" + ipPortDebug + ", extractedIP=" + ip +
                          ", ipFromMap=" + (ipMap.get(key != null ? key.toBase64() : "") != null ? "yes" : "no"));
                  }
                  // Queue the IP for GeoIP lookup if not already in database
                  byte[] geoIpBytes = Addresses.getIPOnly(ip);
                  if (geoIpBytes != null) {
                      _context.commSystem().queueLookup(geoIpBytes);
                  }
                  String geoCountry = _context.commSystem().getCountry(ip);
                  if (geoCountry != null && !geoCountry.isEmpty() && !"xx".equals(geoCountry)) {
                      countryCode = geoCountry.toLowerCase();
                  } else if (_context.logManager().getLog(BanlistRenderer.class).shouldLog(net.i2p.util.Log.DEBUG)) {
                      _context.logManager().getLog(BanlistRenderer.class).debug(
                          "GeoIP lookup failed for IP: " + ip + ", geoCountry=" + geoCountry);
                  }
             }
            // Final fallback to xx if unknown
            if ("unknown".equals(countryCode)) {
                countryCode = "xx";
            }

            String countryName =  _context.commSystem().getCountryName(countryCode);
            buf.append("<td class=country data-sort=\"").append(countryCode).append("\">")
               .append("<img width=28 height=21 title=\"").append(countryName)
               .append("\" src=\"/flags.jsp?c=").append(countryCode).append("\">")
               .append("</td><td class=hash>")
               .append(key != null ? "<span class=b64>" + key.toBase64() + "</span>" : "")
               .append("</td><td class=ip>")
               .append(ip != null ? ip : "")
               .append("</td><td class=port>")
               .append(port != null ? port : "")
               .append("</td>");
            if (enableReverseLookups()) {
                buf.append("<td class=hostname>")
                   .append(hostname != null && !hostname.isEmpty() && !"unknown".equals(hostname) ? hostname : "")
                   .append("</td>");
            }
            buf.append("<td class=reason>")
               .append(reason)
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
            if (hostname == null && enableReverseLookups()) {
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
               .append("<td class=country data-sort=\"").append(countryCode).append("\">")
               .append("<img width=28 height=21 title=\"").append(countryName)
               .append("\" src=\"/flags.jsp?c=").append(countryCode).append("\">")
               .append("</td>")
               .append("<td class=hash>")  // No hash available for IP-only bans
               .append("</td>")
               .append("<td class=ip>")
               .append(ip != null ? ip : "")
               .append("</td><td class=port>")
               .append(port != null ? port : "")
               .append("</td>");
            if (enableReverseLookups()) {
                buf.append("<td class=hostname>")
                   .append(hostname != null && !hostname.isEmpty() && !"unknown".equals(hostname) ? hostname : "")
                   .append("</td>");
            }
            buf.append("<td class=reason>")
               .append(ipBan.reason.isEmpty() ? "IP Ban" : ipBan.reason)
               .append("</td><td class=expires data-sort=").append(ipBan.expires).append(">")
               .append(expireString)
               .append("</td></tr>\n");
            tempBanned++;
        }

        buf.append("</tbody>\n<tfoot id=sessionBanlistFooter><tr><th colspan=")
           .append(enableReverseLookups() ? "7" : "6")
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
}
