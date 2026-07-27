package org.rrd4j.graph;

import java.awt.*;
import org.rrd4j.core.Util;
import org.rrd4j.data.DataProcessor;

/**
 * Represents a plot element that obtains data from a named source. Extends PlotElement to handle
 * data assignment, stacking, and value calculations. Supports parent-child relationships for
 * stacked graph elements and provides value range calculations.
 */
class SourcedPlotElement extends PlotElement {
    /** The source name. */
    final String srcName;
    /** The parent element, or null. */
    final SourcedPlotElement parent;
    /** The values. */
    double[] values;

    /**
     * Create a SourcedPlotElement.
     *
     * @param srcName the source name
     * @param color the paint color
     */
    SourcedPlotElement(String srcName, Paint color) {
        super(color);
        this.srcName = srcName;
        this.parent = null;
    }

    /**
     * Create a SourcedPlotElement with a parent.
     *
     * @param srcName the source name
     * @param color the paint color
     * @param parent the parent element for stacking
     */
    SourcedPlotElement(String srcName, Paint color, SourcedPlotElement parent) {
        super(color);
        this.srcName = srcName;
        this.parent = parent;
    }

    /**
     * Assign values from the data processor.
     *
     * @param dproc the data processor
     */
    void assignValues(DataProcessor dproc) {
        if (parent == null) {
            values = dproc.getValues(srcName);
        } else {
            values = stackValues(dproc);
        }
    }

    /**
     * Stack values on top of parent values.
     *
     * @param dproc the data processor
     * @return the stacked values
     */
    double[] stackValues(DataProcessor dproc) {
        double[] parentValues = parent.getValues();
        double[] procValues = dproc.getValues(srcName);
        double[] stacked = new double[procValues.length];
        for (int i = 0; i < stacked.length; i++) {
            if (Double.isNaN(parentValues[i])) {
                stacked[i] = procValues[i];
            } else if (Double.isNaN(procValues[i])) {
                stacked[i] = parentValues[i];
            } else {
                stacked[i] = parentValues[i] + procValues[i];
            }
        }
        return stacked;
    }

    /**
     * Get the parent's color.
     *
     * @return the parent's color, or null
     */
    Paint getParentColor() {
        return parent != null ? parent.color : null;
    }

    /**
     * Get the values.
     *
     * @return the values
     */
    double[] getValues() {
        return values;
    }

    /**
     * Get the minimum value.
     *
     * @return the minimum value
     */
    double getMinValue() {
        return Util.min(values);
    }

    /**
     * Get the maximum value.
     *
     * @return the maximum value
     */
    double getMaxValue() {
        return Util.max(values);
    }
}
