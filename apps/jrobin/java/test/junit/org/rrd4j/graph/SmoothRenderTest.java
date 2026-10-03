package org.rrd4j.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rrd4j.core.ArcDef;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.DsDef;
import org.rrd4j.core.FetchData;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.Sample;

/**
 * End-to-end checks that the smoothed plot renderer produces well-formed SVG and that switching it
 * on leaves the default step rendering untouched.
 *
 * <p>These render real graphs through {@link RrdGraph} rather than testing the drawing calls in
 * isolation, because the failure modes worth guarding against - a fill outline that closes on
 * itself wrongly, or a curve escaping the plot area - only appear in the emitted path data.
 */
public class SmoothRenderTest {

    private static final String DS_NAME = "value";
    private static final int STEP = 60;
    /** Periods stored in the RRD. */
    private static final int ROWS = 240;
    /**
     * Periods actually plotted. The console's default is 60 periods across a 400px graph, so
     * each period gets several pixels. That matters: when periods outnumber pixels, two periods
     * share a column and a step change falls inside a single column, where no curve can be drawn.
     */
    private static final int WINDOW = 60;
    private static final int WIDTH = 400;
    private static final int HEIGHT = 100;
    /**
     * Fixed end timestamp in SECONDS (the unit RRD uses throughout), so rendered output is
     * reproducible.
     */
    private static final long END = 1_700_000_000L;

    /** @return the first timestamp covered by the RRD */
    private static long start() {
        return END - (long) ROWS * STEP;
    }

    /** @return the first timestamp of the plotted window */
    private static long windowStart() {
        return END - (long) WINDOW * STEP;
    }

    private RrdDb db;
    private String path;

    /**
     * Creates an in-memory RRD pre-filled with a representative series: long flat stretches,
     * step changes, and a ramp. This is the shape real router stats have, and the shape the
     * smoothing rules exist for.
     *
     * @throws IOException if the RRD cannot be created
     */
    @Before
    public void setUp() throws IOException {
        path = "smooth-render-test";
        RrdDef def = new RrdDef(path);
        def.setStartTime(start());
        def.setStep(STEP);
        def.addDatasource(new DsDef(DS_NAME, DsType.GAUGE, STEP * 10, 0, Double.MAX_VALUE));
        // The third argument is a MULTIPLE of the RRD step, not a step count: passing STEP here
        // would make the archive STEP times longer than intended and coarsen every fetch.
        def.addArchive(new ArcDef(ConsolFun.AVERAGE, 0.5, 1, ROWS));
        def.addArchive(new ArcDef(ConsolFun.MAX, 0.5, 1, ROWS));
        RrdBackendFactory factory = RrdBackendFactory.getFactory("MEMORY");
        db = RrdDb.getBuilder().setRrdDef(def).setBackendFactory(factory).build();

        // The RRD's clock starts at startTime and every sample must advance it, so the first
        // sample lands one step in and the last lands exactly on END. Timestamps are seconds.
        long t = start();
        for (int i = 0; i < ROWS; i++) {
            t += STEP;
            Sample sample = db.createSample();
            sample.setTime(t);
            sample.setValue(DS_NAME, seriesValue(i));
            sample.update();
        }
    }

    /**
     * Discards the in-memory RRD so tests do not leak state into one another.
     *
     * @throws IOException if the RRD cannot be closed
     */
    @After
    public void tearDown() throws IOException {
        if (db != null && !db.isClosed()) {
            db.close();
        }
    }

    /**
     * Builds a value shaped like a real router statistic: flat stretches, step changes that take
     * a few periods rather than one, and a ramp. The interesting part sits inside the plotted
     * window; earlier periods only need to be valid.
     *
     * @param i sample index
     * @return the value for that period
     */
    private static double seriesValue(int i) {
        int w = i - (ROWS - WINDOW);
        if (w < 0) {
            return 1000;
        }
        if (w < 12) {
            return 1000;                                  // flat
        } else if (w < 16) {
            return 1000 + (w - 12) * 750;                 // step up over four periods
        } else if (w < 28) {
            return 4000;                                  // flat
        } else if (w < 32) {
            return 4000 - (w - 28) * 800;                 // step down over four periods
        } else if (w < 48) {
            return 800 + (w - 32) * 60;                    // ramp
        }
        return 2000;                                      // flat
    }

