/*
 * Created on Nov 15, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 * Licensed under the GPLv2 or later.
 *  
 * $Revision: 1.2 $
 */
package i2p.susi.webmail.encoding;

import java.io.IOException;

/**
 * Exception for decoding errors.
 * @author susi
 */
public class DecodingException extends IOException {
	private static final long serialVersionUID = 1L;

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
