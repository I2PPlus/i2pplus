/* BEncoder - Converts Java objects to bencoded format.
  Copyright (C) 2003 Mark J. Wielaard
  This file is part of Snark.
  Licensed under the GPL version 2 or later.
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

/**
 * Converts Java objects to bencoded format for BitTorrent protocol communication.
 *
 * <p>Bencoding is a simple way to structure data used in BitTorrent. This class provides static
 * methods to encode the following Java types:
 *
 * <ul>
 *   <li>Strings - encoded as UTF-8 byte strings with length prefix
 *   <li>byte[] - encoded as raw byte strings with length prefix
 *   <li>Numbers - encoded as integer values with 'i' prefix and 'e' suffix
 *   <li>List - encoded as ordered sequences starting with 'l' and ending with 'e'
 *   <li>Map - encoded as dictionaries with sorted keys, starting with 'd' and ending with 'e'
 *   <li>BEValue - unwrapped and encoded according to its contained type
 * </ul>
 *
 * <p>Example output formats:
 *
 * <ul>
 *   <li>String "hello" → "5:hello"
 *   <li>Number 42 → "i42e"
 *   <li>List ["a", "b"] → "l1:a1:be"
 *   <li>Map {"key":"value"} → "d3:key5:valuee"
 * </ul>
 *
 * @since 0.1.0
 */
public class BEncoder {

    /**
     * Bencode an object to the given OutputStream.
     *
     * @param o the object to encode (String, byte[], Number, List, Map, or BEValue)
     * @param out the OutputStream to write the bencoded data to
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if the object type cannot be encoded
     * @throws NullPointerException if the object is null
     */
    public static void bencode(Object o, OutputStream out)
            throws IOException, IllegalArgumentException {
        if (o == null) throw new NullPointerException("Cannot bencode null");
        if (o instanceof String) bencode((String) o, out);
        else if (o instanceof byte[]) bencode((byte[]) o, out);
        else if (o instanceof Number) bencode((Number) o, out);
        else if (o instanceof List) bencode((List<?>) o, out);
        else if (o instanceof Map) bencode((Map<?, ?>) o, out);
        else if (o instanceof BEValue) bencode(((BEValue) o).getValue(), out);
        else throw new IllegalArgumentException("Cannot bencode: " + o.getClass());
    }

    /**
     * Bencode a String to the given OutputStream.
     *
     * @param s the String to encode (will be UTF-8 encoded)
     * @param out the OutputStream to write the bencoded data to
     * @throws IOException if an I/O error occurs
     */
    public static void bencode(String s, OutputStream out) throws IOException {
        byte[] bs = s.getBytes("UTF-8");
        bencode(bs, out);
    }

    /**
     * Bencode a Number to the given OutputStream.
     *
     * @param n the Number to encode
     * @param out the OutputStream to write the bencoded data to
     * @throws IOException if an I/O error occurs
     */
    public static void bencode(Number n, OutputStream out) throws IOException {
        out.write('i');
        String s = n.toString();
        out.write(s.getBytes("UTF-8"));
        out.write('e');
    }

    /**
     * Bencode a List to the given OutputStream.
     *
     * @param l the List to encode
     * @param out the OutputStream to write the bencoded data to
     * @throws IOException if an I/O error occurs
     */
    public static void bencode(List<?> l, OutputStream out) throws IOException {
        out.write('l');
        Iterator<?> it = l.iterator();
        while (it.hasNext()) bencode(it.next(), out);
        out.write('e');
    }

    /**
     * Bencode a byte array to the given OutputStream.
     *
     * @param bs the byte array to encode
     * @param out the OutputStream to write the bencoded data to
     * @throws IOException if an I/O error occurs
     */
    public static void bencode(byte[] bs, OutputStream out) throws IOException {
        String l = Integer.toString(bs.length);
        out.write(l.getBytes("UTF-8"));
        out.write(':');
        out.write(bs);
    }

    /**
     * Bencode a Map to a byte array.
     * Keys must be Strings or (supported as of 0.9.31) byte[]s. A mix in the same Map is not
     * supported.
     *
     * @param m the Map to encode
     * @return the bencoded byte array
     * @throws IllegalArgumentException if keys are not all Strings or all byte[]s
     */
    public static byte[] bencode(Map<?, ?> m) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bencode(m, baos);
            return baos.toByteArray();
        } catch (IOException ioe) {
            throw new InternalError(ioe.toString());
        }
    }

    /**
     * Bencode a Map to the given OutputStream.
     * Keys must be Strings or (supported as of 0.9.31) byte[]s. A mix in the same Map is not
     * supported.
     *
     * @param m the Map to encode
     * @param out the OutputStream to write the bencoded data to
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if keys are not all Strings or all byte[]s
     */
    public static void bencode(Map<?, ?> m, OutputStream out)
            throws IOException, IllegalArgumentException {
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
                    throw new IllegalArgumentException(
                            "Cannot bencode map: contains key of type " + k.getClass());
                }
            }
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException("Cannot bencode map: mixed keys", cce);
        }

        if (l != null) {
            // Keys must be sorted. XXX - This is not the correct order.
            // Spec says to sort by bytes, not lexically
            if (l.size() > 1) Collections.sort(l);
            for (String key : l) {
                bencode(key, out);
                bencode(m.get(key), out);
            }
        } else if (b != null) {
            // Works for arrays of equal lengths, otherwise is probably not
            // what the bittorrent spec intends.
            if (b.size() > 1) Collections.sort(b, new BAComparator());
            for (byte[] key : b) {
                bencode(key, out);
                bencode(m.get(key), out);
            }
        }

        out.write('e');
    }

    /**
     * Comparator for byte arrays. Shorter arrays are less.
     *
     * @see DataHelper#compareTo(byte[], byte[])
     */
    private static class BAComparator implements Comparator<byte[]>, Serializable {
        public int compare(byte[] l, byte[] r) {
            return DataHelper.compareTo(l, r);
        }
    }
}
