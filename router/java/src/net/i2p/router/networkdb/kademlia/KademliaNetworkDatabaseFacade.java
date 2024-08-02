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
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.i2p.crypto.SigAlgo;
import net.i2p.crypto.SigType;
import net.i2p.data.BlindData;
import net.i2p.data.Certificate;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
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
import net.i2p.router.Job;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.networkdb.PublishLocalRouterInfoJob;
import net.i2p.router.networkdb.reseed.ReseedChecker;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Kademlia based version of the network database.
 * Never instantiated directly; see FloodfillNetworkDatabaseFacade.
 */
public abstract class KademliaNetworkDatabaseFacade extends NetworkDatabaseFacade {
    protected final Log _log;
    private KBucketSet<Hash> _kb; // peer hashes sorted into kbuckets, but within kbuckets, unsorted
    private DataStore _ds; // hash to DataStructure mapping, persisted when necessary
    /** where the data store is pushing the data */
    private String _dbDir;
    // set of Hash objects that we should search on (to fill up a bucket, not to get data)
    private final Set<Hash> _exploreKeys;
    private boolean _initialized;
    /** Clock independent time of when we started up */
    private long _started;
    private StartExplorersJob _exploreJob;
    /** when was the last time an exploration found something new? */
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
    static final String PROP_MIN_ROUTER_VERSION = "router.minVersionAllowed";

    /**
     * Map of Hash to RepublishLeaseSetJob for leases we'realready managing.
     * This is added to when we create a new RepublishLeaseSetJob, and the values are
     * removed when the job decides to stop running.
     *
     */
    private final Map<Hash, RepublishLeaseSetJob> _publishingLeaseSets;

    /**
     * Hash of the key currently being searched for, pointing the SearchJob that
     * is currently operating.  Subsequent requests for that same key are simply
     * added on to the list of jobs fired on success/failure
     *
     */
    private final Map<Hash, SearchJob> _activeRequests;

    /**
     * The search for the given key is no longer active
     *
     */
    void searchComplete(Hash key) {
        if (_log.shouldDebug())
            _log.debug("Search for key [" + key.toBase64().substring(0,6) + "] finished");
        synchronized (_activeRequests) {
            _activeRequests.remove(key);
        }
    }

    /**
     * For 10 minutes after startup, don't fail db entries so that if we were
     * offline for a while, we'll have a chance of finding some live peers with the
     * previous references
     */
//    protected final static long DONT_FAIL_PERIOD = 10*60*1000;
    public static long DONT_FAIL_PERIOD = 15*60*1000;

    /** Don't probe or broadcast data, just respond and search when explicitly needed */
    private static final boolean QUIET = false;

    public final static String PROP_DB_DIR = "router.networkDatabase.dbDir";
    public final static String DEFAULT_DB_DIR = "netDb";

    /** Reseed if below this.
     *  @since 0.9.4
     */
    static final int MIN_RESEED = ReseedChecker.MINIMUM;

    /** If we have less than this many routers left, don't drop any more,
     *  even if they're failing or doing bad stuff.
     *  As of 0.9.4, we make this LOWER than the min for reseeding, so
     *  a reseed will be forced if necessary.
     */
//    protected final static int MIN_REMAINING_ROUTERS = MIN_RESEED - 10;
    protected final static int MIN_REMAINING_ROUTERS = MIN_RESEED - 5;

    /**
     * Limits for accepting a dbStore of a router (unless we don't
     * know anyone or just started up) -- see validate() below
     */
//    private final static long ROUTER_INFO_EXPIRATION = 27*60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION = 24*60*60*1000l;
//    private final static long ROUTER_INFO_EXPIRATION_MIN = 90*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_MIN = 8*60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_SHORT = 15*60*1000l;
//    private final static long ROUTER_INFO_EXPIRATION_FLOODFILL = 60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_FLOODFILL = 4*60*60*1000l;
    private final static long ROUTER_INFO_EXPIRATION_INTRODUCED = 54*60*1000l;
    static final String PROP_ROUTER_INFO_EXPIRATION_ADJUSTED = "router.expireRouterInfo";

    static final String PROP_VALIDATE_ROUTERS_AFTER = "router.validateRoutersAfter";

//    private final static long EXPLORE_JOB_DELAY = 10*60*1000l;
    private final static long EXPLORE_JOB_DELAY = 5*60*1000l;


    /**
     * Don't let leaseSets go too far into the future
     */
    private static final long MAX_LEASE_FUTURE = 15*60*1000;
    private static final long MAX_META_LEASE_FUTURE = 65535*1000;


    /** This needs to be long enough to give us time to start up,
        but less than 20m (when we start accepting tunnels and could be a IBGW)
        Actually no, we need this soon if we are a new router or
        other routers have forgotten about us, else
        we can't build IB exploratory tunnels.
        Unused.
     */
//    protected final static long PUBLISH_JOB_DELAY = 5*60*1000l;
    protected final static long PUBLISH_JOB_DELAY = 4*60*1000l;

    /** Maximum number of peers to place in the queue to explore
     */
//    static final int MAX_EXPLORE_QUEUE = 128;
    static final int MAX_EXPLORE_QUEUE = SystemVersion.isSlow() ? 128 : 256;
    static final String PROP_EXPLORE_QUEUE = "router.exploreQueue";

    /**
     *  kad K
     *  Was 500 in old implementation but that was with B ~= -8!
     */
//    private static final int BUCKET_SIZE = 24;
    private static final int BUCKET_SIZE = 48;
    static final String PROP_BUCKET_SIZE = "router.exploreBucketSize";
//    private static final int KAD_B = 4;
    private static final int KAD_B = 5;
    static final String PROP_KAD_B = "router.exploreKadB";

    private static final long[] RATES = { 60*1000, 60*60*1000, 24*60*60*1000 };

    public KademliaNetworkDatabaseFacade(RouterContext context, Hash dbid) {
        _context = context;
        _dbid = dbid;
        _log = _context.logManager().getLog(getClass());
        _networkID = context.router().getNetworkID();
        _publishingLeaseSets = new HashMap<Hash, RepublishLeaseSetJob>(8);
        _activeRequests = new HashMap<Hash, SearchJob>(8);
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
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Created KademliaNetworkDatabaseFacade for DbId: " + _dbid);
        context.statManager().createRateStat("netDb.lookupDeferred", "Deferred NetDb lookups", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.exploreKeySet", "NetDb keys queued for exploration", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.negativeCache", "Aborted NetDb lookups (already cached)", "NetworkDatabase", RATES);
        // following are for StoreJob
        context.statManager().createRateStat("netDb.storeRouterInfoSent", "Sent RouterInfo store messages", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.storeLeaseSetSent", "Sent LeaseSet store messages", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.storePeers", "Peers each NetDb must be sent to before success", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.storeFailedPeers", "Peers each NetDb must be sent to before failing completely", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.ackTime", "Time peer takes to ACK a DbStore", "NetworkDatabase", RATES);
        context.statManager().createRateStat("netDb.replyTimeout", "Timeout expiry after a NetDb send (peer fails to reply in time)", "NetworkDatabase", RATES);
        // following is for RepublishLeaseSetJob
        context.statManager().createRateStat("netDb.republishLeaseSetCount", "How often we republish a LeaseSet", "NetworkDatabase", RATES);
        // following is for DatabaseStoreMessage
        context.statManager().createRateStat("netDb.DSMAllZeros", "Messages stored in NetDb with zero key", "NetworkDatabase", RATES);
        // following is for HandleDatabaseLookupMessageJob
        context.statManager().createRateStat("netDb.DLMAllZeros", "Message lookups in NetDb with zero key ", "NetworkDatabase", RATES);
    }

    @Override
    public boolean isInitialized() {
        return _initialized && _ds != null && _ds.isInitialized();
    }

    /**
     *  Only for main DB
     */
    protected PeerSelector createPeerSelector() {
       if (isClientDb())
           throw new IllegalStateException();
       return new FloodfillPeerSelector(_context);
    }

    /**
     *  @return the main DB's peer selector. Client DBs do not have their own.
     */
    public PeerSelector getPeerSelector() { return _peerSelector; }

    /** @since 0.9 */
    @Override
    public ReseedChecker reseedChecker() {
        if (isClientDb())
            return null;
        return _reseedChecker;
    }

