package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import net.i2p.stat.RateConstants;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterAddress;
import net.i2p.kademlia.KBucketSet;
import net.i2p.kademlia.SelectionCollector;
import net.i2p.kademlia.XORComparator;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.router.Router;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.router.util.RandomIterator;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

/**
 * This is where we implement semi-Kademlia with the floodfills, by selecting
 * floodfills closest to a given key for searches and stores.
 *
 * Warning - most methods taking a key as an argument require the routing key,
 * not the original key.
 *
 */
class FloodfillPeerSelector extends PeerSelector {

    private BanLogger _banLogger;
    private final KademliaNetworkDatabaseFacade _facade;

    private static volatile RouterContext _cfgCtx;
    private static volatile long _cfgRefreshed;
    private static volatile boolean _cachedEnableUnresponsiveFloodfillBan;
    private static final long CONFIG_REFRESH_MS = 30 * 1000L;
    private final RateStat[] _failedLookupRateStatSlot = new RateStat[1];
    private final RateStat[] _testSuccessTimeStatSlot = new RateStat[1];

    /**
     *  Refresh the cached configuration from properties at most once per
     *  CONFIG_REFRESH_MS, or immediately when the context changes.
     *  Benign race: duplicate refreshes are idempotent writes.
     */
    private static void refreshConfig(RouterContext ctx) {
        long now = ctx.clock().now();
        if (_cfgCtx == ctx && now - _cfgRefreshed < CONFIG_REFRESH_MS)
            return;
        _cachedEnableUnresponsiveFloodfillBan = ctx.getProperty("router.banlist.enableUnresponsiveFloodfillBan", true);
        _cfgCtx = ctx;
        _cfgRefreshed = now;
    }

    private static boolean getEnableUnresponsiveFloodfillBan(RouterContext ctx) {
        refreshConfig(ctx);
        return _cachedEnableUnresponsiveFloodfillBan;
    }

    /**
     *  Get the RateStat handle, caching it in the slot for subsequent calls.
     *  Null is not cached, the stat may register later.
     */
    private RateStat getRateStat(RateStat[] slot, String name) {
        RateStat rs = slot[0];
        if (rs == null) {
            rs = _context.statManager().getRate(name);
            if (rs != null) slot[0] = rs;
        }
        return rs;
    }

    /**
     * FloodfillPeerSelector.
     */
    public FloodfillPeerSelector(RouterContext ctx, KademliaNetworkDatabaseFacade facade) {
        super(ctx);
        _facade = facade;
        _banLogger = new BanLogger();
        _banLogger.initialize(ctx);
    }

    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     *
     * Walks the kbuckets with a FloodfillSelectionCollector, which gathers floodfill
     * peers in groups (good floodfills first, then bad, then non-floodfills sorted by
     * Kademlia distance to the key).
     *
     * Does not prefer the floodfill peers that are directly connected.
     * List will not include our own hash. Returns new list, may be modified.
     *
     * @param key the ROUTING key (NOT the original key)
     * @param peersToIgnore can be null
     * @return List of Hash for the peers selected
     */
    @Override
    List<Hash> selectNearestExplicit(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet<Hash> kbuckets) {
        if (peersToIgnore == null) {peersToIgnore = Collections.singleton(_context.routerHash());}
        else {peersToIgnore.add(_context.routerHash());}
        // TODO this is very slow
        FloodfillSelectionCollector matches = new FloodfillSelectionCollector(key, peersToIgnore, maxNumRouters);
        if (kbuckets == null) {return new ArrayList<>();}
        kbuckets.getAll(matches);
        List<Hash> rv = matches.get(maxNumRouters);
        if (_log.shouldDebug()) {
            StringBuilder buf = new StringBuilder();
            buf.append("Searching for ").append(maxNumRouters).append(" peers close to [").append(key.toBase64().substring(0,6)).append("]");
            buf.append("\n* All Hashes: ").append(matches.size());
            buf.append("\n* Ignoring: ");
            for (Hash h : peersToIgnore) {
                buf.append("[").append(h.toBase64().substring(0,6)).append("]").append(" ");
            }
            buf.append("\n* Matched: ");
            for (Hash h : rv) {
                buf.append("[").append(h.toBase64().substring(0,6)).append("]").append(" ");
            }
            _log.debug(buf.toString());
        }
        return rv;
    }

