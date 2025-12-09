package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;

import java.awt.Paint;
import java.util.Arrays;

/**
 * Represents an area plot element with a constant value in RRD graphs. Extends Area to provide
 * filled areas with fixed values across all data points. Useful for showing reference levels,
 * thresholds, or baseline areas.
 */
public class ConstantArea extends Area {
    /** The constant value for this area */
    private final double value;

    /**
     * Creates a constant area with the specified value, color, and parent.
     *
     * @param value the constant value for all data points
     * @param color the fill color for the area
     * @param parent the parent plot element for stacking
     */
    ConstantArea(double value, Paint color, SourcedPlotElement parent) {
        super(Double.toString(value), color, parent);
        this.value = value;
    }

    /**
     * Assigns constant values to all data points, optionally stacking with parent values.
     *
     * @param dproc the data processor for value assignment
     */
    @Override
    void assignValues(DataProcessor dproc) {
        values = new double[dproc.getTimestamps().length];
        Arrays.fill(values, value);
        if (parent != null) {
            double[] parentValues = parent.getValues();
            for (int i = 0; i < values.length; i++) {
                if (!Double.isNaN(parentValues[i])) {
                    values[i] += parentValues[i];
                }
            }
        }
    }
}
