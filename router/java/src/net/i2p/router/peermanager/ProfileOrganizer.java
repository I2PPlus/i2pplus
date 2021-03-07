package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.pool.TunnelPeerSelector;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.router.util.RandomIterator;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;

/**
 * Keep the peer profiles organized according to the tiered model.  This does not
 * actively update anything - the reorganize() method should be called periodically
 * to recalculate thresholds and move profiles into the appropriate tiers, and addProfile()
 * should be used to add new profiles (placing them into the appropriate groupings).
 */
public class ProfileOrganizer {
    private final Log _log;
    private final RouterContext _context;
    /** H(routerIdentity) to PeerProfile for all peers that are fast and high capacity*/
    private final Map<Hash, PeerProfile> _fastPeers;
    /** H(routerIdentity) to PeerProfile for all peers that have high capacities */
    private final Map<Hash, PeerProfile> _highCapacityPeers;
    /** TO BE REMOVED H(routerIdentity) to PeerProfile for all peers that well integrated into the network and not failing horribly */
    private final Map<Hash, PeerProfile> _wellIntegratedPeers;
    /** H(routerIdentity) to PeerProfile for all peers that are not failing horribly */
    private final Map<Hash, PeerProfile> _notFailingPeers;
    /** H(routerIdnetity), containing elements in _notFailingPeers */
    private final List<Hash> _notFailingPeersList;
    /** TO BE REMOVED H(routerIdentity) to PeerProfile for all peers that ARE failing horribly (but that we haven't dropped reference to yet) */
    private final Map<Hash, PeerProfile> _failingPeers;
    /** who are we? */
    private Hash _us;
    private final ProfilePersistenceHelper _persistenceHelper;

    /** PeerProfile objects for all peers profiled, orderd by the ones with the highest capacity first */
    private Set<PeerProfile> _strictCapacityOrder;

    /** threshold speed value, seperating fast from slow */
    private double _thresholdSpeedValue;
    /** threshold reliability value, seperating reliable from unreliable */
    private double _thresholdCapacityValue;
    /** integration value, seperating well integrated from not well integrated */
    private double _thresholdIntegrationValue;

    private final InverseCapacityComparator _comp;

    /**
     * Defines the minimum number of 'fast' peers that the organizer should select.  See
     * {@link ProfileOrganizer#getMinimumFastPeers}
     *
     */
    public static final String PROP_MINIMUM_FAST_PEERS = "profileOrganizer.minFastPeers";
//    public static final int DEFAULT_MINIMUM_FAST_PEERS = 8;
    public static final int DEFAULT_MINIMUM_FAST_PEERS = 150;
    /** this is misnamed, it is really the max minimum number. */
//    private static final int DEFAULT_MAXIMUM_FAST_PEERS = 40;
    private static final int DEFAULT_MAXIMUM_FAST_PEERS = 250;
//    private static final int ABSOLUTE_MAX_FAST_PEERS = 75;
    private static final int ABSOLUTE_MAX_FAST_PEERS = 350;

//    private final int known = net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade.getKnownRouters();

    /**
     * Defines the minimum number of 'high capacity' peers that the organizer should
     * select when using the mean - if less than this many are available, select the
     * capacity by the median.
     *
     */
    public static final String PROP_MINIMUM_HIGH_CAPACITY_PEERS = "profileOrganizer.minHighCapacityPeers";
//    public static final int DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS = 25;
    public static final int DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS = 200;
//    private static final int ABSOLUTE_MAX_HIGHCAP_PEERS = 150;
    private static final int ABSOLUTE_MAX_HIGHCAP_PEERS = 400;

    /** synchronized against this lock when updating the tier that peers are located in (and when fetching them from a peer) */
    private final ReentrantReadWriteLock _reorganizeLock = new ReentrantReadWriteLock(false);

    public ProfileOrganizer(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(ProfileOrganizer.class);
        _comp = new InverseCapacityComparator();
        _fastPeers = new HashMap<Hash, PeerProfile>(32);
        _highCapacityPeers = new HashMap<Hash, PeerProfile>(64);
        _wellIntegratedPeers = new HashMap<Hash, PeerProfile>(128);
        _notFailingPeers = new HashMap<Hash, PeerProfile>(256);
        _notFailingPeersList = new ArrayList<Hash>(256);
        _failingPeers = new HashMap<Hash, PeerProfile>(16);
        _strictCapacityOrder = new TreeSet<PeerProfile>(_comp);
        _persistenceHelper = new ProfilePersistenceHelper(_context);

        _context.statManager().createRequiredRateStat("peer.profileSortTime", "Time to sort peers (ms)", "Peers", new long[] { 60*1000, 60*60*1000 });
        _context.statManager().createRateStat("peer.profileCoalesceTime", "Time to coalesce peer stats (ms)", "Peers", new long[] { 60*1000, 60*60*1000 });
        _context.statManager().createRateStat("peer.profileThresholdTime", "Time to determine tier thresholds (ms)", "Peers", new long[] { 60*1000, 60*60*1000 });
        _context.statManager().createRateStat("peer.profilePlaceTime", "Time to sort peers into tiers (ms)", "Peers", new long[] { 60*1000, 60*60*1000 });
        _context.statManager().createRateStat("peer.profileReorgTime", "Time to reorganize peers (ms)", "Peers", new long[] { 60*1000, 60*60*1000 });
        // used in DBHistory
        _context.statManager().createRequiredRateStat("peer.failedLookupRate", "NetDb Lookup failure rate", "Peers", new long[] { 60*1000, 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
    }

    private void getReadLock() {
        _reorganizeLock.readLock().lock();
    }

    /**
     *  Get the lock if we can. Non-blocking.
     *  @return true if the lock was acquired
     *  @since 0.8.12
     */
    private boolean tryReadLock() {
        return _reorganizeLock.readLock().tryLock();
    }

    private void releaseReadLock() {
        _reorganizeLock.readLock().unlock();
    }

    /**
     *  Get the lock if we can. Non-blocking.
     *  @return true if the lock was acquired
     *  @since 0.9.47
     */
    private boolean tryWriteLock() {
        return _reorganizeLock.writeLock().tryLock();
    }

    /** @return true if the lock was acquired */
    private boolean getWriteLock() {
        try {
            boolean rv = _reorganizeLock.writeLock().tryLock(3000, TimeUnit.MILLISECONDS);
            if ((!rv) && _log.shouldLog(Log.WARN))
                _log.warn("No lock, size is: " + _reorganizeLock.getQueueLength(), new Exception("rats"));
            return rv;
        } catch (InterruptedException ie) {}
        return false;
    }

    private void releaseWriteLock() {
        _reorganizeLock.writeLock().unlock();
    }

    public void setUs(Hash us) { _us = us; }
    public Hash getUs() { return _us; }

    public double getSpeedThreshold() { return _thresholdSpeedValue; }
    public double getCapacityThreshold() { return _thresholdCapacityValue; }
    public double getIntegrationThreshold() { return _thresholdIntegrationValue; }

    /**
     * Retrieve the profile for the given peer, if one exists (else null).
     * Blocking if a reorganize is happening.
     */
    public PeerProfile getProfile(Hash peer) {
        if (peer.equals(_us)) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Retrieved our own profile for the Profile Manager");
            return null;
        }
        getReadLock();
        try {
            return locked_getProfile(peer);
        } finally { releaseReadLock(); }
    }

    /**
     * Retrieve the profile for the given peer, if one exists (else null).
     * Non-blocking. Returns null if a reorganize is happening.
     * @since 0.8.12
     */
    public PeerProfile getProfileNonblocking(Hash peer) {
        if (peer.equals(_us)) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Retrieved our own profile for the Profile Manager");
            return null;
        }
        if (tryReadLock()) {
            try {
                return locked_getProfile(peer);
            } finally { releaseReadLock(); }
        }
        return null;
    }