    /**
     * @param smooth whether to enable smoothing
     * @param line true to plot as a line, false as an area
     * @return the rendered SVG
     * @throws IOException if rendering fails
     */
    private String render(boolean smooth, boolean line) throws IOException {
        return render(windowStart(), smooth, line);
    }

    /**
     * @param from start of the plotted window, in seconds
     * @param smooth whether to enable smoothing
     * @param line true to plot as a line, false as an area
     * @return the rendered SVG
     * @throws IOException if rendering fails
     */
    private String render(long from, boolean smooth, boolean line) throws IOException {
        return render(from, END, smooth, line);
    }

    /**
     * @param from start of the plotted window, in seconds
     * @param to end of the plotted window, in seconds
     * @param smooth whether to enable smoothing
     * @param line true to plot as a line, false as an area
     * @return the rendered SVG
     * @throws IOException if rendering fails
     */
    private String render(long from, long to, boolean smooth, boolean line) throws IOException {
        RrdGraphDef def = new RrdGraphDef(from, to);
        def.setWidth(WIDTH);
        def.setHeight(HEIGHT);
        def.setSmoothing(smooth);
        def.setNoLegend(true);
        def.setShowSignature(false);
        def.setAntiAliasing(false);
        def.setTextAntiAliasing(false);
        def.setDrawXGrid(false);
        def.setDrawYGrid(false);
        def.datasource(DS_NAME, path, DS_NAME, ConsolFun.AVERAGE,
                RrdBackendFactory.getFactory("MEMORY"));
        if (line) {
            def.line(DS_NAME, java.awt.Color.RED, "v", 1);
        } else {
            def.area(DS_NAME, java.awt.Color.BLUE, "v");
        }
        RrdGraph graph = new RrdGraph(def, new SVGImageWorker(0, 0, false, smooth));
        return new String(graph.getRrdGraphInfo().getBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Resolves a plot path's relative deltas into the x coordinates it visits.
     *
     * @param d one plot path
     * @return every absolute x the path reaches, in order
     */
    private static List<Double> pathXs(String d) {
        List<String> toks = new ArrayList<>();
        java.util.regex.Matcher t =
                java.util.regex.Pattern.compile("[A-Za-z]|[-+]?\\d*\\.?\\d+").matcher(d);
        while (t.find()) {
            toks.add(t.group());
        }
        List<Double> xs = new ArrayList<>();
        double x = 0;
        for (int i = 0; i < toks.size(); ) {
            String cmd = toks.get(i++);
            if ("Z".equals(cmd) || "z".equals(cmd)) {
                continue;
            }
            int pairs = ("C".equals(cmd) || "c".equals(cmd)) ? 3 : 1;
            for (int k = 0; k < pairs; k++) {
                x += Double.parseDouble(toks.get(i++));
                i++;
                xs.add(x);
            }
        }
        return xs;
    }

    /**
     * Reads the left edge of the plot area out of the document's clip path, which is the rectangle
     * {@code RrdGraphGenerator.drawData} clips the plots to.
     *
     * @param svg the SVG
     * @return the clip rectangle's left x coordinate
     */
    private static int clipLeft(String svg) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(?s)<clipPath.*?d=\"M(-?[\\d.]+)").matcher(svg);
        assertTrue("the document must carry a clip path for the plot area", m.find());
        return (int) Double.parseDouble(m.group(1));
    }

