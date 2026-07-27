// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.webmail.encoding;

import java.io.IOException;

/**
 * Exception for decoding errors.
 */
public class DecodingException extends IOException {
	private static final long serialVersionUID = 1L;

	/**
	 * Constructs a DecodingException with the specified detail message.
	 *
	 * @param msg detail message
	 */
	public DecodingException( String msg ) {
		super( msg );
	}

	/**
	 * Constructs a DecodingException with the specified detail message and cause.
	 *
	 * @param msg detail message
	 * @param cause the cause
	 * @since 0.9.34
	 */
	public DecodingException(String msg, Exception cause)
	{
		super(msg, cause);
	}
}
