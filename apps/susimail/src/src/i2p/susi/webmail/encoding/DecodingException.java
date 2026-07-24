// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.webmail.encoding;

import java.io.IOException;

/**
 * Exception for decoding errors.
 */
public class DecodingException extends IOException {
	private static final long serialVersionUID = 1L;

	/** @param msg detail message */
	public DecodingException( String msg ) {
		super( msg );
	}

	/**
	 * @since 0.9.34
	 */
	public DecodingException(String msg, Exception cause)
	{
		super(msg, cause);
	}
}
