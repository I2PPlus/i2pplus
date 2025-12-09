/*
 * CyberHTTP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.http;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Utility class for handling HTTP date formatting and time operations. Provides methods to format
 * dates according to HTTP standards and create date instances for different time zones.
 */
public class Date {
    private Calendar cal;

    /**
     * Creates a new Date instance with the specified Calendar.
     *
     * @param cal the Calendar instance to use
     */
    public Date(Calendar cal) {
        this.cal = cal;
    }

    /**
     * Gets the Calendar instance used by this Date.
     *
     * @return the Calendar instance
     */
    public Calendar getCalendar() {
        return cal;
    }

    ////////////////////////////////////////////////
    //	Time
    ////////////////////////////////////////////////

    /**
     * Gets the hour of the day (24-hour format).
     *
     * @return the hour (0-23)
     */
    public int getHour() {
        // Thanks for Theo Beisch (10/20/04)
        return getCalendar().get(Calendar.HOUR_OF_DAY);
    }

    /**
     * Gets the minute of the hour.
     *
     * @return minute (0-59)
     */
    public int getMinute() {
        return getCalendar().get(Calendar.MINUTE);
    }

    /**
     * Gets the second of the minute.
     *
     * @return second (0-59)
     */
    public int getSecond() {
        return getCalendar().get(Calendar.SECOND);
    }

    ////////////////////////////////////////////////
    //	paint
    ////////////////////////////////////////////////

    /**
     * Creates a Date instance using the local time zone.
     *
     * @return Date instance with local time
     */
    public static final Date getLocalInstance() {
        return new Date(Calendar.getInstance());
    }

    /**
     * Creates a Date instance using GMT time zone.
     *
     * @return Date instance with GMT time
     */
    public static final Date getInstance() {
        // Thanks for Theo Beisch (10/20/04)
        return new Date(Calendar.getInstance(TimeZone.getTimeZone("GMT")));
    }

    ////////////////////////////////////////////////
    //	getDateString
    ////////////////////////////////////////////////

    /**
     * Converts an integer value to a zero-padded string.
     *
     * @param value the integer value to convert
     * @return zero-padded string representation
     */
    public static final String toDateString(int value) {
        if (value < 10) return "0" + Integer.toString(value);
        return Integer.toString(value);
    }

    private static final String MONTH_STRING[] = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    };

    /**
     * Converts a month value to its three-letter string representation.
     *
     * @param value the month value (Calendar.JANUARY to Calendar.DECEMBER)
     * @return three-letter month string or empty string if invalid
     */
    public static final String toMonthString(int value) {
        value -= Calendar.JANUARY;
        if (0 <= value && value < 12) return MONTH_STRING[value];
        return "";
    }

    private static final String WEEK_STRING[] = {
        "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat",
    };

    /**
     * Converts a day of week value to its three-letter string representation.
     *
     * @param value the day of week value (Calendar.SUNDAY to Calendar.SATURDAY)
     * @return three-letter day string or empty string if invalid
     */
    public static final String toWeekString(int value) {
        value -= Calendar.SUNDAY;
        if (0 <= value && value < 7) return WEEK_STRING[value];
        return "";
    }

    /**
     * Converts an integer value to a zero-padded time string.
     *
     * @param value the integer value to convert
     * @return zero-padded string representation
     */
    public static final String toTimeString(int value) {
        String str = "";
        if (value < 10) str += "0";
        str += Integer.toString(value);
        return str;
    }

    /**
     * Gets the date string in RFC 1123 format (e.g., "Tue, 15 Nov 1994 08:12:31 GMT").
     *
     * @return formatted date string
     */
    public String getDateString() {
        // Thanks for Theo Beisch (10/20/04)
        Calendar cal = getCalendar();
        return toWeekString(cal.get(Calendar.DAY_OF_WEEK))
                + ", "
                + toTimeString(cal.get(Calendar.DATE))
                + " "
                + toMonthString(cal.get(Calendar.MONTH))
                + " "
                + Integer.toString(cal.get(Calendar.YEAR))
                + " "
                + toTimeString(cal.get(Calendar.HOUR_OF_DAY))
                + ":"
                + toTimeString(cal.get(Calendar.MINUTE))
                + ":"
                + toTimeString(cal.get(Calendar.SECOND))
                + " GMT";
    }

    ////////////////////////////////////////////////
    //	getTimeString
    ////////////////////////////////////////////////

    /**
     * Gets the time string in HH:MM format with a blinking colon effect. The colon blinks by
     * alternating between ":" and " " based on the second.
     *
     * @return formatted time string with blinking colon
     */
    public String getTimeString() {
        // Thanks for Theo Beisch (10/20/04)
        Calendar cal = getCalendar();
        return toDateString(cal.get(Calendar.HOUR_OF_DAY))
                + (((cal.get(Calendar.SECOND) % 2) == 0) ? ":" : " ")
                + toDateString(cal.get(Calendar.MINUTE));
    }
}
