package org.bouncycastle.util;

import java.util.Arrays;
import net.i2p.data.DataHelper;

/**
 * Utility class for byte array operations and cryptographic helper functions.
 * Provides methods for cloning, concatenating, and comparing byte arrays.
 */
public class Util
{
    /**
     * Every helper below is static, so an instance carries nothing.
     */
    public Util()
    {
    }

    /**
     * clone.
     *
     * @param a the array to copy
     * @return an independent copy of the array
     */
    public static byte[] clone(byte[] a)
    {
        return Arrays.copyOf(a, a.length);
    }

    /**
     * concatenate.
     *
     * @param arrays the arrays to join, in order
     * @return a new array holding every input array back to back
     */
    public static byte[] concatenate(byte[][] arrays)
    {
        int size = 0;
        for (int i = 0; i != arrays.length; i++)
        {
            size += arrays[i].length;
        }

        byte[] rv = new byte[size];

        int offSet = 0;
        for (int i = 0; i != arrays.length; i++)
        {
            System.arraycopy(arrays[i], 0, rv, offSet, arrays[i].length);
            offSet += arrays[i].length;
        }

        return rv;
    }

    /**
     * concatenate.
     *
     * @param a the array placed first
     * @param b the array placed second
     * @return a new array holding a followed by b
     */
    public static byte[] concatenate(byte[] a, byte[] b)
    {
        byte[] rv = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, rv, a.length, b.length);
        return rv;
    }

    /**
     * append.
     *
     * @param a the array to extend
     * @param b the byte to place in the new last position
     * @return a new array holding a followed by b
     */
    public static byte[] append(byte[] a, byte b)
    {
        byte[] rv = Arrays.copyOf(a, a.length + 1);
        rv[a.length] = b;
        return rv;
    }

    /**
     * constantTimeAreEqual.
     *
     * @param a the first array to compare
     * @param b the second array to compare
     * @return true if the two arrays hold the same bytes in the same order; arrays of different lengths are never equal
     */
    public static boolean constantTimeAreEqual(byte[] a, byte[] b)
    {
        return a.length == b.length && DataHelper.eqCT(a, 0, b, 0, a.length);
    }
}
