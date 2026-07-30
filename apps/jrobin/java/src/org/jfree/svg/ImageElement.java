package org.jfree.svg;

import java.awt.Image;
import org.jfree.svg.util.Args;

/**
 * A {@code (String, Image)} pair that links together a reference ID and the source image. This is
 * used internally by {@link SVGGraphics2D} to track images as they are rendered. This is important
 * when images are not embedded in the SVG output, in which case you may need to generate
 * corresponding image files for the images (see also {@link SVGGraphics2D#getSVGImages()}).
 */
public final class ImageElement {

    /** The filename specified in the href. */
    private final String href;

    /** The image. */
    private final Image image;

    /**
     * Creates a new instance.
     *
     * @param href the href ({@code null} not permitted).
     * @param image the image ({@code null} not permitted).
     */
    public ImageElement(String href, Image image) {
        Args.nullNotPermitted(href, "href");
        Args.nullNotPermitted(image, "image");
        this.href = href;
        this.image = image;
    }

    /**
     * Returns the reference ID that was specified in the constructor.
     *
     * @return The href (never {@code null}).
     */
    public String getHref() {
        return href;
    }

    /**
     * Returns the image that was specified in the constructor.
     *
     * @return The image (never {@code null}).
     */
    public Image getImage() {
        return image;
    }

    /**
     * Returns a string representation of this object, primarily for debugging purposes.
     *
     * @return A string.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ImageElement[");
        sb.append(this.href).append(", ").append(this.image);
        sb.append("]");
        return sb.toString();
    }
}
