package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;

/**
 * Defines the message a client sends to a router when asking the
 * router what its address visibility is
 *
 * @author jrandom
 */
public class ReportAbuseMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 29;
    private SessionId _sessionId;
    private AbuseSeverity _severity;
    private AbuseReason _reason;
    private MessageId _messageId;

    public ReportAbuseMessage() {
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

    public AbuseSeverity getSeverity() {
        return _severity;
    }

    public void setSeverity(AbuseSeverity severity) {
        _severity = severity;
    }

    public AbuseReason getReason() {
        return _reason;
    }

    public void setReason(AbuseReason reason) {
        _reason = reason;
    }

    public MessageId getMessageId() {
        return _messageId;
    }

    public void setMessageId(MessageId id) {
        _messageId = id;
    }

    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException, IOException {
        try {
            _sessionId = new SessionId();
            _sessionId.readBytes(in);
            _severity = new AbuseSeverity();
            _severity.readBytes(in);
            _reason = new AbuseReason();
            _reason.readBytes(in);
            _messageId = new MessageId();
            _messageId.readBytes(in);
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Unable to load the message data", dfe);
        }
    }

    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if ((_sessionId == null) || (_severity == null) || (_reason == null))
            throw new I2CPMessageException("Not enough information to construct the message");
        ByteArrayOutputStream os = new ByteArrayOutputStream(32);
        try {
            _sessionId.writeBytes(os);
            _severity.writeBytes(os);
            _reason.writeBytes(os);
            if (_messageId == null) {
                _messageId = new MessageId();
                _messageId.setMessageId(0);
            }
            _messageId.writeBytes(os);
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
        buf.append("ReportAbuseMessage: ");
        buf.append("SessionID: ").append(getSessionId());
        buf.append("; Severity: ").append(getSeverity());
        buf.append("; Reason: ").append(getReason());
        buf.append("; [MsgID ").append(getMessageId());
        buf.append("]");
        return buf.toString();
    }
}
