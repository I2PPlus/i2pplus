package net.i2p.data;

/*
 * Public domain
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import net.i2p.crypto.SHA256Generator;

/**
 * Base class for I2P data structures containing a single fixed-length byte array.
 * 
 * <p>SimpleDataStructure provides efficient storage and caching for fixed-size data:</p>
 * <ul>
 *   <li><strong>Fixed Length:</strong> Each subclass defines exact byte array size</li>
 *   <li><strong>Caching Support:</strong> Built-in LRU caching for frequently used objects</li>
 *   <li><strong>Immutability:</strong> Data becomes immutable after first non-null assignment</li>
 *   <li><strong>Memory Efficient:</strong> Optimized for high-performance scenarios</li>
 * </ul>
 * 
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *   <li><strong>Length Specification:</strong> Abstract {@link #length()} method defines size</li>
 *   <li><strong>Data Access:</strong> Direct byte array access via {@link #getData()}</li>
 *   <li><strong>Serialization:</strong> Standard read/write operations for streams</li>
 *   <li><strong>Encoding:</strong> Base64 and byte array conversions</li>
 * </ul>
 * 
 * <p><strong>Caching System:</strong></p>
 * <ul>
 *   <li><strong>LRU Cache:</strong> Least-recently-used caching with size limits</li>
 *   <li><strong>Static Factories:</strong> {@code create()} methods for cache access</li>
 *   <li><strong>Efficient Lookup:</strong> First 4 bytes used as cache index</li>
 *   <li><strong>Memory Management:</strong> Automatic cache size adjustment based on memory</li>
 * </ul>
 * 
 * <p><strong>Immutability Model:</strong></p>
 * <ul>
 *   <li><strong>Initial State:</strong> Can be created with null data</li>
 *   <li><strong>First Assignment:</strong> setData(), readBytes(), fromByteArray(), fromBase64()</li>
 *   <li><strong>Immutable After:</strong> Once non-null data is set, cannot be changed</li>
 *   <li><strong>Protection:</strong> Subsequent modifications throw {@link RuntimeException}</li>
 * </ul>
 * 
 * <p><strong>Usage Patterns:</strong></p>
 * <ul>
 *   <li><strong>Creation:</strong> Use static factory methods for cache efficiency</li>
 *   <li><strong>Comparison:</strong> Efficient equals() and hashCode() implementations</li>
 *   <li><strong>Storage:</strong> Minimal memory footprint for large collections</li>
 *   <li><strong>Network:</strong> Fast serialization for protocol messages</li>
 * </ul>
 * 
 * <p><strong>Performance Optimizations:</strong></p>
 * <ul>
 *   <li><strong>No DataStructureImpl:</strong> As of 0.9.48, extends DataStructure directly</li>
 *   <li><strong>Byte Caching:</strong> Shared byte arrays via SimpleByteCache</li>
 *   <li><strong>Reduced Overhead:</strong> Minimal object size and memory usage</li>
 *   <li><strong>Fast Hashing:</strong> Optimized hashCode() for collections</li>
 * </ul>
 * 
 * <p><strong>Common Subclasses:</strong></p>
 * <ul>
 *   <li>{@link Hash} - 32-byte SHA-256 hashes</li>
 *   <li>{@link PublicKey} - Variable-length encryption keys</li>
 *   <li>{@link PrivateKey} - Variable-length decryption keys</li>
 *   <li>{@link SigningPublicKey} - Variable-length signing keys</li>
 *   <li>{@link SigningPrivateKey} - Variable-length signing private keys</li>
 *   <li>{@link SessionKey} - 32-byte session encryption keys</li>
 *   <li>{@link Signature} - Variable-length digital signatures</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong></p>
 * <ul>
 *   <li><strong>Immutable Data:</strong> Thread-safe after data is set</li>
 *   <li><strong>Static Caches:</strong> Thread-safe LRU cache implementation</li>
 *   <li><strong>Factory Methods:</strong> Thread-safe object creation</li>
 * </ul>
 * 
 * <p><strong>Evolution:</strong></p>
 * <ul>
 *   <li><strong>0.8.2:</strong> Initial implementation with retrofitted classes</li>
 *   <li><strong>0.8.3:</strong> Added caching support and immutability</li>
 *   <li><strong>0.9.48:</strong> Removed DataStructureImpl inheritance for space savings</li>
 * </ul>
 *
 * @since 0.8.2
 * @author zzz
 */
