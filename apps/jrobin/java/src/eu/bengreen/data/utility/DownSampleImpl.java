package eu.bengreen.data.utility;

import org.rrd4j.graph.DownSampler;

/**
 * Naive implementation of down sample with simple array input Largest-Triangle-Three-Buckets, from
 * <a href="http://skemman.is/en/item/view/1946/15343">Sveinn Steinarsson's thesis</a>, section
 * 4.2..
 *
 * @author Benjamin Green
 */
public abstract class DownSampleImpl implements DownSampler {

    /** Number of data points to downsample to. */
    protected final int threshold;

    /**
     * Number of data points to downsample to.
     * @param threshold number of data points to downsample to
     */
    protected DownSampleImpl(int threshold) {
        this.threshold = threshold;
    }

    /**
     * Populate a single element in the downsampled data set.
     *
     * @param sampled the data set receiving the timestamp and value at this rank
     * @param rank the position within the downsampled data set to write
     * @param timestamp the source timestamp, in seconds since the epoch
     * @param value the source value retained for this rank
     */
    protected void setDataSetLine(
            DownSampler.DataSet sampled, int rank, long timestamp, double value) {
        sampled.timestamps[rank] = timestamp;
        sampled.values[rank] = value;
    }

    @Override
    public DataSet downsize(long[] timestamps, double[] values) {
        if (timestamps == null || values == null) {
            throw new NullPointerException("Cannot cope with a null data input array.");
        }
        if (threshold <= 2) {
            throw new IllegalArgumentException("What am I supposed to do with that?");
        }
        if (timestamps.length != values.length) {
            throw new IllegalArgumentException("Unmatched size with input arrays");
        }
        int inputLength = timestamps.length;
        if (inputLength <= threshold) {
            return new DownSampler.DataSet(timestamps, values);
        } else {
            DownSampler.DataSet sampled =
                    new DownSampler.DataSet(new long[threshold], new double[threshold]);
            return downsizeImpl(sampled, timestamps, values);
        }
    }

    /**
     * Reduces the source series into the pre-allocated output set. Implementations pick the
     * ranks to keep, write them one slot at a time with {@link #setDataSetLine}, and return
     * that same set; {@link #downsize} has already rejected inputs shorter than the output
     * and mismatched array lengths.
     *
     * @param sampled the empty output set allocated by downsize(), holding exactly
     *        threshold timestamp and value slots to fill
     * @param timestamps the full source timestamps in seconds since the epoch, more than
     *        threshold in number
     * @param values the full source values, the same length as timestamps
     * @return the same sampled set, with the threshold chosen ranks written into it
     */
    protected abstract DataSet downsizeImpl(DataSet sampled, long[] timestamps, double[] values);
}
