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
import java.io.OutputStream;

/**
 * Base interface defining standard binary representation for I2P data structures.
 * 
 * <p>DataStructure provides the foundation for all I2P protocol data:</p>
 * <ul>
 *   <li><strong>Binary Protocol:</strong> Defines exact bit-level representation</li>
 *   <li><strong>Serialization:</strong> Standard methods for reading/writing to streams</li>
 *   <li><strong>Network Compatibility:</strong> Ensures consistent data encoding</li>
 *   <li><strong>Validation:</strong> Built-in integrity and format checking</li>
 * </ul>
 * 
 * <p><strong>Core Operations:</strong></p>
 * <ul>
 *   <li>{@link #readBytes(InputStream)} - Deserialize from binary stream</li>
 *   <li>{@link #writeBytes(OutputStream)} - Serialize to binary stream</li>
 *   <li><strong>Format Compliance:</strong> Follows I2P data structure specification</li>
 *   <li><strong>Error Handling:</strong> Proper exception reporting for invalid data</li>
 * </ul>
 * 
 * <p><strong>Integrity Protection:</strong></p>
 * <ul>
 *   <li><strong>Immutable After Initialization:</strong> Prevents modification after deserialization</li>
 *   <li><strong>IllegalStateException:</strong> Thrown when modification is attempted</li>
 *   <li><strong>NetDb Protection:</strong> Safeguards network database from corruption</li>
 *   <li><strong>Message Safety:</strong> Protects I2NP messages during transmission</li>
 * </ul>
 * 
 * <p><strong>Usage Throughout I2P:</strong></p>
 * <ul>
 *   <li><strong>I2NP Messages:</strong> All protocol messages implement DataStructure</li>
 *   <li><strong>NetDb Entries:</strong> RouterInfo and LeaseSet storage format</li>
 *   <li><strong>I2CP Protocol:</strong> Client-router communication data</li>
 *   <li><strong>Cryptography:</strong> Keys, signatures, and certificates</li>
 *   <li><strong>Routing:</strong> Tunnel IDs, leases, and routing information</li>
 * </ul>
 * 
 * <p><strong>Implementation Guidelines:</strong></p>
 * <ul>
 *   <li><strong>Do Not Reuse:</strong> Create new instances for each data element</li>
 *   <li><strong>Thread Safety:</strong> Protection is not guaranteed to be thread-safe</li>
 *   <li><strong>Corruption Prevention:</strong> Multiple protection layers but not foolproof</li>
 *   <li><strong>Direct Modification:</strong> Avoid modifying internal byte[] arrays</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Input Validation:</strong> Always validate deserialized data</li>
 *   <li><strong>Bounds Checking:</strong> Prevent buffer overflows and underflows</li>
 *   <li><strong>Exception Handling:</strong> Properly handle DataFormatException</li>
 *   <li><strong>Memory Safety:</strong> Be cautious with direct byte array access</li>
 * </ul>
 * 
 * <p><strong>Performance Aspects:</strong></p>
 * <ul>
 *   <li><strong>Efficient Serialization:</strong> Optimized for network transmission</li>
 *   <li><strong>Minimal Overhead:</strong> Compact binary representation</li>
 *   <li><strong>Fast Validation:</strong> Quick integrity and format checking</li>
 *   <li><strong>Memory Management:</strong> Careful allocation and cleanup</li>
 * </ul>
 * 
 * <p><strong>Common Implementations:</strong></p>
 * <ul>
 *   <li>{@link DataStructureImpl} - Base class with common functionality</li>
 *   <li>{@link SimpleDataStructure} - Fixed-size byte array structures</li>
 *   <li>{@link DatabaseEntry} - NetDb-storable objects with signatures</li>
 *   <li>All I2P protocol message classes</li>
 * </ul>
 *
 * @author jrandom
 */
public interface DataStructure /* extends Serializable */ {

    /**
     * Load up the current object with data from the given stream.  Data loaded
     * this way must match the I2P data structure specification.
     *
     * Warning - many classes will throw IllegalStateException if data is already set.
     *
     * @param in stream to read from
     * @throws DataFormatException if the data is improperly formatted
     * @throws IOException if there was a problem reading the stream
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException;

    /**
     * Write out the data structure to the stream, using the format defined in the
     * I2P data structure specification.
     *
     * @param out stream to write to
     * @throws DataFormatException if the data was incomplete or not yet ready to be written
     * @throws IOException if there was a problem writing to the stream
     */
    public void writeBytes(OutputStream out) throws DataFormatException, IOException;

    /**
     * render the structure into modified base 64 notation
     * @return null on error
     */
    public String toBase64();

/**
     * Load the structure from the provided base64 string.
     *
     * Warning - many classes will throw IllegalStateException if data is already set.
     * Warning - many classes will throw IllegalArgumentException if data is the wrong size.
     *
     * @param data base64 encoded string to load from
     * @throws DataFormatException if the data format is invalid
     */
    public void fromBase64(String data) throws DataFormatException;

    /**
     * Convert the structure to a byte array.
     * 
     * @return may be null if data is not set
     */
    public byte[] toByteArray();

/**
     * Load's structure from the data provided
     *
     * Warning - many classes will throw IllegalStateException if data is already set.
     * Warning - many classes will throw IllegalArgumentException if data is the wrong size.
     *
     * @param data byte array to load from
     * @throws DataFormatException if the data format is invalid
     */
    public void fromByteArray(byte data[]) throws DataFormatException;

    /**
     * Calculate the SHA256 value of this object (useful for a few scenarios)
     *
     * @return SHA256 hash, or null if there were problems (data format or io errors)
     */
    public Hash calculateHash();
}
