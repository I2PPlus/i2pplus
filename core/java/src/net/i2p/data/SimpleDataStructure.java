package net.i2p.data;

/*
 * Public domain
 */

import net.i2p.crypto.SHA256Generator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Base class for I2P data structures holding a single byte array in a
 * protected {@link #_data} field.
 *
 * <p>What the base class actually provides:</p>
 * <ul>
 *   <li><strong>Length:</strong> {@link #length()} is abstract, and subclasses
 *       fix it per type - 32 bytes for {@link Hash} or {@link SessionKey}, 256
 *       for an ElGamal {@link PublicKey}, and 20, 32, 64 or 256 for a
 *       {@link SigningPublicKey} depending on the algorithm. It is a fixed
 *       length per subclass, not per instance.</li>
 *   <li><strong>Single assignment:</strong> {@link #setData(byte[])},
 *       {@link #readBytes(InputStream)} and {@link #fromBase64(String)} all
 *       refuse to overwrite non-null data with a {@link RuntimeException}, and
 *       reject a wrong length. {@link #fromByteArray(byte[])} and
 *       {@link #fromBase64(String)} additionally reject null.</li>
 *   <li><strong>No copy:</strong> {@link #getData()} and
 *       {@link #toByteArray()} return the backing array itself, not a copy.</li>
 *   <li><strong>Encoding:</strong> {@link #toBase64()} and, where a subclass
 *       adds it, its own base32 rendering.</li>
 *   <li><strong>Comparison:</strong> {@link #equals(Object)} compares the raw
 *       arrays without checking the class or length, so two different
 *       SimpleDataStructure subclasses of equal length holding equal bytes
 *       compare equal (e.g. a 32-byte {@link SessionKey} and a 32-byte
 *       {@link Hash}). {@link #hashCode()} uses the first 4 bytes only, which
 *       requires the data to be random - subclasses with structured data
 *       override it.</li>
 * </ul>
 *
 * <p>Not provided here:</p>
 * <ul>
 *   <li><strong>No cache:</strong> there is no LRU or any other cache in this
 *       class. Subclasses that want one supply their own static
 *       {@link SDSCache} and expose create() factory methods that go through
 *       it - see {@link Hash}.</li>
 *   <li><strong>Not thread-safe:</strong> nothing here is synchronized and
 *       {@link #_data} is not volatile, so an instance must be published
 *       safely (final field, or a happens-before edge) before other threads
 *       read it. Safe sharing applies only to a fully populated instance that
 *       nobody mutates - and callers that mutate the array returned by
 *       getData() break that.</li>
 * </ul>
 *
 * @author zzz
 * @since 0.8.2
 */
public abstract class SimpleDataStructure implements DataStructure {
    /** The byte array data for this structure. */
    protected byte[] _data;

    /**
     * A new instance with the data set to null. Call readBytes(), setData(), or fromByteArray() after this to set the data.
     */
    public SimpleDataStructure() {}

    /**
     * Creates a SimpleDataStructure with the given data.
     *
     * @param data the byte array, or null
     * @throws IllegalArgumentException if data is not the legal number of bytes (but null is ok)
     */
    public SimpleDataStructure(byte[] data) {
        setData(data);
    }

    /**
     * The legal length of the byte array in this data structure.
     *
     * @return the legal length of the byte array
     * @since 0.8.2
     */
    public abstract int length();

    /**
     * Data reference (not a copy). Mutating the returned array mutates this
     * structure, and breaks the hashCode contract of any subclass that caches
     * a hash of the data.
     *
     * @return the data
     */
    public byte[] getData() {
        return _data;
    }

    /**
     * The byte array data, or null. Not a copy - see the class documentation.
     *
     * @param data of correct length, or null
     * @throws IllegalArgumentException if data is not the legal number of bytes (but null is ok)
     * @throws RuntimeException if data already set.
     */
    public void setData(byte[] data) {
        if (_data != null) throw new RuntimeException("Data already set");
        if (data != null && data.length != length()) throw new IllegalArgumentException("Bad data length: " + data.length + "; required: " + length());
        _data = data;
    }

    /**
     * Data read from the stream.
     *
     * @param in the stream to read
     * @throws RuntimeException if data already set.
     */
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_data != null) throw new RuntimeException("Data already set");
        int length = length();
        _data = new byte[length];
        // Throws on incomplete read
        read(in, _data);
    }

    /**
     * Repeated reads until the buffer is full or IOException is thrown.
     *
     * @param in the stream to read from
     * @param target the buffer to fill
     * @return number of bytes read (should always equal target.length)
     * @throws IOException on IO error
     * @since 0.9.48
     */
    protected int read(InputStream in, byte[] target) throws IOException {
        return DataHelper.read(in, target);
    }

    /** Writes the data to the output stream. */
    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_data == null) throw new DataFormatException("No data to write out");
        out.write(_data);
    }

    /** Renders the structure into modified base 64 notation. */
    @Override
    public String toBase64() {
        if (_data == null) return null;
        return Base64.encode(_data);
    }

    /**
     * Data from the base64 string.
     *
     * @throws DataFormatException if decoded data is not the legal number of bytes or on decoding error
     * @throws RuntimeException if data already set.
     */
    @Override
    public void fromBase64(String data) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        byte[] d = Base64.decode(data);
        if (d == null) throw new DataFormatException("Bad Base64 encoded data");
        if (d.length != length()) throw new DataFormatException("Bad decoded data length, expected " + length() + " got " + d.length);
        // call setData() instead of _data = data in case overridden
        setData(d);
    }

    /** SHA256 hash of the byte array, or null if data is null. */
    @Override
    public Hash calculateHash() {
        if (_data != null) return SHA256Generator.getInstance().calculateHash(_data);
        return null;
    }

    /**
     * Same thing as getData().
     * @return same thing as getData()
     */
    @Override
    public byte[] toByteArray() {
        return _data;
    }

    /**
     * Does the same thing as setData() but null not allowed.
     *
     * @param data non-null byte array
     * @throws DataFormatException if null or wrong length
     * @throws RuntimeException if data already set
     */
    @Override
    public void fromByteArray(byte[] data) throws DataFormatException {
        if (data == null) throw new DataFormatException("Null data passed in");
        if (data.length != length()) throw new DataFormatException("Bad data length: " + data.length + "; required: " + length());
        // call setData() instead of _data = data in case overridden
        setData(data);
    }

    /** Returns a string representation of this data structure. */
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
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        if (_data == null) return 0;
        int rv = _data[0];
        for (int i = 1; i < 4; i++) rv ^= (_data[i] << (i * 8));
        return rv;
    }

    /**
     * Compares the raw data only: two different SimpleDataStructure subclasses
     * of the same length holding the same bytes are equal, e.g. a 32-byte
     * SessionKey and a 32-byte Hash. Only compare instances of the same class,
     * and only use them as map keys or set members when that cannot happen.
     *
     * @param obj the object to compare
     * @return true if the data arrays are equal
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ((obj == null) || !(obj instanceof SimpleDataStructure)) return false;
        return Arrays.equals(_data, ((SimpleDataStructure) obj)._data);
    }
}
