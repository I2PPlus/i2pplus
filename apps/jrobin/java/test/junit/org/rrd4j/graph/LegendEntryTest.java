package org.rrd4j.graph;

import java.awt.Color;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that one plotted series produces exactly one legend row.
 *
 * <p>A legend row is emitted per plot element that carries legend text, not per series. A
 * series drawn as a filled path plus a thin outline is two plot elements on one datasource,
 * so giving both the same text listed the series twice in every filled-path graph. The
 * caller has to pass the legend to exactly one of them.
 *
 * @since 0.9.71+
 */
public class LegendEntryTest {

    /** Legend rows recorded on the definition. */
    private static int legendRows(RrdGraphDef def) {
        int n = 0;
        List<CommentText> comments = def.comments;
        for (CommentText c : comments) {
            if (c instanceof LegendText) {n++;}
        }
        return n;
    }

    private static RrdGraphDef def() {
        RrdGraphDef d = new RrdGraphDef();
        d.setStartTime(0);
        d.setEndTime(1000);
        return d;
    }

    @Test
    public void aLineAloneIsOneLegendRow() {
        RrdGraphDef d = def();
        d.line("src", Color.RED, "Peers\\l", 1f);
        assertEquals(1, legendRows(d));
    }

    @Test
    public void anAreaAloneIsOneLegendRow() {
        RrdGraphDef d = def();
        d.area("src", Color.RED, "Peers\\l");
        assertEquals(1, legendRows(d));
    }

    /** The regression: both elements of one series were given the same legend text. */
    @Test
    public void aFilledPathWithAnOutlineIsStillOneLegendRow() {
        RrdGraphDef d = def();
        d.area("src", new Color(0, 0, 0, 128));
        d.line("src", Color.RED, "Peers\\l", 1f);
        assertEquals("a fill plus an outline is one series, so one legend row",
                     1, legendRows(d));
    }

    @Test
    public void twoSeriesMakeTwoLegendRows() {
        RrdGraphDef d = def();
        d.area("src", new Color(0, 0, 0, 128));
        d.line("src", Color.RED, "First\\l", 1f);
        d.area("src", new Color(200, 200, 0, 128));
        d.line("src", Color.YELLOW, "Second\\l", 1f);
        assertEquals(2, legendRows(d));
    }

    /** Two series sharing a name would be indistinguishable, whatever the row count. */
    @Test
    public void twoSeriesMayCarryDifferentText() {
        RrdGraphDef d = def();
        d.line("src", Color.RED, "Inbound\\l", 1f);
        d.line("src", Color.YELLOW, "Outbound\\l", 1f);
        List<CommentText> texts = d.comments;
        boolean sawIn = false, sawOut = false;
        for (CommentText c : texts) {
            if (!(c instanceof LegendText)) {continue;}
            String t = ((LegendText) c).text;
            sawIn |= t.contains("Inbound");
            sawOut |= t.contains("Outbound");
        }
        assertTrue("expected the inbound row", sawIn);
        assertTrue("expected the outbound row", sawOut);
    }
}