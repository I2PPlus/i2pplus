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
     * clone.
     */
    public static byte[] clone(byte[] a)
    {
        return Arrays.copyOf(a, a.length);
    }

    /**
     * concatenate.
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
     */
    public static byte[] concatenate(byte[] a, byte[] b)
    {
        byte[] rv = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, rv, a.length, b.length);
        return rv;
    }

    /**
     * append.
     */
    public static byte[] append(byte[] a, byte b)
    {
        byte[] rv = Arrays.copyOf(a, a.length + 1);
        rv[a.length] = b;
        return rv;
    }

    /**
     * constantTimeAreEqual.
     */
    public static boolean constantTimeAreEqual(byte[] a, byte[] b)
    {
        return a.length == b.length && DataHelper.eqCT(a, 0, b, 0, a.length);
    }
}