    /**
     * Retrieve the profile for the given peer, if one exists.
     * If it does not exist and it can get the lock, it will create and return a new profile.
     * Non-blocking. Returns null if a reorganize is happening.
     * @since 0.9.47
     */
    PeerProfile getOrCreateProfileNonblocking(Hash peer) {
        if (peer.equals(_us)) {
            if (_log.shouldDebug())
                _log.debug("Retrieved our own profile for the Profile Manager");
            return null;
        }
        if (!tryReadLock())
            return null;
        PeerProfile rv;
        try {
            rv = locked_getProfile(peer);
        } finally { releaseReadLock(); }
        if (rv != null)
            return rv;
        rv = new PeerProfile(_context, peer);
        rv.coalesceStats();
        if (!tryWriteLock())
            return null;
        try {
            // double check
            PeerProfile old = locked_getProfile(peer);
            if (old != null)
                return old;
            _notFailingPeers.put(peer, rv);
            _notFailingPeersList.add(peer);
            // Add to high cap only if we have room. Don't add to Fast; wait for reorg.
            int minHighCap = _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS);
            int minFast = _context.getProperty(PROP_MINIMUM_FAST_PEERS, DEFAULT_MINIMUM_FAST_PEERS);
            if ((_thresholdCapacityValue <= rv.getCapacityValue() && isSelectable(peer) &&
                countHighCapacityPeers() < getMaximumHighCapPeers()) || countHighCapacityPeers() < minHighCap)
                _highCapacityPeers.put(peer, rv);
            if (countFastPeers() < minFast)
                _fastPeers.put(peer, rv);
            _strictCapacityOrder.add(rv);
        } finally { releaseWriteLock(); }
        return rv;
    }

    /**
     * Add the new profile, returning the old value (or null if no profile existed)
     *
     */
    public PeerProfile addProfile(PeerProfile profile) {
        if (profile == null) return null;

        Hash peer = profile.getPeer();
        if (peer.equals(_us)) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Added our own profile to Profile Manager");
            return null;
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("New profile created for [" + peer.toBase64().substring(0,6) + "]");

        PeerProfile old = getProfile(peer);
        profile.coalesceStats();
        if (!getWriteLock())
            return old;
        try {
            // Don't do this, as it may substantially exceed
            // the high cap and fast limits in-between reorganizations.
            // just add to the not-failing tier, and maybe the high cap tier,
            // it will get promoted in the next reorganization
            // if appropriate. This lessens high-cap churn.
            // The downside is that new peers don't become high cap until the next reorg
            // if we are at our limit.
            //locked_placeProfile(profile);
            _notFailingPeers.put(peer, profile);
            if (old == null)
                _notFailingPeersList.add(peer);
            RouterInfo peerInfo = _context.netDb().lookupRouterInfoLocally(peer);
            String bw = peerInfo.getBandwidthTier();
            String cap = peerInfo.getCapabilities();
            PeerProfile prof = getProfile(peer);
            boolean reachable = cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
            // Add to high cap only if we have room. Don't add to Fast; wait for reorg.
            if (peerInfo != null && cap != null && (!reachable || bw.equals("K") || bw.equals("L") || bw.equals("M"))) {
                prof.setCapacityBonus(-30);
                _highCapacityPeers.remove(peer, profile);
                if (_log.shouldLog(Log.INFO))
                    _log.info("[" + peer.toBase64().substring(0,6) + "] evicted from high cap group -> K, L, M or Unreachable");
            }
            if (_thresholdCapacityValue <= profile.getCapacityValue() && isSelectable(peer) &&
                _highCapacityPeers.size() < getMaximumHighCapPeers()) {
                    if (peerInfo != null && cap != null && reachable && !bw.equals("K") && !bw.equals("L") && !bw.equals("M"))
                        _highCapacityPeers.put(peer, profile);
            }
            _strictCapacityOrder.add(profile);
        } finally { releaseWriteLock(); }
        return old;
    }

    private int count(Map<Hash, PeerProfile> m) {
        getReadLock();
        try {
            return m.size();
        } finally { releaseReadLock(); }
    }

    public int countFastPeers() { return count(_fastPeers); }
    public int countHighCapacityPeers() { return count(_highCapacityPeers); }
    /** @deprecated use ProfileManager.getPeersByCapability('f').size() */
    @Deprecated
    public int countWellIntegratedPeers() { return count(_wellIntegratedPeers); }
    public int countNotFailingPeers() { return count(_notFailingPeers); }
    public int countFailingPeers() { return count(_failingPeers); }

    public int countActivePeers() {
        int activePeers = 0;
        long hideBefore = _context.clock().now() - 5*60*1000;

        getReadLock();
        try {
            for (PeerProfile profile : _failingPeers.values()) {
                if (profile.getLastSendSuccessful() >= hideBefore)
                    activePeers++;
                else if (profile.getLastHeardFrom() >= hideBefore)
                    activePeers++;
            }
            for (PeerProfile profile : _notFailingPeers.values()) {
                if (profile.getLastSendSuccessful() >= hideBefore)
                    activePeers++;
                else if (profile.getLastHeardFrom() >= hideBefore)
                    activePeers++;
            }
        } finally { releaseReadLock(); }
        return activePeers;
    }

    private boolean isX(Map<Hash, PeerProfile> m, Hash peer) {
        getReadLock();
        try {
            return m.containsKey(peer);
        } finally { releaseReadLock(); }
    }

    public boolean isFast(Hash peer) { return isX(_fastPeers, peer); }
    public boolean isHighCapacity(Hash peer) { return isX(_highCapacityPeers, peer); }
    public boolean isWellIntegrated(Hash peer) { return isX(_wellIntegratedPeers, peer); }

    /**
     *  Deprecated for now, always false
     */
    public boolean isFailing(Hash peer) {
        // Always false so skip the lock
        //return isX(_failingPeers, peer);
        return false;
    }

    /** @since 0.8.8 */
    void clearProfiles() {
        if (!getWriteLock())
            return;
        try {
            _failingPeers.clear();
            _fastPeers.clear();
            _highCapacityPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();
            _wellIntegratedPeers.clear();
            _strictCapacityOrder.clear();
        } finally { releaseWriteLock(); }
    }

    /**
     * if a peer sends us more than 5 replies in a searchReply that we cannot
     * fetch, stop listening to them.
     *
     */
