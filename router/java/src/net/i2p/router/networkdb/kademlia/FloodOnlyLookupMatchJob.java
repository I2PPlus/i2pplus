package net.i2p.router.networkdb.kademlia;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Handles match processing for flood-only search operations.
 * <p>
 * This job processes responses to flood-only searches, determining success or failure
 * based on received database store messages. It coordinates with the parent search job
 * to signal completion and handles LeaseSet reply marking appropriately.
 * <p>
 * Extends the base match job functionality to work specifically with flood-based
 * search strategies where responses are processed asynchronously.
 */
class FloodOnlyLookupMatchJob extends JobImpl implements ReplyJob {
    protected final Log _log;
    protected final FloodSearchJob _search;
    protected volatile boolean _success;

    public FloodOnlyLookupMatchJob(RouterContext ctx, FloodSearchJob job) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _search = job;
    }

    public void runJob() {
        if (_success) {
            if (_log.shouldInfo()) {
                _log.info("Search for key [" + _search.getKey().toBase32().substring(0,8) + "] found locally");
            }
            _search.success();
        } else {
            // In practice, we always have zero remaining when this is called,
            // because the selector only returns true when there is zero remaining
            if (_log.shouldLog(Log.INFO)) {
                _log.info("Search failed for key [" + _search.getKey().toBase32().substring(0,8) + "]");
            }
            _search.failed();
        }
    }

    public String getName() { return "Match NetDb FloodSearch"; }

    public void setMessage(I2NPMessage message) {
        int mtype = message.getType();
        if (mtype == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
            // DSRM processing now in FloodOnlyLookupSelector instead of here,
            // a dsrm is only passed in when there are no more lookups remaining
            // so that all DSRM's are processed, not just the last one.
            _search.failed();
            return;
        }
        if (mtype != DatabaseStoreMessage.MESSAGE_TYPE)
            return;

        DatabaseStoreMessage dsm = (DatabaseStoreMessage)message;
        if (_log.shouldDebug()) {
            _log.debug("Received a DbStoreMsg for key [" + dsm.getKey().toBase32().substring(0,8) + "]");
        }
        // This store will handled by HFDSMJ.
        // Just note success here.
        if (dsm.getKey().equals(_search.getKey())) {_success = true;}
        DatabaseEntry entry = dsm.getEntry();
        int type = entry.getType();
        if (DatabaseEntry.isLeaseSet(type)) {
            // Since HFDSMJ wants to setReceivedAsPublished(), we have to
            // set a flag saying this was really the result of a query,
            // so don't do that.
            LeaseSet ls = (LeaseSet) dsm.getEntry();
            ls.setReceivedAsReply();
        }
    }
}
