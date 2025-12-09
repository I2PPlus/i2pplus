package org.rrd4j.data;

/**
 * Abstract base class for RRD data sources.<br>
 * Provides common functionality for storing and managing time-series data with timestamps and
 * values.
 */
abstract class Source {
    private final String name;

    protected double[] values;
    protected long[] timestamps;

    Source(String name) {
        this.name = name;
    }

    String getName() {
        return name;
    }

    void setValues(double[] values) {
        this.values = values;
    }

    void setTimestamps(long[] timestamps) {
        this.timestamps = timestamps;
    }

    double[] getValues() {
        return values;
    }

    long[] getTimestamps() {
        return timestamps;
    }

    /**
     * @param tStart
     * @param tEnd
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
     * @param tStart
     * @param tEnd
     * @param percentile
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
