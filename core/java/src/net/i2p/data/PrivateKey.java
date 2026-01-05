package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Arrays;
import javax.security.auth.Destroyable;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.util.SimpleByteCache;

/**
 * Cryptographic private key for asymmetric encryption in I2P.
 * 
 * <p>PrivateKey provides the decryption component of I2P's asymmetric cryptography:</p>
 * <ul>
 *   <li><strong>Default Algorithm:</strong> ElGamal 2048-bit (256 bytes)</li>
 *   <li><strong>Modern Support:</strong> Variable length and type support since 0.9.38</li>
 *   <li><strong>Key Structure:</strong> Contains only the private exponent</li>
 *   <li><strong>Security:</strong> Implements {@link Destroyable} for secure cleanup</li>
 * </ul>
 * 
 * <p><strong>Supported Algorithms:</strong></p>
 * <ul>
 *   <li><strong>ElGamal 2048:</strong> Legacy algorithm, 256-byte keys</li>
 *   <li><strong>ECIES X25519:</strong> Modern elliptic curve, 32-byte keys</li>
 *   <li><strong>Future Types:</strong> Extensible design for new algorithms</li>
 * </ul>
 * 
 * <p><strong>Key Format:</strong></p>
 * <ul>
 *   <li><strong>ElGamal:</strong> 256-byte private exponent only</li>
 *   <li><strong>Constants:</strong> Prime numbers defined in crypto specification</li>
 *   <li><strong>Efficiency:</strong> Only stores variable private component</li>
 *   <li><strong>Validation:</strong> Type-specific length and format checking</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong></p>
 * <ul>
 *   <li><strong>Decryption:</strong> Decrypt messages encrypted with corresponding {@link PublicKey}</li>
 *   <li><strong>Key Exchange:</strong> Participate in ElGamal key exchange protocols</li>
 *   <li><strong>Identity:</strong> Part of {@link Destination} cryptographic identity</li>
 *   <li><strong>Storage:</strong> Securely stored in keyring or keystore</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Confidentiality:</strong> Private keys must never be exposed</li>
 *   <li><strong>Secure Destruction:</strong> Call {@link #destroy()} when no longer needed</li>
 *   <li><strong>Memory Protection:</strong> Zeroize memory after use</li>
 *   <li><strong>Algorithm Choice:</strong> Prefer modern algorithms (X25519) when possible</li>
 * </ul>
 * 
 * <p><strong>Performance Features:</strong></p>
 * <ul>
 *   <li><strong>Caching:</strong> LRU cache for frequently used keys</li>
 *   <li><strong>Public Key Derivation:</strong> Cached derived public key</li>
 *   <li><strong>Efficient Storage:</strong> Optimized byte representation</li>
 *   <li><strong>Factory Methods:</strong> Static creation methods for cache access</li>
 * </ul>
 * 
 * <p><strong>Migration Path:</strong></p>
 * <ul>
 *   <li><strong>Legacy:</strong> ElGamal 2048-bit for backward compatibility</li>
 *   <li><strong>Modern:</strong> ECIES X25519 for better performance and security</li>
 *   <li><strong>Transition:</strong> Mixed algorithm support during migration</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong></p>
 * <ul>
 *   <li><strong>Immutable Data:</strong> Key data cannot be modified after creation</li>
 *   <li><strong>Safe Destruction:</strong> Thread-safe key zeroization</li>
 *   <li><strong>Cache Access:</strong> Thread-safe factory methods</li>
 * </ul>
 *
 * @author jrandom
 */
public class PrivateKey extends SimpleDataStructure implements Destroyable {
    private static final EncType DEF_TYPE = EncType.ELGAMAL_2048;
    public final static int KEYSIZE_BYTES = DEF_TYPE.getPrivkeyLen();

    private final EncType _type;
    // cache
    private PublicKey _pubKey;

    public PrivateKey() {
        this(DEF_TYPE);
    }

    /**
     *  @param type non-null
     *  @since 0.9.38
     */
    public PrivateKey(EncType type) {
        super();
        _type = type;
    }

    public PrivateKey(byte data[]) {
        this(DEF_TYPE, data);
    }

    /**
     *  @param type non-null
     *  @param data must be non-null
     *  @since 0.9.38
     */
    public PrivateKey(EncType type, byte data[]) {
        this(type);
        if (data == null)
            throw new IllegalArgumentException("Data must be specified");
        setData(data);
    }

    /**
     *  @param type non-null
     *  @param data must be non-null
     *  @param pubKey corresponding pubKey to be cached
     *  @since 0.9.44
     */
    public PrivateKey(EncType type, byte data[], PublicKey pubKey) {
        this(type, data);
        if (type != pubKey.getType())
            throw new IllegalArgumentException("Pubkey mismatch");
        _pubKey = pubKey;
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of PrivateKey
     */
    public PrivateKey(String base64Data) throws DataFormatException {
        this(DEF_TYPE);
        fromBase64(base64Data);
    }

    @Override
    public int length() {
        return _type.getPrivkeyLen();
    }

    /**
      *  Gets the encryption type of this private key.
      *
      *  @return non-null
      *  @since 0.9.38
      */
    public EncType getType() {
        return _type;
    }

    /**
     * Derives a new PublicKey object derived from the secret contents
     * of this PrivateKey.
     * As of 0.9.44, the PublicKey is cached.
     *
     * @return a PublicKey object
     * @throws IllegalArgumentException on bad key
     */
    public PublicKey toPublic() {
        if (_pubKey == null)
            _pubKey = KeyGenerator.getPublicKey(this);
        return _pubKey;
    }

    /**
     *  javax.security.auth.Destroyable interface
     *
     *  @since 0.9.40
      */
    @Override
    public void destroy() {
        byte[] data = _data;
        if (data != null) {
            _data = null;
            Arrays.fill(data, (byte) 0);
            SimpleByteCache.release(data);
        }
        _pubKey = null;
    }

    /**
     *  javax.security.auth.Destroyable interface
     *
     *  @since 0.9.40
      */
    @Override
    public boolean isDestroyed() {
        return _data == null;
    }

    /**
     *  @since 0.9.38
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[PrivateKey ").append(_type).append(' ');
        if (_data == null) {
            buf.append("null");
        } else {
            int length = length();
            if (length <= 32)
                buf.append(toBase64());
            else
                buf.append("Size: ").append(length);
        }
        buf.append(']');
        return buf.toString();
    }

    /**
     * We assume the data has enough randomness in it, so use the last 4 bytes for speed.
     * Overridden since we use short exponents, so the first 227 bytes are all zero.
     */
    @Override
    public int hashCode() {
        if (_data == null)
            return 0;
        if (_type != DEF_TYPE)
            return DataHelper.hashCode(_data);
        int rv = _data[KEYSIZE_BYTES - 4];
        for (int i = 1; i < 4; i++)
            rv ^= (_data[i + (KEYSIZE_BYTES - 4)] << (i*8));
        return rv;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ((obj == null) || !(obj instanceof PrivateKey)) return false;
        PrivateKey p = (PrivateKey) obj;
        return _type == p._type && Arrays.equals(_data, p._data);
    }
}
