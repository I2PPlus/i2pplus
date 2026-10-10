package org.rrd4j.graph;

import java.awt.Paint;

/**
 * Represents a horizontal span in RRD graphs. Draws a colored rectangular region spanning a range
 * of values on the y-axis.
 */
class HSpan extends Span {
    /** Value at which the span begins. */
    final double start;
    /** Value at which the span ends. */
    final double end;

    /**
     * Create a span covering the given value range.
     *
     * @param start value at which the span begins
     * @param end value at which the span ends, which must be greater than start
     * @param color the fill color of the span
     * @param legend the legend entry describing the span
     */
    HSpan(double start, double end, Paint color, LegendText legend) {
        super(color, legend);
        this.start = start;
        this.end = end;
        assert (start < end);
    }
    private boolean checkRange(double v, double min, double max) {
        return v >= min && v <= max;
    }

    /**
     * Control legend visibility based on current axis range.
     *
     * @param min the lower bound of the visible y-axis range
     * @param max the upper bound of the visible y-axis range
     * @param forceLegend when true, keep the legend shown whatever the span's overlap
     */
    void setLegendVisibility(double min, double max, boolean forceLegend) {
        legend.enabled =
                legend.enabled
                        && (forceLegend
                                || checkRange(start, min, max)
                                || checkRange(end, min, max));
    }
}
