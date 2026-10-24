package net.i2p.data.i2cp;

/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.ByteArrayStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Request the router look up the dest for a hash
 * or a host. Replaces DestLookupMessage. Do not send to routers
 * older than 0.9.11.
 *
 * @since 0.9.11
 */
public class HostLookupMessage extends I2CPMessageImpl {
    /**
     * I2CP message type number of a host or hash lookup request.
     */
    public static final int MESSAGE_TYPE = 38;

    /** Id matching the reply to the request. */
    private long _reqID;
    /** How long the router may take, in ms. */
    private long _timeout;
    /** What kind of lookup to perform. */
    private int _lookupType;
    /** The hash to look up, for hash lookups. */
    private Hash _hash;
    /** The host name to look up, for name lookups. */
    private String _host;
    /** The session that asked for the lookup. */
    private SessionId _sessionId;

    /**
     * Lookup type value asking for the Destination behind a hash.
     */
    public static final int LOOKUP_HASH = 0;
    /**
     * Lookup type value asking for the HostName behind a Destination.
     */
    public static final int LOOKUP_HOST = 1;

    /** Largest id that fits on the wire. */
    private static final long MAX_INT = (1L << 32) - 1;

    /**
     * Creates a lookup request with no target, type or session set.
     */
    public HostLookupMessage() {}

    /**
     * Session id.
     * @param id the session id
     * @param h the hash to look up
     * @param reqID 0 to 2**32 - 1
     * @param timeout ms 1 to 2**32 - 1
     */
    public HostLookupMessage(SessionId id, Hash h, long reqID, long timeout) {
        if (id == null || h == null) throw new IllegalArgumentException();
        if (reqID < 0 || reqID > MAX_INT) throw new IllegalArgumentException();
        if (timeout <= 0 || timeout > MAX_INT) throw new IllegalArgumentException();
        _sessionId = id;
        _hash = h;
        _reqID = reqID;
        _timeout = timeout;
        _lookupType = LOOKUP_HASH;
    }

    /**
     * Session id.
     * @param id the session id
     * @param host the hostname to look up
     * @param reqID 0 to 2**32 - 1
     * @param timeout ms 1 to 2**32 - 1
     */
    public HostLookupMessage(SessionId id, String host, long reqID, long timeout) {
        if (id == null || host == null) throw new IllegalArgumentException();
        if (reqID < 0 || reqID > MAX_INT) throw new IllegalArgumentException();
        if (timeout <= 0 || timeout > MAX_INT) throw new IllegalArgumentException();
        _sessionId = id;
        _host = host;
        _reqID = reqID;
        _timeout = timeout;
        _lookupType = LOOKUP_HOST;
    }

    /**
     * Session making the lookup request.
     * @return the session id, null until set or read
     */
    public SessionId getSessionId() {
        return _sessionId;
    }

    /**
     * Return the SessionId for this message.
     *
     * @since 0.9.21
     */
    @Override
    public SessionId sessionId() {
        return _sessionId;
    }

    /**
     * Gets the request ID.
     *
     * @return 0 to 2**32 - 1
     */
    public long getReqID() {
        return _reqID;
    }

    /**
     * Gets the lookup timeout.
     *
     * @return ms 1 to 2**32 - 1
     */
    public long getTimeout() {
        return _timeout;
    }

    /**
     * The lookup type selector.
     *
     * @return 0 (hash) or 1 (host)
     */
    public int getLookupType() {
        return _lookupType;
    }

    /**
     * Gets the hash for hash-type lookups.
     *
     * @return only valid if lookup type == 0
     */
    public Hash getHash() {
        return _hash;
    }

    /**
     * Gets the hostname for host-type lookups.
     *
     * @return only valid if lookup type == 1
     */
    public String getHostname() {
        return _host;
    }

    /**
     * Read the message body from the input stream.
     */
    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _reqID = DataHelper.readLong(in, 4);
            _timeout = DataHelper.readLong(in, 4);
            _lookupType = in.read();
            if (_lookupType < 0) throw new EOFException();
            if (_lookupType == LOOKUP_HASH) {
                _hash = Hash.create(in);
            } else if (_lookupType == LOOKUP_HOST) {
                _host = DataHelper.readString(in);
                if (_host.isEmpty()) throw new I2CPMessageException("Bad host");
            } else {
                throw new I2CPMessageException("Bad type");
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Bad data", dfe);
        }
    }

    /**
     * Write the message body to the output stream.
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        int len;
        if (_lookupType == LOOKUP_HASH) {
            if (_hash == null) throw new I2CPMessageException("Unable to write out the message as there is not enough data");
            len = 11 + Hash.HASH_LENGTH;
        } else if (_lookupType == LOOKUP_HOST) {
            if (_host == null) throw new I2CPMessageException("Unable to write out the message as there is not enough data");
            len = 12 + _host.length();
        } else {
            throw new I2CPMessageException("Bad type");
        }
        ByteArrayStream os = new ByteArrayStream(len);
        try {
            _sessionId.writeBytes(os);
            DataHelper.writeLong(os, 4, _reqID);
            DataHelper.writeLong(os, 4, _timeout);
            os.write((byte) _lookupType);
            if (_lookupType == LOOKUP_HASH) {
                _hash.writeBytes(os);
            } else {
                DataHelper.writeString(os, _host);
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Bad data", dfe);
        }
        return os.toByteArray();
    }

    /**
     * Reports this message as a host lookup request.
     * @return {@link #MESSAGE_TYPE}, 38
     */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }
/** Returns a string representation of this message. */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append("[HostLookupMessage: ");
        buf.append("\n\t").append(_sessionId);
        buf.append("\n\tReqID: ").append(_reqID);
        buf.append("\n\tTimeout: ").append(_timeout);
        if (_lookupType == LOOKUP_HASH) buf.append("\n\tHash: ").append(_hash);
        else if (_lookupType == LOOKUP_HOST) buf.append("\n\tHost: ").append(_host);
        buf.append("]");
        return buf.toString();
    }
}
