package org.rrd4j.graph;

import java.awt.*;

/**
 * Abstract base class for plot elements in RRD graphs. Provides common functionality for all visual
 * elements that can be plotted on a graph. All plot elements have a color and serve as the
 * foundation for specific graph elements.
 */
class PlotElement {
    /** The color used for rendering this plot element */
    final Paint color;

    /**
     * Creates a new plot element with the specified color.
     *
     * @param color the color for this plot element
     */
    PlotElement(Paint color) {
        this.color = color;
    }
}
