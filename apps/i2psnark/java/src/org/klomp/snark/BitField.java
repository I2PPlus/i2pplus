/* BitField - Container of a byte array representing set and unset bits.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.util.Arrays;

/**
 * Container of a byte array representing set and unset bits for torrent piece availability.
 *
 * <p>This class manages a bit field where each bit represents whether a particular piece of a
 * torrent is available (set) or not available (unset). It's used to:
 *
 * <ul>
 *   <li>Track which pieces a peer has available
 *   <li>Track which pieces the local client has downloaded
 *   <li>Determine which pieces are still needed
 *   <li>Optimize piece selection strategies
 * </ul>
 *
 * <p>The bit field is stored efficiently in a byte array, with 8 bits per byte.
 *
 * @since 0.1.0
 */
public class BitField {

    private final byte[] bitfield;
    private final int size;
    private int count;

    /**
     * Creates a new BitField that represents the given number of unset bits.
     *
     * @param size the number of bits in the bit field
     */
    public BitField(int size) {
        this.size = size;
        int arraysize = ((size - 1) / 8) + 1;
        bitfield = new byte[arraysize];
    }

    /**
     * Creates a new BitField that represents the given number of bits as set by the given byte array.
     * This will make a copy of the array. Extra bytes will be ignored.
     *
     * @param bitfield the byte array containing the bit field data
     * @param size the number of bits represented
     * @throws IndexOutOfBoundsException if the byte array is not large enough
     */
    public BitField(byte[] bitfield, int size) {
        this.size = size;
        int arraysize = ((size - 1) / 8) + 1;
        this.bitfield = new byte[arraysize];

        // XXX - More correct would be to check that unused bits are
        // cleared or clear them explicitly ourselves.
        System.arraycopy(bitfield, 0, this.bitfield, 0, arraysize);

        for (int i = 0; i < size; i++) if (get(i)) this.count++;
    }

    /**
     * Returns the actual byte array used. Changes to this array affect this BitField. Note
     * that some bits at the end of the byte array are supposed to be always unset if they represent
     * bits bigger then the size of the bitfield.
     *
     * <p>Caller should synchronize on this and copy!
     *
     * @return the internal byte array representing the bit field
     */
    public byte[] getFieldBytes() {
        return bitfield;
    }

    /**
     * Returns the size of the BitField. The returned value is one bigger then the last valid bit
     * number (since bit numbers are counted from zero).
     *
     * @return the number of bits in the bit field
     */
    public int size() {
        return size;
    }

    /**
     * Sets the given bit to true.
     *
     * @param bit the bit index to set
     * @throws IndexOutOfBoundsException if bit is smaller then zero or bigger then or equal to size
     */
    public void set(int bit) {
        if (bit < 0 || bit >= size) throw new IndexOutOfBoundsException(Integer.toString(bit));
        int index = bit / 8;
        int mask = 128 >> (bit % 8);
        synchronized (this) {
            if ((bitfield[index] & mask) == 0) {
                count++;
                bitfield[index] |= mask;
            }
        }
    }

    /**
     * Sets the given bit to false.
     *
     * @param bit the bit index to clear
     * @throws IndexOutOfBoundsException if bit is smaller then zero or bigger then or equal to size
     * @since 0.9.22
     */
    public void clear(int bit) {
        if (bit < 0 || bit >= size) throw new IndexOutOfBoundsException(Integer.toString(bit));
        int index = bit / 8;
        int mask = 128 >> (bit % 8);
        synchronized (this) {
            if ((bitfield[index] & mask) != 0) {
                count--;
                bitfield[index] &= ~mask;
            }
        }
    }

    /**
     * Sets all bits to true.
     *
     * @since 0.9.21
     */
    public void setAll() {
        Arrays.fill(bitfield, (byte) 0xff);
        count = size;
    }

    /**
     * Returns true if the bit is set or false if it is not.
     *
     * @param bit the bit index to check
     * @return true if the bit is set, false otherwise
     * @throws IndexOutOfBoundsException if bit is smaller then zero or bigger then or equal to size
     */
    public boolean get(int bit) {
        if (bit < 0 || bit >= size) throw new IndexOutOfBoundsException(Integer.toString(bit));

        int index = bit / 8;
        int mask = 128 >> (bit % 8);
        return (bitfield[index] & mask) != 0;
    }

    /**
     * Returns the number of set bits.
     *
     * @return the count of bits that are set to true
     */
    public int count() {
        return count;
    }

    /**
     * Returns true if all bits are set.
     *
     * @return true if all bits are set (bit field is complete), false otherwise
     */
    public boolean complete() {
        return count >= size;
    }

    /**
     * Returns a hash code value for this BitField.
     *
     * @return a hash code value for this BitField
     * @since 0.9.33
     */
    @Override
    public int hashCode() {
        return (count << 16) ^ size;
    }

    /**
     * Compares this BitField to another object for equality.
     *
     * @param o the object to compare with
     * @return true if the objects are equal, false otherwise
     * @since 0.9.33
     */
    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof BitField)) return false;
        BitField bf = (BitField) o;
        return count == bf.count()
                && size == bf.size()
                && Arrays.equals(bitfield, bf.getFieldBytes());
    }

    @Override
    public String toString() {
        // Not very efficient
        StringBuilder sb = new StringBuilder("BitField(");
        sb.append(size).append(")[");
        for (int i = 0; i < size; i++)
            if (get(i)) {
                sb.append(' ');
                sb.append(i);
            }
        sb.append(" ]");

        return sb.toString();
    }
}
