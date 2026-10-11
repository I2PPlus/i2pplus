package com.docuverse.identicon;

import java.awt.image.BufferedImage;
import java.math.BigInteger;

/**
 * Identicon renderer interface.
 *
 * @author don
 *
 */
public interface IdenticonRenderer {

	/**
	 * Returns rendered identicon image for given identicon code encoded as a
	 * 32-bit signed integer.
	 *
	 * @param code
	 *            identicon code
	 * @param size
	 *            image size
	 * @return the rendered image, of exactly {@code size} by {@code size}
	 */
	public BufferedImage render(int code, int size);

	/**
	 * Returns rendered identicon image for given identicon code.
	 *
	 * @param code
	 *            identicon code
	 * @param size
	 *            image size
	 * @return the rendered image, of exactly {@code size} by {@code size}
	 */
	public BufferedImage render(BigInteger code, int size);
}
