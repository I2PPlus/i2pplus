package org.jfree.svg;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the line-run grouping that collapses adjacent {@code <line>} elements into {@code <g>}
 * wrappers on the way out of {@code postProcessSvg}.
 *
 * <p>The grouping used to hand the whole run the first class it found and strip every line's own
 * class. The axis container lines are tagged {@code class="axis"} and are drawn straight after the
 * value-axis gridlines, so on any graph whose last gridline serialised in front of them - every
 * graph tall enough for minor gridlines - the axis was restyled as a dashed gridline instead of
 * the solid bounding box. That is the whole bug: the outcome depended on the order elements
 * happened to appear in, not on what any of them were drawn as.
 *
 * <p>The regression case below is the run captured verbatim from a live 1000x280 single-view
 * bandwidth graph, where the axis lines came out inside {@code <g class="dash major">}.
 *
 * @since 0.9.71+
 */
public class LineGroupingTest {

    /** A value-axis gridline, drawn with a class by the theme rewrite. */
    private static final String GRIDLINE =
        "<line x1=\"63\" y1=\"31\" x2=\"1063\" y2=\"31\" class=\"dash major\" />";

    /** The plot box's top and right edges: drawn in the grid colour, no class. */
    private static final String BOUNDARY_V =
        "<line x1=\"1063\" y1=\"311\" x2=\"1063\" y2=\"31\""
        + " style=\"stroke:rgb(244,244,190);stroke-opacity:.12\"/>";
    private static final String BOUNDARY_H =
        "<line x1=\"63\" y1=\"31\" x2=\"1063\" y2=\"31\""
        + " style=\"stroke:rgb(244,244,190);stroke-opacity:.12\"/>";

    /** The two axis container lines, already tagged by SVGGraphics2D.tagAxis(). */
    private static final String AXIS_X =
        "<line x1=\"59\" y1=\"311\" x2=\"1067\" y2=\"311\" class=\"axis\" />";
    private static final String AXIS_Y =
        "<line x1=\"63\" y1=\"315\" x2=\"63\" y2=\"27\" class=\"axis\" />";

    /** The live run, in the order the serialiser emitted it. */
    private static final String LIVE_RUN = GRIDLINE + BOUNDARY_V + BOUNDARY_H + AXIS_X + AXIS_Y;

    /** The class each line in {@link #LIVE_RUN} must render with, in document order. */
    private static final String[] LIVE_EXPECTED = {
        "dash major", null, null, "axis", "axis",
    };

    private static final Pattern TOKEN = Pattern.compile(
        "<g class=\"([^\"]+)\">|</g>|<line[^/]*/>");

    private static final Pattern OWN_CLASS = Pattern.compile("class=\"([^\"]+)\"");

    /**
     * Walks the markup in document order and reports the class each line actually renders with:
     * its own class if it still has one, otherwise the class of the group that encloses it.
     * A line that renders under a class it was not drawn with is exactly the defect.
     */
    private static String[] effectiveClasses(String svg) {
        List<String> classes = new ArrayList<String>();
        List<String> openGroups = new ArrayList<String>();
        Matcher m = TOKEN.matcher(svg);
        while (m.find()) {
            String token = m.group();
            if (token.startsWith("<g ")) {
                openGroups.add(m.group(1));
            } else if (token.equals("</g>")) {
                openGroups.remove(openGroups.size() - 1);
            } else {
                Matcher own = OWN_CLASS.matcher(token);
                String inherited = openGroups.isEmpty()
                        ? null : openGroups.get(openGroups.size() - 1);
                classes.add(own.find() ? own.group(1) : inherited);
            }
        }
        return classes.toArray(new String[classes.size()]);
    }

    private static String group(String svg, String cssClass) {
        String open = "<g class=\"" + cssClass + "\">";
        int at = svg.indexOf(open);
        if (at < 0) {
            return null;
        }
        int end = svg.indexOf("</g>", at);
        return svg.substring(at + open.length(), end);
    }

    /**
     * The live 1000x280 case: a classed gridline in front of the axis must not take the axis
     * with it, and the unclassed plot-box edges must not be handed a class either.
     */
    @Test
    public void aGridlineInFrontOfTheAxisDoesNotTakeTheAxisWithIt() {
        String out = SVGGraphics2D.groupLineRun(LIVE_RUN);
        assertArrayEquals("a line rendered under a class it was not drawn with\n" + out,
                          LIVE_EXPECTED, effectiveClasses(out));
    }

    /** The axis lines belong to the axis group, and to nothing else. */
    @Test
    public void theAxisLinesEndUpInTheAxisGroup() {
        String axis = group(SVGGraphics2D.groupLineRun(LIVE_RUN), "axis");
        assertNotNull("no axis group - the bounding box lost its class", axis);
        assertTrue("axis x missing from " + axis, axis.contains("x1=\"59\""));
        assertTrue("axis y missing from " + axis, axis.contains("y1=\"315\""));
        assertFalse("a gridline or plot-box edge was folded into the axis", axis.contains("x1=\"63\" y1=\"31\""));
        assertFalse("an unclassed line inherited the axis class", axis.contains("stroke-opacity"));
    }

    /** The gridline keeps its own class, and only itself. */
    @Test
    public void theGridlineKeepsItsOwnGroup() {
        String major = group(SVGGraphics2D.groupLineRun(LIVE_RUN), "dash major");
        assertNotNull("no dash major group", major);
        assertTrue(major.contains("x1=\"63\" y1=\"31\" x2=\"1063\""));
        assertFalse("the axis was folded into the gridline's group", major.contains("x1=\"59\""));
    }

    /** An unclassed line is emitted exactly as it arrived: no wrapper, no borrowed class. */
    @Test
    public void anUnclassedLineIsLeftAlone() {
        String out = SVGGraphics2D.groupLineRun(BOUNDARY_V + BOUNDARY_H);
        assertEquals(BOUNDARY_V + BOUNDARY_H, out);
    }

    /** One class across the whole run still collapses to a single group. */
    @Test
    public void aRunOfTheSameClassCollapsesToOneGroup() {
        String run = "<line x1=\"1\" y1=\"1\" x2=\"2\" y2=\"2\" class=\"dash minor\"/>"
                   + "<line x1=\"3\" y1=\"3\" x2=\"4\" y2=\"4\" class=\"dash minor\"/>";
        String out = SVGGraphics2D.groupLineRun(run);
        assertEquals("<g class=\"dash minor\"><line x1=\"1\" y1=\"1\" x2=\"2\" y2=\"2\" />"
                     + "<line x1=\"3\" y1=\"3\" x2=\"4\" y2=\"4\" /></g>", out);
    }

    /** The order must not matter: axis first, gridline after, same result for both. */
    @Test
    public void theOutcomeDoesNotDependOnTheOrderInTheRun() {
        String reversed = AXIS_X + AXIS_Y + GRIDLINE;
        assertArrayEquals(new String[] {"axis", "axis", "dash major"},
                          effectiveClasses(SVGGraphics2D.groupLineRun(reversed)));
    }

    /** Classes alternating inside one run split at every change rather than averaging. */
    @Test
    public void alternatingClassesSplitAtEveryChange() {
        String run = "<line x1=\"1\" class=\"dash major\"/>"
                   + "<line x1=\"2\"/>"
                   + "<line x1=\"3\" class=\"dash minor\"/>";
        assertArrayEquals(new String[] {"dash major", null, "dash minor"},
                          effectiveClasses(SVGGraphics2D.groupLineRun(run)));
    }
}