    /**
     *  List will not include our own hash.
     *  List is not sorted and not shuffled.
     *  Returns new list, may be modified.
     *
     *  @param kbuckets now unused
     *  @return all floodfills not banlisted forever.
     */
    List<Hash> selectFloodfillParticipants(KBucketSet<Hash> kbuckets) {
        Set<Hash> ignore = Collections.singleton(_context.routerHash());
        return selectFloodfillParticipants(ignore, kbuckets);
    }

    /**
     *  List MAY INCLUDE our own hash.
     *  List is not sorted and not shuffled.
     *  Returns new list, may be modified.
     *
     *  @param _kbuckets now unused
     *  @param toIgnore can be null
     *  @return all floodfills not banlisted forever and not flagged unreachable.
     */
    List<Hash> selectFloodfillParticipants(Set<Hash> toIgnore, KBucketSet<Hash> _kbuckets) {
        Set<Hash> set = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
        List<Hash> rv = new ArrayList<>(set.size());
        for (Hash h : set) {
            if ((toIgnore != null && toIgnore.contains(h)) ||
                _context.banlist().isBanlisted(h) ||
                _context.banlist().isBanlistedForever(h) ||
                _context.profileOrganizer().peerSendsBadReplies(h)) {
                continue;
            }
            RouterInfo ri = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(h);
            if (ri != null && ri.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0) {
                continue;
            }
            rv.add(h);
        }
        return rv;
    }

    /**
     * Sort the floodfills. The challenge here is to keep the good ones at the front
     * and the bad ones at the back. If they are all good or bad, searches and stores won't work well.
     *
     * List will not include our own hash. Returns new list, may be modified.
     *
     * @return floodfills closest to the key that are not banlisted forever
     * @param key the ROUTING key (NOT the original key)
     * @param maxNumRouters max to return
     * @param kbuckets now unused
     *
     * Sorted by closest to the key if &gt; maxNumRouters, otherwise not
     * The list is in 3 groups - sorted by routing key within each group.
     * Group 1: No store or lookup failure in a long time, and
     *          lookup fail rate no more than 1.5 * average
     * Group 2: No store or lookup failure in a little while or
     *          success newer than failure
     * Group 3: All others
     */
    List<Hash> selectFloodfillParticipants(Hash key, int maxNumRouters, KBucketSet<Hash> kbuckets) {
        Set<Hash> ignore = Collections.singleton(_context.routerHash());
        return selectFloodfillParticipants(key, maxNumRouters, ignore, kbuckets);
    }

    private static final int NO_FAIL_STORE_OK = 5*60*1000;
    private static final int NO_FAIL_STORE_GOOD = NO_FAIL_STORE_OK * 2;
    /** This must be longer than the max streaming timeout (60s). */
    private static final int NO_FAIL_LOOKUP_OK = 70*1000;
    private static final int NO_FAIL_LOOKUP_GOOD = NO_FAIL_LOOKUP_OK * 3;
    private static final int MAX_GOOD_RESP_TIME = 2000;
    // TODO we need better tracking of floodfill first-heard-about times
    // before we can do this. Old profiles get deleted.
    private static final long HEARD_AGE = 45*60*1000L;
    private static final long INSTALL_AGE = HEARD_AGE + (60*60*1000L);
    /** Floodfills with RouterInfo published longer ago than this are skipped. */
    private static final long MAX_RI_AGE = 3*60*60*1000L;

