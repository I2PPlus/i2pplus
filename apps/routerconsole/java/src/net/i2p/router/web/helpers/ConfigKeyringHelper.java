package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.i2p.data.BlindData;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.web.HelperBase;


public class ConfigKeyringHelper extends HelperBase {
    public ConfigKeyringHelper() {}

    public String getSummary() {
        StringWriter sw = new StringWriter(4*1024);
        try {
            renderStatusHTML(sw);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return sw.toString();
    }

    /**
     *  @since 0.9.33 moved from PersistentKeyRing
     */
    private void renderStatusHTML(StringWriter out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<h3>").append(_t("Local encrypted destinations")).append("</h3>");
        boolean rv1 = render(buf, true);
        buf.append("<h3>").append(_t("Remote encrypted destinations")).append("</h3>");
        boolean rv2 = render(buf, false);
        if (rv1 || rv2) {
            buf.append("\n<table id=\"keyringbuttons\" class=\"configtable\"><tr><td class=\"optionsave\">" +
                       "<input type=\"reset\" class=\"cancel\" value=\"").append(_t("Cancel")).append("\">" +
                       "<input type=\"submit\" name=\"action\" class=\"delete\" value=\"").append(_t("Delete key")).append("\">" +
                       "</td></tr></table>");
        }
        out.write(buf.toString());
        out.flush();
    }

    /**
     *  @since 0.9.33 moved from PersistentKeyRing
     *  @param local true for local (Enc. LS1 only), false for remote (all types)
     *  @return true if there were any entries
     */
    private boolean render(StringBuilder buf, boolean local) {
        boolean rv = false;
        buf.append("\n<table class=\"configtable encDests\">\n<tr>");
        buf.append("<th>").append(_t("Name"));
        buf.append("<th>").append(_t("Destination")).append("<th>");
        if (!local)
            buf.append(_t("Type"));
        buf.append("</th>");
        buf.append("<th>").append(_t("Encryption Key"));
        buf.append("</th><th>");
        if (!local)
            buf.append(_t("Lookup Password"));
        buf.append("</th><th>");
        if (!local)
            buf.append(_t("Created"));
        buf.append("</th><th>");
        if (!local)
            buf.append(_t("Expires"));
        buf.append("</th><th>");
        if (!local)
            buf.append(_t("Delete"));
        buf.append("</th>");
        buf.append("</tr>");
        // Enc. LS1
        for (Map.Entry<Hash, SessionKey> e : _context.keyRing().entrySet()) {
            Hash h = e.getKey();
            if (local != _context.clientManager().isLocal(h))
                continue;
            buf.append("\n<tr><td>");

            Destination dest = _context.netDb().lookupDestinationLocally(h);
            if (dest != null && local) {
                TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(h);
                if (in != null && in.getDestinationNickname() != null)
                    buf.append(in.getDestinationNickname());
            } else {
                String host = _context.namingService().reverseLookup(h);
                if (host != null)
                    buf.append(host);
            }
            buf.append("</td><td><code>");
            String b32 = h.toBase32();
            buf.append(b32);
            buf.append("</code></td><td>");
            if (!local)
                buf.append(_t("Encrypted")).append(" (AES)");
            SessionKey sk = e.getValue();
            buf.append("</td><td class=\"encKey\"><code>");
            buf.append(sk.toBase64());
            buf.append("</code></td><td>");
            if (!local)
                buf.append("</td><td></td><td></td><td><input value=\"").append(b32).append("\" type=\"checkbox\" name=\"revokeClient\" class=\"optbox\"/>");
            buf.append("</td></tr>");
            rv = true;
        }
        // LS2
        if (!local) {
            List<BlindData> bdata = _context.netDb().getBlindData();
            if (bdata.size() > 1)
                Collections.sort(bdata, new BDComparator());
            for (BlindData bd : bdata) {
                buf.append("\n<tr><td class=\"hostname\">");
                Hash h = bd.getDestHash();
                if (h != null) {
                    String host = _context.namingService().reverseLookup(h);
                    if (host != null)
                        buf.append(host);
                }
                buf.append("</td><td class=\"b32\"><code>");
                String b32 = bd.toBase32();
                buf.append(b32);
                buf.append("</code></td><td class=\"encType\">");
                int type = bd.getAuthType();
                PrivateKey pk = bd.getAuthPrivKey();
                String secret = bd.getSecret();
                String s;
                if (type == BlindData.AUTH_DH) {
                    if (secret != null)
                        s = _t("Encrypted with lookup password") + " (DH)";
                    else
                        s = _t("Encrypted") + " (DH)";
                } else if (type == BlindData.AUTH_PSK) {
                    if (secret != null)
                        s = _t("Encrypted with lookup password") + " (PSK)";
                    else
                        s = _t("Encrypted") + " (PSK)";
                } else {
                    if (secret != null)
                        s = _t("Blinded with lookup password");
                    else
                        s = _t("Blinded");
                }
                buf.append(s);
                buf.append("</td><td class=\"encKey\"><code>");
                if (pk != null) {
                    // display pubkey for DH for sharing with server
                    if (type == BlindData.AUTH_DH)
                        buf.append(pk.toPublic().toBase64());
                    else
                        buf.append(pk.toBase64());
                }
                buf.append("</code></td><td class=\"password\">");
                if (secret != null)
                    buf.append(secret);
                buf.append("</td><td class=\"created\">");
                long t = bd.getDate();
                if (t > 0)
                    buf.append(DataHelper.formatDate(t));
                buf.append("</td><td class=\"expires\">");
                t = bd.getExpiration();
                if (t > 0)
                    buf.append(DataHelper.formatDate(t));
                buf.append("</td><td class=\"revoke\">");
                if (!local)
                    buf.append("<input value=\"").append(b32).append("\" type=\"checkbox\" name=\"revokeClient\" class=\"optbox\"/>");
                buf.append("</td></tr>");
                rv = true;
            }
        }
        if (!rv) {
//            buf.append("<tr><td colspan=\"").append(local ? '3' : '8').append("\"><i>")
            buf.append("<tr><td colspan=\"8\"><i>")
               .append(_t("none")).append("</i></td></tr>");
        }
        buf.append("\n</table>\n");
        return rv;
    }

    /** @since 0.9.41 */
    private static class BDComparator implements Comparator<BlindData>, Serializable {
         public int compare(BlindData l, BlindData r) {
             return l.toBase32().compareTo(r.toBase32());
        }
    }
}
