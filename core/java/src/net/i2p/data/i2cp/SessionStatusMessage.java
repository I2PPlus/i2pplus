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
import net.i2p.util.ByteArrayStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Defines the message a router sends to a client indicating the
 * status of the session.
 *
 * @author jrandom
 */
public class SessionStatusMessage extends I2CPMessageImpl {
    /**
     * MESSAGE_TYPE.
     */
    public static final int MESSAGE_TYPE = 20;
    private SessionId _sessionId;
    private int _status;

    /**
     * STATUS_DESTROYED.
     */
    public static final int STATUS_DESTROYED = 0;
    /**
     * STATUS_CREATED.
     */
    public static final int STATUS_CREATED = 1;
    /**
     * STATUS_UPDATED.
     */
    public static final int STATUS_UPDATED = 2;
    /**
     * STATUS_INVALID.
     */
    public static final int STATUS_INVALID = 3;

    /** Session refused status.
     *
     * @since 0.9.12
     */
    public static final int STATUS_REFUSED = 4;

    /**
     *  Used internally, not in spec, will be remapped to STATUS_INVALID before being sent.
     *
     *  @since 0.9.44
     */
    public static final int STATUS_DUP_DEST = 5;

    /**
     * SessionStatusMessage.
     */
    public SessionStatusMessage() {
        setStatus(STATUS_INVALID);
    }

    /**
     * Session id.
     * @return the session id
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
     * Session ID for this message.
     */
    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    /**
     * Status.
     * @return the status
     */
    public int getStatus() {
        return _status;
    }

    /**
     * Status of the session.
     */
    public final void setStatus(int status) {
        _status = status;
    }

    /**
     * Read the message body from the input stream.
     */
    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _status = in.read();
            if (_status < 0) {
                throw new EOFException();
            }
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    /**
     * Write the message body to the output stream.
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_sessionId == null) {
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        }
        ByteArrayStream os = new ByteArrayStream(3);
        try {
            _sessionId.writeBytes(os);
            os.write((byte) _status);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    /**
     * Type.
     * @return the type
     */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }
/** Returns a string representation of this message. */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append(getSessionId()).append(" SessionStatusMessage: ").append(" [Status: ").append(getStatus() + "]");
        return buf.toString();
    }
}
