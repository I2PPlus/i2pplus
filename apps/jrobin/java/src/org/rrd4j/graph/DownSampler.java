package org.rrd4j.graph;

import java.util.Arrays;

/**
 * A class that implement a downsampler, used to reduce the number of point to display.
 *
 * @author Fabrice Bacchella
 */
public interface DownSampler {

    /**
     * Represents a dataset containing timestamps and corresponding values. Used to store
     * downsampled data for graph rendering.
     */
    class DataSet {
        /** Timestamps for each data point. */
        public final long[] timestamps;
        /** Values corresponding to each timestamp. */
        public final double[] values;

        /**
         * Create a new DataSet.
         *
         * @param timestamps the timestamps
         * @param values the values
         */
        public DataSet(long[] timestamps, double[] values) {
            this.timestamps = timestamps;
            this.values = values;
        }

        @Override
        public String toString() {
            return "{\n  "
                    + Arrays.toString(timestamps)
                    + ",\n  "
                    + Arrays.toString(values)
                    + "}\n";
        }
    }

    /**
     * Downsample the given data.
     *
     * @param timestamps the timestamps
     * @param values the values
     * @return the downsampled data set
     */
    DataSet downsize(long[] timestamps, double[] values);
}
