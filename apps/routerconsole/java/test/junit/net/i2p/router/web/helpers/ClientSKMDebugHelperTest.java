package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests the pure section builder of {@link ClientSKMDebugHelper#buildSection}:
 * one thead/tbody/tfoot per client with a colspan title, the shared column
 * grid, escaped cells and an optional counters footer.
 *
 * @since 0.9.71
 */
public class ClientSKMDebugHelperTest {

    private static String[] row(String kind, String peer) {
        String[] r = new String[12];
        r[0] = kind;
        r[1] = peer;
        for (int i = 2; i < 12; i++) {r[i] = "—";}
        return r;
    }

    @Test
    public void sectionHasColspanTitleGridAndFooter() {
        List<String[]> rows = new ArrayList<>();
        rows.add(row("Inbound", "peerA"));
        rows.add(row("Set", "peerB"));
        String html = ClientSKMDebugHelper.buildSection("my nick", rows, "Sessions created: 3; replaced: 1; inbound sessions completed: 2; inbound tag misses: 0");
        assertTrue(html, html.startsWith("<thead>"));
        assertTrue(html, html.contains("<th colspan=12>my nick (2)</th>"));
        assertTrue(html, html.contains("<th>Kind</th><th>Peer</th><th>Set</th>"));
        assertTrue(html, html.contains("<th>Remaining</th><th>NK sent</th></tr>"));
        assertTrue(html, html.contains("<tbody>\n<tr><td>Inbound</td><td>peerA</td>"));
        assertTrue(html, html.contains("<tfoot>\n<tr><td colspan=12>Sessions created: 3;"));
        assertTrue(html, html.endsWith("</tfoot>\n"));
        assertEquals(2, html.split("<tr><td>", -1).length - 1);
    }

    @Test
    public void noSummaryMeansNoFooter() {
        String html = ClientSKMDebugHelper.buildSection("<span class=skm_dest>dest</span>", Collections.<String[]>emptyList(), null);
        assertTrue(html, !html.contains("<tfoot>"));
        assertTrue(html, html.contains("dest</span> (0)</th>"));
        assertTrue(html, html.endsWith("</tbody>\n"));
    }

    @Test
    public void cellsAreEscaped() {
        String html = ClientSKMDebugHelper.buildSection("t", Collections.singletonList(row("In<b>", "p&b")), null);
        assertTrue(html, html.contains("<td>In&lt;b&gt;</td><td>p&amp;b</td>"));
    }
}
