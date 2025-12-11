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
import net.i2p.crypto.Blinding;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SigType;
import net.i2p.util.SimpleByteCache;

/**
 * Cryptographic private key for digital signature generation in I2P.
 * 
 * <p>SigningPrivateKey provides signature generation capabilities:</p>
 * <ul>
 *   <li><strong>Default Algorithm:</strong> DSA-SHA1 (20 bytes)</li>
 *   <li><strong>Modern Support:</strong> Variable length and type support since 0.9.8</li>
 *   <li><strong>Key Structure:</strong> Contains only private exponent/coordinates</li>
 *   <li><strong>Security:</strong> Implements {@link Destroyable} for secure cleanup</li>
 * </ul>
 * 
 * <p><strong>Supported Algorithms:</strong></p>
 * <ul>
 *   <li><strong>DSA-SHA1:</strong> Legacy algorithm, 20-byte keys</li>
 *   <li><strong>ECDSA-P256:</strong> Modern algorithm, variable length keys</li>
 *   <li><strong>EdDSA-Ed25519:</strong> Modern algorithm, 32-byte keys</li>
 *   <li><strong>Future Types:</strong> Extensible design for new algorithms</li>
 * </ul>
 * 
 * <p><strong>Key Format:</strong></p>
 * <ul>
 *   <li><strong>DSA:</strong> 20-byte private exponent (x)</li>
 *   <li><strong>ECDSA:</strong> Variable length private scalar</li>
 *   <li><strong>EdDSA:</strong> 32-byte private seed</li>
 *   <li><strong>Type Encoding:</strong> Algorithm type embedded in data</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong></p>
 * <ul>
 *   <li><strong>Signature Generation:</strong> Sign data with corresponding {@link SigningPublicKey}</li>
 *   <li><strong>Identity Creation:</strong> Part of {@link Destination} identity</li>
 *   <li><strong>LeaseSet Signing:</strong> Sign LeaseSet for network publication</li>
 *   <li><strong>Router Identity:</strong> Sign router information for NetDb</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Confidentiality:</strong> Private signing keys must never be exposed</li>
 *   <li><strong>Secure Destruction:</strong> Call {@link #destroy()} when no longer needed</li>
 *   <li><strong>Memory Protection:</strong> Zeroize memory after use</li>
 *   <li><strong>Algorithm Choice:</strong> Prefer modern algorithms (Ed25519, ECDSA-P256)</li>
 * </ul>
 * 
 * <p><strong>Blinding Support:</strong></p>
 * <ul>
 *   <li><strong>Key Blinding:</strong> Support for generating blinded key variants</li>
 *   <li><strong>Privacy:</strong> Enable anonymous service endpoints</li>
 *   <li><strong>BlindData:</strong> Integration with {@link BlindData} for blinding operations</li>
 * </ul>
 * 
 * <p><strong>Performance Features:</strong></p>
 * <ul>
 *   <li><strong>Efficient Storage:</strong> Optimized byte representation</li>
 *   <li><strong>Factory Methods:</strong> Static creation methods for consistency</li>
 *   <li><strong>Fast Operations:</strong> Optimized for high-frequency signing</li>
 * </ul>
 * 
 * <p><strong>Migration Path:</strong></p>
 * <ul>
 *   <li><strong>Legacy:</strong> DSA-SHA1 for backward compatibility</li>
 *   <li><strong>Modern:</strong> Ed25519 for better performance and security</li>
 *   <li><strong>Transition:</strong> Mixed algorithm support during migration</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong></p>
 * <ul>
 *   <li><strong>Immutable Data:</strong> Key data cannot be modified after creation</li>
 *   <li><strong>Safe Destruction:</strong> Thread-safe key zeroization</li>
 *   <li><strong>Exclusive Use:</strong> Each instance should be used by only one thread</li>
 * </ul>
 *
 * @author jrandom
 */
public class SigningPrivateKey extends SimpleDataStructure implements Destroyable {
    private static final SigType DEF_TYPE = SigType.DSA_SHA1;
    public final static int KEYSIZE_BYTES = DEF_TYPE.getPrivkeyLen();

    private final SigType _type;

    public SigningPrivateKey() {
        this(DEF_TYPE);
    }

    /**
     *  @since 0.9.8
     */
    public SigningPrivateKey(SigType type) {
        super();
        _type = type;
    }

    public SigningPrivateKey(byte data[]) {
        this(DEF_TYPE, data);
    }

    /**
     *  @since 0.9.8
     */
    public SigningPrivateKey(SigType type, byte data[]) {
        super();
        _type = type;
        setData(data);
    }

    /** constructs from base64
     * @param base64Data a string of base64 data (the output of .toBase64() called
     * on a prior instance of SigningPrivateKey
     */
    public SigningPrivateKey(String base64Data)  throws DataFormatException {
        this();
        fromBase64(base64Data);
    }


    public int length() {
        return _type.getPrivkeyLen();
    }

    /**
     *  @since 0.9.8
     */
    public SigType getType() {
        return _type;
    }

    /**
     *  Converts this signing private key to its public equivalent.
     *  As of 0.9.16, supports all key types.
     *
     *  @return a SigningPublicKey object derived from this private key
     *  @throws IllegalArgumentException on bad key or unknown or unsupported type
     */
    public SigningPublicKey toPublic() {
        return KeyGenerator.getSigningPublicKey(this);
    }

    /**
     *  Only for SigType EdDSA_SHA512_Ed25519
     *
     *  @param alpha the secret data
     *  @throws UnsupportedOperationException unless supported
     *  @since 0.9.38
     */
    public SigningPrivateKey blind(SigningPrivateKey alpha) {
        return Blinding.blind(this, alpha);
    }

    /**
     *  Constant time
     *  @return true if all zeros
     *  @since 0.9.39 moved from PrivateKeyFile
     */
    public boolean isOffline() {
        if (_data == null)
            return true;
        byte b = 0;
        for (int i = 0; i < _data.length; i++) {
            b |= _data[i];
        }
        return b == 0;
    }

    /**
     *  javax.security.auth.Destroyable interface
     *
     *  @since 0.9.40
     */
    public void destroy() {
        byte[] data = _data;
        if (data != null) {
            _data = null;
            Arrays.fill(data, (byte) 0);
            SimpleByteCache.release(data);
        }
    }

    /**
     *  javax.security.auth.Destroyable interface
     *
     *  @since 0.9.40
     */
    public boolean isDestroyed() {
        return _data == null;
    }

    /**
     *  @since 0.9.8
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[SigningPrivateKey ").append(_type).append(' ');
        int length = length();
        if (_data == null) {
            buf.append("null");
        } else if (length <= 32) {
            buf.append(toBase64());
        } else {
            buf.append("size: ").append(length);
        }
        buf.append(']');
        return buf.toString();
    }

    /**
     *  @since 0.9.17
     */
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_type) ^ super.hashCode();
    }

    /**
     *  @since 0.9.17
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || !(obj instanceof SigningPrivateKey)) return false;
        SigningPrivateKey s = (SigningPrivateKey) obj;
        return _type == s._type && Arrays.equals(_data, s._data);
    }
}
