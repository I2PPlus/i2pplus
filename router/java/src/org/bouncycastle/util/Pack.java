package org.bouncycastle.util;

/**
 * Utility methods for converting byte arrays into ints and longs, and back again.
 */
public abstract class Pack
{
    /**
     * Convert a big-endian byte array to a short.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @return the short value
     */
    public static short bigEndianToShort(byte[] bs, int off)
    {
        int n = (bs[off] & 0xff) << 8;
        n |= (bs[++off] & 0xff);
        return (short)n;
    }

    /**
     * Convert a big-endian byte array to an int.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @return the int value
     */
    public static int bigEndianToInt(byte[] bs, int off)
    {
        int n = bs[off] << 24;
        n |= (bs[++off] & 0xff) << 16;
        n |= (bs[++off] & 0xff) << 8;
        n |= (bs[++off] & 0xff);
        return n;
    }

    /**
     * Convert a big-endian byte array to an array of ints.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param ns the output int array
     */
    public static void bigEndianToInt(byte[] bs, int off, int[] ns)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            ns[i] = bigEndianToInt(bs, off);
            off += 4;
        }
    }

    /**
     * Convert a big-endian byte array to a range of an int array.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param ns the output int array
     * @param nsOff the offset into the output array
     * @param nsLen the number of ints to write
     */
    public static void bigEndianToInt(byte[] bs, int off, int[] ns, int nsOff, int nsLen)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            ns[nsOff + i] = bigEndianToInt(bs, off);
            off += 4;
        }
    }

    /**
     * Convert an int to a big-endian byte array.
     *
     * @param n the int value
     * @return the byte array
     */
    public static byte[] intToBigEndian(int n)
    {
        byte[] bs = new byte[4];
        intToBigEndian(n, bs, 0);
        return bs;
    }

    /**
     * Write an int to a byte array in big-endian order.
     *
     * @param n the int value
     * @param bs the target byte array
     * @param off the offset into the array
     */
    public static void intToBigEndian(int n, byte[] bs, int off)
    {
        bs[off] = (byte)(n >>> 24);
        bs[++off] = (byte)(n >>> 16);
        bs[++off] = (byte)(n >>> 8);
        bs[++off] = (byte)(n);
    }

    /**
     * Convert an int array to a big-endian byte array.
     *
     * @param ns the int array
     * @return the byte array
     */
    public static byte[] intToBigEndian(int[] ns)
    {
        byte[] bs = new byte[4 * ns.length];
        intToBigEndian(ns, bs, 0);
        return bs;
    }

    /**
     * Write an int array to a byte array in big-endian order.
     *
     * @param ns the int array
     * @param bs the target byte array
     * @param off the offset into the array
     */
    public static void intToBigEndian(int[] ns, byte[] bs, int off)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            intToBigEndian(ns[i], bs, off);
            off += 4;
        }
    }

    /**
     * Write a range of an int array to a byte array in big-endian order.
     *
     * @param ns the int array
     * @param nsOff the offset into the int array
     * @param nsLen the number of ints to write
     * @param bs the target byte array
     * @param bsOff the offset into the byte array
     */
    public static void intToBigEndian(int[] ns, int nsOff, int nsLen, byte[] bs, int bsOff)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            intToBigEndian(ns[nsOff + i], bs, bsOff);
            bsOff += 4;
        }
    }

    /**
     * Convert a big-endian byte array to a long.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @return the long value
     */
    public static long bigEndianToLong(byte[] bs, int off)
    {
        int hi = bigEndianToInt(bs, off);
        int lo = bigEndianToInt(bs, off + 4);
        return ((long)(hi & 0xffffffffL) << 32) | (long)(lo & 0xffffffffL);
    }

    /**
     * Convert a big-endian byte array to a long array.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param ns the output long array
     */
    public static void bigEndianToLong(byte[] bs, int off, long[] ns)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            ns[i] = bigEndianToLong(bs, off);
            off += 8;
        }
    }

    /**
     * Convert a big-endian byte array to a range of a long array.
     *
     * @param bs the byte array
     * @param bsOff the offset into the byte array
     * @param ns the output long array
     * @param nsOff the offset into the output array
     * @param nsLen the number of longs to write
     */
    public static void bigEndianToLong(byte[] bs, int bsOff, long[] ns, int nsOff, int nsLen)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            ns[nsOff + i] = bigEndianToLong(bs, bsOff);
            bsOff += 8;
        }
    }

    /**
     * Convert a big-endian byte array of variable length to a long.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param len the number of bytes to read (1-8)
     * @return the long value
     */
    public static long bigEndianToLong(byte[] bs, int off, int len)
    {
        long x = 0;
        for (int i = 0; i < len; ++i)
        {
            x |= (bs[i + off] & 0xFFL) << ((7 - i) << 3);
        }
        return x;
    }

    /**
     * Convert a long to a big-endian byte array.
     *
     * @param n the long value
     * @return the byte array
     */
    public static byte[] longToBigEndian(long n)
    {
        byte[] bs = new byte[8];
        longToBigEndian(n, bs, 0);
        return bs;
    }

    /**
     * Write a long to a byte array in big-endian order.
     *
     * @param n the long value
     * @param bs the target byte array
     * @param off the offset into the array
     */
    public static void longToBigEndian(long n, byte[] bs, int off)
    {
        intToBigEndian((int)(n >>> 32), bs, off);
        intToBigEndian((int)(n & 0xffffffffL), bs, off + 4);
    }

    /**
     * Convert a long array to a big-endian byte array.
     *
     * @param ns the long array
     * @return the byte array
     */
    public static byte[] longToBigEndian(long[] ns)
    {
        byte[] bs = new byte[8 * ns.length];
        longToBigEndian(ns, bs, 0);
        return bs;
    }

    /**
     * Write a long array to a byte array in big-endian order.
     *
     * @param ns the long array
     * @param bs the target byte array
     * @param off the offset into the array
     */
    public static void longToBigEndian(long[] ns, byte[] bs, int off)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            longToBigEndian(ns[i], bs, off);
            off += 8;
        }
    }

    /**
     * Write a range of a long array to a byte array in big-endian order.
     *
     * @param ns the long array
     * @param nsOff the offset into the long array
     * @param nsLen the number of longs to write
     * @param bs the target byte array
     * @param bsOff the offset into the byte array
     */
    public static void longToBigEndian(long[] ns, int nsOff, int nsLen, byte[] bs, int bsOff)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            longToBigEndian(ns[nsOff + i], bs, bsOff);
            bsOff += 8;
        }
    }

    /**
     * @param value The number
     * @param bs    The target.
     * @param off   Position in target to start.
     * @param bytes number of bytes to write.
     * @deprecated Will be removed
     */
    public static void longToBigEndian(long value, byte[] bs, int off, int bytes)
    {
        for (int i = bytes - 1; i >= 0; i--)
        {
            bs[i + off] = (byte)(value & 0xff);
            value >>>= 8;
        }
    }

    /**
     * Convert a little-endian byte array to a short.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @return the short value
     */
    public static short littleEndianToShort(byte[] bs, int off)
    {
        int n = bs[off] & 0xff;
        n |= (bs[++off] & 0xff) << 8;
        return (short)n;
    }

    /**
     * Convert a little-endian byte array to an int.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @return the int value
     */
    public static int littleEndianToInt(byte[] bs, int off)
    {
        int n = bs[off] & 0xff;
        n |= (bs[++off] & 0xff) << 8;
        n |= (bs[++off] & 0xff) << 16;
        n |= bs[++off] << 24;
        return n;
    }

    /**
     * Convert a little-endian byte array of variable length to the high-order bits of an int.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param len the number of bytes to read (1-4)
     * @return the int value shifted into the high-order bits
     */
    public static int littleEndianToInt_High(byte[] bs, int off, int len)
    {
        return littleEndianToInt_Low(bs, off, len) << ((4 - len) << 3);
    }

    /**
     * Convert a little-endian byte array of variable length to an int (low-order bits).
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param len the number of bytes to read (1-4)
     * @return the int value
     */
    public static int littleEndianToInt_Low(byte[] bs, int off, int len)
    {
//        assert 1 <= len && len <= 4;

        int result = bs[off] & 0xff;
        int pos = 0;
        for (int i = 1; i < len; ++i)
        {
            pos += 8;
            result |= (bs[off + i] & 0xff) << pos;
        }
        return result;
    }

    /**
     * Convert a little-endian byte array to an int array.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param ns the output int array
     */
    public static void littleEndianToInt(byte[] bs, int off, int[] ns)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            ns[i] = littleEndianToInt(bs, off);
            off += 4;
        }
    }

    /**
     * Convert a little-endian byte array to a range of an int array.
     *
     * @param bs the byte array
     * @param bOff the offset into the byte array
     * @param ns the output int array
     * @param nOff the offset into the output array
     * @param count the number of ints to write
     */
    public static void littleEndianToInt(byte[] bs, int bOff, int[] ns, int nOff, int count)
    {
        for (int i = 0; i < count; ++i)
        {
            ns[nOff + i] = littleEndianToInt(bs, bOff);
            bOff += 4;
        }
    }

    /**
     * Convert a little-endian byte array to a new int array of specified count.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param count the number of ints to read
     * @return the int array
     */
    public static int[] littleEndianToInt(byte[] bs, int off, int count)
    {
        int[] ns = new int[count];
        for (int i = 0; i < ns.length; ++i)
        {
            ns[i] = littleEndianToInt(bs, off);
            off += 4;
        }
        return ns;
    }

    /**
     * Convert a short to a little-endian byte array.
     *
     * @param n the short value
     * @return the byte array
     */
    public static byte[] shortToLittleEndian(short n)
    {
        byte[] bs = new byte[2];
        shortToLittleEndian(n, bs, 0);
        return bs;
    }

    /**
     * Write a short to a byte array in little-endian order.
     *
     * @param n the short value
     * @param bs the target byte array
     * @param off the offset into the array
     */
    public static void shortToLittleEndian(short n, byte[] bs, int off)
    {
        bs[off] = (byte)(n);
        bs[++off] = (byte)(n >>> 8);
    }


    /**
     * Convert a short to a big-endian byte array.
     *
     * @param n the short value
     * @return the byte array
     */
    public static byte[] shortToBigEndian(short n)
    {
        byte[] r = new byte[2];
        shortToBigEndian(n, r, 0);
        return r;
    }

    /**
     * Write a short to a byte array in big-endian order.
     *
     * @param n the short value
     * @param bs the target byte array
     * @param off the offset into the array
     */
    public static void shortToBigEndian(short n, byte[] bs, int off)
    {
        bs[off] = (byte)(n >>> 8);
        bs[++off] = (byte)(n);
    }


    /**
     * Convert an int to a little-endian byte array.
     *
     * @param n the int value
     * @return the byte array
     */
    public static byte[] intToLittleEndian(int n)
    {
        byte[] bs = new byte[4];
        intToLittleEndian(n, bs, 0);
        return bs;
    }

    /**
     * Write an int to a byte array in little-endian order.
     *
     * @param n the int value
     * @param bs the target byte array
     * @param off the offset into the array
     */
    public static void intToLittleEndian(int n, byte[] bs, int off)
    {
        bs[off] = (byte)(n);
        bs[++off] = (byte)(n >>> 8);
        bs[++off] = (byte)(n >>> 16);
        bs[++off] = (byte)(n >>> 24);
    }

    /**
     * Convert an int array to a little-endian byte array.
     *
     * @param ns the int array
     * @return the byte array
     */
    public static byte[] intToLittleEndian(int[] ns)
    {
        byte[] bs = new byte[4 * ns.length];
        intToLittleEndian(ns, bs, 0);
        return bs;
    }

    /**
     * Write an int array to a byte array in little-endian order.
     *
     * @param ns the int array
     * @param bs the target byte array
     * @param off the offset into the array
     */
    public static void intToLittleEndian(int[] ns, byte[] bs, int off)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            intToLittleEndian(ns[i], bs, off);
            off += 4;
        }
    }

    /**
     * Write a range of an int array to a byte array in little-endian order.
     *
     * @param ns the int array
     * @param nsOff the offset into the int array
     * @param nsLen the number of ints to write
     * @param bs the target byte array
     * @param bsOff the offset into the byte array
     */
    public static void intToLittleEndian(int[] ns, int nsOff, int nsLen, byte[] bs, int bsOff)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            intToLittleEndian(ns[nsOff + i], bs, bsOff);
            bsOff += 4;
        }
    }

    /**
     * Convert a little-endian byte array to a long.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @return the long value
     */
    public static long littleEndianToLong(byte[] bs, int off)
    {
        int lo = littleEndianToInt(bs, off);
        int hi = littleEndianToInt(bs, off + 4);
        return ((long)(hi & 0xffffffffL) << 32) | (long)(lo & 0xffffffffL);
    }

    /**
     * Convert a little-endian byte array to a long array.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param ns the output long array
     */
    public static void littleEndianToLong(byte[] bs, int off, long[] ns)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            ns[i] = littleEndianToLong(bs, off);
            off += 8;
        }
    }

    /**
     * Convert a little-endian byte array of variable length to a long.
     *
     * @param input the byte array
     * @param off the offset into the array
     * @param len the number of bytes to read (1-8)
     * @return the long value
     */
    public static long littleEndianToLong(byte[] input, int off, int len)
    {
        long result = 0;
        for (int i = 0; i < len; ++i)
        {
            result |= (input[off + i] & 0xFFL) << (i << 3);
        }
        return result;
    }

    /**
     * Convert a little-endian byte array to a range of a long array.
     *
     * @param bs the byte array
     * @param bsOff the offset into the byte array
     * @param ns the output long array
     * @param nsOff the offset into the output array
     * @param nsLen the number of longs to write
     */
    public static void littleEndianToLong(byte[] bs, int bsOff, long[] ns, int nsOff, int nsLen)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            ns[nsOff + i] = littleEndianToLong(bs, bsOff);
            bsOff += 8;
        }
    }

    /**
     * Write the high-order bytes of a long to a byte array in little-endian order.
     *
     * @param n the long value
     * @param bs the target byte array
     * @param off the offset into the array
     * @param len the number of bytes to write (1-8)
     */
    public static void longToLittleEndian_High(long n, byte[] bs, int off, int len)
    {
        //Debug.Assert(1 <= len && len <= 8);
        int pos = 56;
        bs[off] = (byte)(n >>> pos);
        for (int i = 1; i < len; ++i)
        {
            pos -= 8;
            bs[off + i] = (byte)(n >>> pos);
        }
    }

    /**
     * Write a long to a byte array in little-endian order with variable length.
     *
     * @param n the long value
     * @param bs the target byte array
     * @param off the offset into the array
     * @param len the number of bytes to write (1-8)
     */
    public static void longToLittleEndian(long n, byte[] bs, int off, int len)
    {
        for (int i = 0; i < len; ++i)
        {
            bs[off + i] = (byte)(n >>> (i << 3));
        }
    }

