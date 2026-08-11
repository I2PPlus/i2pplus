package net.i2p.router.crypto.ratchet;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/**
 *  8-byte session tag for ratchet protocol messages with efficient long-based storage representation for memory optimization
 *  Does not extend SessionTag or DataStructure to save space
 *
 *  @since 0.9.44
 */
public class RatchetSessionTag {
    /** Length of the tag in bytes */
    public static final int LENGTH = 8;

    private final long _data;
    /** Tag from a long value for compact 8-byte storage. */

    public RatchetSessionTag(long val) {
        _data = val;
    }

    /**
     *  First 8 bytes of the given value are copied; the reference is not kept.
     *
     *  @param val will copy the first 8 bytes. Reference will not be kept.
     */
    public RatchetSessionTag(byte[] val) {
        if (val.length < LENGTH)
            throw new IllegalArgumentException();
        _data = DataHelper.fromLong8(val, 0);
    }

    /**
     *  The tag as a byte array.
     *
     *  @return data as a byte array
     */
    public byte[] getData() {
        byte[] rv = new byte[LENGTH];
        DataHelper.toLong8(rv, 0, _data);
        return rv;
    }

    /**
     *  The tag as a long value.
     *
     *  @return data as a long value
     *  @since 0.9.46
     */
    public long getLong() {
        return _data;
    }
    /**
     *  Tag size in bytes (always 8).
     *
     *  @return the tag size in bytes (always 8)
     */
    public int length() { // NOSONAR S1845 length() is standard Java naming
        return LENGTH;
    }

    /** 12 chars */
    public String toBase64() {
        // for efficiency
        StringBuilder buf = new StringBuilder(12);
        for (int i = 58; i > 0; i -= 6) {
            buf.append(Base64.ALPHABET_I2P.charAt(((int) (_data >> i)) & 0x3f));
        }
        buf.append(Base64.ALPHABET_I2P.charAt(((int) (_data << 2)) & 0x3c));
        buf.append('=');
        return buf.toString();
    }

    /**
     * We assume the data has enough randomness in it, so use 4 bytes for speed.
     * @return whether h code is present
     */
    @Override
    public int hashCode() {
        return (int) _data;
    }

    /** Compares by long value for fast identity. */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof RatchetSessionTag)) return false;
        return _data == ((RatchetSessionTag) obj)._data;
    }

    /**
     *  Base64-encoded tag string.
     *
     *  @return base64-encoded tag string
     */
    @Override
    public String toString() {
        return "RatchetSessionTag: " + toBase64();
    }

}
