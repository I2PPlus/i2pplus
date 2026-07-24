// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.webmail.encoding;

import java.io.IOException;

/**
 * Converted from Exception to IOException in 0.9.33
 */
public class EncodingException extends IOException {
	/**
	 * Comment for <code>serialVersionUID</code>
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * @since public since 0.9.33, was package private
	 */
	public EncodingException( String msg )
	{
		super( msg );
	}

	/**
	 * @since 0.9.33
	 */
	public EncodingException(String msg, Exception cause)
	{
		super(msg, cause);
	}

}
