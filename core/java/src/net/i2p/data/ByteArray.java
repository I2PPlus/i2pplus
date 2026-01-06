package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.Serializable;

/**
 * Wrap up an array of bytes so that they can be compared and placed in hashes,
 * maps, and the like.
 *
 */
public class ByteArray implements Serializable, Comparable<ByteArray> {
    private byte[] _data;
    private int _valid;
    private int _offset;

    /**
     *  Creates an empty ByteArray.
     */
    public ByteArray() {
    }

    /**
     *  Sets valid = data.length, unless data is null
     *  Sets offset = 0
     *
     * @param data the byte array, may be null
     */
    public ByteArray(byte[] data) {
        _data = data;
        _valid = (data != null ? data.length : 0);
    }

    /**
     *  Sets offset = offset
     *  Sets valid = length
     *
     * @param data the byte array, may be null but why would you do that
     * @param offset the starting offset
     * @param length the valid length
     */
    public ByteArray(byte[] data, int offset, int length) {
        _data = data;
        _offset = offset;
        _valid = length;
    }

    /**
     *  Returns the underlying byte array.
     * @return the byte data, may be null
     */
    public byte[] getData() {
        return _data;
    }

    /**
     *  Sets the underlying byte array.
     *  Warning, does not set valid
     *
     * @param data the new byte array
     */
    public void setData(byte[] data) {
        _data = data;
    }

    /**
     * Count how many of the bytes in the array are 'valid'.
     * this property does not necessarily have meaning for all byte
     * arrays.
     *
     * @return the number of valid bytes
     */
    public int getValid() { return _valid; }

    /**
     * Sets the number of valid bytes.
     *
     * @param valid the new valid count
     */
    public void setValid(int valid) { _valid = valid; }

    /**
     * Returns the offset into the byte array.
     *
     * @return the offset
     */
    public int getOffset() { return _offset; }

    /**
     * Sets the offset into the byte array.
     *
     * @param offset the new offset
     */
    public void setOffset(int offset) { _offset = offset; }

    @Override
    public final boolean equals(Object o) {
        if (o == this) return true;
        if (o == null) return false;
        if (o instanceof ByteArray) {
            ByteArray ba = (ByteArray)o;
            return compare(getData(), _offset, _valid, ba.getData(), ba.getOffset(), ba.getValid());
        }

        // Removed byte array compatibility check as it violates equals() contract
        // ByteArray.equals() should only compare with ByteArray objects
        
        return false;
    }

    private static final boolean compare(byte[] lhs, int loff, int llen, byte[] rhs, int roff, int rlen) {
        return (llen == rlen) && DataHelper.eq(lhs, loff, rhs, roff, llen);
    }

    @Override
    public final int compareTo(ByteArray ba) {
        return DataHelper.compareTo(_data, ba.getData());
    }

    @Override
    public final int hashCode() {
        return DataHelper.hashCode(getData());
    }

    @Override
    public String toString() {
        return super.toString() + "/" + DataHelper.toString(getData(), 32) + "." + _valid;
    }

    /**
     *  Returns this ByteArray as a Base64 encoded string.
     * @return the Base64 encoded string
     */
    public final String toBase64() {
        return Base64.encode(_data, _offset, _valid);
    }
}
