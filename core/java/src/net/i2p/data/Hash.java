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
 * A 32-byte SHA-256 hash, used throughout I2P as a destination, router, or
 * tunnel identifier and as an integrity value for hashed data.
 *
 * <p>Creating a Hash:</p>
 * <ul>
 *   <li>{@link #create(byte[])} and its siblings go through a static
 *       {@link SDSCache} and are the preferred way to obtain one. The cache is
 *       keyed on the first 4 bytes of the data and holds weak references, so
 *       entries disappear under memory pressure; its maximum size is scaled by
 *       the JVM's available memory. On a cache hit the byte array passed in is
 *       handed back to {@link net.i2p.util.SimpleByteCache} for reuse and must not be
 *       touched afterwards.</li>
 *   <li>{@code new Hash(byte[])} bypasses the cache entirely. Use create() when
 *       the same hash may be built repeatedly.</li>
 *   <li>{@link #create(InputStream)} reads exactly 32 bytes from the stream and
 *       wraps them; it does not hash the stream. To hash arbitrary data, use
 *       {@link #calculateHash()} or a SHA256Generator.</li>
 * </ul>
 *
 * <p>Rendering:</p>
 * <ul>
 *   <li>{@link #toBase64()} caches its result in a volatile field.</li>
 *   <li>{@link #toBase32()} is not cached; it encodes on every call.</li>
 * </ul>
 *
 * <p>Comparison:</p>
 * <ul>
 *   <li>{@link #hashCode()} is the precomputed value of the first 4 bytes, held
 *       in a volatile field. It is not constant-time.</li>
 *   <li>equals() is inherited from {@link SimpleDataStructure} and is
 *       {@link java.util.Arrays#equals(byte[], byte[])} - not constant-time, and
 *       not class-specific, so another 32-byte SimpleDataStructure such as a
 *       {@link SessionKey} holding the same bytes compares equal to a Hash.</li>
 * </ul>
 *
 * <p>Thread safety:</p>
 * <ul>
 *   <li>The data cannot be reassigned once set, so a Hash that is safely
 *       published (constructed and then handed to other threads) can be shared
 *       freely; the derived {@code _base64ed} and {@code _cachedHashCode} fields
 *       are volatile. {@link #_data} itself is neither final nor volatile, so a
 *       Hash built by a constructor and published through an unsynchronized
 *       data structure may expose stale data.</li>
 *   <li>{@link #getData()} hands out the backing array, so a caller that
 *       mutates it corrupts the cached hash code and any byte cache that shares
 *       the array.</li>
 * </ul>
 *
 * @author jrandom
 */
@SuppressWarnings({"PMD.OverrideBothEqualsAndHashcode", "checkstyle:EqualsHashCode"})
public class Hash extends SimpleDataStructure {
    private volatile String _base64ed;
    private volatile int _cachedHashCode;

    /** Length of SHA-256 hash in bytes. */
    public static final int HASH_LENGTH = 32;
    /** Placeholder all-zero hash for testing. */
    public static final Hash FAKE_HASH = new Hash(new byte[HASH_LENGTH]);
    /** Initial cache capacity, scaled by available memory by SDSCache. */
    private static final int CACHE_SIZE = 2048;

    private static final SDSCache<Hash> _cache = new SDSCache<>(Hash.class, HASH_LENGTH, CACHE_SIZE);

    /**
     *  Pull from cache or return new
     *
     *  WARNING - If the SDS is found in the cache, the passed-in
     *  byte array will be returned to the SimpleByteCache for reuse.
     *  Do NOT save a reference to the passed-in data, or use or modify it,
     *  after this call.
     *
     *  Ignore this warning and you WILL corrupt the cache or other data structures.
     *
     * @return the cached or new hash
     * @throws IllegalArgumentException if data is not the correct number of bytes
     * @since 0.8.3
     */
    public static Hash create(byte[] data) {
        return _cache.get(data);
    }

    /**
     * Pull from cache or return new
     *
     * @return the cached or new hash
     * @throws ArrayIndexOutOfBoundsException if not enough bytes
     * @since 0.8.3
     */
    public static Hash create(byte[] data, int off) {
        return _cache.get(data, off);
    }

    /**
     * Pull from cache or return new
     *
     * @return the cached or new hash
     * @since 0.8.3
     */
    public static Hash create(InputStream in) throws IOException {
        return _cache.get(in);
    }

    /** Create an empty hash (data is null) */
    public Hash() {
        super();
    }

    /**
     * IllegalArgumentException if data is not 32 bytes (null is ok).
     * @throws IllegalArgumentException if data is not 32 bytes (null is ok)
     */
    public Hash(byte[] data) {
        super();
        setData(data);
    }

    /** Hash length in bytes. */
    @Override
    public int length() {
        return HASH_LENGTH;
    }

    /**
     * IllegalArgumentException if data is not 32 bytes (null is ok).
     * @throws IllegalArgumentException if data is not 32 bytes (null is ok)
     */
    @Override
    public void setData(byte[] data) {
        super.setData(data);
        _base64ed = null;
        _cachedHashCode = super.hashCode();
    }

    /** Reads the hash from an input stream. */
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        super.readBytes(in);
        _base64ed = null;
        _cachedHashCode = super.hashCode();
    }

    /**
     * A Hash is a hash, so just use the first 4 bytes for speed.
     * Precomputed at data-assignment time, so this does not read the data.
     */
    @Override
    public int hashCode() {
        return _cachedHashCode;
    }

    /** Returns the hash as a Base64 string. Cached after the first call. */
    @Override
    public String toBase64() {
        if (_base64ed == null) {
            _base64ed = super.toBase64();
        }
        return _base64ed;
    }

    /**
     *  The .b32.i2p form of the hash. Encoded on every call - unlike
     *  toBase64(), the result is not cached.
     *
     *  @return "{52 chars}.b32.i2p" or null if data not set.
     *  @since 0.9.25
     */
    public String toBase32() {
        if (_data == null) return null;
        return Base32.encode(_data) + ".b32.i2p";
    }

    /**
     *  Drop the shared cache of Hash instances, so cached instances become
     *  eligible for collection. Hashes already handed to callers are unaffected,
     *  and the byte arrays the cached instances held are not returned to the
     *  byte cache.
     *
     *  @since 0.9.17
     */
    public static void clearCache() {
        _cache.clear();
    }
}
