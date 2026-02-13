package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.i2p.crypto.SigAlgo;
import net.i2p.crypto.SigType;
import net.i2p.data.BlindData;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.KeyCertificate;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.kademlia.RejectTrimmer;
import net.i2p.router.Banlist;
import net.i2p.router.BanLogger;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.networkdb.reseed.ReseedChecker;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.stat.RateConstants;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.VersionComparator;

/**
 * Kademlia based version of network database.
 * <p>
 * Provides core network database functionality using Kademlia DHT for peer discovery,
 * routing table management, and data storage/lookup operations.
 * Never instantiated directly; see FloodfillNetworkDatabaseFacade.
 */
public abstract class KademliaNetworkDatabaseFacade extends NetworkDatabaseFacade {
    protected final Log _log;
    private static final BanLogger _banLogger = new BanLogger(null);
    private KBucketSet<Hash> _kb; // peer hashes sorted into kbuckets, but within kbuckets, unsorted
    private DataStore _ds; // hash to DataStructure mapping, persisted when necessary
    /** Where the data store is pushing the data */
    private String _dbDir;
    /** Set of Hash objects that we should search on (to fill up a bucket, not to get data) */
    private final Set<Hash> _exploreKeys;
    private boolean _initialized;
    /** Clock independent time of when we started up */
    private long _started;
    private StartExplorersJob _exploreJob;
    /** When was the last time an exploration found something new? */
    private long _lastExploreNew;
    protected final PeerSelector _peerSelector;
    protected final RouterContext _context;
    private final ReseedChecker _reseedChecker;
    private volatile long _lastRIPublishTime;
    private NegativeLookupCache _negativeCache;
    protected final int _networkID;
    private final BlindCache _blindCache;
    private final Hash _dbid;
    private final Job _elj, _erj;
    private volatile boolean _isTransient;
    static final String PROP_MIN_ROUTER_VERSION = "router.minVersionAllowed";
    public static final String PROP_BLOCK_MY_COUNTRY = "i2np.blockMyCountry";
    public static final String PROP_IP_COUNTRY = "i2np.lastCountry";
    private final static String PROP_BLOCK_COUNTRIES = "router.blockCountries";
    private final static String DEFAULT_BLOCK_COUNTRIES = "";
    public static final String PROP_BLOCK_XG = "i2np.blockXG";
    public static final String PROP_BLOCK_OF57 = "router.blockOf57";
    public static final String minRouterVersion = "0.9.20";
    public static final String MIN_VERSION = "0.9.65";
    public static String CURRENT_VERSION = "0.9.68";
    private final Object kbInitLock = new Object();
    private final AtomicInteger knownLeaseSetsCount = new AtomicInteger(0);

    /**
     * Map of Hash to Set of RepublishLeaseSetJob for leases we're already managing.
     * Multiple jobs may be queued per destination; new jobs replace old ones.
     */
    private final ConcurrentMap<Hash, Set<RepublishLeaseSetJob>> _publishingLeaseSets =
        new ConcurrentHashMap<>(8);

    /**
     * Queue for batching LeaseSet republishes within a 15-second window.
     * This reduces job queue pressure by consolidating multiple republish jobs into one.
     */
    private final ConcurrentLinkedQueue<Hash> _batchRepublishQueue = new ConcurrentLinkedQueue<>();

    /**
     * Track last batch processing time to ensure 15-second window.
     */
    private volatile long _lastBatchProcessTime = 0;

    /**
     * Hash of the key currently being searched for, pointing the SearchJob that
     * is currently operating.  Subsequent requests for that same key are simply
     * added on to the list of jobs fired on success/failure
     */
    private final Set<Hash> _activeRequests = new ConcurrentHashSet<>(64);

    /**
     * The search for the given key is no longer active
     */
    void searchComplete(Hash key) {
        if (_log.shouldDebug()) {_log.debug("Search for key [" + key.toBase64().substring(0,6) + "] finished");}
        _activeRequests.remove(key);
    }

    /**
     * For 15 minutes after startup, don't fail db entries so that if we were
     * offline for a while, we'll have a chance of finding some live peers with the
     * previous references
     */
    public static long DONT_FAIL_PERIOD = 15*60*1000;

    /** Don't probe or broadcast data, just respond and search when explicitly needed */
    private static final boolean QUIET = false;

    public final static String PROP_DB_DIR = "router.networkDatabase.dbDir";
    public final static String DEFAULT_DB_DIR = "netDb";

    /**
     * Reseed if below this.
     * @since 0.9.4
     */
    static final int MIN_RESEED = ReseedChecker.MINIMUM;

    /**
     * If we have less than this many routers left, don't drop any more,
     * even if they're failing or doing bad stuff.
     * As of 0.9.4, we make this LOWER than the min for reseeding, so
     * a reseed will be forced if necessary.
     */
    protected final static int MIN_REMAINING_ROUTERS = MIN_RESEED ;

    /**
     * Limits for accepting a dbStore of a router (unless we don't
     * know anyone or just started up) -- see validate() below
     */
    private final static long ROUTER_INFO_EXPIRATION = 36*60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_MIN = 8*60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_SHORT = 15*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_FLOODFILL = 16*60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_INTRODUCED = 54*60*1000l;
    static final String PROP_ROUTER_INFO_EXPIRATION_ADJUSTED = "router.expireRouterInfo";
    static final String PROP_VALIDATE_ROUTERS_AFTER = "router.validateRoutersAfter";
    private final static long EXPLORE_JOB_DELAY = 5*60*1000l;

    /**
     * Don't let leaseSets go too far into the future
     */
    private static final long MAX_LEASE_FUTURE = 15*60*1000;
    private static final long MAX_META_LEASE_FUTURE = 65535*1000;

    /**
     *  This needs to be long enough to give us time to start up, but less than 20m
     *  (when we start accepting tunnels and could be a IBGW)
     *  Actually no, we need this soon if we are a new router or other routers have
     *  forgotten about us, else we can't build IB exploratory tunnels.
     *  Unused.
     */
    protected final static long PUBLISH_JOB_DELAY = 15*1000l;

    /** Maximum number of peers to place in the queue to explore */
    static final int MAX_EXPLORE_QUEUE = 64;
    static final String PROP_EXPLORE_QUEUE = "router.exploreQueue";

    /**
     *  kad K
     *  Was 500 in old implementation but that was with B ~= -8!
     */
    private static final int BUCKET_SIZE = 24;
    static final String PROP_BUCKET_SIZE = "router.exploreBucketSize";
    private static final int KAD_B = 4;
    static final String PROP_KAD_B = "router.exploreKadB";

    private static final long[] RATES = {RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR, RateConstants.ONE_DAY };

