package org.jfree.svg;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the rule that retags the graph's axis container lines.
 *
 * <p>The axis arrives as a bare {@code <line>} carrying a deliberate marker stroke width,
 * and gets retagged so the theme's {@code .axis} rule supplies the real stroke. Three
 * separate defects lived in this one rule, each found only by looking at rendered output:
 * it was written for a {@code <g>}; it assumed the width led the style attribute; and its
 * terminator consumed the closing quote when the width came last. The last one made the
 * axis disappear whenever the surrounding style happened to serialise that way, which is
 * why it tracked graph size rather than anything about the axis itself.
 *
 * @since 0.9.71+
 */
public class AxisTaggingTest {

    private static final String LINE_OPEN = "<line x1=\"0\" y1=\"0\" x2=\"9\" y2=\"9\" ";

    private static String line(String style) {
        return LINE_OPEN + "style=\"" + style + "\"/>";
    }

    /**
     * The marker width may sit anywhere in the style attribute, first, middle or last, with
     * or without a fractional tail.
     */
    @Test
    public void theMarkerIsFoundAtAnyPositionInTheStyle() {
        String[] styles = {
            "stroke-width:5.0;stroke:rgb(244,244,190);stroke-opacity:.78",
            "stroke:rgb(244,244,190);stroke-width:5.0;stroke-opacity:.78",
            "stroke:rgb(244,244,190);stroke-opacity:.78;stroke-width:5.0",
            "stroke-linecap:round;stroke:rgb(244,244,190);stroke-width:5",
            "stroke-width:5;stroke:rgb(244,244,190)",
        };
        for (String style : styles) {
            String out = SVGGraphics2D.tagAxis("<svg>" + line(style) + "</svg>");
            assertTrue("not tagged for style: " + style + "\n" + out,
                       out.contains("class=\"axis\""));
            assertFalse("the marker width survived: " + style, out.contains("stroke-width:5"));
        }
    }

    /** A width that merely begins with 5 is a different width. */
    @Test
    public void aSimilarWidthIsNotTheMarker() {
        String out = SVGGraphics2D.tagAxis(
                "<svg>" + line("stroke-width:50;stroke:rgb(0,0,0)") + "</svg>");
        assertFalse("50 must not match", out.contains("class=\"axis\""));

        String out2 = SVGGraphics2D.tagAxis(
                "<svg>" + line("stroke:rgb(0,0,0);stroke-width:5.5") + "</svg>");
        assertFalse("5.5 must not match", out2.contains("class=\"axis\""));
    }

    /** Ordinary grid lines and rules must be left completely alone. */
    @Test
    public void anOrdinaryLineIsUntouched() {
        String in = "<svg>" + line("stroke-width:1;stroke:rgb(244,244,190)") + "</svg>";
        assertEquals(in, SVGGraphics2D.tagAxis(in));
    }

    /** Retagging twice must not compound. */
    @Test
    public void taggingIsIdempotent() {
        String once = SVGGraphics2D.tagAxis(
                "<svg>" + line("stroke-width:5.0;stroke:rgb(0,0,0)") + "</svg>");
        assertEquals(once, SVGGraphics2D.tagAxis(once));
    }
}
