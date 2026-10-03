package org.jfree.svg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.geom.GeneralPath;

import org.junit.Test;

/**
 * Pins the relative coordinate encoding of emitted SVG path data.
 *
 * <p>SVG 1.1 section 8.3.1: for the relative form of a command, <em>every</em>
 * coordinate value is relative to the current point at the start of that command.
 * A cubic's three coordinate pairs are therefore all relative to the same point,
 * not chained off one another.
 *
 * <p>This was not so. Each pair was differenced against the previous pair, which
 * placed the control points elsewhere on the canvas and desynchronised the running
 * endpoint the writer uses for the next command, so the error accumulated along the
 * whole path. Curves came out short and never reached the far edge of a graph plot;
 * lines and moves were unaffected because their relative forms carry a single pair.
 *
 * <p>The assertions are on the emitted {@code d} attribute for paths whose absolute
 * coordinates are chosen by hand, so nothing here depends on parsing SVG back out.
 */
public class SVGPathDataRelativeTest {

    /**
     * Emit the path data for one shape and return the {@code d="..."} attribute.
     *
     * @param path the shape to serialise
     * @return the path data attribute value, without the surrounding quotes
     */
    private static String pathData(GeneralPath path) {
        SVGGraphics2D g2d = new SVGGraphics2D(200, 100);
        g2d.draw(path);
        String svg = g2d.getSVGDocument();
        int at = svg.indexOf("d=\"");
        assertTrue("no path data emitted: " + svg, at >= 0);
        int end = svg.indexOf('"', at + 3);
        return svg.substring(at + 3, end);
    }

    /**
     * The first command is absolute, so a cubic after an initial move must encode
     * each of its three pairs relative to that move's point - not relative to the
     * control point that precedes it.
     */
    @Test
    public void testCubicAfterMoveIsRelativeToTheCurrentPoint() {
        GeneralPath p = new GeneralPath();
        p.moveTo(100d, 50d);
        // control1 (110,40) control2 (120,60) end (130,50)
        p.curveTo(110d, 40d, 120d, 60d, 130d, 50d);
        assertEquals("M100 50c10 -10 20 10 30 0", pathData(p));
    }

    /**
     * A second cubic in the same path is relative to the endpoint the writer
     * actually emitted. Deriving the pairs from the previous pair instead makes
     * the running point drift, so this is the case that compounds down a trace.
     */
    @Test
    public void testConsecutiveCubicsShareOneRunningPoint() {
        GeneralPath p = new GeneralPath();
        p.moveTo(100d, 50d);
        p.curveTo(110d, 40d, 120d, 60d, 130d, 50d);
        p.curveTo(140d, 40d, 150d, 60d, 160d, 50d);
        // second cubic is relative to (130,50): +10 -10 +20 +10 +30 0
        assertEquals("M100 50c10 -10 20 10 30 0c10 -10 20 10 30 0", pathData(p));
    }

    /**
     * The encoding must stay correct when the deltas are negative, which is the
     * normal case for a curve descending across a graph.
     */
    @Test
    public void testNegativeDeltasAreRelativeToTheCurrentPoint() {
        GeneralPath p = new GeneralPath();
        p.moveTo(200d, 20d);
        p.curveTo(190d, 30d, 180d, 40d, 170d, 50d);
        assertEquals("M200 20c-10 10 -20 20 -30 30", pathData(p));
    }

    /**
     * A quad's control point carries the same rule as a cubic's.
     */
    @Test
    public void testQuadControlPointIsRelativeToTheCurrentPoint() {
        GeneralPath p = new GeneralPath();
        p.moveTo(100d, 50d);
        p.quadTo(120d, 30d, 140d, 50d);
        assertEquals("M100 50q20 -20 40 0", pathData(p));
    }

    /**
     * Line and move segments are single-pair relative commands and were never
     * affected; they are pinned here so the fix cannot regress them.
     */
    @Test
    public void testLineAndMoveEncodingIsUnchanged() {
        GeneralPath p = new GeneralPath();
        p.moveTo(10d, 10d);
        p.lineTo(20d, 30d);
        p.lineTo(40d, 50d);
        assertEquals("M10 10l10 20l20 20", pathData(p));
    }

    /**
     * A cubic following a line is relative to the line's endpoint.
     */
    @Test
    public void testCubicAfterLineIsRelativeToTheLineEndpoint() {
        GeneralPath p = new GeneralPath();
        p.moveTo(10d, 10d);
        p.lineTo(20d, 20d);
        p.curveTo(30d, 40d, 40d, 60d, 50d, 80d);
        assertEquals("M10 10l10 10c10 20 20 40 30 60", pathData(p));
    }
}