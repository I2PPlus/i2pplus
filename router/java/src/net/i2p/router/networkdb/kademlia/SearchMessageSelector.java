package net.i2p.router.networkdb.kademlia;

import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;
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
    /** The log for the selector. */
    private final Log _log;
    /** The router context. */
    private final RouterContext _context;
    /** The next selector id. */
    private static final AtomicInteger __searchSelectorId = new AtomicInteger();
    /** The peer being searched for. */
    private final Hash _peer;
    /** True once a matching message was found. */
    private volatile boolean _found;
    /** The id of this selector. */
    private final int _id;
    /** When the selector expires. */
    private final long _exp;
    /** The state of the search. */
    private final SearchState _state;

    /**
     * Claims a selector id and records the peer, deadline and search state.
     *
     * @param context the router context supplying the log and the search state
     * @param peer the router the search replies are expected from
     * @param expiration the time in milliseconds after which the reply is no longer waited for
     * @param state the search being run, holding the target key and the remaining peers
     */
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

    /**
     * Debug description of this selector.
     */
    @Override
    public String toString() {
        return "Search selector \n* Looking for reply from [" + _peer.toBase64().substring(0,6) +
                 "] for key [" + _state.getTarget().toBase32().substring(0,8) + "]";
    }

    /**
     * Continue matching until the search expires or the value is found.
     */
    public boolean continueMatching() {
        boolean expired = _context.clock().now() > _exp;
        if (expired) return false;

        // So we don't drop outstanding replies after receiving the value
        // > 1 to account for the 'current' match
        if (_state.getPendingSize() > 1)
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

    /**
     * Absolute time when this selector stops matching.
     *
     * @return the time in milliseconds after the epoch at which matching stops,
     *         taken from the expiration passed to the constructor
     */
    @Override
    public long getExpiration() { return _exp; }

    /**
     * Whether the message is a store for the searched key.
     *
     * @return true for a DatabaseStoreMessage carrying the searched key, or a
     *         DatabaseSearchReplyMessage from the queried peer for that key; either
     *         match records the hit in {@code _found}
     */
    public boolean isMatch(I2NPMessage message) {
        int type = message.getType();
        if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
            DatabaseStoreMessage msg = (DatabaseStoreMessage)message;
            if (msg.getKey().equals(_state.getTarget())) {
                if (_log.shouldDebug())
                    _log.debug("[ID " + _id + "] Received a DbStore of the key we are looking for" +
                               "\n* It may not be from the peer we are checking against," +
                               " because a DbStore does not say who sent it");
                _found = true;
                return true;
            }
        } else if (type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
            DatabaseSearchReplyMessage msg = (DatabaseSearchReplyMessage)message;
            if (_peer.equals(msg.getFromHash()) && msg.getSearchKey().equals(_state.getTarget())) {
                    if (_log.shouldDebug())
                        _log.debug("[ID " + _id + "] Received DbSearchReply from queried peer for a key we're looking for");
                    _found = true;
                    return true;
            }
        }
        return false;
    }
}
