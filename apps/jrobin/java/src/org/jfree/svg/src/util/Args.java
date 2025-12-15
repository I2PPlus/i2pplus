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

package org.jfree.svg.util;

/** A utility class that performs checks for method argument validity. */
public class Args {

    private Args() {
        // no need to instantiate this
    }

    /**
     * Checks that an argument is non-{@code null} and throws an {@code IllegalArgumentException}
     * otherwise.
     *
     * @param obj the object to check for {@code null}.
     * @param ref the text name for the parameter (to include in the exception message).
     */
    public static void nullNotPermitted(Object obj, String ref) {
        if (obj == null) {
            throw new IllegalArgumentException("Null '" + ref + "' argument.");
        }
    }

    /**
     * Checks an array to ensure it has the correct length and throws an {@code
     * IllegalArgumentException} if it does not.
     *
     * @param length the required length.
     * @param array the array to check.
     * @param ref the text name of the array parameter (to include in the exception message).
     */
    public static void arrayMustHaveLength(int length, boolean[] array, String ref) {
        nullNotPermitted(array, "array");
        if (array.length != length) {
            throw new IllegalArgumentException("Array '" + ref + "' requires length " + length);
        }
    }

    /**
     * Checks an array to ensure it has the correct length and throws an {@code
     * IllegalArgumentException} if it does not.
     *
     * @param length the required length.
     * @param array the array to check ({@code null} not permitted).
     * @param ref the text name of the array parameter (to include in the exception message).
     */
    public static void arrayMustHaveLength(int length, double[] array, String ref) {
        nullNotPermitted(array, "array");
        if (array.length != length) {
            throw new IllegalArgumentException("Array '" + ref + "' requires length " + length);
        }
    }
}
