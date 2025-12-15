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
 * An object that can (optionally) translate one font family name to an alternative. A {@code
 * FontMapper} is assigned to an {@link SVGGraphics2D} instance. The default implementation will map
 * Java logical font names to the equivalent SVG generic font names.
 *
 * @since 1.5
 */
public interface FontMapper {

    /**
     * Maps the specified font family name to an alternative, or else returns the same family name.
     *
     * @param family the font family name ({@code null} not permitted).
     * @return The same font family name or an alternative (never {@code null}).
     */
    String mapFont(String family);
}
