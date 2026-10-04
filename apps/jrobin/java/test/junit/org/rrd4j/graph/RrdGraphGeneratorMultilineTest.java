package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the multi-series line treatment.
 *
 * <p>A graph carrying more than one series draws each line dotted instead of solid, so
 * two series that cross are separable by texture and not only by hue. A single-series
 * graph keeps the solid line, where dashes would be pure noise.
 *
 * @since 0.9.71
 */
public class RrdGraphGeneratorMultilineTest {

    private static Stroke strokeFor(int lineCount) {
        return RrdGraphConstants.multilineStroke(lineCount, 1f);
    }

    // ---- stroke selection ----

    @Test
    public void aSingleSeriesStaysSolid() {
        Stroke s = strokeFor(1);
        assertEquals("a lone series must not be dotted",
                     null, ((BasicStroke) s).getDashArray());
    }

    @Test
    public void twoSeriesGetADashPattern() {
        Stroke s = strokeFor(2);
        assertNotNull("overlapping series need a texture to tell them apart",
                      ((BasicStroke) s).getDashArray());
    }

    @Test
    public void theDashPatternIsOneOnOneOff() {
        float[] dash = ((BasicStroke) strokeFor(2)).getDashArray();
        assertEquals(2, dash.length);
        assertEquals("one pixel of ink", 1f, dash[0], 0f);
        assertEquals("one pixel of gap", 1f, dash[1], 0f);
    }

    @Test
    public void capsAreRoundSoEachDashReadsAsADot() {
        assertEquals("a butt cap draws a rectangle, not a dot",
                     BasicStroke.CAP_ROUND, ((BasicStroke) strokeFor(2)).getEndCap());
    }

    @Test
    public void joinsAreRoundToo() {
        assertEquals(BasicStroke.JOIN_ROUND, ((BasicStroke) strokeFor(2)).getLineJoin());
    }

    @Test
    public void theRequestedWidthIsPreserved() {
        for (int n = 0; n <= 3; n++) {
            assertEquals("width must survive the dash conversion", 2.5f,
                         ((BasicStroke) RrdGraphConstants.multilineStroke(n, 2.5f)).getLineWidth(), 0f);
        }
    }

    @Test
    public void zeroSeriesIsTreatedAsSingle() {
        assertNull(((BasicStroke) strokeFor(0)).getDashArray());
    }

    @Test
    public void manySeriesStillUseOnePattern() {
        // The dash is a texture cue, not a per-series identifier; colour does that job.
        float[] two = ((BasicStroke) strokeFor(2)).getDashArray();
        float[] eight = ((BasicStroke) strokeFor(8)).getDashArray();
        assertArrayEquals(two, eight, 0f);
    }

    // ---- series counting ----

    private static List<PlotElement> lines(int n) {
        List<PlotElement> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Line("s" + i, java.awt.Color.BLACK, new BasicStroke(1), null));
        }
        return out;
    }

    @Test
    public void countsLineSeries() {
        assertEquals(3, RrdGraphGenerator.countLineSeries(lines(3)));
    }

    @Test
    public void aStackIsNotALineSeries() {
        // A stack is one quantity in bands; dotting it would imply separate series.
        List<PlotElement> elems = new ArrayList<>();
        elems.add(new Line("a", java.awt.Color.BLACK, new BasicStroke(1), null));
        elems.add(new Area("b", java.awt.Color.RED, null));
        assertEquals(1, RrdGraphGenerator.countLineSeries(elems));
    }

    @Test
    public void anEmptyGraphHasNoSeries() {
        assertEquals(0, RrdGraphGenerator.countLineSeries(new ArrayList<PlotElement>()));
    }

    @Test
    public void aNullListIsTolerated() {
        assertEquals(0, RrdGraphGenerator.countLineSeries(null));
    }

    @Test
    public void countingDrivesTheDashDecision() {
        int n = RrdGraphGenerator.countLineSeries(lines(2));
        assertNotNull(((BasicStroke) strokeFor(n)).getDashArray());
        int one = RrdGraphGenerator.countLineSeries(lines(1));
        assertNull(((BasicStroke) strokeFor(one)).getDashArray());
    }
}