package org.rrd4j.data;

/**
 * Abstract class for custom data sources used in RRD graphs.<br>
 * Provides base functionality for implementing custom datasources that can be used in RRD graph
 * definitions.
 *
 * <p>If you wish to use a custom datasource in a graph, create a class implementing this interface
 * that represents that datasource, and then pass this class to RrdGraphDef.
 *
 * @deprecated Use implementations of {@link IPlottable} instead
 */
@Deprecated
public abstract class Plottable implements IPlottable {
    /**
     * Retrieves datapoint value based on a given timestamp. Use this method if you only have one
     * series of data in this class.
     *
     * @param timestamp Timestamp in seconds for the datapoint.
     * @return Double value of the datapoint.
     */
    public abstract double getValue(long timestamp);
}
