package org.rrd4j.graph;

import java.awt.Paint;

/**
 * Represents a vertical span in RRD graphs. Draws a colored rectangular region spanning a range of
 * timestamps on x-axis.
 */
class VSpan extends Span {
    /** Start */
    final long start;
    /** End */
    final long end;

    /** Constructor */
    VSpan(long start, long end, Paint color, LegendText legend) {
        super(color, legend);
        this.start = start;
        this.end = end;
        assert (start < end);
    }
    /** Check range */
    private boolean checkRange(long v, long min, long max) {
        return v >= min && v <= max;
    }

    /** Legend visibility */
    void setLegendVisibility(long min, long max, boolean forceLegend) {
        legend.enabled =
                legend.enabled
                        && (forceLegend
                                || checkRange(start, min, max)
                                || checkRange(end, min, max));
    }
}
