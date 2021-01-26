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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.kademlia.SelectionCollector;
import net.i2p.kademlia.XORComparator;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.router.util.RandomIterator;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 *  This is where we implement semi-Kademlia with the floodfills, by
 *  selecting floodfills closest to a given key for
 *  searches and stores.
 *
 *  Warning - most methods taking a key as an argument require the
 *            routing key, not the original key.
 *
 */
class FloodfillPeerSelector extends PeerSelector {

    public FloodfillPeerSelector(RouterContext ctx) {
        super(ctx);
    }

    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     * Puts the floodfill peers that are directly connected first in the list.
     * List will not include our own hash.
     * Returns new list, may be modified.
     *
     * @param key the ROUTING key (NOT the original key)
     * @param peersToIgnore can be null
     * @return List of Hash for the peers selected
     */
    @Override
    List<Hash> selectMostReliablePeers(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet<Hash> kbuckets) {
        return selectNearestExplicitThin(key, maxNumRouters, peersToIgnore, kbuckets, true);
    }

    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     * Does not prefer the floodfill peers that are directly connected.
     * List will not include our own hash.
     * Returns new list, may be modified.
     *
     * @param key the ROUTING key (NOT the original key)
     * @param peersToIgnore can be null
     * @return List of Hash for the peers selected
     */
    @Override
    List<Hash> selectNearestExplicitThin(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet<Hash> kbuckets) {
        return selectNearestExplicitThin(key, maxNumRouters, peersToIgnore, kbuckets, false);
    }

