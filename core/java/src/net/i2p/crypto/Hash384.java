package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * No warranty of any kind, either expressed or implied.
 */

import net.i2p.data.SimpleDataStructure;

/**
 * SHA-384 secure hash algorithm implementation.
 * 
 * This class provides the SHA-384 hash algorithm as specified in FIPS PUB 180-4,
 * producing a 48-byte (384-bit) message digest. SHA-384 is part of the
 * SHA-2 family and offers higher security than SHA-256 with similar performance.
 * 
 * <p>Common uses in I2P include:</p>
 * <ul>
 *   <li>Enhanced security requirements for sensitive data</li>
 *   <li>Digital signature generation with longer hash outputs</li>
 *   <li>Cryptographic key derivation and verification</li>
 * </ul>
 *
 * @since 0.9.8
 */
public class Hash384 extends SimpleDataStructure {

    public final static int HASH_LENGTH = 48;

    public Hash384() {
        super();
    }

    /** @throws IllegalArgumentException if data is not correct length (null is ok) */
    public Hash384(byte data[]) {
        super(data);
    }

    public int length() {
        return HASH_LENGTH;
    }
}
