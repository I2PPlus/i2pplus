package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import java.util.Date;
import net.i2p.data.Hash;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Send a message directly to another router (not through a tunnel).
 * <p>
 * Used by {@link HandleGarlicMessageJob} to forward decrypted cloves to their
 * target router or tunnel gateway. Also used by netdb for database
 * store/lookup replies.
 * <p>
 * Safe to run inline via {@link #runJob()}. If the RouterInfo for the Hash is not
 * found locally, it will queue a lookup and register itself to be run
 * again when the lookup succeeds or times out.
 */
public class SendMessageDirectJob extends JobImpl {
    /** Logger for the direct send job. */
    private final Log _log;
    /** The message to send. */
    private final I2NPMessage _message;
    /** The hash of the target router. */
    private final Hash _targetHash;
    /** The target's router info, once found. */
    private RouterInfo _router;
    /** When the message is too old to send. */
    private final long _expiration;
    /** Send priority of the message. */
    private final int _priority;
    /** Job run after a successful send. */
    private final Job _onSend;
    /** Job run when the reply arrives. */
    private final ReplyJob _onSuccess;
    /** Job run when the send fails. */
    private final Job _onFail;
    /** Selector matching the expected reply. */
    private final MessageSelector _selector;
    /** True once the floodfill search started. */
    private volatile boolean _alreadySearched;
    /** True once the message was sent. */
    private volatile boolean _sent;
    /** When to start the floodfill search. */
    private long _searchOn;
    /** Random mask for the message id bloom filter. */
    private final long _msgIDBloomXor;

    /**
     * Send a message directly to a peer.
     *
     * @param ctx the router context
     * @param message the message to send
     * @param toPeer may be ourselves
     * @param timeoutMs milliseconds to wait for delivery; under 5000 is raised to 10000
     * @param priority the OutNetMessage priority, one of the MessageContext constants
     */
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, int timeoutMs, int priority) {
        this(ctx, message, toPeer, null, null, null, null, timeoutMs, priority, 0);
    }

    /**
     * Send a message directly to a peer with an XOR mask.
     *
     * @param ctx the router context
     * @param message the message to send
     * @param toPeer may be ourselves
     * @param timeoutMs milliseconds to wait for delivery; under 5000 is raised to 10000
     * @param priority the OutNetMessage priority, one of the MessageContext constants
     * @param msgIDBloomXor value to xor the messageID with before passing to the InNetMessagePool, may be 0
     */
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, int timeoutMs, int priority, long msgIDBloomXor) {
        this(ctx, message, toPeer, null, null, null, null, timeoutMs, priority, msgIDBloomXor);
    }

    /**
     * Send a message directly to a peer with reply handling.
     *
     * @param ctx the router context
     * @param message the message to send
     * @param toPeer may be ourselves
     * @param onSuccess may be null
     * @param onFail may be null
     * @param selector selects which reply triggers onSuccess, or null to accept any reply
     * @param timeoutMs milliseconds to wait for delivery; under 5000 is raised to 10000
     * @param priority the OutNetMessage priority, one of the MessageContext constants
     */
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, ReplyJob onSuccess,
                                Job onFail, MessageSelector selector, int timeoutMs, int priority) {
        this(ctx, message, toPeer, null, onSuccess, onFail, selector, timeoutMs, priority, 0);
    }

    /**
     * Send a message directly to a peer with reply handling and XOR mask.
     *
     * @param ctx the router context
     * @param message the message to send
     * @param toPeer may be ourselves
     * @param onSuccess may be null
     * @param onFail may be null
     * @param selector selects which reply triggers onSuccess, or null to accept any reply
     * @param timeoutMs milliseconds to wait for delivery; under 5000 is raised to 10000
     * @param priority the OutNetMessage priority, one of the MessageContext constants
     * @param msgIDBloomXor value to xor the messageID with before passing to the InNetMessagePool, may be 0
     */
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, ReplyJob onSuccess,
                                Job onFail, MessageSelector selector, int timeoutMs, int priority, long msgIDBloomXor) {
        this(ctx, message, toPeer, null, onSuccess, onFail, selector, timeoutMs, priority, msgIDBloomXor);
    }

    /**
     * Send a message directly to a peer with full options.
     *
     * @param ctx the router context
     * @param message the message to send
     * @param toPeer may be ourselves
     * @param onSend may be null
     * @param onSuccess may be null
     * @param onFail may be null
     * @param selector selects which reply triggers onSuccess, or null to accept any reply
     * @param timeoutMs milliseconds to wait for delivery; under 5000 is raised to 10000
     * @param priority the OutNetMessage priority, one of the MessageContext constants
     * @param msgIDBloomXor value to xor the messageID with before passing to the InNetMessagePool, may be 0
     */
    public SendMessageDirectJob(RouterContext ctx, I2NPMessage message, Hash toPeer, Job onSend, ReplyJob onSuccess,
                                Job onFail, MessageSelector selector, int timeoutMs, int priority, long msgIDBloomXor) {
        super(ctx);
        _log = getContext().logManager().getLog(SendMessageDirectJob.class);
        _msgIDBloomXor = msgIDBloomXor;
        _message = message;
        _targetHash = toPeer;
        if (timeoutMs < 5*1000) {
            if (_log.shouldWarn())
                _log.warn("Very little time (" + timeoutMs + "ms) allocated to send direct message -> Setting to 10s...");
            _expiration = ctx.clock().now() + 10*1000;
        } else {
            _expiration = timeoutMs + ctx.clock().now();
        }
        _priority = priority;
        _onSend = onSend;
        _onSuccess = onSuccess;
        _onFail = onFail;
        _selector = selector;
        if (message == null)
            throw new IllegalArgumentException("Attempt to send a null message");
        if (_targetHash == null)
            throw new IllegalArgumentException("Attempt to send a message to a null peer");
    }

    @Override
    public String getName() { return "Send Direct Message"; }

    /**
     * Simple class name of the message being sent, unwrapping a
     * {@link TunnelGatewayMessage} to its inner message for a more
     * informative log entry.
     *
     * @return the simple class name of the message type
     */
    private String messageTypeName() {
        I2NPMessage m = _message;
        if (m instanceof TunnelGatewayMessage)
            m = ((TunnelGatewayMessage) m).getMessage();
        return m.getClass().getSimpleName();
    }

    public void runJob() {
        long now = getContext().clock().now();

        if (_expiration < now) {
            if (_log.shouldWarn()) {
                _log.warn("Timed out sending " + messageTypeName() + " message to [" +
                          _targetHash.toBase64().substring(0,6) + "], expired " + new Date(_expiration));
                if (_log.shouldDebug())
                    _log.debug("Message details:" + _message);
            }
            if (_onFail != null)
                getContext().jobQueue().addJob(_onFail);
            return;
        }

        if (_router != null) {
            if (_log.shouldDebug())
                _log.debug("Router specified, sending direct message...");
            send();
        } else {
            _router = getContext().netDb().lookupRouterInfoLocally(_targetHash);
            if (_router != null) {
                if (_log.shouldDebug())
                    _log.debug("Router not specified but lookup found it");
                send();
            } else {
                if (!_alreadySearched) {
                    if (_log.shouldDebug())
                        _log.debug("Router not specified, so we're looking for it...");
                    getContext().netDb().lookupRouterInfo(_targetHash, this, this,
                                                          _expiration - getContext().clock().now());
                    _searchOn = getContext().clock().now();
                    _alreadySearched = true;
                } else {
                    if (_log.shouldInfo()) {
                        _log.info("Unable to find router [" + _targetHash.toBase64().substring(0,6) +
                                  "] to send " + messageTypeName() + " message to after searching for " +
                                  (getContext().clock().now()-_searchOn) + "ms");
                        if (_log.shouldDebug())
                            _log.debug("Message details:" + _message);
                    }
                    if (_onFail != null)
                        getContext().jobQueue().addJob(_onFail);
                }
            }
        }
    }

    /** Sends the message to the target's router info. */
    private void send() {
        if (_sent) {
            if (_log.shouldWarn())
                _log.warn("Message already sent -> Not resent");
            return;
        }
        _sent = true;
        Hash to = _router.getIdentity().getHash();
        Hash us = getContext().routerHash();
        if (us.equals(to)) {
            if (_selector != null) {
                OutNetMessage outM = new OutNetMessage(getContext(), _message, _expiration, _priority, _router);
                outM.setOnFailedReplyJob(_onFail);
                outM.setOnFailedSendJob(_onFail);
                outM.setOnReplyJob(_onSuccess);
                outM.setOnSendJob(_onSend);
                outM.setReplySelector(_selector);
                getContext().messageRegistry().registerPending(outM);
            }

            if (_onSend != null)
                getContext().jobQueue().addJob(_onSend);

            getContext().inNetMessagePool().add(_message, _router.getIdentity(), null, _msgIDBloomXor);

            if (_log.shouldDebug())
                _log.debug("Adding " + _message.getClass().getName() +
                           " to Inbound message pool as it was destined for ourselves");
        } else {
            OutNetMessage msg = new OutNetMessage(getContext(), _message, _expiration, _priority, _router);
            msg.setOnFailedReplyJob(_onFail);
            msg.setOnFailedSendJob(_onFail);
            msg.setOnReplyJob(_onSuccess);
            msg.setOnSendJob(_onSend);
            msg.setReplySelector(_selector);
            getContext().outNetMessagePool().add(msg);
            if (_log.shouldDebug())
                _log.debug("Adding " + _message.getClass().getName() + " to Outbound message pool targeting [" +
                           _router.getIdentity().getHash().toBase64().substring(0,6) + "]");
        }
    }
}
