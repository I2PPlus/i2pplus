package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Defines the token passed between the router and client to associate messages
 * with a particular session.  These IDs are not globally unique.
 *
 * As of 0.9.48, does NOT extend DataStructureImpl.
 *
 * @author jrandom
 */
public class SessionId {
    private int _sessionId;

    /** Creates a new SessionId with default value -1. */
    public SessionId() {
        _sessionId = -1;
    }

    /**
     * 0-65535.
     *  @param id 0-65535
     *  @since 0.9.11
     */
    public SessionId(int id) {
        if (id < 0 || id > 65535) {
            throw new IllegalArgumentException();
        }
        _sessionId = id;
    }

    /**
     * Session id.
     * @return the session id
     */
    public int getSessionId() {
        return _sessionId;
    }

    /**
     * Set the session ID.
     *
     * @param id 0-65535
     * @throws IllegalArgumentException if the ID is out of range
     * @throws IllegalStateException if already set
     */
    public void setSessionId(int id) {
        if (id < 0 || id > 65535) {
            throw new IllegalArgumentException();
        }
        if (_sessionId >= 0) {
            throw new IllegalStateException();
        }
        _sessionId = id;
    }

    /**
     * Read the session ID from a stream.
     *
     * @throws IllegalStateException if already set
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_sessionId >= 0) {
            throw new IllegalStateException();
        }
        _sessionId = (int) DataHelper.readLong(in, 2);
    }

    /** Writes the session ID to a stream. */
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_sessionId < 0) {
            throw new DataFormatException("Invalid Session ID: " + _sessionId);
        }
        DataHelper.writeLong(out, 2, _sessionId);
    }

    /** Compares this session ID with another object for equality. */
    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof SessionId)) {
            return false;
        }
        return _sessionId == ((SessionId) obj)._sessionId;
    }

    /** Hash code derived from the session ID value. */
    @Override
    public int hashCode() {
        return 777 * _sessionId;
    }

    /** Returns a string representation of this session ID. */
    @Override
    public String toString() {
        return "[SessionID " + _sessionId + "]";
    }
}
