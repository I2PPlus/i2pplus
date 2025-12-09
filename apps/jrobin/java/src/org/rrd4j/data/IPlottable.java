package org.rrd4j.data;

/**
 * Interface for custom data sources used in RRD graphs.<br>
 * Implement this interface to create custom datasources that can be passed to RrdGraphDef for
 * graphing operations.
 *
 * <p>If you wish to use a custom datasource in a graph, create a class implementing this interface
 * that represents that datasource, and then pass this class to RrdGraphDef.
 *
 * @since 3.7
 */
@FunctionalInterface
public interface IPlottable {
    /**
     * Retrieves datapoint value based on a given timestamp. Use this method if you only have one
     * series of data in this class.
     *
     * @param timestamp Timestamp in seconds for the datapoint.
     * @return Double value of the datapoint.
     */
    double getValue(long timestamp);
}
