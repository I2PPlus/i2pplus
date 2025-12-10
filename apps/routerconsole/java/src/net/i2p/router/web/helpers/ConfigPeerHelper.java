package net.i2p.router.web.helpers;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import net.i2p.data.Hash;
import net.i2p.router.Blocklist;
import net.i2p.router.web.HelperBase;
import net.i2p.util.Addresses;

/**
 * Helper for peer configuration page rendering and form processing.
 * @since 0.9.33
 */
public class ConfigPeerHelper extends HelperBase {

    private static final int MAX_DISPLAY = 1000;

    public String getBlocklistSummary() {
        StringBuilder buf = new StringBuilder(128*1024);
        Blocklist bl = _context.blocklist();

        buf.append("<table id=bannedips style=display:none><tr><td>\n");

        // permabanned
        buf.append("<table id=permabanned>")
           .append("<tr><th colspan=3><b>").append(_t("IPs Permanently Banned")).append("</b></th></tr>\n");
        int blocklistSize = bl.getBlocklistSize();
        if (blocklistSize > 0) {
            buf.append("<tr><td><b>").append(_t("From")).append("</b></td><td></td>")
               .append("<td><b>").append(_t("To")).append("</b></td></tr>\n");
            long[] blocklist = bl.getPermanentBlocks(MAX_DISPLAY);
            // first 0 - 127
            for (int i = 0; i < blocklist.length; i++) {
                int from = Blocklist.getFrom(blocklist[i]);
                if (from < 0) {continue;}
                buf.append("<tr><td>").append(Blocklist.toStr(from)).append("</td>");
                int to = Blocklist.getTo(blocklist[i]);
                if (to != from) {
                    buf.append("<td>-</td><td>").append(Blocklist.toStr(to)).append("</td></tr>\n");
                } else {
                    buf.append("<td></td><td>&nbsp;</td></tr>\n");
                }
            }
            // then 128 - 255
            for (int i = 0; i < blocklist.length; i++) {
                int from = Blocklist.getFrom(blocklist[i]);
                if (from >= 0) {break;}
                buf.append("<tr><td>").append(Blocklist.toStr(from)).append("</td>");
                int to = Blocklist.getTo(blocklist[i]);
                if (to != from) {
                    buf.append("<td>-</td><td>").append(Blocklist.toStr(to)).append("</td></tr>\n");
                } else {
                    buf.append("<td></td><td>&nbsp;</td></tr>\n");
                }
            }
            if (blocklistSize > MAX_DISPLAY)
                buf.append("<tr><th colspan=3>").append(_t("First {0} displayed, see the {1} file for the full list",
                            MAX_DISPLAY, Blocklist.BLOCKLIST_FILE_DEFAULT)).append("</th></tr>");
        } else {
            buf.append("<tr><td><i>").append(_t("none")).append("</i></td></tr>");
        }
        buf.append("</table>");

        buf.append("</td>\n<td id=sessionIpBans width=50%>");

        // session banned
        buf.append("<table id=banneduntilrestart><tr><th><b>").append(_t("IPs Banned Until Restart")).append("</b></th></tr>\n");
        List<Integer> singles = bl.getTransientIPv4Blocks();
        List<BigInteger> s6 = bl.getTransientIPv6Blocks();
        if (!(singles.isEmpty() && s6.isEmpty())) {
            if (!singles.isEmpty()) {
                Collections.sort(singles);
                buf.append("<tr id=ipv4><td><b>").append(_t("IPv4 Addresses")).append("</b></td></tr>\n");
            }
            // first 0 - 127
            for (Integer ii : singles) {
                 int ip = ii.intValue();
                 if (ip < 0) {continue;}
                 if (bl.isPermanentlyBlocklisted(ip)) {continue;} // don't display if on the permanent blocklist also
                 buf.append("<tr><td>").append(Blocklist.toStr(ip)).append("</td></tr>\n");
            }
            // then 128 - 255
            for (Integer ii : singles) {
                 int ip = ii.intValue();
                 if (ip >= 0) {break;}
                 if (bl.isPermanentlyBlocklisted(ip)) {continue;} // don't display if on the permanent blocklist also
                 buf.append("<tr><td>").append(Blocklist.toStr(ip)).append("</td></tr>\n");
            }
            // then IPv6
            if (!s6.isEmpty()) {
                buf.append("<tr id=ipv6><td><b>").append(_t("IPv6 Addresses")).append("</b></td></tr>\n");
                Collections.sort(s6);
                for (BigInteger bi : s6) {
                     buf.append("<tr><td>").append(Addresses.toString(toIPBytes(bi))).append("</td></tr>\n");
                }
            }
        } else {
            buf.append("<tr><td><i>").append(_t("none")).append("<style>#sessionIpBans{display:none!important}</style>")
               .append("</i></td></tr>\n");
        }
        buf.append("</table>");

        buf.append("</td></tr></table>\n");
        return buf.toString();
    }

    /**
     *  @since 0.9.50
     */
    public boolean isBanned(Hash h) {return _context.banlist().isBanlisted(h);}

    /**
     *  Convert a (non-negative) two's complement IP to exactly 16 bytes
     *
     *  @since IPv6, moved from Blocklist in 0.9.48
     */
    private static byte[] toIPBytes(BigInteger bi) {
        byte[] ba = bi.toByteArray();
        int len = ba.length;
        if (len == 16) {return ba;}
        byte[] rv = new byte[16];
        if (len < 16) {System.arraycopy(ba, 0, rv, 16 - len, len);}
        else {System.arraycopy(ba, len - 16, rv, 0, 16);}
        return rv;
    }
}
