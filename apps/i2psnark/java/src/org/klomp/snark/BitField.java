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
 * <p>This class manages a bit field where each bit represents whether a particular 
 * piece of a torrent is available (set) or not available (unset). It's used to:
 * <ul>
 * <li>Track which pieces a peer has available</li>
 * <li>Track which pieces the local client has downloaded</li>
 * <li>Determine which pieces are still needed</li>
 * <li>Optimize piece selection strategies</li>
 * </ul>
 * </p>
 * 
 * <p>The bit field is stored efficiently in a byte array, with 8 bits per byte.</p>
 * 
 * @since 0.1.0
 */
public class BitField
{

  private final byte[] bitfield;
  private final int size;
  private int count;

  /**
   * Creates a new BitField that represents <code>size</code> unset bits.
   */
  public BitField(int size)
  {
    this.size = size;
    int arraysize = ((size-1)/8)+1;
    bitfield = new byte[arraysize];
  }

  /**
   * Creates a new BitField that represents <code>size</code> bits
   * as set by the given byte array. This will make a copy of the array.
   * Extra bytes will be ignored.
   *
   * @throws IndexOutOfBoundsException if give byte array is not large
   * enough.
   */
  public BitField(byte[] bitfield, int size)
  {
    this.size = size;
    int arraysize = ((size-1)/8)+1;
    this.bitfield = new byte[arraysize];

    // XXX - More correct would be to check that unused bits are
    // cleared or clear them explicitly ourselves.
    System.arraycopy(bitfield, 0, this.bitfield, 0, arraysize);

    for (int i = 0; i < size; i++)
      if (get(i))
        this.count++;
  }

  /**
   * This returns the actual byte array used.  Changes to this array
   * affect this BitField.  Note that some bits at the end of the byte
   * array are supposed to be always unset if they represent bits
   * bigger then the size of the bitfield.
   *
   * Caller should synch on this and copy!
   */
  public byte[] getFieldBytes()
  {
    return bitfield;
  }

  /**
   * Return the size of the BitField. The returned value is one bigger
   * then the last valid bit number (since bit numbers are counted
   * from zero).
   */
  public int size()
  {
    return size;
  }

  /**
   * Sets the given bit to true.
   *
   * @throws IndexOutOfBoundsException if bit is smaller then zero
   * bigger then size (inclusive).
   */
  public void set(int bit)
  {
    if (bit < 0 || bit >= size)
      throw new IndexOutOfBoundsException(Integer.toString(bit));
    int index = bit/8;
    int mask = 128 >> (bit % 8);
    synchronized(this) {
        if ((bitfield[index] & mask) == 0) {
            count++;
            bitfield[index] |= mask;
        }
    }
  }

  /**
   * Sets the given bit to false.
   *
   * @throws IndexOutOfBoundsException if bit is smaller then zero
   * bigger then size (inclusive).
   * @since 0.9.22
   */
  public void clear(int bit)
  {
    if (bit < 0 || bit >= size)
      throw new IndexOutOfBoundsException(Integer.toString(bit));
    int index = bit/8;
    int mask = 128 >> (bit % 8);
    synchronized(this) {
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
   * Return true if the bit is set or false if it is not.
   *
   * @throws IndexOutOfBoundsException if bit is smaller then zero
   * bigger then size (inclusive).
   */
  public boolean get(int bit)
  {
    if (bit < 0 || bit >= size)
      throw new IndexOutOfBoundsException(Integer.toString(bit));

    int index = bit/8;
    int mask = 128 >> (bit % 8);
    return (bitfield[index] & mask) != 0;
  }

  /**
   * Return the number of set bits.
   */
  public int count()
  {
    return count;
  }

  /**
   * Return true if all bits are set.
   */
  public boolean complete()
  {
    return count >= size;
  }

  /** @since 0.9.33 */
  @Override
  public int hashCode() {
      return (count << 16) ^ size;
  }

  /** @since 0.9.33 */
  @Override
  public boolean equals(Object o) {
      if (o == null || !(o instanceof BitField))
          return false;
      BitField bf = (BitField) o;
      return count == bf.count() &&
             size == bf.size() &&
             Arrays.equals(bitfield, bf.getFieldBytes());
  }

  @Override
  public String toString()
  {
    // Not very efficient
    StringBuilder sb = new StringBuilder("BitField(");
    sb.append(size).append(")[");
    for (int i = 0; i < size; i++)
      if (get(i))
        {
          sb.append(' ');
          sb.append(i);
        }
    sb.append(" ]");

    return sb.toString();
  }

}
