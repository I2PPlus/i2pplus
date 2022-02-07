package net.i2p.router.web.helpers;

import java.util.Properties;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.web.HelperBase;

public class ConfigTunnelsHelper extends HelperBase {
    private static final String HOP = "hop";
    private static final String TUNNEL = "tunnel";
    /** dummies for translation */
    private static final String HOPS = ngettext("{0} hop", "{0} hops");
    private static final String TUNNELS = ngettext("{0} tunnel", "{0} tunnels");

    public String getForm() {
        StringBuilder buf = new StringBuilder(1024);
        // HTML: <input> cannot be inside a <table>
        buf.append("<input type=\"hidden\" name=\"pool.0\" value=\"exploratory\" >\n");
        int cur = 1;
        Set<Destination> clients = _context.clientManager().listClients();
        for (Destination dest : clients) {
            buf.append("<input type=\"hidden\" name=\"pool.").append(cur).append("\" value=\"");
            buf.append(dest.calculateHash().toBase64()).append("\" >\n");
            cur++;
        }

        buf.append("<table id=\"tunnelconfig\" class=\"configtable\">\n");
        TunnelPoolSettings exploratoryIn = _context.tunnelManager().getInboundSettings();
        TunnelPoolSettings exploratoryOut = _context.tunnelManager().getOutboundSettings();

        renderForm(buf, 0, "exploratory", _t("Exploratory tunnels"), exploratoryIn, exploratoryOut);

        cur = 1;
        for (Destination dest : clients) {
            TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(dest.calculateHash());
            TunnelPoolSettings out = _context.tunnelManager().getOutboundSettings(dest.calculateHash());

            if (in == null || in.getAliasOf() != null ||
                out == null || out.getAliasOf() != null) {
                cur++;
                continue;
            }

            String name = in.getDestinationNickname();
            if (name == null) {
                name = out.getDestinationNickname();
                if (name == null)
                    name = dest.calculateHash().toBase32();
            }

            String prefix = dest.calculateHash().toBase64().substring(0,4);
            renderForm(buf, cur, prefix, _t("Client tunnels for {0}", DataHelper.escapeHTML(_t(name))), in, out);
            cur++;
        }

        buf.append("</table>\n");
        return buf.toString();
    }

    private static final int WARN_LENGTH = 4;
//    private static final int MAX_LENGTH = 4;
    private static final int MAX_LENGTH = 5;
    private static final int MAX_ADVANCED_LENGTH = 7;
    private static final int WARN_QUANTITY = 5;
    private static final int MAX_QUANTITY = 6;
    private static final int MAX_ADVANCED_QUANTITY = 16;
    private static final int MAX_BACKUP_QUANTITY = 3;
    private static final int MAX_ADVANCED_BACKUP_QUANTITY = 16;
    private static final int MAX_VARIANCE = 2;
    private static final int MIN_NEG_VARIANCE = -1;

