package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.SystemVersion;
//import net.i2p.util.Log;

/**
 * Performs targeted followup lookups for RouterInfo entries from search replies.
 * <p>
 * Processes RouterInfo hashes received in DatabaseSearchReplyMessage by
 * initiating direct queries to obtain missing or stale RouterInfo data.
 * For each unknown peer, queries the peer that provided the hash; for
 * known peers with outdated data or non-floodfill status, queries the
 * peer directly to refresh the information.
 * <p>
 * This lightweight version of SearchReplyJob focuses specifically on
 * RouterInfo acquisition without profile management overhead. Designed
 * for efficient network integration and floodfill peer discovery.
 * <p>
 * Limits concurrent followup operations based on system performance
 * to prevent resource exhaustion during high-volume search responses.
 */
class SingleLookupJob extends JobImpl {
    //private final Log _log;
    private final DatabaseSearchReplyMessage _dsrm;

    /**
     *  I2NP spec allows 255, max actually sent (in ../HDLMJ) is 3,
     *  so just to prevent trouble, we don't want to queue 255 jobs at once
     */
//    public static final int MAX_TO_FOLLOW = 8;
    public static final int MAX_TO_FOLLOW = SystemVersion.isSlow() || SystemVersion.getCPULoadAvg() > 90 ? 6 : 12;

    public SingleLookupJob(RouterContext ctx, DatabaseSearchReplyMessage dsrm) {
        super(ctx);
        //_log = ctx.logManager().getLog(getClass());
        _dsrm = dsrm;
    }

    public void runJob() {
        Hash from = _dsrm.getFromHash();
        int limit = Math.min(_dsrm.getNumReplies(), MAX_TO_FOLLOW);
        for (int i = 0; i < limit; i++) {
            Hash peer = _dsrm.getReply(i);
            if (peer.equals(getContext().routerHash())) // us
                continue;
            if (peer.equals(from)) // unusual?
                continue;
            RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(peer);
            if (ri == null)
                getContext().jobQueue().addJob(new SingleSearchJob(getContext(), peer, from));
            else if (ri.getPublished() < getContext().clock().now() - 60*60*1000 ||
                     !FloodfillNetworkDatabaseFacade.isFloodfill(ri))
                getContext().jobQueue().addJob(new SingleSearchJob(getContext(), peer, peer));
        }
    }

    public String getName() { return "Process DbStoreReplyMsg"; }
}
