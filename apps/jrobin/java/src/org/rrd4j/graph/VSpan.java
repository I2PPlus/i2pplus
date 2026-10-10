package org.rrd4j.graph;

import java.awt.Paint;

/**
 * Represents a vertical span in RRD graphs. Draws a colored rectangular region spanning a range of
 * timestamps on x-axis.
 */
class VSpan extends Span {
    /** Timestamp at which the span begins. */
    final long start;
    /** Timestamp at which the span ends. */
    final long end;

    /**
     * Create a span covering the given timestamp range.
     *
     * @param start timestamp at which the span begins
     * @param end timestamp at which the span ends, which must be greater than start
     * @param color the fill color of the span
     * @param legend the legend entry describing the span
     */
    VSpan(long start, long end, Paint color, LegendText legend) {
        super(color, legend);
        this.start = start;
        this.end = end;
        assert (start < end);
    }
    /**
     * True if v is between min and max inclusive.
     * @return true if v is between min and max inclusive
     */
    private boolean checkRange(long v, long min, long max) {
        return v >= min && v <= max;
    }

    /**
     * Hide the legend unless the span lies within the visible time range.
     *
     * @param min start of the visible time range
     * @param max end of the visible time range
     * @param forceLegend true to keep the legend regardless of overlap
     */
    void setLegendVisibility(long min, long max, boolean forceLegend) {
        legend.enabled =
                legend.enabled
                        && (forceLegend
                                || checkRange(start, min, max)
                                || checkRange(end, min, max));
    }
}
