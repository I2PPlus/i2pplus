package org.rrd4j.graph;

/**
 * Configuration settings for value axis in RRD graphs. Defines grid step size and label factor for
 * axis formatting.
 */
class ValueAxisSetting {
    /** Step size between grid lines on value axis */
    final double gridStep;

    /** Factor determining label frequency (show every Nth label) */
    final int labelFactor;

    /**
     * Creates value axis settings with specified grid step and label factor.
     *
     * @param gridStep distance between grid lines
     * @param labelFactor frequency factor for label display
     */
    ValueAxisSetting(double gridStep, int labelFactor) {
        this.gridStep = gridStep;
        this.labelFactor = labelFactor;
    }
}
