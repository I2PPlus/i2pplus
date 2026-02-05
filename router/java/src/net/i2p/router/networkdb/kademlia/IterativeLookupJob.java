package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.router.BanLogger;

/**
 * Processes DatabaseSearchReplyMessage responses during iterative searches.
 * <p>
 * Handles the followup lookups for RouterInfo entries returned in DSRM messages
 * during iterative Kademlia searches. For each peer hash in the reply, determines
 * whether to fetch new RouterInfo data or refresh existing entries based on
 * age and floodfill status.
 * <p>
 * Optimizes network integration by preferentially querying routers directly
 * for their own RouterInfo when existing data is stale or the router is not
 * confirmed as floodfill. This helps establish reliable floodfill peers
 * and accelerates network discovery.
 * <p>
 * Extracted from IterativeLookupSelector to prevent deadlocks with the
 * OutboundMessageRegistry during concurrent search operations.
 *
 * @since 0.8.9
 */

class IterativeLookupJob extends JobImpl {
    private static final long ONE_HOUR_MS = 60 * 60 * 1000;
    private static final int UNSOLICITED_DSRM_THRESHOLD = 3;
    private static final long DSRM_BAN_MS = 8*60*60*1000;
    private static final ObjectCounter<Hash> _unsolicitedDSRM = new ObjectCounter<Hash>();
    private final Log _log;
    private final DatabaseSearchReplyMessage _dsrm;
    private final IterativeSearchJob _search;
    private BanLogger _banLogger;

    public IterativeLookupJob(RouterContext ctx, DatabaseSearchReplyMessage dsrm, IterativeSearchJob search) {
        super(ctx);
        _log = ctx.logManager().getLog(IterativeLookupJob.class);
        _dsrm = dsrm;
        _search = search;
        _banLogger = new BanLogger(ctx);
    }

    @Override
    public void runJob() {
        RouterContext ctx = getContext();
        Hash from = _dsrm.getFromHash();
        
        // Check for algorithmic bot patterns before processing
        if (_banLogger.checkPatternAndPredict(from, ctx)) {
            return; // Router was predictively banned
        }
        
        long now = ctx.clock().now();
        Log log = _log;
        if (!_search.wasQueried(from)) {
            if (log.shouldWarn()) {
                boolean isBanlisted = ctx.banlist().isBanlisted(from);
                String msg = "Received unsolicited DbSearchReply message from [" + from.toBase64().substring(0, 6) + "]";
                if (isBanlisted) {msg += " (Router is banlisted)";}
                log.warn(msg);
            }
            // Track unsolicited DSRM and ban repeat offenders
            int count = _unsolicitedDSRM.increment(from);
            if (count >= UNSOLICITED_DSRM_THRESHOLD && !ctx.banlist().isBanlisted(from)) {
                ctx.banlist().banlistRouter(from, " <b>➜</b> Unsolicited DbSearchReply", null, null, now + DSRM_BAN_MS);
                _banLogger.logBan(from, "", "Unsolicited DbSearchReply", DSRM_BAN_MS);
                if (log.shouldWarn()) {
                    log.warn("Banning [" + from.toBase64().substring(0, 6) + "] after " + count + " unsolicited DbSearchReply");
                }
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
