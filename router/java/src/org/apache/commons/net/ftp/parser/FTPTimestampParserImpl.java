/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.net.ftp.parser;

import java.text.DateFormatSymbols;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.apache.commons.net.ftp.Configurable;
import org.apache.commons.net.ftp.FTPClientConfig;

/**
 * Default implementation of the {@link FTPTimestampParser FTPTimestampParser} interface also implements the {@link org.apache.commons.net.ftp.Configurable
 * Configurable} interface to allow the parsing to be configured from the outside.
 *
 * @see ConfigurableFTPFileEntryParserImpl
 * @since 1.4
 */
public class FTPTimestampParserImpl implements FTPTimestampParser, Configurable {

    /*
     * List of units in order of increasing significance. This allows the code to clear all units in the Calendar until it reaches the least significant unit in
     * the parse string. The date formats are analyzed to find the least significant unit (e.g. Minutes or Milliseconds) and the appropriate index to the array
     * is saved. This is done by searching the array for the unit specifier, and returning the index. When clearing the Calendar units, the code loops through
     * the array until the previous entry. e.g. for MINUTE it would clear MILLISECOND and SECOND
     */
    private static final int[] CALENDAR_UNITS = { Calendar.MILLISECOND, Calendar.SECOND, Calendar.MINUTE, Calendar.HOUR_OF_DAY, Calendar.DAY_OF_MONTH,
            Calendar.MONTH, Calendar.YEAR };

    /*
     * Return the index to the array representing the least significant unit found in the date format. Default is 0 (to avoid dropping precision)
     */
    private static int getEntry(final SimpleDateFormat dateFormat) {
        if (dateFormat == null) {
            return 0;
        }
        final String FORMAT_CHARS = "SsmHdM";
        final String pattern = dateFormat.toPattern();
        for (final char ch : FORMAT_CHARS.toCharArray()) {
            if (pattern.indexOf(ch) != -1) { // found the character
                switch (ch) {
                case 'S':
                    return indexOf(Calendar.MILLISECOND);
                case 's':
                    return indexOf(Calendar.SECOND);
                case 'm':
                    return indexOf(Calendar.MINUTE);
                case 'H':
                    return indexOf(Calendar.HOUR_OF_DAY);
                case 'd':
                    return indexOf(Calendar.DAY_OF_MONTH);
                case 'M':
                    return indexOf(Calendar.MONTH);
                }
            }
        }
        return 0;
    }

    /*
     * Find the entry in the CALENDAR_UNITS array.
     */
    private static int indexOf(final int calendarUnit) {
        int i;
        for (i = 0; i < CALENDAR_UNITS.length; i++) {
            if (calendarUnit == CALENDAR_UNITS[i]) {
                return i;
            }
        }
        return 0;
    }

    /*
     * Sets the Calendar precision (used by FTPFile#toFormattedDate) by clearing the immediately preceding unit (if any). Unfortunately the clear(int) method
     * results in setting all other units.
     */
    private static void setPrecision(final int index, final Calendar working) {
        if (index <= 0) { // e.g. MILLISECONDS
            return;
        }
        final int field = CALENDAR_UNITS[index - 1];
        // Just in case the analysis is wrong, stop clearing if
        // field value is not the default.
        final int value = working.get(field);
        if (value != 0) { // don't reset if it has a value
//            new Throwable("Unexpected value "+value).printStackTrace(); // DEBUG
        } else {
            working.clear(field); // reset just the required field
        }
    }

    /** The date format for all dates, except possibly recent dates. Assumed to include the year. */
    private SimpleDateFormat defaultDateFormat;

    /* The index in CALENDAR_UNITS of the smallest time unit in defaultDateFormat */
    private int defaultDateSmallestUnitIndex;

    /** The format used for recent dates (which don't have the year). May be null. */
    private SimpleDateFormat recentDateFormat;

    /* The index in CALENDAR_UNITS of the smallest time unit in recentDateFormat */
    private int recentDateSmallestUnitIndex;

    private boolean lenientFutureDates;

    /**
     * The only constructor for this class.
     */
    public FTPTimestampParserImpl() {
        setDefaultDateFormat(DEFAULT_SDF, null);
        setRecentDateFormat(DEFAULT_RECENT_SDF, null);
    }

