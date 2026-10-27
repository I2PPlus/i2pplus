package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Payload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Defines the payload message a router sends to the client.
 *
 */
public class MessagePayloadMessage extends I2CPMessageImpl {
    /**
     * I2CP message type code for a payload message.
     */
    public static final int MESSAGE_TYPE = 31;
    /** The session the payload is for. */
    private int _sessionId;
    /** The message the payload belongs to. */
    private long _messageId;
    /** The message contents. */
    private Payload _payload;

    /**
     * For reading.
     * Deprecated for writing, use 3-arg constructor.
     */
    public MessagePayloadMessage() {
        _sessionId = -1;
        _messageId = -1;
    }

    /**
     * For writing.
     *
     * @param sessID the session the message belongs to, stored truncated to 32 bits
     * @param msgID the message ID used to correlate the payload with its reply
     * @param payload the payload to deliver to the session
     * @since 0.9.54
     */
    public MessagePayloadMessage(long sessID, long msgID, Payload payload) {
        synchronized (this) {
            _sessionId = (int) sessID;
            _messageId = msgID;
            _payload = payload;
        }
    }

    /**
     * Get the session this message was sent on.
     *
     * @return the session ID
     */
    public synchronized long getSessionId() {
        return _sessionId;
    }

    /**
     * Return the SessionId for this message.
     *
     * @since 0.9.21
     */
    @Override
    public synchronized SessionId sessionId() {
        return _sessionId >= 0 ? new SessionId(_sessionId) : null;
    }

    /**
     * Get the message ID, unique within the session.
     *
     * @return the message ID
     */
    public synchronized long getMessageId() {
        return _messageId;
    }

    /**
     * Get the payload carried by this message.
     *
     * @return the payload to deliver to the session, or null if not yet set
     */
    public synchronized Payload getPayload() {
        return _payload;
    }

    /** {@inheritDoc} */
    @Override
    protected synchronized void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = (int) DataHelper.readLong(in, 2);
            _messageId = DataHelper.readLong(in, 4);
            _payload = new Payload();
            _payload.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    /**
     * Write the message body to the output stream.
     *
     * @throws UnsupportedOperationException always, as this message is read-only
              once constructed
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Write out the full message to the stream, including the 4 byte size and 1
     * byte type header.
     *
     * @throws IOException if there's an error writing to the stream
     */
    @Override
    public synchronized void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        if (_sessionId < 0) throw new I2CPMessageException("Cannot write message -> Session ID not defined");
        if (_messageId < 0) throw new I2CPMessageException("Cannot write message -> Message ID not defined");
        if (_payload == null) throw new I2CPMessageException("Cannot write message -> Payload not defined");

        int size = 2 + 4 + 4 + _payload.getSize();
        try {
            DataHelper.writeLong(out, 4, size);
            out.write((byte) MESSAGE_TYPE);
            DataHelper.writeLong(out, 2, _sessionId);
            DataHelper.writeLong(out, 4, _messageId);
            DataHelper.writeLong(out, 4, _payload.getSize());
            out.write(_payload.getEncryptedData());
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Cannot write message length or type", dfe);
        }
    }

    /** {@inheritDoc} */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append(" MessagePayloadMessage").append(" [MsgID ").append(_messageId).append("]")
           .append("\n* SessionID: ").append(_sessionId)
           .append(" [").append(_payload).append("]");
        return buf.toString();
    }
}
