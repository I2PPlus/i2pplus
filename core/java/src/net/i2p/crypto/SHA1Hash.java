package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import net.i2p.data.DataFormatException;
import net.i2p.data.SimpleDataStructure;

/**
 * I2P data structure for SHA-1 hash values.
 * 
 * This class provides a SimpleDataStructure wrapper for 20-byte SHA-1 hashes
 * used throughout I2P for data integrity and identification purposes.
 * It extends SimpleDataStructure to support I2P's serialization framework
 * and provides efficient caching of hash codes.
 * 
 * <p>Common uses include:</p>
 * <ul>
 *   <li>Router and destination identifiers</li>
 *   <li>Message integrity verification</li>
 *   <li>Data structure indexing and lookup</li>
 * </ul>
 *
 * @since 0.8.1
 * @author zzz
 */
public class SHA1Hash extends SimpleDataStructure {
    private int _cachedHashCode;

    public final static int HASH_LENGTH = SHA1.HASH_LENGTH;

    /** @since 0.9.9 */
    public SHA1Hash() {
        super();
    }

    /** @throws IllegalArgumentException if data is not 20 bytes (null is ok) */
    public SHA1Hash(byte data[]) {
        super(data);
    }

    public int length() {
        return HASH_LENGTH;
    }

    /** @throws IllegalArgumentException if data is not 20 bytes (null is ok) */
    @Override
    public void setData(byte[] data) {
        super.setData(data);
        _cachedHashCode = super.hashCode();
    }

    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        super.readBytes(in);
        _cachedHashCode = super.hashCode();
    }

    /** a Hash is a hash, so just use the first 4 bytes for speed */
    @Override
    public int hashCode() {
        return _cachedHashCode;
    }
}
