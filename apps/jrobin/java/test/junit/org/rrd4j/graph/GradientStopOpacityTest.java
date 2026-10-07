package org.rrd4j.graph;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.LinearGradientPaint;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jfree.svg.SVGGraphics2D;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that a gradient stop's alpha reaches the SVG as a usable number.
 *
 * <p>The stop opacity was written through {@code transformDP}, which is a coordinate setting
 * and rounds to whole numbers by default. Every alpha a theme actually uses is below one -
 * the plot fills run from #08 to #c0, that is 0.03 to 0.75 - so they all rounded to
 * {@code "0."}. That is not a valid number, and a renderer that cannot parse it treats the stop
 * as fully transparent, so a translucent gradient did not draw at all.
 *
 * <p>Nothing caught it because no test looked at the attribute, and a flat fill of the same
 * colour was fine: that goes through a separate opacity formatter and was already correct. The
 * two paths disagreeing is what made this hard to see.
 *
 * @since 0.9.71+
 */
public class GradientStopOpacityTest {

    /** Matches one stop-opacity value so its parseability can be checked directly. */
    private static final Pattern STOP_OPACITY =
        Pattern.compile("stop-opacity=\"([^\"]*)\"");

    /**
     * A two-stop gradient must keep both alphas.
     *
     * <p>Checked by parsing each value rather than by matching text, because the failure was a
     * malformed number: "0." looks plausible in a diff and is not a number at all.
     */
    @Test
    public void aTwoStopGradientKeepsBothAlphas() throws Exception {
        String gradient = gradientDef(new GradientPaint(
                new Point2D.Double(0, 0), new Color(0x2e, 0xc2, 0x3e, 0x40),
                new Point2D.Double(0, 200), new Color(0xf0, 0x00, 0x00, 0x08)));
        assertEquals("a stop lost its opacity: " + gradient, 2, countStops(gradient));
        assertAlphasParseable(gradient, 0.251d, 0.031d);
    }

    /** Three stops is the case that only became reachable with multi-stop gradients. */
    @Test
    public void aThreeStopGradientKeepsEveryAlpha() throws Exception {
        String gradient = gradientDef(new LinearGradientPaint(
                new Point2D.Double(0, 0), new Point2D.Double(0, 200),
                new float[] { 0f, 0.5f, 1f },
                new Color[] { new Color(0x2e, 0xc2, 0x3e, 0x40), new Color(0xf0, 0, 0, 0x08),
                              new Color(0xd0, 0, 0, 0x08) }));
        assertEquals("a stop lost its opacity: " + gradient, 3, countStops(gradient));
        assertAlphasParseable(gradient, 0.251d, 0.031d, 0.031d);
    }

    /** An opaque stop needs no attribute at all, which is how the writer has always done it. */
    @Test
    public void anOpaqueStopCarriesNoOpacityAttribute() throws Exception {
        String gradient = gradientDef(new GradientPaint(
                new Point2D.Double(0, 0), new Color(0x2e, 0xc2, 0x3e, 0xff),
                new Point2D.Double(0, 200), Color.RED));
        assertFalse("an opaque stop wrote an opacity: " + gradient,
                    STOP_OPACITY.matcher(gradient).find());
    }

    /**
     * The thinnest alpha a theme can write must still be a usable number.
     *
     * <p>#08 is the floor in every shipped fill, and it is the value that rounded away. A
     * regression to whole-number rounding would fail here rather than at the first translucent
     * gradient somebody themes.
     */
    @Test
    public void theThinnestThemeAlphaIsNotRoundedAway() throws Exception {
        String gradient = gradientDef(new GradientPaint(
                new Point2D.Double(0, 0), Color.RED,
                new Point2D.Double(0, 200), new Color(0, 0, 0, 0x08)));
        java.util.List<String> written = new java.util.ArrayList<>();
        Matcher m = STOP_OPACITY.matcher(gradient);
        while (m.find()) {
            written.add(m.group(1));
        }
        assertEquals("no stop opacity was written: " + gradient, 1, written.size());
        double opacity = Double.parseDouble(written.get(0));
        assertTrue("alpha 8/255 was written as " + opacity + ", which draws nothing",
                   opacity > 0d && opacity < 0.05d);
    }

    /** A flat fill of the same colour is unaffected, which is the asymmetry that hid this. */
    @Test
    public void aFlatFillStillWritesItsOwnAlpha() throws Exception {
        StringBuilder sb = new StringBuilder();
        SVGGraphics2D g = new SVGGraphics2D(400, 200, sb);
        g.setPaint(new Color(0xf0, 0, 0, 0x08));
        g.fill(new Rectangle2D.Double(0, 0, 400, 200));
        g.dispose();
        String svg = g.getSVGElement();
        assertTrue("a flat fill lost its alpha: " + svg, svg.contains("fill-opacity:.03"));
    }

    /** Assert each written opacity parses and matches the expected fraction, in order. */
    private static void assertAlphasParseable(String gradient, double... expected) {
        Matcher m = STOP_OPACITY.matcher(gradient);
        int i = 0;
        while (m.find()) {
            assertTrue("stop " + i + " wrote '" + m.group(1) + "', which is not a number: "
                       + gradient, isNumber(m.group(1)));
            assertEquals("stop " + i + " has the wrong opacity",
                         expected[i], Double.parseDouble(m.group(1)), 0.002d);
            i++;
        }
        assertEquals("fewer stop opacities were written than expected: " + gradient,
                     expected.length, i);
    }

    /** Whether the text is a number a renderer will accept. */
    private static boolean isNumber(String text) {
        try {
            double d = Double.parseDouble(text);
            return d >= 0d && d <= 1d;
        } catch (NumberFormatException nfe) {
            return false;
        }
    }

    private static int countStops(String gradient) {
        return gradient.split("<stop ").length - 1;
    }

    /** Render one filled shape and return just its gradient definition. */
    private static String gradientDef(java.awt.Paint paint) throws Exception {
        StringBuilder sb = new StringBuilder();
        SVGGraphics2D g = new SVGGraphics2D(400, 200, sb);
        g.setPaint(paint);
        g.fill(new Rectangle2D.Double(0, 0, 400, 200));
        g.dispose();
        String svg = g.getSVGElement();
        int from = svg.indexOf("<linearGradient");
        assertTrue("no gradient definition was emitted:\n" + svg, from >= 0);
        return svg.substring(from, svg.indexOf("</linearGradient>", from));
    }
}