    /**
     * Pick out peers with the floodfill capacity set, returning them first, but then
     * after they're complete, sort via kademlia.
     * List will not include our own hash.
     * Returns new list, may be modified.
     *
     * @param key the ROUTING key (NOT the original key)
     * @param peersToIgnore can be null
     * @return List of Hash for the peers selected
     */
    List<Hash> selectNearestExplicitThin(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, KBucketSet<Hash> kbuckets, boolean preferConnected) {
        if (peersToIgnore == null)
            peersToIgnore = Collections.singleton(_context.routerHash());
        else
            peersToIgnore.add(_context.routerHash());
        // TODO this is very slow
        FloodfillSelectionCollector matches = new FloodfillSelectionCollector(key, peersToIgnore, maxNumRouters);
        if (kbuckets == null) return new ArrayList<Hash>();
        kbuckets.getAll(matches);
        List<Hash> rv = matches.get(maxNumRouters, preferConnected);
        StringBuilder buf = new StringBuilder();
        buf.append("Searching for " + maxNumRouters + " peers close to [" + key.toBase64().substring(0,6) + "]");
        buf.append("\n* All Hashes: " + matches.size());
        buf.append("\n* Ignoring: ");
        for (Hash h : peersToIgnore) {
            buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
        }
        buf.append("\n* Matched: ");
        for (Hash h : rv) {
            buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(buf.toString());

//            _log.debug("Searching for " + maxNumRouters + " peers close to [" + key.toBase64().substring(0,6) + "]"
//                       + "\n* All Hashes: " + matches.size()
//                       + "\n* Ignoring: " + peersToIgnore
//                       + "\n* Matched: " + rv); //, new Exception("Search by"));
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
     *  @param kbuckets now unused
     *  @param toIgnore can be null
     *  @return all floodfills not banlisted forever.
     */
    private List<Hash> selectFloodfillParticipants(Set<Hash> toIgnore, KBucketSet<Hash> kbuckets) {
      /*****
        if (kbuckets == null) return Collections.EMPTY_LIST;
        // TODO this is very slow - use profile getPeersByCapability('f') instead
        _context.statManager().addRateData("netDb.newFSC", 0, 0);
        FloodfillSelectionCollector matches = new FloodfillSelectionCollector(null, toIgnore, 0);
        kbuckets.getAll(matches);
        return matches.getFloodfillParticipants();
      *****/
        Set<Hash> set = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
        List<Hash> rv = new ArrayList<Hash>(set.size());
        for (Hash h : set) {
            if ((toIgnore != null && toIgnore.contains(h)) ||
                _context.banlist().isBanlistedForever(h))
               continue;
            rv.add(h);
        }
        return rv;
    }

    /**
     *  Sort the floodfills. The challenge here is to keep the good ones
     *  at the front and the bad ones at the back. If they are all good or bad,
     *  searches and stores won't work well.
     *  List will not include our own hash.
     *  Returns new list, may be modified.
     *
     *  @return floodfills closest to the key that are not banlisted forever
     *  @param key the ROUTING key (NOT the original key)
     *  @param maxNumRouters max to return
     *  @param kbuckets now unused
     *
     *  Sorted by closest to the key if &gt; maxNumRouters, otherwise not
     *  The list is in 3 groups - sorted by routing key within each group.
     *  Group 1: No store or lookup failure in a long time, and
    *            lookup fail rate no more than 1.5 * average
     *  Group 2: No store or lookup failure in a little while or
     *           success newer than failure
     *  Group 3: All others
     */
    List<Hash> selectFloodfillParticipants(Hash key, int maxNumRouters, KBucketSet<Hash> kbuckets) {
        Set<Hash> ignore = Collections.singleton(_context.routerHash());
        return selectFloodfillParticipants(key, maxNumRouters, ignore, kbuckets);
    }

    /** .5 * PublishLocalRouterInfoJob.PUBLISH_DELAY */
    private static final int NO_FAIL_STORE_OK = 10*60*1000;
    private static final int NO_FAIL_STORE_GOOD = NO_FAIL_STORE_OK * 2;
    /** this must be longer than the max streaming timeout (60s) */
    private static final int NO_FAIL_LOOKUP_OK = 75*1000;
    private static final int NO_FAIL_LOOKUP_GOOD = NO_FAIL_LOOKUP_OK * 3;
    private static final int MAX_GOOD_RESP_TIME = 5*1000;
    // TODO we need better tracking of floodfill first-heard-about times
    // before we can do this. Old profiles get deleted.
    //private static final long HEARD_AGE = 48*60*60*1000L;
    //private static final long HEARD_AGE = 60*60*1000L;
    private static final long HEARD_AGE = 3*60*60*1000L;
    private static final long INSTALL_AGE = HEARD_AGE + (60*60*1000L);

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
        if (toIgnore == null) {
            toIgnore = Collections.singleton(_context.routerHash());
        } else if (!toIgnore.contains(_context.routerHash())) {
            // copy the Set so we don't confuse StoreJob
            toIgnore = new HashSet<Hash>(toIgnore);
            toIgnore.add(_context.routerHash());
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
        Collections.sort(sorted, new XORComparator<Hash>(key));

        int found = 0;
        long now = _context.clock().now();
        long installed = _context.getProperty("router.firstInstalled", 0L);
        boolean enforceHeard = installed > 0 && (now - installed) > INSTALL_AGE;

        double maxFailRate = 100;
        if (_context.router().getUptime() > 60*60*1000) {
            RateStat rs = _context.statManager().getRate("peer.failedLookupRate");
            if (rs != null) {
                Rate r = rs.getRate(60*60*1000);
                if (r != null) {
                    double currentFailRate = r.getAverageValue();
                    maxFailRate = Math.max(0.20d, 1.5d * currentFailRate);
                }
            }
        }

        // 5 == FNDF.MAX_TO_FLOOD + 1
        int limit = Math.max(5, howMany + 2);
        limit = Math.min(limit, sorted.size());
        MaskedIPSet maskedIPs = new MaskedIPSet(limit * 3);
        // split sorted list into 3 sorted lists
        List<Hash> rv = new ArrayList<Hash>(howMany);
        List<Hash> okff = new ArrayList<Hash>(limit);
        List<Hash> badff = new ArrayList<Hash>(limit);
        for (int i = 0; found < howMany && i < limit; i++) {
            Hash entry = sorted.get(i);
            if (entry == null)
                break;  // shouldn't happen
            // put anybody in the same /16 at the end
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(entry);
            MaskedIPSet entryIPs = new MaskedIPSet(_context, entry, info, 2);
            boolean sameIP = false;
            for (String ip : entryIPs) {
                if (!maskedIPs.add(ip))
                    sameIP = true;
            }
            if (sameIP) {
                badff.add(entry);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Floodfill Sort: Same /16, family, or port [" + entry.toBase64().substring(0,6) + "]");
            } else if (info != null && now - info.getPublished() > 3*60*60*1000) {
                badff.add(entry);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Floodfill Sort: Old [" + entry.toBase64().substring(0,6) + "]");
            } else if (info != null && _context.commSystem().isInStrictCountry(info)) {
                badff.add(entry);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Floodfill Sort: Bad country [" + entry.toBase64().substring(0,6) + "]");
            } else if (info != null && info.getBandwidthTier().equals("L")) {
                badff.add(entry);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Floodfill Sort: Slow [" + entry.toBase64().substring(0,6) + "]");
            } else {
                PeerProfile prof = _context.profileOrganizer().getProfile(entry);
                double maxGoodRespTime = MAX_GOOD_RESP_TIME;
                RateStat ttst = _context.statManager().getRate("tunnel.testSuccessTime");
                if (ttst != null) {
                    Rate tunnelTestTime = ttst.getRate(10*60*1000);
                    if (tunnelTestTime != null && tunnelTestTime.getAverageValue() > 500)
                        maxGoodRespTime = 2 * tunnelTestTime.getAverageValue();
                }
                if (prof != null) {
                    if (enforceHeard && prof.getFirstHeardAbout() > now - HEARD_AGE) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Floodfill Sort: Bad (too new) [" + entry.toBase64().substring(0,6) + "]");
                        badff.add(entry);
                    } else if (prof.getDBHistory() != null) {
//                        if (prof.getDbResponseTime().getRate(10*60*1000).getAverageValue() < maxGoodRespTime
                        if (prof.getDbResponseTime().getRate(60*60*1000).getAverageValue() < maxGoodRespTime
                            && prof.getDBHistory().getLastStoreFailed() < now - NO_FAIL_STORE_GOOD
                            && prof.getDBHistory().getLastLookupFailed() < now - NO_FAIL_LOOKUP_GOOD
                            && prof.getDBHistory().getFailedLookupRate().getRate(60*60*1000).getAverageValue() < maxFailRate) {
                            // good
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Floodfill Sort: Good [" + entry.toBase64().substring(0,6) + "]");
                            rv.add(entry);
                            found++;
                        } else if (prof.getDBHistory().getLastStoreFailed() <= prof.getDBHistory().getLastStoreSuccessful()
                                   || prof.getDBHistory().getLastLookupFailed() <= prof.getDBHistory().getLastLookupSuccessful()
                                   || (prof.getDBHistory().getLastStoreFailed() < now - NO_FAIL_STORE_OK
                                       && prof.getDBHistory().getLastLookupFailed() < now - NO_FAIL_LOOKUP_OK)) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Floodfill Sort: OK [" + entry.toBase64().substring(0,6) + "]");
                            okff.add(entry);
                        } else {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Floodfill Sort: Bad (DB) [" + entry.toBase64().substring(0,6) + "]");
                            badff.add(entry);
                        }
                    } else {
                        // no DBHistory
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Floodfill Sort: Bad (no DB history) [" + entry.toBase64().substring(0,6) + "]");
                        badff.add(entry);
                    }
                } else {
                    // no profile
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Floodfill Sort: Bad (no profile) [" + entry.toBase64().substring(0,6) + "]");
                    badff.add(entry);
                }
            }
        }
        StringBuilder buf = new StringBuilder();
        buf.append("Floodfill Sort");
        if (!rv.isEmpty()) {
            buf.append("\n* Good: ");
            for (Hash h : rv) {
                buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
            }
        }
        if (!okff.isEmpty()) {
            buf.append("\n* OK: ");
            for (Hash h : okff) {
                buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
            }
        }
        if (!badff.isEmpty()) {
            buf.append("\n* Bad: ");
            for (Hash h : badff) {
                buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
            }
        }
        if (_log.shouldLog(Log.INFO))
//            _log.info("Floodfill Sort\n* Good: " + rv + "\n* OK: " + okff + "\n* Bad: " + badff);
            _log.info(buf.toString());

        // Put the ok floodfills after the good floodfills
        for (int i = 0; found < howMany && i < okff.size(); i++) {
            rv.add(okff.get(i));
            found++;
        }
        // Put the "bad" floodfills after the ok floodfills
        for (int i = 0; found < howMany && i < badff.size(); i++) {
            rv.add(badff.get(i));
            found++;
        }

        return rv;
    }