    /**
     * Initializes the Kademlia-based network database facade.
     *
     * @param context the router context
     * @param dbid the unique identifier for this network database
     */
    public KademliaNetworkDatabaseFacade(RouterContext context, Hash dbid) {
        _context = context;
        _dbid = dbid;
        _log = _context.logManager().getLog(getClass());
        _networkID = context.router().getNetworkID();

        if (isClientDb()) {
            _reseedChecker = null;
            _blindCache = null;
            _exploreKeys = null;
            _erj = null;
            _peerSelector = ((KademliaNetworkDatabaseFacade) context.netDb()).getPeerSelector();
        } else {
            _reseedChecker = new ReseedChecker(context);
            _blindCache = new BlindCache(context);
            _exploreKeys = new ConcurrentHashSet<Hash>(64);
            // We don't have a comm system here to check for ctx.commSystem().isDummy()
            // we'll check before starting in startup()
            _erj = new ExpireRoutersJob(_context, this);
            _peerSelector = createPeerSelector();
        }

        _elj = new ExpireLeasesJob(_context, this);
        _banLogger.initialize(context);
        if (_log.shouldLog(Log.DEBUG)) {_log.debug("Created KademliaNetworkDatabaseFacade for DbId: " + _dbid);}

        /* stats */
        context.statManager().createRateStat("netDb.exploreKeySet", "NetDb keys queued for exploration", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.lookupDeferred", "Deferred NetDb lookups", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.negativeCache", "Aborted NetDb lookups (already cached)", "NetworkDatabase", RATES);

        /* the following are for StoreJob */
        context.statManager().createRateStat("netDb.ackTime", "Time peer takes to ACK a DbStore", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.DLMAllZeros", "Message lookups in NetDb with zero key ", "NetworkDatabase", RATES); // HandleDatabaseLookupMessageJob
        context.statManager().createRateStat("netDb.DSMAllZeros", "Messages stored in NetDb with zero key", "NetworkDatabase", RATES); // DatabaseStoreMessage
        context.statManager().createRateStat("netDb.replyTimeout", "Timeout expiry after a NetDb send (peer fails to reply in time)", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.republishLeaseSetCount", "How often we republish a LeaseSet", "NetworkDatabase", RATES); // RepublishLeaseSetJob
        context.statManager().createRateStat("netDb.republishLeaseSetFail", "How often we fail to republish a LeaseSet", "NetworkDatabase", RATES); // RepublishLeaseSetJob
        context.statManager().createRateStat("netDb.storeFailedPeers", "Peers each NetDb must be sent to before failing completely", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.storeLeaseSetSent", "Sent LeaseSet store messages", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.storePeers", "Peers each NetDb must be sent to before success", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.storeRouterInfoSent", "Sent RouterInfo store messages", "NetworkDatabase", RATES);
    }

    /**
     * Checks whether the network database has been fully initialized.
     *
     * @return true if the database is initialized and ready for use, false otherwise
     */
    @Override
    public boolean isInitialized() {return _initialized && _ds != null && _ds.isInitialized();}

    /**
     * Creates a peer selector for the network database. Only applicable for the main database.
     *
     * @return a new PeerSelector instance
     * @throws IllegalStateException if called on a client database
     */
    protected PeerSelector createPeerSelector() {
       if (isClientDb()) {throw new IllegalStateException();}
       return new FloodfillPeerSelector(_context);
    }

    /**
     * Returns the main database's peer selector.
     *
     * @return the PeerSelector instance, or null if this is a client database
     */
    public PeerSelector getPeerSelector() {return _peerSelector;}

    /**
     * Returns the reseed checker for this database.
     *
     * @return the ReseedChecker instance, or null if this is a client database
     * @since 0.9
     */
    @Override
    public ReseedChecker reseedChecker() {
        if (isClientDb()) {return null;}
        return _reseedChecker;
    }

    /**
     * Returns the blind data cache used by this database.
     *
     * We still always use a single blind cache in the main Db(for now),
     * see issue #421 on i2pgit.org/i2p-hackers/i2p.i2p for details.
     * This checks if we're the main DB already and returns our blind
     * cache if we are. If not, it looks up the main Db and gets it.
     *
     * @return the BlindCache instance, never null
     * @since 0.9.61
     */
    protected BlindCache blindCache() {
        if (!isClientDb()) {return _blindCache;}
        return ((FloodfillNetworkDatabaseFacade) _context.netDb()).blindCache();
    }

    /**
     *  @return the main DB's KBucketSet. Client DBs do not have their own.
     */
    KBucketSet<Hash> getKBuckets() {return _kb;}
    DataStore getDataStore() {return _ds;}

    long getLastExploreNewDate() {return _lastExploreNew;}
    void setLastExploreNewDate(long when) {_lastExploreNew = when;}

    /** @return unmodifiable set */
    public Set<Hash> getExploreKeys() {
        if (!_initialized || isClientDb()) {return Collections.emptySet();}
        return Collections.unmodifiableSet(_exploreKeys);
    }

    public void removeFromExploreKeys(Collection<Hash> toRemove) {
        if (!_initialized || isClientDb()) {return;}
        _exploreKeys.removeAll(toRemove);
        _context.statManager().addRateData("netDb.exploreKeySet", _exploreKeys.size());
    }

    public void queueForExploration(Collection<Hash> keys) {
        String exploreQueue = _context.getProperty("router.exploreQueue");
        boolean upLongEnough = _context.router().getUptime() > 15*60*1000;
        if (!upLongEnough) {
            long down = _context.router().getEstimatedDowntime();
            upLongEnough = down > 0 && down < 10*60*60*1000L;
        }
        if (!_initialized || isClientDb()) {
            if (_log.shouldInfo() && !_initialized) {_log.info("Datastore not initialized -> Cannot queue keys for exploration...");}
            return;
        }
        // TODO: make sure exploreQueue isn't null before assigning
        for (Iterator<Hash> iter = keys.iterator(); iter.hasNext() && _exploreKeys.size() < MAX_EXPLORE_QUEUE;) {
            _exploreKeys.add(iter.next());
         }
        _context.statManager().addRateData("netDb.exploreKeySet", _exploreKeys.size());
    }

    /**
     *  Cannot be restarted.
     */
    public synchronized void shutdown() {
        if (_log.shouldInfo()) {_log.info("NetDb shutdown: " + this);}
        _initialized = false;
        if (!_context.commSystem().isDummy() && !isClientDb() &&
            _context.router().getUptime() > ROUTER_INFO_EXPIRATION_FLOODFILL + 11*60*1000) {
            Job erj = new ExpireRoutersJob(_context, this); // expire inline before saving RIs in _ds.stop()
            erj.runJob();
        }
        if (_elj instanceof ExpireLeasesJob) {
            ((ExpireLeasesJob) _elj).shutdown();
        }
        _context.jobQueue().removeJob(_elj);
        if (_erj != null) {_context.jobQueue().removeJob(_erj);}
        if (_kb != null && !isClientDb()) {_kb.clear();}
        if (_ds != null) {_ds.stop();}
        if (_exploreKeys != null) {_exploreKeys.clear();}
        if (_negativeCache != null) {_negativeCache.stop();}
        if (!isClientDb()) {blindCache().shutdown();}
    }

    /**
     *  Unsupported, do not use
     *
     *  @throws UnsupportedOperationException always
     *  @deprecated
     */
    @Deprecated
    public synchronized void restart() {throw new UnsupportedOperationException();}

    @Override
    public synchronized void rescan() {
        if (isInitialized()) {
            _ds.rescan();
            knownLeaseSetsCount.set(0);
            for (DatabaseEntry entry : _ds.getEntries()) {
                if (entry.isLeaseSet()) {knownLeaseSetsCount.incrementAndGet();}
            }
        }
    }

    /**
     * For the main DB only.
     * Sub DBs are not persisted and must not access this directory.
     *
     * @return null before startup() is called; non-null thereafter, even for subdbs.
     */
    String getDbDir() {return _dbDir;}

    /**
     * Check if the database is a client DB.
     *
     * @return  true if the database is a client DB, false otherwise
     * @since 0.9.61
     */
    public boolean isClientDb() {
        // This is a null check in disguise, don't use .equals() here.
        // FNDS.MAIN_DBID is always null. and if _dbid is also null it is not a client Db
        if (_dbid == FloodfillNetworkDatabaseSegmentor.MAIN_DBID) {return false;}
        return true;
    }

    /**
     *  Mark this client database as transient (short-lived, unpublished LeaseSets).
     *  This will skip starting the ExpireLeasesJob to reduce job queue pressure.
     *  @since 0.9.68+
     */
    public void setTransient() {
        _isTransient = true;
    }

    public void startup() {
        RouterInfo ri = _context.router().getRouterInfo();
        String dbDir = _context.getProperty(PROP_DB_DIR, DEFAULT_DB_DIR);
        boolean initMessage = false;
        if (isClientDb()) {_kb = ((FloodfillNetworkDatabaseFacade) _context.netDb()).getKBuckets();}
        else {
            synchronized (kbInitLock) {
                _kb = new KBucketSet<Hash>(_context, ri.getIdentity().getHash(),
                                           BUCKET_SIZE, KAD_B, new RejectTrimmer<Hash>());
            }
        }

        if (_log.shouldInfo() && _context.router().getUptime() < 60*1000 && !initMessage) {
            _log.info("Initializing the Kademlia Network Database...\n" +
                      "BucketSize: " + BUCKET_SIZE + "; B Value: " + KAD_B);
            initMessage = true;
        }

        try {
            if (!isClientDb()) {_ds = new PersistentDataStore(_context, dbDir, this);}
            else {_ds = new TransientDataStore(_context);}
        } catch (IOException ioe) {throw new RuntimeException("Unable to initialize NetDb storage", ioe);}

        for (DatabaseEntry entry : _ds.getEntries()) {
            if (entry.isLeaseSet()) {knownLeaseSetsCount.incrementAndGet();}
        }

        _dbDir = dbDir;
        _negativeCache = new NegativeLookupCache(_context);
        if (!isClientDb()) {blindCache().startup();}
        createHandlers();
        _initialized = true;
        _started = System.currentTimeMillis();
        long now = _context.clock().now();
        // Don't start ExpireLeasesJob for transient client DBs (e.g., HostChecker ping tunnels)
        // to reduce job queue pressure from short-lived tunnels
        if (!_isTransient) {
            _elj.getTiming().setStartAfter(now + 30*1000);
            _context.jobQueue().addJob(_elj); // expire old leases
        } else if (_log.shouldDebug()) {
            _log.debug("Skipping ExpireLeasesJob for transient client DB: " + _dbid);
        }

        // expire some routers
        // Don't run until after RefreshRoutersJob has run, and after validate() will return invalid for old routers.
        if (!isClientDb() && !_context.commSystem().isDummy()) {
            boolean isFF = _context.getBooleanProperty(FloodfillNetworkDatabaseFacade.PROP_FLOODFILL_PARTICIPANT);
            long down = _context.router().getEstimatedDowntime();
            long delay = 20*60*1000;
            if (down > 24*60*60*1000) {delay = 60*60*1000;}
            _erj.getTiming().setStartAfter(now + delay);
            _context.jobQueue().addJob(_erj);
        }

        if (!QUIET && !isClientDb()) {
            /**
             *  Fill the search queue with random keys in buckets that are too small
             *  Disabled since KBucketImpl.generateRandomKey() is b0rked,
             *  and anyway, we want to search for a completely random key,
             *  not a random key for a particular kbucket.
             */
            if (_exploreJob == null) {_exploreJob = new StartExplorersJob(_context, this);}

            /**
             *  Fire off a group of searches from the explore pool.
             *  Don't start it right away, so we don't send searches for random keys out our 0-hop exploratory
             *  tunnels (generating direct connections to one or more floodfill peers within seconds of startup).
             *  We're trying to minimize the ff connections to lessen the load on the floodfills, and in any case,
             * let's try to build some real expl. tunnels first. No rush, it only runs every 30m.
             */
            _exploreJob.getTiming().setStartAfter(now + EXPLORE_JOB_DELAY);
            _context.jobQueue().addJob(_exploreJob);
        } else if (QUIET) {
            _log.warn("Operating in QUIET MODE - not exploring or pushing data proactively, simply reactively " +
                      "\n* This should NOT be used in production!");
        }
        // PublishLocalRouterInfoJob is now started from Router.setNetDbReady()
    }

    /** unused, see override */
    protected void createHandlers() {}

    /**
     * Get the routers closest to that key in response to a remote lookup
     * Only used by ../HDLMJ
     * Set MAY INCLUDE our own router - add to peersToIgnore if you don't want
     *
     * @param key the real key, NOT the routing key
     * @param peersToIgnore can be null
     */
    public Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore) {
        if (isClientDb()) {return Collections.emptySet();}
        if (!_initialized) {return Collections.emptySet();}
        return new HashSet<Hash>(_peerSelector.selectNearest(key, maxNumRouters, peersToIgnore, _kb));
    }

    /** get the hashes for all known routers */
    public Set<Hash> getAllRouters() {
        if (isClientDb() || !_initialized) {return Collections.emptySet();}
        Set<Hash> result = new HashSet<>();
        for (DatabaseEntry entry : _ds.getEntries()) {
            if (entry.isRouterInfo()) {result.add(entry.getHash());}
        }
        return result;
    }

    /**
     *  This used to return the number of routers that were in both the kbuckets
     *  AND the data store, which was fine when the kbuckets held everything.
     *  But now that is probably not what you want.
     *  Just return the count in the data store.
     *
     *  @return 0 if this is a client DB
     */
    @Override
    public int getKnownRouters() {
        if (isClientDb() || _ds == null) {return 0;}
        int rv = 0;
        for (DatabaseEntry ds : _ds.getEntries()) {
            if (ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {rv++;}
        }
        return rv;
    }

    /**
     *  This is only used by StatisticsManager to publish the count if we are floodfill.
     *  So to hide a clue that a popular eepsite is hosted on a floodfill router,
     *  only count leasesets that are "received as published", as of 0.7.14
     */
    @Override
    public int getKnownLeaseSets() {
        return isClientDb() ? 0 : knownLeaseSetsCount.get();
    }

    /**
     *  The KBucketSet contains RIs only.
     */
    protected int getKBucketSetSize() {
        return _kb == null ? 0 : _kb.size();
    }

    /**
     *  @param spk unblinded key
     *  @return BlindData or null
     *  @since 0.9.40
     */
    @Override
    public BlindData getBlindData(SigningPublicKey spk) {
        return blindCache().getData(spk);
    }

    /**
     *  @param bd new BlindData to put in the cache
     *  @since 0.9.40
     */
    @Override
    public void setBlindData(BlindData bd) {
        if (_log.shouldWarn()) {_log.warn("Adding to blind cache: " + bd);}
        blindCache().addToCache(bd);
    }

    /**
     *  For console ConfigKeyringHelper
     *  @since 0.9.41
     */
    @Override
    public List<BlindData> getBlindData() {
       return blindCache().getData();
    }

    /**
     *  For console ConfigKeyringHelper
     *  @param spk the unblinded public key
     *  @return true if removed
     *  @since 0.9.41
     */
    @Override
    public boolean removeBlindData(SigningPublicKey spk) {
        return blindCache().removeBlindData(spk);
    }

    /**
     *  Notify the netDB that the routing key changed at midnight UTC
     *
     *  @since 0.9.50
     */
    @Override
    public void routingKeyChanged() {
        blindCache().rollover();
        if (_log.shouldInfo()) {_log.info("UTC rollover -> Blind cache updated");}
    }

    /**
     *  @return RouterInfo, LeaseSet, or null, validated
     *  @since 0.8.3
     */
    public DatabaseEntry lookupLocally(Hash key) {
        if (!_initialized) {return null;}
        DatabaseEntry rv = _ds.get(key);
        if (rv == null) {return null;}
        int type = rv.getType();
        if (DatabaseEntry.isLeaseSet(type)) {
            LeaseSet ls = (LeaseSet)rv;
            if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {return rv;}
            else {
                key = blindCache().getHash(key);
                fail(key);
            }
        } else if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            try {
                if (validate((RouterInfo)rv) == null) {return rv;}
            } catch (IllegalArgumentException iae) {}
            fail(key);
        }
        return null;
    }

    /**
     *  Not for use without validation
     *  @return RouterInfo, LeaseSet, or null, NOT validated
     *  @since 0.9.9, public since 0.9.38
     */
    public DatabaseEntry lookupLocallyWithoutValidation(Hash key) {
        if (!_initialized) {return null;}
        return _ds.get(key);
    }

    /**
     *  Lookup using exploratory tunnels.
     *  Use lookupDestination() if you don't need the LS or don't need it validated.
     */
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        lookupLeaseSet(key, onFindJob, onFailedLookupJob, timeoutMs, null);
    }

    /**
     *  Lookup using the client's tunnels
     *  Use lookupDestination() if you don't need the LS or don't need it validated.
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.10
     */
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob,
                               long timeoutMs, Hash fromLocalDest) {
        if (!_initialized) return;
        LeaseSet ls = lookupLeaseSetLocally(key);
        if (ls != null) {
            if (onFindJob != null) {_context.jobQueue().addJob(onFindJob);}
        } else if (isNegativeCached(key)) {
            if (_log.shouldInfo()) {
                _log.info("LeaseSet [" + key.toBase32().substring(0,8) +
                          "] is negatively cached -> Queueing search...");
            }
            if (onFailedLookupJob != null) {_context.jobQueue().addJob(onFailedLookupJob);}
        } else {
            key = blindCache().getHash(key);
            search(key, onFindJob, onFailedLookupJob, timeoutMs, true, fromLocalDest);
        }
    }

