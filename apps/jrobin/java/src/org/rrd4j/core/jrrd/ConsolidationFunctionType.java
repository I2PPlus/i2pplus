package org.rrd4j.core.jrrd;

import org.rrd4j.ConsolFun;

/**
 * Class ConsolidationFunctionType
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
public enum ConsolidationFunctionType {
    /** Average consolidation function. */
    AVERAGE {
        /**
         * Returns the averaging consolidation function.
         */
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.AVERAGE;
        }
    },

    /** Minimum consolidation function. */
    MIN {
        /**
         * Returns the minimum consolidation function.
         */
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.MIN;
        }
    },

    /** Maximum consolidation function. */
    MAX {
        /**
         * Returns the maximum consolidation function.
         */
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.MAX;
        }
    },

    /** Last value consolidation function. */
    LAST {
        /**
         * Returns the last recorded value as the consolidation function.
         */
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.LAST;
        }
    },

    /** Holt-Winters prediction consolidation function. */
    HWPREDICT {
        /**
         * The Holt-Winters predictor has no consolidation function; always throws.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("HWPREDICT not supported");
        }
    },

    /** Seasonal consolidation function. */
    SEASONAL {
        /**
         * The seasonal predictor has no consolidation function; always throws.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("SEASONAL not supported");
        }
    },

    /** Deviation prediction consolidation function. */
    DEVPREDICT {
        /**
         * The deviation predictor has no consolidation function; always throws.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("DEVPREDICT not supported");
        }
    },

    /** Deviation seasonal consolidation function. */
    DEVSEASONAL {
        /**
         * The seasonal deviation predictor has no consolidation function; always throws.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("DEVSEASONAL not supported");
        }
    },

    /** Failures consolidation function. */
    FAILURES {
        /**
         * The failure predictor has no consolidation function; always throws.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("FAILURES not supported");
        }
    },

    /** Multiply Holt-Winters prediction consolidation function. */
    MHWPREDICT {
        /**
         * The modified Holt-Winters predictor has no consolidation function; always throws.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("MHWPREDICT not supported");
        }
    };

    /**
     * Returns the I2P JRobin consolidation function this type stands for.
     * @return the I2P JRobin ConsolFun this type stands for, or an implementation that throws UnsupportedOperationException for types JRobin does not support
     */
    public abstract ConsolFun getConsolFun();
}
