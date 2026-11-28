package org.jfree.svg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.ArcDef;
import org.rrd4j.core.DsDef;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.Sample;
import org.rrd4j.graph.RrdGraph;
import org.rrd4j.graph.RrdGraphDef;
import org.rrd4j.graph.SVGImageWorker;

/**
 * Collapsing a group boundary between two text elements must never restyle or lose one.
 *
 * <p>The SVG post-processor wraps each text in a group of its own, so a run of axis
 * labels serialises as one group per label, and a step exists to co-locate them again.
 * That step used to delete the boundary unconditionally, which drops the second group's
 * attributes - the group is what carries the font class and the clip path - and moves
 * any later children of that group into the first. It also never fired on a real graph:
 * its pattern pinned the clip reference to {@code #clip-2} while the text groups carry
 * {@code #clip-1}, so a rendered graph measured 0 boundaries matched, and the size
 * reduction the step exists for was not happening at all.
 *
 * <p>Both halves are pinned here: the merge fires when it is safe, and refuses when it
 * would change how the text renders.
 *
 * @since 0.9.72+
 */
public class TextMergeTest {

    private static final String DS_NAME = "value";
    private static final int STEP = 60;
    private static final int ROWS = 120;
    private static final long END = 1_700_000_000L;

    /**
     * Test case carrying the merge cases. The pure cases need no renderer at all, and
     * the render case builds its own in-memory RRD.
     */
    public TextMergeTest() {
    }

    /**
     * One text-carrying group, the shape the font-class step leaves behind.
     *
     * @param attrs the group's own attributes, e.g. {@code class="mono s10"}
     * @param x the {@code x} attribute of the text element, in pixels
     * @param label the text body
     * @return the group markup, one text element inside it
     */
    private static String group(String attrs, int x, String label) {
        return "<g " + attrs + "><text x=\"" + x + "\">" + label + "</text></g>";
    }

    /** Two groups that carry the same attributes may share one group. */
    @Test
    public void twoIdenticalTextGroupsShareOneGroup() {
        String clip = "class=\"mono s10\" clip-path=\"url(#clip-1)\"";
        String in = group(clip, 81, "20:20") + group(clip, 181, "20:40");
        String out = SVGGraphics2D.mergeAdjacentTexts(in);
        assertEquals("one boundary must go", 1, count(out, "<g "));
        assertTrue("both texts must survive", out.contains(">20:20</text>") &&
                                            out.contains(">20:40</text>"));
        assertEquals("both texts must still be there", 2, count(out, "<text"));
    }

    /**
     * Two groups with different font classes must not be merged: the text in the second
     * would be drawn in the first one's font, which is the restyling the step used to do.
     */
    @Test
    public void differentFontClassesAreNotMerged() {
        String in = "<g class=\"mono s10\"><text x=\"81\">20:20</text></g>" +
                    "<g class=\"sans s11\"><text x=\"181\">Bandwidth</text></g>";
        String out = SVGGraphics2D.mergeAdjacentTexts(in);
        assertEquals("both groups must stay", 2, count(out, "<g "));
        assertTrue("the second text must keep its own font class",
                   out.contains("<g class=\"sans s11\"><text x=\"181\">Bandwidth</text>"));
    }

    /**
     * Two groups clipped to different regions must not be merged: the second text would
     * lose its clip path and could be drawn outside the area the plot clips it to.
     */
    @Test
    public void differentClipPathsAreNotMerged() {
        String cls = "class=\"mono s10\"";
        String in = group(cls + " clip-path=\"url(#clip-1)\"", 81, "20:20") +
                    group(cls + " clip-path=\"url(#clip-2)\"", 181, "20:40");
        String out = SVGGraphics2D.mergeAdjacentTexts(in);
        assertEquals("both groups must stay", 2, count(out, "<g "));
        assertTrue("the second clip path must survive", out.contains("clip-path=\"url(#clip-2)\""));
    }

