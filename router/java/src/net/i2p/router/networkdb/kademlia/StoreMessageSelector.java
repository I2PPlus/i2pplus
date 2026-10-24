package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Message selector for matching database store operation confirmations.
 * <p>
 * Filters incoming DeliveryStatusMessage responses to identify
 * acknowledgments for specific store operations. Tracks message
 * IDs, peer information, and expiration times to correlate
 * delivery confirmations with pending store jobs.
 * <p>
 * Provides logging support for store operation monitoring
 * and implements simple match-once logic to prevent
 * duplicate processing of delivery confirmations.
 */
class StoreMessageSelector implements MessageSelector {
    /** Logger for this class. */
    private final Log _log;
    /** The peer whose reply we are waiting for. */
    private final Hash _peer;
    /** Message id of the store we sent. */
    private final long _storeJobId;
    /** Message id of the reply we expect. */
    private final long _waitingForId;
    /** Time past which the reply is no longer wanted. */
    private final long _expiration;
    /** True once the reply has been matched. */
    private volatile boolean _found;

    /**
     * Creates a new message selector for database store confirmations.
     *
     * @param ctx the context supplying the log this selector reports matches to
     * @param storeJobId just for logging
     * @param peer just for logging
     * @param waitingForId the unique ID carried by the DeliveryStatusMessage that confirms our store
     * @param expiration the time in milliseconds after which the reply is no longer waited for
     */
    public StoreMessageSelector(RouterContext ctx, long storeJobId, RouterInfo peer, long waitingForId,
                                long expiration) {
        _log = ctx.logManager().getLog(StoreMessageSelector.class);
        _peer = peer.getIdentity().getHash();
        _storeJobId = storeJobId;
        _waitingForId = waitingForId;
        _expiration = expiration;
    }

    public boolean continueMatching() { return !_found; }

    public long getExpiration() { return _expiration; }

    public boolean isMatch(I2NPMessage message) {
        if (message.getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
            DeliveryStatusMessage msg = (DeliveryStatusMessage)message;
            if (msg.getMessageId() == _waitingForId) {
                if (_log.shouldInfo())
                    _log.info("[Job " + _storeJobId + "] Found match for the key we're waiting for [MsgID " + _waitingForId  + "]");
                _found = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "Waiting for NetDb confirm from " + _peer + ", found? " + _found + " waiting for [MsgID " + _waitingForId + "]";
    }
}

