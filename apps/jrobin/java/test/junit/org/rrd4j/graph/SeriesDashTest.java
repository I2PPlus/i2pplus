package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Stroke;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the dot pattern used to separate two series sharing an axis.
 *
 * <p>Two invariants matter and they pull against each other: the dot length is a theme
 * choice, but the gap cannot be, because a round cap extends each dot by half the stroke
 * width on both ends. Too small a gap and consecutive dots touch, which renders a solid
 * line from a "dotted" definition.
 *
 * @since 0.9.71
 */
public class SeriesDashTest {

    private static float[] dash(int seriesCount, float width) {
        Stroke s = RrdGraphConstants.seriesStroke(seriesCount, width);
        return ((BasicStroke) s).getDashArray();
    }

    private static float[] dash(int seriesCount, float width, float dot) {
        Stroke s = RrdGraphConstants.seriesStroke(seriesCount, width, dot);
        return ((BasicStroke) s).getDashArray();
    }

    private static float[] dash(int seriesCount, float width, float dot, float gap) {
        Stroke s = RrdGraphConstants.seriesStroke(seriesCount, width, dot, gap);
        return ((BasicStroke) s).getDashArray();
    }

    /** A lone series has nothing to be distinguished from, so it is not dashed. */
    @Test
    public void aLoneSeriesIsSolid() {
        assertNull(dash(1, 1.5f));
        assertNull(dash(0, 1.5f));
    }

    @Test
    public void twoSeriesAreDotted() {
        assertNotNull(dash(2, 1.5f));
    }

    /**
     * The core invariant: the gap must exceed the stroke width, or the round caps of
     * neighbouring dots overlap and the pattern reads solid.
     */
    @Test
    public void theGapAlwaysExceedsTheStrokeWidth() {
        for (float width : new float[] { 0.5f, 1f, 1.5f, 2f, 3f, 5f, 10f }) {
            float[] d = dash(2, width);
            assertNotNull("width " + width, d);
            assertTrue("width " + width + " gap " + d[1] + " must exceed " + width,
                       d[1] > width);
        }
    }

    /** A widened dot must not be allowed to close its own gap. */
    @Test
    public void aThemedDotKeepsTheGapDerivedFromTheWidth() {
        for (float dot : new float[] { 0.5f, 1f, 2f, 3f, 8f }) {
            for (float width : new float[] { 0.5f, 1.5f, 3f }) {
                float[] d = dash(2, width, dot);
                assertTrue("dot " + dot + " width " + width + " gap " + d[1],
                           d[1] > width);
                assertEquals("dot length must be honoured",
                             dot, d[0], 0.001f);
            }
        }
    }

    /** A nonsense value must not produce an invisible or unbounded pattern. */
    @Test
    public void aNonPositiveDotFallsBackToTheDefault() {
        for (float bad : new float[] { 0f, -1f, -100f }) {
            float[] d = dash(2, 1.5f, bad);
            assertEquals(RrdGraphConstants.SERIES_DOT, d[0], 0.001f);
            assertTrue(d[1] > 1.5f);
        }
    }

    // ---- an explicit gap, as a CSS "--graph_dash:1 3" pair states ----

    @Test
    public void aStatedGapIsHonouredWhenItIsWideEnough() {
        float[] d = dash(2, 1f, 1f, 3f);
        assertEquals(1f, d[0], 0.001f);
        assertEquals(3f, d[1], 0.001f);
        assertEquals(5f, dash(2, 1f, 2f, 5f)[1], 0.001f);
    }

    /**
     * A stated gap at or below the stroke width would merge the dots, so it is raised to
     * the minimum. This is the whole reason a bare length is the safer form.
     */
    @Test
    public void aStatedGapTooTightToSeparateDotsIsRaised() {
        for (float width : new float[] { 1f, 1.5f, 3f, 6f }) {
            float minimum = RrdGraphConstants.minimumDashGap(1f, width);
            assertEquals("gap " + width, minimum, dash(2, width, 1f, 0.1f)[1], 0.001f);
            assertEquals("gap " + width, minimum, dash(2, width, 1f, width)[1], 0.001f);
        }
    }

    @Test
    public void anUnstatedGapIsDerivedTheSameWay() {
        for (float width : new float[] { 1f, 1.5f, 3f }) {
            assertEquals(RrdGraphConstants.minimumDashGap(1f, width),
                         dash(2, width, 1f, 0f)[1], 0.001f);
            assertEquals(RrdGraphConstants.minimumDashGap(1f, width),
                         dash(2, width, 1f, -5f)[1], 0.001f);
        }
    }

    /** The minimum is twice the width, or twice the dot when the dot is the larger. */
    @Test
    public void theMinimumGapFollowsTheLargerOfDotAndWidth() {
        assertEquals(4f, RrdGraphConstants.minimumDashGap(2f, 1f), 0.001f);
        assertEquals(6f, RrdGraphConstants.minimumDashGap(1f, 3f), 0.001f);
        // Dot and width both 1: twice either is 2, so that is the floor.
        assertEquals(2f, RrdGraphConstants.minimumDashGap(1f, 1f), 0.001f);
    }

    /** Round caps and joins are what make a dash read as dots rather than dashes. */
    @Test
    public void dotsUseRoundCapsAndJoins() {
        BasicStroke s = (BasicStroke) RrdGraphConstants.seriesStroke(2, 1.5f);
        assertEquals(BasicStroke.CAP_ROUND, s.getEndCap());
        assertEquals(BasicStroke.JOIN_ROUND, s.getLineJoin());
    }

    /** The stroke width itself is the caller's choice and must pass through untouched. */
    @Test
    public void theRequestedWidthIsPreserved() {
        for (float width : new float[] { 0.5f, 1.5f, 3f }) {
            assertEquals(width,
                         ((BasicStroke) RrdGraphConstants.seriesStroke(2, width)).getLineWidth(),
                         0.001f);
            assertEquals(width,
                         ((BasicStroke) RrdGraphConstants.seriesStroke(1, width)).getLineWidth(),
                         0.001f);
        }
    }
}