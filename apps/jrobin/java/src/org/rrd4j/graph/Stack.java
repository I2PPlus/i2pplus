package org.rrd4j.graph;

import org.rrd4j.data.DataProcessor;

import java.awt.*;

/**
 * Represents a stacked plot element in RRD graphs. Extends SourcedPlotElement to stack values on
 * top of a parent element for cumulative graphing. Enables creation of stacked area and line charts
 * showing cumulative totals.
 */
class Stack extends SourcedPlotElement {

    /**
     * Creates a stacked plot element with the specified parent, source name, and color.
     *
     * @param parent the parent plot element to stack on top of
     * @param srcName the name of the data source
     * @param color the color for this stacked element
     */
    Stack(SourcedPlotElement parent, String srcName, Paint color) {
        super(srcName, color, parent);
    }

    /**
     * Assigns values by stacking this element's data on top of parent values. Handles NaN values
     * appropriately to maintain data integrity.
     *
     * @param dproc the data processor containing source values
     */
    @Override
    void assignValues(DataProcessor dproc) {
        double[] parentValues = parent.getValues();
        double[] procValues = dproc.getValues(srcName);
        values = new double[procValues.length];
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(parentValues[i])) {
                values[i] = procValues[i];
            } else if (Double.isNaN(procValues[i])) {
                values[i] = parentValues[i];
            } else {
                values[i] = parentValues[i] + procValues[i];
            }
        }
    }

    /**
     * Gets the line width of the parent element. Returns -1 for area elements (indicating no line
     * width).
     *
     * @return parent line width, or -1 for area elements
     */
    float getParentLineWidth() {
        if (parent instanceof Line) {
            return ((Line) parent).stroke.getLineWidth();
        } else if (parent instanceof Area) {
            return -1F;
        } else /* if(parent instanceof Stack) */ {
            return ((Stack) parent).getParentLineWidth();
        }
    }

    /**
     * Gets the color of the parent element.
     *
     * @return parent element's color
     */
    @Override
    Paint getParentColor() {
        return parent.color;
    }
}