    /**
     * Extracts the plot path data from the document body. Clip path definitions are removed
     * first: they also carry {@code d} attributes made of line segments, and are not plots.
     *
     * @param svg the SVG
     * @return every remaining {@code d="..."} value, in document order
     */
    private static List<String> pathData(String svg) {
        String body = svg.replaceAll("(?s)<clipPath.*?</clipPath>", "");
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("d=\"([^\"]*)\"").matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * Guards the fixture itself: the archive must resolve to one row per step. If the archive
     * step were accidentally a multiple of the RRD step, the fetch would consolidate to a coarse
     * resolution and every geometry assertion below would pass on a handful of points.
     */
    @Test
    public void testFixtureHasFullResolution() throws IOException {
        FetchData fd = db.createFetchRequest(ConsolFun.AVERAGE, windowStart(), END).fetchData();
        assertTrue("expected about one row per step, got " + fd.getRowCount(),
                fd.getRowCount() >= WINDOW - 2);
        int nonNaN = 0;
        for (double v : fd.getValues()[0]) {
            if (!Double.isNaN(v)) {
                nonNaN++;
            }
        }
        // one extra row because the fetch window is inclusive at both ends
        assertEquals("every plotted sample should be readable", WINDOW + 1, nonNaN);
    }

    /**
     * Counts how the plot path is drawn: horizontal segments (a flat run or a flat curve), curves
     * that actually change height, and unsmoothed vertical steps.
     *
     * @param d one plot path
     * @return {flat, curved, verticalSteps}
     */
    private static int[] classify(String d) {
        java.util.List<String> t = new java.util.ArrayList<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("[A-Za-z]|[-+]?\\d*\\.?\\d+").matcher(d);
        while (m.find()) {
            t.add(m.group());
        }
        int flat = 0, curved = 0, vertical = 0;
        for (int i = 0; i < t.size(); ) {
            String c = t.get(i);
            i++;
            if ("Z".equals(c) || "z".equals(c)) {
                continue;
            }
            boolean cubic = "c".equals(c) || "C".equals(c);
            int need = cubic ? 6 : 2;
            if (i + need > t.size()) {
                break;
            }
            try {
                Double.parseDouble(t.get(i));
            } catch (NumberFormatException nfe) {
                continue;
            }
            double dx, dy;
            if (cubic) {
                dx = Double.parseDouble(t.get(i + 4));
                dy = Double.parseDouble(t.get(i + 5));
            } else {
                dx = Double.parseDouble(t.get(i));
                dy = Double.parseDouble(t.get(i + 1));
            }
            i += need;
            if (Math.abs(dy) < 0.5) {
                flat++;
            } else if (cubic) {
                curved++;
            } else if (dx == 0) {
                vertical++;
            }
        }
        return new int[] {flat, curved, vertical};
    }

    /**
     * @return the longest path in the document body, which is the plot
     */
    private static String plotPath(String svg) {
        String longest = "";
        for (String d : pathData(svg)) {
            if (d.length() > longest.length()) {
                longest = d;
            }
        }
        return longest;
    }

    /**
     * The steps in the default renderer are structural: every sample's x is emitted twice, so each
     * value change lands on a zero-width edge that no curve can cross. Smoothing must switch to
     * one point per sample, which is what turns those vertical steps into curves.
     */
    @Test
    public void testSmoothingRemovesVerticalSteps() throws IOException {
        int[] step = classify(plotPath(render(false, true)));
        int[] smooth = classify(plotPath(render(true, true)));
        assertTrue("the step renderer should produce vertical steps, got none",
                step[2] > 0);
        assertEquals("smoothing must leave no unsmoothed vertical step", 0, smooth[2]);
        assertTrue("smoothing must produce curves, got none", smooth[1] > 0);
    }

    /**
     * Flat stretches must survive smoothing as flat: a plateau is drawn with a horizontal tangent
     * at both ends, so it stays a straight line rather than bowing.
     */
    @Test
    public void testSmoothingKeepsPlateausFlat() throws IOException {
        int[] step = classify(plotPath(render(false, true)));
        int[] smooth = classify(plotPath(render(true, true)));
        assertTrue("the fixture must contain plateaus", step[0] > 0);
        assertTrue("plateaus must still be flat after smoothing, got " + smooth[0],
                smooth[0] > 0);
    }

    /**
     * A smoothed area must be stroked as well as filled, exactly like the step renderer, which
     * draws the filled polygon a second time to outline it. Without the stroke every smoothed
     * graph loses its border and does not read the same as the stepped one.
     */
    @Test
    public void testSmoothedAreaIsAlsoOutlined() throws IOException {
        String svg = render(true, false);
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("<g([^>]*)>").matcher(svg);
        int fills = 0;
        int strokes = 0;
        while (m.find()) {
            String attrs = m.group(1);
            if (attrs.contains("fill:rgb(") && attrs.contains("stroke:none")) {
                fills++;
            } else if (attrs.contains("fill:none") && attrs.contains("stroke:rgb(")) {
                strokes++;
            }
        }
        assertEquals("the area must be filled once", 1, fills);
        assertEquals("the area outline must be stroked once", 1, strokes);
    }

    /** The same outline must be present whether or not smoothing is on. */
    @Test
    public void testStepAndSmoothedAreaHaveSameStructure() throws IOException {
        for (String svg : new String[] {render(false, false), render(true, false)}) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("<g([^>]*)>").matcher(svg);
            int fills = 0;
            int strokes = 0;
            while (m.find()) {
                String attrs = m.group(1);
                if (attrs.contains("fill:rgb(") && attrs.contains("stroke:none")) {
                    fills++;
                } else if (attrs.contains("fill:none") && attrs.contains("stroke:rgb(")) {
                    strokes++;
                }
            }
            assertEquals("an area is one fill plus one outline", 1, fills);
            assertEquals("an area is one fill plus one outline", 1, strokes);
        }
    }