    private class FloodfillSelectionCollector implements SelectionCollector<Hash> {
        private final TreeSet<Hash> _sorted;
        private final List<Hash>  _floodfillMatches;
        private final Hash _key;
        private final Set<Hash> _toIgnore;
        private int _matches;
        private final int _wanted;

        /**
         *  Warning - may return our router hash - add to toIgnore if necessary
         *  @param key the ROUTING key (NOT the original key)
         *  @param toIgnore can be null
         */
        public FloodfillSelectionCollector(Hash key, Set<Hash> toIgnore, int wanted) {
            _key = key;
            _sorted = new TreeSet<Hash>(new XORComparator<Hash>(key));
            _floodfillMatches = new ArrayList<Hash>(8);
            _toIgnore = toIgnore;
            _wanted = wanted;
        }

        private static final int EXTRA_MATCHES = 100;
        public void add(Hash entry) {
            //if (_context.profileOrganizer().isFailing(entry))
            //    return;
            if ( (_toIgnore != null) && (_toIgnore.contains(entry)) )
                return;
            //if (entry.equals(_context.routerHash()))
            //    return;
            // it isn't direct, so who cares if they're banlisted
            //if (_context.banlist().isBanlisted(entry))
            //    return;
            // ... unless they are really bad
            if (_context.banlist().isBanlistedForever(entry))
                return;
            RouterInfo info = _context.netDb().lookupRouterInfoLocally(entry);
            //if (info == null)
            //    return;

            if (info != null && FloodfillNetworkDatabaseFacade.isFloodfill(info)) {
                _floodfillMatches.add(entry);
            } else {
                // This didn't really work because we stopped filling up when _wanted == _matches,
                // thus we don't add and sort the whole db to find the closest.
                // So we keep going for a while. This, together with periodically shuffling the
                // KBucket (see KBucketImpl.add()) makes exploration work well.
                if ( (!SearchJob.onlyQueryFloodfillPeers(_context)) && (_wanted + EXTRA_MATCHES > _matches) && (_key != null) ) {
                    _sorted.add(entry);
                } else {
                    return;
                }
            }
            _matches++;
        }
        /** get the first $howMany entries matching */
        public List<Hash> get(int howMany) {
            return get(howMany, false);
        }

