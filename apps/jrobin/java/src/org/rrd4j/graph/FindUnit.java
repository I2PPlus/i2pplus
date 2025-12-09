package org.rrd4j.graph;

/**
 * Utility class for resolving unit symbols based on magnitude. Provides SI unit symbol resolution
 * for value scaling in RRD graphs.
 */
class FindUnit {

    private static final char UNIT_UNKNOWN = '?';
    private static final char[] UNIT_SYMBOLS = {
        'y', 'z', 'a', 'f', 'p', 'n', 'µ', 'm', ' ', 'K', 'M', 'G', 'T', 'P', 'E', 'Z', 'Y'
    };
    private static final int SYMBOLS_CENTER = 8;

    private FindUnit() {}

    /**
     * Resolves the appropriate SI unit symbol for the given magnitude.
     *
     * @param digits the magnitude index for unit selection
     * @return the unit symbol character, or '?' if unknown
     */
    public static char resolveSymbol(double digits) {
        if (Double.isNaN(digits)) {
            return UNIT_UNKNOWN;
        } else {
            if (((digits + SYMBOLS_CENTER) < UNIT_SYMBOLS.length)
                    && ((digits + SYMBOLS_CENTER) >= 0)) {
                return UNIT_SYMBOLS[(int) digits + SYMBOLS_CENTER];
            } else {
                return UNIT_UNKNOWN;
            }
        }
    }
}
