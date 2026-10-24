package org.jfree.svg;

/**
 * An enumeration of the values for SVG units.
 *
 * @since 3.2
 */
public enum SVGUnits {

    /** The font size. */
    EM("em"),

    /** Height of character 'x'. */
    EX("ex"),

    /** Pixels in user space coordinates. */
    PX("px"),

    /** Points (1/72 inch). */
    PT("pt"),

    /** Picas (1/6 inch). */
    PC("pc"),

    /** Centimeter length unit. */
    CM("cm"),

    /** Millimeter length unit. */
    MM("mm"),

    /** Inch length unit. */
    IN("in");

    /** The name used for this value in the SVG attribute. */
    private final String label;

    /**
     * Creates a value with the name used in the attribute.
     *
     * @param label the attribute name
     */
    SVGUnits(String label) {
        this.label = label;
    }

    /**
     * Returns the SVG unit identifier as a string.
     *
     * @return The unit identifier (e.g. "px", "em", "pt").
     */
    @Override
    public String toString() {
        return this.label;
    }
}
