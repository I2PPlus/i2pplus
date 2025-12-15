/* ===================================================
 * JFreeSVG : an SVG library for the Java(tm) platform
 * ===================================================
 *
 * (C)opyright 2013-2020, by Object Refinery Limited.  All rights reserved.
 *
 * Project Info:  http://www.jfree.org/jfreesvg/index.html
 *
 * Licensed under the GPL version 3 or later.
 *
 * If you do not wish to be bound by the terms of the GPL, an alternative
 * commercial license can be purchased.  For details, please see visit the
 * JFreeSVG home page:
 *
 * http://www.jfree.org/jfreesvg
 *
 */

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

    /** Centimeters. */
    CM("cm"),

    /** Millimeters. */
    MM("mm"),

    /** Inches. */
    IN("in");

    private final String label;

    SVGUnits(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return this.label;
    }
}