//    public static void longToLittleEndian_Low(long n, byte[] bs, int off, int len)
//    {
//        longToLittleEndian_High(n << ((8 - len) << 3), bs, off, len);
//    }

    /**
     * Convert a little-endian byte array of variable length to the high-order bits of a long.
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param len the number of bytes to read (1-8)
     * @return the long value shifted into the high-order bits
     */
    public static long littleEndianToLong_High(byte[] bs, int off, int len)
    {
        return littleEndianToLong_Low(bs, off, len) << ((8 - len) << 3);
    }

    /**
     * Convert a little-endian byte array of variable length to a long (low-order bits).
     *
     * @param bs the byte array
     * @param off the offset into the array
     * @param len the number of bytes to read (1-8)
     * @return the long value
     */
    public static long littleEndianToLong_Low(byte[] bs, int off, int len)
    {
        //Debug.Assert(1 <= len && len <= 8);
        long result = bs[off] & 0xFF;
        for (int i = 1; i < len; ++i)
        {
            result <<= 8;
            result |= bs[off + i] & 0xFF;
        }
        return result;
    }

    /**
     * Convert a long to a little-endian byte array.
     *
     * @param n the long value
     * @return the byte array
     */
    public static byte[] longToLittleEndian(long n)
    {
        byte[] bs = new byte[8];
        longToLittleEndian(n, bs, 0);
        return bs;
    }

    /**
     * Write a long to a byte array in little-endian order.
     *
     * @param n the long value
     * @param bs the target byte array
     * @param off the offset into the array
     */
    public static void longToLittleEndian(long n, byte[] bs, int off)
    {
        intToLittleEndian((int)(n & 0xffffffffL), bs, off);
        intToLittleEndian((int)(n >>> 32), bs, off + 4);
    }

    /**
     * Convert a long array to a little-endian byte array.
     *
     * @param ns the long array
     * @return the byte array
     */
    public static byte[] longToLittleEndian(long[] ns)
    {
        byte[] bs = new byte[8 * ns.length];
        longToLittleEndian(ns, bs, 0);
        return bs;
    }

    /**
     * Write a long array to a byte array in little-endian order.
     *
     * @param ns the long array
     * @param bs the target byte array
     * @param off the offset into the array
     */
    public static void longToLittleEndian(long[] ns, byte[] bs, int off)
    {
        for (int i = 0; i < ns.length; ++i)
        {
            longToLittleEndian(ns[i], bs, off);
            off += 8;
        }
    }

    /**
     * Write a range of a long array to a byte array in little-endian order.
     *
     * @param ns the long array
     * @param nsOff the offset into the long array
     * @param nsLen the number of longs to write
     * @param bs the target byte array
     * @param bsOff the offset into the byte array
     */
    public static void longToLittleEndian(long[] ns, int nsOff, int nsLen, byte[] bs, int bsOff)
    {
        for (int i = 0; i < nsLen; ++i)
        {
            longToLittleEndian(ns[nsOff + i], bs, bsOff);
            bsOff += 8;
        }
    }


}
