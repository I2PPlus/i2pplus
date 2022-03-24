package net.i2p.data.i2cp;

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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Payload;

/**
 * Defines the payload message a router sends to the client
 *
 * @author jrandom
 */
public class MessagePayloadMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 31;
    private int _sessionId;
    private long _messageId;
    private Payload _payload;

    /**
     *  For reading.
     *  Deprecated for writing, use 3-arg constructor
     */
    public MessagePayloadMessage() {
        _sessionId = -1;
        _messageId = -1;
    }

    /**
     *  For writing
     *
     *  @since 0.9.54
     */
    public MessagePayloadMessage(long sessID, long msgID, Payload payload) {
        synchronized(this) {
            _sessionId = (int) sessID;
            _messageId = msgID;
            _payload = payload;
        }
    }

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
     *  @param id 0-65535
     *  @deprecated use 3-arg constructor
     */
    @Deprecated
    public synchronized void setSessionId(long id) {
        _sessionId = (int) id;
    }

    public synchronized long getMessageId() {
        return _messageId;
    }

    /**
     *  @deprecated use 3-arg constructor
     */
    @Deprecated
    public synchronized void setMessageId(long id) {
        _messageId = id;
    }

    public synchronized Payload getPayload() {
        return _payload;
    }

    /**
     *  @deprecated use 3-arg constructor
     */
    @Deprecated
    public synchronized void setPayload(Payload payload) {
        _payload = payload;
    }

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
     *  @throws UnsupportedOperationException always
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Write out the full message to the stream, including the 4 byte size and 1
     * byte type header.
     *
     * @throws IOException
     */
    @Override
    public synchronized void writeMessage(OutputStream out) throws I2CPMessageException, IOException {
        if (_sessionId <= 0)
            throw new I2CPMessageException("Unable to write out the message, as the session ID has not been defined");
        if (_messageId < 0)
            throw new I2CPMessageException("Unable to write out the message, as the message ID has not been defined");
        if (_payload == null)
            throw new I2CPMessageException("Unable to write out the message, as the payload has not been defined");

        int size = 2 + 4 + 4 + _payload.getSize();
        try {
            DataHelper.writeLong(out, 4, size);
            out.write((byte) MESSAGE_TYPE);
            DataHelper.writeLong(out, 2, _sessionId);
            DataHelper.writeLong(out, 4, _messageId);
            DataHelper.writeLong(out, 4, _payload.getSize());
            out.write(_payload.getEncryptedData());
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to write the message length or type", dfe);
        }
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(" MessagePayloadMessage\n*");
        buf.append(" SessionID: ").append(_sessionId);
        buf.append(" [MsgID ").append(_messageId);
        buf.append("] [").append(_payload).append("]");
        return buf.toString();
    }
}
