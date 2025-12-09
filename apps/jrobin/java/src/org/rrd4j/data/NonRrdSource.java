package org.rrd4j.data;

/**
 * Interface for non-RRD data sources.<br>
 * Represents computed or derived data sources that don't directly reference RRD data but calculate
 * values from other sources.
 */
interface NonRrdSource {
    /**
     * Calculates values for the specified time range.
     *
     * @param tStart start time for calculation
     * @param tEnd end time for calculation
     * @param dataProcessor DataProcessor object for accessing other data sources
     */
    void calculate(long tStart, long tEnd, DataProcessor dataProcessor);
}
