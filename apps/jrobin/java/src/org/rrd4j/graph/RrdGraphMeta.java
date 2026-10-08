package org.rrd4j.graph;

import java.awt.Color;
import java.awt.Paint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plot geometry and series behind a rendered graph, in a form a script can
 * invert: given a cursor position in image pixels it yields the time and the
 * value at that point.
 *
 * <p>A graph is served as an {@code <img>}, which makes the SVG an isolated
 * document - script does not run inside it and the parent page cannot read its
 * DOM. The numbers a tooltip needs therefore have to travel separately from the
 * picture, which is what this class is for.
 *
 * <h2>Units</h2>
 * Time is epoch seconds, matching {@link ImageParameters#start} and
 * {@link ImageParameters#end}. Values are in the axis' own display unit, the
 * same one {@link ValueAxis} prints before appending a K/M/G suffix: both the
 * axis bounds and every series value are divided by
 * {@link ImageParameters#magfact}, so the pair stays consistent whichever branch
 * set the bounds and a client can invert linearly without knowing the factor.
 *
 * @since 0.9.71+
 */
public final class RrdGraphMeta {

    private int xOrigin;
    private int yOrigin;
    private int xSize;
    private int ySize;
    private long start;
    private long end;
    private double yMin;
    private double yMax;
    private List<double[]> series = Collections.emptyList();

    /**
     * The CSS colour per series, parallel to {@link #series}, or null where the plot element
     * was painted with something that has no single colour.
     */
    private List<String> colors = Collections.emptyList();

    /** Package-private: instances are created by the renderer. */
    RrdGraphMeta() {}

    /**
     * Populates this metadata from a finished render.
     *
     * @param im the render's image parameters, holding the plot rect and axis bounds
     * @param plotElements the definition's plot elements, holding the series values
     */
    void populate(ImageParameters im, List<PlotElement> plotElements) {
        xOrigin = im.xorigin;
        // ImageParameters.yOrigin is the plot's *bottom* edge: SVG y grows downward and the
        // value axis runs upward from there. A client inverting a cursor position wants a
        // conventional (left, top, width, height), so the top is derived here rather than left
        // for every consumer to get backwards. Verified against a rendered clip-path, which
        // spans y = yOrigin - ySize to yOrigin.
        yOrigin = im.yorigin - im.ysize;
        xSize = im.xsize;
        ySize = im.ysize;
        start = im.start;
        end = im.end;
        double magfact = im.magfact == 0 || Double.isNaN(im.magfact) ? 1D : im.magfact;
        yMin = im.minval / magfact;
        yMax = im.maxval / magfact;
        // One entry per source, not per plot element. Filled-path mode plots an area and then a
        // line over the same source, so iterating the elements verbatim reports that source's
        // values twice and a readout would print the same number twice. The line is added after
        // the area and is the one carrying the legend, so the last element for a source is the
        // one whose colour and values a reader sees. LinkedHashMap keeps first-seen order while
        // taking the last value, which leaves the series in legend order.
        Map<String, PlotElement> bySource = new LinkedHashMap<>();
        if (plotElements != null) {
            for (PlotElement pe : plotElements) {
                if (pe instanceof SourcedPlotElement) {
                    bySource.put(((SourcedPlotElement) pe).srcName, pe);
                }
            }
        }
        List<double[]> collected = new ArrayList<>();
        List<String> collectedColors = new ArrayList<>();
        for (PlotElement pe : bySource.values()) {
            double[] raw = ((SourcedPlotElement) pe).getValues();
            if (raw == null) { continue; }
            double[] scaled = new double[raw.length];
            for (int i = 0; i < raw.length; i++) { scaled[i] = raw[i] / magfact; }
            collected.add(scaled);
            collectedColors.add(hexOf(pe.color));
        }
        series = collected;
        colors = collectedColors;
    }

    /**
     * The CSS colour of a plot element, for a readout swatch to match the graph's own legend.
     *
     * <p>Taken from the element that drew the line, so the swatch cannot drift from the legend
     * when a theme changes. A fill may be a gradient or a texture rather than a flat colour, and
     * those have no single swatch equivalent, so they yield null and the caller falls back to
     * the stylesheet's own colour.
     *
     * @param paint the element's paint, may be null
     * @return a {@code #rrggbb} string, with alpha as {@code #rrggbbaa} when translucent, or null
     * @since 0.9.71+
     */
    private static String hexOf(Paint paint) {
        if (!(paint instanceof Color)) { return null; }
        Color c = (Color) paint;
        String rgb = String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        return c.getAlpha() == 0xFF ? rgb : rgb + String.format("%02x", c.getAlpha());
    }

    /**
     * Whether there is enough here to invert a cursor position: a plot area, a
     * non-degenerate time span, a non-degenerate value range and at least one
     * series. A flat or empty graph produces a zero-height range, and dividing
     * by it would yield infinities.
     *
     * @return true when the metadata is usable
     */
    public boolean isInvertible() {
        return xSize > 0 && ySize > 0 && end > start && yMax > yMin && !series.isEmpty();
    }

    /**
     * Renders the metadata as compact JSON for a client-side overlay.
     *
     * <p>Values are rounded to a fixed number of decimals: the client only ever
     * displays them, and full double precision would triple the payload for no
     * visible gain. Non-finite samples become null so the payload stays valid
     * JSON - a NaN in a series is a gap, not a value.
     *
     * @return the JSON document, never null
     */
    public String toJson() {
        StringBuilder b = new StringBuilder(512 + series.size() * 256);
        b.append("{\"plot\":[")
                .append(xOrigin).append(',').append(yOrigin).append(',').append(xSize).append(',')
                .append(ySize).append("],\"x0\":").append(start).append(",\"x1\":").append(end)
                .append(",\"y0\":").append(round(yMin)).append(",\"y1\":").append(round(yMax))
                .append(",\"colors\":[");
        for (int s = 0; s < colors.size(); s++) {
            if (s > 0) { b.append(','); }
            String hex = colors.get(s);
            if (hex == null) { b.append("null"); } else { b.append('"').append(hex).append('"'); }
        }
        b.append("],\"series\":[");
        for (int s = 0; s < series.size(); s++) {
            if (s > 0) { b.append(','); }
            double[] values = series.get(s);
            b.append('[');
            for (int i = 0; i < values.length; i++) {
                if (i > 0) { b.append(','); }
                double v = values[i];
                if (Double.isNaN(v) || Double.isInfinite(v)) { b.append("null"); }
                else { b.append(round(v)); }
            }
            b.append(']');
        }
        b.append("]}");
        return b.toString();
    }

    /** Rounds for transport: three decimals is finer than any axis label. */
    private static String round(double v) {
        return String.valueOf(Math.round(v * 1000D) / 1000D);
    }
}
