package net.i2p.crypto;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * HMAC-SHA256 message authentication code generator for I2P cryptographic operations.
 *
 * This class provides HMAC-SHA256 calculation using the standard JCA Mac interface,
 * ensuring compatibility with {@code javax.crypto.Mac.getInstance("HmacSHA256")}.
 * It offers both one-shot calculation and streaming operations for different use cases.
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>HMAC-SHA256 algorithm implementation for message authentication</li>
 *   <li>Thread-safe operation with Mac instance pooling</li>
 *   <li>Optimized for both small and large data processing</li>
 *   <li>Integration with I2P's session key management</li>
 * </ul>
 *
 * <p><strong>Compatibility Note:</strong> As of 0.9.12, this class
 * uses the standard javax.crypto.Mac implementation for improved security
 * and compatibility.</p>
 *
 * @since 0.9.12
 */
public final class HMAC256Generator extends HMACGenerator {

    private final LinkedBlockingQueue<Mac> _macs;

    private static final boolean CACHE = true;
    private static final int CACHE_SIZE = 8;

    /**
     * Create a new HMAC256Generator.
     *
     *  @param context unused
     */
    public HMAC256Generator(I2PAppContext context) {
        super();
        _macs = new LinkedBlockingQueue<>(CACHE_SIZE);
    }

    /**
     *  Calculate the HMAC of the data with the given key.
     *  Outputs 32 bytes to target starting at targetOffset.
     *
     *  @throws UnsupportedOperationException if the JVM does not support it
     *  @throws IllegalArgumentException for bad key or target too small
     *  @since 0.9.12 overrides HMACGenerator
     */
    @Override
    public void calculate(SessionKey key, byte[] data, int offset, int length, byte[] target, int targetOffset) {
        calculate(key.getData(), data, offset, length, target, targetOffset);
    }

    /**
     *  Calculate the HMAC of the data with the given key.
     *  Outputs 32 bytes to target starting at targetOffset.
     *
     *  @param key first 32 bytes used as the key
     *  @param data the data to calculate the HMAC over
     *  @param offset offset into data
     *  @param length number of bytes to include
     *  @param target output buffer
     *  @param targetOffset offset into target
     *  @throws UnsupportedOperationException if the JVM does not support it
     *  @throws IllegalArgumentException for bad key or target too small
     *  @since 0.9.38
     */
    public void calculate(byte[] key, byte[] data, int offset, int length, byte[] target, int targetOffset) {
        try {
            Mac mac = acquire();
            SecretKey keyObj = new HMACKey(key);
            mac.init(keyObj);
            mac.update(data, offset, length);
            mac.doFinal(target, targetOffset);
            release(mac);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("HmacSHA256", e);
        }
    }

    /**
     *  Verify the MAC inline, reducing some unnecessary memory churn.
     *
     *  @param key session key to verify the MAC with
     *  @param curData MAC to verify
     *  @param curOffset index into curData to MAC
     *  @param curLength how much data in curData do we want to run the HMAC over
     *  @param origMAC what do we expect the MAC of curData to equal
     *  @param origMACOffset index into origMAC
     *  @param origMACLength how much of the MAC do we want to verify, use 32 for HMAC256
     *  @return true if the MAC matches
     *  @since 0.9.12 overrides HMACGenerator
     */
    @Override
    public boolean verify(SessionKey key, byte[] curData, int curOffset, int curLength, byte[] origMAC, int origMACOffset, int origMACLength) {
        byte[] calc = acquireTmp();
        calculate(key, curData, curOffset, curLength, calc, 0);
        boolean eq = DataHelper.eqCT(calc, 0, origMAC, origMACOffset, origMACLength);
        releaseTmp(calc);
        return eq;
    }

    /**
     *  Package private for HKDF.
     *
     *  @return cached or Mac.getInstance("HmacSHA256")
     *  @since 0.9.48
     */
    Mac acquire() {
        Mac rv = _macs.poll();
        if (rv == null) {
            try {
                rv = Mac.getInstance("HmacSHA256");
            } catch (NoSuchAlgorithmException e) {
                throw new UnsupportedOperationException("HmacSHA256", e);
            }
        }
        return rv;
    }

    /**
     *  Release a Mac back to the pool.
     *  Mac will be reset, discarding any accumulated message state.
     *  Per the JCA contract, {@link Mac#reset()} "resets this Mac object to
     *  the state it was in when previously initialized via a call to
     *  init(Key)", so it clears the message state without re-running the
     *  ipad/opad key schedule that {@link Mac#init(java.security.Key)} does.
     *  That is all a pooled Mac needs, because every caller of acquire() must
     *  init() before use, and engineInit() overwrites all 64 bytes of both
     *  k_ipad and k_opad, so the previous key schedule cannot leak into the
     *  next operation.
     *  Package private for HKDF.
     *
     *  @param mac the Mac to release
     *  @since 0.9.48
     */
    void release(Mac mac) {
        if (CACHE) {
            mac.reset();
            _macs.offer(mac);
        }
    }

    /**
     * Performance-optimized SecretKey implementation for HMAC operations.
     *
     * This class provides an efficient SecretKey implementation that avoids
     * unnecessary key data copying during construction for improved performance.
     * Unlike standard SecretKeySpec, this implementation maintains a direct reference
     * to the key data while maintaining compatibility with Mac operations.
     *
     * <p><strong>Implementation Note:</strong> HmacSHA256 only uses the first 32
     * bytes of the key, so getEncoded() returns exactly those 32 bytes (zero padded
     * if the caller supplied a shorter key); the full key data may be longer than
     * 32 bytes. The copy is mandatory rather than an optimization: the JDK's
     * HmacCore.engineInit() zeroes the array returned by getEncoded() once it has
     * consumed it, so handing out the internal array would destroy the caller's
     * key. This also means the result must never be cached - a second init() with
     * the same key object would read back an all-zero key.</p>
     *
     * @since 0.9.38
     */
    static final class HMACKey implements SecretKey {
        private final byte[] _data;

        /**
         * Key data.
         * @param data the key data
         */
        public HMACKey(byte[] data) {
            _data = data;
        }

        @Override
        public String getAlgorithm() {
            return "HmacSHA256";
        }

        @Override
        public byte[] getEncoded() {
            return Arrays.copyOf(_data, 32);
        }

        @Override
        public String getFormat() {
            return "RAW";
        }
    }
}
