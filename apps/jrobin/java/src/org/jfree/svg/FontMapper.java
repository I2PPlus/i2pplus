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
