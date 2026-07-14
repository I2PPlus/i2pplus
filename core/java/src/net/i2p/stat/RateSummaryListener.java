package net.i2p.stat;

/**
 * Receive the state of the rate when its coalesced
 */
public interface RateSummaryListener {
    /**
     *  Called to add rate statistics for a period.
     *
     * @param totalValue sum of all event values in the most recent period
     * @param eventCount how many events occurred
     * @param totalEventTime how long the events were running for
     * @param period how long this period is
     */
    void add(double totalValue, long eventCount, double totalEventTime, long period);

    /**
     *  Get the last N data points from persistent storage (if available).
     *  Default returns all NaN; override in implementations that store history.
     *
     * @param count number of data points requested
     * @return array of length count, NaN-padded where data is unavailable
     * @since 0.9.70+
     */
    default double[] getLastValues(int count) {
        double[] result = new double[count];
        java.util.Arrays.fill(result, Double.NaN);
        return result;
    }
}
