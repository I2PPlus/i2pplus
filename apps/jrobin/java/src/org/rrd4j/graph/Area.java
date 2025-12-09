package org.rrd4j.graph;

import java.awt.*;

/**
 * Represents an area plot element in RRD graphs. An area is filled with the specified color and
 * extends from the x-axis to the data values. Areas are typically used to show cumulative data or
 * to highlight regions below data lines.
 */
class Area extends SourcedPlotElement {
    Area(String srcName, Paint color, SourcedPlotElement parent) {
        super(srcName, color, parent);
    }
}
