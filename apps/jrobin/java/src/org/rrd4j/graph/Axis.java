package org.rrd4j.graph;

/**
 * Abstract base class for graph axes in RRD graphs. Provides common functionality for time and
 * value axes. All axis implementations must implement the draw() method to render the axis.
 */
public abstract class Axis implements RrdGraphConstants {

    /**
     * Draws the axis on the graph.
     *
     * @return true if the axis was successfully drawn, false otherwise
     */
    abstract boolean draw();
}
