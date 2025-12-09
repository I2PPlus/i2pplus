package org.rrd4j.graph;

import java.util.function.DoubleUnaryOperator;

/**
 * Container for image rendering parameters in RRD graphs. Stores dimensions, value ranges, scaling
 * factors, and other rendering configuration.
 */
class ImageParameters {
    /** Start and end timestamps for graph range */
    long start, end;

    /** Minimum and maximum values for y-axis range */
    double minval, maxval;

    /** Unit exponent for SI unit scaling */
    int unitsexponent;

    /** Base for unit scaling calculations */
    double base;

    /** Magnitude factor for unit scaling */
    double magfact;

    /** SI unit symbol character */
    char symbol;

    /** Step size for y-axis grid lines */
    double ygridstep;

    /** Label factor for y-axis */
    int ylabfact;

    /** Number of decimal places for formatting */
    double decimals;

    /** Quadrant for MRTG-style scaling */
    int quadrant;

    /** Scaled step size for axis calculations */
    double scaledstep;

    /** Graph dimensions */
    int xsize, ysize;

    /** Origin coordinates for graph plotting area */
    int xorigin, yorigin;

    /** Length available for unit labels */
    int unitslength;

    /** Total image dimensions */
    int xgif, ygif;

    /** Logarithmic function for log-scale graphs */
    DoubleUnaryOperator log;
}