//    private final static int MAX_BAD_REPLIES_PER_HOUR = 5;
    private final static int MAX_BAD_REPLIES_PER_HOUR = 8; // increase value to factor in old peers marked as invalid

    /**
     * Does the given peer send us bad replies - either invalid store messages
     * (expired, corrupt, etc) or unreachable replies (pointing towards routers
     * that don't exist).
     *
     */
    public boolean peerSendsBadReplies(Hash peer) {
        PeerProfile profile = getProfile(peer);
        if (profile != null && profile.getIsExpandedDB()) {
            RateStat invalidReplyRateStat = profile.getDBHistory().getInvalidReplyRate();
//            Rate invalidReplyRate = invalidReplyRateStat.getRate(30*60*1000l);
            Rate invalidReplyRate = invalidReplyRateStat.getRate(60*60*1000l);
            if ( (invalidReplyRate.getCurrentTotalValue() > MAX_BAD_REPLIES_PER_HOUR) ||
                 (invalidReplyRate.getLastTotalValue() > MAX_BAD_REPLIES_PER_HOUR) ) {
                return true;
            }
        }
        return false;
    }

    /**
     *  @return true if successful, false if not found
     */
    public boolean exportProfile(Hash profile, OutputStream out) throws IOException {
        PeerProfile prof = getProfile(profile);
        boolean rv = prof != null;
        if (rv)
            _persistenceHelper.writeProfile(prof, out);
        return rv;
    }

    /**
     * Return a set of Hashes for peers that are both fast and reliable.  If an insufficient
     * number of peers are both fast and reliable, fall back onto high capacity peers, and if that
     * doesn't contain sufficient peers, fall back onto not failing peers, and even THAT doesn't
     * have sufficient peers, fall back onto failing peers.
     *
     * @param howMany how many peers are desired
     * @param exclude set of Hashes for routers that we don't want selected
     * @param matches set to store the return value in
     *
     */
    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectFastPeers(howMany, exclude, matches, 0);
    }

    /**
     * Return a set of Hashes for peers that are both fast and reliable.  If an insufficient
     * number of peers are both fast and reliable, fall back onto high capacity peers, and if that
     * doesn't contain sufficient peers, fall back onto not failing peers, and even THAT doesn't
     * have sufficient peers, fall back onto failing peers.
     *
     * @param howMany how many peers are desired
     * @param exclude set of Hashes for routers that we don't want selected
     * @param matches set to store the return value in
     * @param mask 0-4 Number of bytes to match to determine if peers in the same IP range should
     *             not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     *
     */
    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask) {
        getReadLock();
        try {
            locked_selectPeers(_fastPeers, howMany, exclude, matches, mask);
        } finally { releaseReadLock(); }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.DEBUG))
                if (howMany != 1)
                _log.debug("Need " + howMany + " Fast peers for tunnel build; " + matches.size() + " found - selecting remainder from High Capacity tier");
                else
                _log.debug("Need " + howMany + " Fast peer for tunnel build; " + matches.size() + " found - selecting remainder from High Capacity tier");
            selectHighCapacityPeers(howMany, exclude, matches, mask);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                if (howMany != 1)
                   _log.debug(howMany + " Fast peers selected for tunnel build");
                else
                    _log.debug(howMany + " Fast peer selected for tunnel build");
        }
        return;
    }

    /**
     *  Replaces integer subTierMode argument, for clarity
     *
     *  @since 0.9.18
     */
    public enum Slice {

        SLICE_ALL(0x00, 0),
        SLICE_0_1(0x02, 0),
        SLICE_2_3(0x02, 2),
        SLICE_0(0x03, 0),
        SLICE_1(0x03, 1),
        SLICE_2(0x03, 2),
        SLICE_3(0x03, 3);

        final int mask, val;

        Slice(int mask, int val) {
            this.mask = mask;
            this.val = val;
        }
    }

    /**
     * Return a set of Hashes for peers that are both fast and reliable.  If an insufficient
     * number of peers are both fast and reliable, fall back onto high capacity peers, and if that
     * doesn't contain sufficient peers, fall back onto not failing peers, and even THAT doesn't
     * have sufficient peers, fall back onto failing peers.
     *
     * @param howMany how many peers are desired
     * @param exclude set of Hashes for routers that we don't want selected
     * @param matches set to store the return value in
     * @param randomKey used for deterministic random partitioning into subtiers
     * @param subTierMode 0 or 2-7:
     *<pre>
     *    0: no partitioning, use entire tier
     *    2: return only from group 0 or 1
     *    3: return only from group 2 or 3
     *    4: return only from group 0
     *    5: return only from group 1
     *    6: return only from group 2
     *    7: return only from group 3
     *</pre>
     */
    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, Hash randomKey, Slice subTierMode) {
        getReadLock();
        try {
            if (subTierMode != Slice.SLICE_ALL) {
                int sz = _fastPeers.size();
                if (sz < 6 || (subTierMode.mask >= 3 && sz < 12))
                    subTierMode = Slice.SLICE_ALL;
            }
            if (subTierMode != Slice.SLICE_ALL)
                locked_selectPeers(_fastPeers, howMany, exclude, matches, randomKey, subTierMode);
            else
                locked_selectPeers(_fastPeers, howMany, exclude, matches, 2);
        } finally { releaseReadLock(); }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Need " + howMany + " Fast peers for tunnel build; " + matches.size() + " found - selecting remainder from High Capacity peers");
            selectHighCapacityPeers(howMany, exclude, matches, 2);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(howMany + " Fast peers selected for tunnel build");
        }
        return;
    }

    /**
     * Return a set of Hashes for peers that have a high capacity
     *
     */
    public void selectHighCapacityPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectHighCapacityPeers(howMany, exclude, matches, 0);
    }

    /**
     * @param mask 0-4 Number of bytes to match to determine if peers in the same IP range should
     *             not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     */
    public void selectHighCapacityPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask) {
        getReadLock();
        try {
            // we only use selectHighCapacityPeers when we are selecting for PURPOSE_TEST
            // or we are falling back due to _fastPeers being too small, so we can always
            // exclude the fast peers
            /*
            if (exclude == null)
                exclude = new HashSet(_fastPeers.keySet());
            else
                exclude.addAll(_fastPeers.keySet());
             */
            locked_selectPeers(_highCapacityPeers, howMany, exclude, matches, mask);
        } finally { releaseReadLock(); }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Need " + howMany + " High Capacity peers for tunnel build; " + matches.size() + " found - selecting remainder from Not Failing peers");
            selectActiveNotFailingPeers2(howMany, exclude, matches, mask);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(howMany + " High Capacity peers selected for tunnel build");
        }
        return;
    }

    /**
     * Return a set of Hashes for peers that are well integrated into the network.
     *
     * @deprecated unused
     */
    @Deprecated
    public void selectWellIntegratedPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectWellIntegratedPeers(howMany, exclude, matches, 0);
    }

    /**
     * Return a set of Hashes for peers that are well integrated into the network.
     *
     * @param mask 0-4 Number of bytes to match to determine if peers in the same IP range should
     *             not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     * @deprecated unused
     */
    @Deprecated
    public void selectWellIntegratedPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask) {
        getReadLock();
        try {
            locked_selectPeers(_wellIntegratedPeers, howMany, exclude, matches, mask);
        } finally { releaseReadLock(); }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Need " + howMany + " Integrated peers for tunnel build; " + matches.size() + " found - selecting remainder from Not Failing peers");
            selectNotFailingPeers(howMany, exclude, matches, mask);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(howMany + " Integrated peers selected for tunnel build");
        }

        return;
    }

    /**
     * Return a set of Hashes for peers that are not failing, preferring ones that
     * we are already talking with
     *
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectNotFailingPeers(howMany, exclude, matches, false, 0);
    }

    /**
     * @param mask ignored, should call locked_selectPeers, to be fixed
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask) {
        selectNotFailingPeers(howMany, exclude, matches, false, mask);
    }

    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing) {
        selectNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0);
    }

    /**
     * Return a set of Hashes for peers that are not failing, preferring ones that
     * we are already talking with
     *
     * @param howMany how many peers to find
     * @param exclude what peers to skip (may be null)
     * @param matches set to store the matches in
     * @param onlyNotFailing if true, don't include any high capacity peers
     * @param mask ignored, should call locked_selectPeers, to be fixed
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing, int mask) {
        if (matches.size() < howMany)
            selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, mask);
        return;
    }

    /**
     * Return a set of Hashes for peers that are both not failing and we're actively
     * talking with.
     *
     * We use commSystem().isEstablished(), not profile.getIsActive(), as the
     * NTCP idle time is now shorter than the 5 minute getIsActive() threshold,
     * and we're using this to try and limit connections.
     *
     * Caution, this does NOT cascade further to non-connected peers, so it should only
     * be used when there is a good number of connected peers.
     *
     * @param exclude non-null, WARNING - side effect, all not-connected peers are added
     * No mask parameter, to be fixed
     */
    public void selectActiveNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        if (matches.size() < howMany) {
            Set<Hash> connected = _context.commSystem().getEstablished();
            getReadLock();
            try {
                for (Hash peer : _notFailingPeers.keySet()) {
                    if (!connected.contains(peer))
                        exclude.add(peer);
                }
                locked_selectPeers(_notFailingPeers, howMany, exclude, matches, 0);
            } finally { releaseReadLock(); }
        }
    }

    /**
     * Return a set of Hashes for peers that are both not failing and we're actively
     * talking with.
     *
     * We use commSystem().isEstablished(), not profile.getIsActive(), as the
     * NTCP idle time is now shorter than the 5 minute getIsActive() threshold,
     * and we're using this to try and limit connections.
     *
     * This DOES cascade further to non-connected peers.
     *
     * @param mask 0-4 Number of bytes to match to determine if peers in the same IP range should
     *             not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     */
    private void selectActiveNotFailingPeers2(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask) {
        if (matches.size() < howMany) {
            Set<Hash> connected = _context.commSystem().getEstablished();
            Map<Hash, PeerProfile> activePeers = new HashMap<Hash, PeerProfile>(connected.size());
            getReadLock();
            try {
                for (Hash peer : connected) {
                    PeerProfile prof = _notFailingPeers.get(peer);
                    if (prof != null)
                        activePeers.put(peer, prof);
                }
                locked_selectPeers(activePeers, howMany, exclude, matches, mask);
            } finally { releaseReadLock(); }
        }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Need " + howMany + " Not Failing peers for tunnel build; " + matches.size() + " found - selecting remainder from most reliable Failing peers");
            selectNotFailingPeers(howMany, exclude, matches, mask);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(howMany + " Not Failing peers selected for tunnel build");
        }
    }

    /**
     * Return a set of Hashes for peers that are not failing.
     *
     */
    public void selectAllNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing) {
        selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0);
    }

    /**
     * @param mask ignored, should call locked_selectPeers, to be fixed
     *
     */
    private void selectAllNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing, int mask) {
        if (matches.size() < howMany) {
            int orig = matches.size();
            int needed = howMany - orig;
            List<Hash> selected = new ArrayList<Hash>(needed);
            getReadLock();
            try {
                // use RandomIterator to avoid shuffling the whole thing
                for (Iterator<Hash> iter = new RandomIterator<Hash>(_notFailingPeersList); (selected.size() < needed) && iter.hasNext(); ) {
                    Hash cur = iter.next();
                    if (matches.contains(cur) ||
                        (exclude != null && exclude.contains(cur))) {
                        StringBuilder buf = new StringBuilder();
                        buf.append("Current [" + cur.toBase64().substring(0,6) + "] Matched? " + matches.contains(cur));
                        // TODO Add count
                        buf.append("\n* Excluded " + exclude.size() + ": ");
                        for (Hash h : exclude) {
                            buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
                        }
//                        if (_log.shouldLog(Log.DEBUG))
//                            _log.debug(buf.toString());
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Current [" + cur.toBase64().substring(0,6) + "] Matched? " + matches.contains(cur) +
                                      " - Excluded: " + exclude.size() + " peers");
//                            _log.debug("Current [" + cur.toBase64().substring(0,6) + "]; Matched? " + matches.contains(cur)
//                                       + "; Excluded: " + exclude);
                        continue;
                    } else if (onlyNotFailing && _highCapacityPeers.containsKey(cur)) {
                        // we don't want the good peers, just random ones
                        continue;
                    } else {
                        if (isSelectable(cur))
                            selected.add(cur);
                        else if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Not selectable for tunnel build: [" + cur.toBase64().substring(0,6) + "]");
                    }
                }
            } finally { releaseReadLock(); }

            StringBuilder buf = new StringBuilder();
            buf.append("Selecting all Not Failing peers - Strict? " + onlyNotFailing + "\n* Found " + selected.size() + " new peers: ");
            for (Hash h : selected) {
                buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
            }
            buf.append("\n* All: " + _notFailingPeersList.size() + "; Strict: " + _strictCapacityOrder.size());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(buf.toString());
//                _log.info("Selecting all Not Failing peers - Strict? " + onlyNotFailing +
//                          "\n* Found " + selected.size() + " new peers: " + selected + " All: " + _notFailingPeersList.size() +
//                          "; Strict: " + _strictCapacityOrder.size());
            matches.addAll(selected);
        }
        if (matches.size() < howMany) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Need " + howMany + " Not Failing peers for tunnel build; " + matches.size() + " found - selecting remainder from most reliable Failing peers");
            selectFailingPeers(howMany, exclude, matches);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(howMany + " Not Failing peers selected for tunnel build");
        }
        return;
    }

    /**
     * I'm not quite sure why you'd want this... (other than for failover from the better results)
     *
     */
    public void selectFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        getReadLock();
        try {
            locked_selectPeers(_failingPeers, howMany, exclude, matches);
        } finally { releaseReadLock(); }
        return;
    }

    /**
     * Get the peers the transport layer thinks are unreachable
     * and peers requiring introducers.
     *
     */
    public List<Hash> selectPeersLocallyUnreachable() {
        List<Hash> n;
        int count;
        getReadLock();
        try {
            count = _notFailingPeers.size();
            n = new ArrayList<Hash>(_notFailingPeers.keySet());
        } finally { releaseReadLock(); }
        List<Hash> l = new ArrayList<Hash>(count / 4);
        for (Hash peer : n) {
            if (_context.commSystem().wasUnreachable(peer)) {
                l.add(peer);
            } else {
                // Blacklist all peers requiring SSU introducers, because either
                //  a) it's slow; or
                //  b) it doesn't work very often; or
                //  c) in the event they are advertising NTCP, it probably won't work because
                //     they probably don't have a TCP hole punched in their firewall either.
                RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
                if (info != null) {
                        RouterAddress ra = info.getTargetAddress("SSU");
                        // peers with no SSU address at all are fine.
                        // as long as they have NTCP
                        if (ra == null) {
                            if (info.getTargetAddresses("NTCP", "NTCP2").isEmpty())
                                l.add(peer);
                            continue;
                        }
                        // This is the quick way of doing UDPAddress.getIntroducerCount() > 0
                        if (ra.getOption("ihost0") != null)
                            l.add(peer);
                }
            }
        }
        StringBuilder buf = new StringBuilder();
        if (!l.isEmpty()) {
            // TODO Add count
            buf.append("Unreachable: " + l.size() + " peers\n* ");
            for (Hash h : l) {
                buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
            }
        }
//        if (_log.shouldLog(Log.DEBUG) && l.size() > 0)
//            _log.debug(buf.toString());
        if (_log.shouldLog(Log.DEBUG) && l.size() > 0)
            _log.debug("Unreachable: " + l.size() + " peers");
//            _log.debug("Unreachable: " + l);
        return l;
    }

    /**
     * Get the peers that have recently rejected us for bandwidth
     * recent == last 20s
     *
     */
    public List<Hash> selectPeersRecentlyRejecting() {
        getReadLock();
        try {
            long cutoff = _context.clock().now() - (20*1000);
            int count = _notFailingPeers.size();
            List<Hash> l = new ArrayList<Hash>(count / 128);
            for (PeerProfile prof : _notFailingPeers.values()) {
                if (prof.getTunnelHistory().getLastRejectedBandwidth() > cutoff)
                    l.add(prof.getPeer());
            }
            return l;
        } finally { releaseReadLock(); }
    }

    /**
     * Find the hashes for all peers we are actively profiling
     *
     */
    public Set<Hash> selectAllPeers() {
        getReadLock();
        try {
            Set<Hash> allPeers = new HashSet<Hash>(_failingPeers.size() + _notFailingPeers.size() + _highCapacityPeers.size() + _fastPeers.size());
            allPeers.addAll(_failingPeers.keySet());
            allPeers.addAll(_notFailingPeers.keySet());
            allPeers.addAll(_highCapacityPeers.keySet());
            allPeers.addAll(_fastPeers.keySet());
            return allPeers;
        } finally { releaseReadLock(); }
    }

