package org.rrd4j.core.timespec;

/**
 * Token representation for time specification parsing.
 *
 * <p>This class represents individual tokens produced by the lexical scanner during time
 * specification parsing. Each token contains both the original text value and a token type
 * identifier that categorizes the token's meaning in the time specification grammar.
 *
 * <p>Token categories include:<br>
 * - <strong>Time markers</strong>: Specific times like midnight, noon, teatime<br>
 * - <strong>Relative references</strong>: yesterday, today, tomorrow, now<br>
 * - <strong>Time units</strong>: seconds, minutes, hours, days, weeks, months, years<br>
 * - <strong>Calendar units</strong>: Month names (JAN-DEC) and day names (SUN-SAT)<br>
 * - <strong>Operators</strong>: Plus, minus, dot, colon, slash<br>
 * - <strong>Special tokens</strong>: Numbers, identifiers, end-of-file marker
 *
 * <p>The token system supports both abbreviated and full forms where applicable, providing
 * flexibility in time specification syntax. For example, both "January" and "Jan" map to the same
 * JAN token type.
 *
 * <p>Tokens are immutable objects created by the TimeScanner and consumed by the TimeParser during
 * the parsing process.
 *
 * @author Sasa Markovic
 */
class TimeToken {
    // ===== TIME OF DAY MARKERS =====
    /** Token for "midnight" - represents 00:00:00 of today or tomorrow */
    public static final int MIDNIGHT = 1;

    /** Token for "noon" - represents 12:00:00 of today or tomorrow */
    public static final int NOON = 2;

    /** Token for "teatime" - represents 16:00:00 of today or tomorrow */
    public static final int TEATIME = 3;

    // ===== TIME PERIOD MODIFIERS =====
    /** Token for "pm" - evening times for 0-12 hour clock */
    public static final int PM = 4;

    /** Token for "am" - morning times for 0-12 hour clock */
    public static final int AM = 5;

    // ===== RELATIVE TIME REFERENCES =====
    /** Token for "yesterday" - one day before current date */
    public static final int YESTERDAY = 6;

    /** Token for "today" - current date */
    public static final int TODAY = 7;

    /** Token for "tomorrow" - one day after current date */
    public static final int TOMORROW = 8;

    /** Token for "now" or "n" - current timestamp */
    public static final int NOW = 9;

    // ===== RANGE MARKERS =====
    /** Token for "start" or "s" - beginning of time range */
    public static final int START = 10;

    /** Token for "end" or "e" - end of time range */
    public static final int END = 11;

    // ===== TIME UNIT MULTIPLIERS =====
    /** Token for "second", "seconds", "sec", "s" - seconds multiplier */
    public static final int SECONDS = 12;

    /** Token for "minute", "minutes", "min" - minutes multiplier */
    public static final int MINUTES = 13;

    /** Token for "hour", "hours", "hr", "h" - hours multiplier */
    public static final int HOURS = 14;

    /** Token for "day", "days", "d" - days multiplier */
    public static final int DAYS = 15;

    /** Token for "week", "weeks", "wk", "w" - weeks multiplier */
    public static final int WEEKS = 16;

    /** Token for "month", "months", "mon" - months multiplier */
    public static final int MONTHS = 17;

    /** Token for "year", "years", "yr", "y" - years multiplier */
    public static final int YEARS = 18;

    /** Token for ambiguous "m" - requires context resolution to months or minutes */
    public static final int MONTHS_MINUTES = 19;

    // ===== BASIC TOKEN TYPES =====
    /** Token for numeric values - sequences of digits */
    public static final int NUMBER = 20;

    // ===== OPERATORS =====
    /** Token for "+" - addition operator */
    public static final int PLUS = 21;

    /** Token for "-" - subtraction operator */
    public static final int MINUS = 22;

    /** Token for "." - decimal point or date separator */
    public static final int DOT = 23;

    /** Token for ":" - time separator */
    public static final int COLON = 24;

    /** Token for "/" - date separator */
    public static final int SLASH = 25;

    // ===== SPECIAL TOKEN TYPES =====
    /** Token for unrecognized identifiers - unknown words */
    public static final int ID = 26;

    /** Token for invalid/unparseable input */
    public static final int JUNK = 27;

    // ===== MONTH NAMES =====
    /** Token for "jan", "january" - first month */
    public static final int JAN = 28;

    /** Token for "feb", "february" - second month */
    public static final int FEB = 29;

    /** Token for "mar", "march" - third month */
    public static final int MAR = 30;

    /** Token for "apr", "april" - fourth month */
    public static final int APR = 31;

    /** Token for "may" - fifth month */
    public static final int MAY = 32;

    /** Token for "jun", "june" - sixth month */
    public static final int JUN = 33;

    /** Token for "jul", "july" - seventh month */
    public static final int JUL = 34;

    /** Token for "aug", "august" - eighth month */
    public static final int AUG = 35;

    /** Token for "sep", "september" - ninth month */
    public static final int SEP = 36;

    /** Token for "oct", "october" - tenth month */
    public static final int OCT = 37;

    /** Token for "nov", "november" - eleventh month */
    public static final int NOV = 38;

    /** Token for "dec", "december" - twelfth month */
    public static final int DEC = 39;

    // ===== DAY OF WEEK NAMES =====
    /** Token for "sun", "sunday" - first day of week */
    public static final int SUN = 40;

    /** Token for "mon", "monday" - second day of week */
    public static final int MON = 41;

    /** Token for "tue", "tuesday" - third day of week */
    public static final int TUE = 42;

    /** Token for "wed", "wednesday" - fourth day of week */
    public static final int WED = 43;

    /** Token for "thu", "thursday" - fifth day of week */
    public static final int THU = 44;

    /** Token for "fri", "friday" - sixth day of week */
    public static final int FRI = 45;

    /** Token for "sat", "saturday" - seventh day of week */
    public static final int SAT = 46;

    // ===== CONTROL TOKENS =====
    /** Token for end of input - no more tokens available */
    public static final int EOF = -1;

    /** The original text value of this token */
    final String value;

    /** The token type identifier */
    final int token_id;

    /**
     * Creates a new TimeToken with the specified value and type.
     *
     * @param value the original text value of the token (may be null for EOF)
     * @param token_id the token type identifier from the constant definitions
     */
    public TimeToken(String value, int token_id) {
        this.value = value;
        this.token_id = token_id;
    }

    /**
     * Returns a string representation of this token for debugging.
     *
     * <p>The format is "value [token_id]" which makes it easy to see both the original text and the
     * token type during parsing and debugging.
     *
     * @return a formatted string containing the token value and type identifier
     */
    public String toString() {
        return value + " [" + token_id + "]";
    }
}
