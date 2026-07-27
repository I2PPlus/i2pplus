package org.rrd4j.graph;

import static org.rrd4j.graph.RrdGraphConstants.HH_MM;

import java.util.Calendar;

/**
 * Enumeration of time units used in RRD graphs. Provides mapping between calendar constants and
 * time unit labels for axis formatting.
 */
public enum TimeUnit {
    /** Second time unit with second-level label */
    SECOND {
        /** getLabel. */
        @Override
        public String getLabel() {
            return "s";
        }
    },
    /** Minute time unit with minute-level label */
    MINUTE {
        /** getLabel. */
        @Override
        public String getLabel() {
            return HH_MM;
        }
    },
    /** Hour time unit with hour-level label */
    HOUR {
        /** getLabel. */
        @Override
        public String getLabel() {
            return HH_MM;
        }
    },
    /** Day time unit with day-level label */
    DAY {
        /** getLabel. */
        @Override
        public String getLabel() {
            return "EEE dd";
        }
    },
    /** Week time unit with week-level label */
    WEEK {
        /** getLabel. */
        @Override
        public String getLabel() {
            return "'Week 'w";
        }
    },
    /** Month time unit with month-level label */
    MONTH {
        /** getLabel. */
        @Override
        public String getLabel() {
            return "MMM";
        }
    },
    /** Year time unit with year-level label */
    YEAR {
        /** getLabel. */
        @Override
        public String getLabel() {
            return "yy";
        }
    };

    /** @return label format pattern for this time unit */
    public abstract String getLabel();
    /**
     * @param unitKey Calendar constant (SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, YEAR)
     * @return matching TimeUnit enum value
     */

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