    /**
     * The clip reference is not part of the decision. The old pattern matched only
     * {@code #clip-2}, and a rendered graph's text groups carry {@code #clip-1}, so the
     * step matched nothing at all.
     */
    @Test
    public void theClipReferenceIsNotHardcoded() {
        String in = "<g clip-path=\"url(#clip-1)\"><text x=\"81\">20:20</text></g>" +
                    "<g clip-path=\"url(#clip-1)\"><text x=\"181\">20:40</text></g>";
        String out = SVGGraphics2D.mergeAdjacentTexts(in);
        assertEquals("a clip-1 pair must merge like any other", 1, count(out, "<g "));
        assertTrue("the clip path must survive on the group that keeps it",
                   out.contains("<g clip-path=\"url(#clip-1)\">"));
    }

    /**
     * A group with children after its text must not have them pulled into the previous
     * group when the two groups differ - the children belong to the second group's font.
     */
    @Test
    public void laterChildrenStayWithTheirGroupWhenTheMergeIsRefused() {
        String in = "<g class=\"mono s10\"><text x=\"81\">20:20</text></g>" +
                    "<g class=\"sans s11\"><text x=\"181\">Bandwidth</text>" +
                    "<line x1=\"1\" y1=\"2\" x2=\"3\" y2=\"4\"/></g>";
        String out = SVGGraphics2D.mergeAdjacentTexts(in);
        assertTrue("the line must stay inside the group it was drawn in",
                   out.contains("<g class=\"sans s11\"><text x=\"181\">Bandwidth</text>" +
                                "<line x1=\"1\" y1=\"2\" x2=\"3\" y2=\"4\"/></g>"));
    }

