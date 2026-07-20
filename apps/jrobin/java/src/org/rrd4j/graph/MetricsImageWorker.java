package org.rrd4j.graph;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Image worker that performs the full graph layout and font-metric computation but produces no
 * rasterized output. Used to obtain exact image dimensions ({@code RrdGraphInfo.getWidth()} /
 * {@code getHeight()}) without paying the cost of encoding a PNG, so a subsequent pass can render
 * directly to another format (e.g. SVG) at the correct size.
 *
 * <p>Font metrics are derived from a real {@link Graphics2D} context (backed by a throwaway {@link
 * BufferedImage}), which makes the computed dimensions byte-identical to a PNG render.
 */
public class MetricsImageWorker extends ImageWorker {

    private int imgWidth;
    private int imgHeight;
    private final BufferedImage img;

    /**
     * Creates a worker that measures graph dimensions without producing rasterized output. A real
     * {@link Graphics2D} context is set up so font metrics match a PNG render exactly.
     *
     * @param width initial canvas width in pixels (clamped to at least 1)
     * @param height initial canvas height in pixels (clamped to at least 1)
     */
    public MetricsImageWorker(int width, int height) {
        this.imgWidth = Math.max(1, width);
        this.imgHeight = Math.max(1, height);
        this.img = new BufferedImage(this.imgWidth, this.imgHeight, BufferedImage.TYPE_INT_ARGB);
        resize(this.imgWidth, this.imgHeight);
    }

    /**
     * Resizes the backing canvas and re-creates the {@link Graphics2D} context used for font-metric
     * measurement.
     *
     * @param width new canvas width in pixels
     * @param height new canvas height in pixels
     */
    @Override
    void resize(int width, int height) {
        imgWidth = Math.max(1, width);
        imgHeight = Math.max(1, height);
        Graphics2D g2d = img.createGraphics();
        setG2d(g2d);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    /**
     * Restores the default clip for the supplied graphics context.
     *
     * @param g2d the graphics context to reset
     */
    @Override
    protected void reset(Graphics2D g2d) {
        g2d.setClip(0, 0, imgWidth, imgHeight);
    }

    /**
     * Produces no output. This worker exists only to drive layout and dimension computation, so the
     * image-encoding step is intentionally skipped.
     *
     * @param stream the output stream (ignored)
     * @throws java.io.IOException never thrown
     */
    @Override
    void makeImage(OutputStream stream) throws IOException {
        // Intentionally a no-op: this worker computes layout/dimensions only.
    }
}
