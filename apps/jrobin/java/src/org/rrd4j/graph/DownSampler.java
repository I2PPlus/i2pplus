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
        public final long[] timestamps;
        public final double[] values;

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

    DataSet downsize(long[] timestamps, double[] values);
}
