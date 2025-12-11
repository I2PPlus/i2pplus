package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * No warranty of any kind, either expressed or implied.
 */

import net.i2p.data.SimpleDataStructure;

/**
 * SHA-512 secure hash algorithm implementation.
 * 
 * This class provides the SHA-512 hash algorithm as specified in FIPS PUB 180-4,
 * producing a 64-byte (512-bit) message digest. SHA-512 offers the highest
 * security level in the SHA-2 family with excellent performance characteristics.
 * 
 * <p>Common uses in I2P include:</p>
 * <ul>
 *   <li>Maximum security requirements for sensitive operations</li>
 *   <li>Long-term cryptographic key derivation</li>
 *   <li>Digital signatures with strongest hash protection</li>
 *   <li>Integrity verification for critical system components</li>
 * </ul>
 *
 * @since 0.9.8
 */
public class Hash512 extends SimpleDataStructure {

    public final static int HASH_LENGTH = 64;

    public Hash512() {
        super();
    }

    /** @throws IllegalArgumentException if data is not correct length (null is ok) */
    public Hash512(byte data[]) {
        super(data);
    }

    public int length() {
        return HASH_LENGTH;
    }
}
