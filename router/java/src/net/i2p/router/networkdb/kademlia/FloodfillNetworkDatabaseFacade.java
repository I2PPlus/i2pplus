package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.i2p.crypto.SigType;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.stat.RateConstants;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.kademlia.KBucketSet;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 *  The network database
 */
public class FloodfillNetworkDatabaseFacade extends KademliaNetworkDatabaseFacade {
    public static final char CAPABILITY_FLOODFILL = 'f';
    public static final char CAPABILITY_UNREACHABLE = 'U';
    public static final char CAPABILITY_BW12 = 'K';
    public static final char CAPABILITY_BW32 = 'L';
    public static final char CAPABILITY_BW64 = 'M';
    public static final char CAPABILITY_BW128 = 'N';
    public static final char CAPABILITY_BW256 = 'O';
    public static final char CAPABILITY_BW512 = 'P';
    public static final char CAPABILITY_BW_UNLIMITED = 'X';
    public static final char CAPABILITY_CONGESTION_MODERATE = 'D';
    public static final char CAPABILITY_CONGESTION_SEVERE = 'E';
    public static final char CAPABILITY_NO_TUNNELS = 'G';
    private final Map<Hash, FloodSearchJob> _activeFloodQueries;
    private boolean _floodfillEnabled;
    private final Set<Hash> _verifiesInProgress;
    private FloodThrottler _floodThrottler;
    private LookupThrottler _lookupThrottler;
    private LookupBanHammer _lookupBanner;
    private final Job _ffMonitor;
    private String tunnelName = "";

    /**
     *  This is the flood redundancy. Entries are
     *  sent to this many other floodfills.
     *  Was 7 through release 0.9; 5 for 0.9.1.
     *  4 as of 0.9.2; 3 as of 0.9.9
     */

    public static final int MAX_TO_FLOOD = 3;
    private static final int FLOOD_PRIORITY = OutNetMessage.PRIORITY_NETDB_FLOOD;
    private static final int FLOOD_TIMEOUT = 10*1000;
    static final long NEXT_RKEY_RI_ADVANCE_TIME = 45*60*1000;
    private static final long NEXT_RKEY_LS_ADVANCE_TIME = 10*60*1000;
    private static final int NEXT_FLOOD_QTY = 2;
    private static final int MAX_LAG_BEFORE_SKIP_SEARCH = SystemVersion.isSlow() ? 1000 : 500;
    private static final int PUBLISH_JOB_DELAY = 15*1000;
    /** @since 0.9.66 moved from FloodfillMonitorJob */
    public static final String PROP_FLOODFILL_PARTICIPANT = "router.floodfillParticipant";
    /** @since 0.9.66 */
    public static final String PROP_FLOODFILL_AT_RESTART = "router.wasFloodfill";

    /**
     *  Main DB
     */
    public FloodfillNetworkDatabaseFacade(RouterContext context) {
        this(context, FloodfillNetworkDatabaseSegmentor.MAIN_DBID);
    }

    /**
     *  Sub DBs
     *
     *  @param dbid null for main DB
     *  @since 0.9.61
     */
    public FloodfillNetworkDatabaseFacade(RouterContext context, Hash dbid) {
        super(context, dbid);
        _activeFloodQueries = new HashMap<Hash, FloodSearchJob>();
        _verifiesInProgress = new ConcurrentHashSet<Hash>(8);

        long[] rate = new long[] { RateConstants.ONE_MINUTE };
        _context.statManager().createRequiredRateStat("netDb.successTime", "Time for successful NetDb lookup", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.failedTime", "Time a failed NetDb search takes", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.failedAttemptedPeers", "Number of peers we sent a search to that failed", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.successPeers", "Number of peers we sent a search to that succeeded", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.failedPeers", "Number of peers failing to respond to a NetDb lookup", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.searchCount", "Total number of searches sent", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.failedRetries", "Additional queries for failed Iterative search", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.successRetries", "Additional queries for successful Iterative search", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.searchMessageCount", "Total number of messages for all searches sent", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.searchReplyValidated", "Validated NetDb search replies", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.searchReplyNotValidated", "Unvalidated NetDb search replies", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.searchReplyValidationSkipped", "Skipped NetDb search replies (unreliable peers)", "NetworkDatabase", rate);
        _context.statManager().createRateStat("netDb.republishQuantity", "Number of peers we need to send a found LeaseSet to", "NetworkDatabase", rate);
        // for ISJ
        _context.statManager().createRateStat("netDb.RILookupDirect", "Number of direct Iterative RouterInfo lookups", "NetworkDatabase", rate);
        // No need to start the FloodfillMonitorJob for client subDb.
        if (isClientDb()) {_ffMonitor = null;}
        else {_ffMonitor = new FloodfillMonitorJob(_context, this);}
    }

    @Override
    public synchronized void startup() {
        boolean isFF;
        super.startup();
        if (_ffMonitor != null) {_context.jobQueue().addJob(_ffMonitor);}
        if (isClientDb()) {isFF = false;}
        else {
            isFF = _context.getBooleanProperty(PROP_FLOODFILL_PARTICIPANT);
            _lookupThrottler = new LookupThrottler(this);
            _lookupBanner = new LookupBanHammer();
        }

        long down = _context.router().getEstimatedDowntime();
        if (!_context.commSystem().isDummy() && !isClientDb() &&
            (down == 0 || (!isFF && down > 30*60*1000) || (isFF && down > 24*60*60*1000))) {
            // refresh old routers
            Job rrj = new RefreshRoutersJob(_context, this);
            rrj.getTiming().setStartAfter(_context.clock().now() + 5*60*1000);
            _context.jobQueue().addJob(rrj);
        }
    }

    @Override
    protected void createHandlers() {
        // Only initialize the handlers for the flooodfill netDb.
        if (!isClientDb()) {
            if (_log.shouldInfo()) {_log.info("[" + this +  "] Initializing Floodfill NetDb message handlers...");}
            _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseLookupMessage.MESSAGE_TYPE, new FloodfillDatabaseLookupMessageHandler(_context, this));
            _context.inNetMessagePool().registerHandlerJobBuilder(DatabaseStoreMessage.MESSAGE_TYPE, new FloodfillDatabaseStoreMessageHandler(_context, this));
        }
    }

