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
 * Tunnel identifier: a 32-bit unsigned value, held in a long, that names a
 * tunnel within the network database and in lease sets.
 *
 * <p>Value:</p>
 * <ul>
 *   <li>Valid range is 1 to {@link #MAX_ID_VALUE} (0xffffffff). 0 is reserved
 *       for a direct reply in a DatabaseStoreMessage, and the no-argument
 *       constructor leaves the invalid value -1 in place until the ID is set
 *       by a constructor argument, {@link #setTunnelId(long)} or
 *       {@link #readBytes(InputStream)}.</li>
 *   <li>Uniqueness is only local: each router picks and enforces its own IDs,
 *       nothing here generates or checks them.</li>
 * </ul>
 *
 * <p>Comparison:</p>
 * <ul>
 *   <li>{@link #equals(Object)} requires an instance of TunnelId, so unlike
 *       {@link SimpleDataStructure} this class is safe to mix with other types
 *       in a Set or Map.</li>
 *   <li>{@link #hashCode()} is the low 32 bits of the ID, which is the whole
 *       valid range.</li>
 * </ul>
 *
 * <p>Mutability and thread safety:</p>
 * <ul>
 *   <li>Not immutable: the ID is a plain non-volatile long that
 *       {@link #setTunnelId(long)}, {@link #readBytes(InputStream)} and the
 *       constructors can replace, even after the object has been shared.
 *       Publish or synchronize the object, or replace it, rather than mutating
 *       a TunnelId another thread may already hold.</li>
 *   <li>A TunnelId whose ID is set before publication and never changed
 *       afterwards can be shared freely between threads.</li>
 * </ul>
 *
 * @author jrandom
 */
public class TunnelId {
    /** LOCKING: this, see setTunnelId() */
    private long _tunnelId;

    /** Maximum tunnel ID value (2^32 - 1). */
    public static final long MAX_ID_VALUE = 0xffffffffL;

    /**
     * Create a TunnelId with the invalid value -1, for reading from a stream
     * or for setTunnelId().
     */
    public TunnelId() {
        _tunnelId = -1;
    }

    /**
     * 1 to 0xffffffff.
     *  @param id 1 to 0xffffffff
     *  @throws IllegalArgumentException if less than or equal to zero or greater than max value
     */
    public TunnelId(long id) {
        setTunnelId(id);
    }

    /**
     * Tunnel ID, or -1 if not set or not yet read.
     *
     *  @return the tunnel ID
     */
    public long getTunnelId() {
        return _tunnelId;
    }

    /**
     *  Sets the tunnel ID for this lease, replacing any previous value.
     *
     *  @param id 1 to 0xffffffff
     *  @throws IllegalArgumentException if less than or equal to zero or greater than max value
     */
    public final void setTunnelId(long id) {
        if (id <= 0 || id > MAX_ID_VALUE) throw new IllegalArgumentException("Bad Id " + id);
        _tunnelId = id;
    }

    /**
     *  Read the tunnel ID from a stream.
     *
     *  @param in the input stream to read from
     *  @throws DataFormatException if the data is invalid
     *  @throws IOException if there is an error reading
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _tunnelId = DataHelper.readLong(in, 4);
    }

    /**
     *  Write the tunnel ID to a stream.
     *
     *  @param out the output stream to write to
     *  @throws DataFormatException if the data is invalid
     *  @throws IOException if there is an error writing
     */
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        DataHelper.writeLong(out, 4, _tunnelId);
    }

    /**
     *  Two TunnelIds are equal when their IDs are equal. Two unset
     *  TunnelIds (both -1) are equal to each other.
     *
     *  @param obj the object to compare
     *  @return true if the IDs match
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ((obj == null) || !(obj instanceof TunnelId)) return false;
        return _tunnelId == ((TunnelId) obj)._tunnelId;
    }

    /**
     *  The low 32 bits of the ID, which is the whole valid range, or -1 if
     *  the ID is not set.
     *
     *  @return the hash code
     */
    @Override
    public int hashCode() {
        return (int) _tunnelId;
    }

    /**
     *  The ID in decimal.
     *
     *  @return a string representation
     */
    @Override
    public String toString() {
        return String.valueOf(_tunnelId);
    }
}
