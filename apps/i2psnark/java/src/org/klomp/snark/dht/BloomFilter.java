package org.klomp.snark.dht;

import java.security.MessageDigest;
import net.i2p.crypto.SHA1;

/**
 * A BEP 33 bloom filter used to estimate the size of a DHT swarm from a
 * get_peers response.
 *
 * <p>The filter has 256 bytes (m = 2048 bits) and two hash functions
 * (k = 2) derived from the first four bytes of the SHA-1 of the inserted
 * value, as specified in BEP 33. In I2P the inserted values are the
 * 32-byte destination hashes of the peers rather than the IP addresses of
 * the specification, as that is how peers are identified on the I2P
 * network; both the filter builder and the estimator insert the same
 * hashes, so the resulting filters stay compatible between clients.
 *
 * <p>The number of inserted values can be estimated from the count of
 * zero bits, see {@link #estimateSize()}. The estimate is only reliable up
 * to a few thousand inserted values; the filter saturates around 8000.
 *
 * @since 0.9.71+
 */
class BloomFilter {

    /** Number of hash functions, as specified in BEP 33 */
    private static final int K = 2;

    /** Number of bits in the filter, as specified in BEP 33 */
    private static final int M = 256 * 8;

    /** Size of the filter in bytes, as specified in BEP 33 */
    static final int SIZE = M / 8;

    private final byte[] _bloom;

    /** Create a new empty filter. */
    BloomFilter() {
        _bloom = new byte[SIZE];
    }

    /**
     * Create a filter from data received in a get_peers response.
     *
     * @param data 256 bytes of filter data
     * @throws IllegalArgumentException if the data is not 256 bytes
     */
    BloomFilter(byte[] data) {
        if (data == null || data.length != SIZE) {
            throw new IllegalArgumentException("Bad bloom filter size " + (data == null ? -1 : data.length));
        }
        _bloom = data;
    }

    /**
     * Insert a value into the filter, setting the two bits at indices
     * derived from the SHA-1 of the value.
     *
     * @param data the value to insert, e.g. a 32-byte destination hash
     */
    void insert(byte[] data) {
        MessageDigest md = SHA1.getInstance();
        byte[] hash = md.digest(data);
        int index1 = (hash[0] & 0xFF) | ((hash[1] & 0xFF) << 8);
        int index2 = (hash[2] & 0xFF) | ((hash[3] & 0xFF) << 8);
        setBit(index1 % M);
        setBit(index2 % M);
    }

    /**
     * Test whether a value is probably contained in the filter.
     *
     * @param data the value to test
     * @return true if both of the value's bits are set
     */
    boolean contains(byte[] data) {
        MessageDigest md = SHA1.getInstance();
        byte[] hash = md.digest(data);
        int index1 = (hash[0] & 0xFF) | ((hash[1] & 0xFF) << 8);
        int index2 = (hash[2] & 0xFF) | ((hash[3] & 0xFF) << 8);
        return isSet(index1 % M) && isSet(index2 % M);
    }

    private void setBit(int index) {
        _bloom[index / 8] |= (byte) (0x01 << (index % 8));
    }

    private boolean isSet(int index) {
        return (_bloom[index / 8] & (0x01 << (index % 8))) != 0;
    }

    /**
     * Count the number of bits in the filter that are still zero.
     *
     * @return the zero bit count
     */
    int countZeroBits() {
        int count = 0;
        for (byte b : _bloom) {
            count += 8 - Integer.bitCount(b & 0xFF);
        }
        return count;
    }

    /**
     * Estimate the number of values inserted into the filter.
     *
     * <p>Uses the equation from BEP 33, log(c/m) / (k * log(1 - 1/m)),
     * where c is the count of zero bits. The estimate is only meaningful
     * while the filter is less than about half full; it breaks down as the
     * filter approaches 8000 inserted values.
     *
     * @return the estimated number of inserted values, 0 if the filter is full
     */
    double estimateSize() {
        int c = Math.min(M - 1, countZeroBits());
        if (c == 0) {
            return 0.0;
        }
        return Math.log((double) c / M) / (K * Math.log(1.0 - 1.0 / M));
    }

    /**
     * The filter data.
     *
     * @return 256 bytes
     */
    byte[] getData() {
        return _bloom;
    }
}
