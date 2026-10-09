package net.i2p.router.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the y-axis floor decision: what a graph's value axis is scaled against.
 *
 * <p>The production symptom this pins down was a {@code router.activePeers} graph whose data
 * lived between roughly 684 and 1094 rendering against labels of {@code 0} and {@code 1K}. The
 * trace occupied the top eighth of the plot and read as a flat line, and no amount of curve
 * smoothing could show a variation the axis had compressed out of view.
 *
 * <p>Everything under test is static and free of router context, RRD files and clocks.
 */
public class GraphRendererAxisFloorDecisionTest {

    /** Lowest value of the live {@code router.activePeers} window that prompted this. */
    private static final double LIVE_MIN = 684d;
    /** Highest value of that same window. */
    private static final double LIVE_MAX = 1094d;

    ///////////// no usable data

    /** An empty window (a fresh RRD, or a gap spanning the whole range) keeps the zero axis. */
    @Test
    public void testAllNaNWindowIsZeroFloored() {
        assertEquals(0d, GraphRenderer.axisFloor(Double.NaN, Double.NaN, false), 0d);
    }

    /**
     * One side missing is not a range either, and one missing side is enough to make any
     * padding arithmetic meaningless, so it falls back rather than guessing.
     */
    @Test
    public void testOneSidedNaNWindowIsZeroFloored() {
        assertEquals(0d, GraphRenderer.axisFloor(LIVE_MIN, Double.NaN, false), 0d);
        assertEquals(0d, GraphRenderer.axisFloor(Double.NaN, LIVE_MAX, false), 0d);
    }

    ///////////// degenerate ranges

    /**
     * A flat series has no range to scale: padding it by a tenth of nothing puts the floor
     * exactly on the data, which would draw a single horizontal line pinned to the bottom edge.
     */
    @Test
    public void testFlatWindowIsZeroFloored() {
        assertEquals(0d, GraphRenderer.axisFloor(900d, 900d, false), 0d);
        assertEquals(0d, GraphRenderer.axisFloor(0d, 0d, false), 0d);
    }

    /**
     * A range that arrives inverted is the same defect seen from the other side. It cannot
     * be produced by the single-pass scan, but the helper is fed numbers from outside, so it
     * answers 0 instead of a negative padding.
     */
    @Test
    public void testInvertedRangeIsZeroFloored() {
        assertEquals(0d, GraphRenderer.axisFloor(LIVE_MAX, LIVE_MIN, false), 0d);
    }

    ///////////// the floor is only moved for data that lives above zero

    /**
     * Data reaching below zero is measured against zero already, so the historical axis is
     * both correct and more readable than one that would crop the negative half.
     */
    @Test
    public void testNegativeMinimumIsZeroFloored() {
        assertEquals(0d, GraphRenderer.axisFloor(-5d, 100d, false), 0d);
        assertEquals(0d, GraphRenderer.axisFloor(-40d, -10d, false), 0d);
    }

    /** The escape hatch restores the zero axis whatever the data does. */
    @Test
    public void testForceZeroWins() {
        assertEquals(0d, GraphRenderer.axisFloor(LIVE_MIN, LIVE_MAX, true), 0d);
        assertEquals(0d, GraphRenderer.axisFloor(Double.NaN, Double.NaN, true), 0d);
        assertEquals(0d, GraphRenderer.axisFloor(-5d, 100d, true), 0d);
        assertEquals(0d, GraphRenderer.axisFloor(900d, 900d, true), 0d);
    }

    ///////////// the padded floor

    /** The reported case: a tenth of the range as headroom under the minimum. */
    @Test
    public void testPaddedFloor() {
        assertEquals(643d, GraphRenderer.axisFloor(LIVE_MIN, LIVE_MAX, false), 1e-9);
    }

    /**
     * The floor has to sit strictly under the data. If it reached the minimum, the value at
     * the bottom of the trace would be exactly on the axis and the padding would have
     * bought nothing. Windows starting at zero are the clamp case, not this one.
     */
    @Test
    public void testFloorIsAlwaysBelowTheData() {
        double[][] windows = {
            {LIVE_MIN, LIVE_MAX}, {1d, 2d}, {0.5d, 1000d}, {1d, 3d},
            {0.001d, 0.002d}, {1d, 1.0000001d}, {999999d, 1000001d}
        };
        for (double[] window : windows) {
            double floor = GraphRenderer.axisFloor(window[0], window[1], false);
            assertTrue("floor " + floor + " must be under " + window[0],
                       floor < window[0]);
        }
    }

