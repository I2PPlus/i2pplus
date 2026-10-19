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
 * Defines the message a client sends to a router when establishing a new
 * session.
 *
 */
public class CreateSessionMessage extends I2CPMessageImpl {
    /**
     * I2CP message type number identifying a session creation request.
     */
    public static final int MESSAGE_TYPE = 1;
    private SessionConfig _sessionConfig;

    /**
     * Carries the given session configuration to the router.
     *
     * @param config the session properties to send to the router in this message
     */
    public CreateSessionMessage(SessionConfig config) {
        _sessionConfig = config;
    }

    /**
     * Creates a message with an empty session configuration.
     */
    public CreateSessionMessage() {
        _sessionConfig = new SessionConfig();
    }

    /**
     * Session config.
     * @return the session config
     */
    public SessionConfig getSessionConfig() {
        return _sessionConfig;
    }

    /**
     * Session config for this message.
     *
     * @param config the session properties replacing the current ones
     */
    public void setSessionConfig(SessionConfig config) {
        _sessionConfig = config;
    }

    /**
     * Read the message body from the input stream.
     */
    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        SessionConfig config = new SessionConfig();
        try {
            config.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the session configuration", dfe);
        }
        setSessionConfig(config);
    }

    /**
     * Write the message body to the output stream.
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if (_sessionConfig == null) throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        ByteArrayOutputStream os = new ByteArrayOutputStream(512);
        try {
            _sessionConfig.writeBytes(os);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error writing out the session config", dfe);
        }
        return os.toByteArray();
    }

    /**
     * Reports this message's I2CP type number.
     * @return the I2CP type number of this message
     */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }
/** Returns a string representation of this message. */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append("CreateSessionMessage: ");
        buf.append("\n* Config: ").append(_sessionConfig);
        return buf.toString();
    }
}
