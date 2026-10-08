package org.rrd4j.graph;

import java.awt.Color;
import java.awt.Paint;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the metadata a graph hands to a client that wants a cursor readout.
 *
 * <p>A graph is served as an {@code <img>}, which makes the SVG an isolated document: script
 * does not run inside it and the page cannot read its DOM. The plot geometry and the series
 * therefore have to travel separately, and the units have to be the ones the axis uses or the
 * readout disagrees with the labels beside it.
 *
 * @since 0.9.71+
 */
public class RrdGraphMetaTest {

    /** Image parameters as a completed render would leave them. */
    private static ImageParameters params(double magfact, double minval, double maxval) {
        ImageParameters im = new ImageParameters();
        im.xorigin = 63;
        im.yorigin = 311;
        im.xsize = 1000;
        im.ysize = 280;
        im.start = 1000000L;
        im.end = 1000600L;
        im.magfact = magfact;
        im.minval = minval;
        im.maxval = maxval;
        return im;
    }

    /**
     * A sourced plot element carrying the given values, bypassing the data processor.
     *
     * <p>Each call gets its own source name. A shared one would be folded away by the
     * deduplication in {@link RrdGraphMeta#populate}, so asking for "two series" this way would
     * quietly produce one.
     */
    private static SourcedPlotElement series(double... values) {
        return series("src" + (distinctSource++), Color.ORANGE, values);
    }

    /** Source names for the no-argument-name fixture, so each is a series of its own. */
    private static int distinctSource;

    /**
     * A sourced plot element with an explicit name and paint, so a test can model two elements
     * over one source the way filled-path mode does.
     */
    private static SourcedPlotElement series(String srcName, Paint color, double... values) {
        SourcedPlotElement pe = new SourcedPlotElement(srcName, color);
        pe.values = values;
        return pe;
    }

    private static RrdGraphMeta metaOf(ImageParameters im, SourcedPlotElement... elements) {
        List<PlotElement> pes = new ArrayList<>();
        for (SourcedPlotElement pe : elements) {pes.add(pe);}
        RrdGraphMeta meta = new RrdGraphMeta();
        meta.populate(im, pes);
        return meta;
    }

    /**
     * The plot rectangle must survive verbatim: it is what maps cursor pixels.
     *
     * <p>It is emitted as a conventional (left, top, width, height). ImageParameters carries
     * the *bottom* edge as yOrigin because SVG y grows downward and the value axis runs upward
     * from it, so emitting yOrigin directly would tell a client to look for the plot below the
     * image. Every cursor position would then fall outside it and no readout would ever appear.
     */
    @Test
    public void thePlotRectIsCarriedThrough() {
        RrdGraphMeta meta = metaOf(params(1D, 0, 100), series(1, 2, 3));
        // yOrigin 311 minus ySize 280 puts the top at 31, matching a rendered clip-path.
        assertTrue(meta.toJson(), meta.toJson().contains("\"plot\":[63,31,1000,280]"));
    }

    /**
     * The regression, stated as arithmetic rather than as a rendering: the emitted top must be
     * the top, so a cursor inside the rendered image can land inside the plot.
     */
    @Test
    public void thePlotTopIsAboveTheImageOriginNotBelowIt() {
        RrdGraphMeta meta = metaOf(params(1D, 0, 100), series(1, 2, 3));
        String json = meta.toJson();
        int open = json.indexOf("[", json.indexOf("\"plot\""));
        int comma = json.indexOf(",", open);
        int top = Integer.parseInt(json.substring(open + 1, comma));
        assertTrue("the emitted top must be the smaller edge, not the baseline",
                   top < 311);
    }

    /** The time window must survive verbatim, in epoch seconds. */
    @Test
    public void theTimeWindowIsCarriedThrough() {
        RrdGraphMeta meta = metaOf(params(1D, 0, 100), series(1, 2, 3));
        String json = meta.toJson();
        assertTrue(json, json.contains("\"x0\":1000000"));
        assertTrue(json, json.contains("\"x1\":1000600"));
    }

    /**
     * The axis prints {@code axisval / magfact}, so both the bounds and the series are
     * divided by the same factor. If only one were, a client inverting linearly would place
     * the readout off the line it is describing.
     */
    @Test
    public void theBoundsAndTheSeriesShareTheDisplayUnit() {
        // magfact 1000, axis bounds 0..200000 -> displayed 0..200.
        RrdGraphMeta meta = metaOf(params(1000D, 0, 200000), series(0, 100000, 200000));
        String json = meta.toJson();
        assertTrue(json, json.contains("\"y0\":0.0"));
        assertTrue(json, json.contains("\"y1\":200.0"));
        assertTrue(json, json.contains("series\":[[0.0,100.0,200.0]]"));
    }

    /** A magfact of zero or NaN would divide every value away; fall back to unscaled. */
    @Test
    public void aMissingMagnitudeIsTreatedAsOne() {
        RrdGraphMeta meta = metaOf(params(0D, 0, 100), series(5, 6));
        assertTrue(meta.toJson(), meta.toJson().contains("series\":[[5.0,6.0]]"));
    }

    /**
     * A gap in a series is a null sample, not a value. Emitting it as NaN would produce a
     * document that is not valid JSON and a client that reports a number the series never took.
     */
    @Test
    public void aGapBecomesNullRatherThanNaN() {
        RrdGraphMeta meta = metaOf(params(1D, 0, 100), series(1, Double.NaN, 3));
        assertTrue(meta.toJson(), meta.toJson().contains("series\":[[1.0,null,3.0]]"));
    }

