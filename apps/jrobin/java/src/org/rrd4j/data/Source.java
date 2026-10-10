package org.rrd4j.data;

/**
 * Abstract base class for RRD data sources.<br>
 * Provides common functionality for storing and managing time-series data with timestamps and
 * values.
 */
abstract class Source {
    private final String name;

    /** data values */
    protected double[] values;
    /** timestamps corresponding to data values */
    protected long[] timestamps;

    /**
     * Creates a new Source with the given name.
     *
     * @param name the source name
     */
    Source(String name) {
        this.name = name;
    }

    /**
     * Returns the source name.
     *
     * @return the name
     */
    String getName() {
        return name;
    }

    /**
     * Sets the data values.
     *
     * @param values the data values array
     */
    void setValues(double[] values) {
        this.values = values;
    }

    /**
     * Sets the timestamps.
     *
     * @param timestamps the timestamps array
     */
    void setTimestamps(long[] timestamps) {
        this.timestamps = timestamps;
    }

    /**
     * Returns the data values.
     *
     * @return the values array
     */
    double[] getValues() {
        return values;
    }

    /**
     * The time, in seconds since the epoch, of each recorded value.
     *
     * @return the array of timestamps, index-aligned with {@link #getValues()}
     */
    long[] getTimestamps() {
        return timestamps;
    }

    /**
     * @param tStart start of the aggregation window, in seconds since the epoch
     * @param tEnd end of the aggregation window, in seconds since the epoch
     * @return the Aggregates
     * @deprecated This method is deprecated. Uses instance of {@link org.rrd4j.data.Variable}, used
     *     with {@link org.rrd4j.data.DataProcessor#addDatasource(String, String, Variable)}
     */
    @Deprecated
    Aggregates getAggregates(long tStart, long tEnd) {
        Aggregator agg = new Aggregator(timestamps, values);
        return agg.getAggregates(tStart, tEnd);
    }

    /**
     * @param tStart start of the percentile window, in seconds since the epoch
     * @param tEnd end of the percentile window, in seconds since the epoch
     * @param percentile the percentile to compute, from 0.0 through 100.0
     * @return the percentile
     * @deprecated This method is deprecated. Uses instance of {@link
     *     org.rrd4j.data.Variable.PERCENTILE}, used with {@link
     *     org.rrd4j.data.DataProcessor#addDatasource(String, String, Variable)}
     */
    @Deprecated
    double getPercentile(long tStart, long tEnd, double percentile) {
        Variable vpercent = new Variable.PERCENTILE((float) percentile);
        vpercent.calculate(this, tStart, tEnd);
        return vpercent.getValue().value;
    }
}
