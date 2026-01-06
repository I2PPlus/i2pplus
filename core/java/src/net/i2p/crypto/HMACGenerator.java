package net.i2p.crypto;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.SimpleByteCache;

/**
 * Abstract base class for HMAC (Hash-based Message Authentication Code) operations.
 * 
 * This class provides the foundation for HMAC algorithm implementations
 * used throughout I2P for message authentication and integrity verification.
 * It defines the standard interface for calculating and verifying HMAC values
 * with various hash algorithms.
 * 
 * <p>Key operations include:</p>
 * <ul>
 *   <li>HMAC calculation with given key and message data</li>
 *   <li>Inline MAC verification for performance optimization</li>
 *   <li>Support for different hash algorithms (SHA-1, SHA-256)</li>
 *   <li>Integration with I2P's session key management</li>
 * </ul>
 *
 * <p><strong>Implementation Note:</strong> As of 0.9.42, this class serves
 * as an abstract base. See {@code net.i2p.router.transport.udp.SSUHMACGenerator}
 * for SSU-specific HMAC implementation and {@code SHA256Generator} for
 * Syndie-compatible HMAC operations.</p>
 *
 * @since 0.9.42
 */
public abstract class HMACGenerator {

    public HMACGenerator() {}

    /**
     * Calculate the HMAC of the data with the given key
     *
     *  @param key the session key
     *  @param data the data to HMAC
     *  @param offset the starting offset in data
     *  @param length the length of data to HMAC
     *  @param target out parameter the first 16 bytes contain the HMAC, the last 16 bytes are zero
     *  @param targetOffset offset into target to put the hmac
     *  @throws IllegalArgumentException for bad key or target too small
     */
    public abstract void calculate(SessionKey key, byte data[], int offset, int length, byte target[], int targetOffset);

    /**
     * Verify the MAC inline, reducing some unnecessary memory churn.
     *
     * @param key session key to verify the MAC with
     * @param curData MAC to verify
     * @param curOffset index into curData to MAC
     * @param curLength how much data in curData do we want to run the HMAC over
     * @param origMAC what do we expect the MAC of curData to equal
     * @param origMACOffset index into origMAC
     * @param origMACLength how much of the MAC do we want to verify
     * @throws IllegalArgumentException for bad key
     */
    public abstract boolean verify(SessionKey key, byte curData[], int curOffset, int curLength,
                                   byte origMAC[], int origMACOffset, int origMACLength);


    /**
     * 32 bytes from the byte array cache.
     * Does NOT zero.
     */
    protected byte[] acquireTmp() {
        byte rv[] = SimpleByteCache.acquire(Hash.HASH_LENGTH);
        return rv;
    }

    protected void releaseTmp(byte tmp[]) {
        SimpleByteCache.release(tmp);
    }
}
