package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Cryptographic session key for symmetric encryption in I2P.
 * 
 * <p>SessionKey provides symmetric encryption capabilities for I2P communications:</p>
 * <ul>
 *   <li><strong>Fixed Size:</strong> 32-byte key suitable for modern symmetric algorithms</li>
 *   <li><strong>Session-Based:</strong> Temporary keys for message encryption/decryption</li>
 *   <li><strong>Algorithm Agnostic:</strong> Can be used with various symmetric ciphers</li>
 *   <li><strong>Efficient Storage:</strong> Optimized for high-frequency operations</li>
 * </ul>
 * 
 * <p><strong>Key Generation:</strong></p>
 * <ul>
 *   <li><strong>Random Creation:</strong> Use {@code I2PAppContext.keyGenerator().generateSessionKey()}</li>
 *   <li><strong>Cryptographically Secure:</strong> Generated using cryptographically strong randomness</li>
 *   <li><strong>Factory Methods:</strong> Static creation methods for convenience</li>
 *   <li><strong>Invalid Key:</strong> Pre-defined all-zero key for special cases</li>
 * </ul>
 * 
 * <p><strong>Usage Patterns:</strong></p>
 * <ul>
 *   <li><strong>Message Encryption:</strong> Encrypt I2NP messages and I2CP data</li>
 *   <li><strong>Tunnel Encryption:</strong> Protect tunnel payload contents</li>
 *   <li><strong>Session Establishment:</strong> Derive from key exchange protocols</li>
 *   <li><strong>Temporary Storage:</strong> Short-lived keys for enhanced security</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Key Protection:</strong> Never expose session keys to unauthorized parties</li>
 *   <li><strong>Secure Generation:</strong> Use cryptographically secure random sources</li>
 *   <li><strong>Key Rotation:</strong> Change keys regularly to limit exposure</li>
 *   <li><strong>Memory Security:</strong> Zeroize memory when keys are no longer needed</li>
 * </ul>
 * 
 * <p><strong>Performance Features:</strong></p>
 * <ul>
 *   <li><strong>Prepared Keys:</strong> Cache for algorithm-specific prepared keys</li>
 *   <li><strong>Efficient Storage:</strong> Optimized byte array representation</li>
 *   <li><strong>Fast Operations:</strong> Quick access and comparison methods</li>
 *   <li><strong>Memory Management:</strong> Careful allocation and cleanup</li>
 * </ul>
 * 
 * <p><strong>Integration:</strong></p>
 * <ul>
 *   <li><strong>I2P Client:</strong> Used for end-to-end encryption in I2CP</li>
 *   <li><strong>Router Operations:</strong> Tunnel and message encryption</li>
 *   <li><strong>Garlic Encryption:</strong> Multi-layer message protection</li>
 *   <li><strong>ElGamal Integration:</strong> Combined with asymmetric encryption for key exchange</li>
 * </ul>
 * 
 * <p><strong>Constants:</strong></p>
 * <ul>
 *   <li>{@link #KEYSIZE_BYTES} - Standard 32-byte key length</li>
 *   <li>{@link #INVALID_KEY} - All-zero key for invalid/placeholder use</li>
 * </ul>
 * 
 * <p><strong>Best Practices:</strong></p>
 * <ul>
 *   <li><strong>Unique Sessions:</strong> Use different keys for different communication sessions</li>
 *   <li><strong>Limited Lifetime:</strong> Replace keys after reasonable time period</li>
 *   <li><strong>Secure Disposal:</strong> Clear key data when session ends</li>
 *   <li><strong>Algorithm Selection:</strong> Use appropriate symmetric cipher for key size</li>
 * </ul>
 *
 * @author jrandom
 */
public class SessionKey extends SimpleDataStructure {
    private Object _preparedKey;

    public final static int KEYSIZE_BYTES = 32;
    /** A key with all zeroes in the data */
    public static final SessionKey INVALID_KEY = new SessionKey(new byte[KEYSIZE_BYTES]);

    public SessionKey() {
        super();
    }

    public SessionKey(byte data[]) {
        super(data);
    }

    public int length() {
        return KEYSIZE_BYTES;
    }

    /**
     * Sets the data.
     * @param data 32 bytes, or null
     * @throws IllegalArgumentException if data is not the legal number of bytes (but null is ok)
     * @throws RuntimeException if data already set.
     */
    @Override
    public void setData(byte[] data) {
        super.setData(data);
    }

    /**
     * retrieve an internal representation of the session key, as known
     * by the AES engine used.  this can be reused safely
     */
    public Object getPreparedKey() { return _preparedKey; }
    public void setPreparedKey(Object obj) { _preparedKey = obj; }
}
