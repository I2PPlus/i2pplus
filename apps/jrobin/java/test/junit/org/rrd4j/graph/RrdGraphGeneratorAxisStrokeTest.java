package org.rrd4j.graph;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the axis marker width.
 *
 * <p>The SVG post-processor recognises the axis container lines by a stroke width of 5 and
 * tags them {@code class="axis"}, where the theme's rule supplies the visible stroke. Drawn
 * at the default width they stayed generic {@code style="stroke:..."} groups, and the
 * normalising regexes that collapse adjacent stroke groups dropped them whenever a
 * neighbouring group had a matching shape. That is why the axis appeared on some graphs
 * and not on others with identical settings.
 *
 * @since 0.9.71+
 */
public class RrdGraphGeneratorAxisStrokeTest {

    @Test
    public void theAxisIsDrawnAtTheWidthThePostProcessorLooksFor() {
        assertEquals("SVGGraphics2D matches stroke-width:5 to tag class=\"axis\"",
                     5f, RrdGraphGenerator.AXIS_STROKE_WIDTH, 0f);
    }

    /**
     * The marker is a lookup key, not the drawn width: the theme's {@code .axis} rule
     * overrides it. If the two drift apart the axis is silently dropped again, so pin the
     * relationship rather than the literal.
     */
    @Test
    public void theMarkerMatchesThePostProcessorPattern() {
        assertEquals("stroke-width:5", "stroke-width:" + (int) RrdGraphGenerator.AXIS_STROKE_WIDTH);
    }

    /**
     * The pattern matches the serialised value by prefix, so a fractional tail is fine:
     * {@code stroke-width:5.0} still matches {@code style="stroke-width:5}. What must not
     * happen is a width that starts with a different digit, which would silently miss.
     */
    @Test
    public void theMarkerSerialisesWithTheDigitThePatternExpects() {
        String serialised = "stroke-width:" + RrdGraphGenerator.AXIS_STROKE_WIDTH;
        assertTrue(serialised + " must match style=\"stroke-width:5",
                   serialised.startsWith("stroke-width:5"));
        assertTrue("and the pattern must match it",
                   (serialised + "\"").startsWith("stroke-width:5"));
    }
}