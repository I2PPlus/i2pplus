package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Paint;

/**
 * Represents a line plot element in RRD graphs. Draws lines connecting data points with specified
 * stroke and color. Lines are used to show trends and connect individual data points in the graph.
 */
class Line extends SourcedPlotElement {
    final BasicStroke stroke;

    Line(String srcName, Paint color, BasicStroke stroke, SourcedPlotElement parent) {
        super(srcName, color, parent);
        this.stroke = stroke;
    }
}