        /**
         *  @return list of all with the 'f' mark in their NetDb except for banlisted ones.
         *  Will return non-floodfills only if there aren't enough floodfills.
         *
         *  The list is in 3 groups - unsorted (shuffled) within each group.
         *  Group 1: If preferConnected = true, the peers we are directly
         *           connected to, that meet the group 2 criteria
         *  Group 2: NetDb published less than 3h ago, no bad send in last 30m.
         *  Group 3: All others
         *  Group 4: Non-floodfills, sorted by closest-to-the-key
         */
        public List<Hash> get(int howMany, boolean preferConnected) {
            List<Hash> rv = new ArrayList<Hash>(howMany);
            List<Hash> badff = new ArrayList<Hash>(howMany);
            List<Hash> unconnectedff = new ArrayList<Hash>(howMany);
            int found = 0;
            long now = _context.clock().now();
            // Only add in "good" floodfills here...
            // Let's say published in last 3h and no failed sends in last 30m
            // (Forever banlisted ones are excluded in add() above)
            for (Iterator<Hash> iter = new RandomIterator<Hash>(_floodfillMatches); (found < howMany) && iter.hasNext(); ) {
                Hash entry = iter.next();
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(entry);
                if (info != null && now - info.getPublished() > 3*60*60*1000) {
                    badff.add(entry);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Floodfill Sort: Skipping [" + entry.toBase64().substring(0,6) + "] - Published over 3 hours ago");
                } else {
                    PeerProfile prof = _context.profileOrganizer().getProfile(entry);
                    if (prof != null && now - prof.getLastSendFailed() < 30*60*1000) {
                        badff.add(entry);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Floodfill Sort: Skipping [" + entry.toBase64().substring(0,6) + "] - Recent failed send");
                    } else if (preferConnected && !_context.commSystem().isEstablished(entry)) {
                        unconnectedff.add(entry);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Floodfill Sort: Skipping [" + entry.toBase64().substring(0,6) + "] - Not connected");
                    } else {
                        rv.add(entry);
                        found++;
                    }
                }
            }
            // Put the unconnected floodfills after the connected floodfills
            for (int i = 0; found < howMany && i < unconnectedff.size(); i++) {
                rv.add(unconnectedff.get(i));
                found++;
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
            // return non-ff
            peersToIgnore.addAll(selectFloodfillParticipants(peersToIgnore, kbuckets));
            // TODO this is very slow
            FloodfillSelectionCollector matches = new FloodfillSelectionCollector(rkey, peersToIgnore, maxNumRouters);
            kbuckets.getAll(matches);
            return matches.get(maxNumRouters);
        } else {
            // return ff
            return selectFloodfillParticipantsIncludingUs(rkey, maxNumRouters, peersToIgnore, kbuckets);
        }
    }
}
