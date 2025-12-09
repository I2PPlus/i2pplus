package org.rrd4j.core.jrrd;

import org.rrd4j.DsType;

/**
 * Class DataSourceType
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
public enum DataSourceType {
    /** Counter data source type. */
    COUNTER {
        @Override
        public DsType getDsType() {
            return DsType.COUNTER;
        }
    },

    /** Absolute data source type. */
    ABSOLUTE {
        @Override
        public DsType getDsType() {
            return DsType.ABSOLUTE;
        }
    },

    /** Gauge data source type. */
    GAUGE {
        @Override
        public DsType getDsType() {
            return DsType.GAUGE;
        }
    },

    /** Derive data source type. */
    DERIVE {
        @Override
        public DsType getDsType() {
            return DsType.DERIVE;
        }
    },

    /** Computed data source type (CDEF). */
    CDEF {
        @Override
        public DsType getDsType() {
            throw new UnsupportedOperationException("CDEF not supported");
        }
    };

    public abstract DsType getDsType();
}
