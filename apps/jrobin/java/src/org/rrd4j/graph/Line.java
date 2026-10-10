package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Paint;

/**
 * Represents a line plot element in RRD graphs. Draws lines connecting data points with specified
 * stroke and color. Lines are used to show trends and connect individual data points in the graph.
 */
class Line extends SourcedPlotElement {
    /** Stroke definition for line rendering */
    final BasicStroke stroke;

    /**
     * Bottom-to-top colour stops shading this line by its value, or null for a flat line.
     *
     * <p>Per line rather than per graph so two plots on one axis are shaded independently, the
     * same way their colours are. The span is built by the renderer, which is the only party
     * that knows where the plot area ended up.
     */
    final Color[] valueShade;

    /**
     * Create an unshaded line.
     *
     * @param srcName the name of the data source this line plots
     * @param color the stroke colour
     * @param stroke the stroke used to draw the line
     * @param parent the plot element this line belongs to
     */
    Line(String srcName, Paint color, BasicStroke stroke, SourcedPlotElement parent) {
        this(srcName, color, stroke, parent, null);
    }

    /**
     * Create a line, optionally shaded by its value.
     *
     * @param srcName the name of the data source this line plots
     * @param color the stroke colour
     * @param stroke the stroke used to draw the line
     * @param parent the plot element this line belongs to
     * @param valueShade the bottom-to-top colour stops, or null for a flat line
     */
    Line(String srcName, Paint color, BasicStroke stroke, SourcedPlotElement parent,
         Color[] valueShade) {
        super(srcName, color, parent);
        this.stroke = stroke;
        this.valueShade = valueShade == null || valueShade.length < 2
                        ? null : valueShade.clone();
    }
}
