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
import net.i2p.router.web.Messages;

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
     * Read sessionbans.txt and build a map of router hash to IP address.
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
                    String ip = parts[2].trim();
                    if (!hash.isEmpty() && !ip.isEmpty() && ipMap.get(hash) == null) {
                        ipMap.put(hash, ip);
                    }
                }
            }
        } catch (IOException e) {
        }
        return ipMap;
    }

    /**
     * Read sessionbans.txt and build a list of IP-only bans.
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
                    String ip = parts[2].trim();
                    String reason = parts[3].trim();
                    String durationStr = parts.length >= 5 ? parts[4].trim() : "";
                    if (hash.isEmpty() && !ip.isEmpty()) {
                        long expires = parseDuration(durationStr, now);
                        if (expires > now) {
                            ipBans.add(new IPBanEntry(ip, reason, expires, durationStr));
                        }
                    }
                }
            }
        } catch (IOException e) {
        }
        return ipBans;
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
     * Entry for IP-only bans from sessionbans.txt.
     */
    private static class IPBanEntry {
        final String ip;
        final String reason;
        final long expires;
        final String durationStr;

        IPBanEntry(String ip, String reason, long expires, String durationStr) {
            this.ip = ip;
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
        List<IPBanEntry> ipOnlyBans = readSessionBansIPOnly();

        entries.putAll(_context.banlist().getEntries());
        if (entries.isEmpty() && ipOnlyBans.isEmpty()) {
            buf.append("<i>").append(_t("No bans currently active")).append("</i>");
            out.append(buf);
            return;
        }

        buf.append("<table id=sessionBanned>\n<thead><tr><th data-sort-use-group=true>")
           .append(_t("Reason"))
           .append("</th><th></th><th>")
           .append(_t("Router Hash"))
           .append("</th><th>")
           .append(_t("IP Address"))
           .append("</th>");
        if (enableReverseLookups()) {
            buf.append("<th>").append(_t("Hostname")).append("</th>");
        }
        buf.append("<th data-sort-method=number data-sort-direction=ascending>")
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
            buf.append("<tr class=\"lazy");
            if (entry.cause.toLowerCase().contains("floodfill")) {
                buf.append(" banFF");
            }
            String reason = _t(entry.cause, entry.causeCode)
                .replace("<b>➜</b> ", "")
                .replace("<b> -> </b>", "")
                .replace(" -> ", "")
                .replaceAll("^\\s*[<-]?\\s*", "")
                .trim();
            // Remove trailing colon and any trailing whitespace
            if (reason.endsWith(":")) {
                reason = reason.substring(0, reason.length() - 1).trim();
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
            }
            String ip = ipMap.get(key.toBase64());
            if (extractedIP != null && !extractedIP.isEmpty()) {
                ip = extractedIP;
            }
            String hostname = null;
            if (ip != null && !ip.isEmpty() && enableReverseLookups()) {
                hostname = reverseLookup(ip);
            }
            buf.append("\"><td>")
               .append(reason)
               .append("</td><td>:</td><td>")
               .append(key != null ? "<span class=b64>" + key.toBase64() + "</span>" : "")
               .append("</td><td>")
               .append(ip != null ? ip : "")
               .append("</td>");
            if (enableReverseLookups()) {
                buf.append("<td>")
                   .append(hostname != null && !hostname.isEmpty() && !"unknown".equals(hostname) ? hostname : "")
                   .append("</td>");
            }
            buf.append("<td data-sort=").append(expires).append(">")
               .append(expireString)
               .append("</td></tr>\n");
            tempBanned++;
        }

        // Render IP-only bans
        for (IPBanEntry ipBan : ipOnlyBans) {
            String expireString = DataHelper.formatDuration2(ipBan.expires - _context.clock().now());
            String hostname = null;
            if (enableReverseLookups()) {
                hostname = reverseLookup(ipBan.ip);
            }
            buf.append("<tr class=\"lazy ipOnly\">")
               .append("<td>")
               .append(ipBan.reason.isEmpty() ? "IP Ban" : ipBan.reason)
               .append("</td><td>:</td><td></td><td>")
               .append(ipBan.ip)
               .append("</td>");
            if (enableReverseLookups()) {
                buf.append("<td>")
                   .append(hostname != null && !hostname.isEmpty() && !"unknown".equals(hostname) ? hostname : "")
                   .append("</td>");
            }
            buf.append("<td data-sort=").append(ipBan.expires).append(">")
               .append(expireString)
               .append("</td></tr>\n");
            tempBanned++;
        }

        buf.append("</tbody>\n<tfoot id=sessionBanlistFooter><tr><th colspan=")
           .append(enableReverseLookups() ? "6" : "5")
           .append(">")
           .append(_t("Total session-only bans"))
           .append(": ").append(tempBanned)
           .append("</th></tr></tfoot>\n</table>\n");
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
