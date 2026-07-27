package org.rrd4j.graph;

import java.awt.*;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
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

    /** Graphics context for drawing operations */
    private Graphics2D g2d;
    /** Last paint set on the graphics context (for deduplication) */
    private Paint lastPaint;
    /** Last stroke set on the graphics context (for deduplication) */
    private Stroke lastStroke;
    /** Last font set on the graphics context (for deduplication) */
    private Font lastFont;

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
            int c = 0;
            for (int i = start; i < end; i++) {
                int cx = (int) x[i];
                int cy = (int) yTop[i];
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
            xDev[c] = xDev[c - 1];
            xDev[c + 1] = xDev[0];
            yDev[c] = yDev[c + 1] = (int) yBottom;
            g2d.fillPolygon(xDev, yDev, c + 2);
            g2d.drawPolygon(xDev, yDev, c + 2); // duplicate
        }
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
            int[] xDev = new int[n * 2], yDev = new int[n * 2];
            int c = 0;
            for (int i = start; i < end; i++) {
                int cx = (int) x[i];
                int cy = (int) yTop[i];
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
            for (int i = end - 1; i >= start; i--) {
                int cx = (int) x[i];
                int cy = (int) yBottom[i];
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
            g2d.fillPolygon(xDev, yDev, c);
        }
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
            int c = 0;
            for (int i = start; i < end; i++) {
                int cx = (int) x[i];
                int cy = (int) y[i];
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
            g2d.drawPolyline(xDev, yDev, c);
        }
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