    /** Several series come back in plot order, so a client can label each. */
    @Test
    public void everySeriesIsCarriedInOrder() {
        RrdGraphMeta meta = metaOf(params(1D, 0, 100), series(1, 2), series(3, 4));
        assertTrue(meta.toJson(), meta.toJson().contains("series\":[[1.0,2.0],[3.0,4.0]]"));
    }

    /** Non-sourced plot elements (horizontal rules, vertical rules) carry no series. */
    @Test
    public void rulesAndOtherUnsourcedElementsAreSkipped() {
        List<PlotElement> pes = new ArrayList<>();
        pes.add(new HRule(1, null, null, null));
        pes.add(series(1, 2));
        RrdGraphMeta meta = new RrdGraphMeta();
        meta.populate(params(1D, 0, 100), pes);
        assertTrue(meta.toJson(), meta.toJson().contains("series\":[[1.0,2.0]]"));
    }

    /**
     * A flat or empty graph has a zero-height range; dividing by it would produce infinities,
     * so it must report itself unusable rather than hand out a domain.
     */
    @Test
    public void aDegenerateDomainIsNotInvertible() {
        assertFalse("flat value range", metaOf(params(1D, 5, 5), series(5, 5)).isInvertible());
        ImageParameters noSpan = params(1D, 0, 100);
        noSpan.start = noSpan.end;
        assertFalse("empty time span", metaOf(noSpan, series(1, 2)).isInvertible());
        ImageParameters noWidth = params(1D, 0, 100);
        noWidth.xsize = 0;
        assertFalse("zero-width plot", metaOf(noWidth, series(1, 2)).isInvertible());
        RrdGraphMeta noSeries = new RrdGraphMeta();
        noSeries.populate(params(1D, 0, 100), new ArrayList<PlotElement>());
        assertFalse("no series", noSeries.isInvertible());
    }

    /** A usable graph must say so, or every client skips it. */
    @Test
    public void aUsableDomainIsInvertible() {
        assertTrue(metaOf(params(1D, 0, 100), series(1, 2, 3)).isInvertible());
    }
    /**
     * The swatch colour is the line's own colour, taken from the element that drew it, so it
     * cannot drift from the graph's legend when a theme changes.
     */
    @Test
    public void theSeriesColourIsEmittedAsCssHex() {
        RrdGraphMeta meta = metaOf(params(1D, 0, 100),
                                   series("a", new Color(0x0c, 0xc0, 0xc0), 1, 2),
                                   series("b", new Color(255, 68, 0), 3, 4));
        String json = meta.toJson();
        assertTrue(json, json.contains("\"colors\":[\"#0cc0c0\",\"#ff4400\"]"));
    }

    /** A translucent line keeps its alpha, so a swatch over the graph is not opaque by accident. */
    @Test
    public void aTranslucentColourKeepsItsAlpha() {
        RrdGraphMeta meta = metaOf(params(1D, 0, 100), series("a", new Color(0, 0, 0, 0x80), 1));
        assertTrue(meta.toJson(), meta.toJson().contains("\"#00000080\""));
    }

    /**
     * A gradient fill has no single colour to put in a square, so it yields null and the client
     * falls back to the stylesheet's own colour rather than inventing one.
     */
    @Test
    public void aGradientPaintYieldsNoColour() {
        Paint gradient = new java.awt.GradientPaint(0, 0, Color.RED, 0, 10, Color.BLUE);
        RrdGraphMeta meta = metaOf(params(1D, 0, 100), series("a", gradient, 1, 2));
        assertTrue(meta.toJson(), meta.toJson().contains("\"colors\":[null]"));
    }

    /**
     * Filled-path mode plots an area and then a line over one source, so iterating the elements
     * verbatim would report that source twice and a readout would print the number twice. The
     * line wins: it is added last, is drawn on top, and is the one carrying the legend.
     */
    @Test
    public void oneSourceIsReportedOnceEvenWhenDrawnTwice() {
        RrdGraphMeta meta = metaOf(params(1D, 0, 100),
                                   series("a", new java.awt.GradientPaint(0, 0, Color.RED, 0, 9, Color.BLUE), 1, 2),
                                   series("a", new Color(0, 0, 0xfc), 1, 2),
                                   series("b", new Color(0x0c, 0xc0, 0xc0), 3, 4));
        String json = meta.toJson();
        assertTrue(json, json.contains("series\":[[1.0,2.0],[3.0,4.0]]"));
        assertFalse("the area must not add a second copy of the source", json.contains("[1.0,2.0],[1.0,2.0]"));
        assertTrue("and the line's colour is the one kept", json.contains("\"#0000fc\""));
    }

    /**
     * Order follows first appearance, so the series stay in legend order; the values and colour
     * come from the last element seen for that source, which is the line drawn over the fill.
     */
    @Test
    public void deduplicationKeepsFirstSeenOrder() {
        RrdGraphMeta meta = metaOf(params(1D, 0, 100),
                                   series("a", Color.RED, 1),
                                   series("b", Color.GREEN, 2),
                                   series("a", Color.BLUE, 9));
        assertTrue(meta.toJson(), meta.toJson().contains("series\":[[9.0],[2.0]]"));
        assertTrue(meta.toJson(), meta.toJson().contains("\"#0000ff\",\"#00ff00\""));
    }
}