    /**
     * Implements the {@link Configurable Configurable} interface. Configures this {@code FTPTimestampParser} according to the following logic:
     * <p>
     * Sets up the {@link FTPClientConfig#setDefaultDateFormatStr(java.lang.String) defaultDateFormat} and optionally the
     * {@link FTPClientConfig#setRecentDateFormatStr(String) recentDateFormat} to values supplied in the config based on month names configured as follows:
     * </p>
     * <ul>
     * <li>If a {@link FTPClientConfig#setShortMonthNames(String) shortMonthString} has been supplied in the {@code config}, use that to parse parse
     * timestamps.</li>
     * <li>Otherwise, if a {@link FTPClientConfig#setServerLanguageCode(String) serverLanguageCode} has been supplied in the {@code config}, use the month
     * names represented by that {@link FTPClientConfig#lookupDateFormatSymbols(String) language} to parse timestamps.</li>
     * <li>otherwise use default English month names</li>
     * </ul>
     * <p>
     * Finally if a {@link org.apache.commons.net.ftp.FTPClientConfig#setServerTimeZoneId(String) serverTimeZoneId} has been supplied via the config, set that
     * into all date formats that have been configured.
     * </p>
     */
    @Override
    public void configure(final FTPClientConfig config) {
        final DateFormatSymbols dfs;

        final String languageCode = config.getServerLanguageCode();
        final String shortmonths = config.getShortMonthNames();
        if (shortmonths != null) {
            dfs = FTPClientConfig.getDateFormatSymbols(shortmonths);
        } else if (languageCode != null) {
            dfs = FTPClientConfig.lookupDateFormatSymbols(languageCode);
        } else {
            dfs = FTPClientConfig.lookupDateFormatSymbols("en");
        }

        final String recentFormatString = config.getRecentDateFormatStr();
        setRecentDateFormat(recentFormatString, dfs);

        final String defaultFormatString = config.getDefaultDateFormatStr();
        if (defaultFormatString == null) {
            throw new IllegalArgumentException("defaultFormatString cannot be null");
        }
        setDefaultDateFormat(defaultFormatString, dfs);

        setServerTimeZone(config.getServerTimeZoneId());

        lenientFutureDates = config.isLenientFutureDates();
    }

    /**
     * Gets the defaultDateFormat.
     *
     * @return the defaultDateFormat.
     */
    public SimpleDateFormat getDefaultDateFormat() {
        return defaultDateFormat;
    }

    /**
     * Gets the defaultDateFormat pattern string.
     *
     * @return the defaultDateFormat pattern string.
     */
    public String getDefaultDateFormatString() {
        return defaultDateFormat.toPattern();
    }

    /**
     * Gets the recentDateFormat.
     *
     * @return the recentDateFormat.
     */
    public SimpleDateFormat getRecentDateFormat() {
        return recentDateFormat;
    }

    /**
     * Gets the recentDateFormat.
     *
     * @return the recentDateFormat.
     */
    public String getRecentDateFormatString() {
        return recentDateFormat.toPattern();
    }

    /**
     * Gets the serverTimeZone used by this parser.
     *
     * @return the serverTimeZone used by this parser.
     */
    public TimeZone getServerTimeZone() {
        return defaultDateFormat.getTimeZone();
    }

    /**
     * Gets an array of 12 strings representing the short month names used by this parse.
     *
     * @return an array of 12 strings representing the short month names used by this parse.
     */
    public String[] getShortMonths() {
        return defaultDateFormat.getDateFormatSymbols().getShortMonths();
    }

    /**
     * @return the lenientFutureDates.
     */
    boolean isLenientFutureDates() {
        return lenientFutureDates;
    }

    /**
     * Implements the one {@link FTPTimestampParser#parseTimestamp(String) method} in the {@link FTPTimestampParser FTPTimestampParser} interface according to
     * this algorithm:
     *
     * If the recentDateFormat member has been defined, try to parse the supplied string with that. If that parse fails, or if the recentDateFormat member has
     * not been defined, attempt to parse with the defaultDateFormat member. If that fails, throw a ParseException.
     *
     * This method assumes that the server time is the same as the local time.
     *
     * @see FTPTimestampParserImpl#parseTimestamp(String, Calendar)
     * @param timestampStr The timestamp to be parsed
     * @return a Calendar with the parsed timestamp
     */
    @Override
    public Calendar parseTimestamp(final String timestampStr) throws ParseException {
        return parseTimestamp(timestampStr, Calendar.getInstance());
    }

