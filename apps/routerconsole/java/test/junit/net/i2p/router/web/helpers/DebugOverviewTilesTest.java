package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Tests the pure tile builder of
 * {@link DebugOverviewHelper#buildTiles}: one linked cell per data-bearing
 * debug tab, DHT splits into fill and fullest cells when stats exist, and
 * null stat arrays degrade to placeholder cells instead of throwing.
 *
 * @since 0.9.71
 */
public class DebugOverviewTilesTest {

    @Test
    public void fullStatsProduceEightTiles() {
        List<String> tiles = DebugOverviewHelper.buildTiles(12, new int[] {5, 4, 6},
                new int[] {19, 0, 2}, new long[] {2244, 18, 47, 1, 41, 6},
                new long[] {100, 5, 10, 8, 40, 2}, new int[] {7, 3}, new long[] {1222, 58, 24, 24});
        assertEquals(8, tiles.size());
        for (String t : tiles) {
            assertTrue(t, t.startsWith("<a class=ring-cell-link href=\""));
            assertTrue(t, t.contains("ring-cell"));
        }
        assertTrue(tiles.get(0).contains(">12<"));
        assertTrue(tiles.get(1).contains("4/6"));
        assertTrue(tiles.get(2).contains("Available updates"));
        // streams tile links to the streams page, not a debug tab
        assertTrue(tiles.get(5).contains("href=\"/streams\""));
        assertTrue(tiles.get(5).contains(">10<"));
        assertTrue(tiles.get(5).contains("Inbound: 7, outbound: 3"));
        // DHT fill and fullest
        assertTrue(tiles.get(6).contains("1222"));
        assertTrue(tiles.get(7).contains("24/24"));
    }

    @Test
    public void nullStatsDegradeToPlaceholders() {
        List<String> tiles = DebugOverviewHelper.buildTiles(0, null, null, null, null, null, null);
        assertEquals(7, tiles.size());
        for (String t : tiles) {
            assertTrue(t, t.contains("—") || t.contains(">0<"));
            assertFalse(t, t.contains("null"));
        }
    }

    @Test
    public void picksPriorityOrderCappedAtFour() {
        java.util.Set<String> enabled = new java.util.HashSet<>();
        enabled.add("bw.sendRate");
        enabled.add("bw.recvRate");
        for (String stat : DebugOverviewHelper.GRAPH_STATS) {enabled.add(stat);}
        List<String> picked = DebugOverviewHelper.pickGraphStats(enabled);
        assertEquals(4, picked.size());
        assertEquals(java.util.Arrays.asList("bw.combined", "tunnel.buildSuccessRate",
                "tunnel.participatingTunnels", "router.activePeers"), picked);
    }

    @Test
    public void backfillsFromOtherEnabledStats() {
        java.util.Set<String> enabled = new java.util.HashSet<>();
        enabled.add("bw.sendRate");
        enabled.add("bw.recvRate");
        enabled.add("zzz.someStat");
        enabled.add("aaa.someStat");
        enabled.add("mmm.someStat");
        List<String> picked = DebugOverviewHelper.pickGraphStats(enabled);
        // no wishes enabled, backfill the row in name order
        assertEquals(java.util.Arrays.asList("bw.combined", "aaa.someStat",
                "mmm.someStat", "zzz.someStat"), picked);
        // bw rate singles are not duplicated next to the combined graph
        assertFalse(picked.contains("bw.sendRate"));
        assertFalse(picked.contains("bw.recvRate"));
    }

    @Test
    public void emptyEnabledMeansNoGraphs() {
        assertTrue(DebugOverviewHelper.pickGraphStats(java.util.Collections.<String>emptySet()).isEmpty());
        // one bw rate alone does not earn a combined graph
        java.util.Set<String> one = new java.util.HashSet<>();
        one.add("bw.sendRate");
        List<String> picked = DebugOverviewHelper.pickGraphStats(one);
        assertEquals(java.util.Arrays.asList("bw.sendRate"), picked);
    }

    @Test
    public void graphUrlsCarryRequiredPeriodExceptCombined() {
        List<String> stats = java.util.Arrays.asList("bw.combined", "tunnel.buildSuccessRate");
        String html = DebugOverviewHelper.buildGraphs(stats, 1234L, java.util.Collections.<String, String>emptyMap());
        assertTrue(html, html.contains("viewstat.jsp?stat=bw.combined&amp;periodCount=60"));
        assertFalse(html, html.contains("stat=bw.combined&amp;period="));
        assertTrue(html, html.contains("viewstat.jsp?stat=tunnel.buildSuccessRate&amp;period=60000"));
        // standard size with grid, legend and title hidden
        assertTrue(html, html.contains("&amp;width=600&amp;height=120&amp;hideTitle=true&amp;hideLegend=true"));
        assertFalse(html, html.contains("hideGrid"));
        assertTrue(html, html.contains("<a href=\"/stats\">"));
        assertTrue(html, html.contains("<a href=\"/stats?stat=tunnel.buildSuccessRate\">"));
        assertTrue(html, html.contains("&amp;t=1234"));
        assertEquals(2, html.split("<div class=graph-cell>", -1).length - 1);
    }

    @Test
    public void graphLabelsUseMapAndFallBackToStatName() {
        List<String> stats = java.util.Arrays.asList("bw.combined", "zzz.unknownStat");
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        labels.put("bw.combined", "Bandwidth");
        String html = DebugOverviewHelper.buildGraphs(stats, 1234L, labels);
        assertTrue(html, html.contains("<div class=graph-label>Bandwidth</div>"));
        // an unmapped (backfilled) stat keeps its raw name as the caption
        assertTrue(html, html.contains("<div class=graph-label>zzz.unknownStat</div>"));
        // the alt text stays the stat name even when the caption is a label
        assertTrue(html, html.contains("alt=\"bw.combined\""));
    }

    @Test
    public void everyPriorityStatHasALabel() {
        for (String stat : DebugOverviewHelper.GRAPH_STATS) {
            assertTrue(stat, DebugOverviewHelper.GRAPH_LABELS.containsKey(stat));
        }
        assertTrue(DebugOverviewHelper.GRAPH_LABELS.containsKey("bw.combined"));
    }
}
