/* BEValue - Holds different types that a bencoded byte array can represent.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark.bencode;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/**
 * Holds different types that a bencoded byte array can represent.
 * You need to call the correct get method to get the correct java
 * type object. If the BEValue wasn't actually of the requested type
 * you will get a InvalidBEncodingException.
 *
 * @author Mark Wielaard (mark@klomp.org)
 */
public class BEValue
{
  // This is either a byte[], Number, List or Map.
  private final Object value;

  public BEValue(byte[] value)
  {
    this.value = value;
  }

  public BEValue(Number value)
  {
    this.value = value;
  }

  public BEValue(List<BEValue> value)
  {
    this.value = value;
  }

  public BEValue(Map<String, BEValue> value)
  {
    this.value = value;
  }

  /**
   * Returns this BEValue as a String. This operation only succeeds
   * when the BEValue is a byte[], otherwise it will throw a
   * InvalidBEncodingException. The byte[] will be interpreted as
   * UTF-8 encoded characters.
   */
  public String getString() throws InvalidBEncodingException
  {
    try
      {
        return new String(getBytes(), "UTF-8");
      }
    catch (ClassCastException cce)
      {
        throw new InvalidBEncodingException(cce.toString());
      }
    catch (UnsupportedEncodingException uee)
      {
        throw new InternalError(uee.toString());
      }
  }

  /**
   * Returns this BEValue as a byte[]. This operation only succeeds
   * when the BEValue is actually a byte[], otherwise it will throw a
   * InvalidBEncodingException.
   */
  public byte[] getBytes() throws InvalidBEncodingException
  {
    try
      {
        return (byte[])value;
      }
    catch (ClassCastException cce)
      {
        throw new InvalidBEncodingException(cce.toString());
      }
  }

  /**
   * Returns this BEValue as a Number. This operation only succeeds
   * when the BEValue is actually a Number, otherwise it will throw a
   * InvalidBEncodingException.
   */
  public Number getNumber() throws InvalidBEncodingException
  {
    try
      {
        return (Number)value;
      }
    catch (ClassCastException cce)
      {
        throw new InvalidBEncodingException(cce.toString());
      }
  }

  /**
   * Returns this BEValue as int. This operation only succeeds when
   * the BEValue is actually a Number, otherwise it will throw a
   * InvalidBEncodingException. The returned int is the result of
   * <code>Number.intValue()</code>.
   */
  public int getInt() throws InvalidBEncodingException
  {
    return getNumber().intValue();
  }

  /**
   * Returns this BEValue as long. This operation only succeeds when
   * the BEValue is actually a Number, otherwise it will throw a
   * InvalidBEncodingException. The returned long is the result of
   * <code>Number.longValue()</code>.
   */
  public long getLong() throws InvalidBEncodingException
  {
    return getNumber().longValue();
  }

  /**
   * Returns this BEValue as a List of BEValues. This operation only
   * succeeds when the BEValue is actually a List, otherwise it will
   * throw a InvalidBEncodingException.
   */
  @SuppressWarnings("unchecked")
  public List<BEValue> getList() throws InvalidBEncodingException
  {
    try
      {
        return (List<BEValue>)value;
      }
    catch (ClassCastException cce)
      {
        throw new InvalidBEncodingException(cce.toString());
      }
  }

  /**
   * Returns this BEValue as a Map of BEValue keys and BEValue
   * values. This operation only succeeds when the BEValue is actually
   * a Map, otherwise it will throw a InvalidBEncodingException.
   */
  @SuppressWarnings("unchecked")
  public Map<String, BEValue> getMap() throws InvalidBEncodingException
  {
    try
      {
        return (Map<String, BEValue>)value;
      }
    catch (ClassCastException cce)
      {
        throw new InvalidBEncodingException(cce.toString());
      }
  }

  /** return the untyped value */
  public Object getValue() { return value; }

    @Override
  public String toString()
  {
    String valueString;
    if (value instanceof byte[])
      {
        // try to do a nice job for debugging
        byte[] bs = (byte[])value;
        if (bs.length == 0)
          valueString =  "0 bytes";
        else if (bs.length <= 32) {
          StringBuilder buf = new StringBuilder(32);
          boolean bin = false;
          for (int i = 0; i < bs.length; i++) {
              int b = bs[i] & 0xff;
              // no UTF-8
              if (b < ' ' || b > 0x7e) {
                  bin = true;
                  break;
              }
          }
          if (bin && bs.length <= 8) {
              buf.append(bs.length).append(" bytes: 0x");
              for (int i = 0; i < bs.length; i++) {
                  int b = bs[i] & 0xff;
                  if (b < 16)
                      buf.append('0');
                  buf.append(Integer.toHexString(b));
              }
          } else if (bin) {
              buf.append(bs.length).append(" bytes: ").append(Base64.encode(bs));
          } else {
              buf.append('"').append(DataHelper.getUTF8(bs)).append('"');
          }
          valueString = buf.toString();
        } else
          valueString =  bs.length + " bytes";
      }
    else
      valueString = value.toString();

    return "BEValue [" + valueString + "]";
  }
}
