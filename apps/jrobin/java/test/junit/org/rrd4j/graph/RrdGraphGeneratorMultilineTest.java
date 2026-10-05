package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Stroke;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the series stroke.
 *
 * <p>Every plotted series is dotted, on a single-stat graph as well as a multi-series one,
 * so a graph looks the same whether it plots one stat or several. Overlapping series are
 * then separable by texture and not only by colour.
 *
 * @since 0.9.71+
 */
public class RrdGraphGeneratorMultilineTest {

    private static Stroke stroke(float width) {
        return RrdGraphConstants.seriesStroke(2, width);
    }

    /** A lone series is solid: there is nothing for dots to separate it from. */
    @Test
    public void aLoneSeriesIsSolid() {
        assertNull(((BasicStroke) RrdGraphConstants.seriesStroke(1, 1f)).getDashArray());
        assertNull(((BasicStroke) RrdGraphConstants.seriesStroke(0, 1f)).getDashArray());
    }

    @Test
    public void twoOrMoreSeriesAreDotted() {
        for (int n = 2; n <= 6; n++) {
            assertNotNull("series count " + n + " must be dotted",
                          ((BasicStroke) RrdGraphConstants.seriesStroke(n, 1f)).getDashArray());
        }
    }

    @Test
    public void theDotIsOnePixelOfInk() {
        assertEquals(1f, ((BasicStroke) stroke(1f)).getDashArray()[0], 0f);
    }

    /**
     * A round cap adds half the stroke width to both ends of every dash, so the visible
     * gap is the pattern gap minus the width. With a one-on-one-off pattern at 1.5px the
     * ink is 2.5px in a 2px period and consecutive dots overlap: dotted in the markup,
     * solid on screen. The gap has to clear the width.
     */
    @Test
    public void aSolidLoneSeriesKeepsItsWidth() {
        assertEquals(1.5f, ((BasicStroke) RrdGraphConstants.seriesStroke(1, 1.5f)).getLineWidth(), 0f);
    }
    @Test
    public void theGapClearsTheStrokeWidthSoDotsStaySeparate() {
        for (float w : new float[] { 1f, 1.5f, 2f, 3f, 5f }) {
            float[] dash = ((BasicStroke) stroke(w)).getDashArray();
            float visibleGap = dash[1] - w;
            assertTrue("width " + w + ": gap " + dash[1] + " leaves only " + visibleGap
                       + "px between dots", visibleGap >= w);
        }
    }

    @Test
    public void everyWidthGetsTheSameDotLength() {
        assertEquals(((BasicStroke) stroke(1f)).getDashArray()[0],
                     ((BasicStroke) stroke(4f)).getDashArray()[0], 0f);
    }

    /** The user asked for this explicitly: caps and joins both curved. */
    @Test
    public void capsAreRoundSoEachDashReadsAsADot() {
        assertEquals("a butt cap draws a 1px rectangle, not a dot",
                     BasicStroke.CAP_ROUND, ((BasicStroke) stroke(1f)).getEndCap());
    }

    @Test
    public void joinsAreRoundToo() {
        assertEquals(BasicStroke.JOIN_ROUND, ((BasicStroke) stroke(1f)).getLineJoin());
    }

    @Test
    public void theRequestedWidthIsPreserved() {
        for (float w : new float[] { 1f, 1.5f, 2.5f, 3f }) {
            assertEquals("width must survive the dash conversion",
                         w, ((BasicStroke) stroke(w)).getLineWidth(), 0f);
        }
    }

    @Test
    public void aVeryThinLineStillRoundsItsCaps() {
        assertEquals(BasicStroke.CAP_ROUND, ((BasicStroke) RrdGraphConstants.seriesStroke(2, 0.5f)).getEndCap());
    }
}