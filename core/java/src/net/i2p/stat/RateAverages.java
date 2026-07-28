package net.i2p.stat;

/**
 * Storage space for computations of various averages.
 *
 * @author zab
 * @since 0.9.4
 */
public class RateAverages {

    /** Create a new RateAverages */
    public RateAverages() {}

    /** thread-local temp instance */
    private static final ThreadLocal<RateAverages> TEMP = new ThreadLocal<RateAverages>() {
        /**
         * Per-thread initial value.
         */
        @Override
        public RateAverages initialValue() {
            return new RateAverages();
        }
    };

    /**
     *  Gets a thread-local temp instance.
     *
     * @since 0.9.4
     *
     * @return thread-local temp instance.
     */
    public static RateAverages getTemp() {
        return TEMP.get();
    }

    /**
     * Remove the thread-local temp instance.
     */
    public static void release() {
        TEMP.remove();
    }

    /** Weighted average value. */
    private double average;
    /** Current period average value. */
    private double current;
    /** Last period average value. */
    private double last;
    /** Sum of current and last total values. */
    private double totalValues;
    /** Sum of current and last event counts. */
    private long totalEventCount;

    /** Reset all fields to zero. */
    void reset() {
        average = 0;
        current = 0;
        last = 0;
        totalEventCount = 0;
        totalValues = 0;
    }

    /**
     *  Gets the weighted average.
     *
     * @since 0.9.4
     *
     * @return one of several things:
     * if there are any events (current or last) =&gt; weighted average
     * otherwise if the useLifetime parameter to Rate.computeAverages was:
     * true =&gt; the lifetime average value
     * false =&gt; zero
     */
    public double getAverage() {
        return average;
    }

    /** Set the weighted average. */
    void setAverage(double average) {
        this.average = average;
    }

    /**
     *  Gets the current average.
     *
     * @since 0.9.4
     *
     * @return the current average == current value / current event count
     */
    public double getCurrent() {
        return current;
    }

    /** Set the current period average. */
    void setCurrent(double current) {
        this.current = current;
    }

    /**
     *  Gets the last average.
     *
     * @since 0.9.4
     *
     * @return the last average == last value / last event count
     */
    public double getLast() {
        return last;
    }

    /** Set the last period average. */
    void setLast(double last) {
        this.last = last;
    }

    /**
     *  Gets the total event count.
     *
     * @since 0.9.4
     *
     * @return the total event count == current + last event counts
     */
    public long getTotalEventCount() {
        return totalEventCount;
    }

    /** Set the total event count. */
    void setTotalEventCount(long totalEventCount) {
        this.totalEventCount = totalEventCount;
    }

    /**
     *  Gets the total values.
     *
     * @since 0.9.4
     *
     * @return the total values == current + last values
     */
    public double getTotalValues() {
        return totalValues;
    }

    /** Set the total values sum. */
    void setTotalValues(double totalValues) {
        this.totalValues = totalValues;
    }
}
