package org.rrd4j.data;

/**
 * Aggregates RRD data values over specified time periods.<br>
 * Provides statistical aggregation functions for time-series data with configurable time steps and
 * value arrays.
 */
class Aggregator {
    /** Timestamps for data points */
    private final long[] timestamps;

    /** Time step between data points */
    private final long step;

    /** Data values */
    private final double[] values;

    /**
     * Array of timestamps.
     * @param timestamps array of timestamps
     * @param values array of data values, same length as timestamps
     */
    Aggregator(long[] timestamps, double[] values) {
        assert timestamps.length == values.length
                : "Incompatible timestamps/values arrays (unequal lengths)";
        assert timestamps.length >= 2 : "At least two timestamps must be supplied";
        this.timestamps = timestamps;
        this.values = values;
        this.step = timestamps[1] - timestamps[0];
    }

    /**
     * Calculates aggregations for time range.
     *
     * @param tStart start of the range, in seconds since the Unix epoch
     * @param tEnd end of the range, in seconds since the Unix epoch
     * @return the min, max, first, last, total and time-weighted average over the
     *         range; all fields are NaN if no sample fell inside it
     * @deprecated Use {@link org.rrd4j.data.Variable} instead
     */
    @Deprecated
    Aggregates getAggregates(long tStart, long tEnd) {
        Aggregates agg = new Aggregates();
        long totalSeconds = 0;
        int cnt = 0;

        for (int i = 0; i < timestamps.length; i++) {
            long left = Math.max(timestamps[i] - step, tStart);
            long right = Math.min(timestamps[i], tEnd);
            long delta = right - left;

            // delta is only > 0 when the time stamp for a given buck is within the range of tStart
            // and tEnd
            if (delta > 0) {
                double value = values[i];

                if (!Double.isNaN(value)) {
                    totalSeconds += delta;
                    cnt++;

                    if (cnt == 1) {
                        agg.last = agg.first = agg.total = agg.min = agg.max = value;
                    } else {
                        if (delta >= step) { // an entire bucket is included in this range
                            agg.last = value;
                        }

                        agg.min = Math.min(agg.min, value);
                        agg.max = Math.max(agg.max, value);
                        agg.total += value;
                    }
                }
            }
        }

        if (cnt > 0) {
            agg.average = agg.total / totalSeconds;
        }

        return agg;
    }
}
