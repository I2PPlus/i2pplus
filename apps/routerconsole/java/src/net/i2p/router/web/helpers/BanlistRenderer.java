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
import net.i2p.router.RouterContext;
import net.i2p.router.Banlist;
import net.i2p.router.web.Messages;

/**
 *  Moved from Banlist.java
 */
class BanlistRenderer {
    private final RouterContext _context;

    public BanlistRenderer(RouterContext context) {
        _context = context;
    }

    public void renderStatusHTML(Writer out) throws IOException {
        Banlist banlist = _context.banlist();
        int bannedCount = banlist.getRouterCount();
        if (bannedCount == 0) {
            out.write("<i>" + _t("No bans currently active") + "</i>");
            return;
        }

        out.write("<ul id=banlist>");

        boolean largeBanlist = bannedCount > 300;
        long now = _context.clock().now();
        int bufferSize = banlist.getEntries().size() * 512;

        StringBuilder buf = new StringBuilder(bufferSize);
        int count = 0;

        Map<Hash, Banlist.Entry> entries = banlist.getEntries();

        for (Map.Entry<Hash, Banlist.Entry> e : entries.entrySet()) {
            Hash key = e.getKey();
            Banlist.Entry entry = e.getValue();

            long expires = entry.expireOn - now;
            if (expires <= 0) continue;

            if (largeBanlist) {
                String cause = entry.cause;
                String causeCode = entry.causeCode;
                if ((cause != null && cause.contains("LU")) ||
                    (causeCode != null && causeCode.contains("LU"))) {
                    continue;
                }
            }

            final boolean isFakeOrZero = key.equals(Hash.FAKE_HASH) || key.equals(Banlist.HASH_ZERORI);

            buf.append("<li class=lazy>")
               .append(_context.commSystem().renderPeerHTML(key, false))
               .append(' ')
               .append("<span class=banperiod>");

            String expireString = DataHelper.formatDuration2(expires);

            if (isFakeOrZero) {
                buf.append(_t("Permanently banned"));
            } else if (expires < 5L * 24 * 60 * 60 * 1000) {
                buf.append(_t("Temporary ban expiring in {0}", expireString));
            } else {
                buf.append(_t("Banned for {0} / until restart", expireString));
            }
            buf.append("</span>");

            Set<String> transports = entry.transports;
            if (transports != null && !transports.isEmpty()) {
                buf.append(" on the following transport: ").append(transports);
            }

            if (entry.cause != null) {
                buf.append("<hr>\n");
                if (entry.causeCode != null) {
                    buf.append(_t(entry.cause, entry.causeCode));
                } else {
                    buf.append(_t(entry.cause));
                }
            }

            if (!key.equals(Hash.FAKE_HASH)) {
                buf.append(" <a href=\"configpeer?peer=").append(key.toBase64())
                   .append("#unsh\" title=\"Unban\">[").append(_t("unban now")).append("]</a>");
            }

            buf.append("</li>\n");
            count++;

            if (count % 100 == 0) {flushBuffer(buf, out);}
        }

        flushBuffer(buf, out);
        out.write("</ul>\n");
        out.flush();
    }

    /* @since 0.9.59+ */
    public void renderBanlistCompact(Writer out) throws IOException {
        Banlist banlist = _context.banlist();
        Map<Hash, Banlist.Entry> entries = banlist.getEntries();
        if (entries.isEmpty()) {
            out.write("<i>" + _t("No bans currently active") + "</i>");
            return;
        }

        // Write static header directly
        out.write("<table id=sessionBanned>\n<thead><tr><th>");
        out.write(_t("Reason"));
        out.write("</th><th></th><th>");
        out.write(_t("Router Hash"));
        out.write("</th><th data-sort-method=number>");
        out.write(_t("Expiry"));
        out.write("</th></tr></thead>\n<tbody id=sessionBanlist>\n");

        int tempBanned = 0;
        long now = _context.clock().now();
        int bufferSize = banlist.getEntries().size() * 256;

        StringBuilder buf = new StringBuilder(bufferSize);
        int count = 0;

        for (Map.Entry<Hash, Banlist.Entry> e : entries.entrySet()) {
            Hash key = e.getKey();
            Banlist.Entry entry = e.getValue();

            long expires = entry.expireOn - now;
            if (expires <= 0 || key.equals(Hash.FAKE_HASH) || entry.cause == null) {
                continue;
            }

            String causeLower = entry.cause.toLowerCase();
            if (causeLower.contains("hash") || causeLower.contains("sybil") || causeLower.contains("blocklist")) {
                continue;
            }

            buf.append("<tr class=\"lazy");
            if (causeLower.contains("floodfill")) {
                buf.append(" banFF");
            }
            buf.append("\"><td>");

            String translated = entry.causeCode != null ? _t(entry.cause, entry.causeCode) : _t(entry.cause);
            buf.append(translated.replace("<b>➜</b> ", ""));
            buf.append("</td><td>:</td><td><span class=b64>")
               .append(key.toBase64())
               .append("</span></td><td><span hidden>")
               .append(expires)
               .append(".</span>")
               .append(DataHelper.formatDuration2(expires))
               .append("</td></tr>\n");

            tempBanned++;
            count++;

            if (count % 100 == 0) {flushBuffer(buf, out);}
        }

        flushBuffer(buf, out);

        buf.append("</tbody>\n<tfoot id=sessionBanlistFooter><tr><th colspan=4>")
           .append(_t("Total session-only bans"))
           .append(": ").append(Integer.toString(tempBanned))
           .append("</th></tr></tfoot>\n</table>\n");

        flushBuffer(buf, out);
        out.flush();
    }

    private void flushBuffer(StringBuilder buf, Writer out) throws IOException {
        if (buf.length() > 0) {
            out.write(buf.toString());
            buf.setLength(0);
        }
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
