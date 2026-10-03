package org.rrd4j.graph;

import java.awt.geom.GeneralPath;

/**
 * Monotone cubic Hermite interpolation (Fritsch-Carlson / Fritsch-Butland) used to smooth
 * graph plot lines and area boundaries.
 *
 * <p>The unsmoothed renderer emits a staircase: runs of identical samples are collapsed to a
 * single horizontal segment and each value change becomes a near-vertical segment. This class
 * replaces the straight segments with cubic Bezier segments whose tangents are chosen so that:
 *
 * <ul>
 *   <li><b>Nothing overshoots.</b> The limiting step bounds the normalized tangent magnitudes
 *       (alpha, beta) by 3, which is exactly the condition for the interpolant on an interval to
 *       stay within the two endpoint values. A curve therefore never leaves the vertical band of
 *       the data it passes through, so it cannot escape the plot area.</li>
 *   <li><b>Genuinely flat data stays flat.</b> Where the secants on both sides of a vertex are
 *       equal, have opposite signs, or one of them is zero, the tangent is forced to zero. A
 *       plateau is bracketed by two horizontal tangents and so is drawn as an exact straight
 *       horizontal line; only the transitions between plateaus are curved.</li>
 *   <li><b>Spacing is honored.</b> The interpolation is x-aware, so it stays correct when a
 *       {@link DownSampler} has produced a non-uniform sample spacing.</li>
 * </ul>
 *
 * <p>The interpolation is <b>not</b> linear in the y values, because zeroing the tangent at a
 * local extremum or plateau is a sign test rather than a weighted sum. Two series therefore do
 * not interpolate additively, which is why {@link ImageWorker} only smooths plots whose fill has a
 * flat lower boundary; see {@code fillPolygonSmooth} for the reasoning.
 *
 * <p>All methods are static and side-effect free so the interpolation can be exercised without a
 * graphics context or a router.
 *
 * @since 0.9.71
 */
final class MonotoneSpline {

    private MonotoneSpline() {}

    /**
     * Maximum allowed value of the normalized tangent {@code m[i] / d[i]}. Bounding it at 3 is
     * the Fritsch-Carlson monotonicity condition: with alpha, beta at most 3 the cubic Hermite
     * interpolant on the interval cannot overshoot either endpoint.
     */
    private static final double MAX_NORMALIZED_TANGENT = 3.0;

    /**
     * Computes the slope (dy/dx) at each of the vertices {@code x[start]} .. {@code x[end - 1]}.
     *
     * @param x vertex x coordinates, strictly increasing within the range
     * @param y vertex y coordinates
     * @param start first index of the range, inclusive
     * @param end last index of the range, exclusive
     * @return slopes in y-units per x-unit, one per vertex; never null, empty when
     *         {@code end - start < 1}
     * @since 0.9.71
     */
    static double[] tangents(double[] x, double[] y, int start, int end) {
        int n = end - start;
        if (n < 1) {
            return new double[0];
        }
        double[] m = new double[n];
        if (n == 1) {
            return m;
        }
        // Secant slope per interval, in y-units per x-unit.
        double[] secant = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            double h = x[start + i + 1] - x[start + i];
            // A non-positive span means two vertices share an x. The plot reduction never
            // produces that, but a downsampled or hand-built series might, and a zero span
            // would divide by zero and poison the tangents with NaN or infinity.
            secant[i] = h > 0 ? (y[start + i + 1] - y[start + i]) / h : 0.0;
        }

        // Initial tangents: secant at the ends, weighted harmonic mean in the interior.
        m[0] = secant[0];
        m[n - 1] = secant[n - 2];
        for (int i = 1; i < n - 1; i++) {
            double before = secant[i - 1];
            double after = secant[i];
            if (before * after <= 0) {
                // Local extremum or plateau: a zero tangent is what keeps the curve flat
                // through a plateau and stops it overshooting past a peak.
                m[i] = 0.0;
            } else {
                // Fritsch-Butland weighting for non-uniform spacing: intervals adjacent to a
                // short interval carry less influence than intervals adjacent to a long one.
                double hBefore = x[start + i] - x[start + i - 1];
                double hAfter = x[start + i + 1] - x[start + i];
                double w1 = 2 * hAfter + hBefore;
                double w2 = hAfter + 2 * hBefore;
                m[i] = (w1 + w2) / (w1 / before + w2 / after);
            }
        }