    private void renderForm(StringBuilder buf, int index, String prefix, String name, TunnelPoolSettings in, TunnelPoolSettings out) {

        boolean advanced = isAdvanced();

        buf.append("<tr><th colspan=\"3\" class=\"th_title\"><a name=\"").append(prefix).append("\">");
        buf.append(name).append("</a></th></tr>\n");
        if (in.getLength() <= 0 ||
            in.getLength() + in.getLengthVariance() <= 0 ||
            out.getLength() <= 0 ||
            out.getLength() + out.getLengthVariance() <= 0) {
            buf.append("<tr><th colspan=\"3\"><font color=\"#900\">" + _t("ANONYMITY WARNING - Settings include 0-hop tunnels.") + "</font></th></tr>");
            if (TransportUtil.getIPv6Config(_context, "SSU") == TransportUtil.IPv6Config.IPV6_ONLY) {
                // rare, don't bother translating
                buf.append("<tr><th colspan=\"3\"><font color=\"#900\">WARNING - 0-hop tunnels not recommended for IPv6-only routers.</font></th></tr>");
            }
            if ((in.getLength() <= 0 || in.getLength() + in.getLengthVariance() <= 0) &&
                _context.router().isHidden()) {
                // rare, don't bother translating
                buf.append("<tr><th colspan=\"3\"><font color=\"#900\">WARNING - Inbound 0-hop tunnels not recommended for hidden routers.</font></th></tr>");
            }
        } else if (in.getLength() <= 1 ||
            in.getLength() + in.getLengthVariance() <= 1 ||
            out.getLength() <= 1 ||
            out.getLength() + out.getLengthVariance() <= 1) {
            buf.append("<tr><th colspan=\"3\"><font color=\"#900\">" + _t("ANONYMITY WARNING - Settings include 1-hop tunnels.") + "</font></th></tr>");
        }
        if (in.getLength() + Math.abs(in.getLengthVariance()) >= WARN_LENGTH ||
            out.getLength() + Math.abs(out.getLengthVariance()) >= WARN_LENGTH)
            buf.append("<tr><th colspan=\"3\"><font color=\"#900\">" + _t("PERFORMANCE WARNING - Settings include very long tunnels.") + "</font></th></tr>");
        if (in.getTotalQuantity() >= WARN_QUANTITY ||
            out.getTotalQuantity() >= WARN_QUANTITY)
            buf.append("<tr><th colspan=\"3\"><font color=\"#900\">" + _t("PERFORMANCE WARNING - Settings include high tunnel quantities.") + "</font></th></tr>");

        buf.append("<tr><th></th><th><img src=\"/themes/console/images/svg/inbound.svg\" height=\"14\" alt=\"Inbound\" title=\"Inbound Tunnels\" style=\"vertical-align:middle\">&nbsp;&nbsp;" +
                _t("Inbound") + "</th><th><img src=\"/themes/console/images/svg/outbound.svg\" height=\"14\" alt=\"Outbound Tunnels\" title=\"Outbound\" style=\"vertical-align:middle\">&nbsp;&nbsp;" +
                _t("Outbound") + "</th></tr>\n");

        // tunnel depth
        int maxLength = advanced ? MAX_ADVANCED_LENGTH : MAX_LENGTH;
        buf.append("<tr><td><b>" + _t("Length") + ":</b></td>\n");
        buf.append("<td><select name=\"").append(index).append(".depthInbound\"");
        if (!advanced && prefix != "exploratory")
            buf.append(" disabled");
        buf.append(">\n");
        int now = in.getLength();
        renderOptions(buf, 0, maxLength, now, "", HOP);
        if (now > maxLength)
            renderOptions(buf, now, now, now, "", HOP);
        buf.append("</select></td>\n");

        buf.append("<td><select name=\"").append(index).append(".depthOutbound\"");
        if (!advanced && prefix != "exploratory")
            buf.append(" disabled");
        buf.append(">\n");
        now = out.getLength();
        renderOptions(buf, 0, maxLength, now, "", HOP);
        if (now > maxLength)
            renderOptions(buf, now, now, now, "", HOP);
        buf.append("</select></td>\n");
        buf.append("</tr>\n");

        // tunnel depth variance
        if (advanced) {
            buf.append("<tr><td><b>" + _t("Randomization") + ":</b></td>\n");
            buf.append("<td><select name=\"").append(index).append(".varianceInbound\">\n");
            now = in.getLengthVariance();
            renderOptions(buf, 0, 0, now, "", HOP);
            renderOptions(buf, 1, MAX_VARIANCE, now, "+ 0-", HOP);
            renderOptions(buf, MIN_NEG_VARIANCE, -1, now, "+/- 0", HOP);
            if (now > MAX_VARIANCE)
                renderOptions(buf, now, now, now, "+ 0-", HOP);
            else if (now < MIN_NEG_VARIANCE)
                renderOptions(buf, now, now, now, "+/- 0", HOP);
            buf.append("</select></td>\n");

            buf.append("<td><select name=\"").append(index).append(".varianceOutbound\">\n");
            now = out.getLengthVariance();
            renderOptions(buf, 0, 0, now, "", HOP);
            renderOptions(buf, 1, MAX_VARIANCE, now, "+ 0-", HOP);
            renderOptions(buf, MIN_NEG_VARIANCE, -1, now, "+/- 0", HOP);
            if (now > MAX_VARIANCE)
                renderOptions(buf, now, now, now, "+ 0-", HOP);
            else if (now < MIN_NEG_VARIANCE)
                renderOptions(buf, now, now, now, "+/- 0", HOP);
            buf.append("</select></td>\n");
        }
        // tunnel quantity
        int maxQuantity = advanced ? MAX_ADVANCED_QUANTITY : MAX_QUANTITY;
        buf.append("<tr");
        if (!advanced)
            buf.append(" class=\"lastrow\"");
        buf.append("><td><b>" + _t("Quantity") + ":</b></td>\n");
        buf.append("<td><select name=\"").append(index).append(".quantityInbound\"");
        if (!advanced && prefix != "exploratory")
            buf.append(" disabled");
        buf.append(">\n");
        now = in.getQuantity();
        renderOptions(buf, 1, maxQuantity, now, "", TUNNEL);
        if (now > maxQuantity)
            renderOptions(buf, now, now, now, "", TUNNEL);
        buf.append("</select></td>\n");

        buf.append("<td><select name=\"").append(index).append(".quantityOutbound\"");
        if (!advanced && prefix != "exploratory")
            buf.append(" disabled");
        buf.append(">\n");
        now = out.getQuantity();
        renderOptions(buf, 1, maxQuantity, now, "", TUNNEL);
        if (now > maxQuantity)
            renderOptions(buf, now, now, now, "", TUNNEL);
        buf.append("</select></td>\n");
        buf.append("</tr>\n");

        // tunnel backup quantity
        if (advanced) {
            int maxBQuantity = advanced ? MAX_ADVANCED_BACKUP_QUANTITY : MAX_BACKUP_QUANTITY;
            buf.append("<tr class=\"lastrow\"><td><b>" + _t("Backup quantity") + ":</b></td>\n");
            buf.append("<td><select name=\"").append(index).append(".backupInbound\">\n");
            now = in.getBackupQuantity();
            renderOptions(buf, 0, maxBQuantity, now, "", TUNNEL);
            if (now > maxBQuantity)
                renderOptions(buf, now, now, now, "", TUNNEL);
            buf.append("</select></td>\n");

            buf.append("<td><select name=\"").append(index).append(".backupOutbound\">\n");
            now = out.getBackupQuantity();
            renderOptions(buf, 0, maxBQuantity, now, "", TUNNEL);
            if (now > maxBQuantity)
                renderOptions(buf, now, now, now, "", TUNNEL);
            buf.append("</select></td>\n");
            buf.append("</tr>\n");
        }

        // custom options
        // There is no facility to set these, either in ConfigTunnelsHandler or
        // TunnelPoolOptions, so make the boxes readonly.
        // And let's not display them at all unless they have contents, which should be rare.
        Properties props = in.getUnknownOptions();
        if (!props.isEmpty()) {
            buf.append("<tr><td><b>" + _t("Inbound options") + ":</b></td>\n" +
                       "<td colspan=\"2\"><input name=\"").append(index);
            buf.append(".inboundOptions\" type=\"text\" size=\"32\" disabled=\"disabled\" " +
                       "value=\"");
            for (String prop : props.stringPropertyNames()) {
                String val = props.getProperty(prop);
                buf.append(prop).append('=').append(val).append(' ');
            }
            buf.append("\"></td></tr>\n");
        }
        props = out.getUnknownOptions();
        if (!props.isEmpty()) {
            buf.append("<tr><td><b>" + _t("Outbound options") + ":</b></td>\n" +
                       "<td colspan=\"2\"><input name=\"").append(index);
            buf.append(".outboundOptions\" type=\"text\" size=\"32\" disabled=\"disabled\" " +
                       "value=\"");
            for (String prop : props.stringPropertyNames()) {
                String val = props.getProperty(prop);
                buf.append(prop).append('=').append(val).append(' ');
            }
            buf.append("\"></td></tr>\n");
        }
    }

    /** to fool xgettext so the following isn't tagged */
    private static final String DUMMY2 = "{0} ";

    private void renderOptions(StringBuilder buf, int min, int max, int now, String prefix, String name) {
        for (int i = min; i <= max; i++) {
            buf.append("<option value=\"").append(i).append("\" ");
            if (i == now)
                buf.append(SELECTED);
            buf.append(">").append(ngettext(DUMMY2 + name, DUMMY2 + name + 's', i));
            buf.append("</option>\n");
        }
    }

    /** dummy for tagging */
    private static String ngettext(String s, String p) {
        return null;
    }
}
