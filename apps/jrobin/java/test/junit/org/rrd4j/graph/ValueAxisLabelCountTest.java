package org.rrd4j.graph;

import static org.junit.Assert.assertEquals;
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

/**
 * A short graph must not come back with a single number on its value axis.
 *
 * <p>The label factor is chosen so labels cannot overlap, and on a graph a few tens of
 * pixels tall that pushes it to the largest factor the axis definition offers. Labels sit on
 * multiples of the grid step counted from zero, and the console raises the axis floor whenever
 * a series lives in a band away from zero, so the low multiples fall outside the window: at
 * 296x34 the axis used to print exactly one label, {@code 500K}, and a reader could not tell
 * the scale of the graph at all.
 *
 * <p>The console only turns the MRTG axis on at 400x200 and above, so every size here is on
 * the automatic-selection side of that line.
 *
 * @since 0.9.72+
 */
public class ValueAxisLabelCountTest {

    private static final String DS_NAME = "value";
    private static final int STEP = 60;
    private static final int ROWS = 120;
    private static final long END = 1_700_000_000L;

    /** The factors an axis definition offers, ascending - the shape {@code YLabel} uses. */
    private static final int[] FACTS = {1, 2, 5, 10};

    /**
     * The case from the console: a 100K-600K bandwidth window 34 pixels tall, the spacing
     * rule having settled on a factor of 5, leaves only the 500K position inside the window.
     * The largest smaller factor that reaches two labels, without printing them on top of
     * each other, is 2.
     */
    @Test
    public void aSingleLabelAxisGetsAFactorThatReachesTwoLabels() {
        int fact = ValueAxis.keepTwoLabels(5, FACTS, 100_000, 600_000, 100_000, 6, 12);
        assertEquals("two labels at 12 pixels apart is readable; one label is not a scale",
                     2, fact);
        assertTrue("the fix must not leave the axis with one label",
                   ValueAxis.countLabels(100_000, 600_000, 100_000, fact) >= 2);
    }

    /** A factor that already puts two or more labels in the window is left alone. */
    @Test
    public void aFactorThatAlreadyReachesTwoLabelsIsLeftAlone() {
        assertEquals(2, ValueAxis.keepTwoLabels(2, FACTS, 100_000, 600_000, 50_000, 12, 12));
        assertEquals(1, ValueAxis.keepTwoLabels(1, FACTS, 0, 500_000, 100_000, 40, 12));
    }

    /**
     * A smaller factor that would print labels on top of each other is refused: the single
     * label it replaces is the lesser defect, and the spacing rule that produced it still
     * stands.
     */
    @Test
    public void aFactorThatWouldOverlapLabelsIsRefused() {
        // one pixel per gridline: no factor clears one line of 12-pixel text
        assertEquals(5, ValueAxis.keepTwoLabels(5, FACTS, 100_000, 600_000, 100_000, 1, 12));
        // two pixels per gridline: factor 2 reaches two labels but lands 4 pixels apart
        assertEquals(5, ValueAxis.keepTwoLabels(5, FACTS, 100_000, 600_000, 100_000, 2, 12));
    }

    /**
     * A window holding fewer than two grid positions at any factor cannot be fixed, and the
     * chosen factor is returned rather than a factor that still draws one label.
     */
    @Test
    public void aWindowTooNarrowForTwoLabelsIsLeftAlone() {
        // only the 300K position lies inside 250K-350K, at any factor
        assertEquals(1, ValueAxis.keepTwoLabels(1, FACTS, 250_000, 350_000, 100_000, 40, 12));
    }

    /** The count is exact, including a window that straddles zero, where position 0 counts once. */
    @Test
    public void countLabelsCountsThePositionsInsideTheWindow() {
        assertEquals(1, ValueAxis.countLabels(100_000, 600_000, 100_000, 5));
        assertEquals(3, ValueAxis.countLabels(100_000, 600_000, 100_000, 2));
        assertEquals(6, ValueAxis.countLabels(100_000, 600_000, 100_000, 1));
        assertEquals(6, ValueAxis.countLabels(0, 500_000, 100_000, 1));
        assertEquals(5, ValueAxis.countLabels(-100, 100, 50, 1));
        assertEquals(1, ValueAxis.countLabels(-100, 100, 50, 10));
    }

