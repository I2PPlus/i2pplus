package org.rrd4j.graph;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Tests for {@link MonotoneSpline}, the interpolation behind the optional bezier plot renderer.
 *
 * <p>The properties pinned here are the ones the smoothed renderer relies on: no overshoot, and
 * genuinely flat data staying flat. A regression in either shows up as a visual anomaly rather
 * than an exception, so both are asserted numerically over densely sampled curves.
 */
public class MonotoneSplineTest {

    private static final double EPS = 1e-9;
    /** Sub-steps per cubic segment used when scanning a curve. */
    private static final int SCAN_STEPS = 64;

    /**
     * Walks a path, densely sampling every cubic segment. The start point of each cubic is the
     * point already emitted, so only t = 1/SCAN_STEPS .. 1 are added per segment.
     *
     * @param path the path to walk
     * @return array of x,y pairs, flat, length 2 * pointCount
     */
    private static double[] sample(GeneralPath path) {
        List<double[]> pts = new ArrayList<>();
        PathIterator it = path.getPathIterator(null);
        double[] c = new double[6];
        double curX = 0, curY = 0;
        while (!it.isDone()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO:
                case PathIterator.SEG_LINETO:
                    pts.add(new double[] {c[0], c[1]});
                    curX = c[0];
                    curY = c[1];
                    break;
                case PathIterator.SEG_CUBICTO: {
                    double x0 = curX, y0 = curY;
                    double x1 = c[0], y1 = c[1], x2 = c[2], y2 = c[3], x3 = c[4], y3 = c[5];
                    for (int s = 1; s <= SCAN_STEPS; s++) {
                        double t = (double) s / SCAN_STEPS;
                        double u = 1 - t;
                        double b0 = u * u * u, b1 = 3 * u * u * t, b2 = 3 * u * t * t, b3 = t * t * t;
                        pts.add(new double[] {
                                b0 * x0 + b1 * x1 + b2 * x2 + b3 * x3,
                                b0 * y0 + b1 * y1 + b2 * y2 + b3 * y3});
                    }
                    curX = x3;
                    curY = y3;
                    break;
                }
                default:
                    break;
            }
            it.next();
        }
        double[] out = new double[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            out[i * 2] = pts.get(i)[0];
            out[i * 2 + 1] = pts.get(i)[1];
        }
        return out;
    }

    /**
     * Builds the smoothed path the way the renderer does: tangents, then a forward walk.
     *
     * @param xv vertex x coordinates
     * @param yv vertex y coordinates
     * @return the path
     */
    private static GeneralPath smooth(int[] xv, int[] yv) {
        double[] m = MonotoneSpline.tangents(toDouble(xv), toDouble(yv), 0, xv.length);
        GeneralPath path = new GeneralPath();
        path.moveTo(xv[0], yv[0]);
        MonotoneSpline.appendForward(path, xv, yv, m);
        return path;
    }

    /**
     * @param iv source coordinates
     * @return the values widened to double
     */
    private static double[] toDouble(int[] iv) {
        double[] out = new double[iv.length];
        for (int i = 0; i < iv.length; i++) {
            out[i] = iv[i];
        }
        return out;
    }

    /** An empty range yields no tangents rather than throwing. */
    @Test
    public void testTangentsEmptyRange() {
        assertEquals(0, MonotoneSpline.tangents(new double[] {1}, new double[] {2}, 0, 0).length);
        assertEquals(0, MonotoneSpline.tangents(new double[] {1}, new double[] {2}, 1, 1).length);
    }

    /** A lone vertex has no direction, so its slope is zero. */
    @Test
    public void testTangentsSingleVertex() {
        double[] m = MonotoneSpline.tangents(new double[] {5}, new double[] {9}, 0, 1);
        assertEquals(1, m.length);
        assertEquals(0.0, m[0], EPS);
    }

    /** Two vertices interpolate linearly, so both end slopes equal the secant. */
    @Test
    public void testTangentsTwoVerticesIsStraightLine() {
        double[] m = MonotoneSpline.tangents(new double[] {0, 10}, new double[] {0, 20}, 0, 2);
        assertEquals(2, m.length);
        assertEquals(2.0, m[0], EPS);
        assertEquals(2.0, m[1], EPS);
    }

    /**
     * The headline requirement: a plateau flanked by two step changes must produce zero tangents
     * at every vertex, so the flat run renders as a straight horizontal line and only the two
     * transitions are curved.
     */
    @Test
    public void testPlateauTangentsAreZero() {
        double[] m = MonotoneSpline.tangents(
                new double[] {0, 1, 2, 3, 4, 5},
                new double[] {100, 100, 100, 40, 40, 40}, 0, 6);
        for (int i = 0; i < m.length; i++) {
            assertEquals("plateau vertex " + i + " must be flat", 0.0, m[i], EPS);
        }
    }

    /** A completely flat series must produce an entirely flat curve. */
    @Test
    public void testEntirelyFlatSeriesStaysFlat() {
        int[] yv = new int[40];
        java.util.Arrays.fill(yv, 77);
        int[] xv = new int[40];
        for (int i = 0; i < 40; i++) {
            xv[i] = i * 3;
        }
        double[] pts = sample(smooth(xv, yv));
        for (int i = 0; i < pts.length / 2; i++) {
            assertEquals("flat series must not bow", 77.0, pts[i * 2 + 1], 0.5);
        }
    }

    /**
     * With every tangent zero the transition between two plateaus is a smoothstep: it eases out
     * of the first flat run, crosses, and eases into the second, never leaving the band between
     * the two values.
     */
    @Test
    public void testPlateauTransitionStaysWithinBand() {
        int[] xv = {0, 2, 3, 5};
        int[] yv = {100, 100, 40, 40};
        double[] pts = sample(smooth(xv, yv));
        for (int i = 0; i < pts.length / 2; i++) {
            double y = pts[i * 2 + 1];
            assertTrue("must not overshoot above the top plateau, was " + y, y <= 100.5);
            assertTrue("must not overshoot below the bottom plateau, was " + y, y >= 39.5);
        }
        // The flat leading run must be level.
        assertEquals(100.0, pts[1], 0.5);
        assertEquals(100.0, pts[3], 0.5);
    }

    /** A local maximum must not be exceeded; this is the anti-overshoot guarantee. */
    @Test
    public void testNoOvershootAboveLocalMaximum() {
        double[] x = {0, 1, 2, 3, 4, 5};
        double[] y = {10, 90, 20, 80, 30, 70};
        double[] m = MonotoneSpline.tangents(x, y, 0, 6);
        assertEquals("peak tangent must be flat", 0.0, m[2], EPS);
        assertEquals("peak tangent must be flat", 0.0, m[4], EPS);

        int[] xv = new int[6], yv = new int[6];
        for (int i = 0; i < 6; i++) {
            xv[i] = (int) x[i];
            yv[i] = (int) y[i];
        }
        double[] pts = sample(smooth(xv, yv));
        for (int i = 0; i < pts.length / 2; i++) {
            assertTrue("curve must never exceed the data maximum, was " + pts[i * 2 + 1],
                    pts[i * 2 + 1] <= 90.5);
            assertTrue("curve must never dip below the data minimum, was " + pts[i * 2 + 1],
                    pts[i * 2 + 1] >= 9.5);
        }
    }

    /**
     * A one-sample spike is the classic case where an unconstrained spline shoots far past the
     * peak. The limiter must keep the curve inside the data range.
     */
    @Test
    public void testNoOvershootOnSharpSpike() {
        int[] xv = {0, 1, 2, 3, 4, 5, 6, 7};
        int[] yv = {50, 50, 50, 0, 50, 50, 50, 50};
        double[] pts = sample(smooth(xv, yv));
        for (int i = 0; i < pts.length / 2; i++) {
            assertTrue("spike must not undershoot, was " + pts[i * 2 + 1], pts[i * 2 + 1] >= -0.5);
            assertTrue("spike must not overshoot, was " + pts[i * 2 + 1], pts[i * 2 + 1] <= 50.5);
        }
    }

    /**
     * Alternating extremes are the worst case for overshoot: every interior vertex is a local
     * extremum, so every tangent must be zeroed.
     */
    @Test
    public void testNoOvershootOnAlternatingExtremes() {
        int n = 21;
        int[] xv = new int[n];
        int[] yv = new int[n];
        for (int i = 0; i < n; i++) {
            xv[i] = i;
            yv[i] = (i % 2 == 0) ? 0 : 100;
        }
        double[] pts = sample(smooth(xv, yv));
        for (int i = 0; i < pts.length / 2; i++) {
            assertTrue("alternating data must not overshoot, was " + pts[i * 2 + 1],
                    pts[i * 2 + 1] >= -0.5 && pts[i * 2 + 1] <= 100.5);
        }
    }

    /**
     * Non-uniform x, which is what a downsampler produces, must still give a curve that is
     * monotone in y within each stretch of same-sign data.
     */
    @Test
    public void testNonUniformSpacingStaysMonotone() {
        double[] x = {0, 1, 5, 6, 20, 21, 22};
        double[] y = {100, 90, 40, 30, 10, 20, 70};
        int[] xv = new int[x.length], yv = new int[y.length];
        for (int i = 0; i < x.length; i++) {
            xv[i] = (int) x[i];
            yv[i] = (int) y[i];
        }
        double[] pts = sample(smooth(xv, yv));
        for (int i = 1; i < pts.length / 2; i++) {
            if (pts[i * 2] <= 20) {
                assertTrue("descending stretch must stay descending",
                        pts[i * 2 + 1] <= pts[(i - 1) * 2 + 1] + 0.5);
            }
        }
    }

    /**
     * Uniformly scaling the data must scale the tangents by the same factor. This is the
     * observable face of the limiter being homogeneous, and it is what stops the curve's shape
     * from depending on the unit exponent chosen for the y axis.
     */
    @Test
    public void testScalingDataScalesTangents() {
        double[] x = {0, 1, 2, 3};
        double[] m = MonotoneSpline.tangents(x, new double[] {100, 20, 90, 10}, 0, 4);
        double[] m2 = MonotoneSpline.tangents(x, new double[] {200, 40, 180, 20}, 0, 4);
        for (int i = 0; i < m.length; i++) {
            assertEquals("tangent " + i + " must scale with the data", 2 * m[i], m2[i], 1e-6);
        }
    }

    /**
     * The curve must never leave the horizontal span of the two vertices it joins, whatever the
     * slopes are. A control point whose x offset was derived from the slope rather than from the
     * span used to push the curve well outside the plot area on widely uneven sampling.
     */
    @Test
    public void testCurveStaysWithinHorizontalSpan() {
        // Steep slopes riding on a wide interval, which is what a downsampler produces.
        double[] x = {0, 1, 5, 6, 20, 21, 22};
        double[] y = {100, 90, 40, 30, 10, 20, 70};
        int[] xv = new int[x.length], yv = new int[y.length];
        for (int i = 0; i < x.length; i++) {
            xv[i] = (int) x[i];
            yv[i] = (int) y[i];
        }
        double[] pts = sample(smooth(xv, yv));
        for (int i = 0; i < pts.length / 2; i++) {
            double px = pts[i * 2];
            assertTrue("sampled x " + px + " left the plot range", px >= xv[0] - 0.5);
            assertTrue("sampled x " + px + " exceeded the plot range",
                    px <= xv[xv.length - 1] + 0.5);
        }
    }

    /**
     * Control point x offsets must come from the span, never from the slope, so that x advances
     * linearly across each cubic.
     */
    @Test
    public void testControlPointXOffsetIsSpanThird() {
        int[] xv = {0, 30};
        int[] yv = {100, 0};
        double[] m = MonotoneSpline.tangents(toDouble(xv), toDouble(yv), 0, 2);
        GeneralPath path = new GeneralPath();
        path.moveTo(xv[0], yv[0]);
        MonotoneSpline.appendForward(path, xv, yv, m);
        double[] c = new double[6];
        PathIterator it = path.getPathIterator(null);
        it.next();
        assertEquals(PathIterator.SEG_CUBICTO, it.currentSegment(c));
        // span is 30, so each control point sits 10 px along in x, regardless of the slope
        assertEquals(10.0, c[0], EPS);
        assertEquals(20.0, c[2], EPS);
        assertEquals(30.0, c[4], EPS);
    }

    /**
     * Pins the specific non-additivity that documents why stacked bands are not smoothed:
     * subtracting the two curves does not reproduce the interpolation of their difference.
     */
    @Test
    public void testGapTangentsDifferFromSubtraction() {
        double[] x = {0, 1, 2, 3, 4, 5};
        double[] lower = {40, 10, 30, 10, 50, 20};
        double[] gap = {50, 70, 45, 60, 45, 50};
        double[] sum = new double[6];
        for (int i = 0; i < 6; i++) {
            sum[i] = lower[i] + gap[i];
        }
        double[] mLower = MonotoneSpline.tangents(x, lower, 0, 6);
        double[] mSum = MonotoneSpline.tangents(x, sum, 0, 6);
        double[] mGap = MonotoneSpline.tangents(x, gap, 0, 6);
        // At index 1 the lower series has a local minimum so its tangent is zeroed, while the sum
        // is still descending on both sides and keeps a nonzero tangent. Additivity would require
        // them to match.
        assertEquals(0.0, mLower[1], EPS);
        assertFalse("sum keeps a slope where the lower series is flat",
                Math.abs(mSum[1]) < 1e-9);
        assertEquals(0.0, mGap[1], EPS);
        assertFalse("tangents are therefore not additive",
                Math.abs((mSum[1] - mLower[1]) - mGap[1]) < 1e-9);
    }

    /**
     * Reverse traversal must retrace the forward curve exactly, otherwise the outline of a filled
     * band would not close on itself.
     */
    @Test
    public void testReverseWalkMirrorsForwardWalk() {
        int[] xv = {0, 4, 9, 15};
        int[] yv = {100, 100, 40, 40};
        double[] m = MonotoneSpline.tangents(toDouble(xv), toDouble(yv), 0, xv.length);

        GeneralPath fwd = new GeneralPath();
        fwd.moveTo(xv[0], yv[0]);
        MonotoneSpline.appendForward(fwd, xv, yv, m);

        GeneralPath rev = new GeneralPath();
        rev.moveTo(xv[xv.length - 1], yv[xv.length - 1]);
        MonotoneSpline.appendReverse(rev, xv, yv, m);

        double[] a = sample(fwd);
        double[] b = sample(rev);
        assertEquals("point counts must match", a.length, b.length);
        int n = a.length / 2;
        for (int i = 0; i < n; i++) {
            assertEquals("x at " + i, a[i * 2], b[(n - 1 - i) * 2], EPS);
            assertEquals("y at " + i, a[i * 2 + 1], b[(n - 1 - i) * 2 + 1], EPS);
        }
    }

    // ---- sub-pixel transition widening ----

    /**
     * A transition with no width is given one, so it can be drawn as a curve instead of a
     * vertical line. This is the case that appears once a graph plots more periods than pixels.
     * The transition has to sit inside the run, since the run's end intervals are its anchors.
     */
    @Test
    public void testWidenGivesZeroWidthTransitionRoom() {
        int[] xv = {10, 20, 20, 30};
        int[] yv = {100, 100, 40, 40};
        int n = MonotoneSpline.widenTransitions(xv, yv, 3);
        assertEquals("one transition should widen", 1, n);
        assertTrue("transition must gain width", xv[2] - xv[1] >= 2);
    }

    /** A flat run is not a transition and must be left exactly where it is. */
    @Test
    public void testWidenLeavesFlatRunsAlone() {
        int[] xv = {0, 10, 20};
        int[] yv = {100, 100, 100};
        assertEquals(0, MonotoneSpline.widenTransitions(xv, yv, 3));
        assertArrayEquals(new int[] {0, 10, 20}, xv);
    }

    /**
     * Widening may only take width from the plateaus beside a transition; the vertices of those
     * plateaus must not move, and the vertex order must stay strictly increasing.
     */
    @Test
    public void testWidenPreservesOrderAndPlateauVertices() {
        //        flat      step    flat(zero width)  step(zero width)  flat
        int[] xv = {0, 20, 30, 30, 30, 60};
        int[] yv = {100, 100, 40, 40, 90, 90};
        int[] before = xv.clone();
        int n = MonotoneSpline.widenTransitions(xv, yv, 4);
        // Only the 40 -> 90 change has zero width; the 30 -> 30 pair is a flat run.
        assertEquals("the one zero-width transition should widen", 1, n);
        // A zero-width flat run keeps equal x, so order is non-decreasing rather than strict.
        for (int i = 1; i < xv.length; i++) {
            assertTrue("x must not go backwards at " + i, xv[i] >= xv[i - 1]);
        }
        // Widened transitions gain width; every widened interval must be strictly ordered.
        for (int i = 1; i + 1 < xv.length; i++) {
            if (xv[i + 1] != before[i + 1] || xv[i] != before[i]) {
                assertTrue("a widened interval must keep a positive width at " + i,
                        xv[i + 1] > xv[i]);
            }
        }
        // The zero-width flat run between the first step and the second transition is not a
        // transition, so it keeps its position.
        assertEquals("a flat run must not move", 30, xv[3]);
        assertEquals("first vertex anchors the series", before[0], xv[0]);
        assertEquals("last vertex anchors the series", before[xv.length - 1], xv[xv.length - 1]);
    }

    /**
     * Widening must not let a transition eat its whole plateau: each side gives up at most half
     * the span it had, so a flat stretch always keeps a visible core.
     */
    @Test
    public void testWidenNeverConsumesAWholePlateau() {
        //  step    wide flat run    step     step   flat
        int[] xv = {0, 30, 30, 60, 60, 90};
        int[] yv = {40, 40, 90, 90, 30, 30};
        assertEquals("both interior transitions should widen", 2,
                MonotoneSpline.widenTransitions(xv, yv, 20));
        assertTrue("left transition must gain width", xv[2] - xv[1] > 0);
        assertTrue("right transition must gain width", xv[4] - xv[3] > 0);
        // each side gives up at most half its span, so the plateau between them keeps a core
        assertTrue("plateau must keep a visible core", xv[3] - xv[2] >= 10);
    }

    /** Nothing to borrow means nothing is done, rather than collapsing vertices together. */
    @Test
    public void testWidenIsANoOpWhenThereIsNoRoom() {
        int[] xv = {5, 5, 6, 6, 7};
        int[] yv = {100, 40, 40, 90, 90};
        int[] before = xv.clone();
        assertEquals("nothing may widen when neighbours are adjacent",
                0, MonotoneSpline.widenTransitions(xv, yv, 3));
        assertArrayEquals("positions must be untouched", before, xv);
    }

    /** A width below two pixels cannot produce a usable curve, so it is refused. */
    @Test
    public void testWidenRefusesDegenerateWidth() {
        int[] xv = {10, 10, 20};
        int[] yv = {100, 40, 40};
        assertEquals(0, MonotoneSpline.widenTransitions(xv, yv, 1));
        assertArrayEquals(new int[] {10, 10, 20}, xv);
    }

    /**
     * End to end: a widened transition must come out as a curve, must stay within the two values
     * it joins, and must not push the series outside its own horizontal span.
     */
    @Test
    public void testWidenedTransitionDrawsWithinItsValueRange() {
        int[] xv = {100, 150, 150, 250, 250, 300};
        int[] yv = {10, 10, 90, 90, 20, 20};
        MonotoneSpline.widenTransitions(xv, yv, 3);
        assertTrue("transition gained width", xv[2] - xv[1] > 0);
        double[] m = MonotoneSpline.tangents(toDouble(xv), toDouble(yv), 0, xv.length);
        GeneralPath p = new GeneralPath();
        p.moveTo(xv[0], yv[0]);
        MonotoneSpline.appendForward(p, xv, yv, m);
        double[] pts = sample(p);
        for (int i = 0; i < pts.length / 2; i++) {
            double y = pts[i * 2 + 1];
            assertTrue("must stay within the data range, was " + y, y >= 9.5 && y <= 90.5);
            assertTrue("must stay within the horizontal span, was " + pts[i * 2],
                    pts[i * 2] >= 100 - 0.5 && pts[i * 2] <= 300 + 0.5);
        }
    }

    // ---- the run's anchor intervals are never widened ----

    /**
     * The first interval of a run is where the trace starts. Widening it pushed the start of the
     * trace to the right of the plot area's left edge, so it must be left exactly where the data
     * put it - including when the series has no plateau before it to borrow width from.
     */
    @Test
    public void testWidenLeavesLeadingIntervalAlone() {
        int[] xv = {40, 40, 90, 90};
        int[] yv = {100, 20, 20, 60};
        int[] before = xv.clone();
        assertEquals("the leading interval must not widen",
                0, MonotoneSpline.widenTransitions(xv, yv, 8));
        assertArrayEquals("the start of the run must not move", before, xv);
    }

    /**
     * The mirror of the leading edge: the last interval is where the trace ends, and widening it
     * ran the tail of the series past the plot area's right edge.
     */
    @Test
    public void testWidenLeavesTrailingIntervalAlone() {
        int[] xv = {10, 10, 40, 40};
        int[] yv = {60, 60, 20, 100};
        int[] before = xv.clone();
        assertEquals("the trailing interval must not widen",
                0, MonotoneSpline.widenTransitions(xv, yv, 8));
        assertArrayEquals("the end of the run must not move", before, xv);
    }

    /**
     * Both anchors must survive any input, including a series made entirely of zero-width
     * transitions. Both endpoints are what the plot area's boundaries are measured against.
     */
    @Test
    public void testWidenNeverMovesTheEndpoints() {
        // every column holds several changes, so every interval qualifies but none is interior
        int[] xv = {100, 100, 101, 101, 102};
        int[] yv = {10, 40, 90, 20, 70};
        int first = xv[0];
        int last = xv[xv.length - 1];
        MonotoneSpline.widenTransitions(xv, yv, 4);
        assertEquals("the first vertex is the run's anchor", first, xv[0]);
        assertEquals("the last vertex is the run's anchor", last, xv[xv.length - 1]);
    }

    /**
     * The anchor rule itself: an interval qualifies only when it is a value change with no width
     * and it is not one of the run's two end intervals.
     */
    @Test
    public void testIsInteriorTransition() {
        int[] xv = {10, 20, 20, 30};
        int[] yv = {100, 100, 40, 40};
        assertFalse("the leading interval is the run's anchor",
                MonotoneSpline.isInteriorTransition(xv, yv, 0));
        assertTrue("a zero-width value change inside the run qualifies",
                MonotoneSpline.isInteriorTransition(xv, yv, 1));
        assertFalse("the trailing interval is the run's anchor",
                MonotoneSpline.isInteriorTransition(xv, yv, 2));
        assertFalse("an index past the end has no interval",
                MonotoneSpline.isInteriorTransition(xv, yv, 3));
        assertFalse("a negative index has no interval",
                MonotoneSpline.isInteriorTransition(xv, yv, -1));
        // a flat run and a spaced-out change are both left alone
        int[] flat = {10, 20, 20, 30};
        int[] flatY = {100, 100, 100, 40};
        assertFalse("a flat run is not a transition",
                MonotoneSpline.isInteriorTransition(flat, flatY, 1));
        int[] spaced = {10, 20, 21, 30};
        int[] spacedY = {100, 100, 40, 40};
        assertFalse("an interval with width has nothing to widen",
                MonotoneSpline.isInteriorTransition(spaced, spacedY, 1));
    }

    /** Degenerate input: two vertices sharing an x must not produce NaN tangents. */
    @Test
    public void testDuplicateXDoesNotProduceNaN() {
        double[] m = MonotoneSpline.tangents(new double[] {0, 0, 1}, new double[] {5, 9, 4}, 0, 3);
        for (int i = 0; i < m.length; i++) {
            assertFalse("tangent " + i + " must be finite",
                    Double.isNaN(m[i]) || Double.isInfinite(m[i]));
        }
    }

    /** Duplicate x must still draw, as a straight hop rather than a curve. */
    @Test
    public void testDuplicateXDrawsStraightSegment() {
        int[] xv = {0, 0, 10};
        int[] yv = {0, 20, 20};
        double[] m = MonotoneSpline.tangents(toDouble(xv), toDouble(yv), 0, 3);
        GeneralPath path = new GeneralPath();
        path.moveTo(xv[0], yv[0]);
        MonotoneSpline.appendForward(path, xv, yv, m);
        double[] pts = sample(path);
        assertEquals("last point must be reached", 10.0, pts[pts.length - 2], EPS);
        assertEquals("last value must be reached", 20.0, pts[pts.length - 1], EPS);
    }
}