    /**
     *  If we are floodfill, turn it off and tell everybody.
     *  @since 0.8.9
     */
    @Override
    public synchronized void shutdown() {
        // only if not forced ff or not restarting
        boolean floodfillEnabled;
        synchronized(this) {
            floodfillEnabled = _floodfillEnabled;
        }
        if (floodfillEnabled && !(_context.router().scheduledGracefulExitCode() == Router.EXIT_HARD_RESTART ||
            _context.router().scheduledGracefulExitCode() == Router.EXIT_GRACEFUL_RESTART)) {
            // turn off to build a new RI...
            synchronized(this) {_floodfillEnabled = false;}
            // true -> publish inline
            // but job queue is already shut down, so sendStore() called by rebuildRouterInfo() won't work...
            _context.router().rebuildRouterInfo(true);
            // ...so force a flood here
            RouterInfo local = _context.router().getRouterInfo();
            if (local != null && _context.router().getUptime() > PUBLISH_JOB_DELAY) {
                flood(local);
                // let the messages get out...
                try {Thread.sleep(5000);}
                catch (InterruptedException ie) {}
            }
        }
        if (_ffMonitor != null) {_context.jobQueue().removeJob(_ffMonitor);}
        super.shutdown();
    }

    /**
     *  This maybe could be shorter than RepublishLeaseSetJob.REPUBLISH_LEASESET_TIMEOUT,
     *  because we are sending direct, but unresponsive floodfills may take a while due to timeouts.
     */
    static final long PUBLISH_TIMEOUT = 45*1000;

    /**
     * Send our RI to the closest floodfill.
     * @throws IllegalArgumentException if the local router info is invalid
     */
    @Override
    public void publish(RouterInfo localRouterInfo) throws IllegalArgumentException {
        if (localRouterInfo == null) throw new IllegalArgumentException("Impossible: null local RouterInfo?");
        // should this be after super? why not publish locally?
        if (_context.router().isHidden()) return; // DE-nied!
        super.publish(localRouterInfo);
        // Wait until we've read in the RI's so we can find the closest floodfill
        if (!isInitialized()) {
            if (_log.shouldInfo()) {_log.info("Attempted to publish our RouterInfo before NetDb initialized, will retry shortly...");}
            return;
        }
        // No use sending if we have no addresses, unless maybe we used to have addresses? Not worth it...
        if (localRouterInfo.getAddresses().isEmpty()) {return;}
        if (_context.router().getUptime() > PUBLISH_JOB_DELAY) {
            _log.info("Publishing our RouterInfo...");
            sendStore(localRouterInfo.getIdentity().calculateHash(), localRouterInfo, null, null, PUBLISH_TIMEOUT, null);
        } else {
            // Transports may rapidly force republishes at startup as they collect addresses and reachability status from UPnP,
            // peers, and peer testing so "debounce" them by delaying slightly
            _log.info("Delaying slightly before publishing our RouterInfo...");
            DelayedPublish dp = new DelayedPublish(localRouterInfo);
            dp.schedule(3*1000);
        }
    }

    /**
     *  Don't actually publish unless the RI didn't change during the delay
     *  @since 0.9.65
     */
    private class DelayedPublish extends SimpleTimer2.TimedEvent {
        private final RouterInfo localRouterInfo;
        public DelayedPublish(RouterInfo local) {
            super(_context.simpleTimer2());
            localRouterInfo = local;
        }
        public void timeReached() {
            RouterInfo latest = _context.router().getRouterInfo();
            boolean shouldLog = _log.shouldWarn() || _log.shouldInfo();
            // Clock may skew during startup so we do an exact == check, not <=
            if (latest.getDate() == localRouterInfo.getDate()) {
                if (shouldLog) {
                    _log.warn("Publishing our RouterInfo after delay" + (_log.shouldInfo() ? ": " + localRouterInfo : "..."));
                }
                sendStore(localRouterInfo.getIdentity().calculateHash(), localRouterInfo, null, null, PUBLISH_TIMEOUT, null);
            } else {
                // Do nothing, there's another one of these right behind us
                if (shouldLog) {
                    _log.warn("RouterInfo changed, not publishing old one" + (_log.shouldInfo() ? ": " + localRouterInfo : "..."));
                }
            }
        }
    }

