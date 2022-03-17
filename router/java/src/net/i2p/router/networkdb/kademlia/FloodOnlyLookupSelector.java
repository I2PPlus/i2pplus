package net.i2p.router.networkdb.kademlia;

import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.MessageSelector;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Mostly replaced by IterativeLookupSelector
 */
class FloodOnlyLookupSelector implements MessageSelector {
    private final RouterContext _context;
    private final FloodOnlySearchJob _search;
    private boolean _matchFound;
    private final Log _log;

    public FloodOnlyLookupSelector(RouterContext ctx, FloodOnlySearchJob search) {
        _context = ctx;
        _search = search;
        _log = ctx.logManager().getLog(getClass());
    }

    public boolean continueMatching() {
        return _search.getLookupsRemaining() > 0 && !_matchFound && _context.clock().now() < getExpiration();
    }

    public long getExpiration() { return (_matchFound ? -1 : _search.getExpiration()); }

    public boolean isMatch(I2NPMessage message) {
        if (message == null) return false;
        int type = message.getType();
        if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
            DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
            // is it worth making sure the reply came in on the right tunnel?
            if (_search.getKey().equals(dsm.getKey())) {
                _search.decrementRemaining();
                _matchFound = true;
                return true;
            }
        } else if (type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
            DatabaseSearchReplyMessage dsrm = (DatabaseSearchReplyMessage)message;
            if (_search.getKey().equals(dsrm.getSearchKey())) {

                // TODO - dsrm.getFromHash() can't be trusted - check against the list of
                // those we sent the search to in _search ?

                // assume 0 new, all old, 0 invalid, 0 dup
                _context.profileManager().dbLookupReply(dsrm.getFromHash(),  0, dsrm.getNumReplies(), 0, 0,
                                                        System.currentTimeMillis()-_search.getCreated());

                // Moved from FloodOnlyLookupMatchJob so it is called for all replies
                // rather than just the last one
                // Got a netDb reply pointing us at other floodfills...
                // Only process if we don't know enough floodfills or are starting up
                if (_search.shouldProcessDSRM()) {
                    if (_log.shouldInfo())
                        _log.info("[Job " + _search.getJobId() + "] Processing DbSearchReplyMsg via SingleLookupJob from [" +
                                  dsrm.getFromHash().toBase64().substring(0,6) + "]");
                    // Chase the hashes from the reply
                    _context.jobQueue().addJob(new SingleLookupJob(_context, dsrm));
                } else if (_log.shouldInfo()) {
                    int remaining = _search.getLookupsRemaining();
                    _log.info("[Job " + _search.getJobId() + "] DbSearchReplyMsg from [" + dsrm.getFromHash().toBase64().substring(0,6) + "] while looking for ["
                              + _search.getKey().toBase64().substring(0,6) + "] with " + remaining + " outstanding searches");
                }

                // if no more left, time to fail
                int remaining = _search.decrementRemaining(dsrm.getFromHash());
                return remaining <= 0;
            }
        }
        return false;
    }

    /** @since 0.9.12 */
    public String toString() {
        return "FloodOnlyLookup Selector for " + _search.getKey();
    }
}
