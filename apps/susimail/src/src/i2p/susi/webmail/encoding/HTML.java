/*
 * This file is part of SusiMail project for I2P
 * Created on Nov 23, 2004
 * $Revision: 1.3 $
 * Copyright (C) 2004-2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */
package i2p.susi.webmail.encoding;

import i2p.susi.util.Buffer;
import java.io.InputStream;

/**
 * HTML encoding for safe text display in SusiMail web pages.
 * Escapes special characters and converts newlines to HTML line breaks.
 * Used for preventing XSS attacks in I2P webmail interface.
 * 
 * @author susi
 */
public class HTML extends Encoding {

  public String getName() {return "HTML";}

  public String encode(byte[] in) throws EncodingException {
    throw new EncodingException("unsupported");
  }

  @Override
  public String encode(String str) throws EncodingException {
    return  str.replace("&", "&amp;")  // must be first
               .replace( "<", "&lt;" )
               .replace( ">", "&gt;" )
               .replaceAll( "\r{0,1}\n", "<br>\r\n" );
  }

  public void decode(InputStream in, Buffer out) throws DecodingException {
    throw new DecodingException("unsupported");
  }

}