    /**
     * We still always use a single blind cache in the main Db(for now),
     * see issue #421 on i2pgit.org/i2p-hackers/i2p.i2p for details.
     * This checks if we're the main DB already and returns our blind
     * cache if we are. If not, it looks up the main Db and gets it.
     *
     * @return non-null
     * @since 0.9.61
     */
    protected BlindCache blindCache() {
        if (!isClientDb())
            return _blindCache;
        return ((FloodfillNetworkDatabaseFacade) _context.netDb()).blindCache();
    }

    /**
     *  @return the main DB's KBucketSet. Client DBs do not have their own.
     */
    KBucketSet<Hash> getKBuckets() { return _kb; }
    DataStore getDataStore() { return _ds; }

    long getLastExploreNewDate() { return _lastExploreNew; }
    void setLastExploreNewDate(long when) {
        _lastExploreNew = when;
        if (_exploreJob != null)
            _exploreJob.updateExploreSchedule();
    }

    /** @return unmodifiable set */
    public Set<Hash> getExploreKeys() {
        if (!_initialized || isClientDb())
            return Collections.emptySet();
        return Collections.unmodifiableSet(_exploreKeys);
    }

    public void removeFromExploreKeys(Collection<Hash> toRemove) {
        if (!_initialized || isClientDb())
            return;
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
            if (_log.shouldInfo())
                _log.info("Datastore not initialized, cannot queue keys for exploration");
            return;
        }
        // TODO: make sure exploreQueue isn't null before assigning
        //for (Iterator<Hash> iter = keys.iterator(); iter.hasNext() && _exploreKeys.size() < Integer.valueOf(exploreQueue);) {
        for (Iterator<Hash> iter = keys.iterator(); iter.hasNext() && _exploreKeys.size() < MAX_EXPLORE_QUEUE;) {
            _exploreKeys.add(iter.next());
         }
        _context.statManager().addRateData("netDb.exploreKeySet", _exploreKeys.size());
    }

    /**
     *  Cannot be restarted.
     */
    public synchronized void shutdown() {
        if (_log.shouldWarn())
            _log.warn("NetDb shutdown: " + this);
        _initialized = false;
        if (!_context.commSystem().isDummy() && !isClientDb() &&
            _context.router().getUptime() > ROUTER_INFO_EXPIRATION_FLOODFILL + 10*60*1000 + 60*1000) {
            // expire inline before saving RIs in _ds.stop()
            Job erj = new ExpireRoutersJob(_context, this);
            erj.runJob();
        }
        _context.jobQueue().removeJob(_elj);
        if (_erj != null)
            _context.jobQueue().removeJob(_erj);
        if (_kb != null && !isClientDb())
            _kb.clear();
        if (_ds != null)
            _ds.stop();
        if (_exploreKeys != null)
            _exploreKeys.clear();
        if (_negativeCache != null)
            _negativeCache.stop();
        if (!isClientDb())
            blindCache().shutdown();
    }

    /**
     *  Unsupported, do not use
     *
     *  @throws UnsupportedOperationException always
     *  @deprecated
     */
    @Deprecated
    public synchronized void restart() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void rescan() {
        if (isInitialized())
           _ds.rescan();
    }

    /**
     * For the main DB only.
     * Sub DBs are not persisted and must not access this directory.
     *
     * @return null before startup() is called; non-null thereafter, even for subdbs.
     */
    String getDbDir() { return _dbDir; }

    /**
     * Check if the database is a client DB.
     *
     * @return  true if the database is a client DB, false otherwise
     * @since 0.9.61
     */
    public boolean isClientDb() {
        // This is a null check in disguise, don't use .equals() here.
        // FNDS.MAIN_DBID is always null. and if _dbid is also null it is not a client Db
        if (_dbid == FloodfillNetworkDatabaseSegmentor.MAIN_DBID)
            return false;
        return true;
    }

    public void startup() {
        if (_log.shouldInfo()) {
            _log.info("Starting up the Kademlia Network Database...");
        }
        RouterInfo ri = _context.router().getRouterInfo();
        String dbDir = _context.getProperty(PROP_DB_DIR, DEFAULT_DB_DIR);
        if (isClientDb())
            _kb = ((FloodfillNetworkDatabaseFacade) _context.netDb()).getKBuckets();
        else
            _kb = new KBucketSet<Hash>(_context, ri.getIdentity().getHash(),
                                       BUCKET_SIZE, KAD_B, new RejectTrimmer<Hash>());
        _log.info("BucketSize: " + BUCKET_SIZE + "; B Value: " + KAD_B);
        try {
            if (!isClientDb()) {
                _ds = new PersistentDataStore(_context, dbDir, this);
            } else {
                _ds = new TransientDataStore(_context);
            }
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to initialize NetDb storage", ioe);
        }
        _dbDir = dbDir;
        _negativeCache = new NegativeLookupCache(_context);
        if (!isClientDb())
            blindCache().startup();

        createHandlers();

        _initialized = true;
        _started = System.currentTimeMillis();

        // expire old leases
        long now = _context.clock().now();
        _elj.getTiming().setStartAfter(now + 11*60*1000);
        _context.jobQueue().addJob(_elj);

        //// expire some routers
        // Don't run until after RefreshRoutersJob has run, and after validate() will return invalid for old routers.
        if (!isClientDb() && !_context.commSystem().isDummy()) {
            boolean isFF = _context.getBooleanProperty(FloodfillMonitorJob.PROP_FLOODFILL_PARTICIPANT);
            long down = _context.router().getEstimatedDowntime();
            long delay = (down == 0 || (!isFF && down > ROUTER_INFO_EXPIRATION_FLOODFILL/2) || (isFF && down > ROUTER_INFO_EXPIRATION_FLOODFILL*8)) ?
                         ROUTER_INFO_EXPIRATION_FLOODFILL + ROUTER_INFO_EXPIRATION_FLOODFILL/6 :
                         ROUTER_INFO_EXPIRATION_FLOODFILL/6;
            _erj.getTiming().setStartAfter(now + delay);
            _context.jobQueue().addJob(_erj);
        }

        if (!QUIET) {
            if (!isClientDb()) {
            // fill the search queue with random keys in buckets that are too small
            // Disabled since KBucketImpl.generateRandomKey() is b0rked,
            // and anyway, we want to search for a completely random key,
            // not a random key for a particular kbucket.
            // _context.jobQueue().addJob(new ExploreKeySelectorJob(_context, this));

            if (_exploreJob == null)
                _exploreJob = new StartExplorersJob(_context, this);
                // fire off a group of searches from the explore pool
                // Don't start it right away, so we don't send searches for random keys
                // out our 0-hop exploratory tunnels (generating direct connections to
                // one or more floodfill peers within seconds of startup).
                // We're trying to minimize the ff connections to lessen the load on the
                // floodfills, and in any case let's try to build some real expl. tunnels first.
                // No rush, it only runs every 30m.

            _exploreJob.getTiming().setStartAfter(now + EXPLORE_JOB_DELAY);
            _context.jobQueue().addJob(_exploreJob);
            }
        } else {
            _log.warn("Operating in QUIET MODE - not exploring or pushing data proactively, simply reactively " +
                      "\n* This should NOT be used in production!");
        }
        if (!isClientDb()) {
            // periodically update and resign the router's 'published date', which basically
            // serves as a version
            Job plrij = new PublishLocalRouterInfoJob(_context);
            // do not delay this, as this creates the RI too, and we need a good local routerinfo right away
            //plrij.getTiming().setStartAfter(_context.clock().now() + PUBLISH_JOB_DELAY);
            _context.jobQueue().addJob(plrij);
        }
    }

