package net.i2p.router.web.helpers;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
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

    public BanlistRenderer(RouterContext context) {
        _context = context;
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

        entries.putAll(_context.banlist().getEntries());
        if (entries.isEmpty()) {
            buf.append("<i>").append(_t("No bans currently active")).append("</i>");
            out.append(buf);
            return;
        }

        buf.append("<table id=sessionBanned>\n<thead><tr><th>")
           .append(_t("Reason"))
           .append("</th><th></th><th>")
           .append(_t("Router Hash"))
           .append("</th><th data-sort-method=number data-sort-direction=ascending>")
           .append(_t("Expiry"))
           .append("</th></tr></thead>\n<tbody id=sessionBanlist>\n");
        int tempBanned = 0;
        for (Map.Entry<Hash, Banlist.Entry> e : entries.entrySet()) {
            Hash key = e.getKey();
            Banlist.Entry entry = e.getValue();
            long expires = entry.expireOn-_context.clock().now();
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
                buf.append("\"><td>")
                   .append(_t(entry.cause,entry.causeCode).replace("<b>➜</b> ",""))
                   .append("</td><td>:</td><td><span class=b64>")
                   .append(key.toBase64())
                   .append("</span></td><td data-sort=").append(expires).append(">")
                   .append(expireString)
                   .append("</td></tr>\n");
                tempBanned++;
        }
        buf.append("</tbody>\n<tfoot id=sessionBanlistFooter><tr><th colspan=4>")
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
