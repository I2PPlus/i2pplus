package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Paint;
import java.util.Arrays;
import org.rrd4j.data.DataProcessor;

/**
 * Represents a line plot element with a constant value in RRD graphs. Extends Line to provide
 * horizontal lines with fixed values across all data points. Useful for showing reference lines,
 * thresholds, or target values.
 */
public class ConstantLine extends Line {
    /** The constant value for this line */
    private final double value;

    /**
     * Creates a constant line with the specified value, color, stroke, and parent.
     *
     * @param value the constant value for all data points
     * @param color the line color
     * @param stroke the line stroke style
     * @param parent the parent plot element for stacking
     */
    ConstantLine(double value, Paint color, BasicStroke stroke, SourcedPlotElement parent) {
        super(Double.toString(value), color, stroke, parent);
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