/**
    public void startup() {
        synchronized (this) {
            _log.info("Starting up the Kademlia Network Database...");
            RouterInfo ri = _context.router().getRouterInfo();
            _kb = new KBucketSet<Hash>(_context, ri.getIdentity().getHash(),
                                       BUCKET_SIZE, KAD_B, new RejectTrimmer<Hash>());
            _log.info("BucketSize: " + BUCKET_SIZE + "; B Value: " + KAD_B);
            _dbDir = getDbDir();
            try {
                if (!isClientDb()) {
                    _ds = new PersistentDataStore(_context, _dbDir, this);
                } else {
                    _ds = new TransientDataStore(_context);
                }
            } catch (IOException ioe) {
                throw new RuntimeException("Unable to initialize NetDb storage", ioe);
            }
            _negativeCache = new NegativeLookupCache(_context);
            _blindCache.startup();

            createHandlers();

            _initialized = true;
            _started = System.currentTimeMillis();

            // expire old leases
            Job elj = new ExpireLeasesJob(_context, this);
            long now = _context.clock().now();
            elj.getTiming().setStartAfter(now + ROUTER_INFO_EXPIRATION_FLOODFILL/6);
            _context.jobQueue().addJob(elj);

            // expire some routers
            if (!_context.commSystem().isDummy()) {
                Job erj = new ExpireRoutersJob(_context, this);
                boolean isFF = _context.getBooleanProperty(FloodfillMonitorJob.PROP_FLOODFILL_PARTICIPANT);
                long down = _context.router().getEstimatedDowntime();
                long delay = (down == 0 || (!isFF && down > ROUTER_INFO_EXPIRATION_FLOODFILL/2) || (isFF && down > ROUTER_INFO_EXPIRATION_FLOODFILL*24)) ?
                             ROUTER_INFO_EXPIRATION_FLOODFILL + ROUTER_INFO_EXPIRATION_FLOODFILL/6 :
                             ROUTER_INFO_EXPIRATION_FLOODFILL/6;
                erj.getTiming().setStartAfter(now + delay);
                _context.jobQueue().addJob(erj);
            }

            if (!QUIET) {
                // fill the search queue with random keys in buckets that are too small
                // Disabled since KBucketImpl.generateRandomKey() is b0rked,
                // and anyway, we want to search for a completely random key,
                // not a random key for a particular kbucket.
                // _context.jobQueue().addJob(new ExploreKeySelectorJob(_context, this));

                if (_exploreJob == null)
                    _exploreJob = new StartExplorersJob(_context, this);
                    // fire off a group of searches from the explore pool
                    // Don't start it right away, so we don't send searches for random keys
                    // out our 0-hop exploratory tunnels (generating direct connections to
                    // one or more floodfill peers within seconds of startup).
                    // We're trying to minimize the ff connections to lessen the load on the
                    // floodfills, and in any case let's try to build some real expl. tunnels first.
                    // No rush, it only runs every 30m.

                _exploreJob.getTiming().setStartAfter(now + EXPLORE_JOB_DELAY);
                _context.jobQueue().addJob(_exploreJob);
            } else {
                _log.warn("Operating in QUIET MODE - not exploring or pushing data proactively, simply reactively " +
                          "\n* This should NOT be used in production!");
            }
            if (_dbid == null || _dbid.equals(FloodfillNetworkDatabaseSegmentor.MAIN_DBID) || _dbid.isEmpty()) {
                // periodically update and resign the router's 'published date', which basically
                // serves as a version
                Job plrij = new PublishLocalRouterInfoJob(_context);
                _context.jobQueue().addJob(plrij);
            }
        }
    }
**/
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
        if (isClientDb()) {
//            _log.warn("Subdb", new Exception("I did it"));
            return Collections.emptySet();
        }
        if (!_initialized) {return Collections.emptySet();}
        return new HashSet<Hash>(_peerSelector.selectNearest(key, maxNumRouters, peersToIgnore, _kb));
    }

/*****
    private Set<RouterInfo> getRouters(Collection hashes) {
        if (!_initialized) return null;
        Set rv = new HashSet(hashes.size());
        for (Iterator iter = hashes.iterator(); iter.hasNext(); ) {
            Hash rhash = (Hash)iter.next();
            DataStructure ds = _ds.get(rhash);
            if (ds == null) {
                if (_log.shouldInfo())
                    _log.info("Selected hash " + rhash.toBase64() + " is not stored locally");
            } else if ( !(ds instanceof RouterInfo) ) {
                // leaseSet
            } else {
                rv.add(ds);
            }
        }
        return rv;
    }
*****/

    /**
     *  Get the hashes for all known routers
     *
     *  @return empty set if this is a client DB
     */
