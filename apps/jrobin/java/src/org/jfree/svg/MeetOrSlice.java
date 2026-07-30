package org.jfree.svg;

/**
 * An enumeration of the values for the {@code meetOrSlice} attribute.
 *
 * @since 3.2
 */
public enum MeetOrSlice {

    /** Value 'meet'. */
    MEET("meet"),

    /** Value 'slice'. */
    SLICE("slice");

    private final String label;

    MeetOrSlice(String label) {
        this.label = label;
    }

    /**
     * Returns the SVG meetOrSlice attribute value as a string.
     *
     * @return The attribute value ("meet" or "slice").
     */
    @Override
    public String toString() {
        return this.label;
    }
}
