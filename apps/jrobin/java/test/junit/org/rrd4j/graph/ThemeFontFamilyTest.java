package org.rrd4j.graph;

import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that graph text takes its face from the console's font variables.
 *
 * <p>The serialiser collapses {@code font-family:monospace} and {@code font-family:sans-serif}
 * into the classes {@code .mono} and {@code .sans}. Those classes used to name a face outright
 * - {@code FiraCode,monospace} and {@code Open Sans,Segoe UI,Noto Sans,sans-serif} - which was a
 * hardcoded copy of what {@code OpenSans.css} already declares as {@code --monospaced} and
 * {@code --bodyfont}. The console could ship a different font set and the graphs would not
 * follow.
 *
 * <p>Each graph is served as an isolated document, so it cannot inherit the page's custom
 * properties; it links the console's own font stylesheet instead, which is what makes
 * {@code var()} usable here at all.
 *
 * @since 0.9.71+
 */
public class ThemeFontFamilyTest {

    /** A family that is not a CSS generic name, so the extraction leaves it alone. */
    private static final String NAMED_FAMILY = "DejaVu Sans Mono";

    /**
     * Both classes must read the console's variables rather than naming a face.
     *
     * <p>This is the check that was missing. It fails on the old behaviour, and it keeps
     * failing if the hardcoded stacks are ever pasted back in.
     */
    @Test
    public void theFontClassesReadTheConsoleVariables() throws Exception {
        String style = styleBlockOf();
        assertTrue("the .mono class does not use --monospaced, so a console font change"
                   + " will not reach the graphs:\n" + style,
                   rule(style, ".mono").contains("var(--monospaced"));
        assertTrue("the .sans class does not use --bodyfont, so a console font change"
                   + " will not reach the graphs:\n" + style,
                   rule(style, ".sans").contains("var(--bodyfont"));
        assertFalse("the .mono class still names a face of its own:\n" + style,
                    rule(style, ".mono").contains("FiraCode"));
        assertFalse("the .sans class still names a face of its own:\n" + style,
                    rule(style, ".sans").contains("Open Sans"));
    }

    /**
     * Each variable needs a fallback, for a document that failed to load the stylesheet.
     *
     * <p>An unresolved {@code var()} makes the whole declaration invalid at computed-value
     * time, so a missing stylesheet would leave the text with no family at all rather than a
     * generic one.
     */
    @Test
    public void eachVariableHasAGenericFallback() throws Exception {
        String style = styleBlockOf();
        assertTrue("var(--monospaced) has no fallback:\n" + style,
                   rule(style, ".mono").contains("var(--monospaced,monospace)"));
        assertTrue("var(--bodyfont) has no fallback:\n" + style,
                   rule(style, ".sans").contains("var(--bodyfont,sans-serif)"));
    }

    /**
     * The stylesheet carrying those variables has to be linked, or the vars are unset.
     *
     * <p>This is what makes the whole approach work, and it is a link rather than an inherited
     * property precisely because the document is isolated.
     */
    @Test
    public void theConsoleFontStylesheetIsLinked() throws Exception {
        String svg = svgWithFontFamily(Font.MONOSPACED);
        assertTrue("no console font stylesheet is linked into the graph:\n" + svg,
                   svg.contains("/themes/fonts/OpenSans.css"));
    }

    /**
     * A family that is not generic survives on the element itself.
     *
     * <p>The extraction only rewrites the two generic spellings, so naming a physical face
     * keeps it - which is the escape hatch for a graph that wants to differ from the page.
     */
    @Test
    public void aNamedFamilySurvivesOnTheElement() throws Exception {
        String svg = svgWithFontFamily(NAMED_FAMILY);
        assertTrue("the named family was dropped on the way out:\n" + svg,
                   svg.contains("font-family:" + NAMED_FAMILY));
    }

    /** The body of one rule from the emitted style block, or "" when it is absent. */
    private static String rule(String style, String selector) {
        int at = style.indexOf(selector + "{");
        if (at < 0) {return "";}
        return style.substring(at, style.indexOf('}', at));
    }

    private static String styleBlockOf() throws Exception {
        String svg = svgWithFontFamily(Font.MONOSPACED);
        int from = svg.indexOf("<style>");
        int to = svg.indexOf("</style>");
        assertTrue("no style block was emitted:\n" + svg, from >= 0 && to > from);
        return svg.substring(from, to);
    }

    /**
     * Render a graph carrying one text element in one family.
     *
     * @param family the family to ask for, in the form {@code new Font} would take
     * @return the SVG as a string
     */
    private static String svgWithFontFamily(String family) throws Exception {
        SVGImageWorker w = new SVGImageWorker(0, 0, false, false, "light");
        w.resize(400, 200);
        w.drawString("Sample", 4, 12, new Font(family, Font.PLAIN, 12), Color.BLACK);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        w.makeImage(os);
        return os.toString("UTF-8");
    }
}
