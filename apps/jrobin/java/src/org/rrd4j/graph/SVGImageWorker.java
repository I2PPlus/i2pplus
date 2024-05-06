package org.rrd4j.graph;

import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;

import org.jfree.svg.SVGGraphics2D;

/**
 *  I2P adapter for jfreesvg
 *
 *  Requires: https://github.com/jfree/jfreesvg
 *  Requires: rrd4j custom_Graphics2D branch patch https://github.com/rrd4j/rrd4j/commit/225c06b245377e2995ea39c885548e8ef0514630
 *  Ref: https://github.com/rrd4j/rrd4j/issues/165
 *
 *  @since 2024-05-04
 *  @author zzz
 */
public class SVGImageWorker extends ImageWorker {
    private SVGGraphics2D g2d;
    private int imgWidth;
    private int imgHeight;

    public SVGImageWorker(int width, int height) {
        imgWidth = width;
        imgHeight = height;
        g2d = new SVGGraphics2D(imgWidth, imgHeight);
        setG2d(g2d);
    }

    void resize(int width, int height) {
        imgWidth = width;
        imgHeight = height;
        setG2d(g2d);
        //setAntiAliasing(false);
        //setTextAntiAliasing(true);
        //g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    protected void reset(Graphics2D g2d) {
        g2d.setClip(0, 0, imgWidth, imgHeight);
    }

    void makeImage(OutputStream os) throws IOException {
        os.write(g2d.getSVGElement().getBytes("UTF-8"));
    }
}