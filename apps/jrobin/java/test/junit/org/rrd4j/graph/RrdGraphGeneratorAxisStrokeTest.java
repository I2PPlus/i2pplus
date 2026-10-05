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
    /**
     * The end-to-end contract, and the one that was missing: a line drawn at the marker
     * width must come out tagged {@code class="axis"}, with no marker width left anywhere
     * in the document. The constant tests above all passed while the tagging matched
     * nothing, because the axis arrives as a bare {@code <line>} and the pattern was
     * written for a {@code <g>}.
     */
    @Test
    public void aLineAtTheMarkerWidthComesBackTaggedAsTheAxis() throws Exception {
        String svg = axisSvg(5f);
        assertTrue("the axis was not tagged, so it renders at the marker width:\n"
                   + head(svg), svg.contains("class=\"axis\""));
        assertFalse("the marker width survived into the output:\n" + head(svg),
                    svg.contains("stroke-width:5"));
    }

    /** A line at some other width must NOT be tagged: it is a gridline or a rule. */
    @Test
    public void aLineAtAnOrdinaryWidthIsNotTagged() throws Exception {
        String svg = axisSvg(1f);
        assertFalse("an ordinary 1px line was mistaken for the axis", svg.contains("class=\"axis\""));
    }

    /**
     * The theme's rule is what makes the axis visible, so it has to be emitted with a sane
     * width. A 5px axis was the visible symptom of the tagging failing.
     */
    @Test
    public void theThemeRuleSuppliesAThinAxis() throws Exception {
        String svg = axisSvg(5f);
        assertTrue("no .axis rule in:\n" + head(svg), svg.contains(".axis{"));
        int at = svg.indexOf(".axis{");
        int end = svg.indexOf('}', at);
        String rule = svg.substring(at, end);
        assertFalse("the .axis rule must not ask for the marker width: " + rule,
                    rule.contains("stroke-width:5"));
        assertTrue("the .axis rule must set a stroke width: " + rule,
                    rule.contains("stroke-width:1") || rule.contains("stroke-width:2"));
    }

    /**
     * The axis lines are the graph's bounding box, so they must be solid. A translucent
     * rule reads as a smudge rather than an edge, and the theme colours injected for
     * {@code .axis} carry no alpha.
     */
    @Test
    public void theAxisIsSolidNotTranslucent() throws Exception {
        String svg = axisSvg(5f);
        int at = svg.indexOf(".axis{");
        assertTrue("no .axis rule in:\n" + head(svg), at > 0);
        int end = svg.indexOf('}', at);
        String rule = svg.substring(at, end);
        assertFalse("the axis rule must not fade the bounding box: " + rule,
                    rule.contains("opacity"));
        // And the tagged element must carry no inline alpha of its own either.
        int g = svg.indexOf("class=\"axis\"");
        assertTrue("axis not tagged", g > 0);
        int gt = svg.indexOf('>', g);
        assertFalse("the axis element keeps an inline opacity: " + svg.substring(g, gt),
                    svg.substring(g, gt).contains("opacity"));
    }

    /**
     * Collapsing adjacent stroke groups must not cost a line.
     *
     * <p>This is the mechanism behind the axis disappearing at large sizes. The old
     * collapse kept the first group's children and discarded the second group's, so the
     * number of lines lost grew with the number of groups - fine at 296x60, fatal at
     * 1000x280 where ~62 grid lines feed it. Counting drawn lines against the number
     * emitted pins it regardless of how the grouping falls out.
     */
    @Test
    public void noDrawnLineIsLostToGroupCollapsing() throws Exception {
        // Interleave the axis with grid lines, which is the arrangement that used to lose it.
        java.awt.Color axis = new java.awt.Color(244, 244, 190, 200);
        java.awt.Color grid = new java.awt.Color(244, 244, 190, 30);
        SVGImageWorker w = new SVGImageWorker(0, 0, false, false, "dark");
        w.resize(1000, 280);
        int gridLines = 60;
        for (int i = 0; i < gridLines; i++) {
            int x = 70 + i * 15;
            w.drawLine(x, 311, x, 31, grid, new java.awt.BasicStroke(1f));
        }
        w.drawLine(63, 311, 1063, 311, axis, new java.awt.BasicStroke(RrdGraphGenerator.AXIS_STROKE_WIDTH));
        w.drawLine(63, 315, 63, 27, axis, new java.awt.BasicStroke(RrdGraphGenerator.AXIS_STROKE_WIDTH));
        java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
        w.makeImage(os);
        String svg = os.toString("UTF-8");

        int emitted = countLines(svg);
        assertTrue("expected at least the " + gridLines + " grid lines plus two axis lines,"
                   + " got " + emitted, emitted >= gridLines + 2);
        assertTrue("the axis was lost to group collapsing", svg.contains("class=\"axis\""));
    }

    /**
     * The axis tag must not depend on the graph being small.
     *
     * <p>On a live router the axis was present at 296x60 and at 1000x199, and gone at
     * 1000x200. {@code isLarge} is {@code width >= 600 && height >= 200}, so the tagging
     * was sensitive to a flag that only chooses between a 1px and 2px CSS stroke. Whatever
     * the coupling is, it has to hold on both sides of that boundary.
     */
    @Test
    public void theAxisIsTaggedAtEverySize() throws Exception {
        int[][] sizes = { { 1000, 199 }, { 1000, 200 }, { 1000, 280 }, { 296, 60 } };
        for (int[] wh : sizes) {
            String svg = axisSvg(wh[0], wh[1], RrdGraphGenerator.AXIS_STROKE_WIDTH);
            assertTrue("axis not tagged at " + wh[0] + "x" + wh[1] + ":\n" + head(svg),
                       svg.contains("class=\"axis\""));
        }
    }

    /** Draw two axis lines at the given size and width, and return the finished SVG. */
    private static String axisSvg(int w, int h, float width) throws Exception {
        SVGImageWorker worker = new SVGImageWorker(0, 0, false, false, "dark");
        worker.resize(w, h);
        worker.fillRect(10, 10, w / 2, h / 2, java.awt.Color.WHITE);
        worker.drawLine(20, h - 10, w - 20, h - 10, new java.awt.Color(244, 244, 190, 200),
                        new java.awt.BasicStroke(width));
        worker.drawLine(20, h - 10, 20, 10, new java.awt.Color(244, 244, 190, 200),
                        new java.awt.BasicStroke(width));
        java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
        worker.makeImage(os);
        return os.toString("UTF-8");
    }

    /** Count <line> elements in the output. */
    private static int countLines(String svg) {
        int n = 0;
        for (int i = svg.indexOf("<line"); i >= 0; i = svg.indexOf("<line", i + 1)) {n++;}
        return n;
    }

    /** Draw one line at the given width and return the finished SVG. */
    private static String axisSvg(float width) throws Exception {
        SVGImageWorker w = new SVGImageWorker(0, 0, false, false, "dark");
        w.resize(400, 150);
        w.fillRect(10, 10, 200, 80, java.awt.Color.WHITE);
        w.drawLine(20, 120, 380, 120, new java.awt.Color(244, 244, 190, 200),
                   new java.awt.BasicStroke(width));
        w.drawLine(20, 120, 20, 40, new java.awt.Color(244, 244, 190, 200),
                   new java.awt.BasicStroke(width));
        java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
        w.makeImage(os);
        return os.toString("UTF-8");
    }

    private static String head(String svg) {
        return svg.length() > 600 ? svg.substring(0, 600) : svg;
    }

    @Test
    public void theMarkerSerialisesWithTheDigitThePatternExpects() {
        String serialised = "stroke-width:" + RrdGraphGenerator.AXIS_STROKE_WIDTH;
        assertTrue(serialised + " must match style=\"stroke-width:5",
                   serialised.startsWith("stroke-width:5"));
        assertTrue("and the pattern must match it",
                   (serialised + "\"").startsWith("stroke-width:5"));
    }
}