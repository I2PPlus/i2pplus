/*
 * This file is part of SusiMail project for I2P
 * Created on Nov 23, 2004
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
 */
public class HTML extends Encoding {

  @Override
  public String getName() {return "HTML";}

  /** @return HTML-escaped string */
  public String encode(byte[] in) throws EncodingException {
    throw new EncodingException("unsupported");
  }

  @Override
  public String encode(String str) throws EncodingException {
    StringBuilder buf = new StringBuilder(str.length() + 16);
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      switch (c) {
        case '&': buf.append("&amp;"); break;
        case '<': buf.append("&lt;"); break;
        case '>': buf.append("&gt;"); break;
        case '\r':
          if (i + 1 < str.length() && str.charAt(i + 1) == '\n') {i++;}
          buf.append("<br>\r\n");
          break;
        case '\n': buf.append("<br>\r\n"); break;
        default: buf.append(c);
      }
    }
    return buf.toString();
  }

  /** Decode HTML entities from the stream */
  public void decode(InputStream in, Buffer out) throws DecodingException {
    throw new DecodingException("unsupported");
  }

}
