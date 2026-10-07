package org.rrd4j.graph;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
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

/**
 * Tests that a plot's stroke width reaches the rendered SVG.
 *
 * <p>A theme states how heavy its plots are, and that only matters if the number survives into
 * the markup. It does not when the plot is an {@code Area} alone: an area carries no stroke of
 * its own, so nothing emits a width and the series is drawn at the SVG default of 1 - the theme
 * silently has no say. A caller that wants the themed weight has to declare a line as well,
 * which is why {@code GraphRenderer} emits one alongside the area when path fill is off.
 */
public class PlotStrokeWidthTest {

    private static final String DS_NAME = "series";
    private static final String PATH = "plot-stroke-width-test";
    private static final long STEP = 300;
    private static final int ROWS = 40;
    private static final long END = 1700000000000L;

    private RrdDb db;

    @Before
    public void setUp() throws IOException {
        RrdDef def = new RrdDef(PATH);
        def.setStartTime(END - (long) ROWS * STEP);
        def.setStep(STEP);
        def.addDatasource(new DsDef(DS_NAME, DsType.GAUGE, STEP * 10, 0, Double.MAX_VALUE));
        def.addArchive(new ArcDef(ConsolFun.AVERAGE, 0.5, 1, ROWS));
        db = RrdDb.getBuilder().setRrdDef(def)
                .setBackendFactory(RrdBackendFactory.getFactory("MEMORY")).build();
        long t = END - (long) ROWS * STEP;
        for (int i = 0; i < ROWS; i++) {
            t += STEP;
            Sample s = db.createSample();
            s.setTime(t);
            s.setValue(DS_NAME, 1000 + i * 10);
            s.update();
        }
    }

    @After
    public void tearDown() throws IOException {
        if (db != null && !db.isClosed()) {
            db.close();
        }
    }

    /** A base definition bound to the in-memory series, without any plot elements. */
    private RrdGraphDef baseDef() {
        RrdGraphDef def = new RrdGraphDef(END - (long) ROWS * STEP, END);
        def.setWidth(400);
        def.setHeight(200);
        def.setNoLegend(true);
        def.setShowSignature(false);
        def.setAntiAliasing(false);
        def.setTextAntiAliasing(false);
        def.datasource(DS_NAME, PATH, DS_NAME, ConsolFun.AVERAGE,
                RrdBackendFactory.getFactory("MEMORY"));
        return def;
    }

    /** Renders a definition and returns the SVG. */
    private String render(RrdGraphDef def) throws IOException {
        return new String(new RrdGraph(def, new SVGImageWorker(0, 0, false, false, "dark"))
                .getRrdGraphInfo().getBytes(), StandardCharsets.UTF_8);
    }

    /** A declared line carries the width the definition asked for. */
    @Test
    public void aDeclaredLineCarriesItsStrokeWidth() throws IOException {
        RrdGraphDef def = baseDef();
        def.line(DS_NAME, Color.RED, "", 3.5f, null);

        String svg = render(def);
        assertTrue("the plot path declares no stroke width at all", svg.contains("stroke-width:3.5"));
    }

    /**
     * An area on its own states no width, which is why a themed width needs a line beside it.
     *
     * <p>Pins the reason a theme's width was ignored: nothing in the definition carries one, so
     * the series falls back to the SVG default. Not a defect in itself - an area is not a line -
     * but it is the whole explanation.
     */
    @Test
    public void anAreaAloneStatesNoStrokeWidth() throws IOException {
        RrdGraphDef def = baseDef();
        def.area(DS_NAME, Color.BLUE);

        // Gridlines and rules carry stroke-width of their own, so the claim is about the plot:
        // adding an explicit line must introduce a width the area-only graph did not have.
        String areaOnly = render(def);
        RrdGraphDef withLine = baseDef();
        withLine.area(DS_NAME, Color.BLUE);
        withLine.line(DS_NAME, Color.RED, "\\l", 2f, null);

        assertFalse("the plot already stated a width without a line",
                    areaOnly.contains("stroke-width:2"));
        assertTrue("adding a line did not introduce its width",
                   render(withLine).contains("stroke-width:2"));
    }

    /** With a line added beside the area, the themed width is emitted. */
    @Test
    public void anAreaWithALineStatesTheWidth() throws IOException {
        RrdGraphDef def = baseDef();
        def.area(DS_NAME, Color.BLUE);
        def.line(DS_NAME, Color.RED, "\\l", 2f, null);

        String svg = render(def);
        assertTrue("the plot declares no stroke width beside its area",
                   svg.contains("stroke-width:2"));
    }

    /** The width is the one asked for, not a rounded or rewritten form of it. */
    @Test
    public void anUnusualWidthIsEmittedVerbatim() throws IOException {
        for (float width : new float[] { 1.5f, 2f, 2.5f, 4f }) {
            RrdGraphDef def = baseDef();
            def.line(DS_NAME, Color.RED, "", width, null);

            assertTrue("width " + width + " did not survive into the markup",
                       declaresStrokeWidth(render(def), width));
        }
    }

    /**
     * Whether the document declares this width as a whole declaration.
     *
     * <p>A whole-number width is written {@code stroke-width:2.0;}, so both that and
     * {@code stroke-width:2;} count. Matching has to be anchored on the semicolon the writer
     * always emits: a bare search for {@code stroke-width:2} matches {@code 2.5} as a prefix,
     * which let an earlier version of this loop pass without the width being written at all.
     *
     * @param svg the rendered document
     * @param width the width to look for
     * @return true when the width is declared and terminated
     */
    private static boolean declaresStrokeWidth(String svg, float width) {
        String value = width == (long) width ? String.valueOf((long) width) : String.valueOf(width);
        return Pattern.compile("stroke-width:" + Pattern.quote(value) + "(?:\\.0)?;")
                .matcher(svg).find();
    }
}
