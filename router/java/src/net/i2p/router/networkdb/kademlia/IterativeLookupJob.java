package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.util.Log;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

/**
 * Ask the peer who sent us the DSRM for the RouterInfos...
 *
 * ... but if we have the routerInfo already, try to refetch it from that router itself,
 * (if the info is old or we don't think it is floodfill) which will help us establish
 * that router as a good floodfill and speed our integration into the network.
 *
 * Very similar to SingleLookupJob.
 *
 * This was all in IterativeLookupSelector.isMatch() but it caused deadlocks
 * with OutboundMessageRegistry.getOriginalMessages()
 * at both _search.newPeerToTry() and _search.failed().
 *
 * @since 0.8.9
 */

class IterativeLookupJob extends JobImpl {
    private static final long ONE_HOUR_MS = 60 * 60 * 1000;
    private final Log _log;
    private final DatabaseSearchReplyMessage _dsrm;
    private final IterativeSearchJob _search;

    public IterativeLookupJob(RouterContext ctx, DatabaseSearchReplyMessage dsrm, IterativeSearchJob search) {
        super(ctx);
        _log = ctx.logManager().getLog(IterativeLookupJob.class);
        _dsrm = dsrm;
        _search = search;
    }

    @Override
    public void runJob() {
        RouterContext ctx = getContext();
        Hash from = _dsrm.getFromHash();
        long now = ctx.clock().now();
        Log log = _log;
        if (!_search.wasQueried(from)) {
            if (log.shouldWarn()) {
                boolean isBanlisted = ctx.banlist().isBanlisted(from);
                String msg = "Received unsolicited DbSearchReply message from [" + from.toBase64().substring(0, 6) + "]";
                if (isBanlisted) {msg += " (Router is banlisted)";}
                log.warn(msg);
            }
            return;
        }

        // Chase the hashes from the reply - 255 max, see comments in SingleLookupJob
        int limit = Math.min(_dsrm.getNumReplies(), SingleLookupJob.MAX_TO_FOLLOW);
        Hash ourHash = ctx.routerHash();
        int newPeers = 0, oldPeers = 0, invalidPeers = 0;

        for (int i = 0; i < limit; i++) {
            Hash peer = _dsrm.getReply(i);
            if (peer.equals(ourHash)) {
                oldPeers++;
                continue;
            }
            if (peer.equals(from)) {
                invalidPeers++;
                continue;
            }
            if (ctx.banlist().isBanlistedForever(peer) || ctx.banlist().isBanlisted(peer)) {
                oldPeers++;
                continue;
            }
            RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
            if (ri == null) {
                // Take it on faith that it's ff to speed things up, we don't need the RI to query it.
                // Zero-hop outbound tunnel will be failed in ISJ.sendQuery()
                _search.newPeerToTry(peer);
                if (_search.getFromHash() == null) {
                    // Get the RI from the peer that told us about it,
                    // only if original search used expl. tunnels
                    ctx.jobQueue().addJob(new SingleSearchJob(ctx, peer, from));
                }
                newPeers++;
            } else {
                long published = ri.getPublished();
                if (published < now - ONE_HOUR_MS || !FloodfillNetworkDatabaseFacade.isFloodfill(ri)) {
                    if (_search.getFromHash() == null) {
                        ctx.jobQueue().addJob(new IterativeFollowupJob(ctx, peer, peer, _search));
                    }
                    oldPeers++;
                } else {
                    // Add it to the sorted queue - this will check if we have already tried it
                    _search.newPeerToTry(peer);
                    oldPeers++;
                }
            }
        }

        if (log.shouldInfo()) {
            log.info("IterativeLookup -> DbSearchReplyMsg\n* Processed: " + newPeers +
                     " new, " + oldPeers + " old, and " + invalidPeers + " invalid hashes");
        }

        long timeSent = _search.timeSent(from);
        if (timeSent > 0) {
            ctx.profileManager().dbLookupReply(from, newPeers, oldPeers, invalidPeers, 0, now - timeSent);
        }
        _search.failed(from, false);
    }

    @Override
    public String getName() {return "Process DbStoreReplyMsg";}
}