    /**
     * Send out a store.
     *
     * @param key the DatabaseEntry hash
     * @param onSuccess may be null, always called if we are ff and ds is an RI
     * @param onFailure may be null, ignored if we are ff and ds is an RI
     * @param sendTimeout ignored if we are ff and ds is an RI
     * @param toIgnore may be null, if non-null, all attempted and skipped targets will be added as of 0.9.53,
     *        unused if we are ff and ds is an RI
     */

    private int concurrent = 2;
    private int failCount = 0;
    private int successCount = 0;

    @Override
    void sendStore(Hash key, DatabaseEntry ds, Job onSuccess, Job onFailure, long sendTimeout, Set<Hash> toIgnore) {
        // Reduce concurrency during high job lag to prevent queue overload
        long maxLag = _context.jobQueue().getMaxLag();
        if (maxLag > 2000) {
            concurrent = 1; // High lag: minimal concurrency
        } else if (maxLag > 1000) {
            concurrent = 2; // Moderate lag: normal concurrency
        } else {
            concurrent = 2; // Normal: default concurrency
        }
        failCount = 0;
        successCount = 0;
        // If we are a part of the floodfill netDb, don't send out our own leaseSets as part
        // of the flooding - instead, send them to random floodfill peers so they can flood 'em out.
        Set<Hash> floodfillParticipants = selectFloodfillParticipants(toIgnore, getKBuckets(), concurrent);
        if (floodfillParticipants == null) {return;} // No peers to send to, so return early
        if (floodfillEnabled() && (ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)) {flood(ds);}
        else {
            List<Hash> participantList = new ArrayList<>(floodfillParticipants);
            Collections.shuffle(participantList);
            floodfillParticipants = new HashSet<>(participantList);

            int peerIdx = 0;
            for (Hash peer : floodfillParticipants) {
                if (onSuccess != null) {
                    _context.jobQueue().addJob(onSuccess);
                    successCount++;
                }
                else if (successCount == 0) {
                    // Queue all store jobs with staggered delays instead of blocking sleep
                    // This batches the work and prevents job runner blocking
                    FloodfillStoreJob storeJob = new FloodfillStoreJob(_context, this, key, ds, onSuccess, onFailure, sendTimeout, toIgnore);
                    // Stagger jobs: 0ms, 200ms, 400ms... per peer (reduced from 1000ms)
                    long delay = peerIdx * 200L;
                    storeJob.getTiming().setStartAfter(_context.clock().now() + delay);
                    _context.jobQueue().addJob(storeJob);
                    failCount++;
                    if (failCount > 9) {concurrent = 3;}
                    else if (failCount > 4) {concurrent = 2;}
                    if (_log.shouldInfo()) {
                        String name;
                        if (ds instanceof LeaseSet) {
                            String tunnelName = getTunnelName(((LeaseSet) ds).getDestination());
                            name = tunnelName != null ? "LeaseSet for '" + tunnelName + "'" : "key for [" + key.toBase32().substring(0,8) + "]";
                        } else {
                            name = "key for [" + key.toBase32().substring(0,8) + "]";
                        }
                        _log.info("Flood of " + name + " to [" + peer.toBase64().substring(0,6) + "] failed -> " +
                                  "Resending to " + (concurrent > 1 ? concurrent + " new floodfills" : "a different floodfill") + "...");
                    }
                }
                peerIdx++;
            }
        }
    }

    /**
     * Selects a set of floodfill participant peers to which data should be sent.
     *
     * <p>The method filters peers having the floodfill capability and excludes peers that are
     * unreachable, banned, ignored, or known to send bad replies. It then randomly selects
     * up to the specified number of concurrent peers.
     *
     * @param toIgnore the set of peer hashes to exclude from selection, may be null
     * @param kbuckets the KBucketSet representing the routing table (currently unused in logic)
     * @param concurrent the maximum number of peers to select
     * @return a set of floodfill participant Hashes; never null but possibly empty if no suitable peers found
     */
    private Set<Hash> selectFloodfillParticipants(Set<Hash> toIgnore, KBucketSet<Hash> kbuckets, int concurrent) {
        Set<Hash> set = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
        if (set == null || set.isEmpty()) {
            // Return early if no floodfill peers available
            return Collections.emptySet();
        }

        List<Hash> rv = new ArrayList<>(set.size());
        for (Hash h : set) {
            RouterInfo ri = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(h);
            String caps = ri != null ? ri.getCapabilities() : "";
            boolean isUnreachable = ri != null && caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
            if (ri != null && (toIgnore != null && toIgnore.contains(h)) || _context.banlist().isBanlisted(h) || isUnreachable ||
                _context.banlist().isBanlistedForever(h) || _context.profileOrganizer().peerSendsBadReplies(h)) {
                continue;
            }
            rv.add(h);
        }

        if (rv.isEmpty()) {
            // Return early if no suitable peers remain after filtering
            return Collections.emptySet();
        }

        Collections.shuffle(rv);

        // Return up to 'concurrent' number of participants
        return new HashSet<>(rv.subList(0, Math.min(concurrent, rv.size())));
    }