    /**
     *  Unconditionally lookup using the client's tunnels.
     *  No success or failed jobs, no local lookup, no checks.
     *  Use this to refresh a leaseset before expiration.
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.25
     */
    public void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest) {
        if (!_initialized) return;
        key = blindCache().getHash(key);
        if (isNegativeCached(key)) {return;}
        search(key, null, null, 30*1000, true, fromLocalDest);
    }

    /**
     *  Unconditionally lookup using the client's tunnels.
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @param onFindJob may be null
     *  @param onFailedLookupJob may be null
     *  @since 0.9.47
     */
    public void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob,
                                       long timeoutMs, Hash fromLocalDest) {
        if (!_initialized) {return;}
        key = blindCache().getHash(key);
        if (isNegativeCached(key)) {return;}
        search(key, onFindJob, onFailedLookupJob, timeoutMs, true, fromLocalDest);
    }

    /**
     *  Use lookupDestination() if you don't need the LS or don't need it validated.
     */
    public LeaseSet lookupLeaseSetLocally(Hash key) {
        if (!_initialized) {return null;}
        DatabaseEntry ds = _ds.get(key);
        if (ds == null || !ds.isLeaseSet()) {return null;}
        LeaseSet ls = (LeaseSet) ds;
        if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {return ls;}
        key = blindCache().getHash(key);
        fail(key);
        // Interesting key, so either refetch it or simply explore with it
        if (_exploreKeys != null) {_exploreKeys.add(key);}
        return null;
    }

    /**
     *  Lookup using the client's tunnels
     *  Succeeds even if LS validation and store fails due to unsupported sig type, expired, etc.
     *
     *  Note that there are not separate success and fail jobs. Caller must call
     *  lookupDestinationLocally() in the job to determine success.
     *
     *  @param onFinishedJob non-null
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.16
     */
    public void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest) {
        if (!_initialized) return;
        Destination d = lookupDestinationLocally(key);
        if (d != null) {_context.jobQueue().addJob(onFinishedJob);}
        else if (isNegativeCached(key)) {
            if (_log.shouldInfo()) {
                _log.info("Destination [" + key.toBase32().substring(0,8) + "] is negatively cached -> Aborting lookup");
            }
            _context.jobQueue().addJob(onFinishedJob);
        } else {
            key = blindCache().getHash(key);
            search(key, onFinishedJob, onFinishedJob, timeoutMs, true, fromLocalDest);
        }
    }

    /**
     *  Lookup locally in netDB and in badDest cache
     *  Succeeds even if LS validation fails due to unsupported sig type, expired, etc.
     *
     *  @since 0.9.16
     */
    public Destination lookupDestinationLocally(Hash key) {
        if (!_initialized) {return null;}
        DatabaseEntry ds = _ds.get(key);
        if (ds != null) {
            if (ds.isLeaseSet()) {
                LeaseSet ls = (LeaseSet)ds;
                return ls.getDestination();
            }
        } else {return _negativeCache.getBadDest(key);}
        return null;
    }

    private boolean containsCapability(RouterInfo ri, char capability) {
        String caps = ri.getCapabilities();
        return caps != null && caps.indexOf(capability) >= 0;
    }

    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        if (!_initialized) return;
        RouterInfo ri = lookupRouterInfoLocally(key);
        if (ri == null) {return;}
        if (onFindJob != null) {_context.jobQueue().addJob(onFindJob);}
        if (shouldBanlistBasedOnCountry(ri, key)) {handleBanlistAndRemove(ri, key, onFailedLookupJob);}
        else if (shouldBanlistXGUnreachable(ri, key)) {handleBanlistAndRemove(ri, key, onFailedLookupJob);}
        else if (shouldBanlistLUM(ri, key)) {handleBanlistAndRemove(ri, key, onFailedLookupJob);}
        else if (shouldBanlistOf57(ri, key)) {handleBanlistOf57(ri, key, onFailedLookupJob);}
        else if (isPermanentlyBlocklisted(key)) {handlePermanentBlocklist(ri, key, onFailedLookupJob);}
        else if (isHostileBlocklisted(key)) {handleHostileBlocklist(ri, key, onFailedLookupJob);}
        else if (isNegativeCached(key)) {handleNegativeCache(ri, key, onFailedLookupJob);}
        else {search(key, onFindJob, onFailedLookupJob, timeoutMs, false);}
    }

    /**
     * Should we banlist this router based on country restrictions?
     * Checks if:
     * - we are in strict country mode and router is in same country
     * - blockMyCountry is enabled
     * - router is in our country (strict country mode)
     *
     * @since 0.9.67+
     */
    private boolean shouldBanlistBasedOnCountry(RouterInfo ri, Hash key) {
        boolean isStrict = _context.commSystem().isInStrictCountry(key);
        return isStrict || _context.getBooleanProperty(PROP_BLOCK_MY_COUNTRY);
    }

    /**
     * Determine whether the given router should be banlisted due to being an XG router
     * that is neither reachable nor unreachable and the XG banlist option is enabled.
     *
     * @param ri the router info to evaluate
     * @param key the hash of the router (unused in this method, but kept for consistency)
     * @return true if the router matches the XG banlist criteria
     *
     * @since 0.9.67+
     */
    private boolean shouldBanlistXGUnreachable(RouterInfo ri, Hash key) {
        boolean isG = containsCapability(ri, Router.CAPABILITY_NO_TUNNELS);
        boolean isNotRorU = !containsCapability(ri, Router.CAPABILITY_UNREACHABLE) &&
                            !containsCapability(ri, Router.CAPABILITY_REACHABLE);
        boolean isXTier = containsCapability(ri, Router.CAPABILITY_BW_UNLIMITED);
        boolean blockXG = _context.getBooleanPropertyDefaultTrue(PROP_BLOCK_XG);
        boolean isUs = _context.routerHash().equals(ri.getIdentity().getHash());

        return !isUs && isG && isNotRorU && isXTier && blockXG;
    }

    /**
     * Determine whether the given router should be banlisted due to being:
     * - an L-tier or M-tier router (CAPABILITY_BW12 or CAPABILITY_BW32),
     * - currently unreachable (or lacking a 'R' capability),
     * - and running an outdated version (older than MIN_VERSION).
     *
     * This is typically used to filter out low-performing routers.
     *
     * @param ri the router info to evaluate
     * @param key the hash of the router (unused in this method, but kept for consistency)
     * @return true if the router matches the LU banlist criteria
     *
     * @since 0.9.67+
     */
    private boolean shouldBanlistLUM(RouterInfo ri, Hash key) {
        boolean isLTier = containsCapability(ri, Router.CAPABILITY_BW12) ||
                          containsCapability(ri, Router.CAPABILITY_BW32);

        boolean isUnreachable = containsCapability(ri, Router.CAPABILITY_UNREACHABLE) ||
                                !containsCapability(ri, Router.CAPABILITY_REACHABLE);

        boolean isOld = VersionComparator.comp(ri.getVersion(), MIN_VERSION) < 0;
        boolean isUs = _context.routerHash().equals(ri.getIdentity().getHash());

        return !isUs && isLTier && isOld;
    }

    /**
     * Determine whether the given router should be banlisted due to being:
     * - an Of-cap router (CAPABILITY_BW256 + floodfill),
     * - running version 0.9.57.
     *
     * This blocks a specific problematic version with floodfill capability.
     *
     * @param ri the router info to evaluate
     * @param key the hash of the router (unused in this method, but kept for consistency)
     * @return true if the router matches the Of 0.9.57 banlist criteria
     *
     * @since 0.9.68+
     */
    private boolean shouldBanlistOf57(RouterInfo ri, Hash key) {
        boolean blockOf57 = _context.getBooleanPropertyDefaultTrue(PROP_BLOCK_OF57);
        if (!blockOf57) return false;

        boolean isOTier = containsCapability(ri, Router.CAPABILITY_BW256);
        boolean isFloodfill = containsCapability(ri, FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
        boolean isVersion57 = "0.9.57".equals(ri.getVersion());
        boolean isUs = _context.routerHash().equals(ri.getIdentity().getHash());

        return !isUs && isOTier && isFloodfill && isVersion57;
    }

    private boolean isPermanentlyBlocklisted(Hash key) {
        return _context.banlist().isBanlistedForever(key);
    }

    private boolean isHostileBlocklisted(Hash key) {
        return _context.banlist().isBanlistedHostile(key);
    }

    private void handleBanlistAndRemove(RouterInfo ri, Hash key, Job onFailedLookupJob) {
        String caps = ri.getCapabilities();
        String routerId = key.toBase64().substring(0,6);
        boolean isFF = ri.getCapabilities().contains("F");

        if (_log.shouldWarn()) {
            _log.warn("Banning " + (!caps.isEmpty() ? caps : "") + ' ' + (isFF ? "Floodfill" : "Router") +
                      " [" + routerId + "] for 4h -> LU and older than " + MIN_VERSION);
        }

        _context.banlist().banlistRouter(key, "➜ LU and older than " + MIN_VERSION, null, null,
                                         _context.clock().now() + 4 * 60 * 60 * 1000);
        _banLogger.logBan(key, _context, "LU and older than " + MIN_VERSION, 4 * 60 * 60 * 1000);
        _ds.remove(key);
        _kb.remove(key);

        if (onFailedLookupJob != null) {_context.jobQueue().addJob(onFailedLookupJob);}
    }

    private void handleBanlistOf57(RouterInfo ri, Hash key, Job onFailedLookupJob) {
        String caps = ri.getCapabilities();
        String routerId = key.toBase64().substring(0,6);

        String ip = null;
        for (RouterAddress ra : ri.getAddresses()) {
            ip = ra.getHost();
            if (ip != null) break;
        }

        if (_log.shouldWarn()) {
            _log.warn("Banning \'O\' tier floodfill [" + routerId + "] for 4h -> 0.9.57 (probable botnet participant)");
        }

        String reason = "O tier floodfill / 0.9.57";
        _context.banlist().banlistRouter(key, " <b>➜</b> " + reason, null, null,
                                         _context.clock().now() + 4 * 60 * 60 * 1000);
        _banLogger.logBan(key, _context, reason  + (ip != null ? " (" + ip + ")" : ""), 4 * 60 * 60 * 1000);
        _ds.remove(key);
        _kb.remove(key);

        if (onFailedLookupJob != null) {_context.jobQueue().addJob(onFailedLookupJob);}
    }

    private void handlePermanentBlocklist(RouterInfo ri, Hash key, Job onFailedLookupJob) {
        if (_log.shouldInfo()) {
            _log.info("Dropping RouterInfo [" + key.toBase64().substring(0,6) + "] -> Permanently blocklisted");
        }
        _ds.remove(key);
        _kb.remove(key);

        if (onFailedLookupJob != null) {
            _context.jobQueue().addJob(onFailedLookupJob);
        }
    }

    private void handleHostileBlocklist(RouterInfo ri, Hash key, Job onFailedLookupJob) {
        if (_log.shouldInfo()) {
            _log.info("Dropping RouterInfo [" + key.toBase64().substring(0,6) + "] -> Blocklisted (tagged as hostile)");
        }

        // Remove from data store and KBucket
        _ds.remove(key);
        _kb.remove(key);

        // Trigger the failed job callback if present
        if (onFailedLookupJob != null) {_context.jobQueue().addJob(onFailedLookupJob);}
    }

    private void handleNegativeCache(RouterInfo ri, Hash key, Job onFailedLookupJob) {
        if (_log.shouldInfo()) {
            _log.info("Dropping RouterInfo [" + key.toBase64().substring(0,6) + "] -> Negatively cached");
        }

        _ds.remove(key);
        _kb.remove(key);

        if (onFailedLookupJob != null) {_context.jobQueue().addJob(onFailedLookupJob);}
    }

    /**
     * This will return immediately with the result or null.
     * However, this may still fire off a lookup if the RI is present but expired (and will return null).
     * This may result in deadlocks.
     * For true local only, use lookupLocallyWithoutValidation()
     *
     * @return null always for client dbs
     */
    public RouterInfo lookupRouterInfoLocally(Hash key) {
        if (!_initialized || isClientDb()) {return null;}
        DatabaseEntry ds = _ds.get(key);
        if (ds == null || ds.getType() != DatabaseEntry.KEY_TYPE_ROUTERINFO) {return null;}
        RouterInfo ri = (RouterInfo) ds;
        boolean valid = true;
        try {valid = (null == validate(ri));}
        catch (IllegalArgumentException iae) {valid = false;}
        if (!valid) {fail(key); return null;}
        return ri;
    }

    private static final long PUBLISH_DELAY = 1000;
    private static final long PROACTIVE_REPUBLISH_THRESHOLD = 3 * 60 * 1000;

    /**
     * Publishes a local LeaseSet by storing it and scheduling republishing if applicable.
     * Ensures LeaseSet expiry dates are valid (not expired before publish).
     *
     * @param localLeaseSet the LeaseSet to publish
     * @throws IllegalArgumentException if LeaseSet is invalid or expired
     */
    public void publish(LeaseSet localLeaseSet) throws IllegalArgumentException {
        if (!_initialized) {
            if (_log.shouldWarn()) {
                _log.warn("Attempted to publish LOCAL LeaseSet before router fully initialized: " + localLeaseSet);
            }
            return;
        }

        // Validate LeaseSet expiry before storing
        validateLeaseSetExpiry(localLeaseSet);

        Hash hash = localLeaseSet.getHash();

        try {
            store(hash, localLeaseSet, true); // force overwrite
        } catch (IllegalArgumentException iae) {
            if (iae.getMessage() != null && iae.getMessage().contains("Future LeaseSet")) {
                if (_log.shouldWarn()) {
                    _log.warn("Locally published LeaseSet is in the future - will retry later: " + iae.getMessage());
                }
                return;
            }
            if (_log.shouldWarn()) {_log.warn("Locally published LeaseSet is not valid", iae);}
            throw iae;
        }

        // Skip publishing if client manager advises against it
        if (!_context.clientManager().shouldPublishLeaseSet(hash)) {
            return;
        }

        // If shutting down gracefully with defined exit codes, skip publishing
        if (_context.router().gracefulShutdownInProgress()) {
            int code = _context.router().scheduledGracefulExitCode();
            if (code == Router.EXIT_GRACEFUL || code == Router.EXIT_HARD) {
                return;
            }
        }

        scheduleRepublish(hash);
    }

    /**
     * Validates that the LeaseSet has not expired before current publication time.
     * Throws IllegalArgumentException if LeaseSet is already expired.
     */
    private static final long EXPIRY_GRACE_PERIOD = 90*1000;

    private void validateLeaseSetExpiry(LeaseSet leaseSet) {
        long now = _context.clock().now();
        long earliest = leaseSet.getEarliestLeaseDate();
        long latest = leaseSet.getLatestLeaseDate();

        if (earliest < now - EXPIRY_GRACE_PERIOD || latest < now - EXPIRY_GRACE_PERIOD) {
            throw new IllegalArgumentException("Cannot publish LeaseSet with expiry date in the past. "
                    + "Earliest: " + new Date(earliest) + ", Latest: " + new Date(latest)
                    + ", Now: " + new Date(now));
        }
    }

    /**
     * Batched LeaseSet republishing constants.
     */
    private static final long BATCH_WINDOW_MS = 15 * 1000; // 15 second batching window
    private static final long BATCH_PROCESS_DELAY = 5 * 1000; // Process batch 5s after first item

    /**
     * Queues the LeaseSet for future republishing using batching to reduce job queue pressure.
     * Proactively starts republishing 3 minutes before lease expiration to ensure
     * network propagation has ample time before the lease expires.
     *
     * <p>Uses 15-second batching window to consolidate multiple republish jobs into fewer jobs.</p>
     */
    private void scheduleRepublish(Hash hash) {
        if (hasActiveRepublishJob(hash)) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping republish scheduling for [" + hash.toBase32().substring(0, 8) +
                           "] -> Job already active");
            }
            return;
        }

        // Check if lease is expiring soon - if so, process immediately
        LeaseSet ls = lookupLeaseSetLocally(hash);
        long now = _context.clock().now();
        if (ls != null) {
            long expiration = ls.getLatestLeaseDate();
            if (expiration - now < PROACTIVE_REPUBLISH_THRESHOLD) {
                // Expiring soon - process immediately, don't batch
                RepublishLeaseSetJob job = new RepublishLeaseSetJob(_context, this, hash);
                job.getTiming().setStartAfter(now + PUBLISH_DELAY);
                if (job instanceof JobImpl) {
                    ((JobImpl) job).madeReady(now);
                }
                _context.jobQueue().addJobToTop(job);
                return;
            }
        }

        // Add to batch queue for non-urgent republishes
        _batchRepublishQueue.offer(hash);

        // Check if we need to schedule batch processor
        long timeSinceLastBatch = now - _lastBatchProcessTime;
        if (timeSinceLastBatch >= BATCH_WINDOW_MS) {
            // Schedule batch processor to run in 5 seconds
            _lastBatchProcessTime = now;
            BatchRepublishJob batchJob = new BatchRepublishJob(_context);
            batchJob.getTiming().setStartAfter(now + BATCH_PROCESS_DELAY);
            // High priority - LeaseSet republishing is critical for network connectivity
            _context.jobQueue().addJobToTop(batchJob);
            if (_log.shouldDebug()) {
                _log.debug("Scheduled batch republish job for " + _batchRepublishQueue.size() + " LeaseSets");
            }
        }
    }

    /**
     * Job to process batched LeaseSet republishes.
     * Drains the batch queue and creates individual republish jobs for each LeaseSet.
     */
    private class BatchRepublishJob extends JobImpl {
        public BatchRepublishJob(RouterContext ctx) {
            super(ctx);
        }

        public String getName() { return "Republish LeaseSets (batch)"; }

        @Override
        public void runJob() {
            int count = 0;
            Hash hash;
            while ((hash = _batchRepublishQueue.poll()) != null) {
                if (!hasActiveRepublishJob(hash)) {
                    RepublishLeaseSetJob job = new RepublishLeaseSetJob(getContext(),
                            KademliaNetworkDatabaseFacade.this, hash);
                    long now = getContext().clock().now();
                    job.getTiming().setStartAfter(now + (count * 100)); // 100ms stagger per item
                    getContext().jobQueue().addJob(job);
                    count++;
                }
            }
            if (_log.shouldInfo() && count > 0) {
                _log.info("Batch processed " + count + " LeaseSet republishes");
            }
        }
    }

    /**
     * Remove a specific job from the tracking set.
     */
    void removePublishingJob(Hash hash, RepublishLeaseSetJob job) {
        Set<RepublishLeaseSetJob> jobs = _publishingLeaseSets.get(hash);
        if (jobs != null) {
            synchronized (jobs) {
                jobs.remove(job);
                if (jobs.isEmpty()) {
                    _publishingLeaseSets.remove(hash);
                }
            }
        }
    }

    /**
     * Check if an active republish job exists for the given destination.
     */
    boolean hasActiveRepublishJob(Hash hash) {
        Set<RepublishLeaseSetJob> jobs = _publishingLeaseSets.get(hash);
        return jobs != null && !jobs.isEmpty();
    }

    /**
     * Register a new job for tracking. Skips registration if a job is already active.
     * @return true if job was registered, false if one was already active
     */
    boolean registerPublishingJob(RepublishLeaseSetJob job) {
        Hash hash = job.getDestHash();
        Set<RepublishLeaseSetJob> jobs = _publishingLeaseSets.computeIfAbsent(hash,
            key -> Collections.newSetFromMap(new ConcurrentHashMap<>()));

        synchronized (jobs) {
            if (!jobs.isEmpty()) {
                if (_log.shouldDebug()) {
                    _log.debug("Skipping duplicate republish job for [" + hash.toBase32().substring(0, 8) + "]");
                }
                return false;
            }
            jobs.add(job);
            return true;
        }
    }

    void stopPublishing(Hash target) {
        Set<RepublishLeaseSetJob> jobs = _publishingLeaseSets.remove(target);
        if (jobs != null) {
            synchronized (jobs) {
                for (RepublishLeaseSetJob job : jobs) {
                    _context.jobQueue().removeJob(job);
                }
            }
        }
    }

    /**
     * Stores to local db only.
     * Overridden in FNDF to actually send to the floodfills.
     * @throws IllegalArgumentException if the local router info is invalid
     *         or if this is a client DB
     */
    public void publish(RouterInfo localRouterInfo) throws IllegalArgumentException {
        if (isClientDb()) {throw new IllegalArgumentException("RouterInfo publication to ClientDb attempted");}
        if (!_initialized) {return;}
        if (_context.router().gracefulShutdownInProgress()) {return;}
        if (_context.router().isHidden()) {return;} // don't store RouterInfos with hidden cap
        Hash h = localRouterInfo.getIdentity().getHash();
        store(h, localRouterInfo);
    }

    /**
     *  Set the last time we successfully published our RI.
     *  @since 0.9.9
     */
    void routerInfoPublishSuccessful() {_lastRIPublishTime = _context.clock().now();}

    /**
     *  The last time we successfully published our RI.
     *  @since 0.9.9
     */
    @Override
    public long getLastRouterInfoPublishTime() {return _lastRIPublishTime;}

    /**
     * Validates whether the given LeaseSet is acceptable as valid and current based on current known parameters.
     *
     * <p>This method performs several checks including:</p>
     * <ul>
     *   <li>Verifying the key matches the LeaseSet destination.</li>
     *   <li>Validating the LeaseSet's cryptographic signature.</li>
     *   <li>Checking if the LeaseSet is too old (expired).</li>
     *   <li>Checking if the LeaseSet expires too far in the future.</li>
     * </ul>
     *
     * <p>Note: Unlike RouterInfos, this validation is only done once at storage time.
     * Afterward, {@code LeaseSet.isCurrent()} should be used to check currency.</p>
     *
     * @param key the expected key (destination hash) for this LeaseSet
     * @param leaseSet the LeaseSet instance to validate
     * @throws UnsupportedCryptoException if the LeaseSet uses an unsupported cryptographic type/signature
     * @return a descriptive reason why the LeaseSet is invalid or expired, or {@code null} if the LeaseSet is valid
     */
    public String validate(Hash key, LeaseSet leaseSet) throws UnsupportedCryptoException {
        String keyBase32 = key.toBase32();
        Hash leaseHash = leaseSet.getHash();
        String leaseBase32 = leaseHash.toBase32();

        // Check that the key matches the LeaseSet hash
        if (!key.equals(leaseHash)) {
            if (_log.shouldWarn()) {
                _log.warn("Invalid NetDbStore attempt! Key does not match LeaseSet destination!" +
                          "\n* Key: [" + keyBase32.substring(0, 8) + "]" +
                          "\n* LeaseSet: [" + leaseBase32.substring(0, 8) + "]");
            }
            return "Key does not match LeaseSet destination - " + keyBase32;
        }

        // Verify the LeaseSet's cryptographic signature validity
        if (!leaseSet.verifySignature()) {
            processStoreFailure(key, leaseSet); // This may throw UnsupportedCryptoException if signature type is unsupported
            if (_log.shouldWarn()) {
                _log.warn("Invalid LeaseSet signature! [" + leaseBase32.substring(0, 8) + "]");
            }
            return "Invalid LeaseSet signature on " + key;
        }

        // Determine LeaseSet date boundaries based on LeaseSet type
        long earliest, latest;
        int type = leaseSet.getType();

        if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
            // For encrypted LeaseSet2, use published and expires times directly
            LeaseSet2 ls2 = (LeaseSet2) leaseSet;
            earliest = ls2.getPublished();
            latest = ls2.getExpires();
        } else if (type == DatabaseEntry.KEY_TYPE_META_LS2) {
            // For meta LeaseSet2, use minimum of lease and publish dates for earliest/latest
            LeaseSet2 ls2 = (LeaseSet2) leaseSet;
            earliest = Math.min(ls2.getEarliestLeaseDate(), ls2.getPublished());
            latest = Math.min(ls2.getLatestLeaseDate(), ls2.getExpires());
        } else {
            // For legacy LeaseSet, directly query earliest and latest lease dates
            earliest = leaseSet.getEarliestLeaseDate();
            latest = leaseSet.getLatestLeaseDate();
        }

        long now = _context.clock().now();
        final long TEN_MINUTES_MS = 10 * 60 * 1000L;  // 10 minutes in milliseconds

        // During attacks (low build success), relax expiry checks to accommodate tunnel delays
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        boolean isUnderAttack = buildSuccess < 0.4;
        long attackGracePeriod = isUnderAttack ? 30 * 1000L : 0;  // 30 seconds grace during attacks

        // Retrieve destination ID string, fallback to LeaseSet hash base32 if null
        Destination dest = leaseSet.getDestination();
        String id = (dest != null ? dest.toBase32() : leaseBase32);
        String idShort = id.substring(0, 6);  // Shortened ID for logs and messages

        // Determine if LeaseSet timestamps are outdated (stale)
        // During attacks, apply grace period to isLatestStale check
        boolean isEarliestStale = earliest <= now - TEN_MINUTES_MS;
        boolean isLatestStale = latest <= now - Router.CLOCK_FUDGE_FACTOR - attackGracePeriod;

        if (isEarliestStale || isLatestStale) {
            long age = now - earliest;
            if (isUnderAttack && latest > now - Router.CLOCK_FUDGE_FACTOR - 60 * 1000L) {
                if (_log.shouldWarn()) {
                    _log.warn("Old LeaseSet during attack - accepting with grace: [" + idShort + "]" +
                              "\n* Expired: " + DataHelper.formatDuration(age) + " ago");
                }
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Old LeaseSet [" + idShort + "] -> rejecting store..." +
                              "\n* First expired: " + new Date(earliest) +
                              "\n* Last expired: " + new Date(latest) +
                              "\n* " + leaseSet);
                }
                // If there are no leases, aggressively mark lookups as failed to reduce retries
                if (leaseSet.getLeaseCount() == 0) {
                    for (int i = 0; i < NegativeLookupCache.MAX_FAILS; i++) {
                        lookupFailed(key);
                    }
                }
                return "LeaseSet for [" + idShort + "] expired " + DataHelper.formatDuration(age) + " ago";
            }
        }

        // Determine limits for future expiration timestamps
        long futureLimit = Router.CLOCK_FUDGE_FACTOR + MAX_LEASE_FUTURE;
        long metaFutureLimit = Router.CLOCK_FUDGE_FACTOR + MAX_META_LEASE_FUTURE;

        // During attacks (low build success), relax future limits to allow more LeaseSets
        if (isUnderAttack) {
            futureLimit += 10 * 60 * 1000L;  // Add 10 minutes buffer during attacks
            metaFutureLimit += 15 * 60 * 1000L;  // Add 15 minutes for meta LeaseSets
            if (_log.shouldWarn()) {
                _log.warn("Under attack (" + (int)(buildSuccess * 100) + "% build success) -> Relaxing future publication limits for LeaseSets...");
            }
        }

        // If LeaseSet expires too far into the future, reject it (to handle clock skew issues)
        boolean isFutureTooFar =
            latest > now + futureLimit &&
            (type != DatabaseEntry.KEY_TYPE_META_LS2 || latest > now + metaFutureLimit);

        if (isFutureTooFar) {
            // During attacks, just warn instead of rejecting for slightly future LeaseSets
            if (isUnderAttack && latest <= now + futureLimit + (5 * 60 * 1000L)) {
                if (_log.shouldWarn()) {
                    _log.warn("LeaseSet slightly in future during attack - accepting: [" + idShort + "]" +
                              "\n* Expires: " + DataHelper.formatDuration(latest - now) + " from now");
                }
                return null;  // Accept it
            }
            long age = latest - now;
            if (_log.shouldWarn()) {
                _log.warn("LeaseSet expires too far in the future: [" + idShort + "]" +
                          "\n* Expires: " + DataHelper.formatDuration(age) + " from now");
            }
            return "Future LeaseSet for [" + idShort + "] expiring in " + DataHelper.formatDuration(age);
        }

        // Passed all validation checks - LeaseSet is valid and current
        return null;
    }

    public LeaseSet store(Hash key, LeaseSet leaseSet) throws IllegalArgumentException {
        return store(key, leaseSet, false);
    }

    /**
     * Store the leaseSet.
     *
     * If the store fails due to unsupported crypto, it will negative cache
     * the hash until restart.
     *
     * @param force always store even if not newer
     * @throws IllegalArgumentException if the leaseSet is not valid
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return previous entry or null
     * @since 0.9.64
     */
    public LeaseSet store(Hash key, LeaseSet leaseSet, boolean force) throws IllegalArgumentException {
        if (!_initialized) {return null;}

        LeaseSet rv;
        try {
            rv = (LeaseSet)_ds.get(key);
            if (rv != null && !force && !isNewer(leaseSet, rv)) {
                if (_log.shouldDebug()) {
                    _log.debug("Not storing LeaseSet [" + key.toBase32().substring(0,8) + "] -> Local copy is newer");
                }
                // Copy over relevant metadata flags without re-storing
                Hash to = leaseSet.getReceivedBy();
                if (to != null) {rv.setReceivedBy(to);}
                else if (leaseSet.getReceivedAsReply()) {rv.setReceivedAsReply();}
                if (leaseSet.getReceivedAsPublished()) {rv.setReceivedAsPublished();}
                return rv;
            }
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException("Attempt to replace RouterInfo with LeaseSet: " + leaseSet, cce);
        }

        // spoof / hash collision detection
        // todo allow non-exp to overwrite exp
        if (rv != null && !force) {
            Destination d1 = leaseSet.getDestination();
            Destination d2 = rv.getDestination();
            if (d1 != null && d2 != null && !d1.equals(d2)) {
                throw new IllegalArgumentException("LeaseSet Hash collision");
            }
        }

        EncryptedLeaseSet encls = null;
        int type = leaseSet.getType();
        if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
            // set dest or key before validate() calls verifySignature() which
            // will do the decryption
            encls = (EncryptedLeaseSet) leaseSet;
            BlindData bd = blindCache().getReverseData(leaseSet.getSigningKey());
            if (bd != null) {
                if (_log.shouldInfo()) {_log.info("Found blind data for encrypted LeaseSet: " + bd);}
                // secret must be set before destination
                String secret = bd.getSecret();
                if (secret != null) {encls.setSecret(secret);}
                Destination dest = bd.getDestination();
                if (dest != null) {encls.setDestination(dest);}
                else {encls.setSigningKey(bd.getUnblindedPubKey());}
                // per-client auth
                if (bd.getAuthType() != BlindData.AUTH_NONE) {
                    encls.setClientPrivateKey(bd.getAuthPrivKey());
                }
            } else {
                // if we created it, there's no blind data, but it's still decrypted
                if (encls.getDecryptedLeaseSet() == null && _log.shouldInfo()) {
                    _log.info("No blind data found for " + leaseSet);
                }
            }
        }

        String err = validate(key, leaseSet);
        if (err != null) {
            throw new IllegalArgumentException("Invalid NetDbStore attempt \n* " + err);
        }

        if (force) {_ds.forcePut(key, leaseSet);}
        else {_ds.put(key, leaseSet);}

        if (rv == null) {knownLeaseSetsCount.incrementAndGet();}

        if (encls != null) {
            // we now have decrypted it, store it as well
            LeaseSet decls = encls.getDecryptedLeaseSet();
            if (decls != null) {
                if (_log.shouldWarn()) {
                    _log.warn("Successfully decrypted encrypted LeaseSet: " + decls);
                }
                // recursion
                Destination dest = decls.getDestination();
                store(dest.getHash(), decls);
                blindCache().setBlinded(dest);
            }
        } else if (type == DatabaseEntry.KEY_TYPE_LS2 || type == DatabaseEntry.KEY_TYPE_META_LS2) {
             // if it came in via garlic
             LeaseSet2 ls2 = (LeaseSet2) leaseSet;
             if (ls2.isBlindedWhenPublished()) {
                 Destination dest = leaseSet.getDestination();
                 if (dest != null) {blindCache().setBlinded(dest, null, null);}
            }
        }
        return rv;
    }

    /**
     * Utility to determine if a is newer than b.
     * Uses publish date if a and b are both LS2, else earliest lease date.
     *
     * @param a non-null
     * @param b non-null
     * @return if a is newer than b
     * @since 0.9.64
     */
    public static boolean isNewer(LeaseSet a, LeaseSet b) {
        if (a.getType() != DatabaseEntry.KEY_TYPE_LEASESET &&
            b.getType() != DatabaseEntry.KEY_TYPE_LEASESET) {
            return ((LeaseSet2) a).getPublished() > ((LeaseSet2) b).getPublished();
        } else {return a.getEarliestLeaseDate() > b.getEarliestLeaseDate();}
    }

    private static final int MIN_ROUTERS = 2000;

    /**
     * Determine whether this routerInfo will be accepted as valid and current given what we know now.
     * Call this only on first store, to check the key and signature once.
     *
     * If the store fails due to unsupported crypto, it will banlist the router hash until restart
     * and then throw UnsupportedCrytpoException.
     *
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return reason why the entry is not valid, or null if it is valid
     */
    private String validate(Hash key, RouterInfo routerInfo) throws IllegalArgumentException {
        if (!key.equals(routerInfo.getIdentity().getHash())) {
            if (_log.shouldWarn()) {
                _log.warn("Invalid NetDbStore attempt! Key [" + key.toBase64().substring(0,6) + "] " +
                          "does not match identity for RouterInfo [" +
                          routerInfo.getIdentity().getHash().toBase64().substring(0,6) + "]");
            }
            return "Key does not match routerInfo.identity";
        }
        // todo experimental sig types
        if (!routerInfo.isValid()) {
            processStoreFailure(key, routerInfo); // throws UnsupportedCryptoException
            if (_log.shouldWarn()) {
                _log.warn("Invalid RouterInfo signature detected for [" + routerInfo.getIdentity().getHash().toBase64().substring(0,6) + "]");
            }
            return "Invalid RouterInfo signature";
        }
        int id = routerInfo.getNetworkId();
        if (id != _networkID) {
            if (id == -1) {
                // old i2pd bug, possibly at startup, don't ban forever
                _context.banlist().banlistRouter(key, " <b>➜</b> No Network specified", null, null,
                                                 _context.clock().now() + Banlist.BANLIST_DURATION_NO_NETWORK);
                _banLogger.logBan(key, _context, "No Network specified", Banlist.BANLIST_DURATION_NO_NETWORK);
            } else {
                _context.banlist().banlistRouterForever(key, " <b>➜</b> " + "Not in our Network: " + id);
                _banLogger.logBanForever(key, _context, "Not in our Network: " + id);
            }
            if (_log.shouldWarn()) {
                _log.warn("BAD Network detected for [" + routerInfo.getIdentity().getHash().toBase64().substring(0,6) + "]");
            }
            return "Not in our network";
        }
        FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
        if (fkc != null) {
            FamilyKeyCrypto.Result r = fkc.verify(routerInfo);
            switch (r) {
                case BAD_KEY:
                case INVALID_SIG:
                    Hash h = routerInfo.getHash();
                    // never fail our own router, that would cause a restart and rekey
                    if (h.equals(_context.routerHash())) {break;}
                    return "BAD Family " + r + ' ' + h;

                case NO_SIG:
                    // Routers older than 0.9.54 that added a family and haven't restarted
                    break;

                case BAD_SIG:
                    // To be investigated
                    break;
            }
        }
        return validate(routerInfo);
    }

    /**
     * Determine whether this routerInfo will be accepted as valid and current
     * given what we know now.
     *
     * Call this before each use, to check expiration
     *
     * @return reason why the entry is not valid, or null if it is valid
     * @since 0.9.7
     */
    String validate(RouterInfo routerInfo) throws IllegalArgumentException {
        if (shouldSkipValidation() || routerInfo == null) {return null;}

        long now = _context.clock().now();
        String routerId = getRouterId(routerInfo);
        String caps = routerInfo.getCapabilities() != null ? routerInfo.getCapabilities().toUpperCase() : "";
        Hash h = routerInfo.getIdentity().getHash();
        if (h == null) {return null;}
        boolean isUs = h.equals(_context.routerHash());
        //int existing = _kb.size();
        int existing = getStoredRouterInfoCount();

        if (banInvalidNTCPAddresses(routerInfo, now, caps, routerId)) {return "Invalid NTCP address";}
        if (_context.banlist().isBanlisted(h)) {return null;}

        long uptime = _context.router().getUptime();
        boolean upLongEnough = isUptimeLongEnough(uptime);
        long adjustedExpiration = computeAdjustedExpiration(existing);
        boolean dontFail = uptime < DONT_FAIL_PERIOD;

        if (checkCountryBlocking(routerInfo, caps, routerId, h)) {return null;}

        String futureBanReason = checkFutureRouterInfo(routerInfo, now, caps, routerId, h, isUs);
        if (futureBanReason != null) {return futureBanReason;}

        String oldVersionBan = checkRouterVersion(routerInfo, caps, routerId, h);
        if (oldVersionBan != null) {return oldVersionBan;}

        String slowDrop = dropSlowRouter(routerInfo, now, existing, caps, routerId);
        if (slowDrop != null) {return slowDrop;}

        String addrCheckDrop = checkAddressesAndIntroducers(routerInfo, now, caps, routerId, isUs, dontFail);
        if (addrCheckDrop != null) {return addrCheckDrop;}

        String expirationDrop = checkExpirationBasedDrop(routerInfo, upLongEnough, adjustedExpiration, now, caps, routerId, isUs);
        if (expirationDrop != null) {return expirationDrop;}

        String shortExpireDrop = checkShortExpiration(routerInfo, caps, routerId, isUs);
        if (shortExpireDrop != null) {return shortExpireDrop;}

        String staleDropReason = checkStaleRouterInfo(routerInfo, upLongEnough, existing, caps, routerId, isUs);
        if (staleDropReason != null) {return staleDropReason;}

        return null;
    }

    /**
     * Determines whether validation should be skipped based on initialization status or dummy communication system.
     *
     * @return true if validation should be skipped, false otherwise
     * @since 0.9.67+
     */
    private boolean shouldSkipValidation() {
        return !isInitialized() || _context.commSystem().isDummy();
    }

    /**
     * Generates a short identifier for a router based on its hash.
     *
     * @param routerInfo the RouterInfo to extract the ID from
     * @return the first 6 characters of the base64-encoded router hash
     * @since 0.9.67+
     */
    private String getRouterId(RouterInfo routerInfo) {
        return routerInfo.getIdentity().getHash().toBase64().substring(0,6);
    }

    /**
     * Checks for invalid NTCP2 addresses in the given RouterInfo and bans the router if any are found.
     *
     * @param routerInfo the RouterInfo to validate
     * @param now current timestamp in milliseconds
     * @param caps the capabilities string of the router
     * @param routerId the short ID of the router
     * @return true if the router has invalid NTCP addresses and was banned, false otherwise
     * @since 0.9.67+
     */
    private boolean banInvalidNTCPAddresses(RouterInfo routerInfo, long now, String caps, String routerId) {
        for (RouterAddress ra : routerInfo.getTargetAddresses("NTCP2")) {
            String i = ra.getOption("i");
            if (i != null && i.length() != 24) {
                Hash h = routerInfo.getIdentity().calculateHash();
                _context.banlist().banlistRouter(h, " <b>➜</b> Invalid NTCP address", null, null, now + 24*60*60*1000L);
                _banLogger.logBan(h, _context, "Invalid NTCP address", 24*60*60*1000L);
                if (_log.shouldWarn()) {
                    _log.warn("Banning " + (caps.isEmpty() ? "" : caps + " ") + "Router [" + routerId + "] for 24h -> Invalid NTCP address");
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the router's uptime is long enough to begin validating other routers.
     *
     * The minimum uptime is determined by the property "router.validateRoutersAfter" (in minutes),
     * or defaults to 20 minutes.
     *
     * @param uptime the uptime of the local router in milliseconds
     * @return true if the uptime is sufficient, false otherwise
     * @since 0.9.67+
     */
    private boolean isUptimeLongEnough(long uptime) {
        String validateUptime = _context.getProperty("router.validateRoutersAfter");
        if (validateUptime != null) {
            try {
                int mins = Integer.parseInt(validateUptime);
                return uptime > mins*60*1000;
            } catch (NumberFormatException ignored) {}
        }
        return uptime > 20*60*1000;
    }

    /**
     * Computes the expiration time for a RouterInfo based on current network conditions.
     *
     * The expiration time can be customized via the "router.expireRouterInfo" property (in hours),
     * or defaults based on the number of existing routers or whether this is a floodfill.
     *
     * @param existing the number of routers currently in the KBucket
     * @return the adjusted expiration time in milliseconds
     * @since 0.9.67+
     */
    private long computeAdjustedExpiration(int existingRouters) {
        String expireRI = _context.getProperty("router.expireRouterInfo");
        boolean shouldBoost = _context.router().isHidden() ||
                              _context.router().getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
        if (expireRI != null) {
            try {return Integer.parseInt(expireRI)*60*60*1000L;}
            catch (NumberFormatException ignored) {}
        }
        long calculatedExpiry = ROUTER_INFO_EXPIRATION;
        if (floodfillEnabled() && existingRouters > 3000) {calculatedExpiry = ROUTER_INFO_EXPIRATION_FLOODFILL / 3 * 2;}
        else if (floodfillEnabled() && existingRouters > 2000) {calculatedExpiry = ROUTER_INFO_EXPIRATION_FLOODFILL;}
        else if (floodfillEnabled()) {calculatedExpiry = ROUTER_INFO_EXPIRATION_FLOODFILL * 3 / 2;}
        else if (existingRouters < 1000) {calculatedExpiry = ROUTER_INFO_EXPIRATION * 2;}
        else if (existingRouters < 2000) {calculatedExpiry = ROUTER_INFO_EXPIRATION * 3 / 2;}
        else {calculatedExpiry = ROUTER_INFO_EXPIRATION;}
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        if (buildSuccess < 0.40) {calculatedExpiry *= 2;}
        if (shouldBoost) {calculatedExpiry *= 2;}
        return calculatedExpiry;
    }

    /**
     * Checks if the given router should be blocked due to country restrictions.
     *
     * This includes:
     * - Strict country mode
     * - Hidden mode
     * - Configuration-based country bans
     * - XG router bans
     *
     * @param routerInfo the RouterInfo to evaluate
     * @param caps the capabilities string of the router
     * @param routerId the short ID of the router
     * @param h the Hash of the router
     * @return true if the router should be blocked, false otherwise
     * @since 0.9.67+
     */
    private boolean checkCountryBlocking(RouterInfo routerInfo, String caps, String routerId, Hash h) {
        String country = _context.commSystem().getCountry(h);
        if (country == null) country = "unknown";
        boolean isFF = caps.toLowerCase().contains("f");
        boolean isStrict = _context.commSystem().isInStrictCountry();
        boolean isHidden = _context.router().isHidden();
        boolean blockMyCountry = _context.getBooleanProperty(PROP_BLOCK_MY_COUNTRY);
        boolean blockXG = _context.getBooleanPropertyDefaultTrue(PROP_BLOCK_XG);
        boolean blockOf57 = _context.getBooleanPropertyDefaultTrue(PROP_BLOCK_OF57);
        Set<String> blockedCountries = getBlockedCountries();
        String myCountry = _context.getProperty(PROP_IP_COUNTRY);
        boolean isBanned = _context.banlist().isBanlisted(h);

        if ((isStrict || isHidden || blockMyCountry) && myCountry != null && myCountry.equals(country) && !isBanned) {
            if (_log.shouldWarn()) {
                String reason = isHidden ? "Hidden mode active and router is in same country" :
                                isStrict ? "We are in a strict country and so is this router" :
                                "i2np.hideMyCountry=true";
                _log.warn("Dropping RouterInfo [" + getRouterId(routerInfo) + "] -> " + reason);
                _log.warn("Banning " + (caps.isEmpty() ? "" : caps + " ") + (isFF ? "Floodfill" : "Router") +
                          " [" + routerId + "] for duration of session -> Router is in our country");
            }
            if (blockMyCountry) {_context.banlist().banlistRouterForever(h, " <b>➜</b> In our country (banned via config)");}
            else if (isHidden) {_context.banlist().banlistRouterForever(h, " <b>➜</b> In our country (we are in Hidden mode)");}
            else if (isStrict) {_context.banlist().banlistRouterForever(h, " <b>➜</b> In our country (we are in a strict country)");}
            if (blockMyCountry || isHidden || isStrict) {
                _banLogger.logBanForever(h, _context, "In our country");
            }
            return true;
        }
        if (blockedCountries.contains(country) && !isBanned) {
            if (_log.shouldWarn()) {
                _log.warn("Banning [" + routerId + "] -> Blocked country: " + country);
            }
            _context.banlist().banlistRouter(h, " <b>➜</b> Blocked country: " + country, null, null, _context.clock().now() + 8*60*60*1000);
            _banLogger.logBan(h, _context, "Blocked country: " + country, 8*60*60*1000);
            //_context.simpleTimer2().addEvent(new Disconnector(h), 3*1000);
            return true;
        }
        if (blockXG && isRouterBlockXG(routerInfo, h.equals(_context.routerHash()))) {
            if (!_context.banlist().isBanlisted(h)) {
                if (_log.shouldWarn()) {
                    _log.warn("Banning " + (caps.isEmpty() ? "" : caps + " ") + (isFF ? "Floodfill" : "Router") +
                              " [" + routerId + "] for 4h -> XG Router (no transit / botnet)");
                }
                _context.banlist().banlistRouter(h, " <b>➜</b> XG Router (" + (isFF ? "floodfill / " : "") + "no transit)",
                                                 null, null, _context.clock().now() + 4*60*60*1000);
                _banLogger.logBan(h, _context, "XG Router (" + (isFF ? "floodfill / " : "") + "no transit)", 4*60*60*1000);
            }
            return true;
        }
        if (blockOf57 && isRouterBlockOf57(routerInfo, h.equals(_context.routerHash()))) {
            if (!_context.banlist().isBanlisted(h)) {
                String ip = getRouterIP(routerInfo);
                if (_log.shouldWarn()) {
                    _log.warn("Banning \'O\' tier floodfill [" + routerId + "] for 4h -> 0.9.57 (probable botnet participant)");
                }
                String reason = "O tier floodfill / 0.9.57";
                _context.banlist().banlistRouter(h, " <b>➜</b> " + reason, null, null, _context.clock().now() + 4*60*60*1000);
                _banLogger.logBan(h, _context, reason + (ip != null ? " (" + ip + ")" : ""), 4*60*60*1000);
            }
            return true;
        }
        return false;
    }

    /**
     * Determines whether the given router qualifies as an XG router that should be blocked.
     *
     * An XG router is defined as:
     * - Has the G capability (no tunnels)
     * - Has the X (unlimited bandwidth) capability
     *
     * @param routerInfo the RouterInfo to evaluate
     * @param isUs true if the router is the local router
     * @return true if the router is an XG router and should be blocked
     * @since 0.9.67+
     */
    private boolean isRouterBlockXG(RouterInfo routerInfo, boolean isUs) {
        String caps = routerInfo.getCapabilities();
        return !isUs && caps.indexOf(Router.CAPABILITY_NO_TUNNELS) >= 0 &&
               caps.indexOf(Router.CAPABILITY_BW_UNLIMITED) >= 0;
    }

    /**
     * Determines whether the given router qualifies as an Of57 router that should be blocked.
     *
     * An Of57 router is defined as:
     * - Has the O capability (bandwidth tier 256)
     * - Has the f (floodfill) capability
     * - Running version 0.9.57
     *
     * @param routerInfo the RouterInfo to evaluate
     * @param isUs true if the router is the local router
     * @return true if the router is an Of57 router and should be blocked
     * @since 0.9.68
     */
    private boolean isRouterBlockOf57(RouterInfo routerInfo, boolean isUs) {
        String caps = routerInfo.getCapabilities();
        return !isUs && caps.indexOf(Router.CAPABILITY_BW256) >= 0 &&
               caps.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0 &&
               "0.9.57".equals(routerInfo.getVersion());
    }

    /**
     * Gets the IP address from a router's addresses.
     *
     * @param routerInfo the RouterInfo to get IP from
     * @return the IP address string, or null if not found
     * @since 0.9.68
     */
    private String getRouterIP(RouterInfo routerInfo) {
        for (RouterAddress ra : routerInfo.getAddresses()) {
            String ip = ra.getHost();
            if (ip != null) return ip;
        }
        return null;
    }

    /**
     * Checks if the given router info was published in the future and bans it if so.
     *
     * @param routerInfo the RouterInfo to evaluate
     * @param now current timestamp in milliseconds
     * @param caps the capabilities string of the router
     * @param routerId the short ID of the router
     * @param h the Hash of the router
     * @param isUs true if the router is the local router
     * @return a descriptive string if the router is from the future, null otherwise
     * @since 0.9.67+
     */
    private String checkFutureRouterInfo(RouterInfo routerInfo, long now, String caps, String routerId, Hash h, boolean isUs) {
        if (routerInfo.getPublished() > now + 2*Router.CLOCK_FUDGE_FACTOR && !isUs) {
            long age = routerInfo.getPublished() - now;
            if (!_context.banlist().isBanlisted(h) && _log.shouldWarn()) {
                _log.warn("Banning [" + routerId + "] for 4h -> RouterInfo from the future!\n* Published: " + new Date(routerInfo.getPublished()));
                _context.banlist().banlistRouter(h, " <b>➜</b> RouterInfo from the future (" + new Date(routerInfo.getPublished()) + ")", null, null, 4*60*60*1000);
                _banLogger.logBan(h, _context, "RouterInfo from the future", 4*60*60*1000);
            }
            return caps + " Router [" + routerId + "] -> Published " + DataHelper.formatDuration(age) + " in the future";
        }
        return null;
    }

    /**
     * Checks if the given router is running a version older than the minimum allowed.
     *
     * If so, it bans the router until restart.
     *
     * @param routerInfo the RouterInfo to evaluate
     * @param caps the capabilities string of the router
     * @param routerId the short ID of the router
     * @param h the Hash of the router
     * @return a descriptive string if the router is too old, null otherwise
     * @since 0.9.67+
     */
    private String checkRouterVersion(RouterInfo routerInfo, String caps, String routerId, Hash h) {
        if (routerInfo == null) {return null;}
        String v = routerInfo.getVersion();
        String minVersionAllowed = _context.getProperty("router.minVersionAllowed");
        if (minVersionAllowed != null) {
            if (VersionComparator.comp(v, minVersionAllowed) < 0) {
                _context.banlist().banlistRouterForever(h, " <b>➜</b> Router too old (" + v + ")");
                return caps + " Router [" + routerId + "] -> Too old (" + v + ") - banned until restart";
            }
        } else {
            if (VersionComparator.comp(v, minRouterVersion) < 0) {
                _context.banlist().banlistRouterForever(h, " <b>➜</b> Router too old (" + v + ")");
                return caps + " Router [" + routerId + "] -> Too old (" + v + ") - banned until restart";
            }
        }
        return null;
    }

    /**
     * Checks if the given router should be dropped due to being a slow tier (K, L, or M) and outdated.
     *
     * @param routerInfo the RouterInfo to evaluate
     * @param now current timestamp in milliseconds
     * @param existing number of existing routers
     * @param caps the capabilities string of the router
     * @param routerId the short ID of the router
     * @return a descriptive string if the router should be dropped, null otherwise
     * @since 0.9.67
     */
    private String dropSlowRouter(RouterInfo routerInfo, long now, int existing, String caps, String routerId) {
        if (routerInfo == null) {return null;}
        long uptime = _context.router().getUptime();
        boolean isUs = routerInfo.getIdentity().getHash().equals(_context.routerHash());
        String capsStr = routerInfo.getCapabilities() != null ? routerInfo.getCapabilities() : "";
        boolean isSlow = (capsStr.indexOf(Router.CAPABILITY_BW12) >= 0 ||
                          capsStr.indexOf(Router.CAPABILITY_BW32) >= 0 ||
                          capsStr.indexOf(Router.CAPABILITY_BW64) >= 0) && !isUs;

        if (uptime > 10*60*1000 && existing > 500 && isSlow && routerInfo.getPublished() < now - (ROUTER_INFO_EXPIRATION_MIN / 8)) {
            if (_log.shouldInfo()) {
                _log.info("Dropping RouterInfo [" + routerId + "] -> K, L or M tier and published over 1h ago");
            }
            return caps + " Router [" + routerId + "] -> Slow and published over 1h ago";
        }
        if (isSlow && routerInfo.getPublished() < now - (ROUTER_INFO_EXPIRATION_MIN / 4)) {
            if (_log.shouldInfo()) {
                _log.info("Dropping RouterInfo [" + routerId + "] -> K, L or M tier and published over 2h ago");
            }
            return caps + " Router [" + routerId + "] -> Slow and published over 2h ago";
        }
        return null;
    }

    /**
     * Checks if the router has no valid addresses or introducers and has been published too long ago.
     *
     * @param routerInfo the RouterInfo to evaluate
     * @param now current timestamp in milliseconds
     * @param caps the capabilities string of the router
     * @param routerId the short ID of the router
     * @param isUs true if the router is the local router
     * @param dontFail whether to skip failure checks
     * @return a descriptive string if the router should be dropped, null otherwise
     * @since 0.9.67+
     */
    private String checkAddressesAndIntroducers(RouterInfo routerInfo, long now, String caps, String routerId, boolean isUs, boolean dontFail) {
        if (routerInfo == null) {return null;}
        if (!dontFail && !routerInfo.isCurrent(ROUTER_INFO_EXPIRATION_INTRODUCED) && !isUs) {
            if (routerInfo.getAddresses().isEmpty()) {
                if (_log.shouldInfo()) {
                    _log.info("Dropping RouterInfo [" + routerId + "] -> No addresses and published over 54m ago");
                }
                return caps + " Router [" + routerId + "] -> No addresses and published over 54m ago";
            }
            boolean unreachable = routerInfo.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
            if (unreachable || routerInfo.getAddresses().isEmpty()) {
                if (_log.shouldInfo()) {
                    _log.info("Dropping RouterInfo [" + routerId + "] -> Unreachable and published over 54m ago");
                }
                return caps + " Router [" + routerId + "] -> Unreachable and published over 54m ago";
            }
            for (RouterAddress ra : routerInfo.getAddresses()) {
                if (ra.getOption("itag0") != null) {
                    if (_log.shouldInfo()) {
                        _log.info("Dropping RouterInfo [" + routerId + "] -> SSU Introducers and published over 54m ago");
                    }
                    return caps + " Router [" + routerId + "] -> SSU Introducers and published over 54m ago";
                }
            }
        }
        return null;
    }

    /**
     * Checks if the router has expired based on configured expiration time.
     *
     * @param routerInfo the RouterInfo to evaluate
     * @param upLongEnough true if the local router has been up long enough
     * @param adjustedExpiration the computed expiration time in milliseconds
     * @param now current timestamp in milliseconds
     * @param caps the capabilities string of the router
     * @param routerId the short ID of the router
     * @param isUs true if the router is the local router
     * @return a descriptive string if the router should be dropped, null otherwise
     * @since 0.9.67+
     */
    private String checkExpirationBasedDrop(RouterInfo routerInfo, boolean upLongEnough, long adjustedExpiration, long now, String caps, String routerId, boolean isUs) {
        if (routerInfo == null) {return null;}
        String expireRI = _context.getProperty("router.expireRouterInfo");
        if (expireRI != null && !isUs) {
            try {
                long expireRI_ms = Long.parseLong(expireRI)*60*60*1000L;
                if (upLongEnough && routerInfo.getPublished() < now - expireRI_ms) {
                    long age = now - routerInfo.getPublished();
                    return caps + " Router [" + routerId + "] -> Published " + DataHelper.formatDuration(age) + " ago";
                }
            } catch (NumberFormatException ignored) {}
        } else {
            if (upLongEnough && routerInfo.getPublished() < now - adjustedExpiration && !isUs) {
                long age = now - routerInfo.getPublished();
                return caps + " Router [" + routerId + "] -> Published " + DataHelper.formatDuration(age) + " ago";
            }
        }
        return null;
    }

    private String checkShortExpiration(RouterInfo routerInfo, String caps, String routerId, boolean isUs) {
        if (routerInfo == null) {return null;}
        if (!routerInfo.isCurrent(ROUTER_INFO_EXPIRATION_SHORT)) {
            for (RouterAddress ra : routerInfo.getAddresses()) {
                if (routerInfo.getTargetAddresses("NTCP", "NTCP2").isEmpty() && ra.getOption("ihost0") == null && !isUs) {
                    return caps + " Router [" + routerId + "] -> SSU only without Introducers and published over 15m ago";
                } else {
                    boolean isUnreachable = routerInfo.getCapabilities().indexOf(Router.CAPABILITY_REACHABLE) < 0;
                    if (isUnreachable && !isUs) {
                        return caps + " Router [" + routerId + "] -> Unreachable on any transport and published over 15m ago";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Checks if the router has expired within a short time window and lacks valid transports.
     *
     * @param routerInfo the RouterInfo to evaluate
     * @param caps the capabilities string of the router
     * @param routerId the short ID of the router
     * @param isUs true if the router is the local router
     * @return a descriptive string if the router should be dropped, null otherwise
     * @since 0.9.67+
     */
    private String checkStaleRouterInfo(RouterInfo routerInfo, boolean upLongEnough, int existing, String caps, String routerId, boolean isUs) {
        if (routerInfo == null) {return null;}
        if (upLongEnough && !isUs && !routerInfo.isCurrent(computeAdjustedExpiration(existing))) {
            long age = _context.clock().now() - routerInfo.getPublished();
            if (existing >= MIN_REMAINING_ROUTERS) {
                if (_log.shouldInfo()) {
                    _log.info("Dropping RouterInfo [" + routerId + "] -> Published " + DataHelper.formatDuration(age) + " ago");
                }
                return "Published " + DataHelper.formatDuration(age) + " ago";
            }
            if (_log.shouldWarn()) {
                _log.warn("Even though RouterInfo [" + routerId + "] is STALE, we have only " + existing + " peers left - not dropping...");
            }
        }
        return null;
    }

    /**
     * Store the routerInfo.
     *
     * If the store fails due to unsupported crypto, it will banlist
     * the router hash until restart and then throw UnsupportedCrytpoException.
     *
     * @throws IllegalArgumentException if the routerInfo is not valid
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return previous entry or null
     */
    public RouterInfo store(Hash key, RouterInfo routerInfo) throws IllegalArgumentException {
        return store(key, routerInfo, true);
    }

    /**
     * Store the routerInfo.
     *
     * If the store fails due to unsupported crypto, it will banlist
     * the router hash until restart and then throw UnsupportedCrytpoException.
     *
     * @throws IllegalArgumentException if the routerInfo is not valid
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return previous entry or null
     */
    RouterInfo store(Hash key, RouterInfo routerInfo, boolean persist) throws IllegalArgumentException {
        if (!_initialized || key == null || routerInfo == null) {return null;}
        if (isClientDb()) {throw new IllegalArgumentException("RI store to client DB");}
        boolean blockXG = _context.getBooleanPropertyDefaultTrue(PROP_BLOCK_XG);

        RouterInfo rv;
        try {
            rv = (RouterInfo)_ds.get(key, persist);
            if (rv != null && rv.getPublished() >= routerInfo.getPublished()) {
                if (_log.shouldDebug()) {
                    _log.debug("Not storing RouterInfo [" + key.toBase64().substring(0,6) + "] -> Local copy is newer");
                }
                return rv; // quick check without calling validate()
            }
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException("Attempt to replace LeaseSet with " + routerInfo);
        }

        // spoof / hash collision detection
        // todo allow non-exp to overwrite exp
        if (rv != null && !routerInfo.getIdentity().equals(rv.getIdentity())) {
            throw new IllegalArgumentException("RouterInfo Hash collision");
        }

        String err = validate(key, routerInfo);
        if (err != null) {
            throw new IllegalArgumentException("Invalid NetDbStore attempt - " + err);
        }

        boolean isUs = _context.routerHash().equals(key);
        String caps = routerInfo.getCapabilities() != null ? routerInfo.getCapabilities() : "";
        boolean isFF = caps.toLowerCase().contains("f");
        if (blockXG && isRouterBlockXG(routerInfo, isUs)) {
            if (_log.shouldInfo()) {
                _log.info("Dropping RouterInfo [" + key.toBase64().substring(0,6) + "] on store -> XG Router" +
                          (isFF ? " (floodfill)" : ""));
            }
            if (!_context.banlist().isBanlisted(key)) {
                _context.banlist().banlistRouter(key, " <b>➜</b> XG Router (" + (isFF ? "floodfill / " : "") + "no transit)",
                                                 null, null, _context.clock().now() + 4*60*60*1000);
                _banLogger.logBan(key, _context, "XG Router (" + (isFF ? "floodfill / " : "") + "no transit)", 4*60*60*1000);
            }
            return rv;
        }

        _context.peerManager().setCapabilities(key, routerInfo.getCapabilities());
        _ds.put(key, routerInfo, persist);
        if (rv == null) {_kb.add(key);}
        return rv;
    }

    /**
     *  If the validate fails, call this
     *  to determine if it was because of unsupported crypto.
     *
     *  If so, this will banlist-forever the router hash or permanently negative cache the dest hash,
     *  and then throw the exception. Otherwise it does nothing.
     *
     *  @throws UnsupportedCryptoException if that's why it failed.
     *  @since 0.9.16
     */
    private void processStoreFailure(Hash h, DatabaseEntry entry) throws UnsupportedCryptoException {
        if (h != null && entry.getHash().equals(h)) {
            int etype = entry.getType();
            if (DatabaseEntry.isLeaseSet(etype)) {
                LeaseSet ls = (LeaseSet) entry;
                Destination d = ls.getDestination();
                // will be null for encrypted LS
                if (d != null) {
                    Certificate c = d.getCertificate();
                    if (c.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
                        try {
                            KeyCertificate kc = c.toKeyCertificate();
                            SigType type = kc.getSigType();
                            if (type == null || !type.isAvailable() || type.getBaseAlgorithm() == SigAlgo.RSA) {
                                failPermanently(d);
                                String stype = (type != null) ? type.toString() : Integer.toString(kc.getSigTypeCode());
                                if (_log.shouldWarn()) {
                                    _log.warn("Unsupported Signature type " + stype + " for destination " + h);
                                }
                                throw new UnsupportedCryptoException("Sig type " + stype);
                            }
                        } catch (DataFormatException dfe) {}
                    }
                }
            } else if (etype == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                RouterInfo ri = (RouterInfo) entry;
                RouterIdentity id = ri.getIdentity();
                Certificate c = id.getCertificate();
                if (c.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
                    try {
                        KeyCertificate kc = c.toKeyCertificate();
                        SigType type = kc.getSigType();
                        if (type == null || !type.isAvailable()) {
                            String stype = (type != null) ? type.toString() : Integer.toString(kc.getSigTypeCode());
                            _context.banlist().banlistRouterForever(h, " <b>➜</b> " + "Unsupported Signature type " + stype);
                            _banLogger.logBanForever(h, _context, "Unsupported Signature type " + stype);
                            if (_log.shouldWarn()) {
                                _log.warn("Unsupported Signature type " + stype + " for [" +
                                          h.toBase64().substring(0,6) + "] - banned until restart");
                            }
                            throw new UnsupportedCryptoException("Sig type " + stype);
                        }
                    } catch (DataFormatException dfe) {}
                }
            }
        }
        if (_log.shouldWarn()) {
            _log.warn("RouterInfo verification failure (Unknown cause)\n" + entry);
        }
    }

    /**
     *  Final remove for a leaseset.
     *  For a router info, will look up in the network before dropping.
     */
    public void fail(Hash dbEntry) {
        if (!_initialized) {return;}
        DatabaseEntry o = _ds.get(dbEntry);
        if (o == null) {
            if (_kb != null) {_kb.remove(dbEntry);} // if we don't know the key, let's make sure it isn't a now-dead peer
            _context.peerManager().removeCapabilities(dbEntry);
            return;
        }

        if (o.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            lookupBeforeDropping(dbEntry, (RouterInfo) o);
            return;
        }

        /*
         * We always drop failed leaseSets (timed out), regardless of how many routers we have.
         * This is called on a lease if it has expired *or* its tunnels are failing and we want
         * to see if there are any updates.
         */
        if (_log.shouldInfo()) {
            _log.info("Dropping LeaseSet [" + dbEntry.toBase32().substring(0,8) + "] -> Lookup / tunnel failure");
        }

       if (knownLeaseSetsCount.get() <= 0 && _log.shouldInfo()) {
           _log.warn("Attempted to decrement LeaseSet count when already at " + knownLeaseSetsCount.get());
        }
        knownLeaseSetsCount.decrementAndGet();

        if (!isClientDb()) {_ds.remove(dbEntry, false);}
        /* If this happens, it's because we're a TransientDataStore instead,
         * so just call remove without the persist option.
         */
        else {_ds.remove(dbEntry);}
    }

    /** Don't use directly - see F.N.D.F. override */
    protected void lookupBeforeDropping(Hash peer, RouterInfo info) {dropAfterLookupFailed(peer);} // bah, humbug.

    /**
     *  Final remove for a router info.
     *  Do NOT use for leasesets.
     */
    boolean dropAfterLookupFailed(Hash peer) {
        if (isClientDb()) {return false;}
        boolean loggedFailure = false;
        int count = 0;
        if (count == 0) {
            _context.peerManager().removeCapabilities(peer);
            _negativeCache.cache(peer);
            _kb.remove(peer);
            _ds.remove(peer);
            if (_log.shouldInfo()) {
                if (!loggedFailure) {
                    _log.info("Dropping RouterInfo [" + peer.toBase64().substring(0,6) + "] -> Lookup failure");
                    loggedFailure = true;
                }
            }
            count++;
        }
        return loggedFailure;
    }

    public void unpublish(LeaseSet localLeaseSet) {
        if (!_initialized) return;
        Hash h = localLeaseSet.getHash();
        DatabaseEntry data = _ds.remove(h);

        if (data == null) {
            knownLeaseSetsCount.decrementAndGet();
            if (_log.shouldWarn()) {
                _log.warn("Unpublishing UNKNOWN LOCAL LeaseSet [" + h.toBase32().substring(0,8) + "]");
            }
            if (_log.shouldInfo() && knownLeaseSetsCount.get() <= 0) {
                _log.warn("Attempted to decrement LeaseSet count when already at " + knownLeaseSetsCount.get());
            }
        } else {
            if (_log.shouldInfo()) {
                _log.info("Unpublishing LOCAL LeaseSet [" + h.toBase32().substring(0,8) + "]");
            }
        }
        // now update it if we can to remove any leases
    }

    /**
     * Begin a kademlia style search for the key specified, which can take up to timeoutMs and
     * will fire the appropriate jobs on success or timeout (or if the kademlia search completes
     * without any match)
     *
     * Unused - called only by FNDF.searchFull() from FloodSearchJob which is overridden - don't use this.
     *
     * @throws UnsupportedOperationException always
     */
    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease) {
        throw new UnsupportedOperationException();
    }

    /**
     * Unused - see FNDF
     * @throws UnsupportedOperationException always
     * @since 0.9.10
     */
    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease,
                     Hash fromLocalDest) {
        throw new UnsupportedOperationException();
    }

    /** public for NetDbRenderer in routerconsole */
    @Override
    public Set<LeaseSet> getLeases() {
        if (!_initialized) {return null;}
        Set<LeaseSet> leases = new ConcurrentHashSet<>();
        for (DatabaseEntry o : getDataStore().getEntries()) {
            if (o.isLeaseSet()) {leases.add((LeaseSet)o);}
        }
        return leases;
    }

    /** public for NetDbRenderer in routerconsole */
    /* @since 0.9.64+ */
    @Override
    public Set<LeaseSet> getClientLeases() {
        if (!_initialized) {return null;}
        Set<LeaseSet> leases = new ConcurrentHashSet<>();
        for (DatabaseEntry o : getDataStore().getEntries()) {
            if (o.isLeaseSet()) {
                Hash key = o.getHash();
                boolean isLocal = !o.getReceivedAsPublished() && !o.getReceivedAsReply();
                if (isLocal) {leases.add((LeaseSet)o);}
            }
        }
        return leases;
    }

    /** public for NetDbRenderer in routerconsole */
    /* @since 0.9.64+ */
    @Override
    public Set<LeaseSet> getPublishedLeases() {
        if (!_initialized) {return null;}
        Set<LeaseSet> leases = new ConcurrentHashSet<>();
        for (DatabaseEntry o : getDataStore().getEntries()) {
            if (o.isLeaseSet()) {
                Hash key = o.getHash();
                boolean isLocal = !o.getReceivedAsPublished() && !o.getReceivedAsReply();
                boolean published = _context.clientManager().shouldPublishLeaseSet(key);
                if (published && isLocal) {leases.add((LeaseSet)o);} // include i2cp/sam clients like snark etc
            }
        }
        return leases;
    }

    /** public for NetDbRenderer in routerconsole */
    /* @since 0.9.64+ */
    @Override
    public Set<LeaseSet> getUnpublishedLeases() {
        if (!_initialized) {return null;}
        Set<LeaseSet> leases = new ConcurrentHashSet<>();
        for (DatabaseEntry o : getDataStore().getEntries()) {
            if (o.isLeaseSet()) {
                Hash key = o.getHash();
                boolean published = _context.clientManager().shouldPublishLeaseSet(key);
                boolean isLocal = !o.getReceivedAsPublished() && !o.getReceivedAsReply();
                if (!published && isLocal) {leases.add((LeaseSet)o);}
            }
        }
        return leases;
    }

    /**
     * Public for NetDbRenderer in routerconsole
     * @since 0.9.64+
     */
    @Override
    public Set<LeaseSet> getFloodfillLeases() {
        if (!_initialized) {return null;}
        Set<LeaseSet> leases = new ConcurrentHashSet<>();
        for (DatabaseEntry o : getDataStore().getEntries()) {
            if (o.isLeaseSet() && !isClientDb()) {leases.add((LeaseSet)o);}
        }
        return leases;
    }

    /**
     *  Public for NetDbRenderer in routerconsole
     *  @return empty set if this is a client DB
     */
    @Override
    public Set<RouterInfo> getRouters() {
        if (isClientDb()) {return Collections.emptySet();}
        if (!_initialized) {return null;}
        Set<RouterInfo> routers = new ConcurrentHashSet<>();
        for (DatabaseEntry o : getDataStore().getEntries()) {
            if (o.isRouterInfo()) {routers.add((RouterInfo)o);}
        }
        return routers;
    }

    /** smallest allowed period */
    private static final int MIN_PER_PEER_TIMEOUT = 2500;
    /**
     *  We want FNDF.PUBLISH_TIMEOUT and RepublishLeaseSetJob.REPUBLISH_LEASESET_TIMEOUT
     *  to be greater than MAX_PER_PEER_TIMEOUT * TIMEOUT_MULTIPLIER by a factor of at least
     *  3 or 4, to allow at least that many peers to be attempted for a store.
     */
    private static final int MAX_PER_PEER_TIMEOUT = 15*1000;
    private static final int TIMEOUT_MULTIPLIER = 3;

    /**
     * @return the timeout for a peer, based on the profile data, or the default timeout
     */
    public int getPeerTimeout(Hash peer) {
        if (peer == null) {throw new IllegalArgumentException("Peer cannot be null");}
        PeerProfile prof = _context.profileOrganizer().getProfile(peer);
        if (prof == null) {return TIMEOUT_MULTIPLIER * MAX_PER_PEER_TIMEOUT;}
        double responseTime = prof.getDbResponseTime() != null &&  prof.getDbResponseTime().getRate(RateConstants.ONE_HOUR) != null
                              ? prof.getDbResponseTime().getRate(RateConstants.ONE_HOUR).getAvgOrLifetimeAvg() : 0;
        if (responseTime <= 0) {responseTime = MAX_PER_PEER_TIMEOUT;}
        else if (responseTime > MAX_PER_PEER_TIMEOUT) {responseTime = MAX_PER_PEER_TIMEOUT;}
        else if (responseTime < MIN_PER_PEER_TIMEOUT) {responseTime = MIN_PER_PEER_TIMEOUT;}
        return TIMEOUT_MULTIPLIER * (int)responseTime;
    }

    /**
     * See implementation in FNDF
     *
     * @param key the DatabaseEntry hash
     * @param onSuccess may be null, always called if we are ff and ds is an RI
     * @param onFailure may be null, ignored if we are ff and ds is an RI
     * @param sendTimeout ignored if we are ff and ds is an RI
     * @param toIgnore may be null, if non-null, all attempted and skipped targets will be added as of 0.9.53,
     *                 unused if we are ff and ds is an RI
     */
    abstract void sendStore(Hash key, DatabaseEntry ds, Job onSuccess, Job onFailure, long sendTimeout, Set<Hash> toIgnore);

    /**
     *  Increment in the negative lookup cache
     *
     *  @param key for Destinations or RouterIdentities
     *  @since 0.9.4 moved from FNDF to KNDF in 0.9.16
     */
    void lookupFailed(Hash key) {_negativeCache.lookupFailed(key);}

    /**
     *  Is the key in the negative lookup cache?
     *
     *  @param key for Destinations or RouterIdentities
     *  @since 0.9.4 moved from FNDF to KNDF in 0.9.16
     */
    boolean isNegativeCached(Hash key) {
        boolean rv = _negativeCache.isCached(key);
        if (rv) {_context.statManager().addRateData("netDb.negativeCache", 1);}
        return rv;
    }

    /**
     *  Negative cache until restart
     *  @since 0.9.16
     */
    void failPermanently(Destination dest) {_negativeCache.failPermanently(dest);}

    /**
     *  Is it permanently negative cached?
     *
     *  @param key only for Destinations; for RouterIdentities, see Banlist
     *  @since 0.9.16
     */
    public boolean isNegativeCachedForever(Hash key) {return key != null && _negativeCache.getBadDest(key) != null;}

    /**
     * Debug info, HTML formatted
     * @since 0.9.10
     */
    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        if (_kb == null) {return;}
        out.write(_kb.toString().replace("\n", "<br>\n"));
    }

    /**
     * @since 0.9.61
     */
    @Override
    public String toString() {
        if (!isClientDb()) {return "MainNetDb";}
        return "ClientNetDb [" + _dbid.toBase32().substring(0,8) + "]";
    }

    /** @since 0.9.65+ */
    private Set<String> getBlockedCountries() {
        String blockCountries = _context.getProperty(PROP_BLOCK_COUNTRIES, DEFAULT_BLOCK_COUNTRIES);
        if (blockCountries.isEmpty()) {return Collections.emptySet();}
        return Arrays.stream(blockCountries.trim().toLowerCase().split("\\s*,\\s*"))
                     .filter(s -> !s.isEmpty())
                     .collect(Collectors.toSet());
    }

    /**
     * Get the number of RouterInfo files stored on disk, if using PersistentDataStore.
     * @return number of RIs on disk, or 0 if not using persistent storage
     * @since 0.9.68+
     */
    public int getStoredRouterInfoCount() {
        DataStore ds = getDataStore();
        if (ds instanceof PersistentDataStore) {
            return ((PersistentDataStore) ds).countStoredRIs();
        }
        return 0;
    }

    /** @since 0.9.52 */
    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) {this.h = h;}
        public void timeReached() {_context.commSystem().forceDisconnect(h);}
    }

}
