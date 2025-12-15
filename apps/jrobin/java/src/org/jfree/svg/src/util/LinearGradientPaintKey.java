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

import java.awt.LinearGradientPaint;
import java.util.Arrays;

/**
 * A wrapper for a {@code LinearGradientPaint} that can be used as the key for a {@code Map}
 * (including a {@code HashMap}). This class is used internally by {@code SVGGraphics2D} to track
 * and re-use gradient definitions. {@code LinearGradientPaint} itself does not implement the {@code
 * equals()} and {@code hashCode()} methods, so it doesn't make a good key for a {@code Map}.
 *
 * @since 1.9
 */
public class LinearGradientPaintKey {

    private final LinearGradientPaint paint;

    /**
     * Creates a new instance.
     *
     * @param lgp the linear gradient paint ({@code null} not permitted).
     */
    public LinearGradientPaintKey(LinearGradientPaint lgp) {
        Args.nullNotPermitted(lgp, "lgp");
        this.paint = lgp;
    }

    /**
     * Returns the {@code LinearGradientPaint} that was supplied to the constructor.
     *
     * @return The {@code LinearGradientPaint} (never {@code null}).
     */
    public LinearGradientPaint getPaint() {
        return this.paint;
    }

    /**
     * Tests this instance for equality with an arbitrary object.
     *
     * @param obj the object to test ({@code null} permitted).
     * @return A boolean.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof LinearGradientPaintKey)) {
            return false;
        }
        LinearGradientPaintKey that = (LinearGradientPaintKey) obj;
        LinearGradientPaint thatPaint = that.getPaint();
        if (!this.paint.getStartPoint().equals(thatPaint.getStartPoint())) {
            return false;
        }
        if (!this.paint.getEndPoint().equals(thatPaint.getEndPoint())) {
            return false;
        }
        if (!Arrays.equals(this.paint.getColors(), thatPaint.getColors())) {
            return false;
        }
        if (!Arrays.equals(this.paint.getFractions(), thatPaint.getFractions())) {
            return false;
        }
        return true;
    }

    /**
     * Returns a hash code for this instance.
     *
     * @return A hash code.
     */
    @Override
    public int hashCode() {
        int hash = 5;
        hash = 47 * hash + this.paint.getStartPoint().hashCode();
        hash = 47 * hash + this.paint.getEndPoint().hashCode();
        hash = 47 * hash + Arrays.hashCode(this.paint.getColors());
        hash = 47 * hash + Arrays.hashCode(this.paint.getFractions());
        return hash;
    }
}
