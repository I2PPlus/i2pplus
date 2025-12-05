package net.i2p.router.networkdb.kademlia;

import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Message selector for matching search operation responses from specific peers.
 * <p>
 * Filters incoming DatabaseStoreMessage and DatabaseSearchReplyMessage
 * to identify responses corresponding to ongoing search operations.
 * Tracks match status, expiration times, and coordinates with
 * SearchState to manage pending peer queries.
 * <p>
 * Provides unique identification for each selector instance and
 * implements continuation logic based on search progress and
 * timeout conditions. Ensures proper response correlation
 * between sent queries and received replies.
 */
class SearchMessageSelector implements MessageSelector {
    private final Log _log;
    private final RouterContext _context;
    private static final AtomicInteger __searchSelectorId = new AtomicInteger();
    private final Hash _peer;
    private volatile boolean _found;
    private final int _id;
    private final long _exp;
    private final SearchState _state;

    public SearchMessageSelector(RouterContext context, RouterInfo peer, long expiration, SearchState state) {
        _context = context;
        _log = context.logManager().getLog(SearchMessageSelector.class);
        _peer = peer.getIdentity().getHash();
        _exp = expiration;
        _state = state;
        _id = __searchSelectorId.incrementAndGet();
        if (_log.shouldDebug())
            _log.debug("[ID " + _id + "] Created: " + toString());
    }

    @Override
    public String toString() {
        return "Search selector \n* Looking for reply from [" + _peer.toBase64().substring(0,6) +
                 "] for key [" + _state.getTarget().toBase32().substring(0,8) + "]";
    }

    public boolean continueMatching() {
        boolean expired = _context.clock().now() > _exp;
        if (expired) return false;

        // So we don't drop outstanding replies after receiving the value
        // > 1 to account for the 'current' match
        if (_state.getPending().size() > 1)
            return true;

        if (_found) {
            if (_log.shouldDebug())
                _log.debug("[ID " + _id + "] Don't continue matching! Looking for reply from [" +
                           _peer.toBase64().substring(0,6) + "] for key [" + _state.getTarget().toBase32().substring(0,8) + "]");
            return false;
        } else {
            return true;
        }
    }

    public long getExpiration() { return _exp; }

    public boolean isMatch(I2NPMessage message) {
        int type = message.getType();
        if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
            DatabaseStoreMessage msg = (DatabaseStoreMessage)message;
            if (msg.getKey().equals(_state.getTarget())) {
                if (_log.shouldDebug())
                    _log.debug("[ID " + _id + "] Received DbStore of the key we're looking for. " +
                               "May not have been from peer we're checking against though, " +
                               "but DBStore doesn't include that info");
                _found = true;
                return true;
            }
        } else if (type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
            DatabaseSearchReplyMessage msg = (DatabaseSearchReplyMessage)message;
            if (_peer.equals(msg.getFromHash())) {
                if (msg.getSearchKey().equals(_state.getTarget())) {
                    if (_log.shouldDebug())
                        _log.debug("[ID " + _id + "] Received DbSearchReply from queried peer for a key we're looking for");
                    _found = true;
                    return true;
                }
            }
        }
        return false;
    }
}
