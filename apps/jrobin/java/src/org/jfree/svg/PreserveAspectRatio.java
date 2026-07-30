package org.jfree.svg;

/**
 * An enumeration of the values for the {@code preserveAspectRatio} attribute.
 *
 * @since 3.2
 */
public enum PreserveAspectRatio {

    /** Value 'none'. */
    NONE("none"),

    /** Value 'xMinYMin'. */
    XMIN_YMIN("xMinYMin"),

    /** Value 'xMinYMid'. */
    XMIN_YMID("xMinYMid"),

    /** Value 'xMinYMax'. */
    XMIN_YMAX("xMinYMax"),

    /** Value 'xMidYMin'. */
    XMID_YMIN("xMidYMin"),

    /** Value 'xMidYMid'. */
    XMID_YMID("xMidYMid"),

    /** Value 'xMidYMax'. */
    XMID_YMAX("xMidYMax"),

    /** Value 'xMaxYMin'. */
    XMAX_YMIN("xMaxYMin"),

    /** Value 'xMaxYMid'. */
    XMAX_YMID("xMaxYMid"),

    /** Value 'xMaxYMax'. */
    XMAX_YMAX("xMaxYMax");

    private final String label;

    PreserveAspectRatio(String label) {
        this.label = label;
    }

    /**
     * Returns the SVG preserveAspectRatio attribute value as a string.
     *
     * @return The attribute value (e.g. "xMidYMid", "none").
     */
    @Override
    public String toString() {
        return this.label;
    }
}
