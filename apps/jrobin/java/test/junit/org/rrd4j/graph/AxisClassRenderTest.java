package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Stroke;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.ArcDef;
import org.rrd4j.core.DsDef;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.Sample;

import static org.junit.Assert.*;

/**
 * Tests that the axis keeps its own class when a real graph is rendered.
 *
 * <p>The MRTG-style value axis draws each label and its gridline together, so the last gridline
 * comes out immediately in front of the plot box and the two axis container lines, with nothing
 * between them. The grouping that runs over that stretch of consecutive {@code <line>} elements
 * used to hand the whole stretch the first class it found, which meant a gridline styled
 * {@code class="dash major"} restyled the axis as a dashed gridline too: the bounding box
 * stopped rendering solid on every graph tall enough for minor gridlines.
 *
 * <p>The console turns the MRTG axis on only for graphs at least 400x200, which is why the
 * defect tracked graph size rather than anything about the axis. Rendered graphs are used rather
 * than the drawing calls in isolation because the arrangement that triggers it - a classed
 * gridline immediately in front of the axis - only appears in the emitted document, and only on
 * one side of that threshold.
 *
 * @since 0.9.71+
 */
public class AxisClassRenderTest {

    private static final String DS_NAME = "value";
    private static final int STEP = 60;
    private static final int ROWS = 120;
    private static final long END = 1_700_000_000L;

    /** The console's grid colours: an alpha of 30/200 turns into the theme's minor/major rules. */
    private static final Color GRID_COLOR = new Color(244, 244, 190, 30);
    private static final Color MGRID_COLOR = new Color(200, 200, 0, 50);

