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
 * Represents a view box in SVG.
 *
 * @since 3.2
 */
public class ViewBox {

    private final int minX;

    private final int minY;

    private final int width;

    private final int height;

    /**
     * Creates a new instance with the specified dimensions.
     *
     * @param minX the x coordinate.
     * @param minY the y coordinate.
     * @param width the width.
     * @param height the height.
     */
    public ViewBox(int minX, int minY, int width, int height) {
        this.minX = minX;
        this.minY = minY;
        this.width = width;
        this.height = height;
    }

    /**
     * Returns a string containing the view box coordinates and dimensions.
     *
     * @return A string containing the view box coordinates and dimensions.
     */
    public String valueStr() {
        return new StringBuilder()
                .append(this.minX)
                .append(" ")
                .append(this.minY)
                .append(" ")
                .append(this.width)
                .append(" ")
                .append(this.height)
                .toString();
    }
}
