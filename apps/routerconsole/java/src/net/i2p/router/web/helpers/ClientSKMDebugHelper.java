package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.crypto.ratchet.MuxedPQSKM;
import net.i2p.router.crypto.ratchet.MuxedSKM;
import net.i2p.router.crypto.ratchet.RatchetSKM;
import net.i2p.util.Translate;

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
     * The column header row, translated.
     *
     * @param ctx the router context for the translation bundle
     * @return the header row html
     */
    private static String columns(RouterContext ctx) {
        String[] labels = {"Kind", "Peer", "Set", "Acked", "Created", "Established",
                           "Last Used", "Last Rcvd", "Expires in", "Tags", "Remaining", "NK sent"};
        StringBuilder buf = new StringBuilder(256);
        buf.append("<tr>");
        for (String label : labels) {
            buf.append("<th>").append(Translate.getString(label, ctx, "net.i2p.router.web.messages")).append("</th>");
        }
        buf.append("</tr>\n");
        return buf.toString();
    }

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
                appendRatchet(ctx, sections, title, (RatchetSKM) skm);
            } else if (skm instanceof MuxedSKM) {
                MuxedSKM m = (MuxedSKM) skm;
                appendRatchet(ctx, sections, title + " (" + m.getECSKM().getType().name() + ")", m.getECSKM());
                legacySection(legacy, title + " (ElGamal)", m.getElgSKM());
                legacySections++;
            } else if (skm instanceof MuxedPQSKM) {
                MuxedPQSKM m = (MuxedPQSKM) skm;
                appendRatchet(ctx, sections, title + " (" + m.getECSKM().getType().name() + ")", m.getECSKM());
                appendRatchet(ctx, sections, title + " (" + m.getPQSKM().getType().name() + ")", m.getPQSKM());
            } else {
                legacySection(legacy, title, skm);
                legacySections++;
            }
        }
        if (sections.length() == 0 && legacySections == 0) {
            out.write("<p>" + Translate.getString("No client session key managers.", ctx, "net.i2p.router.web.messages") + "</p>\n");
            return;
        }
        if (sections.length() > 0) {
            out.write("<table id=client_skms>\n" + sections + "</table>\n");
        }
        out.write(legacy.toString());
    }

    private static void appendRatchet(RouterContext ctx, StringBuilder sections, String title, RatchetSKM rskm) {
        sections.append(buildSection(title, rskm.getDebugRows(), rskm.getDebugSummary(),
                                     columns(ctx), 1, peerRenderer()));
    }

    /**
     * Turns a base64 key-hash cell into a destination reference: the peer of
     * a client session is a destination, never a router, so no router-info
     * markup (flag, bandwidth) applies and there is nothing to look up.
     * Shows the short hash with the full hash as title; falls back to the
     * label itself when it is not a hash (e.g. the "null" key).
     *
     * @return a function mapping the cell text to html
     */
    private static Function<String, String> peerRenderer() {
        return label -> {
            byte[] data = Base64.decode(label);
            if (data == null || data.length != Hash.HASH_LENGTH) {return label;}
            String shown = label.length() > 8 ? label.substring(0, 8) : label;
            return "<span class=skm_dest title=\"" + label + "\">" + shown + "</span>";
        };
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
     * Build one thead/tbody/tfoot section with the default english columns
     * and no peer rendering. Package-visible for testing.
     *
     * @param title the client title html
     * @param rows unified rows from RatchetSKM.getDebugRows()
     * @param summary the counters line, or null for no tfoot
     * @return the section html, never null
     */
    static String buildSection(String title, List<String[]> rows, String summary) {
        return buildSection(title, rows, summary, COLUMNS, -1, null);
    }

    /**
     * Build one thead/tbody/tfoot section. The title is pre-escaped html.
     * The cell at peerCol is passed through peerRenderer (trusted html,
     * e.g. a router-info reference) instead of being escaped.
     * Package-visible for testing.
     *
     * @param title the client title html
     * @param rows unified rows from RatchetSKM.getDebugRows()
     * @param summary the counters line, or null for no tfoot
     * @param columns the translated header row html
     * @param peerCol index of the peer column, or -1 for none
     * @param peerRenderer renders the peer cell html, or null to escape it
     * @return the section html, never null
     */
    static String buildSection(String title, List<String[]> rows, String summary,
                               String columns, int peerCol, Function<String, String> peerRenderer) {
        StringBuilder buf = new StringBuilder(rows.size() * 96 + 256);
        buf.append("<thead>\n<tr><th colspan=12>").append(title).append(" (").append(rows.size()).
            append(")</th></tr>\n").append(columns).append("</thead>\n<tbody>\n");
        for (String[] r : rows) {
            buf.append("<tr>");
            for (int i = 0; i < r.length; i++) {
                buf.append("<td>");
                if (i == peerCol && peerRenderer != null) {
                    buf.append(peerRenderer.apply(r[i]));
                } else {
                    buf.append(DataHelper.escapeHTML(r[i]));
                }
                buf.append("</td>");
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
