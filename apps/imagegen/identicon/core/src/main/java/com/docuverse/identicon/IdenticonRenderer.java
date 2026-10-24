package com.docuverse.identicon;

import java.awt.image.BufferedImage;

/**
 * Renders an identicon image from a 32-bit hash code, implemented by the
 * geometric patch renderers in this package.
 */
public interface IdenticonRenderer {

	/**
	 * Renders the identicon for a hash code.
	 *
	 * @param code hash code driving the pattern, colours, and rotations
	 * @param size image edge length in pixels
	 * @return the rendered image, of exactly {@code size} by {@code size}
	 */
	public BufferedImage render(int code, int size);
}
