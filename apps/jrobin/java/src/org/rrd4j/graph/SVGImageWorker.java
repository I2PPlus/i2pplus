package org.rrd4j.graph;

import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import org.jfree.svg.SVGGraphics2D;

/**
 * I2P adapter for jfreesvg. State deduplication in {@link ImageWorker} handles inline style
 * reduction; SVG element grouping is handled by postProcessSvg consolidation in SVGGraphics2D.
 *
 * @author zzz
 * @since 2024-05-04
 */
public class SVGImageWorker extends ImageWorker {
    private SVGGraphics2D g2d;
    /** Console theme, reapplied on every resize since that rebuilds {@link #g2d}. */
    private String themeName;
    /** Img width */
    private int imgWidth;
    /** Img height */
    private int imgHeight;
    private boolean glow;
    private boolean smoothing;

    /*** Image width in pixels.
  @param width image width in pixels
     *  @param height image height in pixels */
    public SVGImageWorker(int width, int height) {
        this(width, height, false, false, null);
    }

    /*** Image width in pixels.
  @param width image width in pixels
     *  @param height image height in pixels
     *  @param glow whether to enable glow effect */
    public SVGImageWorker(int width, int height, boolean glow) {
        this(width, height, glow, false, null);
    }

    /**
     * Image width in pixels.
     *
     * @param width image width in pixels
     * @param height image height in pixels
     * @param glow whether to enable glow effect
     * @param smoothing whether plot paths hold curves, which are antialiased rather than crisp
     * @since 0.9.71
     */
    public SVGImageWorker(int width, int height, boolean glow, boolean smoothing) {
        this(width, height, glow, smoothing, null);
    }

    /**
     * Image width in pixels.
     *
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param glow whether to enable glow effect
     * @param smoothing whether plot paths hold curves, which are antialiased rather than crisp
     * @param theme console theme name, or null to infer it from the plot
     * @since 0.9.71
     */
    public SVGImageWorker(int width, int height, boolean glow, boolean smoothing, String theme) {
        this.glow = glow;
        this.smoothing = smoothing;
        this.themeName = theme;
        initGraphics(width, height);
    }
    /**
     * Init graphics
     */

    private void initGraphics(int width, int height) {
        imgWidth = width;
        imgHeight = height;
        g2d = new SVGGraphics2D(imgWidth, imgHeight);
        g2d.setGlowEnabled(glow);
        g2d.setSmoothingEnabled(smoothing);
        // Applied here rather than once in the constructor because resize() rebuilds g2d.
        // The renderer builds the worker at 0x0 and resizes to the real dimensions before
        // drawing, so a theme set only in the constructor was thrown away with the first
        // resize: every graph then fell back to inferring the theme from the drawing, which
        // only works when there is a filled area to read. A line-only graph had nothing to
        // read and came out in the classic colours.
        if (themeName != null) {
            g2d.setThemeName(themeName);
        }
        setG2d(g2d);
    }
    /**
     * Resize
     */

    void resize(int width, int height) {
        if (width != imgWidth || height != imgHeight) {
            initGraphics(width, height);
        }
    }
    /**
     * Reset the allocator state
     */

    protected void reset(Graphics2D g2d) {
        g2d.setClip(0, 0, imgWidth, imgHeight);
    }
    /**
     * Make image
     */

    void makeImage(OutputStream os) throws IOException {
        byte[] svgBytes = g2d.getSVGElement().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        os.write(svgBytes);
    }
    /**
     * Draw string
     */

    @Override
    void drawString(String text, int x, int y, Font font, Paint paint) {
        super.drawString(text.trim(), x, y, font, paint);
    }

    @Override
    double getStringWidth(String text, Font font) {
        return super.getStringWidth(text.trim(), font);
    }
}
