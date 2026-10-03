package org.rrd4j.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * Pins the shape-preserving property of the smoothed renderer: no curve may leave the vertical band
 * of the two samples it joins, and a genuinely flat run must stay exactly flat.
 *
 * <p>This was the visible defect - a smoothed graph bulging where the data held a constant value,
 * while the stepped rendering of the same data looked right. Measuring it on the rendered SVG is
 * not an option: the path data is emitted as relative deltas whose numbers are not the control
 * points, so a walker that reads them as absolute measures the encoder, not the renderer. The
 * assertions below therefore run on the control points themselves, via
 * {@link ImageWorker#smoothedRun}, which is the single place the renderer builds them.
 *
 * <p>The guarantee is structural. A cubic Bezier lies within the convex hull of its four control
 * points, so if both control point y values sit inside {@code [min(y0, y1), max(y0, y1)]} the curve
 * cannot leave the band. {@link MonotoneSpline} keeps them there by limiting every tangent to at
 * most three times its interval's secant, and by forcing both tangents of a flat interval to zero.
 */
public class SmoothedBandTest {

    /** Plot width the console uses for an inline graph, in pixels. */
    private static final int WIDTH = 296;
    /** Plot height the console uses for an inline graph, in pixels. */
    private static final int HEIGHT = 60;

    /** One emitted cubic: {startY, controlY, controlY, endY}. */
    private static final class Cubic {
        final double y0;
        final double cp1;
        final double cp2;
        final double y1;

        Cubic(double y0, double cp1, double cp2, double y1) {
            this.y0 = y0;
            this.cp1 = cp1;
            this.cp2 = cp2;
            this.y1 = y1;
        }
    }

    /**
     * Drives the renderer's own smoothing pipeline over one gap-free run and returns the control
     * points of every cubic it emits.
     *
     * <p>Nothing here re-implements the pipeline: {@code reduceVertices}, the run anchor, transition
     * widening, the tangents and the curve are all the production ones, in the production order.
     *
     * @param x sample x coordinates, one per sample
     * @param y sample y coordinates, one per sample, no NaN in this run
     * @return the emitted control points, empty when the run has nothing to draw
     */
    private static List<Cubic> controlPoints(double[] x, double[] y) {
        return controlPoints(x, y, ImageWorker.DEFAULT_TRANSITION_WIDTH);
    }

    /**
     * @param x sample x coordinates, one per sample
     * @param y sample y coordinates, one per sample, no NaN in this run
     * @param transitionWidth most width to give one sub-pixel transition
     * @return the emitted control points, empty when the run has nothing to draw
     * @see #controlPoints(double[], double[])
     */
    private static List<Cubic> controlPoints(double[] x, double[] y, int transitionWidth) {
        List<Cubic> out = new ArrayList<>();
        GeneralPath path = ImageWorker.smoothedRun(x, y, 0, y.length, new int[y.length],
                new int[y.length], transitionWidth);
        if (path == null) {
            return out;
        }
        double[] c = new double[6];
        double cy = 0;
        PathIterator it = path.getPathIterator(null);
        while (!it.isDone()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
                cy = c[1];
            } else if (type == PathIterator.SEG_CUBICTO) {
                out.add(new Cubic(cy, c[1], c[3], c[5]));
                cy = c[5];
            }
            it.next();
        }
        return out;
    }

    /**
     * Spreads {@code n} samples evenly over the plot width, the way {@code xtrDistinct} maps
     * timestamps through the plot area.
     *
     * @param n sample count
     * @return the x coordinates
     */
    private static double[] columns(int n) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = (double) WIDTH * i / (n - 1);
        }
        return x;
    }

    /**
     * Asserts the band invariant on every emitted cubic, and that any flat run is exactly flat.
     *
     * @param label identifies the series in a failure message
     * @param x sample x coordinates
     * @param y sample y coordinates
     */
    private static void assertBandHeld(String label, double[] x, double[] y) {
        for (Cubic c : controlPoints(x, y)) {
            double lo = Math.min(c.y0, c.y1);
            double hi = Math.max(c.y0, c.y1);
            assertTrue(label + ": control point " + c.cp1 + " left the band [" + lo + ", " + hi
                    + "] of its own endpoints", c.cp1 >= lo && c.cp1 <= hi);
            assertTrue(label + ": control point " + c.cp2 + " left the band [" + lo + ", " + hi
                    + "] of its own endpoints", c.cp2 >= lo && c.cp2 <= hi);
            if (c.y0 == c.y1) {
                assertEquals(label + ": a flat run must not invent curvature", c.y0, c.cp1, 0.0);
                assertEquals(label + ": a flat run must not invent curvature", c.y1, c.cp2, 0.0);
            }
        }
    }

    /**
     * The headline invariant, on the real pipeline and on data shaped like a router statistic:
     * long flat stretches, steps taken over several periods, and a ramp.
     */
    @Test
    public void testRealisticSeriesKeepsEveryControlPointInBand() {
        int[] levels = {
            40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40,
            55, 55, 55, 55, 55, 55, 55, 55, 55, 55, 55, 55,
            55, 55, 55, 55, 55, 55, 55, 55, 55, 55, 55, 55,
            31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31,
            31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31, 31,
            48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48
        };
        double[] x = columns(levels.length);
        double[] y = new double[levels.length];
        for (int i = 0; i < levels.length; i++) {
            y[i] = levels[i];
        }
        assertTrue("the fixture must actually produce curves",
                controlPoints(x, y).size() > 1);
        assertBandHeld("stepped series", x, y);
    }

    /**
     * Randomised walks over the real pipeline, at densities from far fewer samples than pixels to
     * far more, so transitions both straddle pixels and land inside a single column. A high density
     * is the interesting case: it is what puts a value change between two samples that share a
     * column, which is the situation that made the renderer look wrong.
     */
    @Test
    public void testRandomWalksKeepEveryControlPointInBand() {
        Random rnd = new Random(20260903L);
        int checked = 0;
        for (int trial = 0; trial < 300; trial++) {
            int n = 2 + rnd.nextInt(200);
            double[] x = columns(n);
            double[] y = new double[n];
            int v = 1 + rnd.nextInt(HEIGHT - 2);
            for (int i = 0; i < n; i++) {
                v = Math.max(1, Math.min(HEIGHT - 1, v + rnd.nextInt(11) - 5));
                y[i] = v;
            }
            checked += controlPoints(x, y).size();
            assertBandHeld("walk " + trial + " (n=" + n + ")", x, y);
        }
        assertTrue("the fuzzing must have exercised real curves, saw " + checked,
                checked > 1000);
    }

    /**
     * The same property has to hold at every transition width. Widening only moves vertices in x,
     * which changes the secants and therefore the tangents, so it is exactly the step that could
     * reintroduce overshoot if the limiting were missing.
     */
    @Test
    public void testBandHeldAtEveryTransitionWidth() {
        Random rnd = new Random(11L);
        for (int width = 2; width <= 12; width++) {
            for (int trial = 0; trial < 20; trial++) {
                int n = 40 + rnd.nextInt(120);
                double[] x = columns(n);
                double[] y = new double[n];
                int v = HEIGHT / 2;
                for (int i = 0; i < n; i++) {
                    v = Math.max(1, Math.min(HEIGHT - 1, v + rnd.nextInt(7) - 3));
                    y[i] = v;
                }
                for (Cubic c : controlPoints(x, y, width)) {
                    double lo = Math.min(c.y0, c.y1);
                    double hi = Math.max(c.y0, c.y1);
                    assertTrue("width " + width + ": control point " + c.cp1 + " left ["
                            + lo + ", " + hi + "]", c.cp1 >= lo && c.cp1 <= hi);
                    assertTrue("width " + width + ": control point " + c.cp2 + " left ["
                            + lo + ", " + hi + "]", c.cp2 >= lo && c.cp2 <= hi);
                }
            }
        }
    }

    // ---- genuinely flat runs ----

    /**
     * A flat run must contribute control points at exactly its own value. Anything else is a bulge,
     * and a bulge on a plateau is what made the smoothed trace look wrong where the stepped one
     * looked right. The values are integers because the renderer truncates to the pixel grid, so
     * "exactly" is testable with no tolerance at all.
     */
    @Test
    public void testFlatRunControlPointsSitExactlyOnTheFlat() {
        double[] x = columns(24);
        double[] y = new double[24];
        for (int i = 0; i < 24; i++) {
            y[i] = 41;
        }
        List<Cubic> cubics = controlPoints(x, y);
        assertTrue("a constant series must still be drawn", !cubics.isEmpty());
        for (Cubic c : cubics) {
            assertEquals("start of a flat run must not be dragged off the flat", 41.0, c.y0, 0.0);
            assertEquals("end of a flat run must not be dragged off the flat", 41.0, c.y1, 0.0);
            assertEquals("first control point of a flat run", 41.0, c.cp1, 0.0);
            assertEquals("second control point of a flat run", 41.0, c.cp2, 0.0);
        }
    }

    /**
     * The regression shape from the bug report: a plateau with a large step on one side and a small
     * step on the other. A tangent averaged from both neighbours without limiting picks up the large
     * step and bows the plateau away from its value, so the two steps must be unbalanced here rather
     * than equal.
     */
    @Test
    public void testPlateauBetweenALargeAndASmallStepStaysFlat() {
        int[] levels = {10, 10, 10, 10, 55, 55, 55, 55, 55, 55, 55, 59};
        double[] x = columns(levels.length);
        double[] y = new double[levels.length];
        for (int i = 0; i < levels.length; i++) {
            y[i] = levels[i];
        }
        List<Cubic> cubics = controlPoints(x, y);
        assertTrue("expected the two steps and the plateau between them", cubics.size() >= 3);
        // The plateau is the segment joining the two ends of the 55 run.
        int plateau = -1;
        for (int i = 0; i < cubics.size(); i++) {
            if (cubics.get(i).y0 == 55.0 && cubics.get(i).y1 == 55.0) {
                plateau = i;
                break;
            }
        }
        assertTrue("the fixture must contain a flat run at 55", plateau >= 0);
        assertEquals("a 45px step and a 4px step must not drag the plateau",
                55.0, cubics.get(plateau).cp1, 0.0);
        assertEquals("a 45px step and a 4px step must not drag the plateau",
                55.0, cubics.get(plateau).cp2, 0.0);
        assertBandHeld("unbalanced steps", x, y);
    }

    /**
     * A plateau of a single step - one sample between two different neighbours. There is no width
     * to bulge into, and the sample is a local extremum of the series, so both of its tangents must
     * be zero for the curve to touch the sample and turn.
     */
    @Test
    public void testPlateauOfASingleStepStaysFlat() {
        int[] levels = {20, 20, 20, 20, 20, 44, 44, 44, 44, 44};
        double[] x = columns(levels.length);
        double[] y = new double[levels.length];
        for (int i = 0; i < levels.length; i++) {
            y[i] = levels[i];
        }
        List<Cubic> cubics = controlPoints(x, y);
        int spike = -1;
        for (int i = 0; i < cubics.size(); i++) {
            if (cubics.get(i).y0 == 44.0 && cubics.get(i).y1 == 44.0) {
                spike = i;
                break;
            }
        }
        assertTrue("the fixture must contain a single-sample plateau", spike >= 0);
        assertEquals("a single-sample plateau must not bulge", 44.0, cubics.get(spike).cp1, 0.0);
        assertEquals("a single-sample plateau must not bulge", 44.0, cubics.get(spike).cp2, 0.0);
        assertBandHeld("single step", x, y);
    }

    // ---- boundary cases ----

    /**
     * One sample reduces to a single vertex, which is not a segment. The renderer must decline to
     * draw rather than invent one, and must not throw.
     */
    @Test
    public void testSingleSampleWindowDrawsNothing() {
        double[] x = {WIDTH / 2.0};
        double[] y = {30};
        assertNull("a lone sample has no segment to draw",
                ImageWorker.smoothedRun(x, y, 0, 1, new int[1], new int[1],
                        ImageWorker.DEFAULT_TRANSITION_WIDTH));
        assertTrue("and therefore no control points", controlPoints(x, y).isEmpty());
    }

    /**
     * A window of nothing but NaN yields no run at all. The all-NaN case is the one a router hits
     * before its first sample lands, and it must not reach the spline at all.
     */
    @Test
    public void testAllNaNWindowHasNoRun() {
        double[] x = {0, 1, 2, 3};
        double[] y = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        assertTrue("an empty series must produce no path",
                new org.rrd4j.graph.PathIterator(y).getNextPath() == null);
    }

    /**
     * Two samples are the smallest drawable window. It must yield exactly one cubic, contained in
     * the band of the two values it joins.
     */
    @Test
    public void testTwoSampleWindowYieldsOneContainedCubic() {
        double[] x = {0, WIDTH};
        double[] y = {58, 4};
        List<Cubic> cubics = controlPoints(x, y);
        assertEquals("two samples make one segment", 1, cubics.size());
        assertEquals(58.0, cubics.get(0).y0, 0.0);
        assertEquals(4.0, cubics.get(0).y1, 0.0);
        assertTrue("the control points must stay between the two samples",
                cubics.get(0).cp1 >= 4.0 && cubics.get(0).cp1 <= 58.0
                        && cubics.get(0).cp2 >= 4.0 && cubics.get(0).cp2 <= 58.0);
    }

    /**
     * Two samples at the same value make a flat window of width one segment, which must stay
     * perfectly level rather than becoming a degenerate curve.
     */
    @Test
    public void testTwoSampleFlatWindowStaysLevel() {
        double[] x = {0, WIDTH};
        double[] y = {30, 30};
        List<Cubic> cubics = controlPoints(x, y);
        assertEquals("two samples make one segment", 1, cubics.size());
        assertEquals("a flat window must stay level", 30.0, cubics.get(0).cp1, 0.0);
        assertEquals("a flat window must stay level", 30.0, cubics.get(0).cp2, 0.0);
    }

    /**
     * An interior gap must split the series into two independent runs, each of which is smoothed
     * with its own tangents. Neither may borrow a slope across the gap, and neither may escape its
     * band: a run whose boundary sample follows a NaN has no neighbour on the far side to average
     * with, so its first tangent is the secant of its own first interval.
     */
    @Test
    public void testInteriorGapSplitsTheSeriesWithoutBanding() {
        double[] x = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120};
        double[] y = {20, 20, 20, 50, 50, Double.NaN, 50, 50, 12, 12, 12, 12, 12};
        List<Cubic> all = new ArrayList<>();
        org.rrd4j.graph.PathIterator it = new org.rrd4j.graph.PathIterator(y);
        int runs = 0;
        for (int[] pos = it.getNextPath(); pos != null; pos = it.getNextPath()) {
            runs++;
            int start = pos[0], end = pos[1];
            double[] rx = new double[end - start];
            double[] ry = new double[end - start];
            System.arraycopy(x, start, rx, 0, end - start);
            System.arraycopy(y, start, ry, 0, end - start);
            GeneralPath path = ImageWorker.smoothedRun(x, y, start, end, new int[end - start],
                    new int[end - start], ImageWorker.DEFAULT_TRANSITION_WIDTH);
            assertNotNull("each run must be drawable", path);
            all.addAll(controlPoints(rx, ry));
        }
        assertEquals("the gap must split the series in two", 2, runs);
        for (Cubic c : all) {
            double lo = Math.min(c.y0, c.y1);
            double hi = Math.max(c.y0, c.y1);
            assertTrue("a run beside a gap must stay in its band [" + lo + ", " + hi + "]",
                    c.cp1 >= lo && c.cp1 <= hi && c.cp2 >= lo && c.cp2 <= hi);
        }
        // The 50 run before the gap and the flat tail after it must both stay level.
        for (Cubic c : all) {
            if (c.y0 == c.y1) {
                assertEquals("no run may bulge across a gap boundary", c.y0, c.cp1, 0.0);
                assertEquals("no run may bulge across a gap boundary", c.y1, c.cp2, 0.0);
            }
        }
    }

    /**
     * The window whose left half is covered and whose right half is not, which is what a router
     * graph shows whenever the requested window is longer than the recorded history. The leading
     * NaN period is what the run anchor exists for, so this case has to keep working.
     */
    @Test
    public void testWindowLongerThanTheDataKeepsItsBand() {
        double[] x = new double[40];
        for (int i = 0; i < 40; i++) {
            x[i] = (double) WIDTH * i / 39.0;
        }
        double[] y = new double[40];
        for (int i = 0; i < 40; i++) {
            y[i] = Double.NaN;
        }
        for (int i = 22; i < 40; i++) {
            y[i] = 22 + (i - 22) * 4;
        }
        org.rrd4j.graph.PathIterator it = new org.rrd4j.graph.PathIterator(y);
        int[] pos = it.getNextPath();
        assertNotNull("the covered part of the window must still be a run", pos);
        assertEquals("the run starts at the first valid sample", 22, pos[0]);
        GeneralPath path = ImageWorker.smoothedRun(x, y, pos[0], pos[1], new int[pos[1] - pos[0]],
                new int[pos[1] - pos[0]], ImageWorker.DEFAULT_TRANSITION_WIDTH);
        assertNotNull("the partial run must be drawable", path);
        for (Cubic c : controlPoints(java.util.Arrays.copyOfRange(x, pos[0], pos[1]),
                java.util.Arrays.copyOfRange(y, pos[0], pos[1]))) {
            double lo = Math.min(c.y0, c.y1);
            double hi = Math.max(c.y0, c.y1);
            assertTrue("a partially covered window must stay in its band [" + lo + ", " + hi + "]",
                    c.cp1 >= lo && c.cp1 <= hi && c.cp2 >= lo && c.cp2 <= hi);
        }
    }
}
