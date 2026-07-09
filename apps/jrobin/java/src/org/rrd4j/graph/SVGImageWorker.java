package org.rrd4j.graph;

import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import org.jfree.svg.SVGGraphics2D;

/**
 * I2P adapter for jfreesvg. State deduplication in {@link ImageWorker} handles inline style
 * reduction; SVG element grouping is handled by postProcessSvg consolidation in SVGGraphics2D.
 *
 * @since 2024-05-04
 * @author zzz
 */
public class SVGImageWorker extends ImageWorker {
    private SVGGraphics2D g2d;
    private int imgWidth;
    private int imgHeight;
    private boolean glow;

    public SVGImageWorker(int width, int height) {
        this.glow = false;
        initGraphics(width, height);
    }

    public SVGImageWorker(int width, int height, boolean glow) {
        this.glow = glow;
        initGraphics(width, height);
    }

    private void initGraphics(int width, int height) {
        imgWidth = width;
        imgHeight = height;
        g2d = new SVGGraphics2D(imgWidth, imgHeight);
        g2d.setGlowEnabled(glow);
        setG2d(g2d);
    }

    void resize(int width, int height) {
        if (width != imgWidth || height != imgHeight) {
            initGraphics(width, height);
        }
    }

    protected void reset(Graphics2D g2d) {
        g2d.setClip(0, 0, imgWidth, imgHeight);
    }

    void makeImage(OutputStream os) throws IOException {
        byte[] svgBytes = g2d.getSVGElement().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        os.write(svgBytes);
    }

    @Override
    void drawString(String text, int x, int y, Font font, Paint paint) {
        super.drawString(text.trim(), x, y, font, paint);
    }

    @Override
    double getStringWidth(String text, Font font) {
        return super.getStringWidth(text.trim(), font);
    }
}
