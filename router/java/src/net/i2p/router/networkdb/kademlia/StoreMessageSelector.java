package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Check to see the message is a reply from the peer regarding the current
 * store
 *
 */
class StoreMessageSelector implements MessageSelector {
    private final Log _log;
    private final Hash _peer;
    private final long _storeJobId;
    private final long _waitingForId;
    private final long _expiration;
    private volatile boolean _found;

    /**
     *  @param storeJobId just for logging
     *  @param peer just for logging
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
//        if (_log.shouldDebug())
//            _log.debug("[Job " + _storeJobId + "] isMatch -> " + message.getClass().getName().replace("net.i2p.data.i2np.", "").replace("Message", "Msg").replace("Database", "Db") +
//                       "\n* Requested: DeliveryStatusMsg from [" + _peer.toBase64().substring(0,6) + "]");
//            _log.debug("[Job " + _storeJobId + "] isMatch -> " + message.getClass().getSimpleName() +
//                       "\n* Requested: DeliveryStatusMsg from [" + _peer.toBase64().substring(0,6) + "]");
        if (message.getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
            DeliveryStatusMessage msg = (DeliveryStatusMessage)message;
            if (msg.getMessageId() == _waitingForId) {
                if (_log.shouldInfo())
                    _log.info("[Job " + _storeJobId + "] Found match for the key we're waiting for [MsgID " + _waitingForId  + "]");
                _found = true;
                return true;
            } else {
//                if (_log.shouldDebug())
//                    _log.debug("[Job " + _storeJobId + "] Received DeliveryStatusMsg [MsgID " + msg.getMessageId() +
//                               "] but waiting for [MsgID " + _waitingForId + "]");
                return false;
            }
        } else {
                //if (_log.shouldDebug())
                //_log.debug("[Job " + _storeJobId + "] Not a DeliveryStatusMsg");
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder rv = new StringBuilder(128);
        rv.append("Waiting for NetDb confirm from ").append(_peer).append(", found? ");
        rv.append(_found).append(" waiting for [MsgID ").append(_waitingForId).append("]");
        return rv.toString();
    }
}