    /**
     *  Increments and tests.
     *  @since 0.7.11
     */
    boolean shouldThrottleFlood(Hash key) {
        FloodThrottler ft;
        synchronized(this) {
            ft = _floodThrottler;
        }
        return ft != null && ft.shouldThrottle(key);
    }


    /* @since 0.9.59 */
    boolean shouldBanLookup(Hash from, TunnelId id) {
        // null before startup
        return _lookupBanner == null || _lookupBanner.shouldBan(from, id);
    }

    /**
     *  Increments and tests.
     *  @since 0.7.11
     */
    boolean shouldThrottleLookup(Hash from, TunnelId id) {
        // null before startup
        return _lookupThrottler == null || _lookupThrottler.shouldThrottle(from, id);
    }

    /**
     *  If we are floodfill AND the key is not throttled,
     *  flood it, otherwise don't.
     *
     *  @return if we did
     *  @since 0.9.36 for NTCP2
     */
    public boolean floodConditional(DatabaseEntry ds) {
        if (!floodfillEnabled()) {return false;}
        Hash h = ds.getHash();
        if (_context.banlist().isBanlistedForever(h) || _context.banlist().isBanlistedHostile(h)) {
            return false;
        }
        if (shouldThrottleFlood(h)) {
            _context.statManager().addRateData("netDb.floodThrottled", 1);
            return false;
        }
        flood(ds);
        return true;
    }

    /**
     *  Send to a subset of all floodfill peers.
     *  We do this to implement Kademlia within the floodfills, i.e.
     *  we flood to those closest to the key.
     */
    public void flood(DatabaseEntry ds) {
        Hash key = ds.getHash();
        RouterKeyGenerator gen = _context.routerKeyGenerator();
        Hash rkey = gen.getRoutingKey(key);
        FloodfillPeerSelector sel = (FloodfillPeerSelector)getPeerSelector();
        final int type = ds.getType();
        final boolean isls = ds.isLeaseSet();
        final boolean isls2 = isls && type != DatabaseEntry.KEY_TYPE_LEASESET;
        final SigType lsSigType = (isls && type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) ?
                                  ds.getKeysAndCert().getSigningPublicKey().getType() :
                                  null;
        int max = MAX_TO_FLOOD;
        // increase candidates because we will be skipping some
        if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {max *= 4;}
        else if (isls2) {max *= 3;}
        List<Hash> peers = sel.selectFloodfillParticipants(rkey, max, getKBuckets());

        // todo key cert skip?
        long until = gen.getTimeTillMidnight();
        if ((type != DatabaseEntry.KEY_TYPE_ROUTERINFO && until < NEXT_RKEY_LS_ADVANCE_TIME &&
             (((LeaseSet)ds).getLatestLeaseDate() - _context.clock().now()) > until) ||
            (type == DatabaseEntry.KEY_TYPE_ROUTERINFO && until < NEXT_RKEY_RI_ADVANCE_TIME)) {
            // to avoid lookup failures after midnight, also flood to some closest to the
            // next routing key for a period of time before midnight.
            Hash nkey = gen.getNextRoutingKey(key);
            List<Hash> nextPeers = sel.selectFloodfillParticipants(nkey, NEXT_FLOOD_QTY, getKBuckets());
            int i = 0;
            for (Hash h : nextPeers) {
                // Don't flood an RI back to itself
                // Not necessary, a ff will do its own flooding (reply token == 0)
                // But other implementations may not...
                if (h.equals(key)) {continue;}
                // todo key cert skip?
                if (!peers.contains(h)) {
                    peers.add(h);
                    i++;
                }
                if (i >= MAX_TO_FLOOD) {break;}
            }
            if (i > 0) {
                max += i;
                if (_log.shouldDebug()) {
                    _log.debug("Flooding the entry for [" + key.toBase32().substring(0,8) + "] to " + i + " more, just before midnight");
                }
            }
        }
        int flooded = 0;
        for (int i = 0; i < peers.size(); i++) {
            Hash peer = peers.get(i);
            RouterInfo target = lookupRouterInfoLocally(peer);
            if (!shouldFloodTo(key, type, lsSigType, peer, target)) {
                if (_log.shouldDebug()) {
                    _log.debug("Not flooding [" + key.toBase32().substring(0,8) + "] to [" + peer.toBase64().substring(0,6) + "] -> Too old");
                }
                continue;
            }
            DatabaseStoreMessage msg = new DatabaseStoreMessage(_context);
            msg.setEntry(ds);
            OutNetMessage m = new OutNetMessage(_context, msg, _context.clock().now() + FLOOD_TIMEOUT, FLOOD_PRIORITY, target);
            Job floodFail = new FloodFailedJob(_context, peer);
            m.setOnFailedSendJob(floodFail);
            // we want to give credit on success, even if we aren't sure,
            // because otherwise no use noting failure
            Job floodGood = new FloodSuccessJob(_context, peer);
            m.setOnSendJob(floodGood);
            _context.commSystem().processMessage(m);
            flooded++;
            if (_log.shouldDebug()) {
                _log.debug("Flooding the entry for [" + key.toBase32().substring(0,8) + "] to [" + peer.toBase64().substring(0,6) + "]...");
            }
            if (flooded >= MAX_TO_FLOOD) {break;}
        }

        if (_log.shouldInfo()) {
            _log.info("Flooded the entry for [" + key.toBase32().substring(0,8) + "] to " + flooded + " of " + peers.size() + " peers");
        }
    }

