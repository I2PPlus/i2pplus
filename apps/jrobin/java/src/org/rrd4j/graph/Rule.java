package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Paint;

/**
 * Abstract base class for rule elements in RRD graphs. Represents horizontal or vertical rules with
 * optional legends and styling.
 */
class Rule extends PlotElement {
    /** Legend text for this rule */
    final LegendText legend;

    /** Stroke style for drawing the rule line */
    final BasicStroke stroke;

    /**
     * Creates a new rule with specified color, legend, and stroke.
     *
     * @param color color for the rule line
     * @param legend optional legend text
     * @param stroke stroke style for the line
     */
    Rule(Paint color, LegendText legend, BasicStroke stroke) {
        super(color);
        this.legend = legend;
        this.stroke = stroke;
    }
}