/**
    public Set<Hash> getAllRouters() {
        if (isClientDb()) {
            _log.warn("Subdb", new Exception("I did it"));
            return Collections.emptySet();
        }
        if (!_initialized) return Collections.emptySet();
        Set<Map.Entry<Hash, DatabaseEntry>> entries = _ds.getMapEntries();
        Set<Hash> rv = new HashSet<Hash>(entries.size());
        for (Map.Entry<Hash, DatabaseEntry> entry : entries) {
            if (entry.getValue().getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                rv.add(entry.getKey());
            }
        }
        return rv;
    }
**/

    /** get the hashes for all known routers */
    public Set<Hash> getAllRouters() {
        if (isClientDb()) {
//            _log.warn("Subdb", new Exception("I did it"));
            return Collections.emptySet();
        }
        if (!_initialized) {return Collections.emptySet();}
        Collection<DatabaseEntry> entries = _ds.getEntries();
        return entries.stream()
                      .filter(e -> e.isRouterInfo())
                      .map(e -> e.getHash())
                      .collect(Collectors.toSet());
    }

    /**
     *  This used to return the number of routers that were in
     *  both the kbuckets AND the data store, which was fine when the kbuckets held everything.
     *  But now that is probably not what you want.
     *  Just return the count in the data store.
     *
     *  @return 0 if this is a client DB
     */
    @Override
    public int getKnownRouters() {
/****
        if (_kb == null) return 0;
        CountRouters count = new CountRouters();
        _kb.getAll(count);
        return count.size();
****/
        if (isClientDb()) {
//            _log.warn("Subdb", new Exception("I did it"));
            return 0;
        }
        if (_ds == null) return 0;
        int rv = 0;
        for (DatabaseEntry ds : _ds.getEntries()) {
            if (ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
                rv++;
        }
        return rv;
    }

/****
    private class CountRouters implements SelectionCollector<Hash> {
        private int _count;
        public int size() { return _count; }
        public void add(Hash entry) {
            if (_ds == null) return;
            DatabaseEntry o = _ds.get(entry);
            if (o != null && o.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
                _count++;
        }
    }
****/

    /**
     *  This is only used by StatisticsManager to publish
     *  the count if we are floodfill.
     *  So to hide a clue that a popular eepsite is hosted
     *  on a floodfill router, only count leasesets that
     *  are "received as published", as of 0.7.14
     */
    @Override
    public int getKnownLeaseSets() {
        if (_ds == null) return 0;
        //return _ds.countLeaseSets();
        int rv = 0;
        for (DatabaseEntry ds : _ds.getEntries()) {
            if (ds.isLeaseSet() &&
                ((LeaseSet)ds).getReceivedAsPublished())
                rv++;
        }
        return rv;
    }

    /**
     *  The KBucketSet contains RIs only.
     */
    protected int getKBucketSetSize() {
        if (_kb == null) return 0;
        return _kb.size();
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
        if (_log.shouldWarn())
            _log.warn("Adding to blind cache: " + bd);
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
        if (_log.shouldInfo())
            _log.info("UTC rollover -> Blind cache updated");
    }

    /**
     *  @return RouterInfo, LeaseSet, or null, validated
     *  @since 0.8.3
     */
    public DatabaseEntry lookupLocally(Hash key) {
        if (!_initialized)
            return null;
        DatabaseEntry rv = _ds.get(key);
        if (rv == null)
            return null;
        int type = rv.getType();
        if (DatabaseEntry.isLeaseSet(type)) {
            LeaseSet ls = (LeaseSet)rv;
            if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                return rv;
            } else {
                key = blindCache().getHash(key);
                fail(key);
            }
        } else if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            try {
                if (validate((RouterInfo)rv) == null)
                    return rv;
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
        if (!_initialized)
            return null;
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
            //if (_log.shouldDebug())
            //    _log.debug("LeaseSet found locally - firing " + onFindJob);
            if (onFindJob != null)
                _context.jobQueue().addJob(onFindJob);
        } else if (isNegativeCached(key)) {
            if (_log.shouldInfo())
                _log.info("Not searching for negatively cached LeaseSet [" + key.toBase64().substring(0,6) + "]");
            if (onFailedLookupJob != null)
                _context.jobQueue().addJob(onFailedLookupJob);
        } else {
            //if (_log.shouldDebug())
            //    _log.debug("LeaseSet not found locally - running search");
            key = blindCache().getHash(key);
            search(key, onFindJob, onFailedLookupJob, timeoutMs, true, fromLocalDest);
        }
        //if (_log.shouldDebug())
        //    _log.debug("after lookupLeaseSet");
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
        if (isNegativeCached(key))
            return;
//        search(key, null, null, 20*1000, true, fromLocalDest);
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
        if (!_initialized) return;
        key = blindCache().getHash(key);
        if (isNegativeCached(key))
            return;
        search(key, onFindJob, onFailedLookupJob, timeoutMs, true, fromLocalDest);
    }

    /**
     *  Use lookupDestination() if you don't need the LS or don't need it validated.
     */
    public LeaseSet lookupLeaseSetLocally(Hash key) {
        if (!_initialized) return null;
        DatabaseEntry ds = _ds.get(key);
        if (ds != null) {
            if (ds.isLeaseSet()) {
                LeaseSet ls = (LeaseSet)ds;
                if (ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                    return ls;
                } else {
                    key = blindCache().getHash(key);
                    fail(key);
                    // this was an interesting key, so either refetch it or simply explore with it
                    if (_exploreKeys != null)
                        _exploreKeys.add(key);
                    return null;
                }
            } else {
                //_log.debug("Looking for a LeaseSet [" + key + "] but it ISN'T a LeaseSet! " + ds, new Exception("Who thought that router was a lease?"));
                return null;
            }
        } else {
            return null;
        }
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
        if (d != null) {
            _context.jobQueue().addJob(onFinishedJob);
        } else if (isNegativeCached(key)) {
            if (_log.shouldInfo())
                _log.info("Not searching for negatively cached Destination [" + key.toBase64().substring(0,6) + "]");
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
        if (!_initialized) return null;
        DatabaseEntry ds = _ds.get(key);
        if (ds != null) {
            if (ds.isLeaseSet()) {
                LeaseSet ls = (LeaseSet)ds;
                return ls.getDestination();
            }
        } else {
            return _negativeCache.getBadDest(key);
        }
        return null;
    }

    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        if (!_initialized) return;
        RouterInfo ri = lookupRouterInfoLocally(key);
        if (ri != null) {
            if (onFindJob != null)
                _context.jobQueue().addJob(onFindJob);

            boolean isHidden = _context.router().isHidden() || _context.getBooleanProperty("router.hiddenMode");
            String v = ri.getVersion();
            String MIN_VERSION = "0.9.61";
            String CURRENT_VERSION = "0.9.62";
            long uptime = _context.router().getUptime();
            boolean isOld = VersionComparator.comp(v, MIN_VERSION) < 0;
            boolean isOlderThanCurrent = VersionComparator.comp(v, CURRENT_VERSION) < 0;
            Hash us = _context.routerHash();
            boolean isUs = us.equals(ri.getIdentity().getHash());
            boolean uninteresting = (ri.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
                                     ri.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                                     ri.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0 ||
                                     ri.getCapabilities().indexOf(Router.CAPABILITY_BW64) >= 0) &&
                                     isOld && (uptime > 15*60*1000 ||
//                                     _context.netDbSegmentor().getKnownRouters() > 1500) && !isUs;
                                     _context.netDb().getKnownRouters() > 1500) && !isUs;
            boolean isLTier =  ri.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                               ri.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0;
            boolean isUnreachable = ri.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
            String caps = ri.getCapabilities().toUpperCase();
            boolean isFF = false;
            boolean noCountry = true;
            String country = "unknown";
            if (caps.contains("F")) {
                isFF = true;
            }
            boolean noSSU = true;
            for (RouterAddress ra : ri.getAddresses()) {
                if (ra.getTransportStyle().equals("SSU") ||
                    ra.getTransportStyle().equals("SSU2")) {
                    noSSU = false;
                    break;
                }
            }

            country = _context.commSystem().getCountry(key);
            if (country != null && country != "unknown") {
                noCountry = false;
            }

            if (!isUs && isLTier && isUnreachable && isOlderThanCurrent) {
                if (!_context.banlist().isBanlisted(key)) {
                    if (_log.shouldInfo()) {
                        _log.info("Dropping RouterInfo [" + key.toBase64().substring(0,6) + "] -> LU and older than 0.9.61");
                    }
                    if (_log.shouldWarn() && !_context.banlist().isBanlisted(key)) {
                        _log.warn("Banning " + (caps != "" ? caps : "") + ' ' + (isFF ? "Floodfill" : "Router") +
                                  " [" + key.toBase64().substring(0,6) + "] for 4h -> LU and older than 0.9.61");
                    }
                    _context.banlist().banlistRouter(key, " <b>➜</b> LU and older than 0.9.61", null, null, _context.clock().now() + 4*60*60*1000);
                    _ds.remove(key);
                    _kb.remove(key);
                }
            } else if (!isUs && uninteresting && !isHidden) {
                if (_log.shouldInfo())
                    _log.info("Dropping RouterInfo [" + key.toBase64().substring(0,6) + "] -> Uninteresting");
                _ds.remove(key);
                _kb.remove(key);
            } else if (key != null && _context.banlist().isBanlistedForever(key)) {
                if (_log.shouldInfo())
                    _log.info("Dropping RouterInfo [" + key.toBase64().substring(0,6) + "] -> Permanently blocklisted");
                _ds.remove(key);
                _kb.remove(key);
            } else if (key != null && _context.banlist().isBanlistedHostile(key)) {
                if (_log.shouldInfo())
                    _log.info("Dropping RouterInfo [" + key.toBase64().substring(0,6) + "] -> Blocklisted (tagged as hostile)");
                _ds.remove(key);
                _kb.remove(key);
            } else if (key != null && isNegativeCached(key)) {
                if (_log.shouldInfo())
                    _log.info("Dropping RouterInfo [" + key.toBase64().substring(0,6) + "] -> Negatively cached");
                _ds.remove(key);
                _kb.remove(key);
                if (onFailedLookupJob != null)
                    _context.jobQueue().addJob(onFailedLookupJob);
            } else {
                search(key, onFindJob, onFailedLookupJob, timeoutMs, false);
            }
        }
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
        if (!_initialized) return null;
        // Client netDb shouldn't have RI, search for RI in the floodfill netDb.
        if (isClientDb()) {
            _log.warn("Subdb", new Exception("I did it"));
            return null;
        }
        DatabaseEntry ds = _ds.get(key);
        if (ds != null) {
            if (ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                // more aggressive than perhaps is necessary, but makes sure we
                // drop old references that we had accepted on startup (since
                // startup allows some lax rules).
                boolean valid = true;
                try {valid = (null == validate((RouterInfo) ds));}
                catch (IllegalArgumentException iae) {valid = false;}
                if (!valid) {
                    fail(key);
                    return null;
                }
                return (RouterInfo) ds;
            } else {
                //_log.debug("Looking for a router [" + key + "] but it ISN'T a RouterInfo! " + ds, new Exception("Who thought that lease was a router?"));
                return null;
            }
        } else {return null;}
    }

    private static final long PUBLISH_DELAY = 5*1000;

    /**
     * @throws IllegalArgumentException if the leaseSet is not valid
     */
    public void publish(LeaseSet localLeaseSet) throws IllegalArgumentException {
        if (!_initialized) {
            if (_log.shouldWarn())
                _log.warn("Attempted to publish local LeaseSet before router fully initialized: " + localLeaseSet);
            return;
        }
        Hash h = localLeaseSet.getHash();
        try {
            store(h, localLeaseSet);
        } catch (IllegalArgumentException iae) {
            _log.error("Locally published LeaseSet is not valid", iae);
            throw iae;
        }
        if (!_context.clientManager().shouldPublishLeaseSet(h)) {
            return;
        }
        // If we're exiting, don't publish.
        // If we're restarting, keep publishing to minimize the downtime.
        if (_context.router().gracefulShutdownInProgress()) {
            int code = _context.router().scheduledGracefulExitCode();
            if (code == Router.EXIT_GRACEFUL || code == Router.EXIT_HARD) {
                return;
            }
        }

        RepublishLeaseSetJob j;
        synchronized (_publishingLeaseSets) {
            j = _publishingLeaseSets.get(h);
            if (j == null) {
                j = new RepublishLeaseSetJob(_context, this, h);
                _publishingLeaseSets.put(h, j);
            }
        }
        // Don't spam the floodfills. In addition, always delay a few seconds since there may
        // be another leaseset change coming along momentarily.
        long nextTime = Math.max(j.lastPublished() + RepublishLeaseSetJob.REPUBLISH_LEASESET_TIMEOUT, _context.clock().now() + PUBLISH_DELAY);
        // remove first since queue is a TreeSet now...
        _context.jobQueue().removeJob(j);
        j.getTiming().setStartAfter(nextTime);
        if (_log.shouldInfo())
            //_log.info("Queuing local LeaseSet [" + localLeaseSet.toBase64().substring(0,6) + "] -> Publishing at " + (new Date(nextTime)));
            _log.info("Queuing local LeaseSet [" + localLeaseSet.toBase64().substring(0,6) + "] for publication...");
        _context.jobQueue().addJob(j);
    }

    void stopPublishing(Hash target) {
        synchronized (_publishingLeaseSets) {
            _publishingLeaseSets.remove(target);
        }
    }

    /**
     * Stores to local db only.
     * Overridden in FNDF to actually send to the floodfills.
     * @throws IllegalArgumentException if the local router info is invalid
     *         or if this is a client DB
     */
    public void publish(RouterInfo localRouterInfo) throws IllegalArgumentException {
        if (isClientDb())
            throw new IllegalArgumentException("RI publish to client DB");
        if (!_initialized) return;
        if (_context.router().gracefulShutdownInProgress())
            return;
        // This isn't really used for anything
        // writeMyInfo(localRouterInfo);
        if (_context.router().isHidden()) return; // DE-nied!
        Hash h = localRouterInfo.getIdentity().getHash();
        store(h, localRouterInfo);
    }

    /**
     *  Set the last time we successfully published our RI.
     *  @since 0.9.9
     */
    void routerInfoPublishSuccessful() {
        _lastRIPublishTime = _context.clock().now();
    }

    /**
     *  The last time we successfully published our RI.
     *  @since 0.9.9
     */
    @Override
    public long getLastRouterInfoPublishTime() {
        return _lastRIPublishTime;
    }

    /**
     * Persist the local router's info (as updated) into netDb/my.info, since
     * ./router.info isn't always updated.  This also allows external applications
     * to easily pick out which router a netDb directory is rooted off, which is handy
     * for getting the freshest data.
     *
     */
