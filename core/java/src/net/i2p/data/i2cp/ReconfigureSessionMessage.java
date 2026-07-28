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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Defines the message a client sends to a router when
 * updating the config on an existing session.
 *
 * @author zzz
 */
/**
 * Message to reconfigure an I2CP session.
 */
public class ReconfigureSessionMessage extends I2CPMessageImpl {
    /**
     * MESSAGE_TYPE.
     */
    public static final int MESSAGE_TYPE = 2;
    private SessionId _sessionId;
    private SessionConfig _sessionConfig;

    /**
     * ReconfigureSessionMessage.
     */
    public ReconfigureSessionMessage() { /* required for I2CP deserialization */ }

    /**
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
     * setSessionId.
     */
    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    /**
     * @return the session config
     */
    public SessionConfig getSessionConfig() {
        return _sessionConfig;
    }

    /**
     * setSessionConfig.
     */
    public void setSessionConfig(SessionConfig config) {
        _sessionConfig = config;
    }

    /**
     * doReadMessage.
     */
    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _sessionConfig = new SessionConfig();
            _sessionConfig.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    /**
     * doWriteMessage.
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_sessionId == null || _sessionConfig == null) throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(1024);
        try {
            _sessionId.writeBytes(os);
            _sessionConfig.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the message data", dfe);
        }
        return os.toByteArray();
    }

    /**
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
        buf.append("[ReconfigureSessionMessage: ");
        buf.append("\n\tSessionId: ").append(_sessionId);
        buf.append("\n\tSessionConfig: ").append(_sessionConfig);
        buf.append("]");
        return buf.toString();
    }
}
