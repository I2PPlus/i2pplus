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
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.AVERAGE;
        }
    },

    /** Minimum consolidation function. */
    MIN {
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.MIN;
        }
    },

    /** Maximum consolidation function. */
    MAX {
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.MAX;
        }
    },

    /** Last value consolidation function. */
    LAST {
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.LAST;
        }
    },

    /** Holt-Winters prediction consolidation function. */
    HWPREDICT {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("HWPREDICT not supported");
        }
    },

    /** Seasonal consolidation function. */
    SEASONAL {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("SEASONAL not supported");
        }
    },

    /** Deviation prediction consolidation function. */
    DEVPREDICT {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("DEVPREDICT not supported");
        }
    },

    /** Deviation seasonal consolidation function. */
    DEVSEASONAL {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("DEVSEASONAL not supported");
        }
    },

    /** Failures consolidation function. */
    FAILURES {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("FAILURES not supported");
        }
    },

    /** Multiply Holt-Winters prediction consolidation function. */
    MHWPREDICT {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("MHWPREDICT not supported");
        }
    };

    public abstract ConsolFun getConsolFun();
}
