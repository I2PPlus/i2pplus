package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Paint;

/**
 * Represents a vertical rule in RRD graphs. Draws a vertical line at a specific timestamp across
 * the graph with optional legend.
 */
class VRule extends Rule {
    /** The timestamp at which to draw the vertical rule */
    final long timestamp;

    /**
     * Creates a vertical rule at the specified timestamp.
     *
     * @param timestamp timestamp in seconds for the rule
     * @param color color for the rule line
     * @param legend optional legend text
     * @param stroke stroke style for the line
     */
    VRule(long timestamp, Paint color, LegendText legend, BasicStroke stroke) {
        super(color, legend, stroke);
        this.timestamp = timestamp;
    }

    /**
     * Sets legend visibility based on timestamp range and force setting.
     *
     * @param minval minimum timestamp range
     * @param maxval maximum timestamp range
     * @param forceLegend if true, legend is always visible
     */
    void setLegendVisibility(long minval, long maxval, boolean forceLegend) {
        legend.enabled &= (forceLegend || (timestamp >= minval && timestamp <= maxval));
    }
}
