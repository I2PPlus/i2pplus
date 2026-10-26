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

import java.io.IOException;
import java.io.InputStream;

/**
 * Defines the message a client sends to a router when asking the
 * router what its address visibility is.
 *
 */
public class ReportAbuseMessage extends I2CPMessageImpl {
    /**
     * I2CP message type number identifying an abuse report.
     */
    public static final int MESSAGE_TYPE = 29;
    /** The session the report is about. */
    private SessionId _sessionId;
    /** How serious the abuse was. */
    private AbuseSeverity _severity;
    /** What the client objects to. */
    private AbuseReason _reason;
    /** The message the abuse was seen on. */
    private MessageId _messageId;

    /**
     * Creates an empty report, for I2CP deserialization.
     */
    public ReportAbuseMessage() { /* required for I2CP deserialization */ }

    /**
     * Session making the report.
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
     * Session ID for this message.
     *
     * @param id the session this abuse report was sent from
     */
    public void setSessionId(SessionId id) {
        _sessionId = id;
    }

    /**
     * Reports how serious the abuse was judged to be.
     * @return the reported severity level
     */
    public AbuseSeverity getSeverity() {
        return _severity;
    }

    /**
     * Severity of the abuse.
     *
     * @param severity how serious the reported behaviour is judged to be
     */
    public void setSeverity(AbuseSeverity severity) {
        _severity = severity;
    }

    /**
     * Reports which category of misbehaviour was observed.
     * @return the free-text category string
     */
    public AbuseReason getReason() {
        return _reason;
    }

    /**
     * Reason for the abuse.
     *
     * @param reason the category of misbehaviour being reported
     */
    public void setReason(AbuseReason reason) {
        _reason = reason;
    }

    /**
     * Message the abuse was seen on.
     * @return the message id, null until set or read
     */
    public MessageId getMessageId() {
        return _messageId;
    }

    /**
     * Message ID of the reported message.
     *
     * @param id the identifier of the message being complained about
     */
    public void setMessageId(MessageId id) {
        _messageId = id;
    }

    /**
     * Read the message body from the input stream.
     */
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

    /**
     * Write the message body to the output stream.
     */
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException, IOException {
        if ((_sessionId == null) || (_severity == null) || (_reason == null)) throw new I2CPMessageException("Not enough information to construct the message");
        int len = 2 + 1 + 4 + 1;
        String r = _reason.getReason();
        if (r != null) len += r.length();
        ByteArrayStream os = new ByteArrayStream(len);
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

    /**
     * Reports this message's I2CP type number.
     * @return {@link #MESSAGE_TYPE}, 29
     */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }
    /** Returns a string representation of this message. */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append("ReportAbuseMessage ");
        buf.append(" [MsgID ").append(getMessageId()).append("]");
        buf.append("\n* SessionID: ").append(getSessionId());
        buf.append(" * Severity: ").append(getSeverity());
        buf.append(" * Reason: ").append(getReason());
        return buf.toString();
    }
}