    /** The default rendering must contain no curves at all. */
    @Test
    public void testStepRenderingHasNoCurves() throws IOException {
        String svg = render(false, true);
        for (String d : pathData(svg)) {
            assertFalse("step rendering must not emit curves: " + d, d.contains("c"));
            assertFalse("step rendering must not emit curves: " + d, d.contains("q"));
        }
    }

    /** With smoothing on, the plot path must contain curve commands. */
    @Test
    public void testSmoothRenderingEmitsCurves() throws IOException {
        String svg = render(true, true);
        boolean sawCurve = false;
        for (String d : pathData(svg)) {
            if (d.contains("c")) {
                sawCurve = true;
            }
        }
        assertTrue("smooth rendering must emit curves", sawCurve);
    }

    /**
     * A smoothed area must be emitted as a single closed subpath. Two open subpaths would each be
     * implicitly closed by the renderer and their windings could cancel under the default nonzero
     * rule, punching a hole in the fill.
     */
    @Test
    public void testSmoothedAreaIsOneClosedSubpath() throws IOException {
        String svg = render(true, false);
        List<String> data = pathData(svg);
        int closedPaths = 0;
        boolean sawFill = false;
        for (String d : data) {
            if (!d.contains("c")) {
                continue;
            }
            sawFill = true;
            int moves = d.split("M", -1).length - 1;
            assertEquals("a smoothed fill must be one subpath, saw " + moves + " in " + d, 1, moves);
            assertTrue("a smoothed fill must be closed: " + d, d.trim().endsWith("Z"));
            closedPaths++;
        }
        assertTrue("expected a smoothed area fill", sawFill);
        assertTrue("expected the area to be filled", closedPaths > 0);
    }

