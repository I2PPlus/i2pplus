package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Paint;

/**
 * Represents a horizontal rule in RRD graphs. Draws a horizontal line at a specific value across
 * the graph with optional legend.
 */
class HRule extends Rule {
    /** The value at which to draw the horizontal rule */
    final double value;

    /**
     * Creates a horizontal rule at the specified value.
     *
     * @param value the y-axis value for the rule
     * @param color color for the rule line
     * @param legend optional legend text
     * @param stroke stroke style for the line
     */
    HRule(double value, Paint color, LegendText legend, BasicStroke stroke) {
        super(color, legend, stroke);
        this.value = value;
    }

    /**
     * Sets legend visibility based on value range and force setting.
     *
     * @param minval minimum value range
     * @param maxval maximum value range
     * @param forceLegend if true, legend is always visible
     */
    void setLegendVisibility(double minval, double maxval, boolean forceLegend) {
        legend.enabled &= (forceLegend || (value >= minval && value <= maxval));
    }
}
