package org.rrd4j.graph;

import static org.rrd4j.graph.RrdGraphConstants.HH_MM;

import java.util.Calendar;

/**
 * Enumeration of time units used in RRD graphs. Provides mapping between calendar constants and
 * time unit labels for axis formatting.
 */
public enum TimeUnit {
    SECOND {
        @Override
        public String getLabel() {
            return "s";
        }
    },
    MINUTE {
        @Override
        public String getLabel() {
            return HH_MM;
        }
    },
    HOUR {
        @Override
        public String getLabel() {
            return HH_MM;
        }
    },
    DAY {
        @Override
        public String getLabel() {
            return "EEE dd";
        }
    },
    WEEK {
        @Override
        public String getLabel() {
            return "'Week 'w";
        }
    },
    MONTH {
        @Override
        public String getLabel() {
            return "MMM";
        }
    },
    YEAR {
        @Override
        public String getLabel() {
            return "yy";
        }
    };

    public abstract String getLabel();

    public static TimeUnit resolveUnit(int unitKey) {
        switch (unitKey) {
            case Calendar.SECOND:
                return SECOND;
            case Calendar.MINUTE:
                return MINUTE;
            case Calendar.HOUR_OF_DAY:
                return HOUR;
            case Calendar.DAY_OF_MONTH:
                return DAY;
            case Calendar.WEEK_OF_YEAR:
                return WEEK;
            case Calendar.MONTH:
                return MONTH;
            case Calendar.YEAR:
                return YEAR;
            default:
                throw new IllegalArgumentException("Unidentified key " + unitKey);
        }
    }
}
