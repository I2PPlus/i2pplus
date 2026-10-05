package org.rrd4j.graph;

import java.io.ByteArrayOutputStream;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that every theme's graph text is painted in that theme's own colour.
 *
 * <p>The serialiser emits one {@code <style>} block per image and fills in the theme's text
 * colour from a table keyed by theme name. Midnight had no entry, so its labels, legend and
 * title fell through to the base stylesheet's default fill - a dark grey on a near-black
 * canvas, which is to say invisible. Nothing failed: the colour was simply never written.
 *
 * @since 0.9.71+
 */
public class ThemeTextColorTest {

    /** The text colour each theme must write, matching the ink its page draws with. */
    private static final String[][] THEMES = {
        { "light", "#33333f" },
        { "classic", "#33333f" },
        { "dark", "#f4f4be" },
        { "midnight", "#c9ceff" },
    };

    /**
     * Every theme must name a text colour.
     *
     * <p>This is the check that was missing. Midnight's absence was invisible in a render on
     * any other theme, because only the missing rule is a problem and only midnight had one.
     */
    @Test
    public void everyThemePaintsItsTextInItsOwnColour() throws Exception {
        for (String[] theme : THEMES) {
            String style = styleBlockOf(theme[0]);
            assertTrue(theme[0] + " writes no text colour, so its labels fall back to the"
                       + " base stylesheet:\n" + style,
                       style.contains("text{fill:"));
            assertTrue(theme[0] + " text is " + theme[1] + ", not another theme's:\n" + style,
                       style.contains("text{fill:" + theme[1] + ";"));
        }
    }

    /**
     * The axis rules are text's neighbour and carry the same ink on every theme.
     *
     * <p>Checked for equality, not just presence: {@code --graph_axis} is the theme's
     * statement of this colour, so a rule that softened it would be the serialiser
     * overriding the stylesheet rather than falling back to it.
     */
    @Test
    public void everyThemeDrawsItsAxisInItsOwnInk() throws Exception {
        for (String[] theme : THEMES) {
            String style = styleBlockOf(theme[0]);
            int at = style.indexOf(".axis{");
            assertTrue(theme[0] + " emits no .axis rule:\n" + style, at >= 0);
            int end = style.indexOf('}', at);
            String rule = style.substring(at, end);
            assertTrue(theme[0] + " .axis rule sets no stroke: " + rule,
                       rule.contains("stroke:"));
            assertTrue(theme[0] + " axis is not " + theme[1] + ", its own --graph_axis: " + rule,
                       rule.contains("stroke:" + theme[1] + ";"));
        }
    }

    /**
     * Midnight's text must be the ink its own stylesheet declares.
     *
     * <p>Pinned separately because midnight is the theme that broke: the ink lives in the
     * theme and the serialiser's table must agree with it.
     */
    @Test
    public void midnightsTextIsTheInkItsPageUses() throws Exception {
        assertTrue("the serialiser must write midnight's ink",
                   styleBlockOf("midnight").contains("text{fill:#c9ceff;"));
    }

    /** Render one empty frame in the given theme and return just its {@code <style>} block. */
    private static String styleBlockOf(String theme) throws Exception {
        SVGImageWorker w = new SVGImageWorker(0, 0, false, false, theme);
        w.resize(400, 200);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        w.makeImage(os);
        String svg = os.toString("UTF-8");
        int from = svg.indexOf("<style>");
        int to = svg.indexOf("</style>");
        assertTrue(theme + " emitted no style block:\n" + svg, from >= 0 && to > from);
        return svg.substring(from, to);
    }
}
