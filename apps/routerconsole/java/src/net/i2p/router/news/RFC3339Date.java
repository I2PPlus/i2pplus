package net.i2p.router.news;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import net.i2p.util.SystemVersion;

/**
 * RFC 3339 date parsing utility for Atom feed compatibility.
 * <p>
 * Provides parsing and formatting of dates according to RFC 3339
 * (ISO 8601) and RFC 4287 Atom specifications. Supports multiple
 * date formats including timezone handling and millisecond precision.
 * <p>
 * Adapted from RFC822Date with enhanced support for modern
 * date formats and timezone specifications. Handles both numeric
 * and named timezone offsets with proper validation.
 * <p>
 * Thread-safe implementation with ThreadLocal formatters for
 * concurrent access. Includes comprehensive format support for
 * various date representations encountered in news feeds.
 *
 * @since 0.9.17
 */
public abstract class RFC3339Date {

    private static final String TZF1;
    private static final String TZF2;
    static {
        if (SystemVersion.isJava7() && !SystemVersion.isAndroid()) {
            TZF1 = "yyyy-MM-dd'T'HH:mm:ssXXX";
            TZF2 = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
        } else {
            TZF1 = "yyyy-MM-dd'T'HH:mm:ssZZZZZ";
            TZF2 = "yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ";
        }
    }

    private static final ThreadLocal<DateFormat> OUTPUT_FORMAT = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
            return fmt;
        }
    };

    private static final ThreadLocal<DateFormat[]> DATE_FORMATS = new ThreadLocal<DateFormat[]>() {
        @Override
        protected DateFormat[] initialValue() {
            TimeZone utc = TimeZone.getTimeZone("GMT");
            SimpleDateFormat[] formats = new SimpleDateFormat[] {
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
                new SimpleDateFormat(TZF1, Locale.US),
                new SimpleDateFormat(TZF2, Locale.US),
                new SimpleDateFormat("yyyy-MM-dd", Locale.US),
                new SimpleDateFormat("yyyy/MM/dd", Locale.US)
            };
            for (int i = 0; i < formats.length; i++) {
                formats[i].setTimeZone(utc);
            }
            return formats;
        }
    };

    /**
     * Parse the date
     *
     * @param s non-null
     * @return -1 on failure
     */
    public static long parse3339Date(String s) {
        s = s.trim();
        int len = s.length();
        if ((!SystemVersion.isJava7() || SystemVersion.isAndroid()) &&
            s.charAt(len - 1) != 'Z' &&
            s.charAt(len - 3) == ':' &&
            (s.charAt(len - 6) == '+' || s.charAt(len - 6) == '-')) {
            s = s.substring(0, len - 3) + s.substring(len - 2);
        }
        DateFormat[] formats = DATE_FORMATS.get();
        try {
            for (int i = 0; i < formats.length; i++) {
                try {
                    Date date = formats[i].parse(s);
                    if (date != null)
                        return date.getTime();
                } catch (ParseException pe) { /* ignored */ }
            }
            return -1;
        } finally {
            DATE_FORMATS.remove();
        }
    }

    /**
     * Format is "yyyy-MM-ddTHH:mm:ssZ"
     */
    public static String to3339Date(long t) {
        try {
            return OUTPUT_FORMAT.get().format(new Date(t));
        } finally {
            OUTPUT_FORMAT.remove();
        }
    }

}
