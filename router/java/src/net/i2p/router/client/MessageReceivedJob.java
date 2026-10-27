package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import net.i2p.data.Destination;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.SessionId;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Async job to notify the client that a new message is available for them,
 * or just send it directly if specified.
 */
class MessageReceivedJob extends JobImpl {
    /** Logger for this class. */
    private final Log _log;
    /** The runner delivering the message. */
    private final ClientConnectionRunner _runner;
    /** The destination the message is addressed to. */
    private final Destination _toDest;
    /** The message being delivered. */
    private final Payload _payload;
    /** True if the message arrived directly, not through a tunnel. */
    private final boolean _sendDirect;

    /**
     * Create a job that delivers one received message to a client.
     *
     * @param ctx the router context
     * @param runner the client connection runner
     * @param toDest non-null, required to pick session
     * @param fromDest sending destination, generally null
     * @param payload the message payload
     * @param sendDirect if true send directly
     */
    public MessageReceivedJob(RouterContext ctx, ClientConnectionRunner runner, Destination toDest,
                              Destination fromDest, Payload payload, boolean sendDirect) {
        super(ctx);
        _log = ctx.logManager().getLog(MessageReceivedJob.class);
        _runner = runner;
        _toDest = toDest;
        _payload = payload;
        _sendDirect = sendDirect;
    }

    public String getName() { return "Deliver New Message"; }

    public void runJob() {
        receiveMessage();
    }

    /**
     * Same as {@link #runJob()} but with a return value.
     *
     * @return true if the payload was handed over, false if the runner is dead
     * or the send threw
     * @since 0.9.29
     */
    public boolean receiveMessage() {
        if (_runner.isDead())
            return false;
        MessageId id = null;
        try {
            long nextID = _runner.getNextMessageId();
            if (_sendDirect) {
                sendMessage(nextID);
            } else {
                id = new MessageId(nextID);
                _runner.setPayload(id, _payload);
                messageAvailable(id, _payload.getSize());
            }
            return true;
        } catch (I2CPMessageException ime) {
            String msg = "Error sending data to client " + _runner.getDestHash().toBase32();
            if (_log.shouldWarn())
                _log.warn(msg, ime);
            else
                _log.logAlways(Log.WARN, msg);
            if (id != null)
                _runner.removePayload(id);
            return false;
        }
    }

    /**
     * Deliver notification to the client that the given message is available.
     *
     * @param id the id of the message that became available
     * @param size the message size in bytes
     * @throws I2CPMessageException if the status message cannot be sent to the
     *                              client
     */
    private void messageAvailable(MessageId id, long size) throws I2CPMessageException {
        MessageStatusMessage msg = new MessageStatusMessage();
        msg.setMessageId(id.getMessageId());
        SessionId sid = _runner.getSessionId(_toDest.calculateHash());
        if (sid == null) {
            if (_log.shouldWarn())
                _log.warn("No session for [" + _toDest.toBase32().substring(0, 8) + "]");
            return;
        }
        msg.setSessionId(sid.getSessionId());
        msg.setSize(size);
        // has to be >= 0, it is initialized to -1
        msg.setNonce(1);
        msg.setStatus(MessageStatusMessage.STATUS_AVAILABLE);
        _runner.doSend(msg);
    }

    /**
     * Deliver the message directly, skip notification.
     *
     * @param id the id of the queued message to deliver
     * @throws I2CPMessageException if the payload message cannot be sent to
     *                              the client
     * @since 0.9.4
     */
    private void sendMessage(long id) throws I2CPMessageException {
        SessionId sid = _runner.getSessionId(_toDest.calculateHash());
        if (sid == null) {
            if (_log.shouldWarn())
                _log.warn("No session for [" + _toDest.toBase32().substring(0, 8) + "]");
            return;
        }
        MessagePayloadMessage msg = new MessagePayloadMessage(sid.getSessionId(), id, _payload);
        _runner.doSend(msg);
    }
}
