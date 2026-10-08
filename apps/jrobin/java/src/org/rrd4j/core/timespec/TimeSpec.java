package org.rrd4j.core.timespec;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

/**
 * Represents time specifications parsed from at-style date strings.
 *
 * <p>This class stores the result of parsing time specifications in the format used by RRDTool and
 * described in detail on the rrdfetch man page. It supports both absolute timestamps and relative
 * time adjustments.
 *
 * <p>Time specification types:<br>
 * - <strong>Absolute time</strong>: Specific date and time (e.g., "Jan 1 2023 12:00")<br>
 * - <strong>Relative time</strong>: Offsets from reference time (e.g., "now-2hours")<br>
 * - <strong>Start/End markers</strong>: Relative to another time spec (e.g., "start+1day")
 *
 * <p>Common usage patterns:<br>
 *
 * <pre>
 * // Absolute time examples
 * TimeParser p1 = new TimeParser("Jan 1 2023");
 * TimeParser p2 = new TimeParser("12:30");
 * TimeParser p3 = new TimeParser("2023/01/01 12:30:45");
 *
 * // Relative time examples
 * TimeParser p4 = new TimeParser("now-1day");      // Yesterday
 * TimeParser p5 = new TimeParser("now+2hours30min"); // 2.5 hours from now
 * TimeParser p6 = new TimeParser("tomorrow");       // Tomorrow at midnight
 * TimeParser p7 = new TimeParser("noon");           // Today at 12:00
 *
 * // Range examples
 * TimeParser p8 = new TimeParser("start");           // Start of range
 * TimeParser p9 = new TimeParser("end+1week");      // End of range + 1 week
 * </pre>
 *
 * <p>See {@link TimeParser} for complete syntax reference and parsing details.
 *
 * @author Sasa Markovic
 */
public class TimeSpec {
    /** Time specification represents an absolute date/time. */
    static final int TYPE_ABSOLUTE = 0;
    /** Time specification is relative to the start of the time range. */
    static final int TYPE_START = 1;
    /** Time specification is relative to the end of the time range. */
    static final int TYPE_END = 2;
    /**
     * Which of the three forms above this spec takes; an absolute date/time unless a parser
     * says otherwise.
     */
    int type = TYPE_ABSOLUTE;
    /**
     * Broken-down date and wall-clock time of the instant this spec resolves to, following
     * {@link GregorianCalendar} conventions: {@code month} counts from 0 for January, and
     * {@code sec} holds whole seconds with no sub-second part.
     */
    int year, month, day, hour, min, sec;
    /** Day of week, 0 for Sunday through 6 for Saturday, used to resolve weekday names. */
    int wday;
    /**
     * Offset from the context's instant, in the same units as the components above: {@code dmonth}
     * is a whole number of months, the rest whole years, days, hours, minutes and seconds.
     */
    int dyear, dmonth, dday, dhour, dmin, dsec;
    /** The at-style text this spec was parsed from, echoed in resolution error messages. */
    final String dateString;
    /** Absolute spec this one resolves against, wired up by {@link #getTimes}. */
    TimeSpec context;

    /**
     * Creates an empty spec bound to the text it will be parsed from; {@link TimeParser} fills in
     * the time components afterwards.
     *
     * @param dateString at-style time specification, echoed in resolution error messages
     */
    TimeSpec(String dateString) {
        this.dateString = dateString;
    }

    /**
     * Initializes this TimeSpec with local time components from a timestamp.
     *
     * <p>This helper method converts a Unix timestamp (seconds since epoch) into individual time
     * components (year, month, day, hour, minute, second) and day of week. This is used to
     * establish a baseline time for relative time calculations.
     *
     * @param timestamp Unix timestamp in seconds since epoch
     */
    void localtime(long timestamp) {
        GregorianCalendar date = new GregorianCalendar();
        date.setTime(new Date(timestamp * 1000L));
        year = date.get(Calendar.YEAR);
        month = date.get(Calendar.MONTH);
        day = date.get(Calendar.DAY_OF_MONTH);
        hour = date.get(Calendar.HOUR_OF_DAY);
        min = date.get(Calendar.MINUTE);
        sec = date.get(Calendar.SECOND);
        wday = date.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
    }

