package org.rrd4j.graph;

import java.awt.*;

/**
 * Abstract base class for span elements in RRD graphs. Represents colored regions that span across
 * a range of values or time.
 */
class Span extends PlotElement {
    /** Legend */
    final LegendText legend;

    /** Create Span */
    Span(Paint color, LegendText legend) {
        super(color);
        this.legend = legend;
    }
}
