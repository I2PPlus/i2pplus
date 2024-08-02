package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.util.ByteArrayStream;

/**
 * Defines the message a router sends to a client indicating the
 * status of the session.
 *
 * @author jrandom
 */
public class SessionStatusMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 20;
    private SessionId _sessionId;
    private int _status;

    public final static int STATUS_DESTROYED = 0;
    public final static int STATUS_CREATED = 1;
    public final static int STATUS_UPDATED = 2;
    public final static int STATUS_INVALID = 3;
    /** @since 0.9.12 */
    public final static int STATUS_REFUSED = 4;
    /**
     *  Used internally, not in spec, will be remapped to STATUS_INVALID before being sent.
     *  @since 0.9.44
     */
    public final static int STATUS_DUP_DEST = 5;

    public SessionStatusMessage() {
        setStatus(STATUS_INVALID);
    }

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

    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    public int getStatus() {
        return _status;
    }

    public void setStatus(int status) {
        _status = status;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _status = in.read();
            if (_status < 0)
                throw new EOFException();
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_sessionId == null)
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayStream os = new ByteArrayStream(3);
        try {
            _sessionId.writeBytes(os);
            os.write((byte) _status);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(getSessionId());
        buf.append(" SessionStatusMessage: ");
        buf.append(" [Status: ").append(getStatus() + "]");
        return buf.toString();
    }
}
