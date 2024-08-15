package net.i2p.router.networkdb.kademlia;

import java.util.HashSet;

import net.i2p.data.Hash;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;

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
            _log.info("Floodfill search for " + _key + " failed with " + timeRemaining + " remaining after " + time);
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
            _log.info("Floodfill search for " + _key + " successful");
        /**
         *   Sadly, we don't know which of the two replied, unless the first one sent a DSRM
         *   before the second one sent the answer, which isn't that likely.
         *   Would be really nice to fix this, but it isn't clear how unless CONCURRENT_SEARCHES == 1.
         *   Maybe don't unregister the msg from the Registry for a while and see if we get a 2nd reply?
         *   Or delay the 2nd search for a few seconds?
         *   We'll have to rely primarily on other searches (ExploreJob which calls SearchJob,
         *   and FloodfillVerifyStoreJob) to record successful searches for now.
         *   StoreJob also calls dbStoreSent() which updates the lastHeardFrom timer - this also helps.
         */
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
