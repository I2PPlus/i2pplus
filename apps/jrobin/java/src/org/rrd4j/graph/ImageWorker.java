package org.rrd4j.graph;

import java.awt.*;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Abstract base class for image workers in RRD graphs. Provides drawing operations for creating
 * graph images with various output formats. Tracks last paint/stroke/font to avoid redundant
 * state changes that produce duplicate inline styles in SVG output.
 *
 * Default constructor.
 */
public abstract class ImageWorker {

    private static final String DUMMY_TEXT = "Dummy";
    //    private static final int IMG_BUFFER_CAPACITY = 10000; // bytes
    private static final int IMG_BUFFER_CAPACITY = 40 * 1024; // bytes

    /** Default pixels of width given to a transition narrower than one pixel. */
    static final int DEFAULT_TRANSITION_WIDTH = 3;

    /** Graphics context for drawing operations */
    private Graphics2D g2d;
    /** Last paint set on the graphics context (for deduplication) */
    private Paint lastPaint;
    /** Last stroke set on the graphics context (for deduplication) */
    private Stroke lastStroke;
    /** Last font set on the graphics context (for deduplication) */
    private Font lastFont;
    /** Whether plot lines and areas are drawn as bezier curves instead of steps */
    private boolean smoothing;
    /**
     * Most width, in pixels, given to a transition that would otherwise be a vertical line.
     * See {@link MonotoneSpline#widenTransitions}.
     */
    private int transitionWidth = DEFAULT_TRANSITION_WIDTH;

    /**
     * Sets the graphics context for drawing operations. Disposes of previous context if exists.
     *
     * @param g2d new graphics context
     */
    protected void setG2d(Graphics2D g2d) {
        if (g2d != null) {
            dispose();
        }
        this.g2d = g2d;
        resetState();
    }

    /**
     * Resets cached state tracking. Called when the graphics context changes or when an SVG
     * group boundary is opened/closed that may affect inherited state.
     */
    protected void resetState() {
        this.lastPaint = null;
        this.lastStroke = null;
        this.lastFont = null;
    }

    /**
     * Resize the image to the given dimensions.
     *
     * @param width the new width
     * @param height the new height
     */
    abstract void resize(int width, int height);

    /**
     * Set the clipping region for drawing operations.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param width the width
     * @param height the height
     */
    void clip(int x, int y, int width, int height) {
        g2d.setClip(x, y, width, height);
    }

    /**
     * Apply a translation and rotation transform.
     *
     * @param x the x translation
     * @param y the y translation
     * @param angle the rotation angle
     */
    void transform(int x, int y, double angle) {
        g2d.translate(x, y);
        g2d.rotate(angle);
    }

    /** Reset the graphics state. */
    void reset() {
        reset(g2d);
    }

    /**
     * Reset the graphics state using the given context.
     *
     * @param g2d the graphics context
     */
    protected abstract void reset(Graphics2D g2d);

    /**
     * Fill a rectangle with the given paint.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param width the width
     * @param height the height
     * @param paint the paint to fill with
     */
    void fillRect(int x, int y, int width, int height, Paint paint) {
        g2d.setPaint(paint);
        g2d.fillRect(x, y, width, height);
    }

