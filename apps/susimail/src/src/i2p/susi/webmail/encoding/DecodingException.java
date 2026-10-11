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
	 * @param msg message describing why decoding failed
	 */
	public DecodingException( String msg ) {
		super( msg );
	}

	/**
	 * Constructs a DecodingException with the specified detail message and cause.
	 *
	 * @param msg message describing why decoding failed
	 * @param cause the underlying exception that made decoding fail
	 * @since 0.9.34
	 */
	public DecodingException(String msg, Exception cause)
	{
		super(msg, cause);
	}
}