    /**
     * The smoothed fill must not self-intersect. Its upper curve cannot rise above the baseline
     * because the interpolation stays inside the band of the two samples it joins, and all samples
     * sit above the baseline, so the outline is a simple polygon.
     */
    @Test
    public void testSmoothedAreaStaysAboveBaseline() throws IOException {
        String svg = render(true, false);
        for (String d : pathData(svg)) {
            if (!d.contains("c")) {
                continue;
            }
            // Every coordinate pair in the emitted path, relative deltas resolved cumulatively.
            double x = 0, y = 0;
            double maxY = Double.NEGATIVE_INFINITY;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([mlc])\\s*(-?[\\d.]+) (-?[\\d.]+)").matcher(d);
            while (m.find()) {
                String cmd = m.group(1);
                if ("m".equals(cmd) || "l".equals(cmd)) {
                    x += Double.parseDouble(m.group(2));
                    y += Double.parseDouble(m.group(3));
                } else {
                    // three coordinate pairs: control, control, end
                    for (int k = 0; k < 3; k++) {
                        if (!m.find()) {
                            break;
                        }
                        x += Double.parseDouble(m.group(2));
                        y += Double.parseDouble(m.group(3));
                    }
                }
                maxY = Math.max(maxY, y);
            }
            assertTrue("curve must stay within the graph height, reached " + maxY, maxY <= HEIGHT);
        }
    }

    /**
     * Turning smoothing on must add the antialiasing override, because the global
     * crispEdges rule would otherwise render the curves jagged.
     */
    @Test
    public void testSmoothingAddsAntialiasingOverride() throws IOException {
        assertTrue(render(true, true).contains("path{shape-rendering:geometricPrecision}"));
        assertFalse(render(false, true).contains("shape-rendering:geometricPrecision"));
    }

    /**
     * With smoothing off, the crispEdges rule must still be the only shape-rendering declaration,
     * so the default rendering is byte-for-byte unaffected.
     */
    @Test
    public void testStepRenderingKeepsCrispEdges() throws IOException {
        String svg = render(false, true);
        assertTrue(svg.contains("line,path,rect{shape-rendering:crispEdges"));
    }

    /**
     * Smoothing must reduce the vertex count relative to the raw sample count, because flat runs
     * still collapse to single segments. If it did not, the payload would grow rather than shrink.
     */
    @Test
    public void testSmoothingDoesNotInflateVertexCount() throws IOException {
        int stepSegments = 0;
        int smoothSegments = 0;
        for (String d : pathData(render(false, true))) {
            stepSegments += d.split("l", -1).length - 1;
        }
        for (String d : pathData(render(true, true))) {
            smoothSegments += d.split("c", -1).length - 1;
        }
        assertTrue("smoothed curve should need no more segments than the steps, was "
                + smoothSegments + " vs " + stepSegments, smoothSegments <= stepSegments);
    }

    /**
     * A NaN gap in the data must produce separate paths rather than a curve bridging the gap.
     *
     * @throws IOException if rendering fails
     */
    @Test
    public void testGapsAreNotBridged() throws IOException {
        RrdGraphDef def = new RrdGraphDef(windowStart(), END);
        def.setWidth(WIDTH);
        def.setHeight(HEIGHT);
        def.setSmoothing(true);
        def.setNoLegend(true);
        def.setShowSignature(false);
        def.datasource(DS_NAME, path, DS_NAME, ConsolFun.AVERAGE,
                RrdBackendFactory.getFactory("MEMORY"));
        def.line(DS_NAME, java.awt.Color.RED, "v", 1);
        String svg = new String(new RrdGraph(def, new SVGImageWorker(0, 0, false, true))
                .getRrdGraphInfo().getBytes(), java.nio.charset.StandardCharsets.UTF_8);

        int curvePaths = 0;
        for (String d : pathData(svg)) {
            if (d.contains("c")) {
                curvePaths++;
                assertEquals("each gap must start a fresh subpath", 1, d.split("M", -1).length - 1);
            }
        }
        assertTrue("expected the plot to be drawn", curvePaths > 0);
    }

    /** The rendered document must stay well-formed enough to parse as XML. */
    @Test
    public void testRenderedSvgIsWellFormed() throws IOException {
        for (boolean smooth : new boolean[] {false, true}) {
            for (boolean line : new boolean[] {false, true}) {
                String svg = render(smooth, line);
                try {
                    javax.xml.parsers.DocumentBuilderFactory f =
                            javax.xml.parsers.DocumentBuilderFactory.newInstance();
                    f.setNamespaceAware(true);
                    f.newDocumentBuilder().parse(
                            new java.io.ByteArrayInputStream(svg.getBytes("UTF-8")));
                } catch (Exception e) {
                    throw new AssertionError("smooth=" + smooth + " line=" + line
                            + " produced invalid XML: " + e.getMessage(), e);
                }
            }
        }
    }