    /** The console's grid stroke: 1px, round caps, dotted - the shape that becomes class="dash". */
    private static final Stroke GRID_STROKE = new BasicStroke(
            1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] {1, 1}, 0);

    private RrdDb db;
    private String path;

    /**
     * Creates an in-memory RRD holding a steadily rising series. The values themselves are
     * irrelevant to the axis; the graph only has to draw its grid and its bounding box.
     *
     * @throws IOException if the RRD cannot be created
     */
    @Before
    public void setUp() throws IOException {
        path = "axis-class-render-test";
        RrdDef def = new RrdDef(path);
        def.setStartTime(END - (long) ROWS * STEP);
        def.setStep(STEP);
        def.addDatasource(new DsDef(DS_NAME, DsType.GAUGE, STEP * 10, 0, Double.MAX_VALUE));
        def.addArchive(new ArcDef(ConsolFun.AVERAGE, 0.5, 1, ROWS));
        db = RrdDb.getBuilder().setRrdDef(def)
                .setBackendFactory(RrdBackendFactory.getFactory("MEMORY")).build();
        long t = END - (long) ROWS * STEP;
        for (int i = 0; i < ROWS; i++) {
            t += STEP;
            Sample sample = db.createSample();
            sample.setTime(t);
            sample.setValue(DS_NAME, 1000 + i * 10);
            sample.update();
        }
    }

    /** @throws IOException if the RRD cannot be closed */
    @After
    public void tearDown() throws IOException {
        if (db != null && !db.isClosed()) {
            db.close();
        }
    }

    /**
     * @param w graph width in pixels
     * @param h graph height in pixels
     * @return the rendered SVG, set up the way the console sets it up
     * @throws IOException if rendering fails
     */
    private String render(int w, int h) throws IOException {
        RrdGraphDef def = new RrdGraphDef(END - (long) ROWS * STEP, END);
        def.setWidth(w);
        def.setHeight(h);
        def.setNoLegend(true);
        def.setShowSignature(false);
        def.setAntiAliasing(false);
        def.setTextAntiAliasing(false);
        def.setColor(ElementsNames.grid, GRID_COLOR);
        def.setColor(ElementsNames.mgrid, MGRID_COLOR);
        def.setGridStroke(GRID_STROKE);
        // The console's rule: MRTG scaling - and with it the interleaved label/gridline axis -
        // only above 400x200. The defect lives on that side of the line.
        def.setAltYMrtg(w >= 400 && h >= 200);
        def.datasource(DS_NAME, path, DS_NAME, ConsolFun.AVERAGE,
                RrdBackendFactory.getFactory("MEMORY"));
        def.line(DS_NAME, Color.RED, "v", 1);
        byte[] svg = new RrdGraph(def, new SVGImageWorker(0, 0, false, false, "dark"))
                .getRrdGraphInfo().getBytes();
        return new String(svg, StandardCharsets.UTF_8);
    }

    /** @param svg the document @param cssClass the group's class @return its body, or null */
    private static String group(String svg, String cssClass) {
        String open = "<g class=\"" + cssClass + "\">";
        int at = svg.indexOf(open);
        if (at < 0) {
            return null;
        }
        int end = svg.indexOf("</g>", at);
        return end < 0 ? null : svg.substring(at + open.length(), end);
    }

    /** @param svg the document @return the number of {@code <line>} elements it contains */
    private static int countLines(String svg) {
        int n = 0;
        for (int i = svg.indexOf("<line"); i >= 0; i = svg.indexOf("<line", i + 1)) {n++;}
        return n;
    }

    /**
     * The regression: on the sizes that draw an MRTG axis the axis must come back in its own
     * group. The classed gridline drawn last used to take the whole run, axis included, so the
     * bounding box rendered as a dashed gridline instead of a solid edge.
     */
    @Test
    public void theAxisKeepsItsClassNextToTheLastGridline() throws IOException {
        int[][] sizes = { { 400, 200 }, { 600, 280 }, { 1000, 280 } };
        for (int[] wh : sizes) {
            String svg = render(wh[0], wh[1]);
            String axis = group(svg, "axis");
            assertNotNull("no axis group at " + wh[0] + "x" + wh[1]
                          + " - the bounding box lost its class to the gridline:\n" + head(svg),
                          axis);
            assertTrue("the axis group holds no lines at " + wh[0] + "x" + wh[1],
                       countLines(axis) >= 2);
            assertFalse("the translucent plot-box edges were folded into the axis at "
                        + wh[0] + "x" + wh[1] + ":\n" + head(axis),
                        axis.contains("stroke-opacity"));
        }
    }

    /**
     * Below the threshold the console draws a plain axis, whose labels separate the gridlines
     * from the axis. The axis must hold there too - the class must not depend on graph size.
     */
    @Test
    public void theAxisKeepsItsClassOnASmallGraph() throws IOException {
        String svg = render(296, 60);
        assertNotNull("no axis group on a small graph:\n" + head(svg), group(svg, "axis"));
    }

    /**
     * Guards the fixture: without a classed gridline immediately in front of the axis nothing is
     * at stake, and the test above would pass whatever the grouping did. The plot box edges are
     * groupless lines, so if they sit between the last gridline group and the axis with nothing
     * but lines in between, all three were consecutive when the document was written - which is
     * the stretch of lines the grouping has to split correctly.
     */
    @Test
    public void theGridlineInFrontOfTheAxisIsClassed() throws IOException {
        String svg = render(400, 200);
        int at = svg.indexOf("<g class=\"axis\">");
        assertTrue("no axis group, so the gridline cannot be shown to precede it:\n" + head(svg),
                   at > 0);
        int gridGroup = svg.lastIndexOf("<g class=\"dash", at);
        assertTrue("the fixture draws no classed gridline at all:\n" + head(svg), gridGroup > 0);
        String stretch = svg.substring(gridGroup, at);
        assertTrue("nothing but lines should sit between that gridline group and the axis:\n"
                   + stretch, stretch.contains("<line"));
        assertFalse("a label separates the gridline from the axis, so they never shared a run:\n"
                    + stretch, stretch.contains("<text"));
    }

    /** @param svg @return the first 600 characters, for failure messages */
    private static String head(String svg) {
        return svg.length() > 600 ? svg.substring(0, 600) : svg;
    }
}
