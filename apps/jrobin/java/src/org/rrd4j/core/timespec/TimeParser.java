package org.rrd4j.core.timespec;

import java.time.Instant;

/**
 * Parser for at-style time specifications used in RRDTool commands.
 *
 * <p>This class parses human-readable time specifications in the format described in detail on the
 * rrdfetch man page. It is primarily a Java port of Tobi Oetiker's parsetime.c code, adapted for
 * Java conventions.
 *
 * <p>Supported time specification formats include:<br>
 * - <strong>Absolute times</strong>: "Jan 1 2023", "12:30", "2023/01/01 12:30:45"<br>
 * - <strong>Relative times</strong>: "now-2hours", "tomorrow", "noon yesterday"<br>
 * - <strong>Natural language</strong>: "midnight", "teatime", "start of week"<br>
 * - <strong>Time ranges</strong>: "start+1week", "end-2days"
 *
 * <p>Special parsing rules:<br>
 * - Two-digit years before 38 are interpreted as post-2000<br>
 * - Ambiguous "m" token resolved based on context (months vs minutes)<br>
 * - Supports both 12-hour (AM/PM) and 24-hour time formats<br>
 * - Multiple date separators accepted: "/", ".", "-"
 *
 * <p>The parser uses a two-phase approach:<br>
 * 1. Lexical scanning ({@link TimeScanner}) breaks input into tokens<br>
 * 2. Grammar parsing interprets tokens according to at-style syntax rules
 *
 * <p>Example usage:<br>
 *
 * <pre>
 * TimeParser parser = new TimeParser("now-1day+2hours");
 * TimeSpec spec = parser.parse();
 * long timestamp = spec.getTimestamp();
 * </pre>
 *
 * @author Sasa Markovic (based on work by Tobi Oetiker)
 */
public class TimeParser {
    private static final int PREVIOUS_OP = -1;

    TimeToken token;
    final TimeScanner scanner;
    final TimeSpec spec;

    int op = TimeToken.PLUS;
    int prev_multiplier = -1;

    /**
     * Constructs TimeParser instance from the given input string.
     *
     * @param dateString at-style time specification (read rrdfetch man page for the complete
     *     explanation)
     */
    public TimeParser(String dateString) {
        scanner = new TimeScanner(dateString);
        spec = new TimeSpec(dateString);
    }