    /**
     *  See above for description
     *  List will not include our own hash
     *  Returns new list, may be modified.
     *
     *  @param key the ROUTING key (NOT the original key)
     *  @param toIgnore can be null
     *  @param kbuckets now unused
     */
    List<Hash> selectFloodfillParticipants(Hash key, int howMany, Set<Hash> toIgnore, KBucketSet<Hash> kbuckets) {
        if (toIgnore == null) {toIgnore = Collections.singleton(_context.routerHash());}
        else if (!toIgnore.contains(_context.routerHash())) {
            // copy the Set so we don't confuse StoreJob
            // Synchronize on toIgnore because caller may use Collections.synchronizedSet()
            // which only synchronizes individual operations, not iteration
            Set<Hash> newIgnore;
            synchronized (toIgnore) {
                newIgnore = new HashSet<>(toIgnore.size() + 1);
                newIgnore.addAll(toIgnore);
            }
            newIgnore.add(_context.routerHash());
            toIgnore = newIgnore;
        }
        return selectFloodfillParticipantsIncludingUs(key, howMany, toIgnore, kbuckets);
    }

    /**
     *  See above for description
     *  List MAY CONTAIN our own hash unless included in toIgnore
     *  Returns new list, may be modified.
     *
     *  @param key the ROUTING key (NOT the original key)
     *  @param toIgnore can be null
     *  @param kbuckets now unused
     */
    private List<Hash> selectFloodfillParticipantsIncludingUs(Hash key, int howMany, Set<Hash> toIgnore, KBucketSet<Hash> kbuckets) {
        List<Hash> sorted = selectFloodfillParticipants(toIgnore, kbuckets);
        Collections.sort(sorted, new XORComparator<>(key));

        int found = 0;
        long now = _context.clock().now();
        long installed = _context.getProperty("router.firstInstalled", 0L);
        long uptime = _context.router().getUptime();
        boolean enforceHeard = installed > 0 && (now - installed) > INSTALL_AGE;
        double maxFailRate = computeMaxFailRate(uptime);

        MaskedIPSet maskedIPs = new MaskedIPSet(Math.min(sorted.size(), 128) * 3);
        // split sorted list into 3 unsorted lists
        List<Hash> rv = new ArrayList<>(howMany);
        List<Hash> okff = new ArrayList<>(howMany);
        List<Hash> badff = new ArrayList<>(howMany);
        for (int i = 0; found < howMany && i < sorted.size(); i++) {
            Hash entry = sorted.get(i);
            if (entry == null) {continue;} // shouldn't happen
            if (uptime < 45*1000L) {break;}
            // Skip recently-queried floodfills to spread load across concurrent searches
            if (_facade.isRecentlyQueried(entry)) {
                continue;
            }
            RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(entry);
            MaskedIPSet entryIPs = new MaskedIPSet(_context, entry, info, 2); // put anybody in the same /16 at the end
            boolean sameIP = false;
            if (info != null) {
                for (String ip : entryIPs) {
                    if (!maskedIPs.add(ip)) {sameIP = true;}
                }
            }
            PeerClass cls = classifyFloodfillPeer(entry, info, sameIP, now, enforceHeard, maxFailRate);
            switch (cls) {
                case GOOD:  rv.add(entry); found++; break;
                case OK:    okff.add(entry); break;
                case BAD:   badff.add(entry); break;
            }
        }
        logSelectionResults(rv, okff, badff);
        // Shuffle within each tier so concurrent searches don't all pick
        // the same XOR-closest peers, distributing load across floodfills
        Collections.shuffle(rv, _context.random());
        Collections.shuffle(okff, _context.random());
        Collections.shuffle(badff, _context.random());
        for (int i = 0; found < howMany && i < okff.size(); i++) {
            rv.add(okff.get(i));
            found++;
        }
        // Put the "bad" floodfills after the ok floodfills
        for (int i = 0; found < howMany && i < badff.size(); i++) {
            rv.add(badff.get(i));
            found++;
        }
        // Reserve up to 2 slots for random BAD peers to profile their performance;
        // this lets us detect and ban unresponsive floodfills that we'd otherwise never query
        int profiled = 0;
        for (Hash bad : badff) {
            if (profiled >= 2 || rv.size() >= howMany) break;
            if (!rv.contains(bad)) {
                rv.add(bad);
                profiled++;
            }
        }

        return rv;
    }

