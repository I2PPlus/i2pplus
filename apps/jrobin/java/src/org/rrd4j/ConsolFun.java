package org.rrd4j;

import org.rrd4j.data.Variable;

/**
 * Enumeration of available consolidation functions. Note that data aggregation inevitably leads to
 * loss of precision and information. The trick is to pick the aggregate function such that the
 * interesting properties of your data are kept across the aggregation process.
 */
public enum ConsolFun {
    /** The average of the data points is stored. */
    AVERAGE {
        /**
         * Creates the variable that averages the data points.
         */
        @Override
        public Variable getVariable() {
            return new Variable.AVERAGE();
        }
    },

    /** The smallest of the data points is stored. */
    MIN {
        /**
         * Creates the variable that keeps the smallest data point.
         */
        @Override
        public Variable getVariable() {
            return new Variable.MIN();
        }
    },

    /** The largest of the data points is stored. */
    MAX {
        /**
         * Creates the variable that keeps the largest data point.
         */
        @Override
        public Variable getVariable() {
            return new Variable.MAX();
        }
    },

    /** The last data point is used. */
    LAST {
        /**
         * Creates the variable that keeps the last data point.
         */
        @Override
        public Variable getVariable() {
            return new Variable.LAST();
        }
    },

    /** The fist data point is used. */
    FIRST {
        /**
         * Creates the variable that keeps the first data point.
         */
        @Override
        public Variable getVariable() {
            return new Variable.FIRST();
        }
    },

    /** The total of the data points is stored. */
    TOTAL {
        /**
         * Creates the variable that sums the data points.
         */
        @Override
        public Variable getVariable() {
            return new Variable.TOTAL();
        }
    };

    /**
     * Creates the variable that applies this consolidation function.
     * @return the Variable implementation that applies this consolidation function
     */
    public abstract Variable getVariable();
}