/***
    private final void writeMyInfo(RouterInfo info) {
        FileOutputStream fos = null;
        try {
            File dbDir = new File(_dbDir);
            if (!dbDir.exists())
                dbDir.mkdirs();
            fos = new FileOutputStream(new File(dbDir, "my.info"));
            info.writeBytes(fos);
            fos.close();
        } catch (IOException ioe) {
            if (_log.shouldError())
                _log.error("Unable to persist my.info?!", ioe);
        } catch (DataFormatException dfe) {
            if (_log.shouldError())
                _log.error("Error persisting my.info - our structure isn't valid?!", dfe);
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
***/

    /**
     * Determine whether this leaseSet will be accepted as valid and current
     * given what we know now.
     *
     * Unlike for RouterInfos, this is only called once, when stored.
     * After that, LeaseSet.isCurrent() is used.
     *
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return reason why the entry is not valid, or null if it is valid
     */
    public String validate(Hash key, LeaseSet leaseSet) throws UnsupportedCryptoException {
        if (!key.equals(leaseSet.getHash())) {
            if (_log.shouldWarn())
                _log.warn("Invalid NetDbStore attempt! Key does not match LeaseSet destination!" +
                          "\n* Key: [" + key.toBase32().substring(0,6) + "]" +
                          "\n* LeaseSet: [" + leaseSet.toBase64().substring(0,6) + "]");
            return "Key does not match LeaseSet destination - " + key.toBase64();
        }
        // todo experimental sig types
        if (!leaseSet.verifySignature()) {
            // throws UnsupportedCryptoException
            processStoreFailure(key, leaseSet);
            if (_log.shouldWarn())
                _log.warn("Invalid LeaseSet signature! [" + leaseSet.toBase64().substring(0,6) + "]");
            return "Invalid LeaseSet signature on " + key;
        }
        long earliest;
        long latest;
        int type = leaseSet.getType();
        if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
            LeaseSet2 ls2 = (LeaseSet2) leaseSet;
            // we'll assume it's not an encrypted meta, for now
            earliest = ls2.getPublished();
            latest = ls2.getExpires();
        } else if (type == DatabaseEntry.KEY_TYPE_META_LS2) {
            LeaseSet2 ls2 = (LeaseSet2) leaseSet;
            // TODO this isn't right, and must adjust limits below also
            earliest = Math.min(ls2.getEarliestLeaseDate(), ls2.getPublished());
            latest = Math.min(ls2.getLatestLeaseDate(), ls2.getExpires());
        } else {
            earliest = leaseSet.getEarliestLeaseDate();
            latest = leaseSet.getLatestLeaseDate();
        }
        long now = _context.clock().now();
        if (earliest <= now - 10*60*1000L ||
            // same as the isCurrent(Router.CLOCK_FUDGE_FACTOR) test in
            // lookupLeaseSetLocally()
            latest <= now - Router.CLOCK_FUDGE_FACTOR) {
            long age = now - earliest;
            Destination dest = leaseSet.getDestination();
            String id = dest != null ? dest.toBase32() : leaseSet.getHash().toBase32();
            if (_log.shouldWarn())
                _log.warn("Old LeaseSet [" + id.substring(0,6) + "] -> rejecting store..." +
                          "\n* First expired: " + new Date(earliest) +
                          "\n* Last expired: " + new Date(latest) +
                          "\n* " + leaseSet);
//                          new Exception("Rejecting store"));
            // i2pd bug?
            // So we don't immediately go try to fetch it for a reply
            if (leaseSet.getLeaseCount() == 0) {
                for (int i = 0; i < NegativeLookupCache.MAX_FAILS; i++) {
                     lookupFailed(key);
                }
            }
            return "LeaseSet for [" + id.substring(0,6) + "]"
                   + " expired " + DataHelper.formatDuration(age) + " ago";
        }
        if (latest > now + (Router.CLOCK_FUDGE_FACTOR + MAX_LEASE_FUTURE) &&
            (leaseSet.getType() != DatabaseEntry.KEY_TYPE_META_LS2 ||
             latest > now + (Router.CLOCK_FUDGE_FACTOR + MAX_META_LEASE_FUTURE))) {
            long age = latest - now;
            // let's not make this an error, it happens when peers have bad clocks
            Destination dest = leaseSet.getDestination();
            String id = dest != null ? dest.toBase32() : leaseSet.getHash().toBase32();
            if (_log.shouldWarn())
                _log.warn("LeaseSet expires too far in the future: [" + id.substring(0,6) +
                          "]\n* Expires: " + DataHelper.formatDuration(age) + " from now");
            return "Future LeaseSet for [" + id.substring(0,6) + "] expiring in " + DataHelper.formatDuration(age);
        }
        return null;
    }

    /**
     * Store the leaseSet.
     *
     * If the store fails due to unsupported crypto, it will negative cache
     * the hash until restart.
     *
     * @throws IllegalArgumentException if the leaseSet is not valid
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return previous entry or null
     */
    public LeaseSet store(Hash key, LeaseSet leaseSet) throws IllegalArgumentException {
        if (!_initialized) return null;

        LeaseSet rv;
        try {
            rv = (LeaseSet)_ds.get(key);
            if (rv != null && rv.getEarliestLeaseDate() >= leaseSet.getEarliestLeaseDate()) {
                if (_log.shouldDebug())
                    _log.debug("Not storing LeaseSet [" + key.toBase64().substring(0,6) + "] -> Older than our current version");
                // if it hasn't changed, no need to do anything
                // except copy over the flags
                Hash to = leaseSet.getReceivedBy();
                if (to != null) {
                    rv.setReceivedBy(to);
                } else if (leaseSet.getReceivedAsReply()) {
                    rv.setReceivedAsReply();
                }
                if (leaseSet.getReceivedAsPublished()) {
                    rv.setReceivedAsPublished();
                }
                return rv;
            }
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException("Attempt to replace RouterInfo with " + leaseSet);
        }

        // spoof / hash collision detection
        // todo allow non-exp to overwrite exp
        if (rv != null) {
            Destination d1 = leaseSet.getDestination();
            Destination d2 = rv.getDestination();
            if (d1 != null && d2 != null && !d1.equals(d2))
                throw new IllegalArgumentException("LeaseSet Hash collision");
        }

        EncryptedLeaseSet encls = null;
        int type = leaseSet.getType();
        if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
            // set dest or key before validate() calls verifySignature() which
            // will do the decryption
            encls = (EncryptedLeaseSet) leaseSet;
            BlindData bd = blindCache().getReverseData(leaseSet.getSigningKey());
            if (bd != null) {
                if (_log.shouldWarn())
                    _log.warn("Found blind data for encrypted LeaseSet: " + bd);
                // secret must be set before destination
                String secret = bd.getSecret();
                if (secret != null)
                    encls.setSecret(secret);
                Destination dest = bd.getDestination();
                if (dest != null) {
                    encls.setDestination(dest);
                } else {
                    encls.setSigningKey(bd.getUnblindedPubKey());
                }
                // per-client auth
                if (bd.getAuthType() != BlindData.AUTH_NONE)
                    encls.setClientPrivateKey(bd.getAuthPrivKey());
            } else {
                // if we created it, there's no blind data, but it's still decrypted
                if (encls.getDecryptedLeaseSet() == null && _log.shouldWarn())
                    _log.warn("No blind data found for encrypted LeaseSet: " + leaseSet);
            }
        }


        String err = validate(key, leaseSet);
        if (err != null)
            throw new IllegalArgumentException("Invalid NetDbStore attempt - " + err);

        if (_log.shouldDebug())
            _log.debug("Storing LeaseSet [" + key.toBase64().substring(0,6) + "] in persistent data store...");
        _ds.put(key, leaseSet);

        if (encls != null) {
            // we now have decrypted it, store it as well
            LeaseSet decls = encls.getDecryptedLeaseSet();
            if (decls != null) {
                if (_log.shouldWarn())
                    _log.warn("Successfully decrypted encrypted LeaseSet: " + decls);
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
                 if (dest != null)
                    blindCache().setBlinded(dest, null, null);
            }
        }
        return rv;
    }

