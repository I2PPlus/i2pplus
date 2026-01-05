package net.i2p.crypto;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;

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
    private static final SecretKey ZERO_KEY = new HMACKey(new byte[32]);

    /**
     *  @param context unused
     */
    public HMAC256Generator(I2PAppContext context) {
        super();
        _macs = new LinkedBlockingQueue<Mac>(CACHE_SIZE);
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
    public void calculate(SessionKey key, byte data[], int offset, int length, byte target[], int targetOffset) {
        calculate(key.getData(), data, offset, length, target, targetOffset);
    }

    /**
     *  Calculate the HMAC of the data with the given key.
     *  Outputs 32 bytes to target starting at targetOffset.
     *
     *  @param key first 32 bytes used as the key
     *  @throws UnsupportedOperationException if the JVM does not support it
     *  @throws IllegalArgumentException for bad key or target too small
     *  @since 0.9.38
     */
    public void calculate(byte[] key, byte data[], int offset, int length, byte target[], int targetOffset) {
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
     *  @since 0.9.12 overrides HMACGenerator
     */
    @Override
    public boolean verify(SessionKey key, byte curData[], int curOffset, int curLength,
                          byte origMAC[], int origMACOffset, int origMACLength) {
        byte calc[] = acquireTmp();
        calculate(key, curData, curOffset, curLength, calc, 0);
        boolean eq = DataHelper.eq(calc, 0, origMAC, origMACOffset, origMACLength);
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
     *  Mac will be reset and initialized with a zero key.
     *  Package private for HKDF.
     *
     *  @since 0.9.48
     */
    void release(Mac mac) {
         if (CACHE) {
             try {
                mac.init(ZERO_KEY);
            } catch (GeneralSecurityException e) {
                return;
            }
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
     * <p><strong>Implementation Note:</strong> getEncoded() returns a copy of the
     * first 32 bytes because the Mac class requires this behavior for proper
     * operation. The full key data may be longer than 32 bytes.</p>
     *
     * @since 0.9.38
     */
    static final class HMACKey implements SecretKey {
        private final byte[] _data;

        public HMACKey(byte[] data) { _data = data; }

        @Override
        public String getAlgorithm() { return "HmacSHA256"; }
        @Override
        public byte[] getEncoded() { return Arrays.copyOf(_data, 32); }
        @Override
        public String getFormat() { return "RAW"; }
    }

}