    /**
     * Resolves this spec against its context, applying the relative offsets.
     *
     * @return the resolved instant, with the offsets applied
     * @throws IllegalStateException if a relative spec has no absolute context to resolve against
     */
    GregorianCalendar getTime() {
        GregorianCalendar gc;
        // absolute time, this is easy
        if (type == TYPE_ABSOLUTE) {
            gc = new GregorianCalendar(year, month, day, hour, min, sec);
        }
        // relative time, we need a context to evaluate it
        else if (context != null && context.type == TYPE_ABSOLUTE) {
            gc = context.getTime();
        }
        // how would I guess what time it was?
        else {
            throw new IllegalStateException(
                    "Relative times like '"
                            + dateString
                            + "' require proper absolute context to be evaluated");
        }
        gc.add(Calendar.YEAR, dyear);
        gc.add(Calendar.MONTH, dmonth);
        gc.add(Calendar.DAY_OF_MONTH, dday);
        gc.add(Calendar.HOUR_OF_DAY, dhour);
        gc.add(Calendar.MINUTE, dmin);
        gc.add(Calendar.SECOND, dsec);
        return gc;
    }

    /**
     * Returns the corresponding timestamp (seconds since Epoch). Example:
     *
     * <pre>
     * TimeParser p = new TimeParser("now-1day");
     * TimeSpec ts = p.parse();
     * System.out.println("Timestamp was: " + ts.getTimestamp();
     * </pre>
     *
     * @return Timestamp (in seconds, no milliseconds)
     */
    public long getTimestamp() {
        return getTime().toInstant().getEpochSecond();
    }

    /**
     * Returns a debug string representation of this TimeSpec.
     *
     * <p>This method formats the TimeSpec for debugging purposes, showing both the absolute time
     * components and the relative delta components in a compact format.
     *
     * @return debug string showing type and all time components
     */
    String dump() {
        return (type == TYPE_ABSOLUTE ? "ABSTIME" : type == TYPE_START ? "START" : "END")
                + ": "
                + year
                + "/"
                + month
                + "/"
                + day
                + "/"
                + hour
                + "/"
                + min
                + "/"
                + sec
                + " ("
                + dyear
                + "/"
                + dmonth
                + "/"
                + dday
                + "/"
                + dhour
                + "/"
                + dmin
                + "/"
                + dsec
                + ")";
    }

    /**
     * Use this static method to resolve relative time references and obtain the corresponding
     * Calendar objects. Example:
     *
     * <pre>
     * TimeParser pStart = new TimeParser("now-1month"); // starting time
     * TimeParser pEnd = new TimeParser("start+1week");  // ending time
     * TimeSpec specStart = pStart.parse();
     * TimeSpec specEnd = pEnd.parse();
     * GregorianCalendar[] gc = TimeSpec.getTimes(specStart, specEnd);
     * </pre>
     *
     * @param spec1 Starting time specification
     * @param spec2 Ending time specification
     * @return Two element array containing Calendar objects
     */
    public static Calendar[] getTimes(TimeSpec spec1, TimeSpec spec2) {
        if (spec1.type == TYPE_START || spec2.type == TYPE_END) {
            throw new IllegalArgumentException("Recursive time specifications not allowed");
        }
        spec1.context = spec2;
        spec2.context = spec1;
        return new Calendar[] {spec1.getTime(), spec2.getTime()};
    }

    /**
     * Use this static method to resolve relative time references and obtain the corresponding
     * timestamps (seconds since epoch). Example:
     *
     * <pre>
     * TimeParser pStart = new TimeParser("now-1month"); // starting time
     * TimeParser pEnd = new TimeParser("start+1week");  // ending time
     * TimeSpec specStart = pStart.parse();
     * TimeSpec specEnd = pEnd.parse();
     * long[] ts = TimeSpec.getTimestamps(specStart, specEnd);
     * </pre>
     *
     * @param spec1 Starting time specification
     * @param spec2 Ending time specification
     * @return array containing two timestamps (in seconds since epoch)
     */
    public static long[] getTimestamps(TimeSpec spec1, TimeSpec spec2) {
        Calendar[] gcs = getTimes(spec1, spec2);
        return new long[] {
            gcs[0].toInstant().getEpochSecond(), gcs[1].toInstant().getEpochSecond()
        };
    }
}
