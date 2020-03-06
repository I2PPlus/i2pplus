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

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

/**
 * Defines the message a client sends to a router when asking the
 * router to start sending a message to it.
 *
 * @author jrandom
 */
public class ReceiveMessageEndMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 7;
    private int _sessionId;
    private long _messageId;

    public ReceiveMessageEndMessage() {
        _sessionId = -1;
        _messageId = -1;
    }

    public long getSessionId() {
        return _sessionId;
    }

    /**
     * Return the SessionId for this message.
     *
     * @since 0.9.21
     */
    @Override
    public SessionId sessionId() {
        return _sessionId >= 0 ? new SessionId(_sessionId) : null;
    }

    /** @param id 0-65535 */
    public void setSessionId(long id) {
        _sessionId = (int) id;
    }

    public long getMessageId() {
        return _messageId;
    }

    public void setMessageId(long id) {
        _messageId = id;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = (int) DataHelper.readLong(in, 2);
            _messageId = DataHelper.readLong(in, 4);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if ((_sessionId < 0) || (_messageId < 0))
            throw new I2CPMessageException("Unable to write out the message as there is not enough data");
        byte rv[] = new byte[2+4];
        DataHelper.toLong(rv, 0, 2, _sessionId);
        DataHelper.toLong(rv, 2, 4, _messageId);
        return rv;
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[ReceiveMessageEndMessage: ");
        buf.append(" SessionId: ").append(_sessionId);
        buf.append("; MessageId: ").append(_messageId);
        buf.append("]");
        return buf.toString();
    }
}
