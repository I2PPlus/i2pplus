package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Search for a particular key iteratively until we either find a value, we run
 * out of peers, or the bucket the key belongs in has sufficient values in it.
 * Well, we're skipping the 'bucket gets filled up' test for now, since it'll never
 * get used (at least for a while).
 *
 */
class ExploreJob extends SearchJob {
    private final FloodfillPeerSelector _peerSelector;
    private final boolean _isRealExplore;

    /** how long each exploration should run for
     *  The exploration won't "succeed" so we make it long so we query several peers */
//    private static final long MAX_EXPLORE_TIME = 30*1000;
    private static final long MAX_EXPLORE_TIME = 45*1000;

    /** how many peers to explore through concurrently */
    static final String PROP_EXPLORE_BREDTH = "router.exploreBredth";
    private static final int EXPLORE_BREDTH = 1;

    /** Only send the closest "don't tell me about" refs...
     *  Override to make this bigger because we want to include both the
     *  floodfills and the previously-queried peers */
//    static final int MAX_CLOSEST = 20; // LINT -- field hides another field, this isn't an override.
    static final int MAX_CLOSEST = 32; // LINT -- field hides another field, this isn't an override.

    /** Override to make this shorter, since we don't sort out the
     *  unresponsive ff peers like we do in FloodOnlySearchJob */
    static final int PER_FLOODFILL_PEER_TIMEOUT = 5*1000; // LINT -- field hides another field, this isn't an override.

