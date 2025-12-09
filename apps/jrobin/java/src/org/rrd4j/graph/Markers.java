package org.rrd4j.graph;

/**
 * Enumeration of text alignment and formatting markers used in RRD graph legends. Provides
 * constants for text positioning and spacing within legend elements.
 */
/**
 * Enumeration of text alignment and formatting markers used in RRD graph legends. Provides
 * constants for text positioning and spacing within legend elements.
 */
enum Markers {
    /** Constant to represent left alignment marker */
    ALIGN_LEFT_MARKER("\\l"),
    /** Constant to represent left alignment marker, without new line */
    ALIGN_LEFTNONL_MARKER("\\L"),
    /** Constant to represent centered alignment marker */
    ALIGN_CENTER_MARKER("\\c"),
    /** Constant to represent right alignment marker */
    ALIGN_RIGHT_MARKER("\\r"),
    /** Constant to represent justified alignment marker */
    ALIGN_JUSTIFIED_MARKER("\\j"),
    /** Constant to represent "glue" marker */
    GLUE_MARKER("\\g"),
    /** Constant to represent vertical spacing marker */
    VERTICAL_SPACING_MARKER("\\s"),
    /** Constant to represent no justification markers */
    NO_JUSTIFICATION_MARKER("\\J");

    /** The marker string value */
    final String marker;

    /**
     * Creates a marker with the specified string value.
     *
     * @param marker the marker string
     */
    Markers(String marker) {
        this.marker = marker;
    }

    /**
     * Checks if this marker matches the specified string.
     *
     * @param mark the string to check against
     * @return true if the marker matches, false otherwise
     */
    boolean check(String mark) {
        return marker.equals(mark);
    }
}
