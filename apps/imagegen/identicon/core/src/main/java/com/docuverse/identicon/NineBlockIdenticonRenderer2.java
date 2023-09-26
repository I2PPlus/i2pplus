package com.docuverse.identicon;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.math.BigInteger;

public class NineBlockIdenticonRenderer2 implements IdenticonRenderer {

    private static final int PATCH_GRIDS = 5;
    private static final float DEFAULT_PATCH_SIZE = 20.0f;
    private static final byte PATCH_SYMMETRIC = 1;
    private static final byte PATCH_INVERTED = 2;
    private static final int PATCH_MOVETO = -1;

    private static final int[][] PATCH_TYPES = {
            {0, 4, 24, 20},
            {0, 4, 20},
            {2, 24, 20},
            {0, 2, 20, 22},
            {2, 14, 22, 10},
            {0, 14, 24, 22},
            {2, 24, 22, 13, 11, 22, 20},
            {0, 14, 22},
            {6, 8, 18, 16},
            {4, 20, 10, 12, 2},
            {0, 2, 12, 10},
            {10, 14, 22},
            {20, 12, 24},
            {10, 2, 12},
            {0, 2, 10},
            {0, 4, 24, 20}
    };

    private static final byte[] PATCH_FLAGS = {
            PATCH_SYMMETRIC, 0, 0, 0,
            PATCH_SYMMETRIC, 0, 0, 0,
            PATCH_SYMMETRIC, 0, 0, 0,
            0, 0, 0, PATCH_SYMMETRIC + PATCH_INVERTED
    };

    private static final int[] CENTER_PATCH_TYPES = {0, 4, 8, 15};

    private float patchSize;
    private GeneralPath[] patchShapes;
    private float patchOffset;
    private Color backgroundColor = Color.WHITE;

    public NineBlockIdenticonRenderer2() {
        setPatchSize(DEFAULT_PATCH_SIZE);
    }

    public float getPatchSize() {
        return patchSize;
    }

    public void setPatchSize(float size) {
        this.patchSize = size;
        this.patchOffset = patchSize / 2.0f;
        float patchScale = patchSize / 4.0f;
        this.patchShapes = new GeneralPath[PATCH_TYPES.length];
        for (int i = 0; i < PATCH_TYPES.length; i++) {
            GeneralPath patch = new GeneralPath(GeneralPath.WIND_NON_ZERO);
            boolean moveTo = true;
            int[] patchVertices = PATCH_TYPES[i];
            for (int j = 0; j < patchVertices.length; j++) {
                int v = patchVertices[j];
                if (v == PATCH_MOVETO) {
                    moveTo = true;
                } else {
                    float vx = ((v % PATCH_GRIDS) * patchScale) - patchOffset;
                    float vy = ((v / PATCH_GRIDS) * patchScale) - patchOffset;
                    if (moveTo) {
                        patch.moveTo(vx, vy);
                        moveTo = false;
                    } else {
                        patch.lineTo(vx, vy);
                    }
                }
            }
            patch.closePath();
            this.patchShapes[i] = patch;
        }
    }

    public Color getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public BufferedImage render(BigInteger code, int size) {
        return renderQuilt((int)code.longValue(), size);
    }

    public BufferedImage render(int code, int size) {
        return renderQuilt(code, size);
    }

    private BufferedImage renderQuilt(int code, int size) {
        int middleType = CENTER_PATCH_TYPES[code & 0x3];
        boolean middleInvert = ((code >> 2) & 0x1) != 0;
        int cornerType = (code >> 3) & 0x0f;
        boolean cornerInvert = ((code >> 7) & 0x1) != 0;
        int cornerTurn = (code >> 8) & 0x3;
        int sideType = (code >> 10) & 0x0f;
        boolean sideInvert = ((code >> 14) & 0x1) != 0;
        int sideTurn = (code >> 15) & 0x3;
        int blue = (code >> 16) & 0x01f;
        int green = (code >> 21) & 0x01f;
        int red = (code >> 27) & 0x01f;

        Color fillColor = new Color(red << 3, green << 3, blue << 3);
        Color strokeColor = getColorDistance(fillColor, backgroundColor) < 32.0f ? getComplementaryColor(fillColor) : null;

        BufferedImage targetImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = targetImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setBackground(backgroundColor);
        g.clearRect(0, 0, size, size);

        float blockSize = size / 3.0f;
        float blockSize2 = blockSize * 2.0f;

        drawPatch(g, blockSize, blockSize, blockSize, middleType, 0, middleInvert, fillColor, strokeColor);
        drawPatch(g, blockSize, 0, blockSize, sideType, sideTurn++, sideInvert, fillColor, strokeColor);
        drawPatch(g, blockSize2, blockSize, blockSize, sideType, sideTurn++, sideInvert, fillColor, strokeColor);
        drawPatch(g, blockSize, blockSize2, blockSize, sideType, sideTurn++, sideInvert, fillColor, strokeColor);
        drawPatch(g, 0, blockSize, blockSize, sideType, sideTurn++, sideInvert, fillColor, strokeColor);
        drawPatch(g, 0, 0, blockSize, cornerType, cornerTurn++, cornerInvert, fillColor, strokeColor);
        drawPatch(g, blockSize2, 0, blockSize, cornerType, cornerTurn++, cornerInvert, fillColor, strokeColor);
        drawPatch(g, blockSize2, blockSize2, blockSize, cornerType, cornerTurn++, cornerInvert, fillColor, strokeColor);
        drawPatch(g, 0, blockSize2, blockSize, cornerType, cornerTurn++, cornerInvert, fillColor, strokeColor);

        g.dispose();
        return targetImage;
    }

    private void drawPatch(Graphics2D g, float x, float y, float size, int patch, int turn, boolean invert, Color fillColor, Color strokeColor) {
        assert(patch >= 0);
        assert(turn >= 0);
        patch %= PATCH_TYPES.length;
        turn %= 4;
        if ((PATCH_FLAGS[patch] & PATCH_INVERTED) != 0) {
            invert = !invert;
        }

        Shape shape = patchShapes[patch];
        double scale = size / patchSize;
        float offset = size / 2.0f;

        g.setColor(invert ? fillColor : backgroundColor);
        g.fill(new Rectangle2D.Float(x, y, size, size));

        AffineTransform saveTransform = g.getTransform();
        g.translate(x + offset, y + offset);
        g.scale(scale, scale);
        g.rotate(Math.toRadians(turn * 90));

        if (strokeColor != null) {
            g.setColor(strokeColor);
            g.draw(shape);
        }

        g.setColor(invert ? backgroundColor : fillColor);
        g.fill(shape);

        g.setTransform(saveTransform);
    }

    private float getColorDistance(Color c1, Color c2) {
        float dr = c1.getRed() - c2.getRed();
        float dg = c1.getGreen() - c2.getGreen();
        float db = c1.getBlue() - c2.getBlue();
        return dr * dr + dg * dg + db * db;
    }

    private Color getComplementaryColor(Color color) {
        return new Color(255 - color.getRed(), 255 - color.getGreen(), 255 - color.getBlue());
    }
}