    /**
     * Padding a window that is narrow relative to how far it sits from zero would land
     * below zero, where the axis would have to show negative labels for data that has none.
     * The clamp keeps the zero baseline in that case, which is where such a series reads
     * correctly anyway.
     */
    @Test
    public void testFloorIsClampedAtZero() {
        // 0 - (1 - 0) * 0.1 would be -0.1
        assertEquals(0d, GraphRenderer.axisFloor(0d, 1d, false), 0d);
        // 1 - (100 - 1) * 0.1 would be -8.9
        assertEquals(0d, GraphRenderer.axisFloor(1d, 100d, false), 0d);
    }

    /**
     * The clamp switches where the padding exactly consumes the distance to zero, that is at
     * a range ten times the minimum. On that boundary the floor is legitimately zero rather
     * than a clamped one, and a hair either side of it the two branches differ.
     */
    @Test
    public void testClampBoundary() {
        // 5 - (55 - 5) * 0.1 == 0 exactly
        assertEquals(0d, GraphRenderer.axisFloor(5d, 55d, false), 0d);
        // a hair under the boundary: the padding still fits
        assertEquals(0.01d, GraphRenderer.axisFloor(5.1d, 56d, false), 1e-9);
        // a hair over it: the padding would go negative, so the clamp takes over
        assertEquals(0d, GraphRenderer.axisFloor(5.1d, 56.2d, false), 0d);
    }

    /**
     * A window living far from zero but narrow still gets a floor, and the headroom stays
     * proportional to the range rather than to the magnitude.
     */
    @Test
    public void testPaddingIsProportionalToTheRange() {
        // 100000 - (100010 - 100000) * 0.1 = 99999
        assertEquals(99999d, GraphRenderer.axisFloor(100000d, 100010d, false), 1e-6);
        // 0.05 - (0.06 - 0.05) * 0.1 = 0.049
        assertEquals(0.049d, GraphRenderer.axisFloor(0.05d, 0.06d, false), 1e-12);
    }

    ///////////// the result is always usable as a setMinValue argument

    /**
     * rrd4j divides by the range it is given, so a NaN or an infinity here would blank the
     * graph rather than merely scale it badly.
     */
    @Test
    public void testResultIsNeverNaNAndNeverInfinite() {
        double[] mins = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                         -1e308d, -1d, 0d, 1d, 1e308d, LIVE_MIN};
        double[] maxes = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                          -1d, 0d, 1d, 1e308d, 1.7976931348623157e308d, LIVE_MAX};
        for (double min : mins) {
            for (double max : maxes) {
                for (boolean force : new boolean[] {false, true}) {
                    double floor = GraphRenderer.axisFloor(min, max, force);
                    assertFalse("NaN from min=" + min + " max=" + max, Double.isNaN(floor));
                    assertFalse("infinite from min=" + min + " max=" + max,
                                Double.isInfinite(floor));
                    assertTrue("negative floor from min=" + min + " max=" + max, floor >= 0d);
                }
            }
        }
    }

    /**
     * The range most extreme pair that could overflow the padding arithmetic - both ends of
     * the double range - has to land on a number rrd4j can still map values into.
     */
    @Test
    public void testExtremeRangeDoesNotOverflow() {
        double floor = GraphRenderer.axisFloor(1e308d, Double.MAX_VALUE, false);
        assertFalse(Double.isNaN(floor));
        assertFalse(Double.isInfinite(floor));
        assertTrue(floor >= 0d && floor < 1e308d);
    }

    /**
     * Every finite, ascending window that starts above zero gets a floor below its own data,
     * which is the property the graph actually depends on: rrd4j only honours a floor that is
     * under the data and snaps it down otherwise.
     */
    @Test
    public void testFloorStaysUnderEveryAscendingWindow() {
        double[][] windows = {
            {0.0001d, 0.0002d}, {7d, 8d}, {684d, 685d},
            {1234d, 987654d}, {1d, 2d}, {999d, 1000d}
        };
        for (double[] window : windows) {
            double floor = GraphRenderer.axisFloor(window[0], window[1], false);
            assertTrue("floor " + floor + " must be under " + window[0], floor < window[0]);
            assertTrue("floor " + floor + " must not be negative", floor >= 0d);
        }
    }
}
