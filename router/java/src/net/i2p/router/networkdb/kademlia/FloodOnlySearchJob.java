package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.kademlia.KBucketSet;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Uunused directly, replaced by IterativeSearchJob, but still extended by
 * SingleSearchJob.
 *
 * Try sending a search to some floodfill peers, failing completely if we don't get
 * a match from one of those peers, with no fallback to the kademlia search
 *
 * Exception (a semi-exception, since we still fail completely without fallback):
 * If we don't know any floodfill peers, we ask a couple of peers at random,
 * who will hopefully reply with some floodfill keys.
 * We still fail without fallback, but we then spin off a job to
 * ask that same random peer for the RouterInfos for those keys.
 * If that job succeeds, the next search should work better.
 *
 * In addition, we follow the floodfill keys in the DSRM
 * (DatabaseSearchReplyMessage) if we know less than 4 floodfills.
 *
 * These enhancements allow the router to bootstrap back into the network
 * after it loses (or never had) floodfill references, as long as it
 * knows one peer that is up.
 */
abstract class FloodOnlySearchJob extends FloodSearchJob {
    private boolean _shouldProcessDSRM;
    private final HashSet<Hash> _unheardFrom;

    /** this is a marker to register with the MessageRegistry, it is never sent */
    private OutNetMessage _out;
    protected final MessageSelector _replySelector;
    protected final ReplyJob _onReply;
    protected final Job _onTimeout;

    private static final int MIN_FOR_NO_DSRM = 4;
    private static final long SINGLE_SEARCH_MSG_TIME = 10*1000;