    /**
     * Expects a specific token type and throws exception if not found.
     *
     * <p>This helper method advances the scanner and validates that the next token matches the
     * expected type. If the token type doesn't match, it throws an IllegalArgumentException with
     * the provided error message.
     *
     * @param desired the expected token type identifier
     * @param errorMessage error message to throw if token doesn't match
     * @throws IllegalArgumentException if the next token is not of the desired type
     */
    private void expectToken(int desired, String errorMessage) {
        token = scanner.nextToken();
        if (token.token_id != desired) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    /**
     * Handles plus/minus operations for relative time adjustments.
     *
     * <p>This method processes time adjustments like "+2 hours" or "-5 days". It handles the
     * ambiguous "m" token (months vs minutes) by using context from the previous multiplier to make
     * an educated guess.
     *
     * <p>Ambiguity resolution logic:<br>
     * - After days/weeks/months/years: "m" means months<br>
     * - After seconds/minutes/hours: "m" means minutes<br>
     * - Otherwise: numbers < 6 mean months, >= 6 mean minutes
     *
     * @param doop the operation type (PLUS or MINUS), or -1 to skip processing
     */
    private void plusMinus(int doop) {
        if (doop >= 0) {
            op = doop;
            expectToken(
                    TimeToken.NUMBER,
                    "There should be number after " + (op == TimeToken.PLUS ? '+' : '-'));
            prev_multiplier = -1; /* reset months-minutes guessing mechanics */
        }
        int delta = Integer.parseInt(token.value);
        token = scanner.nextToken();
        if (token.token_id == TimeToken.MONTHS_MINUTES) {
            /* hard job to guess what does that -5m means: -5mon or -5min? */
            switch (prev_multiplier) {
                case TimeToken.DAYS:
                case TimeToken.WEEKS:
                case TimeToken.MONTHS:
                case TimeToken.YEARS:
                    token = scanner.resolveMonthsMinutes(TimeToken.MONTHS);
                    break;
                case TimeToken.SECONDS:
                case TimeToken.MINUTES:
                case TimeToken.HOURS:
                    token = scanner.resolveMonthsMinutes(TimeToken.MINUTES);
                    break;
                default:
                    if (delta < 6) {
                        token = scanner.resolveMonthsMinutes(TimeToken.MONTHS);
                    } else {
                        token = scanner.resolveMonthsMinutes(TimeToken.MINUTES);
                    }
            }
        }
        prev_multiplier = token.token_id;
        delta *= (op == TimeToken.PLUS) ? +1 : -1;
        switch (token.token_id) {
            case TimeToken.YEARS:
                spec.dyear += delta;
                return;
            case TimeToken.MONTHS:
                spec.dmonth += delta;
                return;
            case TimeToken.WEEKS:
                delta *= 7;
            /* FALLTHRU */
            case TimeToken.DAYS:
                spec.dday += delta;
                return;
            case TimeToken.HOURS:
                spec.dhour += delta;
                return;
            case TimeToken.MINUTES:
                spec.dmin += delta;
                return;
            case TimeToken.SECONDS:
            default: // default is 'seconds'
                spec.dsec += delta;
        }
    }

    /**
     * Attempts to parse time-of-day specification from current token.
     *
     * <p>This method is called when a plain number is encountered at the start of a time
     * specification, which could represent either a time (HH:MM) or a date. The method tries to
     * parse it as a time first, and if that fails, it restores scanner state and returns without
     * modifying the time spec.
     *
     * <p>Supported time formats:<br>
     * - HH (hour only)<br>
     * - HH:MM or HH.MM (hour and minute)<br>
     * - Optional AM/PM suffix for 12-hour format
     *
     * <p>The method uses backtracking to restore scanner state if parsing fails, allowing the
     * parser to try alternative interpretations (like treating the number as a date instead).
     */
    private void timeOfDay() {
        int hour;
        int minute = 0;
        /* save token status in case we must abort */
        scanner.saveState();
        /* first pick out the time of day - we assume a HH (COLON|DOT) MM time */
        if (token.value.length() > 2) {
            // Definitely not an hour specification; probably a date or something.  Give up now
            return;
        }
        hour = Integer.parseInt(token.value);
        token = scanner.nextToken();
        if (token.token_id == TimeToken.SLASH) {
            /* guess we are looking at a date */
            token = scanner.restoreState();
            return;
        }
        if (token.token_id == TimeToken.COLON || token.token_id == TimeToken.DOT) {
            expectToken(
                    TimeToken.NUMBER,
                    "Parsing HH:MM or HH.MM syntax, expecting MM as number, got none");
            minute = Integer.parseInt(token.value);
            if (minute > 59) {
                throw new IllegalArgumentException(
                        "Parsing HH:MM or HH.MM syntax, got MM = " + minute + " (>59!)");
            }
            token = scanner.nextToken();
            if (token.token_id == TimeToken.DOT) {
                // Oh look, another dot; must have actually been a date in DD.MM.YYYY format.  Give
                // up and return
                token = scanner.restoreState();
                return;
            }
        }
        /* check if an AM or PM specifier was given */
        if (token.token_id == TimeToken.AM || token.token_id == TimeToken.PM) {
            if (hour > 12) {
                throw new IllegalArgumentException("There cannot be more than 12 AM or PM hours");
            }
            if (token.token_id == TimeToken.PM) {
                if (hour != 12) {
                    /* 12:xx PM is 12:xx, not 24:xx */
                    hour += 12;
                }
            } else {
                if (hour == 12) {
                    /* 12:xx AM is 00:xx, not 12:xx */
                    hour = 0;
                }
            }
            token = scanner.nextToken();
        } else if (hour > 23) {
            /* guess it was not a time then, probably a date ... */
            token = scanner.restoreState();
            return;
        }
        spec.hour = hour;
        spec.min = minute;
        spec.sec = 0;
        if (spec.hour == 24) {
            spec.hour = 0;
            spec.day++;
        }
    }

    private void assignDate(long mday, long mon, long year) {
        if (year >= 0 && year < 38) {
            // 00-37  means post 2000
            year += 2000;
        } else if (year >= 38 && year <= 99) {
            // 38-99  means 1938-1999
            year += 1900;
        }
        spec.year = (int) year;
        spec.month = (int) mon;
        spec.day = (int) mday;
    }

    private void day() {
        long mday = 0;
        long wday;
        long mon;
        long year = spec.year;
        switch (token.token_id) {
            case TimeToken.YESTERDAY:
                spec.day--;
            /* FALLTRHU */
            case TimeToken.TODAY: /* force ourselves to stay in today - no further processing */
                token = scanner.nextToken();
                break;
            case TimeToken.TOMORROW:
                spec.day++;
                token = scanner.nextToken();
                break;
            case TimeToken.JAN:
            case TimeToken.FEB:
            case TimeToken.MAR:
            case TimeToken.APR:
            case TimeToken.MAY:
            case TimeToken.JUN:
            case TimeToken.JUL:
            case TimeToken.AUG:
            case TimeToken.SEP:
            case TimeToken.OCT:
            case TimeToken.NOV:
            case TimeToken.DEC:
                /* do month mday [year] */
                mon = (token.token_id - TimeToken.JAN);
                expectToken(TimeToken.NUMBER, "the day of the month should follow month name");
                mday = Long.parseLong(token.value);
                token = scanner.nextToken();
                if (token.token_id == TimeToken.NUMBER) {
                    year = Long.parseLong(token.value);
                    token = scanner.nextToken();
                } else {
                    year = spec.year;
                }
                assignDate(mday, mon, year);
                break;
            case TimeToken.SUN:
            case TimeToken.MON:
            case TimeToken.TUE:
            case TimeToken.WED:
            case TimeToken.THU:
            case TimeToken.FRI:
            case TimeToken.SAT:
                /* do a particular day of the week */
                wday = (token.token_id - TimeToken.SUN);
                spec.day += (wday - spec.wday);
                token = scanner.nextToken();
                break;
            case TimeToken.NUMBER:
                /* get numeric <sec since 1970>, MM/DD/[YY]YY, or DD.MM.[YY]YY */
                mon = Long.parseLong(token.value);
                if (mon > 10L * 365L * 24L * 60L * 60L) {
                    spec.localtime(mon);
                    token = scanner.nextToken();
                    break;
                }
                if (mon > 19000101 && mon < 24000101) {
                    /*works between 1900 and 2400 */
                    year = mon / 10000;
                    mday = mon % 100;
                    mon = (mon / 100) % 100;
                    token = scanner.nextToken();
                } else {
                    token = scanner.nextToken();
                    if (mon <= 31
                            && (token.token_id == TimeToken.SLASH
                                    || token.token_id == TimeToken.DOT)) {
                        int sep = token.token_id;
                        expectToken(
                                TimeToken.NUMBER,
                                "there should be "
                                        + (sep == TimeToken.DOT ? "month" : "day")
                                        + " number after "
                                        + (sep == TimeToken.DOT ? '.' : '/'));
                        mday = Long.parseLong(token.value);
                        token = scanner.nextToken();
                        if (token.token_id == sep) {
                            expectToken(
                                    TimeToken.NUMBER,
                                    "there should be year number after "
                                            + (sep == TimeToken.DOT ? '.' : '/'));
                            year = Long.parseLong(token.value);
                            token = scanner.nextToken();
                        }
                        /* flip months and days for European timing */
                        if (sep == TimeToken.DOT) {
                            long x = mday;
                            mday = mon;
                            mon = x;
                        }
                    }
                }
                mon--;
                if (mon < 0 || mon > 11) {
                    throw new IllegalArgumentException("Did you really mean month " + (mon + 1));
                }
                if (mday < 1 || mday > 31) {
                    throw new IllegalArgumentException(
                            "I'm afraid that " + mday + " is not a valid day of the month");
                }
                assignDate(mday, mon, year);
                break;
        }
    }

    /**
     * Parses the input string specified in the constructor.
     *
     * @return Object representing parsed date/time.
     */
    public TimeSpec parse() {
        long now = Instant.now().getEpochSecond();
        int hr = 0;
        /* this MUST be initialized to zero for midnight/noon/teatime */
        /* establish the default time reference */
        spec.localtime(now);
        token = scanner.nextToken();
        switch (token.token_id) {
            case TimeToken.PLUS:
            case TimeToken.MINUS:
                break; /* jump to OFFSET-SPEC part */
            case TimeToken.START:
                spec.type = TimeSpec.TYPE_START;
            /* FALLTHRU */
            case TimeToken.END:
                if (spec.type != TimeSpec.TYPE_START) {
                    spec.type = TimeSpec.TYPE_END;
                }
                spec.year = spec.month = spec.day = spec.hour = spec.min = spec.sec = 0;
            /* FALLTHRU */
            case TimeToken.NOW:
                int time_reference = token.token_id;
                token = scanner.nextToken();
                if (token.token_id == TimeToken.PLUS || token.token_id == TimeToken.MINUS) {
                    break;
                }
                if (time_reference != TimeToken.NOW) {
                    throw new IllegalArgumentException(
                            "Words 'start' or 'end' MUST be followed by +|- offset");
                } else if (token.token_id != TimeToken.EOF) {
                    throw new IllegalArgumentException(
                            "If 'now' is followed by a token it must be +|- offset");
                }
                break;
            /* Only absolute time specifications below */
            case TimeToken.NUMBER:
                timeOfDay();
            /* fix month parsing */
            case TimeToken.JAN:
            case TimeToken.FEB:
            case TimeToken.MAR:
            case TimeToken.APR:
            case TimeToken.MAY:
            case TimeToken.JUN:
            case TimeToken.JUL:
            case TimeToken.AUG:
            case TimeToken.SEP:
            case TimeToken.OCT:
            case TimeToken.NOV:
            case TimeToken.DEC:
            case TimeToken.TODAY:
            case TimeToken.YESTERDAY:
            case TimeToken.TOMORROW:
                day();
                if (token.token_id != TimeToken.NUMBER) {
                    break;
                }
                // Allows (but does not require) the time to be specified after the day.  This
                // extends the rrdfetch specification
                timeOfDay();
                break;

            /* evil coding for TEATIME|NOON|MIDNIGHT - we've initialized
             * hr to zero up above, then fall into this case in such a
             * way so we add +12 +4 hours to it for teatime, +12 hours
             * to it for noon, and nothing at all for midnight, then
             * set our rettime to that hour before leaping into the
             * month scanner
             */
            case TimeToken.TEATIME:
                hr += 4;
            /* FALLTHRU */
            case TimeToken.NOON:
                hr += 12;
            /* FALLTHRU */
            case TimeToken.MIDNIGHT:
                spec.hour = hr;
                spec.min = 0;
                spec.sec = 0;
                token = scanner.nextToken();
                day();
                break;
            default:
                throw new IllegalArgumentException("Unparsable time: " + token.value);
        }

        /*
         * the OFFSET-SPEC part
         *
         * (NOTE, the sc_tokid was prefetched for us by the previous code)
         */
        if (token.token_id == TimeToken.PLUS || token.token_id == TimeToken.MINUS) {
            scanner.setContext(false);
            while (token.token_id == TimeToken.PLUS
                    || token.token_id == TimeToken.MINUS
                    || token.token_id == TimeToken.NUMBER) {
                if (token.token_id == TimeToken.NUMBER) {
                    plusMinus(PREVIOUS_OP);
                } else {
                    plusMinus(token.token_id);
                }
                token = scanner.nextToken();
                /* We will get EOF eventually but that's OK, since
                token() will return us as many EOFs as needed */
            }
        }
        /* now we should be at EOF */
        if (token.token_id != TimeToken.EOF) {
            throw new IllegalArgumentException("Unparsable trailing text: " + token.value);
        }
        return spec;
    }
}