    /**
     * Create a new search for the routingKey specified
     *
     * @param isRealExplore if true, a standard exploration (no floodfills will be returned)
     *                      if false, a standard lookup (floodfills will be returned, use if low on floodfills)
     */
    public ExploreJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, boolean isRealExplore) {
        // note that we're treating the last param (isLease) as *false* since we're just exploring.
        // if this collides with an actual leaseSet's key, neat, but that wouldn't imply we're actually
        // attempting to send that lease a message!
        super(context, facade, key, null, null, MAX_EXPLORE_TIME, false, false);
        _peerSelector = (FloodfillPeerSelector) (_facade.getPeerSelector());
        _isRealExplore = isRealExplore;
    }

    /**
     * Build the database search message, but unlike the normal searches, we're more explicit in
     * what we /don't/ want.  We don't just ask them to ignore the peers we've already searched
     * on, but to ignore a number of the peers we already know about (in the target key's bucket) as well.
     *
     * Perhaps we may want to ignore other keys too, such as the ones in nearby
     * buckets, but we probably don't want the dontIncludePeers set to get too
     * massive (aka sending the entire routing table as 'don't tell me about these
     * guys').  but maybe we do.  dunno.  lots of implications.
     *
     * FloodfillPeerSelector would add only the floodfill peers,
     * and PeerSelector doesn't include the floodfill peers,
     * so we add the ff peers ourselves and then use the regular PeerSelector.
     *
     * @param replyTunnelId tunnel to receive replies through, or our router hash if replyGateway is null
     * @param replyGateway gateway for the reply tunnel, if null, we are sending direct, do not encrypt
     * @param expiration when the search should stop
     * @param peer the peer to send it to
     *
     * @return a DatabaseLookupMessage or GarlicMessage or null on error
     */
    @Override
    protected I2NPMessage buildMessage(TunnelId replyTunnelId, Hash replyGateway, long expiration, RouterInfo peer) {
        DatabaseLookupMessage msg = new DatabaseLookupMessage(getContext(), true);
        msg.setSearchKey(getState().getTarget());
        msg.setFrom(replyGateway);
        // Moved below now that DLM makes a copy
        //msg.setDontIncludePeers(getState().getClosestAttempted(MAX_CLOSEST));
        Set<Hash> dontIncludePeers = getState().getClosestAttempted(MAX_CLOSEST);
        msg.setMessageExpiration(expiration);
        if (replyTunnelId != null)
            msg.setReplyTunnel(replyTunnelId);

        int available = MAX_CLOSEST - dontIncludePeers.size();
        if (_isRealExplore) {
            // supported as of 0.9.16. We don't add "fake hash" any more.
            msg.setSearchType(DatabaseLookupMessage.Type.EXPL);
        } else {
            msg.setSearchType(DatabaseLookupMessage.Type.RI);
        }

        KBucketSet<Hash> ks = _facade.getKBuckets();
        Hash rkey = getContext().routingKeyGenerator().getRoutingKey(getState().getTarget());
        // in a few releases, we can (and should) remove this,
        // as routers will honor the above flag, and we want the table to include
        // only non-floodfills.
        // Removed in 0.8.8, good thing, as we had well over MAX_CLOSEST floodfills.
        //if (available > 0 && ks != null) {
        //    List peers = _peerSelector.selectFloodfillParticipants(rkey, available, ks);
        //    int len = peers.size();
        //    if (len > 0)
        //        msg.getDontIncludePeers().addAll(peers);
        //}

        available = MAX_CLOSEST - dontIncludePeers.size();
        if (available > 0) {
            // selectNearestExplicit adds our hash to the dontInclude set (3rd param) ...
            // And we end up with MAX_CLOSEST+1 entries.
            // We don't want our hash in the message's don't-include list though.
            // We're just exploring, but this could give things away, and tie our exploratory tunnels to our router,
            // so let's not put our hash in there.
            Set<Hash> dontInclude = new HashSet<Hash>(dontIncludePeers);
            List<Hash> peers = _peerSelector.selectNearestExplicit(rkey, available, dontInclude, ks);
            dontIncludePeers.addAll(peers);
        }

        StringBuilder buf = new StringBuilder();
        buf.append("Excluding " + (dontIncludePeers.size() - 1) + " closest peers from exploration\n* Excluded: ");
        for (Hash h : dontIncludePeers) {
            buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(buf.toString());
//        else if (_log.shouldLog(Log.INFO))
//            _log.info("Excluding our " + (dontIncludePeers.size() - 1) + " closest peers from exploration");
//            _log.debug("Peers we don't want to hear about: " + dontIncludePeers);

        msg.setDontIncludePeers(dontIncludePeers);

        // Now encrypt if we can
        I2NPMessage outMsg;
        if (replyTunnelId != null &&
            getContext().getProperty(IterativeSearchJob.PROP_ENCRYPT_RI, IterativeSearchJob.DEFAULT_ENCRYPT_RI)) {
            // request encrypted reply?
            if (DatabaseLookupMessage.supportsEncryptedReplies(peer)) {
                MessageWrapper.OneTimeSession sess;
                sess = MessageWrapper.generateSession(getContext());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("[Job " + getJobId() + "] Requesting encrypted reply from [" +
                               peer.getIdentity().calculateHash().toBase64().substring(0,6) +
                               "]\n* Session key: " + sess.key + "\n* Tag: " + sess.tag);
                msg.setReplySession(sess.key, sess.tag);
            }
            // may be null
            outMsg = MessageWrapper.wrap(getContext(), msg, peer);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("[Job " + getJobId() + "] Encrypted Exploratory DbLookupMessage for [" +
                           getState().getTarget().toBase64().substring(0,6) + "] sent to [" +
                           peer.getIdentity().calculateHash().toBase64().substring(0,6) + "]");
        } else {
            outMsg = msg;
        }
        return outMsg;
    }

    /** max # of concurrent searches */
    @Override
    protected int getBredth() {
        String exploreBredth = getContext().getProperty("router.exploreBredth");
        if (exploreBredth == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("[Job " + getJobId() + "] Initiating Exploratory Search");
            return EXPLORE_BREDTH;
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("[Job " + getJobId() + "] Initiating Exploratory Search - max " + exploreBredth + " concurrent");
            return Integer.valueOf(exploreBredth);
        }
    }

    /**
     * We've gotten a search reply that contained the specified
     * number of peers that we didn't know about before.
     *
     */
    @Override
    protected void newPeersFound(int numNewPeers) {
        // who cares about how many new peers.  well, maybe we do.  but for now,
        // we'll do the simplest thing that could possibly work.
        if (_log.shouldLog(Log.INFO)) {
            if (numNewPeers == 1)
                _log.info("[Job " + getJobId() + "] Found " + numNewPeers + " new peer via Exploratory Search");
            else if (numNewPeers > 1)
                _log.info("[Job " + getJobId() + "] Found " + numNewPeers + " new peers via Exploratory Search");
            else
                _log.info("[Job " + getJobId() + "] Found no new peers via Exploratory Search");
        }
        _facade.setLastExploreNewDate(getContext().clock().now());
    }

    /*
     * We could override searchNext to see if we actually fill up a kbucket before
     * the search expires, but, c'mon, the keyspace is just too bloody massive, and
     * buckets won't be filling anytime soon, so might as well just use the SearchJob's
     * searchNext
     *
     */

    @Override
    public String getName() { return "Explore Kademlia NetDb"; }
}
