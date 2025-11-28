package org.rrd4j.data;

import org.rrd4j.ConsolFun;
import org.rrd4j.core.Util;

/**
 * Simple class which holds aggregated values (MIN, MAX, FIRST, LAST, AVERAGE and TOTAL). You
 * don't need to create objects of this class directly. Objects of this class are returned from
 * <code>getAggregates()</code> method in
 * {@link org.rrd4j.core.FetchData#getAggregates(String) FetchData} and
 * {@link org.rrd4j.data.DataProcessor#getAggregates(String)} DataProcessor classes.
 *
 * @deprecated This class is deprecated. Uses instance of {@link org.rrd4j.data.Variable}, used with {@link org.rrd4j.data.DataProcessor#addDatasource(String, String, Variable)}.
 */
@Deprecated
public class Aggregates {
    /** Minimal value */
    double min = Double.NaN, max = Double.NaN;
    /** First and last values */
    double first = Double.NaN, last = Double.NaN;
    /** Average and total values */
    double average = Double.NaN, total = Double.NaN;

    /**
     * Default constructor.
     */
    public Aggregates() {
    }

    /**
     * Returns minimal value
     *
     * @return Minimal value
     */
    public double getMin() {
        return min;
    }

    /**
     * Returns maximum value
     *
     * @return Maximum value
     */
    public double getMax() {
        return max;
    }

    /**
     * Returns first value
     *
     * @return First value
     */
    public double getFirst() {
        return first;
    }

    /**
     * Returns last value
     *
     * @return Last value
     */
    public double getLast() {
        return last;
    }

    /**
     * Returns average value
     *
     * @return Average value
     */
    public double getAverage() {
        return average;
    }

    /**
     * Returns total value
     *
     * @return Total value
     */
    public double getTotal() {
        return total;
    }

    /**
     * Sets minimal value
     *
     * @param min Minimal value
     */
    public void setMin(double min) {
        this.min = min;
    }

    /**
     * Sets maximum value
     *
     * @param max Maximum value
     */
    public void setMax(double max) {
        this.max = max;
    }

    /**
     * Sets first value
     *
     * @param first First value
     */
    public void setFirst(double first) {
        this.first = first;
    }

    /**
     * Sets last value
     *
     * @param last Last value
     */
    public void setLast(double last) {
        this.last = last;
    }

    /**
     * Sets average value
     *
     * @param average Average value
     */
    public void setAverage(double average) {
        this.average = average;
    }

    /**
     * Sets total value
     *
     * @param total Total value
     */
    public void setTotal(double total) {
        this.total = total;
    }
}