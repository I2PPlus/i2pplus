/* BDecoder - Converts an InputStream to BEValues.
   Copyright (C) 2003 Mark J. Wielaard

   This file is part of Snark.
   
   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 2, or (at your option)
   any later version.
 
   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.
 
   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.klomp.snark.bencode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataHelper;

public class BEncoder
{

  public static void bencode(Object o, OutputStream out)
    throws IOException, IllegalArgumentException
  {
    if (o == null)
      throw new NullPointerException("Cannot bencode null");
    if (o instanceof String)
      bencode((String)o, out);
    else if (o instanceof byte[])
      bencode((byte[])o, out);
    else if (o instanceof Number)
      bencode((Number)o, out);
    else if (o instanceof List)
      bencode((List<?>)o, out);
    else if (o instanceof Map)
      bencode((Map<?, ?>)o, out);
    else if (o instanceof BEValue)
      bencode(((BEValue)o).getValue(), out);
    else
      throw new IllegalArgumentException("Cannot bencode: " + o.getClass());
  }

  public static void bencode(String s, OutputStream out) throws IOException
  {
    byte[] bs = s.getBytes("UTF-8");
    bencode(bs, out);
  }

  public static void bencode(Number n, OutputStream out) throws IOException
  {
    out.write('i');
    String s = n.toString();
    out.write(s.getBytes("UTF-8"));
    out.write('e');
  }

  public static void bencode(List<?> l, OutputStream out) throws IOException
  {
    out.write('l');
    Iterator<?> it = l.iterator();
    while (it.hasNext())
      bencode(it.next(), out);
    out.write('e');
  }

  public static void bencode(byte[] bs, OutputStream out) throws IOException
  {
    String l = Integer.toString(bs.length);
    out.write(l.getBytes("UTF-8"));
    out.write(':');
    out.write(bs);
  }

  /**
   * Keys must be Strings or (supported as of 0.9.31) byte[]s
   * A mix in the same Map is not supported.
   *
   * @throws IllegalArgumentException if keys are not all Strings or all byte[]s
   */
  public static byte[] bencode(Map<?, ?> m)
  {
    try
      {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bencode(m, baos);
        return baos.toByteArray();
      }
    catch (IOException ioe)
      {
        throw new InternalError(ioe.toString());
      }
  }

  /**
   * Keys must be Strings or (supported as of 0.9.31) byte[]s
   * A mix in the same Map is not supported.
   *
   * @throws IllegalArgumentException if keys are not all Strings or all byte[]s
   */
  public static void bencode(Map<?, ?> m, OutputStream out)
    throws IOException, IllegalArgumentException
  {
    out.write('d');

    Set<?> s = m.keySet();
    List<String> l = null;
    List<byte[]> b = null;
    try {
        for (Object k : s) {
            if (l != null) {
                l.add((String) k);
            } else if (b != null) {
                b.add((byte[]) k);
            } else if (String.class.isAssignableFrom(k.getClass())) {
                l = new ArrayList<String>(s.size());
                l.add((String) k);
            } else if (byte[].class.isAssignableFrom(k.getClass())) {
                b = new ArrayList<byte[]>(s.size());
                b.add((byte[]) k);
            } else {
                throw new IllegalArgumentException("Cannot bencode map: contains key of type " + k.getClass());
            }
        }
    } catch (ClassCastException cce) {
        throw new IllegalArgumentException("Cannot bencode map: mixed keys", cce);
    }

    if (l != null) {
        // Keys must be sorted. XXX - This is not the correct order.
        // Spec says to sort by bytes, not lexically
        if (l.size() > 1)
            Collections.sort(l);
        for (String key : l) {
            bencode(key, out);
            bencode(m.get(key), out);
        }
    } else if (b != null) {
        // Works for arrays of equal lengths, otherwise is probably not
        // what the bittorrent spec intends.
        if (b.size() > 1)
            Collections.sort(b, new BAComparator());
        for (byte[] key : b) {
            bencode(key, out);
            bencode(m.get(key), out);
        }
    }

    out.write('e');
  }

  /**
   * Shorter arrays are less. See DataHelper.compareTo()
   * Works for arrays of equal lengths, otherwise is probably not
   * what the bittorrent spec intends.
   *
   * @since 0.9.31
   */
  private static class BAComparator implements Comparator<byte[]>, Serializable {
      public int compare(byte[] l, byte[] r) {
          return DataHelper.compareTo(l, r);
      }
  }
}
