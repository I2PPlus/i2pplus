package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.crypto.ratchet.MuxedPQSKM;
import net.i2p.router.crypto.ratchet.MuxedSKM;
import net.i2p.router.crypto.ratchet.RatchetSKM;

/**
 * Renders every client ratchet session key manager as one table, with a
 * thead/tbody/tfoot section per client as on the update manager page, so
 * the columns align across all clients. Inbound tag sets, outbound
 * sessions and outbound tag sets share the column grid; cells that do not
 * apply to a row's kind show an em-dash. The tfoot carries the client's
 * session counters. Clients whose SKM has no ratchet part (plain ElGamal)
 * keep their own tag-status rendering below the table.
 *
 * @since 0.9.71
 */
public class ClientSKMDebugHelper {

    /**
     * Static helpers only, no instances.
     */
    private ClientSKMDebugHelper() {}

    private static final String COLUMNS =
        "<tr><th>Kind</th><th>Peer</th><th>Set</th><th>Acked</th><th>Created</th><th>Established</th>" +
        "<th>Last Used</th><th>Last Rcvd</th><th>Expires in</th><th>Tags</th><th>Remaining</th><th>NK sent</th></tr>\n";

    /**
     * Write the consolidated table, one section per distinct client ratchet
     * SKM (unwrapping the muxed variants), plus the legacy status section
     * for each client whose SKM has no ratchet part (plain ElGamal).
     *
     * @param ctx the router context
     * @param out where to write
     * @throws IOException if writing to out fails
     */
    public static void render(RouterContext ctx, Writer out) throws IOException {
        Set<Destination> clients = ctx.clientManager().listClients();
        Set<SessionKeyManager> rendered = new HashSet<>(clients.size());
        StringBuilder sections = new StringBuilder(4096);
        StringWriter legacy = new StringWriter(1024);
        int legacySections = 0;
        for (Destination dest : clients) {
            Hash h = dest.calculateHash();
            SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(h);
            if (skm == null || !rendered.add(skm)) {continue;}
            String title = clientTitle(ctx, dest, h);
            if (skm instanceof RatchetSKM) {
                appendRatchet(sections, title, (RatchetSKM) skm);
            } else if (skm instanceof MuxedSKM) {
                MuxedSKM m = (MuxedSKM) skm;
                appendRatchet(sections, title + " (" + m.getECSKM().getType().name() + ")", m.getECSKM());
                legacySection(legacy, title + " (ElGamal)", m.getElgSKM());
                legacySections++;
            } else if (skm instanceof MuxedPQSKM) {
                MuxedPQSKM m = (MuxedPQSKM) skm;
                appendRatchet(sections, title + " (" + m.getECSKM().getType().name() + ")", m.getECSKM());
                appendRatchet(sections, title + " (" + m.getPQSKM().getType().name() + ")", m.getPQSKM());
            } else {
                legacySection(legacy, title, skm);
                legacySections++;
            }
        }
        if (sections.length() == 0 && legacySections == 0) {
            out.write("<p>No client session key managers.</p>\n");
            return;
        }
        if (sections.length() > 0) {
            out.write("<table id=client_skms>\n" + sections + "</table>\n");
        }
        out.write(legacy.toString());
    }

    private static void appendRatchet(StringBuilder sections, String title, RatchetSKM rskm) {
        sections.append(buildSection(title, rskm.getDebugRows(), rskm.getDebugSummary()));
    }

    /**
     * Pre-ratchet SKMs keep their own tag-status rendering.
     */
    private static void legacySection(Writer out, String title, SessionKeyManager skm) throws IOException {
        out.write("<div class=debug_section>\n<h2>Session Key Manager: " + title + "</h2>\n");
        skm.renderStatusHTML(out);
        out.write("</div>\n");
    }

    /**
     * The client's nickname when its inbound tunnel has one, otherwise the
     * base32 destination.
     */
    private static String clientTitle(RouterContext ctx, Destination dest, Hash h) {
        TunnelPoolSettings tps = ctx.tunnelManager().getInboundSettings(h);
        if (tps != null) {
            String nick = tps.getDestinationNickname();
            if (nick != null) {return DataHelper.escapeHTML(nick);}
        }
        return "<span class=skm_dest>" + dest.toBase32() + "</span>";
    }

    /**
     * Build one thead/tbody/tfoot section. The title is pre-escaped html.
     * Package-visible for testing.
     *
     * @param title the client title html
     * @param rows unified rows from RatchetSKM.getDebugRows()
     * @param summary the counters line, or null for no tfoot
     * @return the section html, never null
     */
    static String buildSection(String title, List<String[]> rows, String summary) {
        StringBuilder buf = new StringBuilder(rows.size() * 96 + 256);
        buf.append("<thead>\n<tr><th colspan=12>").append(title).append(" (").append(rows.size()).
            append(")</th></tr>\n").append(COLUMNS).append("</thead>\n<tbody>\n");
        for (String[] r : rows) {
            buf.append("<tr>");
            for (String c : r) {
                buf.append("<td>").append(DataHelper.escapeHTML(c)).append("</td>");
            }
            buf.append("</tr>\n");
        }
        buf.append("</tbody>\n");
        if (summary != null) {
            buf.append("<tfoot>\n<tr><td colspan=12>").append(DataHelper.escapeHTML(summary)).append("</td></tr>\n</tfoot>\n");
        }
        return buf.toString();
    }
}