//    private static final int MIN_ROUTERS = 90;
    private static final int MIN_ROUTERS = 2000;

    /**
     * Determine whether this routerInfo will be accepted as valid and current
     * given what we know now.
     *
     * Call this only on first store, to check the key and signature once
     *
     * If the store fails due to unsupported crypto, it will banlist
     * the router hash until restart and then throw UnsupportedCrytpoException.
     *
     * @throws UnsupportedCryptoException if that's why it failed.
     * @return reason why the entry is not valid, or null if it is valid
     */
    private String validate(Hash key, RouterInfo routerInfo) throws IllegalArgumentException {
        if (!key.equals(routerInfo.getIdentity().getHash())) {
            if (_log.shouldWarn())
                _log.warn("Invalid NetDbStore attempt! Key [" + key.toBase64().substring(0,6) + "] " +
                          "does not match identity for RouterInfo [" + routerInfo.getIdentity().getHash().toBase64().substring(0,6) + "]");
            return "Key does not match routerInfo.identity";
        }
        // todo experimental sig types
        if (!routerInfo.isValid()) {
            // throws UnsupportedCryptoException
            processStoreFailure(key, routerInfo);
            if (_log.shouldWarn())
                _log.warn("Invalid RouterInfo signature detected for [" + routerInfo.getIdentity().getHash().toBase64().substring(0,6) + "]");
                //_log.warn("Banning [" + routerInfo.getIdentity().getHash().toBase64().substring(0,6) + "] for duration of session -> Malformed RouterInfo");
            //_context.banlist().banlistRouter(key, " <b>➜</b> Malformed RouterInfo", null, null, _context.clock().now() + Banlist.BANLIST_DURATION_FOREVER);
            return "Invalid RouterInfo signature";
        }
        int id = routerInfo.getNetworkId();
        if (id != _networkID) {
            if (id == -1) {
                // old i2pd bug, possibly at startup, don't ban forever
                _context.banlist().banlistRouter(key, " <b>➜</b> No Network specified", null, null, _context.clock().now() + Banlist.BANLIST_DURATION_NO_NETWORK);
            } else {
                _context.banlist().banlistRouterForever(key, " <b>➜</b> " + "Not in our Network: " + id);
            }
            if (_log.shouldWarn())
                _log.warn("BAD Network detected for [" + routerInfo.getIdentity().getHash().toBase64().substring(0,6) + "]");
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
                    if (h.equals(_context.routerHash()))
                        break;
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
        if (!isInitialized()) { return null; }
        long now = _context.clock().now();
        String validateUptime = _context.getProperty("router.validateRoutersAfter");
        Hash us = _context.routerHash();
        boolean isUs = us.equals(routerInfo.getIdentity().getHash());
        long uptime = _context.router().getUptime();
        boolean upLongEnough = uptime > 20*60*1000;
        boolean dontFail = _context.router().getUptime() < DONT_FAIL_PERIOD;
        if (validateUptime != null)
            upLongEnough = _context.router().getUptime() > Integer.valueOf(validateUptime)*60*1000;
        // Once we're over MIN_ROUTERS routers, reduce the expiration time down from the default,
        // as a crude way of limiting memory usage.
        // i.e. at 2*MIN_ROUTERS routers the expiration time will be about half the default, etc.
        // And if we're floodfill, we can keep the expiration really short, since
        // we are always getting the latest published to us.
        // As the net grows this won't be sufficient, and we'll have to implement
        // flushing some from memory, while keeping all on disk.
        long adjustedExpiration;
        int existing = _kb.size();
        String expireRI = _context.getProperty("router.expireRouterInfo");
        String routerId = "";
        String v = routerInfo.getVersion();
        String minRouterVersion = "0.9.20";
        String MIN_VERSION = "0.9.61";
        String CURRENT_VERSION = "0.9.62";
        String minVersionAllowed = _context.getProperty("router.minVersionAllowed");
        boolean isSlow = routerInfo != null && (routerInfo.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                                                routerInfo.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0 ||
                                                routerInfo.getCapabilities().indexOf(Router.CAPABILITY_BW64) >= 0) && !isUs;
        boolean isUnreachable = routerInfo != null && routerInfo.getCapabilities().indexOf(Router.CAPABILITY_REACHABLE) < 0;
        boolean isLTier =  routerInfo != null && routerInfo.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                           routerInfo.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0;
        boolean isBanned = routerInfo != null && _context.banlist().isBanlisted(routerInfo.getIdentity().getHash());
        boolean isFF = false;
        boolean noSSU = true;
        String caps = "";
        boolean noCountry = true;
        String country = "unknown";
        Hash h = null;
        boolean isOld = routerInfo != null && !isUs && VersionComparator.comp(v, MIN_VERSION) < 0;
        boolean isOlderThanCurrent = routerInfo != null && !isUs && VersionComparator.comp(v, CURRENT_VERSION) < 0;
        if (routerInfo != null) {
            routerId = routerInfo.getIdentity().getHash().toBase64().substring(0,6);
            caps = routerInfo.getCapabilities().toUpperCase();
            h = routerInfo.getIdentity().getHash();
            if (caps.contains("F")) {isFF = true;}
            for (RouterAddress ra : routerInfo.getAddresses()) {
                if (ra.getTransportStyle().equals("SSU") ||
                    ra.getTransportStyle().equals("SSU2")) {
                    noSSU = false;
                    break;
                }
            }
            country = _context.commSystem().getCountry(h);
            if (country != null && country != "unknown") {
                noCountry = false;
            }
        }
        for (RouterAddress ra : routerInfo.getTargetAddresses("NTCP2")) {
            String i = ra.getOption("i");
            if (i != null && i.length() != 24) {
                _context.banlist().banlistRouter(routerInfo.getIdentity().calculateHash(), " <b>➜</b> Invalid NTCP address", null, null, now + 24*60*60*1000L);
                if (_log.shouldWarn() && !isBanned) {
                    _log.warn("Banning " + (caps != "" ? caps : "") + ' ' + (isFF ? "Floodfill" : "Router") +
                              " [" + routerId + "] for 24h -> Invalid NTCP address");
                }
                return "Invalid NTCP address";
            }
        }
        if (expireRI != null) {
            adjustedExpiration = Integer.valueOf(expireRI)*60*60*1000;
        } else if (floodfillEnabled()) {
            adjustedExpiration = ROUTER_INFO_EXPIRATION_FLOODFILL;
        } else {
            adjustedExpiration = existing > 4000 ? ROUTER_INFO_EXPIRATION / 3 :
                                 existing > 3000 ? ROUTER_INFO_EXPIRATION / 2 :
                                 existing > 2000 ? ROUTER_INFO_EXPIRATION / 3 * 2 :
                                 ROUTER_INFO_EXPIRATION;
        }
        if (upLongEnough && !isUs && !routerInfo.isCurrent(adjustedExpiration)) {
            long age = now - routerInfo.getPublished();
            if (existing >= MIN_REMAINING_ROUTERS) {
                if (_log.shouldInfo()) {
                    _log.info("Dropping RouterInfo [" + routerInfo.getIdentity().getHash().toBase64().substring(0,6) + "] -> " +
                              "Published " + DataHelper.formatDuration(age) + " ago");
                }
                return "Published " + DataHelper.formatDuration(age) + " ago";
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Even though RouterInfo [" + routerInfo.getIdentity().getHash().toBase64().substring(0,6) +
                              "] is STALE, we have only " + existing + " peers left - not dropping...");
                }
            }
        }
        if (routerInfo.getPublished() > now + 2*Router.CLOCK_FUDGE_FACTOR && !isUs) {
            long age = routerInfo.getPublished() - now;
            if (_log.shouldWarn() && !isBanned) {
                _log.warn("Banning [" + routerId + "] for 4h -> RouterInfo from the future!" +
                          "\n* Published: " + new Date(routerInfo.getPublished()));
            }
            _context.banlist().banlistRouter(h, " <b>➜</b> RouterInfo from the future (" +
                                             new Date(routerInfo.getPublished()) + ")", null, null, 4*60*60*1000);
            return caps + " Router [" + routerId + "] -> Published " + DataHelper.formatDuration(age) + " in the future";
        }
        if (noSSU && isFF && !isUs) {
            if (_log.shouldWarn() && !isBanned) {
                //_log.warn("Dropping RouterInfo [" + riHash + "] -> Floodfill with SSU disabled");
                _log.warn("Banning [" + routerId + "] for 4h -> Floodfill with SSU disabled");
            }
            _context.banlist().banlistRouter(h, " <b>➜</b> Floodfill with SSU disabled", null, null, 4*60*60*1000);
            return caps + " Router [" + routerId + "] -> Floodfill with SSU disabled";
        } else if (noSSU && !isUs) {
            if (_log.shouldWarn() && !isBanned) {
                //_log.warn("Dropping RouterInfo [" + riHash + "] -> Router with SSU disabled");
                _log.warn("Banning [" + routerId + "] for 4h -> Router with SSU disabled");
            }
            _context.banlist().banlistRouter(h, " <b>➜</b> Router with SSU disabled", null, null, 4*60*60*1000);
            return caps + " Router [" + routerId + "] -> Router with SSU disabled";
/**
        } else if (uptime > 45*1000 && noCountry && !isUs) {
            if (_log.shouldWarn() && !isBanned)
                _log.warn("Banning " + (caps != "" ? caps : "") + ' ' + (isFF ? "Floodfill" : "Router") +
                          " [" + routerId + "] for 15m -> Address not resolvable via GeoIP");
                if (isFF)
                    _context.banlist().banlistRouter(h, " <b>➜</b> Floodfill without GeoIP resolvable address", null, null, _context.clock().now() + 15*60*1000);
                else
                    _context.banlist().banlistRouter(h, " <b>➜</b> No GeoIP resolvable address", null, null, _context.clock().now() + 15*60*1000);
**/
        }
        if (isLTier && isUnreachable && isOlderThanCurrent) {
            if (!isBanned) {
                if (_log.shouldWarn() && !_context.banlist().isBanlisted(h)) {
                    _log.warn("Banning " + (caps != "" ? caps : "") + ' ' + (isFF ? "Floodfill" : "Router") +
                              " [" + routerId + "] for 4h -> LU and older than 0.9.61");
                }
            }
            _context.banlist().banlistRouter(h, " <b>➜</b> LU and older than 0.9.61", null, null, _context.clock().now() + 4*60*60*1000);
        }
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
        if (uptime > 10*60*1000 && existing > 500 && isSlow && routerInfo.getPublished() < now - (ROUTER_INFO_EXPIRATION_MIN / 8)) {
            if (_log.shouldInfo())
                _log.info("Dropping RouterInfo [" + routerId + "] -> K, L or M tier and published over 1h ago");
            return caps + " Router [" + routerId + "] -> Slow and published over 1h ago";
        } else if (isSlow && routerInfo.getPublished() < now - (ROUTER_INFO_EXPIRATION_MIN / 4)) {
            if (_log.shouldInfo())
                _log.info("Dropping RouterInfo [" + routerId + "] -> K, L or M tier and published over 2h ago");
            return caps + " Router [" + routerId + "] -> Slow and published over 2h ago";
        }
//        if (upLongEnough && !routerInfo.isCurrent(ROUTER_INFO_EXPIRATION_INTRODUCED)) {
        if (!dontFail && !routerInfo.isCurrent(ROUTER_INFO_EXPIRATION_INTRODUCED) && !isUs) {
            if (routerInfo.getAddresses().isEmpty()) {
                if (_log.shouldInfo())
                    _log.info("Dropping RouterInfo [" + routerId + "] -> No addresses and published over 54m ago");
                return caps + " Router [" + routerId + "] -> No addresses and published over 54m ago";
            }
            // This should cover the introducers case below too
            // And even better, catches the case where the router is unreachable but knows no introducers
            if (!isUs && routerInfo.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 || routerInfo.getAddresses().isEmpty()) {
                if (_log.shouldInfo())
                    _log.info("Dropping RouterInfo [" + routerId + "] -> Unreachable and published over 54m ago");
                return caps + " Router [" + routerId + "] -> Unreachable and published over 54m ago";
            }
            // Just check all the addresses, faster than getting just the SSU ones
            for (RouterAddress ra : routerInfo.getAddresses()) {
                // Introducers change often, introducee will ping introducer for 2 hours
                if (ra.getOption("itag0") != null) {
                    if (_log.shouldInfo())
                        _log.info("Dropping RouterInfo [" + routerId + "] -> SSU Introducers and published over 54m ago");
                    return caps + " Router [" + routerId + "] -> SSU Introducers and published over 54m ago";
                }
            }
        }
        if (expireRI != null && !isUs) {
            if (upLongEnough && (routerInfo.getPublished() < now - Long.valueOf(expireRI)*60*60*1000l) ) {
                long age = now - routerInfo.getPublished();
                return caps + " Router [" + routerId + "] -> Published " + DataHelper.formatDuration(age) + " ago";
            }
        } else {
            if (upLongEnough && (routerInfo.getPublished() < now - ROUTER_INFO_EXPIRATION) && !isUs) {
                long age = _context.clock().now() - routerInfo.getPublished();
                return caps + " Router [" + routerId + "] -> Published " + DataHelper.formatDuration(age) + " ago";
            }
        }
        if (!routerInfo.isCurrent(ROUTER_INFO_EXPIRATION_SHORT)) {
            for (RouterAddress ra : routerInfo.getAddresses()) {
                if (routerInfo.getTargetAddresses("NTCP", "NTCP2").isEmpty() && ra.getOption("ihost0") == null && !isUs) {
                    return caps + " Router [" + routerId + "] -> SSU only without Introducers and published over 15m ago";
                } else {
                    if (isUnreachable && !isUs)
                    return caps + " Router [" + routerId + "] -> Unreachable on any transport and published over 15m ago";
                }
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
        if (!_initialized) return null;
        if (isClientDb())
            throw new IllegalArgumentException("RI store to client DB");

        RouterInfo rv;
        try {
            rv = (RouterInfo)_ds.get(key, persist);
            if (rv != null && rv.getPublished() >= routerInfo.getPublished()) {
                if (_log.shouldDebug())
                    _log.debug("Not storing RouterInfo [" + key.toBase64().substring(0,6) + "] -> Older than our current copy");
                // quick check without calling validate()
                return rv;
            }
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException("Attempt to replace LeaseSet with " + routerInfo);
        }

        // spoof / hash collision detection
        // todo allow non-exp to overwrite exp
        if (rv != null && !routerInfo.getIdentity().equals(rv.getIdentity()))
            throw new IllegalArgumentException("RouterInfo Hash collision");

        String err = validate(key, routerInfo);
        if (err != null)
            throw new IllegalArgumentException("Invalid NetDbStore attempt - " + err);

        //if (_log.shouldDebug())
        //    _log.debug("RouterInfo " + key.toBase64() + " is stored with "
        //               + routerInfo.getOptionsMap().size() + " options on "
        //               + new Date(routerInfo.getPublished()));

        _context.peerManager().setCapabilities(key, routerInfo.getCapabilities());
        _ds.put(key, routerInfo, persist);
        if (rv == null)
            _kb.add(key);
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
        if (entry.getHash().equals(h)) {
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
                                if (_log.shouldWarn())
                                    _log.warn("Unsupported Signature type " + stype + " for destination " + h);
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
                            if (_log.shouldWarn())
                                _log.warn("Unsupported Signature type (" + stype + ") for ["
                                          + h.toBase64().substring(0,6) + "] - banned until restart");
                            throw new UnsupportedCryptoException("Sig type " + stype);
                        }
                    } catch (DataFormatException dfe) {}
                }
            }
        }
        if (_log.shouldWarn())
            _log.warn("RouterInfo verification failure (Unknown cause)\n" + entry);
    }


    /**
     *   Final remove for a leaseset.
     *   For a router info, will look up in the network before dropping.
     */
    public void fail(Hash dbEntry) {
        if (!_initialized) return;
        DatabaseEntry o = _ds.get(dbEntry);
        if (o == null) {
            // if we dont know the key, lets make sure it isn't a now-dead peer
            if (_kb != null)
                _kb.remove(dbEntry);
            _context.peerManager().removeCapabilities(dbEntry);
            return;
        }

        if (o.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            lookupBeforeDropping(dbEntry, (RouterInfo)o);
            return;
        }

        // we always drop leaseSets that are failed [timed out],
        // regardless of how many routers we have.  this is called on a lease if
        // it has expired *or* its tunnels are failing and we want to see if there
        // are any updates
        if (_log.shouldInfo())
            _log.info("Dropping LeaseSet [" + dbEntry.toBase64().substring(0,6) + "] -> Lookup / tunnel failure");
        if (!isClientDb()) {
            _ds.remove(dbEntry, false);
        } else {
            // if this happens it's because we're a TransientDataStore instead,
            // so just call remove without the persist option.
            _ds.remove(dbEntry);
        }
    }

    /** don't use directly - see F.N.D.F. override */
    protected void lookupBeforeDropping(Hash peer, RouterInfo info) {
        //bah, humbug.
        dropAfterLookupFailed(peer);
    }

    /**
     *  Final remove for a router info.
     *  Do NOT use for leasesets.
     */
    void dropAfterLookupFailed(Hash peer) {
        if (isClientDb()) {
            _log.warn("Subdb", new Exception("I did it"));
            return;
        }
        _context.peerManager().removeCapabilities(peer);
        _negativeCache.cache(peer);
        _kb.remove(peer);
        _ds.remove(peer);
        if (_log.shouldInfo())
            _log.info("Dropping RouterInfo [" + peer.toBase64().substring(0,6) + "] -> Lookup failure");
    }

    public void unpublish(LeaseSet localLeaseSet) {
        if (!_initialized) return;
        Hash h = localLeaseSet.getHash();
        DatabaseEntry data = _ds.remove(h);

        if (data == null) {
            if (_log.shouldWarn())
                _log.warn("Unpublishing UNKNOWN local LeaseSet [" + localLeaseSet.toBase64().substring(0,6) + "]");
        } else {
            if (_log.shouldInfo())
                _log.info("Unpublishing LOCAL LeaseSet [" + h.toBase64().substring(0,6) + "]");
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
/****
        if (!_initialized) return null;
        boolean isNew = true;
        SearchJob searchJob = null;
        synchronized (_activeRequests) {
            searchJob = _activeRequests.get(key);
            if (searchJob == null) {
                searchJob = new SearchJob(_context, this, key, onFindJob, onFailedLookupJob,
                                         timeoutMs, true, isLease);
                _activeRequests.put(key, searchJob);
            } else {
                isNew = false;
            }
        }
        if (isNew) {
            if (_log.shouldDebug())
                _log.debug("this is the first search for that key, fire off the SearchJob");
            _context.jobQueue().addJob(searchJob);
        } else {
            if (_log.shouldInfo())
                _log.info("Deferred search for " + key.toBase64() + " with " + onFindJob);
            int deferred = searchJob.addDeferred(onFindJob, onFailedLookupJob, timeoutMs, isLease);
            _context.statManager().addRateData("netDb.lookupDeferred", deferred, searchJob.getExpiration()-_context.clock().now());
        }
        return searchJob;
****/
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
        if (!_initialized) return null;
        Set<LeaseSet> leases = new HashSet<LeaseSet>();
        for (DatabaseEntry o : getDataStore().getEntries()) {
            if (o.isLeaseSet())
                leases.add((LeaseSet)o);
        }
        return leases;
    }

    /**
     *  Public for NetDbRenderer in routerconsole
     *
     *  @return empty set if this is a client DB
     */
    @Override
    public Set<RouterInfo> getRouters() {
        if (isClientDb()) {
            _log.warn("Subdb", new Exception("I did it"));
            return Collections.emptySet();
        }
        if (!_initialized) return null;
        Set<RouterInfo> routers = new HashSet<RouterInfo>();
        for (DatabaseEntry o : getDataStore().getEntries()) {
            if (o.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
                routers.add((RouterInfo)o);
        }
        return routers;
    }

    /** smallest allowed period */
//    private static final int MIN_PER_PEER_TIMEOUT = 2*1000;
    private static final int MIN_PER_PEER_TIMEOUT = 4*1000;
    /**
     *  We want FNDF.PUBLISH_TIMEOUT and RepublishLeaseSetJob.REPUBLISH_LEASESET_TIMEOUT
     *  to be greater than MAX_PER_PEER_TIMEOUT * TIMEOUT_MULTIPLIER by a factor of at least
     *  3 or 4, to allow at least that many peers to be attempted for a store.
     */
//    private static final int MAX_PER_PEER_TIMEOUT = 5*1000;
    private static final int MAX_PER_PEER_TIMEOUT = 6*1000;
//    private static final int TIMEOUT_MULTIPLIER = 3;
    private static final int TIMEOUT_MULTIPLIER = 5;

    /** todo: does this need more tuning? */
    public int getPeerTimeout(Hash peer) {
        PeerProfile prof = _context.profileOrganizer().getProfile(peer);
        double responseTime = MAX_PER_PEER_TIMEOUT;
        if (prof != null && prof.getIsExpandedDB()) {
            responseTime = prof.getDbResponseTime().getRate(60*60*1000L).getAvgOrLifetimeAvg();
            // if 0 then there is no data, set to max.
            if (responseTime <= 0 || responseTime > MAX_PER_PEER_TIMEOUT)
                responseTime = MAX_PER_PEER_TIMEOUT;
            else if (responseTime < MIN_PER_PEER_TIMEOUT)
                responseTime = MIN_PER_PEER_TIMEOUT;
        }
        return TIMEOUT_MULTIPLIER * (int)responseTime;  // give it up to 3x the average response time
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
    void lookupFailed(Hash key) {
        _negativeCache.lookupFailed(key);
    }

    /**
     *  Is the key in the negative lookup cache?
     *
     *  @param key for Destinations or RouterIdentities
     *  @since 0.9.4 moved from FNDF to KNDF in 0.9.16
     */
    boolean isNegativeCached(Hash key) {
        boolean rv = _negativeCache.isCached(key);
        if (rv)
            _context.statManager().addRateData("netDb.negativeCache", 1);
        return rv;
    }

    /**
     *  Negative cache until restart
     *  @since 0.9.16
     */
    void failPermanently(Destination dest) {
        _negativeCache.failPermanently(dest);
    }

    /**
     *  Is it permanently negative cached?
     *
     *  @param key only for Destinations; for RouterIdentities, see Banlist
     *  @since 0.9.16
     */
    public boolean isNegativeCachedForever(Hash key) {
        return _negativeCache.getBadDest(key) != null;
    }

    /**
     * Debug info, HTML formatted
     * @since 0.9.10
     */
    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        if (_kb == null)
            return;
        out.write(_kb.toString().replace("\n", "<br>\n"));
    }

    /**
     * @since 0.9.61
     */
    @Override
    public String toString() {
        if (!isClientDb())
            return "MainNetDb";
        return "ClientNetDb [" + _dbid.toBase64().substring(0,8) + "]";
    }

}