    /** A degenerate window or step counts nothing rather than looping or dividing by zero. */
    @Test
    public void countLabelsHandlesADegenerateWindow() {
        assertEquals(0, ValueAxis.countLabels(0, 0, 0, 1));
        assertEquals(0, ValueAxis.countLabels(100, 200, -1, 1));
        assertEquals(0, ValueAxis.countLabels(100, 200, 50, 0));
        assertEquals(0, ValueAxis.countLabels(300, 200, 50, 1));
        assertEquals(0, ValueAxis.countLabels(Double.NaN, 200, 50, 1));
    }

    /**
     * The regression, end to end: at the sizes the console draws a 296-wide mini-graph, the
     * value axis must carry more than one label. Before the fix, 34 and 40 pixels tall each
     * carried a single {@code 500K}.
     *
     * @throws IOException if the in-memory RRD cannot be rendered to SVG
     */
    @Test
    public void aShortGraphCarriesMoreThanOneValueLabel() throws IOException {
        for (int h : new int[] {34, 40, 50}) {
            List<String> labels = valueLabels(296, h);
            assertTrue("a 296x" + h + " graph must show a scale, not one number: " + labels,
                       labels.size() >= 2);
        }
    }

    /**
     * The fix must not crowd a graph that was already readable: the same series at 120 pixels
     * keeps one label per grid step.
     *
     * @throws IOException if the in-memory RRD cannot be rendered to SVG
     */
    @Test
    public void aTallGraphKeepsItsLabels() throws IOException {
        List<String> labels = valueLabels(296, 120);
        assertTrue("a tall graph must keep every label it had: " + labels, labels.size() >= 5);
    }

    /**
     * Render a graph of the given size over a series in the 120K-510K band and return the
     * text drawn to the left of the plot - the value axis labels.
     *
     * @param w graph width in pixels
     * @param h graph height in pixels
     * @return the value-axis labels, top to bottom
     * @throws IOException if the in-memory RRD cannot be rendered to SVG
     */
    private static List<String> valueLabels(int w, int h) throws IOException {
        String path = "value-axis-label-count-" + w + "x" + h;
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
                // a bandwidth-like band well away from zero, which is what makes the console
                // raise the axis floor and strand the low grid positions below the plot
                sample.setValue(DS_NAME, 120_000 + (i % 40) * 10_000);
                sample.update();
            }
            RrdGraphDef gdef = new RrdGraphDef(END - (long) ROWS * STEP, END);
            gdef.setWidth(w);
            gdef.setHeight(h);
            gdef.setNoLegend(true);
            gdef.setShowSignature(false);
            gdef.setAntiAliasing(false);
            gdef.setTextAntiAliasing(false);
            // the console's rule: the MRTG axis only at 400x200 and above
            gdef.setAltYMrtg(w >= 400 && h >= 200);
            gdef.datasource(DS_NAME, path, DS_NAME, ConsolFun.AVERAGE,
                            RrdBackendFactory.getFactory("MEMORY"));
            gdef.line(DS_NAME, Color.RED, "v", 1);
            byte[] svg = new RrdGraph(gdef, new SVGImageWorker(0, 0, false, false, "dark"))
                    .getRrdGraphInfo().getBytes();
            return valueLabels(new String(svg, StandardCharsets.UTF_8));
        } finally {
            db.close();
        }
    }

    /**
     * Pick out the value-axis labels from a rendered document. They are the texts made of
     * digits and a magnitude suffix; the time axis labels carry a colon and the legend and
     * title carry words.
     *
     * @param svg the rendered SVG text to scan
     * @return the value-axis label texts, in document order
     */
    private static List<String> valueLabels(String svg) {
        List<String> out = new ArrayList<>();
        int at = 0;
        while ((at = svg.indexOf("<text", at)) >= 0) {
            int gt = svg.indexOf('>', at);
            int end = svg.indexOf("</text>", gt);
            if (end < 0) {
                break;
            }
            String body = svg.substring(gt + 1, end).replaceAll("<[^>]*>", "").trim();
            if (body.matches("\\d+(\\.\\d+)?[KMG]?")) {
                out.add(body);
            }
            at = end;
        }
        return out;
    }
}
