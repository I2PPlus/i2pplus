package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Tests the glow filter emitted by {@link SVGImageWorker} when glow is enabled.
 *
 * <p>The glow has to be a halo of the line's own colour. The filter used to blur
 * {@code SourceAlpha}, which carries the alpha channel alone, so the colour was thrown
 * away and the halo rendered black - a drop shadow on a light background, and a muddy
 * smudge even on the dark ones.
 */
public class GlowFilterTest {

    /**
     * Renders a stroked path with the glow on and returns the SVG.
     *
     * @param glow whether to enable the glow
     * @return the rendered SVG
     * @throws Exception if rendering fails
     */
    private String render(boolean glow) throws Exception {
        SVGImageWorker w = new SVGImageWorker(0, 0, glow, false, "dark");
        w.resize(400, 200);
        w.drawLine(10, 100, 390, 100, new Color(0, 200, 0), new BasicStroke(2f));
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        w.makeImage(os);
        return os.toString("UTF-8");    }

    /** The glow filter must blur the graphic, not the alpha channel, to keep the line colour. */
    @Test
    public void glowBlursTheGraphicSoTheHaloKeepsTheLineColour() throws Exception {
        String svg = render(true);
        int f = svg.indexOf("<filter id=\"glow\"");
        assertTrue("no glow filter was emitted", f >= 0);
        int end = svg.indexOf("</filter>", f);
        assertTrue("the glow filter is unterminated", end > f);
        String filter = svg.substring(f, end);
        assertTrue("the glow does not blur SourceGraphic: " + filter,
                   filter.contains("in=\"SourceGraphic\""));
        assertFalse("the glow blurs SourceAlpha, which renders a black halo: " + filter,
                    filter.contains("SourceAlpha"));
    }

    /** The blur feeds a fade and is merged back under the line, so the line stays crisp. */
    @Test
    public void glowMergesTheFadedBlurBackUnderTheLine() throws Exception {
        String svg = render(true);
        int f = svg.indexOf("<filter id=\"glow\"");
        int end = svg.indexOf("</filter>", f);
        String filter = svg.substring(f, end);
        assertTrue("the blur is not faded: " + filter, filter.contains("feFuncA"));
        int merge = filter.indexOf("<feMerge>");
        assertTrue("the halo is not merged: " + filter, merge >= 0);
        String merged = filter.substring(merge);
        int halo = merged.indexOf("feMergeNode in=\"fadedBlur\"");
        int line = merged.indexOf("feMergeNode in=\"SourceGraphic\"");
        assertTrue("the faded halo is not merged at all: " + filter, halo >= 0);
        assertTrue("the line is not merged at all: " + filter, line >= 0);
        assertTrue("the halo is merged over the line instead of under it: " + filter, halo < line);
    }

    /** With the glow off no filter is emitted at all, so nothing renders a halo. */
    @Test
    public void noGlowFilterWithoutTheGlow() throws Exception {
        assertFalse("a glow filter was emitted with the glow off",
                    render(false).contains("<filter id=\"glow\""));
    }
}