    /**
     * If the recentDateFormat member has been defined, try to parse the supplied string with that. If that parse fails, or if the recentDateFormat member has
     * not been defined, attempt to parse with the defaultDateFormat member. If that fails, throw a ParseException.
     *
     * This method allows a {@link Calendar} instance to be passed in which represents the current (system) time.
     *
     * @see FTPTimestampParser#parseTimestamp(String)
     * @param timestampStr The timestamp to be parsed
     * @param serverTime   The current time for the server
     * @return the calendar
     * @throws ParseException if timestamp cannot be parsed
     * @since 1.5
     */
    public Calendar parseTimestamp(final String timestampStr, final Calendar serverTime) throws ParseException {
        final Calendar working = (Calendar) serverTime.clone();
        working.setTimeZone(getServerTimeZone()); // is this needed?

        Date parsed;

        if (recentDateFormat != null) {
            final Calendar now = (Calendar) serverTime.clone(); // Copy this, because we may change it
            now.setTimeZone(getServerTimeZone());
            if (lenientFutureDates) {
                // add a day to "now" so that "slop" doesn't cause a date
                // slightly in the future to roll back a full year. (Bug 35181 => NET-83)
                now.add(Calendar.DAY_OF_MONTH, 1);
            }
            // The Java SimpleDateFormat class uses the epoch year 1970 if not present in the input
            // As 1970 was not a leap year, it cannot parse "Feb 29" correctly.
            // Java 1.5+ returns Mar 1 1970
            // Temporarily add the current year to the short date time
            // to cope with short-date leap year strings.
            // Since Feb 29 is more that 6 months from the end of the year, this should be OK for
            // all instances of short dates which are +- 6 months from current date.
            // TODO this won't always work for systems that use short dates +0/-12months
            // e.g. if today is Jan 1 2001 and the short date is Feb 29
            final String year = Integer.toString(now.get(Calendar.YEAR));
            final String timeStampStrPlusYear = timestampStr + " " + year;
            final SimpleDateFormat hackFormatter = new SimpleDateFormat(recentDateFormat.toPattern() + " yyyy", recentDateFormat.getDateFormatSymbols());
            hackFormatter.setLenient(false);
            hackFormatter.setTimeZone(recentDateFormat.getTimeZone());
            final ParsePosition pp = new ParsePosition(0);
            parsed = hackFormatter.parse(timeStampStrPlusYear, pp);
            // Check if we parsed the full string, if so it must have been a short date originally
            if (parsed != null && pp.getIndex() == timeStampStrPlusYear.length()) {
                working.setTime(parsed);
                if (working.after(now)) { // must have been last year instead
                    working.add(Calendar.YEAR, -1);
                }
                setPrecision(recentDateSmallestUnitIndex, working);
                return working;
            }
        }

        final ParsePosition pp = new ParsePosition(0);
        parsed = defaultDateFormat.parse(timestampStr, pp);
        // note, length checks are mandatory for us since
        // SimpleDateFormat methods will succeed if less than
        // full string is matched. They will also accept,
        // despite "leniency" setting, a two-digit number as
        // a valid year (e.g. 22:04 will parse as 22 A.D.)
        // so could mistakenly confuse an hour with a year,
        // if we don't insist on full length parsing.
        if (parsed == null || pp.getIndex() != timestampStr.length()) {
            throw new ParseException("Timestamp '" + timestampStr + "' could not be parsed using a server time of " + serverTime.getTime().toString(),
                    pp.getErrorIndex());
        }
        working.setTime(parsed);
        setPrecision(defaultDateSmallestUnitIndex, working);
        return working;
    }

    /**
     * @param format The defaultDateFormat to be set.
     * @param dfs    the symbols to use (may be null)
     */
    private void setDefaultDateFormat(final String format, final DateFormatSymbols dfs) {
        if (format != null) {
            if (dfs != null) {
                defaultDateFormat = new SimpleDateFormat(format, dfs);
            } else {
                defaultDateFormat = new SimpleDateFormat(format);
            }
            defaultDateFormat.setLenient(false);
        } else {
            defaultDateFormat = null;
        }
        defaultDateSmallestUnitIndex = getEntry(defaultDateFormat);
    }

    /**
     * @param lenientFutureDates The lenientFutureDates to set.
     */
    void setLenientFutureDates(final boolean lenientFutureDates) {
        this.lenientFutureDates = lenientFutureDates;
    }

    /**
     * @param format The recentDateFormat to set.
     * @param dfs    the symbols to use (may be null)
     */
    private void setRecentDateFormat(final String format, final DateFormatSymbols dfs) {
        if (format != null) {
            if (dfs != null) {
                recentDateFormat = new SimpleDateFormat(format, dfs);
            } else {
                recentDateFormat = new SimpleDateFormat(format);
            }
            recentDateFormat.setLenient(false);
        } else {
            recentDateFormat = null;
        }
        recentDateSmallestUnitIndex = getEntry(recentDateFormat);
    }

    /**
     * sets a TimeZone represented by the supplied ID string into all the parsers used by this server.
     *
     * @param serverTimeZoneId Time Id java.util.TimeZone id used by the ftp server. If null the client's local time zone is assumed.
     */
    private void setServerTimeZone(final String serverTimeZoneId) {
        TimeZone serverTimeZone = TimeZone.getDefault();
        if (serverTimeZoneId != null) {
            serverTimeZone = TimeZone.getTimeZone(serverTimeZoneId);
        }
        defaultDateFormat.setTimeZone(serverTimeZone);
        if (recentDateFormat != null) {
            recentDateFormat.setTimeZone(serverTimeZone);
        }
    }
}