//    private static final long MIN_EXPIRE_TIME = 60*60*1000;
    private static final long MIN_EXPIRE_TIME = 30*60*1000;
//    private static final long MAX_EXPIRE_TIME = 6*60*60*1000;
    private static final long MAX_EXPIRE_TIME = 4*60*60*1000;
    private static final long ADJUST_EXPIRE_TIME = 60*1000;
    private static final int ENOUGH_PROFILES = 400;
    private long _currentExpireTime = MAX_EXPIRE_TIME;

    /**
     * Place peers into the correct tier, as well as expand/contract and even drop profiles
     * according to whatever limits are in place.  Peer profiles are not coalesced during
     * this method, but the averages are recalculated.
     *
     */
    void reorganize() { reorganize(false, false); }

    void reorganize(boolean shouldCoalesce, boolean shouldDecay) {
        long sortTime = 0;
        int coalesceTime = 0;
        long thresholdTime = 0;
        long placeTime = 0;
        int profileCount = 0;
        int expiredCount = 0;

        // null for main()
        Router r = _context.router();
        long uptime = (r != null) ? r.getUptime() : 0L;
        long expireOlderThan = -1;
        if (uptime > 60*60*1000) {
            // dynamically adjust expire time to control memory usage
            if (countNotFailingPeers() > ENOUGH_PROFILES)
                _currentExpireTime = Math.max(_currentExpireTime - ADJUST_EXPIRE_TIME, MIN_EXPIRE_TIME);
            else
                _currentExpireTime = Math.min(_currentExpireTime + ADJUST_EXPIRE_TIME, MAX_EXPIRE_TIME);
            // drop profiles that we haven't spoken to in a while
            expireOlderThan = _context.clock().now() - _currentExpireTime;
        }

        if (shouldCoalesce) {
            getReadLock();
            try {
                long coalesceStart = System.currentTimeMillis();
                for (PeerProfile prof : _strictCapacityOrder) {
                    if ( (expireOlderThan > 0) && (prof.getLastSendSuccessful() <= expireOlderThan) ) {
                        continue;
                    }
                    prof.coalesceOnly(shouldDecay);
                }
                coalesceTime = (int)(System.currentTimeMillis()-coalesceStart);
            } finally {
                releaseReadLock();
            }
        }

        if (!getWriteLock())
            return;
        long start = System.currentTimeMillis();
        try {
            Set<PeerProfile> allPeers = _strictCapacityOrder; //new HashSet(_failingPeers.size() + _notFailingPeers.size() + _highCapacityPeers.size() + _fastPeers.size());
            //allPeers.addAll(_failingPeers.values());
            //allPeers.addAll(_notFailingPeers.values());
            //allPeers.addAll(_highCapacityPeers.values());
            //allPeers.addAll(_fastPeers.values());

            Set<PeerProfile> reordered = new TreeSet<PeerProfile>(_comp);
            long sortStart = System.currentTimeMillis();
            for (Iterator<PeerProfile> iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                PeerProfile prof = iter.next();
                if ( (expireOlderThan > 0) && (prof.getLastSendSuccessful() <= expireOlderThan) ) {
                    expiredCount++;
                    continue; // drop, but no need to delete, since we don't periodically reread
                    // TODO maybe we should delete files, otherwise they are only deleted at restart
                }
                prof.updateValues();
                reordered.add(prof);
                profileCount++;
            }
            sortTime = System.currentTimeMillis() - sortStart;
            _strictCapacityOrder = reordered;

            long thresholdStart = System.currentTimeMillis();
            locked_calculateThresholds(allPeers);
            thresholdTime = System.currentTimeMillis()-thresholdStart;

            _failingPeers.clear();
            _fastPeers.clear();
            _highCapacityPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();
            _wellIntegratedPeers.clear();

            long placeStart = System.currentTimeMillis();

            for (PeerProfile profile : _strictCapacityOrder) {
                locked_placeProfile(profile);
            }

            locked_unfailAsNecessary();
            locked_demoteHighCapAsNecessary();
            locked_promoteFastAsNecessary();
            locked_demoteFastAsNecessary();

            // we now use a random iterator in selectAllNotFailingPeers(),
            // as it was picking peers in-order before the first reorganization
            //Collections.shuffle(_notFailingPeersList, _context.random());

            placeTime = System.currentTimeMillis()-placeStart;
        } finally { releaseWriteLock(); }


        if (_log.shouldLog(Log.INFO))
            _log.info("Profiles reorganized: " + expiredCount + " expired"
                       + "\n* Averages: [Integration: " + _thresholdIntegrationValue
                       + "] [Capacity: " + _thresholdCapacityValue + "] [Speed: " + _thresholdSpeedValue + "]");
            /*****
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuilder buf = new StringBuilder(512);
                for (Iterator iter = _strictCapacityOrder.iterator(); iter.hasNext(); ) {
                    PeerProfile prof = (PeerProfile)iter.next();
                    buf.append('[').append(prof.toString()).append('=').append(prof.getCapacityValue()).append("] ");
                }
                _log.debug("Strictly organized (highest capacity first): " + buf.toString());
                _log.debug("fast: " + _fastPeers.values());
            }
            *****/

        long total = System.currentTimeMillis()-start;
        _context.statManager().addRateData("peer.profileSortTime", sortTime, profileCount);
        _context.statManager().addRateData("peer.profileCoalesceTime", coalesceTime, profileCount);
        _context.statManager().addRateData("peer.profileThresholdTime", thresholdTime, profileCount);
        _context.statManager().addRateData("peer.profilePlaceTime", placeTime, profileCount);
        _context.statManager().addRateData("peer.profileReorgTime", total, profileCount);
    }

    /**
     * As with locked_unfailAsNecessary, I'm not sure how much I like this - if there
     * aren't enough fast peers, move some of the not-so-fast peers into the fast group.
     * This picks the not-so-fast peers based on capacity, not speed, and skips over any
     * failing peers.  Perhaps it should build a seperate strict ordering by speed?  Nah, not
     * worth the maintenance and memory overhead, at least not for now.
     *
     */
    private void locked_promoteFastAsNecessary() {
        int minFastPeers = getMinimumFastPeers();
        int numToPromote = minFastPeers - _fastPeers.size();
        if (numToPromote > 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Need to explicitly promote " + numToPromote + " peers to Fast group");
            for (PeerProfile cur : _strictCapacityOrder) {
                if ( (!_fastPeers.containsKey(cur.getPeer())) && (!cur.getIsFailing()) ) {
                    if (!isSelectable(cur.getPeer())) {
                        // skip peers we don't have in the netDb
                        // if (_log.shouldLog(Log.INFO))
                        //     _log.info("skip unknown peer from fast promotion: " + cur.getPeer().toBase64());
                        continue;
                    }
                    if (!cur.getIsActive()) {
                        // skip inactive
                        // if (_log.shouldLog(Log.INFO))
                        //     _log.info("skip inactive peer from fast promotion: " + cur.getPeer().toBase64());
                        continue;
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Promoting [" + cur.getPeer().toBase64().substring(0,6) + "] to Fast group");
                    _fastPeers.put(cur.getPeer(), cur);
                    // no need to remove it from any of the other groups, since if it is
                    // fast, it has a high capacity, and it is not failing
                    numToPromote--;
                    if (numToPromote <= 0)
                        break;
                }
            }
        }
        return;
    }

    /**
     * We want to put a cap on the fast pool, to use only a small set of routers
     * for client tunnels for anonymity reasons. Also, unless we use only a small
     * number, we don't really find out who the fast ones are.
     * @since 0.7.10
     */
    private void locked_demoteFastAsNecessary() {
        int maxFastPeers = getMaximumFastPeers();
        int numToDemote = _fastPeers.size() - maxFastPeers;
        if (numToDemote > 0) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Need to explicitly demote " + numToDemote + " peers from Fast group");
            // sort by speed, slowest-first
            Set<PeerProfile> sorted = new TreeSet<PeerProfile>(new SpeedComparator());
            sorted.addAll(_fastPeers.values());
            Iterator<PeerProfile> iter = sorted.iterator();
            for (int i = 0; i < numToDemote && iter.hasNext(); i++) {
                _fastPeers.remove(iter.next().getPeer());
            }
        }
    }

    /**
     * We want to put a limit on the high cap pool, to use only a small set of routers
     * for expl. tunnels for anonymity reasons. Also, unless we use only a small
     * number, we don't really find out who the high capacity ones are.
     * @since 0.7.11
     */
    private void locked_demoteHighCapAsNecessary() {
        int maxHighCapPeers = getMaximumHighCapPeers();
        NetworkDatabaseFacade netDb = _context.netDb();
        int numToDemote = _highCapacityPeers.size() - maxHighCapPeers;
        if (numToDemote > 0) {
            // sorted by capacity, highest-first
            Iterator<PeerProfile> iter = _strictCapacityOrder.iterator();
            for (int i = 0; iter.hasNext() && i < maxHighCapPeers; ) {
                if (_highCapacityPeers.containsKey(iter.next().getPeer()))
                    i++;
            }
            for (int i = 0; iter.hasNext() && i < numToDemote; ) {
                Hash h = iter.next().getPeer();
                if (_highCapacityPeers.remove(h) != null) {
                    _fastPeers.remove(h);
                    i++;
                }
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("Demoted " + numToDemote + " peers from High Capacity group; new size is " + _highCapacityPeers.size() + " peers");
        }
    }

    /** how many not failing/active peers must we have? */
    private final static int MIN_NOT_FAILING_ACTIVE = 50;

    /**
     * I'm not sure how much I dislike the following - if there aren't enough
     * active and not-failing peers, pick the most reliable active peers and
     * override their 'failing' flag, resorting them into the not-failing buckets
     *
     */
    private void locked_unfailAsNecessary() {
        int notFailingActive = 0;
        for (PeerProfile peer : _notFailingPeers.values()) {
            if (peer.getIsActive())
                notFailingActive++;
            if (notFailingActive >= MIN_NOT_FAILING_ACTIVE) {
                // we've got enough, no need to try further
                return;
            }
        }

        // we don't have enough, let's unfail our best ones remaining
        int needToUnfail = MIN_NOT_FAILING_ACTIVE - notFailingActive;
        if (needToUnfail > 0) {
            int unfailed = 0;
            for (PeerProfile best : _strictCapacityOrder) {
                if ( (best.getIsActive()) && (best.getIsFailing()) ) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("All peers are marked as failing; overriding the Failing flag for [" +
                                  best.getPeer().toBase64().substring(0,6) + "]");
                    best.setIsFailing(false);
                    locked_placeProfile(best);
                    unfailed++;
                }
                if (unfailed >= needToUnfail)
                    break;
            }
        }
    }

    ////////
    // no more public stuff below
    ////////

    /**
     * Update the thresholds based on the profiles in this set.  currently
     * implements the capacity threshold based on the mean capacity of active
     * and nonfailing peers (falling back on the median if that results in too
     * few peers.  We then use the median speed from that group to define the
     * speed threshold, and use the mean integration value from the
     * high capacity group to define the integration threshold.
     *
     */
    private void locked_calculateThresholds(Set<PeerProfile> allPeers) {
        double totalCapacity = 0;
        double totalIntegration = 0;
        Set<PeerProfile> reordered = new TreeSet<PeerProfile>(_comp);
        for (PeerProfile profile : allPeers) {
            if (_us.equals(profile.getPeer())) continue;

            // exclude unreachable peers
            if (profile.wasUnreachable()) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Excluding [" + profile.getPeer().toBase64().substring(0,6) + "] from fast/highcap groups -> unreachable");
                continue;
            }

            RouterInfo peerInfo = _context.netDb().lookupRouterInfoLocally(profile.getPeer());
            String bw = peerInfo.getBandwidthTier();
            String cap = peerInfo.getCapabilities();
            boolean reachable = cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
            if (peerInfo != null && cap != null && (!reachable || bw.equals("K") || bw.equals("L") || bw.equals("M"))) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Excluding [" + profile.getPeer().toBase64().substring(0,6) + "] from fast/highcap groups -> K,L,M or unreachable");
                continue;
            }

            // only take into account active peers that aren't failing
            if (profile.getIsFailing() || (!profile.getIsActive()))
                continue;

            // don't bother trying to make sense of things below the baseline
            // otoh, keep them in the threshold calculation, so we can adapt
            ////if (profile.getCapacityValue() <= CapacityCalculator.GROWTH_FACTOR)
            ////    continue;

            totalCapacity += profile.getCapacityValue();
            totalIntegration += profile.getIntegrationValue();
            reordered.add(profile);
        }

        locked_calculateCapacityThreshold(totalCapacity, reordered);
        locked_calculateSpeedThreshold(reordered);

        if (totalIntegration > 0)
            _thresholdIntegrationValue = 1.0d * avg(totalIntegration, reordered.size());
        else    // Make nobody rather than everybody well-integrated
            _thresholdIntegrationValue = 1.0d;
    }

    /**
     * Update the _thresholdCapacityValue by using a few simple formulas run
     * against the specified peers.  Ideally, we set the threshold capacity to
     * the mean, as long as that gives us enough peers and is greater than the
     * median.
     *
     * @param reordered ordered set of PeerProfile objects, ordered by capacity
     *                  (highest first) for active nonfailing peers whose
     *                  capacity is greater than the growth factor
     */
    private void locked_calculateCapacityThreshold(double totalCapacity, Set<PeerProfile> reordered) {
        int numNotFailing = reordered.size();
        double meanCapacity = avg(totalCapacity, numNotFailing);
        int minHighCapacityPeers = getMinimumHighCapacityPeers();
        int numExceedingMean = 0;
        double thresholdAtMedian = 0;
        double thresholdAtMinHighCap = 0;
        double thresholdAtLowest = CapacityCalculator.GROWTH_FACTOR;
        int cur = 0;
        for (PeerProfile profile : reordered) {
            double val = profile.getCapacityValue();
            if (val > meanCapacity)
                numExceedingMean++;
            if (cur == reordered.size()/2)
                thresholdAtMedian = val;
            if (cur == minHighCapacityPeers - 1)
                thresholdAtMinHighCap = val;
            if (cur == reordered.size() -1)
                thresholdAtLowest = val;
            cur++;
        }

        if (numExceedingMean >= minHighCapacityPeers) {
            // our average is doing well (growing, not recovering from failures)
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity (" + meanCapacity + ") is good and includes " +
                          numExceedingMean + " peer profiles");
            _thresholdCapacityValue = meanCapacity;
        } else if (meanCapacity > thresholdAtMedian && reordered.size() / 2 > minHighCapacityPeers) {
            // avg > median, get the min High Cap peers
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity (" + meanCapacity + ") is greater than the median,"
                          + " so we've satisified the threshold (" + thresholdAtMinHighCap + ") to get the min High Capacity peers");
            _thresholdCapacityValue = thresholdAtMinHighCap;
        } else if (reordered.size() / 2 >= minHighCapacityPeers) {
            // ok mean is skewed low, but we still have enough to use the median
            // We really don't want to be here, since the default is 5.0 and the median
            // is inevitably 5.01 or so.
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity (" + meanCapacity + ") is skewed under the median,"
                          + " so we're using the median threshold " + thresholdAtMedian);
            _thresholdCapacityValue = thresholdAtMedian;
        } else {
            // our average is doing well, but not enough peers
            if (_log.shouldLog(Log.INFO))
                _log.info("Our average capacity (" + meanCapacity + ") is good" +
                          " but we don't have enough peers (" + numExceedingMean + " required)");
            _thresholdCapacityValue = Math.max(thresholdAtMinHighCap, thresholdAtLowest);
        }

        // the base growth factor is the value we give to new routers that we don't
        // know anything about.  don't go under that limit unless you want to expose
        // the selection to simple ident flooding attacks
        if (_thresholdCapacityValue <= CapacityCalculator.GROWTH_FACTOR)
            _thresholdCapacityValue = CapacityCalculator.GROWTH_FACTOR + 0.0001;
    }

    /**
     * Update the _thresholdSpeedValue by calculating the average speed of all
     * high capacity peers.
     *
     * @param reordered ordered set of PeerProfile objects, ordered by capacity
     *                  (highest first) for active nonfailing peers
     */
    private void locked_calculateSpeedThreshold(Set<PeerProfile> reordered) {
        if (true) {
            locked_calculateSpeedThresholdMean(reordered);
            return;
        }
/*****
        Set speeds = new TreeSet();
        for (Iterator iter = reordered.iterator(); iter.hasNext(); ) {
            PeerProfile profile = (PeerProfile)iter.next();
            if (profile.getCapacityValue() >= _thresholdCapacityValue) {
                // duplicates being clobbered is fine by us
                speeds.add(new Double(0-profile.getSpeedValue()));
            } else {
                // its ordered
                break;
            }
        }

        // calc the median speed of high capacity peers
        int i = 0;
        for (Iterator iter = speeds.iterator(); iter.hasNext(); i++) {
            Double speed = (Double)iter.next();
            if (i >= (speeds.size() / 2)) {
                _thresholdSpeedValue = 0-speed.doubleValue();
                break;
            }
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Threshold value for speed: " + _thresholdSpeedValue + " out of speeds: " + speeds);
*****/
    }

    private void locked_calculateSpeedThresholdMean(Set<PeerProfile> reordered) {
        double total = 0;
        int count = 0;
        int maxHighCapPeers = getMaximumHighCapPeers();
        int minHighCapPeers = getMinimumHighCapacityPeers();
        int maxFastPeers = getMaximumFastPeers();
        for (PeerProfile profile : reordered) {
            if (profile.getCapacityValue() >= _thresholdCapacityValue) {
                // duplicates being clobbered is fine by us
                total += profile.getSpeedValue();
                int speedBonus = profile.getSpeedBonus();
                if (speedBonus >= 9999999)
                    total -= 9999999;
                if (count++ > minHighCapPeers / 2 * 3)
                    break;
            } else {
                // its ordered
                break;
            }
        }

        if (count > 0)
//            _thresholdSpeedValue = total / count;
            _thresholdSpeedValue = ((total / count) / 7) * 5;
        if (_log.shouldLog(Log.INFO))
            _log.info("Threshold value for speed: " + _thresholdSpeedValue + " (calculated from " + count + " profiles)");
    }


    /** simple average, or 0 if NaN */
    private final static double avg(double total, double quantity) {
        if ( (total > 0) && (quantity > 0) )
            return total/quantity;
        else
            return 0.0d;
    }

    /** called after locking the reorganizeLock */
    private PeerProfile locked_getProfile(Hash peer) {
        PeerProfile cur = _notFailingPeers.get(peer);
        if (cur != null)
            return cur;
        cur = _failingPeers.get(peer);
        return cur;
    }

    /**
     * Select peers from the peer mapping, excluding appropriately and increasing the
     * matches set until it has howMany elements in it.
     *
     */
    private void locked_selectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude, Set<Hash> matches) {
        locked_selectPeers(peers, howMany, toExclude, matches, 0);
    }

    /**
      *
      * As of 0.9.24, checks for a netdb family match as well, unless mask == 0.
      *
     * @param mask 0-4 Number of bytes to match to determine if peers in the same IP range should
     *             not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     */
    private void locked_selectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude, Set<Hash> matches, int mask) {
        List<Hash> all = new ArrayList<Hash>(peers.keySet());
        MaskedIPSet IPSet = new MaskedIPSet(16);
        // use RandomIterator to avoid shuffling the whole thing
        for (Iterator<Hash> iter = new RandomIterator<Hash>(all); (matches.size() < howMany) && iter.hasNext(); ) {
            Hash peer = iter.next();
            if (toExclude != null && toExclude.contains(peer))
                continue;
            if (matches.contains(peer))
                continue;
            if (_us != null && _us.equals(peer))
                continue;
            boolean ok = isSelectable(peer);
            if (ok) {
                ok = mask <= 0 || notRestricted(peer, IPSet, mask);
                if ((!ok) && _log.shouldLog(Log.WARN))
                    _log.warn("IP address restriction prevents [" + peer.toBase64().substring(0,6) + "] from joining " + matches);
            }
            if (ok)
                matches.add(peer);
            else
                matches.remove(peer);
        }
    }

    /**
     * Does the peer's IP address NOT match the IP address of any peer already in the set,
     * on any transport, within a given mask?
     *
     * As of 0.9.24, checks for a netdb family match as well.
     *
     * @param mask is 1-4 (number of bytes to match)
     * @param IPMatches all IPs so far, modified by this routine
     */
    private boolean notRestricted(Hash peer, MaskedIPSet IPSet, int mask) {
        Set<String> peerIPs = new MaskedIPSet(_context, peer, mask);
        if (IPSet.containsAny(peerIPs))
            return false;
        IPSet.addAll(peerIPs);
        return true;
    }

    /**
     * @param randomKey used for deterministic random partitioning into subtiers
     * @param subTierMode 2-7:
     *<pre>
     *    2: return only from group 0 or 1
     *    3: return only from group 2 or 3
     *    4: return only from group 0
     *    5: return only from group 1
     *    6: return only from group 2
     *    7: return only from group 3
     *</pre>
     */
    private void locked_selectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude,
                                    Set<Hash> matches, Hash randomKey, Slice subTierMode) {
        List<Hash> all = new ArrayList<Hash>(peers.keySet());
        // use RandomIterator to avoid shuffling the whole thing
        for (Iterator<Hash> iter = new RandomIterator<Hash>(all); (matches.size() < howMany) && iter.hasNext(); ) {
            Hash peer = iter.next();
            if (toExclude != null && toExclude.contains(peer))
                continue;
            if (matches.contains(peer))
                continue;
            if (_us.equals(peer))
                continue;
            int subTier = getSubTier(peer, randomKey);
            if ((subTier & subTierMode.mask) != subTierMode.val)
                continue;
            boolean ok = isSelectable(peer);
            if (ok)
                matches.add(peer);
            else
                matches.remove(peer);
        }
    }

    /**
     *  Implement a random, deterministic split into 4 groups that cannot be predicted by
     *  others.
     *  @return 0-3
     */
    private int getSubTier(Hash peer, Hash randomKey) {
        // input is first 64 bytes; output is last 32 bytes
        byte[] data = new byte[96];
        System.arraycopy(peer.getData(), 0, data, 0, 32);
        System.arraycopy(randomKey.getData(), 0, data, 32, 32);
        _context.sha().calculateHash(data, 0, 64, data, 64);
        return data[64] & 0x03;
    }

    public boolean isSelectable(Hash peer) {
        NetworkDatabaseFacade netDb = _context.netDb();
        // the CLI shouldn't depend upon the netDb
        if (netDb == null) return true;
        if (_context.router() == null) return true;
        if ( (_context.banlist() != null) && (_context.banlist().isBanlisted(peer)) ) {
             if (_log.shouldLog(Log.DEBUG))
                 _log.debug("[" + peer.toBase64().substring(0,6) + "] is banlisted; not using it to build tunnels");
            return false; // never select a banlisted peer
        }

        RouterInfo info = _context.netDb().lookupRouterInfoLocally(peer);
//        String caps = DataHelper.stripHTML(info.getCapabilities());
        if (null != info) {
            String tier = DataHelper.stripHTML(info.getBandwidthTier());
            if (info.isHidden()) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("[" + peer.toBase64().substring(0,6) + "] is marked as hidden; not using it to build tunnels");
                return false;

/**
            } else if (tier.equals("L") || tier.equals("M")) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("[" + peer.toBase64().substring(0,6) + "] is in tier L or M; not using it to build tunnels");
                return false;
**/

            } else {
                boolean exclude = TunnelPeerSelector.shouldExclude(_context, info);
                if (exclude) {
                    // if (_log.shouldLog(Log.WARN))
                    //     _log.warn("Peer " + peer.toBase64() + " has capabilities or other stats suggesting we avoid it");
                    return false;
                } else {
                    // if (_log.shouldLog(Log.INFO))
                    //     _log.info("Peer " + peer.toBase64() + " is locally known, allowing its use");
                    return true;
                }
            }
        } else {
            // if (_log.shouldLog(Log.WARN))
            //    _log.warn("Peer " + peer.toBase64() + " is NOT locally known, disallowing its use");
            return false;
        }
    }

    /**
     * called after locking the reorganizeLock, place the profile in the appropriate tier.
     * This is where we implement the (betterThanAverage ? goToTierX : goToTierY) algorithms
     *
     */
    private void locked_placeProfile(PeerProfile profile) {
        Hash peer = profile.getPeer();
        if (profile.getIsFailing()) {
            if (!shouldDrop(profile))
                _failingPeers.put(peer, profile);
            _fastPeers.remove(peer);
            _highCapacityPeers.remove(peer);
            _wellIntegratedPeers.remove(peer);
            _notFailingPeers.remove(peer);
            _notFailingPeersList.remove(peer);
        } else {
            _failingPeers.remove(peer);
            _fastPeers.remove(peer);
            _highCapacityPeers.remove(peer);
            _wellIntegratedPeers.remove(peer);

            _notFailingPeers.put(peer, profile);
            _notFailingPeersList.add(peer);
            // if not selectable for a tunnel (banlisted for example),
            // don't allow them in the high-cap pool, what would the point of that be?
            if (_thresholdCapacityValue <= profile.getCapacityValue() &&
                isSelectable(peer) &&
                // null for tests
                (_context.commSystem() == null || !_context.commSystem().isInStrictCountry(peer))) {
                _highCapacityPeers.put(peer, profile);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Promoting [" + peer.toBase64().substring(0,6) + "] to High Capacity group");
                if (_thresholdSpeedValue <= profile.getSpeedValue()) {
                    if (!profile.getIsActive()) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Not promoting  [" + peer.toBase64().substring(0,6) + "] to Fast group (inactive)");
                    } else {
                        _fastPeers.put(peer, profile);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Promoting [" + peer.toBase64().substring(0,6) + "] to Fast group");
                    }
                }

            } else {
                // not high capacity, but not failing (yet)
            }
            // We aren't using the well-integrated list yet...
            // But by observation, the floodfill peers are often not in the
            // high-capacity group, so let's not require a peer to be high-capactiy
            // to call him well-integrated.
            // This could be used later to see if a floodfill peer is for real.
            if (_thresholdIntegrationValue <= profile.getIntegrationValue()) {
                _wellIntegratedPeers.put(peer, profile);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Promoting [" + peer.toBase64().substring(0,6) + "] to Integrated group");
            }
        }
    }

    /**
     * This is where we determine whether a failing peer is so poor and we're so overloaded
     * that we just want to forget they exist.  This algorithm won't need to be implemented until
     * after I2P 1.0, most likely, since we should be able to handle thousands of peers profiled
     * without ejecting any of them, but anyway, this is how we'd do it.  Most likely.
     *
     */
    private boolean shouldDrop(PeerProfile profile) {
        if (profile.getIsFailing())
            return true;
        else
            return false;
    }

    /**
     * Defines the minimum number of 'fast' peers that the organizer should select.  If
     * the profile calculators derive a threshold that does not select at least this many peers,
     * the threshold will be overridden to make sure this many peers are in the fast+reliable group.
     * This parameter should help deal with a lack of diversity in the tunnels created when some
     * peers are particularly fast.
     *
     * Increase default for every local destination, up to a max.
     *
     * @return minimum number of peers to be placed in the 'fast' group
     */
    protected int getMinimumFastPeers() {
        // null for main()
        ClientManagerFacade cm = _context.clientManager();
        if (cm == null)
            return DEFAULT_MAXIMUM_FAST_PEERS;
        int known = _context.netDb().getKnownRouters();
        int def = Math.max(DEFAULT_MAXIMUM_FAST_PEERS, (10 * cm.listClients().size()) + DEFAULT_MINIMUM_FAST_PEERS - 2);
        if (known > 3000)
//            return _context.getProperty(PROP_MINIMUM_FAST_PEERS, Math.max((known / 35), def));
            return _context.getProperty(PROP_MINIMUM_FAST_PEERS, Math.min(known / 30, DEFAULT_MAXIMUM_FAST_PEERS));
        else
            return _context.getProperty(PROP_MINIMUM_FAST_PEERS, DEFAULT_MINIMUM_FAST_PEERS);
    }

    /** fixme add config  @since 0.7.10 */
    protected int getMaximumFastPeers() {
        int known = _context.netDb().getKnownRouters();
        if (known > 3000)
            return known / 20;
        else
            return ABSOLUTE_MAX_FAST_PEERS;
    }

    /** fixme add config  @since 0.7.11 */
    protected int getMaximumHighCapPeers() {
        int known = _context.netDb().getKnownRouters();
        if (known > 3000)
            return (Math.min(known / 10, ABSOLUTE_MAX_HIGHCAP_PEERS));
        else
            return ABSOLUTE_MAX_HIGHCAP_PEERS;
    }

    /**
     * Defines the minimum number of 'high capacity' peers that the organizer should select.  If
     * the profile calculators derive a threshold that does not select at least this many peers,
     * the threshold will be overridden to make sure this many peers are in the fast+reliable group.
     * This parameter should help deal with a lack of diversity in the tunnels created when some
     * peers are particularly fast.
     *
     * @return minimum number of peers to be placed in the 'high capacity' group
     */
    protected int getMinimumHighCapacityPeers() {
        int known = _context.netDb().getKnownRouters();
        if (known > 3000)
            return _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, Math.max(known / 15, DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS));
        else
            return _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS);
    }

    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }

    /**
     * Read in all of the profiles specified and print out
     * their calculated values.  Usage: <pre>
     *  ProfileOrganizer [filename]*
     * </pre>
     */
    public static void main(String args[]) {
        if (args.length <= 0) {
            System.err.println("Usage: profileorganizer file.txt.gz [file2.txt.gz] ...");
            System.exit(1);
        }

        RouterContext ctx = new RouterContext(null); // new net.i2p.router.Router());
        ProfileOrganizer organizer = new ProfileOrganizer(ctx);
        organizer.setUs(Hash.FAKE_HASH);
        ProfilePersistenceHelper helper = new ProfilePersistenceHelper(ctx);
        for (int i = 0; i < args.length; i++) {
            PeerProfile profile = helper.readProfile(new java.io.File(args[i]));
            if (profile == null) {
                System.err.println("Could not load profile " + args[i]);
                continue;
            }
            organizer.addProfile(profile);
        }
        organizer.reorganize();
        DecimalFormat fmt = new DecimalFormat("0000.0");

        for (Hash peer : organizer.selectAllPeers()) {
            PeerProfile profile = organizer.getProfile(peer);
            if (!profile.getIsActive()) {
                System.out.println("Peer " + peer.toBase64().substring(0,4)
                           + " [" + (organizer.isFast(peer) ? "IF+R" :
                                     organizer.isHighCapacity(peer) ? "IR  " :
                                     organizer.isFailing(peer) ? "IX  " : "I   ") + "]: "
                           + " Speed:\t" + fmt.format(profile.getSpeedValue())
                           + " Capacity:\t" + fmt.format(profile.getCapacityValue())
                           + " Integration:\t" + fmt.format(profile.getIntegrationValue())
                           + " Active?\t" + profile.getIsActive()
                           + " Failing?\t" + profile.getIsFailing());
            } else {
                System.out.println("Peer " + peer.toBase64().substring(0,4)
                           + " [" + (organizer.isFast(peer) ? "F+R " :
                                     organizer.isHighCapacity(peer) ? "R   " :
                                     organizer.isFailing(peer) ? "X   " : "    ") + "]: "
                           + " Speed:\t" + fmt.format(profile.getSpeedValue())
                           + " Capacity:\t" + fmt.format(profile.getCapacityValue())
                           + " Integration:\t" + fmt.format(profile.getIntegrationValue())
                           + " Active?\t" + profile.getIsActive()
                           + " Failing?\t" + profile.getIsFailing());
            }
        }

        System.out.println("Thresholds:");
        System.out.println("Speed:       " + num(organizer.getSpeedThreshold()) + " (" + organizer.countFastPeers() + " fast peers)");
        System.out.println("Capacity:    " + num(organizer.getCapacityThreshold()) + " (" + organizer.countHighCapacityPeers() + " reliable peers)");
    }

}
