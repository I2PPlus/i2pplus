package org.rrd4j.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
     * Constructor for aggregator.
     *
     * @param timestamps array of timestamps
     * @param values array of data values
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
     * @param tStart start time
     * @param tEnd end time
     * @return calculated aggregations
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

    /**
     * Calculates percentile value for data in time range.
     *
     * @param tStart start time
     * @param tEnd end time
     * @param percentile percentile to calculate (0-100)
     * @return percentile value
     */
    double getPercentile(long tStart, long tEnd, double percentile) {
        List<Double> valueList = new ArrayList<>();
        // create a list of included datasource values (different from NaN)
        for (int i = 0; i < timestamps.length; i++) {
            long left = Math.max(timestamps[i] - step, tStart);
            long right = Math.min(timestamps[i], tEnd);
            if (right > left && !Double.isNaN(values[i])) {
                valueList.add(values[i]);
            }
        }
        // create an array to work with
        int count = valueList.size();
        if (count > 1) {
            double[] valuesCopy = new double[count];
            for (int i = 0; i < count; i++) {
                valuesCopy[i] = valueList.get(i);
            }
            // sort array
            Arrays.sort(valuesCopy);
            // skip top (100% - percentile) values
            double topPercentile = (100.0 - percentile) / 100.0;
            count -= (int) Math.ceil(count * topPercentile);
            // if we have anything left...
            if (count > 0) {
                return valuesCopy[count - 1];
            }
        }
        // not enough data available
        return Double.NaN;
    }
}
