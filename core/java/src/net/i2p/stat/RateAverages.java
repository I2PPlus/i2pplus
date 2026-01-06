package net.i2p.stat;

/**
 * Storage space for computations of various averages.
 *
 * @author zab
 * @since 0.9.4
 */
public class RateAverages {

    /** thread-local temp instance */
    private static final ThreadLocal<RateAverages> TEMP =
            new ThreadLocal<RateAverages>() {
        @Override
        public RateAverages initialValue() {
            return new RateAverages();
        }
    };

    /**
      *  Gets a thread-local temp instance.
      *
      * @since 0.9.4
      * @return thread-local temp instance.
      */
    public static RateAverages getTemp() {
        return TEMP.get();
    }

    private double average, current, last, totalValues;
    private long totalEventCount;

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
      * @return one of several things:
      * if there are any events (current or last) =&gt; weighted average
      * otherwise if the useLifetime parameter to Rate.computeAverages was:
      * true =&gt; the lifetime average value
      * false =&gt; zero
      */
    public double getAverage() {
        return average;
    }

    void setAverage(double average) {
        this.average = average;
    }

    /**
      *  Gets the current average.
      *
      * @since 0.9.4
      * @return the current average == current value / current event count
      */
    public double getCurrent() {
        return current;
    }

    void setCurrent(double current) {
        this.current = current;
    }

    /**
      *  Gets the last average.
      *
      * @since 0.9.4
      * @return the last average == last value / last event count
      */
    public double getLast() {
        return last;
    }

    void setLast(double last) {
        this.last = last;
    }

    /**
      *  Gets the total event count.
      *
      * @since 0.9.4
      * @return the total event count == current + last event counts
      */
    public long getTotalEventCount() {
        return totalEventCount;
    }

    void setTotalEventCount(long totalEventCount) {
        this.totalEventCount = totalEventCount;
    }

    /**
      *  Gets the total values.
      *
      * @since 0.9.4
      * @return the total values == current + last values
      */
    public double getTotalValues() {
        return totalValues;
    }

    void setTotalValues(double totalValues) {
        this.totalValues = totalValues;
    }

}