    // ---- where the trace starts and ends ----

    /**
     * The step renderer starts a run on the column of the sample before it, because its doubled x
     * array overwrites the trailing NaN while leaving that sample's x in place. The smoothed
     * renderer uses one x per sample and keeps its NaN, so it has to anchor the run back onto the
     * same column. When it did not, turning smoothing on moved the whole trace one sample period to
     * the right - a gap at the start of the graph that grows with the plot's sample spacing.
     *
     * <p>The window here reaches back past the first stored sample, so the leading period is NaN
     * and the run genuinely starts late. That is the shape a live router graph has whenever its
     * window is longer than its recorded history.
     */
    @Test
    public void testSmoothedTraceStartsWhereSteppedTraceStarts() throws IOException {
        long from = start();
        for (boolean line : new boolean[] {true, false}) {
            List<Double> stepped = pathXs(plotPath(render(from, false, line)));
            List<Double> smoothed = pathXs(plotPath(render(from, true, line)));
            assertFalse("the window must have a lead-in NaN period to be a real test",
                    stepped.isEmpty() || smoothed.isEmpty());
            assertEquals("smoothing must not move the start of the trace (line=" + line + ")",
                    stepped.get(0), smoothed.get(0));
        }
    }

    /**
     * The tail is checked on the furthest column the path reaches rather than its last coordinate,
     * because a filled area's outline closes back onto its first corner and so ends where it
     * started.
     *
     * <p>Widening the run's end interval pushed that furthest column past the plot area's right
     * edge, where it was silently clipped away, so a smoothed line could be drawn longer than its
     * stepped twin.
     */
    @Test
    public void testSmoothedTraceEndsWhereSteppedTraceEnds() throws IOException {
        for (long from : new long[] {windowStart(), start()}) {
            for (boolean line : new boolean[] {true, false}) {
                List<Double> stepped = pathXs(plotPath(render(from, false, line)));
                List<Double> smoothed = pathXs(plotPath(render(from, true, line)));
                assertEquals("smoothing must not move the end of the trace (from=" + from
                        + " line=" + line + ")",
                        furthest(stepped), furthest(smoothed));
            }
        }
    }

    /**
     * The trace has to reach the plot area's left edge, so nothing is left blank along it. Plots
     * are clipped to {@link #clipLeft}, so a trace starting right of that coordinate has been
     * inset into the graph; one starting at or left of it is drawn all the way out to the edge.
     *
     * <p>Both windows are checked: one covered by data throughout, and one reaching back past the
     * first stored sample. The second is the case that regressed, because the trace was then
     * anchored a whole sample period inside the plot.
     */
    @Test
    public void testSmoothedTraceCoversTheLeftPlotEdge() throws IOException {
        for (long from : new long[] {windowStart(), start()}) {
            for (boolean line : new boolean[] {true, false}) {
                String svg = render(from, true, line);
                List<Double> xs = pathXs(plotPath(svg));
                assertFalse("the plot must be drawn", xs.isEmpty());
                assertTrue("the smoothed trace must reach the plot area's left edge (from=" + from
                        + " line=" + line + "): it starts at " + xs.get(0) + " but the plot area "
                        + "starts at " + clipLeft(svg), xs.get(0) <= clipLeft(svg));
            }
        }
    }

    /**
     * @param xs x coordinates visited by a path
     * @return the largest of them, boxed so it compares as an object against a parsed coordinate
     */
    private static Double furthest(List<Double> xs) {
        double max = Double.NEGATIVE_INFINITY;
        for (double x : xs) {
            max = Math.max(max, x);
        }
        return max;
    }
}
