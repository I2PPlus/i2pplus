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

    @Override
    public String toString() {
        return this.label;
    }
}