    /**
     *  Classification result for floodfill peer selection.
     */
    private enum PeerClass { GOOD, OK, BAD }

    /**
     *  Compute the maximum acceptable failure rate for a floodfill peer,
     *  based on the network average over the last 10 minutes.
     *  Returns a value between 0.20 and 0.95.
     */
    private double computeMaxFailRate(long uptime) {
        double maxFailRate = 0.95;
        if (uptime > 2*60*60*1000L) {
            RateStat rs = getRateStat(_failedLookupRateStatSlot, "peer.failedLookupRate");
            if (rs != null) {
                Rate r = rs.getRate(RateConstants.TEN_MINUTES);
                if (r != null) {
                    double currentFailRate = r.getAverageValue();
                    maxFailRate = Math.min(0.95d, Math.max(0.20d, 1.25d * currentFailRate));
                }
            }
        }
        return maxFailRate;
    }

    /**
     *  Classify a single floodfill peer as GOOD, OK, or BAD for selection.
     *  GOOD: no recent failures, fast response, good profile.
     *  OK: some recent failures but still usable.
     *  BAD: unreachable, stale, slow, same IP, strict country, or poor profile.
     */
    private PeerClass classifyFloodfillPeer(Hash entry, RouterInfo info, boolean sameIP,
                                             long now, boolean enforceHeard, double maxFailRate) {
        if (info == null) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Bad: We have no profile for this router");
            return PeerClass.BAD;
        }
        String caps = DataHelper.stripHTML(info.getCapabilities());
        boolean isUnreachable = caps != null && !caps.contains("R");
        if (isUnreachable) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Bad: Router is unreachable");
            return PeerClass.BAD;
        }
        if (sameIP) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Bad: Same /16, family, or port");
            return PeerClass.BAD;
        }
        if (now - info.getPublished() > MAX_RI_AGE) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Bad: RouterInfo published over 3 hours ago");
            return PeerClass.BAD;
        }
        if (_context.commSystem().isInStrictCountry(info)) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Bad: Router located in strict country");
            return PeerClass.BAD;
        }
        if (info.getBandwidthTier().equals("L") || info.getBandwidthTier().equals("M")) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Bad: Router is slow (L or M tier)");
            if (info.getBandwidthTier().equals("L")) {
                if (_context.banlist().isLuBanEnabled()) {
                    String verCaps = "(" + info.getVersion() + " / " + caps + ")";
                    banAndDisconnect(entry, info, "L tier Floodfill " + verCaps, "L tier Floodfill " + verCaps,
                                     "L tier Floodfill", 4*60*60*1000L, 4*60*60*1000L, now);
                    if (_log.shouldWarn()) {
                        _log.warn("Banning for 4h and disconnecting from Floodfill [" + entry.toBase64().substring(0,6) + "] -> L tier " + verCaps);
                    }
                }
            }
            return PeerClass.BAD;
        }
        PeerProfile prof = _context.profileOrganizer().getOrCreateProfileNonblocking(entry);
        double maxGoodRespTime = MAX_GOOD_RESP_TIME;
        RateStat ttst = getRateStat(_testSuccessTimeStatSlot, "tunnel.testSuccessTime");
        if (ttst != null) {
            Rate tunnelTestTime = ttst.getRate(RateConstants.TEN_MINUTES);
            if (tunnelTestTime != null && tunnelTestTime.getAverageValue() > 500)
                maxGoodRespTime = 2 * tunnelTestTime.getAverageValue();
        }
        if (prof == null) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Bad: We have no profile for this router");
            return PeerClass.BAD;
        }
        if (enforceHeard && prof.getFirstHeardAbout() > now - HEARD_AGE) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Bad: Router is too new (less than 45m old)");
            return PeerClass.BAD;
        }
        if (prof.getDBHistory() == null) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Bad: Profile contains no history");
            return PeerClass.BAD;
        }
        Rate dbRespRate = prof.getDbResponseTime().getRate(RateConstants.ONE_HOUR);
        Rate goodFailRate = prof.getDBHistory().getFailedLookupRate().getRate(RateConstants.ONE_HOUR);
        if (dbRespRate != null && goodFailRate != null &&
            dbRespRate.getAvgOrLifetimeAvg() < maxGoodRespTime
            && prof.getDBHistory().getLastStoreFailed() < now - NO_FAIL_STORE_GOOD
            && prof.getDBHistory().getLastLookupFailed() < now - NO_FAIL_LOOKUP_GOOD
            && goodFailRate.getAverageValue() < maxFailRate) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Good");
            return PeerClass.GOOD;
        }
        if (prof.getDBHistory().getLastStoreFailed() <= prof.getDBHistory().getLastStoreSuccessful() ||
            prof.getDBHistory().getLastLookupFailed() <= prof.getDBHistory().getLastLookupSuccessful() ||
            (prof.getDBHistory().getLastStoreFailed() < now - NO_FAIL_STORE_OK &&
            prof.getDBHistory().getLastLookupFailed() < now - NO_FAIL_LOOKUP_OK)) {
            if (_log.shouldDebug())
                _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> OK");
            return PeerClass.OK;
        }
        // Ban floodfills with >95% failure rate on >=30 failed queries and no recent success
        Rate failRate = prof.getDBHistory().getFailedLookupRate().getRate(RateConstants.ONE_HOUR);
        if (failRate != null &&
            failRate.getLifetimeEventCount() >= 30 &&
            failRate.getAverageValue() > 0.95d &&
            now - Math.max(prof.getDBHistory().getLastLookupSuccessful(), prof.getDBHistory().getLastStoreSuccessful()) > 15*60*1000L &&
            Math.max(prof.getDBHistory().getLastLookupFailed(), prof.getDBHistory().getLastStoreFailed()) > now - 15*60*1000L) {
            if (getEnableUnresponsiveFloodfillBan(_context)) {
                String ipPort = getIPFromRouterInfo(info);
                String verCaps = "(" + info.getVersion() + " / " + caps + ")";
                banAndDisconnect(entry, info, "Unresponsive Floodfill", "Unresponsive Floodfill " + verCaps,
                                 "Unresponsive Floodfill", 60*60*1000L, 30*60*1000L, now);
                if (_log.shouldWarn()) {
                    _log.warn("Banning for 30m and disconnecting from unresponsive Floodfill [" + entry.toBase64().substring(0,6) + "] -> " +
                              "Fail rate: " + String.format("%.2f", failRate.getAverageValue()) +
                              ", Failures: " + failRate.getLifetimeEventCount() +
                              ", IP: " + (ipPort != null ? ipPort : "UNKNOWN") +
                              ", " + verCaps);
                }
            }
            return PeerClass.BAD;
        }
        if (_log.shouldDebug())
            _log.debug("Floodfill sort: [" + entry.toBase64().substring(0,6) + "] -> Bad: Poor profile history for this router");
        return PeerClass.BAD;
    }

    /**
     *  Ban, log, and disconnect from a floodfill peer, with separate texts for
     *  the banlist, the ban log, and the disconnect reason.
     *
     *  @param entry the peer hash
     *  @param info the peer's RouterInfo
     *  @param banReason the reason recorded in the banlist
     *  @param logReason the reason recorded in the ban log
     *  @param disconnectReason the reason passed to forceDisconnect
     *  @param banDuration the ban length in ms
     *  @param logDuration the ban log length in ms
     *  @param now the current time in ms
     */
    private void banAndDisconnect(Hash entry, RouterInfo info, String banReason, String logReason,
                                  String disconnectReason, long banDuration, long logDuration, long now) {
        String ipPort = getIPFromRouterInfo(info);
        _context.banlist().banlistRouter(entry, banReason, null, null, now + banDuration);
        _banLogger.logBan(entry, ipPort != null ? ipPort : "UNKNOWN", logReason, logDuration, info);
        _context.commSystem().forceDisconnect(entry, disconnectReason);
    }

    /**
     *  Log the classification results at debug level.
     */
    private void logSelectionResults(List<Hash> good, List<Hash> ok, List<Hash> bad) {
        if (_log.shouldDebug()) {
            StringBuilder buf = new StringBuilder();
            buf.append("Floodfill sort results:");
            if (!good.isEmpty()) {
                buf.append("\n* Good: ");
                for (Hash h : good) {
                    buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
                }
            }
            if (!ok.isEmpty()) {
                buf.append("\n* OK: ");
                for (Hash h : ok) {
                    buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
                }
            }
            if (!bad.isEmpty()) {
                buf.append("\n* Bad: ");
                for (Hash h : bad) {
                    buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
                }
            }
            _log.debug(buf.toString());
        }
    }

    private class FloodfillSelectionCollector implements SelectionCollector<Hash> {
        private final TreeSet<Hash> _sorted;
        private final List<Hash>  _floodfillMatches;
        private final Hash _key;
        private final Set<Hash> _toIgnore;
        private int _matches;
        private final int _wanted;
        private final Map<Hash, RouterInfo> _infoCache = new HashMap<>(64);

        /**
         *  Warning - may return our router hash - add to toIgnore if necessary
         *  @param key the ROUTING key (NOT the original key)
         *  @param toIgnore can be null
         */
        public FloodfillSelectionCollector(Hash key, Set<Hash> toIgnore, int wanted) {
            _key = key;
            _sorted = new TreeSet<>(new XORComparator<>(key));
            _floodfillMatches = new ArrayList<>(8);
            _toIgnore = toIgnore;
            _wanted = wanted;
        }
        private static final int EXTRA_MATCHES = 200;
        /**
         * Add an entry to the selection, skipping ignored, banlisted,
         * or non-floodfill entries once enough matches have been collected.
         *
         * @param entry the candidate router hash
         */
        public void add(Hash entry) {
            if ((_toIgnore != null) && (_toIgnore.contains(entry))) {return;}
            if (_context.banlist().isBanlisted(entry)) {return;}
            RouterInfo info = cachedInfo(entry);
            if (info != null && FloodfillNetworkDatabaseFacade.isFloodfill(info)) {_floodfillMatches.add(entry);}
            else {
                // This didn't really work because we stopped filling up when _wanted == _matches,
                // thus we don't add and sort the whole db to find the closest.
                // So we keep going for a while. This, together with periodically shuffling the
                // KBucket (see KBucketImpl.add()) makes exploration work well.
                if ((!SearchJob.onlyQueryFloodfillPeers(_context)) && (_wanted + EXTRA_MATCHES > _matches) && (_key != null)) {
                    _sorted.add(entry);
                } else {return;}
            }
            _matches++;
        }

        /**
         * The first howMany matching entries, floodfills with the 'f' mark in their
         * NetDb except for banlisted ones; non-floodfills only if there aren't enough floodfills.
         *
         * The list is in 3 groups - unsorted (shuffled) within each group.
         * Group 1: NetDb published less than 3h ago, no bad send in last 30m.
         * Group 2: All others
         * Group 3: Non-floodfills, sorted by closest-to-the-key
         *
         * @param howMany the maximum number of entries to return
         * @return the list of matching peer hashes
         */
        public List<Hash> get(int howMany) {
            List<Hash> rv = new ArrayList<>(howMany);
            List<Hash> badff = new ArrayList<>(howMany);
            int found = 0;
            long now = _context.clock().now();
            // Only add in "good" floodfills here...
            // Let's say published in last 3h and no failed sends in last 30m
            // (Forever banlisted ones are excluded in add() above)
            for (Iterator<Hash> iter = new RandomIterator<>(_floodfillMatches); (found < howMany) && iter.hasNext(); ) {
                Hash entry = iter.next();
                RouterInfo info = cachedInfo(entry);
                if (info != null && now - info.getPublished() > MAX_RI_AGE) {
                    badff.add(entry);
                    if (_log.shouldDebug())
                        _log.debug("Floodfill sort: Skipping [" + entry.toBase64().substring(0,6) + "] -> RouterInfo published over 3h ago");
                } else {
                    PeerProfile prof = _context.profileOrganizer().getProfile(entry);
                    if (prof != null && now - prof.getLastSendFailed() < 5*60*1000L) {
                        badff.add(entry);
                        if (_log.shouldDebug())
                            _log.debug("Floodfill sort: Skipping [" + entry.toBase64().substring(0,6) + "] -> Poor send success rate for the last 5m");
                    } else {
                        rv.add(entry);
                        found++;
                    }
                }
            }
            // Put the "bad" floodfills at the end of the floodfills but before the kademlias
            for (int i = 0; found < howMany && i < badff.size(); i++) {
                rv.add(badff.get(i));
                found++;
            }
            // are we corrupting _sorted here?
            for (int i = rv.size(); i < howMany; i++) {
                if (_sorted.isEmpty())
                    break;
                Hash entry = _sorted.first();
                rv.add(entry);
                _sorted.remove(entry);
            }
            return rv;
        }

        /**
         *  Look up the RouterInfo for an entry, caching the result.
         *  Null results are cached too, so unknown peers are not re-looked-up.
         *
         *  @param entry the router hash
         *  @return the RouterInfo, or null if not found
         */
        private RouterInfo cachedInfo(Hash entry) {
            if (_infoCache.containsKey(entry)) {return _infoCache.get(entry);}
            RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(entry);
            _infoCache.put(entry, info);
            return info;
        }
        /**
         * Number of entries collected so far.
         *
         * @return the match count
         */
        public int size() { return _matches; }
    }

    /**
     * Floodfill peers only. Used only by HandleDatabaseLookupMessageJob to populate the DSRM.
     * UNLESS peersToIgnore contains Hash.FAKE_HASH (all zeros), in which case this is an exploratory
     * lookup, and the response should not include floodfills.
     * List MAY INCLUDE our own router - add to peersToIgnore if you don't want
     *
     * @param key the original key (NOT the routing key)
     * @param peersToIgnore can be null
     * @return List of Hash for the peers selected, ordered
     */
    @Override
    List<Hash> selectNearest(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet<Hash> kbuckets) {
        Hash rkey = _context.routingKeyGenerator().getRoutingKey(key);
        if (peersToIgnore != null && peersToIgnore.contains(Hash.FAKE_HASH)) {
            Set<Hash> ignore = new HashSet<>(peersToIgnore);
            ignore.addAll(selectFloodfillParticipants(ignore, kbuckets));
            // TODO this is very slow
            FloodfillSelectionCollector matches = new FloodfillSelectionCollector(rkey, ignore, maxNumRouters);
            kbuckets.getAll(matches);
            return matches.get(maxNumRouters);
        } else {
            return selectFloodfillParticipantsIncludingUs(rkey, maxNumRouters, peersToIgnore, kbuckets);
        }
    }

    /**
     * Extract IP:port from RouterInfo.
     * @return the i p from router info
     */
    private String getIPFromRouterInfo(RouterInfo ri) {
        if (ri == null) return null;
        try {
            for (RouterAddress ra : ri.getAddresses()) {
                if (ra != null) {
                    String host = ra.getHost();
                    int port = ra.getPort();
                    if (host != null && port > 0) {
                        return host + ":" + port;
                    }
                }
            }
        } catch (Exception e) { /* ignored */ }
        return null;
    }
}
