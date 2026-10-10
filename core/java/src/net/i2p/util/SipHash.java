package net.i2p.util;

import net.i2p.crypto.SipHashInline;

/**
 *  Wrapper around SipHashInline with constant per-JVM keys
 *
 *  @since 0.9.5
 */
public abstract class SipHash {
    private static final long K0 = RandomSource.getInstance().nextLong();
    private static final long K1 = RandomSource.getInstance().nextLong();

    /**
     * Constructor for subclasses that hash with their own key rather than the per-JVM key.
     */
    public SipHash() {}


    /**
     * Hash a whole array.
     *
     * @param data non-null
     * @return the 64-bit SipHash-2-4 digest of the array under this JVM's random keys
     */
    public static long digest(byte[] data) {
        return SipHashInline.hash24(K0, K1, data);
    }

    /**
     * Hash a region of an array.
     *
     * @param data non-null
     * @param off the index in data at which the hashed region begins
     *  @param len the number of bytes to hash, counting from off
     *  @return the 64-bit SipHash-2-4 digest of that region under this JVM's random keys
     */
    public static long digest(byte[] data, int off, int len) {
        return SipHashInline.hash24(K0, K1, data, off, len);
    }

    /**
     *  Secure replacement for DataHelper.hashCode(byte[]);
     *  caching recommended
     *
     *  @param data may be null
     *  @return whether h code is present
     */
    public static int hashCode(byte[] data) {
        if (data == null) return 0;
        return (int) SipHashInline.hash24(K0, K1, data);
    }

}