public abstract class SimpleDataStructure implements DataStructure {
    protected byte[] _data;

    /** A new instance with the data set to null. Call readBytes(), setData(), or fromByteArray() after this to set the data */
    public SimpleDataStructure() {
    }

    /** @throws IllegalArgumentException if data is not the legal number of bytes (but null is ok) */
    public SimpleDataStructure(byte data[]) {
        setData(data);
    }

    /**
     * The legal length of the byte array in this data structure
     * @since 0.8.2
     */
    abstract public int length();

    /**
     * Get the data reference (not a copy)
     * @return the byte array, or null if unset
     */
    public byte[] getData() {
        return _data;
    }

    /**
     * Sets the data.
     * @param data of correct length, or null
     * @throws IllegalArgumentException if data is not the legal number of bytes (but null is ok)
     * @throws RuntimeException if data already set.
     */
    public void setData(byte[] data) {
        if (_data != null)
            throw new RuntimeException("Data already set");
        if (data != null && data.length != length())
            throw new IllegalArgumentException("Bad data length: " + data.length + "; required: " + length());
        _data = data;
    }

    /**
     * Sets the data.
      * @param in the stream to read
      * @throws RuntimeException if data already set.
      */
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_data != null)
            throw new RuntimeException("Data already set");
        int length = length();
        _data = new byte[length];
        // Throws on incomplete read
        read(in, _data);
    }

    /**
     * Repeated reads until the buffer is full or IOException is thrown
     *
     * @return number of bytes read (should always equal target.length)
     * @since 0.9.48, copied from former superclass DataStructureImpl
     */
    protected int read(InputStream in, byte target[]) throws IOException {
        return DataHelper.read(in, target);
    }

    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_data == null) throw new DataFormatException("No data to write out");
        out.write(_data);
    }

    @Override
    public String toBase64() {
        if (_data == null)
            return null;
        return Base64.encode(_data);
    }

    /**
     * Sets the data.
     * @throws DataFormatException if decoded data is not the legal number of bytes or on decoding error
     * @throws RuntimeException if data already set.
     */
    @Override
    public void fromBase64(String data) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        byte[] d = Base64.decode(data);
        if (d == null)
            throw new DataFormatException("Bad Base64 encoded data");
        if (d.length != length())
            throw new DataFormatException("Bad decoded data length, expected " + length() + " got " + d.length);
        // call setData() instead of _data = data in case overridden
        setData(d);
    }

    /** @return the SHA256 hash of the byte array, or null if the data is null */
    @Override
    public Hash calculateHash() {
        if (_data != null) return SHA256Generator.getInstance().calculateHash(_data);
        return null;
    }

    /**
     * @return same thing as getData()
     */
    @Override
    public byte[] toByteArray() {
        return _data;
    }

    /**
     * Does the same thing as setData() but null not allowed.
     * @param data non-null
     * @throws DataFormatException if null or wrong length
     * @throws RuntimeException if data already set.
     */
    @Override
    public void fromByteArray(byte data[]) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        if (data.length != length())
            throw new DataFormatException("Bad data length: " + data.length + "; required: " + length());
        // call setData() instead of _data = data in case overridden
        setData(data);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        int length = length();
        if (_data == null) {
            buf.append("null");
        } else if (length <= 32) {
            buf.append(toBase64());
        } else {
            buf.append(Integer.toString(length)).append(" bytes");
        }
        return buf.toString();
    }

    /**
     * We assume the data has enough randomness in it, so use the first 4 bytes for speed.
     * If this is not the case, override in the extending class.
     */
    @Override
    public int hashCode() {
        if (_data == null)
            return 0;
        int rv = _data[0];
        for (int i = 1; i < 4; i++)
            rv ^= (_data[i] << (i*8));
        return rv;
    }

    /**
     * Warning - this returns true for two different classes with the same size
     * and same data, e.g. SessionKey and SessionTag, but you wouldn't
     * put them in the same Set, would you?
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ((obj == null) || !(obj instanceof SimpleDataStructure)) return false;
        return Arrays.equals(_data, ((SimpleDataStructure) obj)._data);
    }
}
