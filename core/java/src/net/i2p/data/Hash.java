package net.i2p.data;

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

/**
 * SHA-256 hash implementation for I2P data identification and integrity.
 * 
 * <p>Hash provides cryptographic hash functionality throughout I2P:</p>
 * <ul>
 *   <li><strong>Algorithm:</strong> SHA-256 cryptographic hash function</li>
 *   <li><strong>Fixed Size:</strong> Always 32 bytes (256 bits)</li>
 *   <li><strong>Uniqueness:</strong> Extremely low collision probability</li>
 *   <li><strong>Standardized:</strong> Consistent hash across all I2P components</li>
 * </ul>
 * 
 * <p><strong>Key Uses:</strong></p>
 * <ul>
 *   <li><strong>Identification:</strong> Unique identifiers for destinations and routers</li>
 *   <li><strong>Integrity:</strong> Verify data hasn't been modified or corrupted</li>
 *   <li><strong>Indexing:</strong> Fast lookup keys in network database (NetDb)</li>
 *   <li><strong>Routing:</strong> Keys for distributed hash table (DHT) operations</li>
 *   <li><strong>Caching:</strong> Efficient storage and retrieval of frequently used hashes</li>
 * </ul>
 * 
 * <p><strong>Performance Features:</strong></p>
 * <ul>
 *   <li><strong>LRU Caching:</strong> Least-recently-used cache with size limits</li>
 *   <li><strong>Factory Methods:</strong> Static creation methods for cache efficiency</li>
 *   <li><strong>Efficient Comparison:</strong> Optimized equals() and hashCode() methods</li>
 *   <li><strong>Base32 Conversion:</strong> Cached .b32.i2p address generation</li>
 *   <li><strong>Memory Management:</strong> Automatic cache size adjustment</li>
 * </ul>
 * 
 * <p><strong>Security Properties:</strong></p>
 * <ul>
 *   <li><strong>Cryptographic Strength:</strong> SHA-256 provides 128-bit security against collisions</li>
 *   <li><strong>Preimage Resistance:</strong> Computationally infeasible to reverse</li>
 *   <li><strong>Second Preimage:</strong> Computationally infeasible to find similar inputs</li>
 *   <li><strong>Collision Resistance:</strong> Practically impossible to find colliding inputs</li>
 * </ul>
 * 
 * <p><strong>Common Operations:</strong></p>
 * <ul>
 *   <li><strong>Data Hashing:</strong> Calculate hash of any byte array or data structure</li>
 *   <li><strong>Stream Hashing:</strong> Incremental hashing of large data streams</li>
 *   <li><strong>File Verification:</strong> Verify file integrity using hash comparison</li>
 *   <li><strong>Address Generation:</strong> Convert to Base32 for .b32.i2p addresses</li>
 * </ul>
 * 
 * <p><strong>Constants:</strong></p>
 * <ul>
 *   <li>{@link #HASH_LENGTH} - Standard 32-byte hash size</li>
 *   <li>{@link #FAKE_HASH} - All-zero hash for testing/placeholder use</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong></p>
 * <ul>
 *   <li><strong>Immutable Data:</strong> Hash data cannot be modified after creation</li>
 *   <li><strong>Thread-Safe Cache:</strong> Static factory methods are thread-safe</li>
 *   <li><strong>Safe Sharing:</strong> Instances can be safely shared between threads</li>
 * </ul>
 * 
 * <p><strong>Best Practices:</strong></p>
 * <ul>
 *   <li><strong>Factory Methods:</strong> Use static create() methods for cache efficiency</li>
 *   <li><strong>Hash Verification:</strong> Always verify critical data hashes</li>
 *   <li><strong>Constant Time:</strong> Use hash comparison for data integrity checks</li>
 *   <li><strong>Memory Efficiency:</strong> Reuse hash instances when possible</li>
 * </ul>
 *
 * @author jrandom
 */
public class Hash extends SimpleDataStructure {
    private volatile String _base64ed;
    private volatile int _cachedHashCode;

    public final static int HASH_LENGTH = 32;
    public final static Hash FAKE_HASH = new Hash(new byte[HASH_LENGTH]);
    private static final int CACHE_SIZE = 2048;

    private static final SDSCache<Hash> _cache = new SDSCache<Hash>(Hash.class, HASH_LENGTH, CACHE_SIZE);

    /**
     * Pull from cache or return new
     *
     *  WARNING - If the SDS is found in the cache, the passed-in
     *  byte array will be returned to the SimpleByteCache for reuse.
     *  Do NOT save a reference to the passed-in data, or use or modify it,
     *  after this call.
     *
     *  Ignore this warning and you WILL corrupt the cache or other data structures.
     *
     * @throws IllegalArgumentException if data is not the correct number of bytes
     * @since 0.8.3
     */
    public static Hash create(byte[] data) {
        return _cache.get(data);
    }

    /**
     * Pull from cache or return new
     * @throws ArrayIndexOutOfBoundsException if not enough bytes
     * @since 0.8.3
     */
    public static Hash create(byte[] data, int off) {
        return _cache.get(data, off);
    }

    /**
     * Pull from cache or return new
     * @since 0.8.3
     */
    public static Hash create(InputStream in) throws IOException {
        return _cache.get(in);
    }

    public Hash() {
        super();
    }

    /** @throws IllegalArgumentException if data is not 32 bytes (null is ok) */
    public Hash(byte data[]) {
        super();
        setData(data);
    }

    @Override
    public int length() {
        return HASH_LENGTH;
    }

    /** @throws IllegalArgumentException if data is not 32 bytes (null is ok) */
    @Override
    public void setData(byte[] data) {
        super.setData(data);
        _base64ed = null;
        _cachedHashCode = super.hashCode();
    }

    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        super.readBytes(in);
        _base64ed = null;
        _cachedHashCode = super.hashCode();
    }

    /** a Hash is a hash, so just use the first 4 bytes for speed */
    @Override
    public int hashCode() {
        return _cachedHashCode;
    }

    @Override
    public String toBase64() {
        if (_base64ed == null) {
            _base64ed = super.toBase64();
        }
        return _base64ed;
    }

    /**
     *  For convenience.
     *  @return "{52 chars}.b32.i2p" or null if data not set.
     *  @since 0.9.25
     */
    public String toBase32() {
        if (_data == null)
            return null;
        return Base32.encode(_data) + ".b32.i2p";
    }

    /** Clear the hash cache.
     *  @since 0.9.17
     */
    public static void clearCache() {
        _cache.clear();
    }
}
