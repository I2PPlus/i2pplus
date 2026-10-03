package org.rrd4j.graph;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link ImageWorker#reduceVertices}, the run-collapsing step shared by the step and
 * smoothed plot renderers.
 *
 * <p>Both renderers must agree on which samples become vertices, so that turning smoothing on
 * changes only the shape of the joins and never which data points are plotted. The reverse
 * direction matters too: a filled area walks its lower boundary backwards, and getting the range
 * wrong silently drops samples from the outline.
 */
public class ReduceVerticesTest {

    /**
     * @param x source x coordinates
     * @param y source y coordinates
     * @return evenly spaced x coordinates, one per sample
     */
    private static double[] rampX(double... y) {
        double[] x = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            x[i] = i * 10;
        }
        return x;
    }

    /**
     * @param xv collected x coordinates, trimmed to count
     * @param yv collected y coordinates, trimmed to count
     * @param count number of vertices written
     */
    private static void assertVertices(int[] xv, int[] yv, int count, int[] expX, int[] expY) {
        assertEquals("vertex count", expX.length, count);
        int[] ax = new int[count], ay = new int[count];
        System.arraycopy(xv, 0, ax, 0, count);
        System.arraycopy(yv, 0, ay, 0, count);
        assertArrayEquals("x", expX, ax);
        assertArrayEquals("y", expY, ay);
    }

    /** A flat run collapses to its two endpoints. */
    @Test
    public void testFlatRunCollapsesToEndpoints() {
        double[] x = rampX(5, 5, 5, 5);
        double[] y = {100, 100, 100, 100};
        int[] xv = new int[4], yv = new int[4];
        int c = ImageWorker.reduceVertices(x, y, 0, 4, false, xv, yv, 0);
        assertVertices(xv, yv, c, new int[] {0, 30}, new int[] {100, 100});
    }

    /** A single value change keeps both sides of the step. */
    @Test
    public void testStepKeepsBothLevels() {
        double[] x = rampX(5, 5, 5, 5);
        double[] y = {100, 100, 40, 40};
        int[] xv = new int[4], yv = new int[4];
        int c = ImageWorker.reduceVertices(x, y, 0, 4, false, xv, yv, 0);
        assertVertices(xv, yv, c, new int[] {0, 10, 20, 30}, new int[] {100, 100, 40, 40});
    }

    /** Every distinct sample is kept when nothing can be collapsed. */
    @Test
    public void testNoCollapseWhenAllDistinct() {
        double[] x = rampX(1, 2, 3, 4);
        double[] y = {10, 20, 30, 40};
        int[] xv = new int[4], yv = new int[4];
        int c = ImageWorker.reduceVertices(x, y, 0, 4, false, xv, yv, 0);
        assertVertices(xv, yv, c, new int[] {0, 10, 20, 30}, new int[] {10, 20, 30, 40});
    }

    /**
     * Forward and reverse walks of the same range must produce the same vertex list, reversed.
     * They describe the same boundary, and a filled area uses one for each edge.
     */
    @Test
    public void testReverseIsForwardReversed() {
        double[] y = {100, 100, 100, 40, 40, 800, 900, 900};
        double[] x = rampX(y);
        int n = y.length;

        int[] fx = new int[n], fy = new int[n];
        int fc = ImageWorker.reduceVertices(x, y, 0, n, false, fx, fy, 0);

        int[] rx = new int[n], ry = new int[n];
        int rc = ImageWorker.reduceVertices(x, y, 0, n, true, rx, ry, 0);

        assertEquals("same number of vertices either way", fc, rc);
        for (int i = 0; i < fc; i++) {
            assertEquals("x at " + i, fx[i], rx[fc - 1 - i]);
            assertEquals("y at " + i, fy[i], ry[fc - 1 - i]);
        }
    }

    /**
     * The reverse walk must cover the whole range. An off-by-one here silently drops the
     * boundary's end sample and leaves a filled outline open on one side.
     */
    @Test
    public void testReverseCoversWholeRange() {
        double[] y = {5, 6, 7, 8};
        double[] x = rampX(y);
        int[] rx = new int[4], ry = new int[4];
        int rc = ImageWorker.reduceVertices(x, y, 0, 4, true, rx, ry, 0);
        assertVertices(rx, ry, rc, new int[] {30, 20, 10, 0}, new int[] {8, 7, 6, 5});
    }

    /** A sub-range must be reduced on its own terms, not bleed into its neighbours. */
    @Test
    public void testSubRangeIsIndependent() {
        double[] y = {100, 100, 100, 100};
        double[] x = rampX(y);
        int[] xv = new int[4], yv = new int[4];
        int c = ImageWorker.reduceVertices(x, y, 1, 3, false, xv, yv, 0);
        assertVertices(xv, yv, c, new int[] {10, 20}, new int[] {100, 100});
    }

    /**
     * Continuing from an existing count lets the two boundaries of a filled area share one dedup
     * pass. Walking the upper edge forward and the lower edge backward must trace a closed outline
     * that returns to the starting column.
     */
    @Test
    public void testContinuationTracesClosedOutline() {
        double[] top = {100, 40, 40};
        double[] x = rampX(top);
        double[] base = {200, 200, 200};
        int[] xv = new int[8], yv = new int[8];
        int c = ImageWorker.reduceVertices(x, top, 0, 3, false, xv, yv, 0);
        c = ImageWorker.reduceVertices(x, base, 0, 3, true, xv, yv, c);
        assertVertices(xv, yv, c,
                new int[] {0, 10, 20, 20, 0},
                new int[] {100, 40, 40, 200, 200});
        // the outline returns to the column it started in, so closing it adds no new edge
        assertEquals("outline must return to its starting column", xv[0], xv[c - 1]);
        // and it sits above the baseline throughout
        for (int i = 0; i < c; i++) {
            assertTrue("upper edge must stay above the baseline at " + i, yv[i] <= 200);
        }
    }

    /**
     * A flat-baseline area outline must walk the top edge once and close with the two baseline
     * corners. Reducing the top edge a second time backwards would retrace it and leave a
     * self-overlapping polygon with no baseline at all.
     */
    @Test
    public void testFlatAreaOutlineClosesOntoBaseline() {
        double[] top = {100, 100, 40, 40};
        double[] x = rampX(top);
        int[] xv = new int[top.length + 2], yv = new int[top.length + 2];
        int c = ImageWorker.areaOutlineFlat(x, top, 0, top.length, 200, xv, yv);
        assertVertices(xv, yv, c,
                new int[] {0, 10, 20, 30, 30, 0},
                new int[] {100, 100, 40, 40, 200, 200});
    }

    /**
     * The varying-baseline outline must walk the top edge forwards and the lower edge backwards,
     * covering both ranges in full. Dropping the last sample of the reverse walk would leave the
     * outline open at the starting column.
     */
    @Test
    public void testVariableAreaOutlineCoversBothEdges() {
        double[] top = {40, 40, 40, 40};
        double[] bottom = {100, 100, 100, 100};
        double[] x = rampX(top);
        int[] xv = new int[top.length * 2], yv = new int[top.length * 2];
        int c = ImageWorker.areaOutlineVariable(x, bottom, top, 0, top.length, xv, yv);
        assertVertices(xv, yv, c,
                new int[] {0, 30, 30, 0},
                new int[] {40, 40, 100, 100});
    }

    /**
     * A stacked band's outline must put the upper boundary above the lower one all the way round,
     * otherwise the band folds over itself.
     */
    @Test
    public void testVariableAreaOutlineStaysOrdered() {
        double[] top = {40, 20, 60, 20};
        double[] bottom = {100, 90, 80, 70};
        double[] x = rampX(top);
        int[] xv = new int[top.length * 2], yv = new int[top.length * 2];
        int c = ImageWorker.areaOutlineVariable(x, bottom, top, 0, top.length, xv, yv);
        int half = c / 2;
        for (int i = 0; i < half; i++) {
            assertTrue("upper edge must stay above the lower edge at " + i, yv[i] <= yv[c - 1 - i]);
        }
    }

    /**
     * Values are truncated toward zero when reduced, matching the coordinate type the renderers
     * use, so a curve can never be built around a coordinate the output cannot represent.
     */
    @Test
    public void testValuesAreTruncated() {
        double[] x = {0.9, 1.9};
        double[] y = {10.7, 20.2};
        int[] xv = new int[2], yv = new int[2];
        int c = ImageWorker.reduceVertices(x, y, 0, 2, false, xv, yv, 0);
        assertVertices(xv, yv, c, new int[] {0, 1}, new int[] {10, 20});
    }

    /**
     * Duplicate columns collapse into a vertical edge, which is what a step change inside a single
     * pixel column produces. Such an edge has no horizontal room for a curve.
     */
    @Test
    public void testDuplicateColumnsCollapseVertically() {
        double[] x = {0, 0, 0, 5};
        double[] y = {10, 20, 30, 40};
        int[] xv = new int[4], yv = new int[4];
        int c = ImageWorker.reduceVertices(x, y, 0, 4, false, xv, yv, 0);
        assertTrue("a vertical run must not emit a vertex per sample", c < 4);
    }

    // ---- where a smoothed run is allowed to start ----

    /** A run that begins at the first sample starts at that sample's own column. */
    @Test
    public void testRunAnchorAtFirstSample() {
        double[] x = {63.5, 70.2, 77.9};
        assertEquals(63, ImageWorker.runAnchorX(x, 0));
    }

    /**
     * A run that begins just after a NaN is anchored on the NaN sample's column, not on its own.
     * This is the rule that keeps a smoothed trace from starting a sample period to the right of
     * where the step renderer starts it.
     */
    @Test
    public void testRunAnchorAfterNaNPrefix() {
        double[] x = {40.7, 55.2, 69.9, 84.4};
        assertEquals("anchor on the sample before the run", 55, ImageWorker.runAnchorX(x, 2));
    }

    /**
     * The anchor must not depend on why the run starts late, because the doubled x array the step
     * renderer uses maps a leading NaN and the far side of a gap to the same place. A run after an
     * interior gap therefore gets the same treatment.
     */
    @Test
    public void testRunAnchorAfterInteriorGap() {
        double[] x = {10.1, 20.4, 30.8, 41.2, 51.6};
        assertEquals(30, ImageWorker.runAnchorX(x, 3));
    }

    /**
     * Pins the anchor against the renderer it has to agree with. The step renderer feeds
     * {@code reduceVertices} a doubled x array whose trailing NaN has been overwritten, so its run
     * starts one index early; the smoothed renderer feeds it one x per sample with its NaNs
     * intact. Unless the smoothed run is anchored back onto the NaN sample's column, turning
     * smoothing on shifts the whole trace right by one sample period.
     */
    @Test
    public void testSmoothedRunAnchorsWhereSteppedRunAnchors() {
        // A window that reaches back past the first stored sample: one NaN, then real data.
        double[] values = {Double.NaN, 100, 100, 40, 40, 90};
        int n = values.length;

        double[] stepX = new double[2 * n - 1];
        double[] stepY = new double[2 * n - 1];
        for (int i = 0, j = 0; i < n; i++, j += 2) {
            stepX[j] = i * 11.4;
            if (i < n - 1) {
                stepX[j + 1] = stepX[j];
            }
            stepY[j] = values[i];
            if (j > 0) {
                stepY[j - 1] = stepY[j];
            }
        }
        int[] stepRun = new PathIterator(stepY).getNextPath();
        int[] stepXv = new int[stepX.length], stepYv = new int[stepX.length];
        int stepCount = ImageWorker.reduceVertices(
                stepX, stepY, stepRun[0], stepRun[1], false, stepXv, stepYv, 0);

        double[] smoothX = new double[n], smoothY = new double[n];
        for (int i = 0; i < n; i++) {
            smoothX[i] = i * 11.4;
            smoothY[i] = values[i];
        }
        int[] smoothRun = new PathIterator(smoothY).getNextPath();
        int[] smoothXv = new int[n], smoothYv = new int[n];
        int smoothCount = ImageWorker.reduceVertices(
                smoothX, smoothY, smoothRun[0], smoothRun[1], false, smoothXv, smoothYv, 0);
        smoothXv[0] = ImageWorker.runAnchorX(smoothX, smoothRun[0]);

        assertTrue("both renderers must produce vertices", stepCount > 0 && smoothCount > 0);
        assertEquals("the smoothed trace must start on the same column as the stepped one",
                stepXv[0], smoothXv[0]);
    }

    /**
     * A window with no valid sample at all has no run to anchor: there is no leading edge and
     * nothing to widen, so the renderer must emit nothing rather than reaching for a vertex that
     * is not there. This is the case a window reaching back past the first stored sample runs into,
     * and it is {@link PathIterator} that guarantees it - {@link ImageWorker#reduceVertices} trusts
     * its caller to hand it a NaN-free range.
     */
    @Test
    public void testAllNaNWindowHasNoRunToAnchor() {
        double[] x = {0.0, 10.0, 20.0, 30.0};
        double[] y = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        assertNull("an all-NaN window yields no path to draw", new PathIterator(y).getNextPath());
        // Widening the empty vertex list a renderer would pass in must be a no-op, not a crash.
        assertEquals(0, MonotoneSpline.widenTransitions(new int[0], new int[0], 3));
        assertEquals("a lone vertex is a run of one, which cannot be widened",
                0, MonotoneSpline.widenTransitions(new int[] {7}, new int[] {9}, 3));
    }

    /**
     * A single valid sample is not a run either, so there is nothing to draw and no anchor to
     * compute.
     */
    @Test
    public void testSingleSampleWindowHasNoRunToAnchor() {
        double[] x = {0.0, 10.0};
        double[] y = {Double.NaN, 42.0};
        assertNull("one sample cannot describe a line", new PathIterator(y).getNextPath());
    }
}