    /**
     *  @param type database store type
     *  @param lsSigType may be null
     *  @since 0.9.39
     */
    private boolean shouldFloodTo(Hash key, int type, SigType lsSigType, Hash peer, RouterInfo target) {
       if ((target == null) || (_context.banlist().isBanlisted(peer))) {return false;}
       // Don't flood an RI back to itself
       // Not necessary, a ff will do its own flooding (reply token == 0)
       // But other implementations may not...
       if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO && peer.equals(key)) {return false;}
       if (peer.equals(_context.routerHash())) {return false;}
       if ((type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2 || lsSigType == SigType.RedDSA_SHA512_Ed25519) &&
           !StoreJob.shouldStoreEncLS2To(target)) {
           return false;
       }
       if (!StoreJob.shouldStoreTo(target)) {return false;}
       return true;
    }

    /** note in the profile that the store failed */
    private static class FloodFailedJob extends JobImpl {
        private final Hash _peer;

        public FloodFailedJob(RouterContext ctx, Hash peer) {
            super(ctx);
            _peer = peer;
        }
        public String getName() {return "Flood failed";}
        public void runJob() {getContext().profileManager().dbStoreFailed(_peer);}
    }

    /**
     *  Note in the profile that the store succeeded
     *  @since 0.9.19
     */
    private static class FloodSuccessJob extends JobImpl {
        private final Hash _peer;

        public FloodSuccessJob(RouterContext ctx, Hash peer) {
            super(ctx);
            _peer = peer;
        }
        public String getName() {return "Flood succeeded";}
        public void runJob() {getContext().profileManager().dbStoreSuccessful(_peer);}
    }

    /**
     *  Public, called from console. This wakes up the floodfill monitor,
     *  which will rebuild the RI and log in the event log,
     *  and call setFloodfillEnabledFromMonitor which really sets it.
     */
    public synchronized void setFloodfillEnabled(boolean yes) {
        if ((yes != _floodfillEnabled) && (_ffMonitor != null)) {
            _context.jobQueue().removeJob(_ffMonitor);
            _ffMonitor.getTiming().setStartAfter(_context.clock().now() + 1000);
            _context.jobQueue().addJob(_ffMonitor);
        }
    }

    /**
     *  Package private, called from FloodfillMonitorJob. This does not wake up the floodfill monitor.
     *  @since 0.9.34
     */
    synchronized void setFloodfillEnabledFromMonitor(boolean yes) {
        _floodfillEnabled = yes;
        if (yes && _floodThrottler == null) {
            _floodThrottler = new FloodThrottler();
            _context.statManager().createRateStat("netDb.floodThrottled", "How often we decline to flood the NetDb", "NetworkDatabase", new long[] { RateConstants.ONE_MINUTE });
            // following are for HFDSMJ
            _context.statManager().createRateStat("netDb.storeFloodNew", "Time to flood out a newly received NetDb entry", "NetworkDatabase", new long[] { RateConstants.ONE_MINUTE });
            _context.statManager().createRateStat("netDb.storeFloodOld", "How often we receive a stale NetDb entry", "NetworkDatabase", new long[] { RateConstants.ONE_MINUTE });
        }
    }

    @Override
    public boolean floodfillEnabled() {
        synchronized(this) {return _floodfillEnabled;}
    }

    /**
     *  @param peer may be null, returns false if null
     */
    public static boolean isFloodfill(RouterInfo peer) {
        if (peer == null) {return false;}
        String caps = peer.getCapabilities();
        return caps.indexOf(CAPABILITY_FLOODFILL) >= 0;
    }

    public List<RouterInfo> getKnownRouterData() {
        List<RouterInfo> rv = new ArrayList<RouterInfo>();
        DataStore ds = getDataStore();
        if (ds != null) {
            for (DatabaseEntry o : ds.getEntries()) {
                if (o.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {rv.add((RouterInfo)o);}
            }
        }
        return rv;
    }

    /**
     * Lookup using exploratory tunnels.
     *
     * Caller should check negative cache and/or banlist before calling.
     *
     * Begin a kademlia style search for the key specified, which can take up to timeoutMs and
     * will fire the appropriate jobs on success or timeout (or if the kademlia search completes
     * without any match)
     *
     * @return null always
     */
    @Override
    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease) {
        return search(key, onFindJob, onFailedLookupJob, timeoutMs, isLease, null);
    }

    /**
     * Lookup using the client's tunnels.
     *
     * Caller should check negative cache and/or banlist before calling.
     *
     * @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     * @return null always
     * @since 0.9.10
     */
    // ToDo: With repect to segmented netDb clients, this framework needs refinement.
    // A client with a segmented netDb can not use exploratory tunnels.
    // The return messages will not have sufficient information to be directed back to the client making the query.
    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease, Hash fromLocalDest) {
        if (key == null) {
            if (_log.shouldWarn()) {_log.warn("NULL key search requested -> Dropping...");}
            return null;
        } else if (fromLocalDest == null && isClientDb()) {
            if (_log.shouldWarn()) {_log.warn("Search from Client subDb using Exploratory tunnels requested -> Dropping...");}
            return null;
        } else {
            boolean isNew = false;
            FloodSearchJob searchJob;
            synchronized (_activeFloodQueries) {
                searchJob = _activeFloodQueries.get(key);
                if (searchJob == null) {
                    searchJob = new IterativeSearchJob(_context, this, key, onFindJob, onFailedLookupJob, (int)timeoutMs,
                                                       isLease, fromLocalDest);
                    _activeFloodQueries.put(key, searchJob);
                    isNew = true;
                }
            }

            if (isNew) {
                if (_log.shouldDebug()) {
                    _log.debug("New IterativeSearch via " + ((fromLocalDest != null) ? "Client tunnels" : "Exploratory tunnels") +
                               " for key [" + key.toBase32().substring(0,8) + "] -> " + this);
                }
                _context.jobQueue().addJob(searchJob);
            } else {
                if (_log.shouldDebug()) {
                    _log.debug("Deferred IterativeSearch for [" + key.toBase32().substring(0,8) + "] with " +
                               _activeFloodQueries.size() + " in progress...");
                }
                searchJob.addDeferred(onFindJob, onFailedLookupJob, timeoutMs, isLease);
                // not necessarily LS
                _context.statManager().addRateData("netDb.lookupDeferred", 1, searchJob.getExpiration()-_context.clock().now());
            }
            return null;
        }
    }

    /**
     *  Must be called by the search job queued by search() on success or failure
     */
    void complete(Hash key) {
        synchronized (_activeFloodQueries) { _activeFloodQueries.remove(key); }
    }

    /** list of the Hashes of currently known floodfill peers;
      * Returned list will not include our own hash.
      *  List is not sorted and not shuffled.
      */
    public List<Hash> getFloodfillPeers() {
        FloodfillPeerSelector sel = (FloodfillPeerSelector)getPeerSelector();
        return sel.selectFloodfillParticipants(getKBuckets());
    }

    /** @since 0.7.10 */
    boolean isVerifyInProgress(Hash h) {return _verifiesInProgress.contains(h);}

    /** @since 0.7.10 */
    void verifyStarted(Hash h) {_verifiesInProgress.add(h);}

    /** @since 0.7.10 */
    void verifyFinished(Hash h) {_verifiesInProgress.remove(h);}

    /** NTCP cons drop quickly but SSU takes a while, so it's prudent to keep this
     *  a little higher than 1 or 2. */
    protected final static int MIN_ACTIVE_PEERS = SystemVersion.isSlow() ? 16 : 32;

    /** @since 0.8.7 */
    private static final int MAX_DB_BEFORE_SKIPPING_SEARCH;
    static {
        long maxMemory = SystemVersion.getMaxMemory();
        MAX_DB_BEFORE_SKIPPING_SEARCH = SystemVersion.isSlow() ? 3000 : 5000;
    }

    /**
      * Search for a newer router info, drop it from the db if the search fails,
      * unless just started up or have bigger problems.
      */
    @Override
    protected void lookupBeforeDropping(Hash peer, RouterInfo info) {
        if (_context.commSystem().isEstablished(peer)) {
            // see DirectLookupJob
            boolean isNew = false;
            FloodSearchJob searchJob;
            Job onFindJob = new DropLookupFoundJob(_context, peer, info);
            Job onFailedLookupJob = new DropLookupFailedJob(_context, peer, info);
            synchronized (_activeFloodQueries) {
                searchJob = _activeFloodQueries.get(peer);
                if (searchJob == null) {
                    searchJob = new DirectLookupJob(_context, this, peer, info, onFindJob, onFailedLookupJob);
                    _activeFloodQueries.put(peer, searchJob);
                    isNew = true;
                }
            }
            if (isNew) {
                if (_log.shouldDebug()) {
                    _log.debug("Direct RouterInfo lookup for [" + peer.toBase64().substring(0,6) + "]");
                }
                _context.jobQueue().addJob(searchJob);
            } else {
                if (_log.shouldDebug()) {
                    _log.debug("Pending Direct RouterInfo lookup for [" + peer.toBase64().substring(0,6) + "]");
                }
                searchJob.addDeferred(onFindJob, onFailedLookupJob, 10*1000, false);
            }
            return;
        }

        // following are some special situations, we don't want to drop the peer in these cases
        long uptime = _context.router().getUptime();
        String nofail = _context.getProperty("router.noFailGracePeriod");
        if (nofail != null) {DONT_FAIL_PERIOD = Long.parseLong(nofail)*60*1000;}
        int knownRouters = getKBucketSetSize();
        if (info.getNetworkId() == _networkID && (knownRouters < MIN_REMAINING_ROUTERS || (uptime < DONT_FAIL_PERIOD && knownRouters < 2000) ||
            _context.commSystem().countActivePeers() <= MIN_ACTIVE_PEERS) || _context.commSystem().getStatus() == Status.DISCONNECTED) {
            if (_log.shouldInfo()) {
                if (uptime < DONT_FAIL_PERIOD && knownRouters < 2000) {
                    _log.info("Lookup of [" + peer.toBase64().substring(0,6) + "] failed -> Not dropping (startup grace period)");
                } else {
                    _log.info("Lookup of [" + peer.toBase64().substring(0,6) + "] failed -> Not dropping (Our Router has issues)");
                }
            }
            return;
        }

        // should we skip the search?
        boolean forceExplore = _context.getBooleanProperty("router.exploreWhenFloodfill");
        Hash us = _context.routerHash();
        boolean isUs = info != null && us.equals(info.getIdentity().getHash());
        String MIN_VERSION = "0.9.64";
        String v = info.getVersion();
        boolean isHidden = _context.router().isHidden();
        boolean slow = info != null && !isUs && (info.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
                       info.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                       info.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0 ||
                       info.getCapabilities().indexOf(Router.CAPABILITY_BW64) >= 0);
        boolean fast = info != null && !isUs && (info.getCapabilities().indexOf(Router.CAPABILITY_BW256) >= 0 ||
                       info.getCapabilities().indexOf(Router.CAPABILITY_BW512) >= 0 ||
                       info.getCapabilities().indexOf(Router.CAPABILITY_BW_UNLIMITED) >= 0);
        boolean uninteresting = info != null && !isHidden && (VersionComparator.comp(v, MIN_VERSION) < 0 && !fast) &&
                                _context.netDb().getKnownRouters() > 2000 && !isUs;
        boolean isFF = false;
        //boolean noSSU = true;
        String caps = "unknown";
        if (info != null) {
            caps = info.getCapabilities();
            if (caps.contains("f")) {isFF = true;}
/**
            for (RouterAddress ra : info.getAddresses()) {
                if (ra.getTransportStyle().contains("SSU")) {
                    noSSU = false;
                    break;
                }
            }
**/
        }
        //boolean isBadFF = isFF && noSSU;
        boolean floodfillEnabled;
        synchronized(this) {
            floodfillEnabled = _floodfillEnabled;
        }
        if ((floodfillEnabled && !forceExplore) ||
             _context.router().gracefulShutdownInProgress() ||
             _context.jobQueue().getMaxLag() > MAX_LAG_BEFORE_SKIP_SEARCH ||
             _context.banlist().isBanlisted(peer) || uninteresting) {
            /**
             * Don't try to overload ourselves (e.g. failing 3000 router refs at once, and then firing off 3000 netDb lookup tasks)
             * Also don't queue a search if we have plenty of routerinfos (KBucketSetSize() includes leasesets but avoids locking)
             */
            if (_log.shouldInfo()) {
                boolean ffEnabled;
                synchronized(this) {
                    ffEnabled = _floodfillEnabled;
                }
                if (ffEnabled && !forceExplore) {
                    _log.info("Skipping lookup of RouterInfo [" + peer.toBase64().substring(0,6) + "] -> Floodfill mode active");
                } else if (_context.banlist().isBanlistedForever(peer)) {
                    _log.info("Skipping lookup of RouterInfo [" + peer.toBase64().substring(0,6) + "] -> Banlisted");
                } else if (uninteresting) {
                    _log.info("Skipping lookup of RouterInfo [" + peer.toBase64().substring(0,6) + "] -> Uninteresting");
                } else if (_context.jobQueue().getMaxLag() > MAX_LAG_BEFORE_SKIP_SEARCH) {
                    _log.info("Skipping lookup of RouterInfo [" + peer.toBase64().substring(0,6) + "] -> High Job Lag");
                }
/**
                } else if (isBadFF) {
                    _log.info("Skipping lookup of RouterInfo [" + peer.toBase64().substring(0,6) + "] -> Floodfill with SSU disabled");
                    new DropLookupFailedJob(_context, peer, info);
                } else if (getKBucketSetSize() > MAX_DB_BEFORE_SKIPPING_SEARCH) {
                    _log.info("Skipping lookup of [" + peer.toBase64().substring(0,6) + "] -> KBucket is full");
**/
                super.lookupBeforeDropping(peer, info);
                return;
            }
        }

        // The following doesn't kick in most of the time, because of
        // the MAX_DB_BEFORE_SKIPPING_SEARCH check above,
        // that value is pretty small compared to typical netdb sizes.

        if (caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
            caps.indexOf(Router.CAPABILITY_BW32) >= 0 ||
            caps.indexOf(Router.CAPABILITY_BW64) >= 0) {
            super.lookupBeforeDropping(peer, info);
            return;
        }
        if (caps.indexOf(CAPABILITY_FLOODFILL) >= 0) {
            PeerProfile prof = _context.profileOrganizer().getProfile(peer);
            if (prof == null) {
                //_log.warn("skip lookup no profile " + peer.toBase64());
                super.lookupBeforeDropping(peer, info);
                return;
            }
            // classification similar to that in FloodfillPeerSelector
            long now = _context.clock().now();
            long installed = _context.getProperty("router.firstInstalled", 0L);
            boolean enforceHeard = installed > 0 && (now - installed) > 2*60*60*1000;
            if (enforceHeard && prof.getFirstHeardAbout() > now - 3*60*60*1000) {
                //_log.warn("skip lookup new " + DataHelper.formatTime(prof.getFirstHeardAbout()) + ' ' + peer.toBase64());
                super.lookupBeforeDropping(peer, info);
                return;
            }
            DBHistory hist = prof.getDBHistory();
            if (hist == null) {
                //_log.warn("skip lookup no dbhist " + peer.toBase64());
                super.lookupBeforeDropping(peer, info);
                return;
            }
            long cutoff = now - 30*60*1000;
            long lastLookupSuccess = hist.getLastLookupSuccessful();
            long lastStoreSuccess = hist.getLastStoreSuccessful();
            if (uptime > 30*60*1000 && lastLookupSuccess < cutoff && lastStoreSuccess < cutoff) {
                //_log.warn("skip lookup no db success " + peer.toBase64());
                super.lookupBeforeDropping(peer, info);
                return;
            }
            cutoff = now - 2*60*60*1000;
            long lastLookupFailed = hist.getLastLookupFailed();
            long lastStoreFailed = hist.getLastStoreFailed();
            if (lastLookupFailed > cutoff || lastStoreFailed > cutoff ||
                lastLookupFailed > lastLookupSuccess || lastStoreFailed > lastStoreSuccess) {
                //_log.warn("skip lookup dbhist store fail " + DataHelper.formatTime(lastStoreFailed) +
                //           " lookup fail " + DataHelper.formatTime(lastLookupFailed) + ' ' + peer.toBase64());
                super.lookupBeforeDropping(peer, info);
                return;
            }
            double maxFailRate = 0.95;
            if (_context.router().getUptime() > 60*60*1000) {
                RateStat rs = _context.statManager().getRate("peer.failedLookupRate");
                if (rs != null) {
                    Rate r = rs.getRate(RateConstants.ONE_HOUR);
                    if (r != null) {
                        double currentFailRate = r.getAverageValue();
                        maxFailRate = Math.min(0.95d, Math.max(0.20d, 1.25d * currentFailRate));
                    }
                }
            }
            double failRate = hist.getFailedLookupRate().getRate(RateConstants.ONE_HOUR).getAverageValue();
            if (failRate >= 1 || failRate > maxFailRate) {
                //_log.warn("skip lookup fail rate " + failRate + " max " + maxFailRate + ' ' + peer.toBase64());
                //super.lookupBeforeDropping(peer, info);
                return;
            }
        }

        // this sends out the search to the floodfill peers even if we already have the
        // entry locally, firing no job if it gets a reply with an updated value (meaning
        // we shouldn't drop them but instead use the new data), or if they all time out,
        // firing the dropLookupFailedJob, which actually removes our local reference
        //search(peer, new DropLookupFoundJob(_context, peer, info),
        //        new DropLookupFailedJob(_context, peer, info), 10*1000, false);
        if (_log.shouldDebug()) {
            _log.debug("Initiating Floodfill Lookup of [" + peer.toBase64().substring(0,6) + "] before dropping..." +
                       "\n* Published: " + new java.util.Date(info.getPublished()));
        }
        search(peer, new DropLookupFoundJob(_context, peer, info), new DropLookupFailedJob(_context, peer, info), 8*1000, false);
    }

    private class DropLookupFailedJob extends JobImpl {
        private final Hash _peer;

        public DropLookupFailedJob(RouterContext ctx, Hash peer, RouterInfo info) {
            super(ctx);
            _peer = peer;
            // Add stagger delay to spread out these jobs and prevent job queue spikes
            long delay = ctx.random().nextInt(3000); // 0-3 seconds
            getTiming().setStartAfter(ctx.clock().now() + delay);
        }
        public String getName() { return "Timeout NetDb Lookup for Failing Peer"; }
        public void runJob() {
            if (!dropAfterLookupFailed(_peer)) {
                dropAfterLookupFailed(_peer);
            }
        }
    }

    private class DropLookupFoundJob extends JobImpl {
        private final Hash _peer;
        private final RouterInfo _info;

        public DropLookupFoundJob(RouterContext ctx, Hash peer, RouterInfo info) {
            super(ctx);
            _peer = peer;
            _info = info;
            // Add stagger delay to spread out these jobs and prevent job queue spikes
            long delay = ctx.random().nextInt(3000); // 0-3 seconds
            getTiming().setStartAfter(ctx.clock().now() + delay);
        }
        public String getName() { return "Verify NetDb Lookup for Failing Peer"; }
        public void runJob() {
            RouterInfo updated = lookupRouterInfoLocally(_peer);
            if (updated == null || updated.getPublished() <= _info.getPublished()) {
                // they just sent us what we already had
                if (!dropAfterLookupFailed(_peer)) {
                    dropAfterLookupFailed(_peer);
                }
            }
        }
    }

    public String getTunnelName(Destination d) {
        TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(d.calculateHash());
        String name = (in != null ? in.getDestinationNickname() : null);
        if (name == null) {
            TunnelPoolSettings out = _context.tunnelManager().getOutboundSettings(d.calculateHash());
            name = (out != null ? out.getDestinationNickname() : null);
        }
        if (name == null) {return "";}
        return name;
    }

}