    /**
     * Fill a polygon with a flat bottom.
     *
     * @param x the x coordinates
     * @param yBottom the bottom y coordinate
     * @param yTop the top y coordinates
     * @param paint the paint to fill with
     */
    void fillPolygon(double[] x, double yBottom, double[] yTop, Paint paint) {
        g2d.setPaint(paint);
        PathIterator path = new PathIterator(yTop);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1], n = end - start;
            int[] xDev = new int[n + 2], yDev = new int[n + 2];
            int c = areaOutlineFlat(x, yTop, start, end, yBottom, xDev, yDev);
            g2d.fillPolygon(xDev, yDev, c);
            g2d.drawPolygon(xDev, yDev, c); // duplicate
        }
    }

    /**
     * Builds the closed outline of an area whose lower boundary is the graph baseline.
     *
     * <p>Extracted from {@link #fillPolygon(double[], double, double[], Paint)} so the outline can
     * be checked without a graphics context.
     *
     * @param x the x coordinates
     * @param yTop the top y coordinates
     * @param start first sample index of the run, inclusive
     * @param end one past the last sample index, exclusive
     * @param yBottom the flat baseline
     * @param xDev destination x coordinates, at least {@code end - start + 2} long
     * @param yDev destination y coordinates, same length as {@code xDev}
     * @return the number of vertices in the closed outline
     * @since 0.9.71
     */
    static int areaOutlineFlat(double[] x, double[] yTop, int start, int end, double yBottom,
            int[] xDev, int[] yDev) {
        int c = reduceVertices(x, yTop, start, end, false, xDev, yDev, 0);
        // The baseline is a constant, so it contributes only the two closing corners.
        xDev[c] = xDev[c - 1];
        xDev[c + 1] = xDev[0];
        yDev[c] = yDev[c + 1] = (int) yBottom;
        return c + 2;
    }

    /**
     * Fill a polygon with variable bottom points.
     *
     * @param x the x coordinates
     * @param yBottom the bottom y coordinates
     * @param yTop the top y coordinates
     * @param paint the paint to fill with
     */
    void fillPolygon(double[] x, double[] yBottom, double[] yTop, Paint paint) {
        g2d.setPaint(paint);
        PathIterator path = new PathIterator(yTop);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1], n = end - start;
            // Both boundaries are walked into one buffer, so it must hold two full vertex lists
            // before any collapsing. Sizing it at n would overflow on data that never collapses.
            int[] xDev = new int[n * 2 + 2], yDev = new int[n * 2 + 2];
            int c = areaOutlineVariable(x, yBottom, yTop, start, end, xDev, yDev);
            g2d.fillPolygon(xDev, yDev, c);
        }
    }

    /**
     * Builds the closed outline of an area whose lower boundary is itself a plotted series, as
     * used by stacked plots.
     *
     * <p>Extracted from {@link #fillPolygon(double[], double[], double[], Paint)} so the outline
     * can be checked without a graphics context.
     *
     * @param x the x coordinates
     * @param yBottom the lower boundary y coordinates
     * @param yTop the top y coordinates
     * @param start first sample index of the run, inclusive
     * @param end one past the last sample index, exclusive
     * @param xDev destination x coordinates, at least {@code 2 * (end - start)} long
     * @param yDev destination y coordinates, same length as {@code xDev}
     * @return the number of vertices in the closed outline
     * @since 0.9.71
     */
    static int areaOutlineVariable(double[] x, double[] yBottom, double[] yTop, int start, int end,
            int[] xDev, int[] yDev) {
        int c = reduceVertices(x, yTop, start, end, false, xDev, yDev, 0);
        // The lower boundary is walked backwards to close the outline. It shares the dedup state
        // with the upper one so the vertical side edges collapse instead of adding vertices.
        return reduceVertices(x, yBottom, start, end, true, xDev, yDev, c);
    }

    /**
     * Append the step-collapsed vertices of {@code y} over {@code [start, end)} into
     * {@code xDev}/{@code yDev}, continuing from index {@code c}.
     *
     * <p>A run of samples sharing a value is emitted as a single segment rather than as one
     * vertex per sample, which is what gives the plots their staircase shape: a flat stretch
     * becomes one horizontal line and each value change becomes one near-vertical line. Both
     * the step and the smoothed renderer reduce the same way, so they always describe the same
     * data.
     *
     * <p>The caller owns the buffers. Continuing from an existing {@code c} lets the two
     * boundaries of a filled area share one dedup pass, so a vertical side edge collapses into
     * the adjacent vertex instead of contributing a redundant one.
     *
     * @param x the x coordinates
     * @param y the y coordinates to reduce
     * @param from first sample index of the range, inclusive
     * @param to one past the last sample index of the range, exclusive; the same exclusive bound
     *          in both directions
     * @param reverse true to walk from {@code to - 1} down to {@code from} instead of upwards
     * @param xDev destination x coordinates
     * @param yDev destination y coordinates
     * @param c index to start writing at
     * @return the new write index
     * @since 0.9.71
     */
    static int reduceVertices(double[] x, double[] y, int from, int to, boolean reverse,
            int[] xDev, int[] yDev, int c) {
        for (int n = reverse ? to - 1 : from; reverse ? n >= from : n < to;
                n += reverse ? -1 : 1) {
            int cx = (int) x[n];
            int cy = (int) y[n];
            if (c == 0 || cx != xDev[c - 1] || cy != yDev[c - 1]) {
                if (c >= 2 && cy == yDev[c - 1] && cy == yDev[c - 2]) {
                    // collapse horizontal lines
                    xDev[c - 1] = cx;
                } else if (c >= 2 && cx == xDev[c - 1] && cx == xDev[c - 2]) {
                    // collapse vertical lines
                    yDev[c - 1] = cy;
                } else {
                    xDev[c] = cx;
                    yDev[c++] = cy;
                }
            }
        }
        return c;
    }

    /**
     * Leftmost column a run of samples may be drawn at, matching where the step renderer anchors
     * the same run.
     *
     * <p>The step renderer feeds {@link #reduceVertices} a doubled x array, in which every sample's
     * x is written twice, and a y array whose trailing NaN is overwritten by the value that
     * follows it. A run that therefore begins just after a NaN sample starts one index early, on
     * the NaN sample's own x, so the step path's first point is drawn at that earlier column. The
     * smoothed renderer uses one x per sample and keeps its NaNs, so without this correction its
     * run would begin at the first valid sample instead - exactly one sample period to the right,
     * which is visible as a gap at the start of the trace and grows with the plot's sample
     * spacing.
     *
     * <p>Anchoring on {@code x[start - 1]} is the same rule for every run, whether the preceding
     * sample is the leading edge of the window or the far side of a gap, because the doubled x
     * array maps both to the same index. When the run starts at the first sample there is no
     * preceding sample and the run simply starts at its own column, which is at or just left of the
     * plot area's clipped left edge, so the trace covers the whole width.
     *
     * @param x the sample x coordinates, one per sample
     * @param start first sample index of the run, inclusive, as returned by
     *        {@link PathIterator#getNextPath()}
     * @return the column the run's first vertex must be drawn at
     * @since 0.9.71
     */
    static int runAnchorX(double[] x, int start) {
        return (int) x[start > 0 ? start - 1 : 0];
    }

    /**
     * Draw a line with the given paint and stroke.
     *
     * @param x1 the starting x coordinate
     * @param y1 the starting y coordinate
     * @param x2 the ending x coordinate
     * @param y2 the ending y coordinate
     * @param paint the paint
     * @param stroke the stroke
     */
    void drawLine(int x1, int y1, int x2, int y2, Paint paint, Stroke stroke) {
        if (stroke != lastStroke) {
            g2d.setStroke(stroke);
            lastStroke = stroke;
        }
        if (!paint.equals(lastPaint)) {
            g2d.setPaint(paint);
            lastPaint = paint;
        }
        g2d.drawLine(x1, y1, x2, y2);
    }

    /**
     * Draw a polyline with the given paint and stroke.
     *
     * @param x the x coordinates
     * @param y the y coordinates
     * @param paint the paint
     * @param stroke the stroke
     */
    void drawPolyline(double[] x, double[] y, Paint paint, Stroke stroke) {
        g2d.setPaint(paint);
        g2d.setStroke(stroke);
        PathIterator path = new PathIterator(y);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1];
            int[] xDev = new int[end - start], yDev = new int[end - start];
            int c = reduceVertices(x, y, start, end, false, xDev, yDev, 0);
            g2d.drawPolyline(xDev, yDev, c);
        }
    }

    /**
     * Selects whether plots are drawn as bezier curves or as steps.
     *
     * <p>Off by default, which keeps the staircase rendering. When on, line plots and areas with
     * a flat lower boundary are drawn through {@link MonotoneSpline}, which smooths only the
     * transitions and leaves genuinely flat stretches as straight lines.
     *
     * @param smoothing true to draw curves instead of steps
     * @since 0.9.71
     */
    void setSmoothing(boolean smoothing) {
        this.smoothing = smoothing;
    }

    /**
     * @return true if plots are drawn as bezier curves
     * @since 0.9.71
     */
    boolean isSmoothing() {
        return smoothing;
    }

    /**
     * Sets how much width a sub-pixel transition may borrow from the flat runs beside it.
     *
     * @param pixels total width for one transition, at least 2
     * @since 0.9.71
     */
    void setTransitionWidth(int pixels) {
        this.transitionWidth = Math.max(2, pixels);
    }

    /**
     * Draw a polyline as a smoothed curve.
     *
     * <p>Each gap-free run of samples becomes one path; a run of NaN samples is a gap in the data
     * and is never bridged.
     *
     * @param x the x coordinates
     * @param y the y coordinates
     * @param paint the paint
     * @param stroke the stroke
     * @since 0.9.71
     */
    void drawPolylineSmooth(double[] x, double[] y, Paint paint, Stroke stroke) {
        g2d.setPaint(paint);
        g2d.setStroke(stroke);
        PathIterator path = new PathIterator(y);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1];
            int[] xDev = new int[end - start], yDev = new int[end - start];
            int c = reduceVertices(x, y, start, end, false, xDev, yDev, 0);
            if (c < 2) {
                continue;
            }
            int[] vx = java.util.Arrays.copyOf(xDev, c);
            int[] vy = java.util.Arrays.copyOf(yDev, c);
            // Anchor the run where the step renderer anchors it, so switching smoothing on does
            // not shift the start of the trace to the right by a sample period.
            vx[0] = runAnchorX(x, start);
            MonotoneSpline.widenTransitions(vx, vy, transitionWidth);
            GeneralPath curve = new GeneralPath();
            curve.moveTo(vx[0], vy[0]);
            MonotoneSpline.appendForward(curve, vx, vy,
                    MonotoneSpline.tangents(toDouble(vx), toDouble(vy), 0, c));
            g2d.draw(curve);
        }
    }

    /**
     * Fill an area with a flat lower boundary, drawn as a smoothed curve.
     *
     * <p>The outline is emitted as a single closed subpath: the smoothed upper boundary forward,
     * then down to the baseline and back along it. One subpath is required rather than two open
     * ones, because SVG closes each subpath implicitly and two overlapping windings under the
     * default nonzero rule would cancel and leave a hole.
     *
     * <p>The smoothed curve cannot rise above the baseline. It is confined to the vertical band
     * between the two samples it joins, and every sample is at or above the baseline the graph
     * is drawn from, so the outline cannot fold back on itself.
     *
     * @param x the x coordinates
     * @param yBottom the flat lower boundary
     * @param yTop the top y coordinates
     * @param paint the paint to fill with
     * @since 0.9.71
     */
    void fillPolygonSmooth(double[] x, double yBottom, double[] yTop, Paint paint) {
        g2d.setPaint(paint);
        PathIterator path = new PathIterator(yTop);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1];
            int[] xDev = new int[end - start], yDev = new int[end - start];
            int c = reduceVertices(x, yTop, start, end, false, xDev, yDev, 0);
            if (c < 2) {
                continue;
            }
            int[] vx = java.util.Arrays.copyOf(xDev, c);
            int[] vy = java.util.Arrays.copyOf(yDev, c);
            // Anchor the run where the step renderer anchors it: the closing corner of the
            // outline uses the same column, so a smoothed area starts where its stepped twin does.
            vx[0] = runAnchorX(x, start);
            MonotoneSpline.widenTransitions(vx, vy, transitionWidth);
            GeneralPath area = new GeneralPath();
            area.moveTo(vx[0], vy[0]);
            MonotoneSpline.appendForward(area, vx, vy,
                    MonotoneSpline.tangents(toDouble(vx), toDouble(vy), 0, c));
            area.lineTo(vx[c - 1], (int) yBottom);
            area.lineTo(vx[0], (int) yBottom);
            area.closePath();
            g2d.fill(area);
            // The step renderer outlines the filled area by drawing the same polygon again, and
            // the console themes rely on that edge to read the plot. Stroke the same outline here
            // or a smoothed graph loses its border and looks nothing like the stepped one.
            g2d.draw(area);
        }
    }

    /**
     * Widens integral coordinates for the spline, which works in double precision.
     *
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

    /**
     * Draw a string at the given position with the specified font and paint.
     *
     * @param text the text to draw
     * @param x the x coordinate
     * @param y the y coordinate
     * @param font the font
     * @param paint the paint
     */
    void drawString(String text, int x, int y, Font font, Paint paint) {
        if (font != lastFont) {
            g2d.setFont(font);
            lastFont = font;
        }
        if (!paint.equals(lastPaint)) {
            g2d.setPaint(paint);
            lastPaint = paint;
        }
        g2d.drawString(text, x, y);
    }

    /**
     * Get the ascent of the given font.
     *
     * @param font the font
     * @return the font ascent
     */
    double getFontAscent(Font font) {
        LineMetrics lm = font.getLineMetrics(DUMMY_TEXT, g2d.getFontRenderContext());
        return lm.getAscent();
    }

    /**
     * Get the total height of the given font.
     *
     * @param font the font
     * @return the font height (ascent + descent)
     */
    double getFontHeight(Font font) {
        LineMetrics lm = font.getLineMetrics(DUMMY_TEXT, g2d.getFontRenderContext());
        return lm.getAscent() + lm.getDescent();
    }

    /**
     * Get the width of the given text in the specified font.
     *
     * @param text the text
     * @param font the font
     * @return the string width
     */
    double getStringWidth(String text, Font font) {
        return font.getStringBounds(text, 0, text.length(), g2d.getFontRenderContext())
                .getBounds()
                .getWidth();
    }

    /**
     * Enable or disable anti-aliasing.
     *
     * @param enable true to enable, false to disable
     */
    void setAntiAliasing(boolean enable) {
        g2d.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                enable ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
    }

    /**
     * Enable or disable text anti-aliasing.
     *
     * @param enable true to enable, false to disable
     */
    void setTextAntiAliasing(boolean enable) {
        g2d.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                enable
                        ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                        : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    }

    /**
     * Load and draw an image from the given source.
     *
     * @param imageSource the image source
     * @param x the x coordinate
     * @param y the y coordinate
     * @param w the width
     * @param h the height
     * @throws IOException if an I/O error occurs
     */
    void loadImage(RrdGraphDef.ImageSource imageSource, int x, int y, int w, int h)
            throws IOException {
        BufferedImage wpImage = imageSource.apply(w, h).getSubimage(0, 0, w, h);
        g2d.drawImage(wpImage, new AffineTransform(1f, 0f, 0f, 1f, x, y), null);
    }

    /** Dispose of the graphics context. */
    void dispose() {
        if (g2d != null) {
            g2d.dispose();
        }
    }

    /**
     * Write the image to the given path.
     *
     * @param path the output path
     * @throws IOException if an I/O error occurs
     */
    void makeImage(Path path) throws IOException {
        try (OutputStream os = Files.newOutputStream(path)) {
            makeImage(os);
        }
    }

    /**
     * Write the image to the given output stream.
     *
     * @param os the output stream
     * @throws IOException if an I/O error occurs
     */
    abstract void makeImage(OutputStream os) throws IOException;

    /**
     * Save the image to the given file path.
     *
     * @param path the file path
     * @throws IOException if an I/O error occurs
     */
    void saveImage(String path) throws IOException {
        makeImage(Paths.get(path));
    }

    /**
     * Get the image as a byte array.
     *
     * @return the image bytes
     * @throws IOException if an I/O error occurs
     */
    byte[] getImageBytes() throws IOException {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream(IMG_BUFFER_CAPACITY)) {
            makeImage(stream);
            return stream.toByteArray();
        }
    }
}
