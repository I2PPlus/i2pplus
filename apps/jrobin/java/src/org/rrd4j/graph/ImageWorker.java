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

public abstract class ImageWorker {
    private static final String DUMMY_TEXT = "Dummy";
    private static final int IMG_BUFFER_CAPACITY = 10000; // bytes
    private Graphics2D g2d;

    protected void setG2d(Graphics2D g2d) {
        if (g2d != null) {
            dispose();
        }
        this.g2d = g2d;
    }

    abstract void resize(int width, int height);

    void clip(int x, int y, int width, int height) {
        g2d.setClip(x, y, width, height);
    }

    void transform(int x, int y, double angle) {
        g2d.translate(x, y);
        g2d.rotate(angle);
    }

    void reset() {
        reset(g2d);
    }

    protected abstract void reset(Graphics2D g2d);

    void fillRect(int x, int y, int width, int height, Paint paint) {
        g2d.setPaint(paint);
        g2d.fillRect(x, y, width, height);
    }

    void fillPolygon(double[] x, double yBottom, double[] yTop, Paint paint) {
        g2d.setPaint(paint);
        PathIterator path = new PathIterator(yTop);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1], n = end - start;
            int[] xDev = new int[n + 2], yDev = new int[n + 2];
            for (int i = start; i < end; i++) {
                xDev[i - start] = (int) x[i];
                yDev[i - start] = (int) yTop[i];
            }
            xDev[n] = xDev[n - 1];
            xDev[n + 1] = xDev[0];
            yDev[n] = yDev[n + 1] = (int) yBottom;
            g2d.fillPolygon(xDev, yDev, xDev.length);
            g2d.drawPolygon(xDev, yDev, xDev.length);
        }
    }

    void fillPolygon(double[] x, double[] yBottom, double[] yTop, Paint paint) {
        g2d.setPaint(paint);
        PathIterator path = new PathIterator(yTop);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1], n = end - start;
            int[] xDev = new int[n * 2], yDev = new int[n * 2];
            for (int i = start; i < end; i++) {
                int ix1 = i - start, ix2 = n * 2 - 1 - i + start;
                xDev[ix1] = xDev[ix2] = (int) x[i];
                yDev[ix1] = (int) yTop[i];
                yDev[ix2] = (int) yBottom[i];
            }
            g2d.fillPolygon(xDev, yDev, xDev.length);
        }
    }

    void drawLine(int x1, int y1, int x2, int y2, Paint paint, Stroke stroke) {
        g2d.setStroke(stroke);
        g2d.setPaint(paint);
        g2d.drawLine(x1, y1, x2, y2);
    }

    void drawPolyline(double[] x, double[] y, Paint paint, Stroke stroke) {
        g2d.setPaint(paint);
        g2d.setStroke(stroke);
        PathIterator path = new PathIterator(y);
        for (int[] pos = path.getNextPath(); pos != null; pos = path.getNextPath()) {
            int start = pos[0], end = pos[1];
            int[] xDev = new int[end - start], yDev = new int[end - start];
            for (int i = start; i < end; i++) {
                xDev[i - start] = (int) x[i];
                yDev[i - start] = (int) y[i];
            }
            g2d.drawPolyline(xDev, yDev, xDev.length);
        }
    }

    void drawString(String text, int x, int y, Font font, Paint paint) {
        g2d.setFont(font);
        g2d.setPaint(paint);
        g2d.drawString(text, x, y);
    }

    double getFontAscent(Font font) {
        LineMetrics lm = font.getLineMetrics(DUMMY_TEXT, g2d.getFontRenderContext());
        return lm.getAscent();
    }

    double getFontHeight(Font font) {
        LineMetrics lm = font.getLineMetrics(DUMMY_TEXT, g2d.getFontRenderContext());
        return lm.getAscent() + lm.getDescent();
    }

    double getStringWidth(String text, Font font) {
        return font.getStringBounds(text, 0, text.length(), g2d.getFontRenderContext()).getBounds().getWidth();
    }

    void setAntiAliasing(boolean enable) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                enable ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
    }

    void setTextAntiAliasing(boolean enable) {
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                enable ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    }

    void loadImage(RrdGraphDef.ImageSource imageSource, int x, int y, int w, int h) throws IOException {
        BufferedImage wpImage = imageSource.apply(w, h).getSubimage(0, 0, w, h);
        g2d.drawImage(wpImage, new AffineTransform(1f, 0f, 0f, 1f, x, y), null);
    }

    void dispose() {
        if (g2d != null) {
            g2d.dispose();
        }
    }

    void makeImage(Path path) throws IOException {
        try (OutputStream os = Files.newOutputStream(path)) {
            makeImage(os);
        }
    }

    abstract void makeImage(OutputStream os) throws IOException ;

    void saveImage(String path) throws IOException {
        makeImage(Paths.get(path));
    }

    byte[] getImageBytes() throws IOException {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream(IMG_BUFFER_CAPACITY)){
            makeImage(stream);
            return stream.toByteArray();
        }
    }

}
