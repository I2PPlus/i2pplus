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
         * getConsolFun.
         */
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.AVERAGE;
        }
    },

    /** Minimum consolidation function. */
    MIN {
        /**
         * getConsolFun.
         */
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.MIN;
        }
    },

    /** Maximum consolidation function. */
    MAX {
        /**
         * getConsolFun.
         */
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.MAX;
        }
    },

    /** Last value consolidation function. */
    LAST {
        /**
         * getConsolFun.
         */
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.LAST;
        }
    },

    /** Holt-Winters prediction consolidation function. */
    HWPREDICT {
        /**
         * getConsolFun.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("HWPREDICT not supported");
        }
    },

    /** Seasonal consolidation function. */
    SEASONAL {
        /**
         * getConsolFun.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("SEASONAL not supported");
        }
    },

    /** Deviation prediction consolidation function. */
    DEVPREDICT {
        /**
         * getConsolFun.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("DEVPREDICT not supported");
        }
    },

    /** Deviation seasonal consolidation function. */
    DEVSEASONAL {
        /**
         * getConsolFun.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("DEVSEASONAL not supported");
        }
    },

    /** Failures consolidation function. */
    FAILURES {
        /**
         * getConsolFun.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("FAILURES not supported");
        }
    },

    /** Multiply Holt-Winters prediction consolidation function. */
    MHWPREDICT {
        /**
         * getConsolFun.
         */
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("MHWPREDICT not supported");
        }
    };

    /**
     * getConsolFun().
     */
    public abstract ConsolFun getConsolFun();
}
