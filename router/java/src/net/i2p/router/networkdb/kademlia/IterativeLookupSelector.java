package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Slightly modified version of FloodOnlyLookupSelector.
 * Always follows the DSRM entries.
 *
 * @since 0.8.9
 */
class IterativeLookupSelector implements MessageSelector {
    /** The router running the lookup. */
    private final RouterContext _context;
    /** The search choosing these peers. */
    private final IterativeSearchJob _search;
    /** True once a peer holding the key answered. */
    private boolean _matchFound;
    /** Logger for this class. */
    private final Log _log;

    /**
     * Binds the selector to the iterative search whose replies it matches.
     *
     * @param ctx router context used for the job queue and logging
     * @param search the job whose key and expiration this selector matches on
     */
    public IterativeLookupSelector(RouterContext ctx, IterativeSearchJob search) {
        _context = ctx;
        _search = search;
        _log = ctx.logManager().getLog(getClass());
    }

    /**
     * Continue matching until a match is found or the search expires.
     */
    @Override
    public boolean continueMatching() {
        // don't use remaining searches count
        return (!_matchFound) && _context.clock().now() < getExpiration();
    }

    /**
     * Absolute time when the search expires, or -1 once a match is found.
     *
     * @return milliseconds since the epoch, or -1 after a match
     */
    public long getExpiration() { return (_matchFound ? -1 : _search.getExpiration()); }

    /**
     * This only returns true for DSMs, not for DSRMs.
     * @return true if the message carries the key being searched for; a DSRM
     *         naming the key only returns false
     */
    public boolean isMatch(I2NPMessage message) {
        if (message == null) return false;
        int type = message.getType();
        if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
            // is it worth making sure the reply came in on the right tunnel?
            if (_search.getKey().equals(dsm.getKey())) {
                _matchFound = true;
                if (_log.shouldDebug())
                    _log.debug("DbStoreMsg match for [" + _search.getKey().toBase64().substring(0, 6) + "] * " + this);
                return true;
            }
        } else if (type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
            DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage)message;
            if (_search.getKey().equals(dsrm.getSearchKey())) {
                // Got a netDb reply pointing us at other floodfills...
                if (_log.shouldInfo()) {
                    Hash from = dsrm.getFromHash();
                    _log.info("Processing search reply from [" + from.toBase64().substring(0,6) + "] via IterativeLookup for [" +
                              _search.getKey().toBase64().substring(0,6) + "]");
                    if (_log.shouldDebug()) _log.debug("Search reply: " + this);
                }

                // was inline, now in IterativeLookupJob due to deadlocks
                _context.jobQueue().addJob(new IterativeLookupJob(_context, dsrm, _search));

                // fall through, always return false, we do not wish the match job to be called
            }
        }
        return false;
    }

    /**
     * Returns a string representation of this selector.
     *
     * @since 0.9.12
     */
    @Override
    public String toString() {
        return "IterativeLookupSelector for " + _search.getKey();
    }
}
