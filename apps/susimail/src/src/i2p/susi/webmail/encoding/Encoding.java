/*
 * This file is part of SusiMail project for I2P
 * Created on Nov 12, 2004
 * $Revision: 1.3 $
 * Copyright (C) 2004-2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */
package i2p.susi.webmail.encoding;

import i2p.susi.util.Buffer;
import i2p.susi.util.MemoryBuffer;
import i2p.susi.util.ReadBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Interface to encode/decode content transfer encodings like quoted-printable, base64 etc.
 * 
 * @since 0.9.33 changed from interface to abstract class
 */
public abstract class Encoding {
  protected final Log _log;

  protected Encoding() {_log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());}

  public abstract String getName();

  /**
   * Encode a byte array to a ASCII or ISO-8859-1 String.
   * Output must be SMTP-safe: Line length of 998 or less,
   * using SMTP-safe characters,
   * followed by \r\n, and must not start with a '.'
   * unless escaped by a 2nd dot.
   * For some encodings, max line length is 76.
   * 
   * @param in
   * @return Encoded string.
   * @throws EncodingException 
   */
  public abstract String encode( byte in[] ) throws EncodingException;

  /**
   * Encode a (UTF-8) String to a ASCII or ISO-8859-1 String.
   * Output must be SMTP-safe: Line length of 998 or less,
   * using SMTP-safe characters,
   * followed by \r\n, and must not start with a '.'
   * unless escaped by a 2nd dot.
   * For some encodings, max line length is 76.
   * 
   * This implementation just converts the string to a byte array
   * and then calls encode(byte[]).
   * Most classes will not need to override.
   * 
   * @param str
   * @see Encoding#encode(byte[])
   * @throws EncodingException 
   * @since 0.9.33 implementation moved from subclasses
   */
  public String encode(String str) throws EncodingException {return encode(DataHelper.getUTF8(str));}

  /**
   * Encode an input stream of bytes to a ASCII or ISO-8859-1 String.
   * Output must be SMTP-safe: Line length of 998 or less,
   * using SMTP-safe characters,
   * followed by \r\n, and must not start with a '.'
   * unless escaped by a 2nd dot.
   * For some encodings, max line length is 76.
   * 
   *  This implementation just reads the whole stream into memory
   *  and then calls encode(byte[]).
   *  Subclasses should implement a more memory-efficient method
   *  if large inputs are expected.
   *
   *  @since 0.9.33
   */
  public void encode(InputStream in, Writer out) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataHelper.copy(in, baos);
    out.write(encode(baos.toByteArray()));
  }

  /**
   * This implementation just calls decode(in, 0, in.length).
   * Most classes will not need to override.
   * 
   * @param in
   * @see Encoding#decode(byte[], int, int)
   * @throws DecodingException 
   * @since 0.9.33 implementation moved from subclasses
   */
  public Buffer decode(byte in[]) throws DecodingException {return decode(in, 0, in.length);}

  /**
   * @param in
   * @param offset 
   * @param length 
   * @return Output buffer containing decoded String.
   * @throws DecodingException 
   */
  public Buffer decode(byte in[], int offset, int length) throws DecodingException {
    try {
      ReadBuffer rb = new ReadBuffer(in, offset, length);
      return decode(rb);
    } catch (IOException ioe) {throw new DecodingException("decode error", ioe);}
  }

  /**
   * This implementation just converts the string to a byte array
   * and then calls decode(byte[]).
   * Most classes will not need to override.
   * 
   * @param str
   * @return null if str is null
   * @see Encoding#decode(byte[], int, int)
   * @throws DecodingException 
   * @since 0.9.33 implementation moved from subclasses
   */
  public Buffer decode(String str) throws DecodingException {
    return str != null ? decode(DataHelper.getUTF8(str)) : null;
  }

  /**
   * This implementation just calls decode(in.content, in.offset, in.length).
   * Most classes will not need to override.
   * 
   * @param in
   * @see Encoding#decode(byte[], int, int)
   * @throws DecodingException 
   * @since 0.9.33 implementation moved from subclasses
   */
  public Buffer decode(Buffer in) throws IOException {
    MemoryBuffer rv = new MemoryBuffer(4096);
    decode(in, rv);
    return rv;
  }

  /**
   * @param in
   * @see Encoding#decode(byte[], int, int)
   * @throws DecodingException 
   * @since 0.9.34
   */
  public void decode(Buffer in, Buffer out) throws IOException {decode(in.getInputStream(), out);}

  /**
   * @param in
   * @see Encoding#decode(byte[], int, int)
   * @throws DecodingException 
   * @since 0.9.34
   */
  public abstract void decode(InputStream in, Buffer out) throws IOException;
  
}