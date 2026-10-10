package org.rrd4j.graph;

import java.awt.*;

/**
 * Abstract base class for span elements in RRD graphs. Represents colored regions that span across
 * a range of values or time.
 */
class Span extends PlotElement {
    /** The legend entry describing this span. */
    final LegendText legend;

    /**
     * Create a span drawn in the given color.
     *
     * @param color the fill color of the span
     * @param legend the legend entry describing the span
     */
    Span(Paint color, LegendText legend) {
        super(color);
        this.legend = legend;
    }
}