        // Fritsch-Carlson limiting.
        for (int i = 0; i < n - 1; i++) {
            double d = secant[i];
            if (d == 0) {
                // Flat interval: both ends of the interval must be horizontal, otherwise the
                // curve would bow away from the plateau it is supposed to follow.
                m[i] = 0.0;
                m[i + 1] = 0.0;
            } else {
                double alpha = m[i] / d;
                double beta = m[i + 1] / d;
                double magnitude = alpha * alpha + beta * beta;
                if (magnitude > MAX_NORMALIZED_TANGENT * MAX_NORMALIZED_TANGENT) {
                    double scale = MAX_NORMALIZED_TANGENT / Math.sqrt(magnitude);
                    m[i] = scale * alpha * d;
                    m[i + 1] = scale * beta * d;
                }
            }
        }
        return m;
    }

    /**
     * Appends the smoothed curve through the vertices, walking forward from {@code xv[0]} to
     * {@code xv[n - 1]}.
     *
     * <p>The caller is responsible for having positioned the path at {@code (xv[0], yv[0])}.
     * Vertices with a non-positive span between them are joined with a straight line, since no
     * curve is defined there.
     *
     * @param path path to append to
     * @param xv vertex x coordinates
     * @param yv vertex y coordinates
     * @param m slopes as returned by {@link #tangents(double[], double[], int, int)} for this
     *           vertex range
     * @since 0.9.71
     */
    static void appendForward(GeneralPath path, int[] xv, int[] yv, double[] m) {
        int n = xv.length;
        for (int i = 0; i < n - 1; i++) {
            double h = xv[i + 1] - xv[i];
            if (h <= 0) {
                path.lineTo(xv[i + 1], yv[i + 1]);
                continue;
            }
            // The tangent vector at a vertex is (h, m * h): x advances at a constant rate so
            // that x stays linear in the curve parameter, and only y follows the slope.
            // Placing the control point a third of the span along each axis puts it at the 1/3
            // and 2/3 parameter positions of the cubic. Deriving the x offset from the slope
            // instead would let the curve double back outside the interval, so the two axes are
            // advanced separately and deliberately.
            int cx = round(h / 3.0);
            int cy1 = round(m[i] * h / 3.0);
            int cy2 = round(m[i + 1] * h / 3.0);
            path.curveTo(xv[i] + cx, yv[i] + cy1, xv[i + 1] - cx, yv[i + 1] - cy2,
                    xv[i + 1], yv[i + 1]);
        }
    }

    /**
     * Appends the smoothed curve through the vertices, walking backward from {@code xv[n - 1]}
     * to {@code xv[0]}. Used for the lower boundary of a filled area, which is traversed in
     * reverse to close the outline.
     *
     * <p>The caller is responsible for having positioned the path at
     * {@code (xv[n - 1], yv[n - 1])}.
     *
     * @param path path to append to
     * @param xv vertex x coordinates
     * @param yv vertex y coordinates
     * @param m slopes as returned by {@link #tangents(double[], double[], int, int)} for this
     *           vertex range
     * @since 0.9.71
     */
    static void appendReverse(GeneralPath path, int[] xv, int[] yv, double[] m) {
        int n = xv.length;
        for (int i = n - 2; i >= 0; i--) {
            double h = xv[i + 1] - xv[i];
            if (h <= 0) {
                path.lineTo(xv[i], yv[i]);
                continue;
            }
            // Reversing an interval swaps that interval's two control points. The x offsets stay
            // at a third of the span either way, for the same reason as in appendForward.
            int cx = round(h / 3.0);
            int cy1 = round(m[i + 1] * h / 3.0);
            int cy2 = round(m[i] * h / 3.0);
            path.curveTo(xv[i + 1] - cx, yv[i + 1] - cy1, xv[i] + cx, yv[i] + cy2,
                    xv[i], yv[i]);
        }
    }

    /**
     * Widens zero-width transitions so they have room to be drawn as curves.
     *
     * <p>When a graph plots more periods than it has pixels, several samples share one pixel
     * column and a value change lands inside that column. There is then no horizontal distance
     * across which to draw a curve, so the transition stays a vertical line and the plot keeps its
     * staircase look however good the interpolation is. This borrows the missing width from the
     * flat runs on either side of the transition, spreading it into an S-shaped ramp.
     *
     * <p>Only the x position of the two vertices bounding a transition moves; their y values, and
     * every vertex of the surrounding plateaus, are left alone. A flat stretch therefore stays
     * exactly flat and at exactly the value the data holds, it is just a little shorter. The curve
     * still cannot overshoot, because it is bounded by the same two endpoint values as before.
     *
     * <p>Only <b>interior</b> transitions are widened. The first and last intervals of a run are
     * left at their own columns, because they are the run's anchors: the first is where the trace
     * starts and the last is where it ends, and both sit on the plot area's boundary when the
     * series fills the window. Widening either one moves the anchor, which would start the trace
     * inside the plot instead of at its edge or run the tail past the far edge.
     *
     * <p>Because the drawn curve is given width it does not literally pass through every sample
     * once they are closer together than a pixel. That is the intended trade: this renders the
     * series as a trend rather than reproducing sub-pixel steps exactly.
     *
     * @param xv vertex x coordinates, adjusted in place
     * @param vy vertex y coordinates, read only to tell a transition from a flat run
     * @param maxWidth the most total width, in pixels, to give one transition; must be at least 2
     * @return the number of transitions widened
     * @since 0.9.71
     */
    static int widenTransitions(int[] xv, int[] vy, int maxWidth) {
        if (maxWidth < 2) {
            return 0;
        }
        int widened = 0;
        for (int i = 0; i + 1 < xv.length; i++) {
            if (!isInteriorTransition(xv, vy, i)) {
                continue;
            }
            // Take at most half of each neighbouring span, so a plateau keeps a visible core and
            // widening one transition can never eat the room another one needs.
            int left = maxWidth / 2;
            int right = maxWidth - left;
            left = Math.min(left, (xv[i] - xv[i - 1]) / 2);
            right = Math.min(right, (xv[i + 2] - xv[i + 1]) / 2);
            if (left + right < 2) {
                continue;
            }
            xv[i] -= left;
            xv[i + 1] += right;
            widened++;
        }
        return widened;
    }

    /**
     * Tells whether the interval at {@code i} is a zero-width value change that may be widened.
     *
     * <p>Three conditions, all required. The x coordinates must be equal, or the transition
     * already spans pixels and there is nothing to widen. The y values must differ, or it is a
     * flat run and there is no transition to draw. And {@code i} must be neither 0 nor
     * {@code xv.length - 2}, because those intervals bound the run: moving either vertex would
     * move the start or the end of the trace rather than a join in the middle of it.
     *
     * @param xv vertex x coordinates
     * @param vy vertex y coordinates
     * @param i index of the interval, whose two ends are {@code i} and {@code i + 1}
     * @return true if the interval may be widened
     * @since 0.9.71
     */
    static boolean isInteriorTransition(int[] xv, int[] vy, int i) {
        if (i <= 0 || i + 2 >= xv.length) {
            // A run's first and last intervals are its anchors, not joins between samples.
            return false;
        }
        return xv[i + 1] == xv[i] && vy[i + 1] != vy[i];
    }

    /**
     * Rounds a Bezier control point coordinate to the nearest integer.
     *
     * <p>Control points are only shape hints, and the SVG writer emits relative deltas truncated
     * to integers. Emitting integral coordinates keeps every delta exact instead of accumulating
     * a sub-pixel error across a long series, at a cost of at most a quarter pixel of curve
     * deviation.
     *
     * @param value coordinate to round
     * @return the nearest integer coordinate
     */
    private static int round(double value) {
        return (int) Math.round(value);
    }
}