    public FloodOnlySearchJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash key, Job onFind, Job onFailed, int timeoutMs, boolean isLease) {
        super(ctx, facade, key, onFind, onFailed, timeoutMs, isLease);
        // these override the settings in super
        _timeoutMs = Math.min(timeoutMs, SearchJob.PER_FLOODFILL_PEER_TIMEOUT);
        _expiration = _timeoutMs + ctx.clock().now();
        _unheardFrom = new HashSet<Hash>(CONCURRENT_SEARCHES);
        _replySelector = new FloodOnlyLookupSelector(ctx, this);
        _onReply = new FloodOnlyLookupMatchJob(ctx, this);
        _onTimeout = new FloodOnlyLookupTimeoutJob(ctx, this);
    }

    /**
     * For DirectLookupJob extension, RI only, different match job
     *
     * @since 0.9.56
     */
    protected FloodOnlySearchJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash key, Job onFind, Job onFailed, int timeoutMs) {
        super(ctx, facade, key, onFind, onFailed, timeoutMs, false);
        _timeoutMs = timeoutMs;
        _expiration = _timeoutMs + ctx.clock().now();
        _unheardFrom = new HashSet<Hash>(1);
        _replySelector = new FloodOnlyLookupSelector(ctx, this);
        _onReply = new DirectLookupMatchJob(ctx, this);
        _onTimeout = new FloodOnlyLookupTimeoutJob(ctx, this);
    }

    public boolean shouldProcessDSRM() { return _shouldProcessDSRM; }

    @Override
    public void runJob() {
        throw new UnsupportedOperationException("use override");
/****
        // pick some floodfill peers and send out the searches
        // old
        //List<Hash> floodfillPeers = _facade.getFloodfillPeers();
        // new
        List<Hash> floodfillPeers;
        KBucketSet<Hash> ks = _facade.getKBuckets();
        if (ks != null) {
            Hash rkey = getContext().routingKeyGenerator().getRoutingKey(_key);
            // Ideally we would add the key to an exclude list, so we don't try to query a ff peer for itself,
            // but we're passing the rkey not the key, so we do it below instead in certain cases.
            floodfillPeers = ((FloodfillPeerSelector)_facade.getPeerSelector()).selectFloodfillParticipants(rkey, MIN_FOR_NO_DSRM, ks);
        } else {
            floodfillPeers = Collections.emptyList();
        }

        // If we dont know enough floodfills,
        // or the global network routing key just changed (which is set at startup,
        // so this includes the first few minutes of uptime)
        _shouldProcessDSRM = floodfillPeers.size() < MIN_FOR_NO_DSRM ||
                             getContext().routingKeyGenerator().getLastChanged() > getContext().clock().now() - 60*60*1000;

        if (floodfillPeers.isEmpty()) {
            // ask anybody, they may not return the answer but they will return a few ff peers we can go look up,
            // so this situation should be temporary
            if (_log.shouldWarn())
                _log.warn("Running NetDb searches against Floodfills, but we don't know any");
            floodfillPeers = new ArrayList<Hash>(_facade.getAllRouters());
            if (floodfillPeers.isEmpty()) {
                if (_log.shouldError())
                    _log.error("We don't know any peers at all");
                failed();
                return;
            }
            Collections.shuffle(floodfillPeers, getContext().random());
        }

        // This OutNetMessage is never used or sent (setMessage() is never called), it's only
        // so we can register a reply selector.
        _out = getContext().messageRegistry().registerPending(_replySelector, _onReply, _onTimeout);

        // We need to randomize our ff selection, else we stay with the same ones since
        // getFloodfillPeers() is sorted by closest distance. Always using the same
        // ones didn't help reliability.
        // Also, query the unheard-from, unprofiled, failing, unreachable and banlisted ones last.
        // We should hear from floodfills pretty frequently so set a 30m time limit.
        // If unprofiled we haven't talked to them in a long time.
        // We aren't contacting the peer directly, so banlist doesn't strictly matter,
        // but it's a bad sign, and we often banlist a peer before we fail it...
        if (floodfillPeers.size() > CONCURRENT_SEARCHES) {
            Collections.shuffle(floodfillPeers, getContext().random());
            List ffp = new ArrayList(floodfillPeers.size());
            int failcount = 0;
            long before = getContext().clock().now() - 30*60*1000;
            for (int i = 0; i < floodfillPeers.size(); i++) {
                 Hash peer = (Hash)floodfillPeers.get(i);
                 PeerProfile profile = getContext().profileOrganizer().getProfile(peer);
                 if (profile == null || profile.getLastHeardFrom() < before ||
                     profile.getIsFailing() || getContext().banlist().isBanlisted(peer) ||
                     getContext().commSystem().wasUnreachable(peer)) {
                     failcount++;
                     ffp.add(peer);
                 } else
                     ffp.add(0, peer);
            }
            // This will help us recover if the router just started and all the floodfills
            // have changed since the last time we were running
            if (floodfillPeers.size() - failcount <= 2)
                _shouldProcessDSRM = true;
            if (_log.shouldInfo() && failcount > 0)
                _log.info("[Job " + getJobId() + "] " + failcount + " of " + floodfillPeers.size() + " floodfills are not heard from, unprofiled, failing, unreachable or banlisted");
            floodfillPeers = ffp;
        } else {
            _shouldProcessDSRM = true;
        }

        int count = 0; // keep a separate count since _lookupsRemaining could be decremented elsewhere
        for (int i = 0; _lookupsRemaining.get() < CONCURRENT_SEARCHES && i < floodfillPeers.size(); i++) {
            Hash peer = floodfillPeers.get(i);
            if (peer.equals(getContext().routerHash()))
                continue;

            DatabaseLookupMessage dlm = new DatabaseLookupMessage(getContext(), true);
            TunnelInfo replyTunnel = getContext().tunnelManager().selectInboundTunnel();
            TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundTunnel();
            if ( (replyTunnel == null) || (outTunnel == null) ) {
                failed();
                return;
            }

            // As explained above, it's hard to keep the key itself out of the ff list,
            // so let's just skip it for now if the outbound tunnel is zero-hop.
            // Yes, that means we aren't doing double-lookup for a floodfill
            // if it happens to be closest to itself and we are using zero-hop exploratory tunnels.
            // If we don't, the OutboundMessageDistributor ends up logging erors for
            // not being able to send to the floodfill, if we don't have an older netdb entry.
            if (outTunnel.getLength() <= 1 && peer.equals(_key) && floodfillPeers.size() > 1)
                continue;

            synchronized(_unheardFrom) {
                _unheardFrom.add(peer);
            }
            dlm.setFrom(replyTunnel.getPeer(0));
            dlm.setMessageExpiration(getContext().clock().now() + SINGLE_SEARCH_MSG_TIME);
            dlm.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
            dlm.setSearchKey(_key);
            dlm.setSearchType(_isLease ? DatabaseLookupMessage.Type.LS : DatabaseLookupMessage.Type.RI);

            if (_log.shouldInfo())
                _log.info("[Job " + getJobId() + "] Floodfill search for " + _key + " to " + peer);
            getContext().tunnelDispatcher().dispatchOutbound(dlm, outTunnel.getSendTunnelId(0), peer);
            count++;
            _lookupsRemaining.incrementAndGet();
        }

        if (count <= 0) {
            if (_log.shouldInfo())
                _log.info("[Job " + getJobId() + "] Floodfill search for " + _key + " had no peers to send to");
            // no floodfill peers, fail
            failed();
        }
****/
    }

    @Override
    public String getName() { return "Start NetDb Search for Floodfill"; }

    /**
     *  Note that we heard from the peer
     *
     *  @return number remaining after decrementing
     */
    int decrementRemaining(Hash peer) {
        synchronized(_unheardFrom) {
            _unheardFrom.remove(peer);
            return decrementRemaining();
        }
    }

    @Override
    void failed() {
        synchronized (this) {
            if (_dead) return;
            _dead = true;
        }
        getContext().messageRegistry().unregisterPending(_out);
        long time = System.currentTimeMillis() - _created;
        if (_log.shouldInfo()) {
             int timeRemaining = (int)(_expiration - getContext().clock().now());
            _log.info("[Job " + getJobId() + "] Floodfill search for " + _key + " failed with " + timeRemaining + " remaining after " + time);
        }
        synchronized(_unheardFrom) {
            for (Hash h : _unheardFrom)
                getContext().profileManager().dbLookupFailed(h);
        }
        _facade.complete(_key);
        getContext().statManager().addRateData("netDb.failedTime", time);
        for (Job j : _onFailed) {
            getContext().jobQueue().addJob(j);
        }
        _onFailed.clear();
    }

    @Override
    void success() {
        synchronized (this) {
            if (_dead) return;
            _dead = true;
            super.success();
        }
        if (_log.shouldInfo())
            _log.info("[Job " + getJobId() + "] Floodfill search for " + _key + " successful");
        // Sadly, we don't know which of the two replied, unless the first one sent a DSRM
        // before the second one sent the answer, which isn't that likely.
        // Would be really nice to fix this, but it isn't clear how unless CONCURRENT_SEARCHES == 1.
        // Maybe don't unregister the msg from the Registry for a while and see if we get a 2nd reply?
        // Or delay the 2nd search for a few seconds?
        // We'll have to rely primarily on other searches (ExploreJob which calls SearchJob,
        // and FloodfillVerifyStoreJob) to record successful searches for now.
        // StoreJob also calls dbStoreSent() which updates the lastHeardFrom timer - this also helps.
        long time = System.currentTimeMillis() - _created;
        synchronized(_unheardFrom) {
            if (_unheardFrom.size() == 1) {
                Hash peer = _unheardFrom.iterator().next();
                getContext().profileManager().dbLookupSuccessful(peer, time);
            }
        }
        _facade.complete(_key);
        getContext().statManager().addRateData("netDb.successTime", time);
        for (Job j : _onFind) {
            getContext().jobQueue().addJob(j);
        }
    }
}
