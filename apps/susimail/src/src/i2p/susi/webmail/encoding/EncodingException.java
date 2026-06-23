/*
 * Created on Nov 17, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 * Licensed under the GPLv2 or later.
 *  
 *  $Revision: 1.2 $
 */
package i2p.susi.webmail.encoding;

import java.io.IOException;

/**
 * Converted from Exception to IOException in 0.9.33
 * @author susi
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