    /**
     * A run of identical label groups collapses to one group holding every label, which
     * is the size reduction the step exists for.
     */
    @Test
    public void aRunOfLabelGroupsCollapsesToOneGroup() {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < 5; i++)
            in.append("<g class=\"mono s10\" clip-path=\"url(#clip-1)\"><text x=\"").append(i * 100)
              .append("\">label").append(i).append("</text></g>");
        String out = SVGGraphics2D.mergeAdjacentTexts(in.toString());
        assertEquals("five identical groups must become one", 1, count(out, "<g "));
        assertEquals("every label must survive", 5, count(out, "<text"));
        for (int i = 0; i < 5; i++)
            assertTrue("label" + i + " must survive", out.contains(">label" + i + "</text>"));
    }

    /**
     * A text element that is not the last child of its group is not a boundary the step
     * may collapse, since the group does not end at that text.
     */
    @Test
    public void aTextFollowedByAnotherChildIsNotABoundary() {
        String in = "<g class=\"mono s10\"><text x=\"81\">20:20</text><line x1=\"1\" y1=\"2\" " +
                    "x2=\"3\" y2=\"4\"/></g><g class=\"mono s10\"><text x=\"181\">20:40</text></g>";
        String out = SVGGraphics2D.mergeAdjacentTexts(in.toString());
        assertEquals("no boundary may go", 2, count(out, "<g "));
    }

    /**
     * A document with no adjacent text groups is returned unchanged, so the step cannot
     * disturb the rest of the document.
     */
    @Test
    public void aDocumentWithoutAdjacentTextGroupsIsUnchanged() {
        String in = "<g class=\"axis\"><line x1=\"0\" y1=\"0\" x2=\"10\" y2=\"0\"/></g>" +
                    "<text x=\"1\">only</text>";
        assertEquals(in, SVGGraphics2D.mergeAdjacentTexts(in));
    }

    /**
     * The regression, end to end: a rendered graph must still carry every axis label,
     * exactly once, with the merge now firing on it. Before the change the step matched
     * nothing on a rendered graph, so this asserts the document the new step does produce
     * is still complete.
     *
     * @throws IOException if the in-memory RRD cannot be rendered to SVG
     */
    @Test
    public void aRenderedGraphKeepsEveryLabel() throws IOException {
        String svg = render();
        List<String> labels = texts(svg);
        assertTrue("the rendered graph must carry time-axis labels: " + labels,
                   labels.size() >= 4);
        for (String label : labels)
            assertEquals("each label must appear exactly once: " + label, 1,
                         count(svg, ">" + label + "</text>"));
        assertTrue("the rendered graph must still group its text: " + svg,
                   svg.contains("<g class=\"mono s10\""));
    }

    /**
     * The merge must be a reduction, not a rewrite: the rendered document keeps every
     * text element it had before the step ran on it.
     *
     * @throws IOException if the in-memory RRD cannot be rendered to SVG
     */
    @Test
    public void theMergeLosesNoTextFromARenderedGraph() throws IOException {
        String merged = render();
        String unmerged = SVGGraphics2D.mergeAdjacentTexts(merged);
        assertEquals("running the step again must not drop a text element",
                     count(merged, "<text"), count(unmerged, "<text"));
        for (String label : texts(merged))
            assertTrue("label must survive a second pass: " + label,
                       unmerged.contains(">" + label + "</text>"));
    }

    /**
     * Render a graph of the standard console size over a bandwidth-like series.
     *
     * @return the rendered SVG text
     * @throws IOException if the in-memory RRD cannot be rendered
     */
    private static String render() throws IOException {
        String path = "text-merge-render";
        RrdDef def = new RrdDef(path);
        def.setStartTime(END - (long) ROWS * STEP);
        def.setStep(STEP);
        def.addDatasource(new DsDef(DS_NAME, DsType.GAUGE, STEP * 10, 0, Double.MAX_VALUE));
        def.addArchive(new ArcDef(ConsolFun.AVERAGE, 0.5, 1, ROWS));
        RrdDb db = RrdDb.getBuilder().setRrdDef(def)
                .setBackendFactory(RrdBackendFactory.getFactory("MEMORY")).build();
        try {
            long t = END - (long) ROWS * STEP;
            for (int i = 0; i < ROWS; i++) {
                t += STEP;
                Sample sample = db.createSample();
                sample.setTime(t);
                sample.setValue(DS_NAME, 120_000 + (i % 40) * 10_000);
                sample.update();
            }
            RrdGraphDef gdef = new RrdGraphDef(END - (long) ROWS * STEP, END);
            gdef.setWidth(600);
            gdef.setHeight(240);
            gdef.setTitle("Bandwidth over 2 hours");
            gdef.setAntiAliasing(false);
            gdef.setTextAntiAliasing(false);
            gdef.datasource(DS_NAME, path, DS_NAME, ConsolFun.AVERAGE,
                            RrdBackendFactory.getFactory("MEMORY"));
            gdef.line(DS_NAME, Color.RED, "v", 1);
            byte[] svg = new RrdGraph(gdef, new SVGImageWorker(0, 0, false, false, "dark"))
                    .getRrdGraphInfo().getBytes();
            return new String(svg, StandardCharsets.UTF_8);
        } finally {
            db.close();
        }
    }

    /**
     * The text drawn by a rendered document.
     *
     * @param svg the document to scan
     * @return every text body, in document order
     */
    private static List<String> texts(String svg) {
        List<String> out = new ArrayList<>();
        int at = 0;
        while ((at = svg.indexOf("<text", at)) >= 0) {
            int gt = svg.indexOf('>', at);
            int end = svg.indexOf("</text>", gt);
            if (end < 0) {break;}
            String body = svg.substring(gt + 1, end).replaceAll("<[^>]*>", "").trim();
            if (!body.isEmpty()) {out.add(body);}
            at = end;
        }
        return out;
    }

    /**
     * Count of non-overlapping occurrences of a literal.
     *
     * @param haystack the text to scan
     * @param needle the literal to count
     * @return how many times it occurs
     */
    private static int count(String haystack, String needle) {
        int n = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        assertFalse("the scan must make progress", needle.isEmpty());
        return n;
    }
}
