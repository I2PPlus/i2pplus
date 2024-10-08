package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.crypto.SipHashInline;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SessionKey;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.pool.TunnelPeerSelector;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.router.util.RandomIterator;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Keep the peer profiles organized according to the tiered model.  This does not
 * actively update anything - the reorganize() method should be called periodically
 * to recalculate thresholds and move profiles into the appropriate tiers, and addProfile()
 * should be used to add new profiles (placing them into the appropriate groupings).
 */
public class ProfileOrganizer {
    private final Log _log;
    private final RouterContext _context;
    /** H(routerIdentity) to PeerProfile for all peers that are fast and high capacity */
    private final Map<Hash, PeerProfile> _fastPeers;
    /** H(routerIdentity) to PeerProfile for all peers that have high capacities */
    private final Map<Hash, PeerProfile> _highCapacityPeers;
    /** TO BE REMOVED H(routerIdentity) to PeerProfile for all peers that well integrated into the network and not failing horribly */
    private final Map<Hash, PeerProfile> _wellIntegratedPeers;
    /** H(routerIdentity) to PeerProfile for all peers that are not failing horribly */
    private final Map<Hash, PeerProfile> _notFailingPeers;
    /** H(routerIdnetity), containing elements in _notFailingPeers */
    private final List<Hash> _notFailingPeersList;
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
    public static final int DEFAULT_MINIMUM_FAST_PEERS = 400;
    /** this is misnamed, it is really the max minimum number. */
    private static final int DEFAULT_MAXIMUM_FAST_PEERS = 500;
    private static final int ABSOLUTE_MAX_FAST_PEERS = 600;

    /**
     * Defines the minimum number of 'high capacity' peers that the organizer should
     * select when using the mean - if less than this many are available, select the
     * capacity by the median.
     *
     */
    public static final String PROP_MINIMUM_HIGH_CAPACITY_PEERS = "profileOrganizer.minHighCapacityPeers";
    public static final int DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS = 500;
    private static final int ABSOLUTE_MAX_HIGHCAP_PEERS = 800;
    private static final long[] RATES = {60*1000l, 5*60*1000l, 10*60*1000l, 30*60*1000l, 60*60*1000l, 24*60*60*1000 };

    /** synchronized against this lock when updating the tier that peers are located in (and when fetching them from a peer) */
    private final ReentrantReadWriteLock _reorganizeLock = new ReentrantReadWriteLock(false);

    public ProfileOrganizer(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(ProfileOrganizer.class);
        _comp = new InverseCapacityComparator();
        _fastPeers = new HashMap<Hash, PeerProfile>(512);
        _highCapacityPeers = new HashMap<Hash, PeerProfile>(512);
        _notFailingPeersList = new ArrayList<Hash>(4096);
        _notFailingPeers = new HashMap<Hash, PeerProfile>(4096);
        _persistenceHelper = new ProfilePersistenceHelper(_context);
        _strictCapacityOrder = new TreeSet<PeerProfile>(_comp);
        _wellIntegratedPeers = new HashMap<Hash, PeerProfile>(512);
        _context.statManager().createRateStat("peer.profileCoalesceTime", "Time to coalesce peer stats (ms)", "Peers", RATES);
        _context.statManager().createRateStat("peer.profilePlaceTime", "Time to sort peers into tiers (ms)", "Peers", RATES);
        _context.statManager().createRateStat("peer.profileReorgTime", "Time to reorganize peers (ms)", "Peers", RATES);
        _context.statManager().createRateStat("peer.profileThresholdTime", "Time to determine tier thresholds (ms)", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.failedLookupRate", "NetDb Lookup failure rate", "Peers", RATES); // used in DBHistory
        _context.statManager().createRequiredRateStat("peer.profileSortTime", "Time to sort peers (ms)", "Peers", RATES);
    }

    private void getReadLock() {_reorganizeLock.readLock().lock();}

    /**
     *  Get the lock if we can. Non-blocking.
     *  @return true if the lock was acquired
     *  @since 0.8.12
     */
    private boolean tryReadLock() {return _reorganizeLock.readLock().tryLock();}

    private void releaseReadLock() {_reorganizeLock.readLock().unlock();}

    /**
     *  Get the lock if we can. Non-blocking.
     *  @return true if the lock was acquired
     *  @since 0.9.47
     */
    private boolean tryWriteLock() {return _reorganizeLock.writeLock().tryLock();}

    /** @return true if the lock was acquired */
    private boolean getWriteLock() {
        try {
            boolean rv = _reorganizeLock.writeLock().tryLock(3000, TimeUnit.MILLISECONDS);
            if ((!rv) && _log.shouldWarn()) {
                _log.warn("No lock, size is: " + _reorganizeLock.getQueueLength(), new Exception("rats"));
            }
            return rv;
        } catch (InterruptedException ie) {}
        return false;
    }

    private void releaseWriteLock() {_reorganizeLock.writeLock().unlock();}

    public void setUs(Hash us) {_us = us;}
    public Hash getUs() {return _us;}

    public double getSpeedThreshold() {return _thresholdSpeedValue;}
    public double getCapacityThreshold() {return _thresholdCapacityValue;}
    public double getIntegrationThreshold() {return _thresholdIntegrationValue;}

    /**
     * Retrieve the profile for the given peer, if one exists (else null).
     * Blocking if a reorganize is happening.
     */
    public PeerProfile getProfile(Hash peer) {
        if (peer != null && peer.equals(_us)) {return null;}
        getReadLock();
        try {return locked_getProfile(peer);}
        finally {releaseReadLock();}
    }

    /**
     * Retrieve the profile for the given peer, if one exists (else null).
     * Non-blocking. Returns null if a reorganize is happening.
     * @since 0.8.12
     */
    public PeerProfile getProfileNonblocking(Hash peer) {
        if (peer != null && peer.equals(_us)) {return null;}
        if (tryReadLock()) {
            try {return locked_getProfile(peer);}
            finally {releaseReadLock();}
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
        if (peer == null || peer.equals(_us) || !tryReadLock()) {return null;}

        PeerProfile rv;
        try {rv = locked_getProfile(peer);}
        finally {releaseReadLock();}
        if (rv != null) {return rv;}

        rv = new PeerProfile(_context, peer);
        rv.setLastHeardAbout(rv.getFirstHeardAbout());
        rv.coalesceStats();
        if (!tryWriteLock()) {return null;}

        try {
            // double check
            PeerProfile old = locked_getProfile(peer);
            if (old != null) {return old;}
            _notFailingPeers.put(peer, rv);
            _notFailingPeersList.add(peer);
            // Add to high cap only if we have room. Don't add to Fast - wait for reorg.
            if (_thresholdCapacityValue <= rv.getCapacityValue() && isSelectable(peer) &&
                _highCapacityPeers.size() < getMaximumHighCapPeers()) {
                _highCapacityPeers.put(peer, rv);
            }
            _strictCapacityOrder.add(rv);
        } finally {releaseWriteLock();}
        return rv;
    }

    /**
     * Add the new profile, returning the old value (or null if no profile existed)
     *
     */
    public PeerProfile addProfile(PeerProfile profile) {
        if (profile == null) {return null;}

        Hash peer = profile.getPeer();
        if (peer.equals(_us)) {return null;}
        if (_log.shouldInfo()) {_log.info("New profile created for [" + peer.toBase64().substring(0,6) + "]");}

        PeerProfile old = getProfile(peer);
        profile.coalesceStats();
        if (!getWriteLock()) {return old;}

        try {
            _notFailingPeers.put(peer, profile);
            if (old == null) {_notFailingPeersList.add(peer);}
            // Add to high cap only if we have room. Don't add to Fast - wait for reorg.
            if (_thresholdCapacityValue <= profile.getCapacityValue() && isSelectable(peer) &&
                _highCapacityPeers.size() < getMaximumHighCapPeers()) {
                _highCapacityPeers.put(peer, profile);
            }
            _strictCapacityOrder.add(profile);
        } finally {releaseWriteLock();}
        return old;
    }

    private int count(Map<Hash, PeerProfile> m) {
        getReadLock();
        try {return m.size();}
        finally {releaseReadLock();}
    }

    public int countFastPeers() {return count(_fastPeers);}
    public int countHighCapacityPeers() {return count(_highCapacityPeers);}
    public int countNotFailingPeers() {return count(_notFailingPeers);}

    /** @deprecated use ProfileManager.getPeersByCapability('f').size() */
    @Deprecated
    public int countWellIntegratedPeers() {return count(_wellIntegratedPeers);}
    @Deprecated
    public int countFailingPeers() {return 0;}

    public int countActivePeers() {
        int activePeers = 0;
        long hideBefore = _context.clock().now() - 4*60*60*1000;

        getReadLock();
        try {
            for (PeerProfile profile : _notFailingPeers.values()) {
                if (profile.getLastSendSuccessful() >= hideBefore) {activePeers++;}
                else if (profile.getLastHeardFrom() >= hideBefore) {activePeers++;}
            }
        } finally {releaseReadLock();}
        return activePeers;
    }

    // @since 0.9.50+
    public int countActivePeersInLastHour() {
        int activePeers = 0;
        long hideBefore = _context.clock().now() - 60*60*1000;

        getReadLock();
        try {
            for (PeerProfile profile : _notFailingPeers.values()) {
                if (profile.getIsActive(60*60*1000)) {activePeers++;}
                else if (profile.getLastSendSuccessful() >= hideBefore) {activePeers++;}
                else if (profile.getLastSendFailed() >= hideBefore) {activePeers++;}
                else if (profile.getLastHeardFrom() >= hideBefore) {activePeers++;}
            }
        } finally {releaseReadLock();}
        return activePeers;
    }

    private boolean isX(Map<Hash, PeerProfile> m, Hash peer) {
        getReadLock();
        try {return m.containsKey(peer);}
        finally {releaseReadLock();}
    }

    public boolean isFast(Hash peer) {return isX(_fastPeers, peer);}
    public boolean isHighCapacity(Hash peer) {return isX(_highCapacityPeers, peer);}
    public boolean isWellIntegrated(Hash peer) {return isX(_wellIntegratedPeers, peer);}

    /**
     *  Deprecated for now, always false
     *  @deprecated unused
     */
    @Deprecated
    public boolean isFailing(Hash peer) {
        // Always false so skip the lock
        //return isX(_failingPeers, peer);
        return false;
    }

    /** @since 0.8.8 */
    void clearProfiles() {
        if (!getWriteLock()) {return;}
        try {
            _fastPeers.clear();
            _highCapacityPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();
            _wellIntegratedPeers.clear();
            _strictCapacityOrder.clear();
        } finally {releaseWriteLock();}
    }

    /**
     * if a peer sends us more than 30 replies/hr in a searchReply that we cannot
     * fetch, stop listening to them.
     *
     */
    private final static int MAX_BAD_REPLIES_PER_HOUR = 30;

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
            Rate invalidReplyRate = invalidReplyRateStat.getRate(60*60*1000l);
            RateStat failedLookupRateStat = profile.getDBHistory().getFailedLookupRate();
            Rate failedLookupRate = failedLookupRateStat.getRate(60*60*1000l);
            if (invalidReplyRate.getCurrentTotalValue() > MAX_BAD_REPLIES_PER_HOUR ||
                invalidReplyRate.getLastTotalValue() > MAX_BAD_REPLIES_PER_HOUR ||
                failedLookupRate.getCurrentTotalValue() > MAX_BAD_REPLIES_PER_HOUR ||
                failedLookupRate.getLastTotalValue() > MAX_BAD_REPLIES_PER_HOUR) {return true;}
        }
        return false;
    }

    /**
     *  Only for console
     *
     *  @return true if successful, false if not found
     */
    public boolean exportProfile(Hash profile, OutputStream out) throws IOException {
        PeerProfile prof = getProfile(profile);
        boolean rv = prof != null;
        if (rv) {_persistenceHelper.writeProfile(prof, out);}
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
        selectFastPeers(howMany, exclude, matches, 0, null);
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
     * @param ipSet in/out param, use for multiple calls, may be null only if mask is 0
     * @since 0.9.53 added ipSet param
     *
     */
    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        getReadLock();
        try {locked_selectPeers(_fastPeers, howMany, exclude, matches, mask, ipSet);}
        finally {releaseReadLock();}
        if (matches.size() < howMany) {
            if (_log.shouldDebug()) {
                if (howMany != 1) {
                    _log.debug("Need " + howMany + " Fast peers for tunnel build -> " + matches.size() + " found, selecting remainder from High Capacity tier...");
                } else {
                    _log.debug("Need " + howMany + " Fast peer for tunnel build -> " + matches.size() + " found, selecting remainder from High Capacity tier...");
                }
            }
            selectHighCapacityPeers(howMany, exclude, matches, mask, ipSet);
        } else {
            if (_log.shouldDebug()) {
                if (howMany != 1) {_log.debug(howMany + " Fast peers selected for tunnel build");}
                else {_log.debug(howMany + " Fast peer selected for tunnel build");}
            }
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
     * @param mask 0-4
     * @param ipSet in/out param, use for multiple calls, may be null only if mask is 0
     * @since 0.9.53 added mask and ipSet params
     */
    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, SessionKey randomKey,
                                Slice subTierMode, int mask, MaskedIPSet ipSet) {
        getReadLock();
        try {
            if (subTierMode != Slice.SLICE_ALL) {
                int sz = _fastPeers.size();
                if (sz < 6 || (subTierMode.mask >= 3 && sz < 12))
                    subTierMode = Slice.SLICE_ALL;
            }
            if (subTierMode != Slice.SLICE_ALL)
                locked_selectPeers(_fastPeers, howMany, exclude, matches, randomKey, subTierMode, mask, ipSet);
            else
                locked_selectPeers(_fastPeers, howMany, exclude, matches, mask, ipSet);
        } finally {releaseReadLock();}
        if (matches.size() < howMany) {
            if (_log.shouldDebug())
                _log.debug("Need " + howMany + " Fast " + (howMany > 1 ? "peers" : "peer") + " for tunnel build -> " +
                           matches.size() + " found, selecting remainder from High Capacity tier...");
            selectHighCapacityPeers(howMany, exclude, matches, mask, ipSet);
        } else {
            if (_log.shouldDebug())
                _log.debug(howMany + " Fast " + (howMany > 1 ? "peers" : "peer") + " selected for tunnel build");
        }
        return;
    }

    /**
     * Return a set of Hashes for peers that have a high capacity
     *
     */
    public void selectHighCapacityPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectHighCapacityPeers(howMany, exclude, matches, 0, null);
    }

    /**
     * @param mask 0-4 Number of bytes to match to determine if peers in the same IP range should
     *             not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     * @param ipSet in/out param, use for multiple calls, may be null only if mask is 0
     * @since 0.9.53 added ipSet param
     */
    public void selectHighCapacityPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        getReadLock();
        try {
            // we only use selectHighCapacityPeers when we are selecting for PURPOSE_TEST
            // or we are falling back due to _fastPeers being too small, so we can always
            // exclude the fast peers
            locked_selectPeers(_highCapacityPeers, howMany, exclude, matches, mask, ipSet);
        } finally {releaseReadLock();}
        if (matches.size() < howMany) {
            if (_log.shouldDebug()) {
                _log.debug("Need " + (howMany > 1 ? "High Capacity peers" : "High Capacity peer") + " for tunnel build -> " + matches.size() +
                           " found, selecting remainder from non-failing peers...");
            }
            selectNotFailingPeers(howMany, exclude, matches, mask, ipSet);
        } else {
            if (_log.shouldDebug()) {_log.debug(howMany + " High Capacity peers selected for tunnel build");}
        }
        return;
    }

    /**
     * Return a set of Hashes for peers that are not failing, preferring ones that
     * we are already talking with
     *
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectNotFailingPeers(howMany, exclude, matches, false, 0, null);
    }

    /**
     * @param mask ignored, should call locked_selectPeers, to be fixed
     * @param ipSet ignored, should call locked_selectPeers, to be fixed
     * @since 0.9.53 added ipSet param
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        selectNotFailingPeers(howMany, exclude, matches, false, mask, ipSet);
    }

    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing) {
        selectNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0, null);
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
     * @param ipSet ignored, should call locked_selectPeers, to be fixed
     * @since 0.9.53 added ipSet param
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing,
                                      int mask, MaskedIPSet ipSet) {
        if (matches.size() < howMany) {selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, mask);}
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
     * @param exclude non-null, not-connected peers will NOT be added, as of 0.9.58
     */
    public void selectActiveNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectActiveNotFailingPeers(howMany, exclude, matches, 0, null);
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
     * @param exclude non-null, not-connected peers will NOT be added, as of 0.9.58
     * @param mask 0-4 Number of bytes to match to determine if peers in the same IP range should
     *             not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     * @param ipSet may be null only if mask is 0
     * @since 0.9.53
     */
    public void selectActiveNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        if (matches.size() < howMany) {
            List<Hash> connected = _context.commSystem().getEstablished();
            if (connected != null && connected.isEmpty()) {return;}
            getReadLock();
            try {locked_selectActive(connected, howMany, exclude, matches, mask, ipSet);}
            finally {releaseReadLock();}
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
     * @param exclude non-null, not-connected peers will NOT be added, as of 0.9.58
     * @param mask 0-4 Number of bytes to match to determine if peers in the same IP range should
     *             not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     * @param ipSet in/out param, use for multiple calls, may be null only if mask is 0
     * @since 0.9.53 added ipSet param
     */
    private void selectActiveNotFailingPeers2(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        if (matches.size() < howMany) {
            List<Hash> connected = _context.commSystem().getEstablished();
            if (connected != null && !connected.isEmpty()) {
                getReadLock();
                try {locked_selectActive(connected, howMany, exclude, matches, mask, ipSet);}
                finally {releaseReadLock();}
            }
        }
        if (matches.size() < howMany) {
            if (_log.shouldDebug())
                _log.debug("Need " + howMany + "  active, not failing peers for tunnel build -> " + matches.size() +
                           " found, selecting remainder from most reliable Failing peers...");
            selectNotFailingPeers(howMany, exclude, matches, mask, ipSet);
        } else {
            if (_log.shouldDebug())
                _log.debug(howMany + " most reliable Failing peers selected for tunnel build");
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
                        if (_log.shouldDebug())
                            _log.debug("Current [" + cur.toBase64().substring(0,6) + "] Matched? " + matches.contains(cur) +
                                      " - Excluded: " + exclude.size() + " peers");
                        continue;
                    } else if (onlyNotFailing && _highCapacityPeers.containsKey(cur)) {
                        // we don't want the good peers, just random ones
                        continue;
                    } else {
                        if (isSelectable(cur)) {selected.add(cur);}
                        else if (_log.shouldDebug()) {
                            _log.debug("Not selectable for tunnel build: [" + cur.toBase64().substring(0,6) + "]");
                        }
                    }
                }
            } finally {releaseReadLock();}

            StringBuilder buf = new StringBuilder();
            buf.append("Selecting all Not Failing peers - Strict? " + onlyNotFailing + "\n* Found " + selected.size() + " new peers: ");
            for (Hash h : selected) {
                buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
            }
            buf.append("\n* All: " + _notFailingPeersList.size() + "; Strict: " + _strictCapacityOrder.size());
            if (_log.shouldDebug()) {_log.debug(buf.toString());}
            matches.addAll(selected);
        }
        if (matches.size() < howMany) {
            if (_log.shouldDebug())
                _log.debug("Need " + howMany + " Not Failing peers for tunnel build -> " + matches.size() + " found, selecting remainder from most reliable Failing peers...");
            selectFailingPeers(howMany, exclude, matches);
        } else {
            if (_log.shouldDebug())
                _log.debug(howMany + " Not Failing peers selected for tunnel build");
        }
        return;
    }

    /**
     * I'm not quite sure why you'd want this... (other than for failover from the better results)
     *
     * @deprecated unused
     */
    @Deprecated
    private void selectFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        return;
    }

    /**
     * Find the hashes for all peers we are actively profiling
     *
     */
    public Set<Hash> selectAllPeers() {
        getReadLock();
        try {
            Set<Hash> allPeers = new HashSet<Hash>(_notFailingPeers.size() + _highCapacityPeers.size() + _fastPeers.size());
            allPeers.addAll(_notFailingPeers.keySet());
            allPeers.addAll(_highCapacityPeers.keySet());
            allPeers.addAll(_fastPeers.keySet());
            return allPeers;
        } finally {releaseReadLock();}
    }

    private static final long MIN_EXPIRE_TIME = 3*24*60*60*1000;
    private static final long MAX_EXPIRE_TIME = 4*7*24*60*60*1000;
    private static final long ADJUST_EXPIRE_TIME = 3*60*1000;
    private static final int ENOUGH_PROFILES = SystemVersion.isSlow() ? 1000 : 2000;
    private long _currentExpireTime = MAX_EXPIRE_TIME;

    /**
     * Place peers into the correct tier, as well as expand/contract and even drop profiles
     * according to whatever limits are in place.  Peer profiles are not coalesced during
     * this method, but the averages are recalculated.
     *
     */
    void reorganize() {reorganize(false, false);}

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
        long expireOlderThan = 7*24*60*60*1000;

        // drop profiles that we haven't spoken to in a while
        if (countNotFailingPeers() > ENOUGH_PROFILES) {expireOlderThan = 8*60*60*1000;}
        else {expireOlderThan = 24*60*60*1000;}

        if (shouldCoalesce && uptime > 60*60*1000 && countNotFailingPeers() > (ENOUGH_PROFILES / 2)) {
            getReadLock();
            try {
                long coalesceStart = System.currentTimeMillis();
                for (PeerProfile prof : _strictCapacityOrder) {
                    long lastGoodSend = prof.getLastSendSuccessful();
                    if (lastGoodSend < (expireOlderThan - coalesceStart)) {continue;}
                    prof.coalesceOnly(shouldDecay);
                }
                coalesceTime = (int) (System.currentTimeMillis() - coalesceStart);
            } finally {releaseReadLock();}
        }

        if (!getWriteLock()) {return;}
        long start = System.currentTimeMillis();
        try {
            Set<PeerProfile> allPeers = _strictCapacityOrder;
            Set<PeerProfile> reordered = new TreeSet<PeerProfile>(_comp);
            long sortStart = System.currentTimeMillis();
            for (Iterator<PeerProfile> iter = _strictCapacityOrder.iterator(); iter.hasNext();) {
                PeerProfile prof = iter.next();

                long lastGoodSend = prof.getLastSendSuccessful();
                if (lastGoodSend < (sortStart - expireOlderThan)) {
                    expiredCount++;
                    //_persistenceHelper.deleteOldProfiles(expireOlderThan);

                    if (_log.shouldInfo()) {
                        _log.info("Removed " + expiredCount + " expired profiles from memory");
                    }
                    continue; // drop
                }
                prof.updateValues();
                reordered.add(prof);
                profileCount++;
            }
            sortTime = System.currentTimeMillis() - sortStart;
            _strictCapacityOrder = reordered;

            long thresholdStart = System.currentTimeMillis();
            locked_calculateThresholds(allPeers);
            thresholdTime = System.currentTimeMillis() - thresholdStart;

            _fastPeers.clear();
            _highCapacityPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();
            _wellIntegratedPeers.clear();

            long placeStart = System.currentTimeMillis();

            for (PeerProfile profile : _strictCapacityOrder) {
                locked_placeProfile(profile);
            }

            locked_demoteHighCapAsNecessary();
            locked_promoteFastAsNecessary();
            locked_demoteFastAsNecessary();

            // we now use a random iterator in selectAllNotFailingPeers(),
            // as it was picking peers in-order before the first reorganization
            //Collections.shuffle(_notFailingPeersList, _context.random());

            placeTime = System.currentTimeMillis() - placeStart;
        } finally {releaseWriteLock();}


        if (_log.shouldInfo())
            _log.info("Profiles reorganized: " + expiredCount + " expired \n* Averages: [Integration: " + _thresholdIntegrationValue +
                      "] [Capacity: " + _thresholdCapacityValue + "] [Speed: " + _thresholdSpeedValue + "]");

        long total = System.currentTimeMillis() - start;
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
     * failing peers.  Perhaps it should build a seperate strict ordering by speed?
     * Nah, not worth the maintenance and memory overhead, at least not for now.
     *
     */
    private void locked_promoteFastAsNecessary() {
        int minFastPeers = getMinimumFastPeers();
        int numToPromote = minFastPeers - _fastPeers.size();
        if (numToPromote > 0) {
            if (_log.shouldInfo()) {_log.info("Need to explicitly promote " + numToPromote + " peers to Fast group");}
            long now = _context.clock().now();
            for (PeerProfile cur : _strictCapacityOrder) {
                if (!_fastPeers.containsKey(cur.getPeer())) {
                    if (!isSelectable(cur.getPeer())) {continue;} // skip peers we don't have in the netDb
                    //if (!cur.getIsActive(now)) {continue;} // skip inactive
                    if (_log.shouldInfo()) {_log.info("Promoting [" + cur.getPeer().toBase64().substring(0,6) + "] to Fast group");}
                    _fastPeers.put(cur.getPeer(), cur);
                    // no need to remove it from any of the other groups, since if it is
                    // fast, it has a high capacity, and it is not failing
                    numToPromote--;
                    if (numToPromote <= 0) {break;}
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
            if (_log.shouldInfo()) {
                _log.info("Need to explicitly demote " + numToDemote + " peers from Fast group");
            }
            Set<PeerProfile> sorted = new TreeSet<PeerProfile>(new SpeedComparator()); // sort by speed, slowest-first
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
                if (_highCapacityPeers.containsKey(iter.next().getPeer())) {i++;}
            }
            for (int i = 0; iter.hasNext() && i < numToDemote; ) {
                Hash h = iter.next().getPeer();
                if (_highCapacityPeers.remove(h) != null) {
                    _fastPeers.remove(h);
                    i++;
                }
            }
            if (_log.shouldInfo())
                _log.info("Demoted " + numToDemote + " peers from High Capacity group; new size is " + _highCapacityPeers.size() + " peers");
        }
    }

    /**
     * no more public stuff below
     */

    /**
     * Update the thresholds based on the profiles in this set.  Currently
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
        long now = _context.clock().now();
        for (PeerProfile profile : allPeers) {
            if (_us.equals(profile.getPeer())) continue;

            // exclude unreachable peers
            if (profile.wasUnreachable()) {
                if (_log.shouldInfo())
                    _log.info("Excluding [" + profile.getPeer().toBase64().substring(0,6) + "] from fast/highcap groups -> Unreachable");
                continue;
            }

            if (profile.getPeer() == null)
                continue;

            RouterInfo peerInfo = _context.netDb().lookupRouterInfoLocally(profile.getPeer());
            String bw = "K";
            if (peerInfo != null && peerInfo.getBandwidthTier() != null) {
                bw = peerInfo.getBandwidthTier();
            }
            if (peerInfo != null && (bw.equals("K") || bw.equals("L") || bw.equals("M"))) {
                if (_log.shouldInfo())
                    _log.info("Excluding [" + profile.getPeer().toBase64().substring(0,6) + "] from fast/highcap groups -> K, L or M tier");
                continue;
            }

            // only take into account active peers that aren't failing
            if (!profile.getIsActive(now)) {continue;}

            totalCapacity += profile.getCapacityValue();
            totalIntegration += profile.getIntegrationValue();
            reordered.add(profile);
        }

        locked_calculateCapacityThreshold(totalCapacity, reordered);
        locked_calculateSpeedThreshold(reordered);

        if (totalIntegration > 0) {_thresholdIntegrationValue = 1.0d * avg(totalIntegration, reordered.size());}
        else {_thresholdIntegrationValue = 1.0d;} // Make nobody rather than everybody well-integrated
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
            if (_log.shouldInfo())
                _log.info("Our average capacity (" + meanCapacity + ") is good and includes " +
                          numExceedingMean + " peer profiles");
            _thresholdCapacityValue = meanCapacity;
        } else if (meanCapacity > thresholdAtMedian && reordered.size() / 2 > minHighCapacityPeers) {
            // avg > median, get the min High Cap peers
            if (_log.shouldInfo())
                _log.info("Our average capacity (" + meanCapacity + ") is greater than the median,"
                          + " so we've satisified the threshold (" + thresholdAtMinHighCap + ") to get the min High Capacity peers");
            _thresholdCapacityValue = thresholdAtMinHighCap;
        } else if (reordered.size() / 2 >= minHighCapacityPeers) {
            // ok mean is skewed low, but we still have enough to use the median
            // We really don't want to be here, since the default is 5.0 and the median
            // is inevitably 5.01 or so.
            if (_log.shouldInfo())
                _log.info("Our average capacity (" + meanCapacity + ") is skewed under the median,"
                          + " so we're using the median threshold " + thresholdAtMedian);
            _thresholdCapacityValue = thresholdAtMedian;
        } else {
            // our average is doing well, but not enough peers
            if (_log.shouldInfo())
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
                if (speedBonus >= 9999999) {total -= 9999999;}
                if (count++ > minHighCapPeers / 2 * 3) {break;}
            } else {break;} // its ordered
        }

        if (count > 0)
            _thresholdSpeedValue = ((total / count) / 7) * 5;
        if (_log.shouldInfo())
            _log.info("Threshold value for speed: " + _thresholdSpeedValue + " (calculated from " + count + " profiles)");
    }


    /** simple average, or 0 if NaN */
    private final static double avg(double total, double quantity) {
        if ((total > 0) && (quantity > 0)) {return total/quantity;}
        else {return 0.0d;}
    }

    /** called after locking the reorganizeLock */
    private PeerProfile locked_getProfile(Hash peer) {
        PeerProfile cur = _notFailingPeers.get(peer);
        return cur;
    }

    /**
     * Select peers from the peer mapping, excluding appropriately and increasing the
     * matches set until it has howMany elements in it.
     *
     */
    private void locked_selectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude, Set<Hash> matches) {
        locked_selectPeers(peers, howMany, toExclude, matches, 0, null);
    }

    /**
     *
     * As of 0.9.24, checks for a netdb family match as well, unless mask == 0.
     *
     * @param mask 0-4 Number of bytes to match to determine if peers in the same IP range should
     *             not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     * @param ipSet may be null only if mask is 0
     * @since 0.9.53 added ipSet param
     */
        private void locked_selectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude, Set<Hash> matches,
                                        int mask, MaskedIPSet ipSet) {
        List<Hash> all = new ArrayList<Hash>(peers.keySet());
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
                ok = mask <= 0 || notRestricted(peer, ipSet, mask);
                if ((!ok) && _log.shouldWarn())
                    _log.warn("IP address restriction prevents [" + peer.toBase64().substring(0,6) + "] from joining " + matches);
            } else {
                if (toExclude != null)
                    toExclude.add(peer);
            }
            if (ok)
                matches.add(peer);
            else
                matches.remove(peer);
        }
    }

    /**
     *
     * For efficiency. Rather than iterating through _notFailingPeers looking for connected peers,
     * iterate through the connected peers and then check if failing.
     *
     * @param mask 0-4 Number of bytes to match to determine if peers in the same IP range should
     *             not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     * @param ipSet may be null only if mask is 0
     * @since 0.9.58
     */
    private void locked_selectActive(List<Hash> connected, int howMany, Set<Hash> toExclude, Set<Hash> matches,
                                     int mask, MaskedIPSet ipSet) {
        // use RandomIterator to avoid shuffling the whole thing
        for (Iterator<Hash> iter = new RandomIterator<Hash>(connected); (matches.size() < howMany) && iter.hasNext(); ) {
            Hash peer = iter.next();
            if (toExclude != null && toExclude.contains(peer))
                continue;
            if (matches.contains(peer))
                continue;
            if (_us.equals(peer))
                continue;
            // we assume if connected, it's fine, don't look in _notFailingPeers
            boolean ok = isSelectable(peer);
            if (ok) {
                ok = mask <= 0 || notRestricted(peer, ipSet, mask);
                if ((!ok) && _log.shouldWarn())
                    _log.warn("IP restriction prevents " + peer + " from joining " + matches);
            } else {
                if (toExclude != null)
                    toExclude.add(peer);
            }
            if (ok)
                matches.add(peer);
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
        if (IPSet.containsAny(peerIPs)) {return false;}
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
     * @param mask is 1-4 (number of bytes to match)
     * @param IPMatches all IPs so far, modified by this routine
     * @since 0.9.53 added mask/ipSet params
     */
    private void locked_selectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude,
                                    Set<Hash> matches, SessionKey randomKey, Slice subTierMode,
                                    int mask, MaskedIPSet ipSet) {
        List<Hash> all = new ArrayList<Hash>(peers.keySet());
        byte[] rk = randomKey.getData();
        // we use the first half of the random key here,
        // the second half is used in TunnelPeerSelector.
        long k0 = DataHelper.fromLong8(rk, 0);
        long k1 = DataHelper.fromLong8(rk, 8);

        // use RandomIterator to avoid shuffling the whole thing
        for (Iterator<Hash> iter = new RandomIterator<Hash>(all); (matches.size() < howMany) && iter.hasNext(); ) {
            Hash peer = iter.next();
            if (toExclude != null && toExclude.contains(peer))
                continue;
            if (matches.contains(peer))
                continue;
            if (_us.equals(peer))
                continue;
            int subTier = getSubTier(peer, k0, k1);
            if ((subTier & subTierMode.mask) != subTierMode.val)
                continue;
            boolean ok = isSelectable(peer);
            if (ok) {
                ok = mask <= 0 || notRestricted(peer, ipSet, mask);
                if ((!ok) && _log.shouldWarn())
                    _log.warn("IP address restriction prevents [" + peer.toBase64().substring(0,6) + "] from joining " + matches);
            } else {
                if (toExclude != null)
                    toExclude.add(peer);
            }
            if (ok)
                matches.add(peer);
            else
                matches.remove(peer);
        }
    }

    /**
     *  Implement a random, deterministic split into 4 groups that cannot be predicted by
     *  others.
     *
     *  @param k0 random key part 1
     *  @param k1 random key part 2
     *  @return 0-3
     */
    private int getSubTier(Hash peer, long k0, long k1) {
        return ((int) SipHashInline.hash24(k0, k1, peer.getData())) & 0x03;
    }

    public boolean isSelectable(Hash peer) {
        NetworkDatabaseFacade netDb = _context.netDb();
        // the CLI shouldn't depend upon the netDb
        if (netDb == null) return true;
        if (_context.router() == null) return true;
        if ( (_context.banlist() != null) && (_context.banlist().isBanlisted(peer)) ) {
             if (_log.shouldDebug())
                 _log.debug("Not selecting [" + peer.toBase64().substring(0,6) + "] for local tunnel builds -> Router is banlisted");
            return false; // never select a banlisted peer
        }

        RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
        if (info != null) {
            String tier = DataHelper.stripHTML(info.getBandwidthTier());
            if (info.isHidden()) {
                if (_log.shouldWarn()) {
                    _log.warn("Not selecting [" + peer.toBase64().substring(0,6) + "] for local tunnel builds -> Router is hidden");
                }
                return false;
            } else if (tier.equals("L") || tier.equals("M") || tier.equals("N")) {
                if (_log.shouldInfo())
                    _log.info("Not selecting [" + peer.toBase64().substring(0,6) + "] for local tunnel builds -> Router is L, M or N bandwidth tier");
                return false;
            } else {
                boolean exclude = TunnelPeerSelector.shouldExclude(_context, info);
                if (exclude) {
                    // if (_log.shouldWarn())
                    //     _log.warn("Router [" + peer.toBase64().substring(0,6) + "] has capabilities or other stats suggesting we avoid it");
                    return false;
                } else {
                    // if (_log.shouldInfo())
                    //     _log.info("Router [" + peer.toBase64().substring(0,6) + "] is locally known, allowing its use");
                    return true;
                }
            }
        } else {
            // if (_log.shouldWarn())
            //    _log.warn("Router [" + peer.toBase64().substring(0,6) + "] is NOT locally known, disallowing its use");
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
        int minHighCap = _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS);
        _fastPeers.remove(peer);
        _highCapacityPeers.remove(peer);
        _wellIntegratedPeers.remove(peer);
        _notFailingPeers.put(peer, profile);
        _notFailingPeersList.add(peer);
        // if not selectable for a tunnel (banlisted for example),
        // don't allow them in the high-cap pool, what would the point of that be?
        if (_thresholdCapacityValue <= profile.getCapacityValue() &&
            isSelectable(peer) && (_context.commSystem() == null ||
            !_context.commSystem().isInStrictCountry(peer))) {
            _highCapacityPeers.put(peer, profile);
            if (_log.shouldDebug()) {_log.debug("Promoting [" + peer.toBase64().substring(0,6) + "] to High Capacity group");}
            if (_thresholdSpeedValue <= profile.getSpeedValue()) {
                if (!profile.getIsActive()) {
                    if (_log.shouldDebug()) {
                        _log.debug("Not promoting [" + peer.toBase64().substring(0,6) + "] to Fast group -> Inactive");
                    }
                } else {
                    _fastPeers.put(peer, profile);
                    if (_log.shouldDebug()) {_log.debug("Promoting [" + peer.toBase64().substring(0,6) + "] to Fast group");}
                }
            }

        } else if (countHighCapacityPeers() < minHighCap && isSelectable(peer) && !_context.commSystem().isInStrictCountry(peer)) {
            _highCapacityPeers.put(peer, profile);
            // not high capacity, but not failing (yet)
        }
        // We aren't using the well-integrated list yet...
        // But by observation, the floodfill peers are often not in the
        // high-capacity group, so let's not require a peer to be high-capactiy
        // to call him well-integrated.
        // This could be used later to see if a floodfill peer is for real.
        if (_thresholdIntegrationValue <= profile.getIntegrationValue()) {
            _wellIntegratedPeers.put(peer, profile);
            if (_log.shouldDebug()) {
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
    private boolean shouldDrop(PeerProfile profile) {return false;}

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
        if (_context.router() == null) return DEFAULT_MINIMUM_FAST_PEERS;
        int known = _context.netDb().getKnownRouters();
        if (known > 3000) {return _context.getProperty(PROP_MINIMUM_FAST_PEERS, Math.max(known / 15, DEFAULT_MINIMUM_FAST_PEERS));}
        else {return _context.getProperty(PROP_MINIMUM_FAST_PEERS, DEFAULT_MINIMUM_FAST_PEERS);}
    }

    /** fixme add config  @since 0.7.10 */
    protected int getMaximumFastPeers() {
        if (_context.router() == null) return ABSOLUTE_MAX_FAST_PEERS;
        int known = _context.netDb().getKnownRouters();
        if (known > 3000) {return Math.max(known / 12, ABSOLUTE_MAX_FAST_PEERS);}
        else {return ABSOLUTE_MAX_FAST_PEERS;}
    }

    /** fixme add config  @since 0.7.11 */
    protected int getMaximumHighCapPeers() {
        if (_context.router() == null) return ABSOLUTE_MAX_HIGHCAP_PEERS;
        int known = _context.netDb().getKnownRouters();
        if (known > 3000) {return (Math.max(known / 10, ABSOLUTE_MAX_HIGHCAP_PEERS));}
        else {return ABSOLUTE_MAX_HIGHCAP_PEERS;}
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
        if (_context.router() == null) return DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS;
        int known = _context.netDb().getKnownRouters();
        if (known > 3000) {
            return _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, Math.max(known / 15, DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS));
        } else {
            return _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS);
        }
    }

    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
    private final static String num(double num) {synchronized (_fmt) {return _fmt.format(num);} }

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
            PeerProfile profile = helper.readProfile(new java.io.File(args[i]), 0);
            if (profile == null) {
                System.err.println("Could not load profile " + args[i]);
                continue;
            }
            organizer.addProfile(profile);
        }
        organizer.reorganize();
        DecimalFormat fmt = new DecimalFormat("0000.0");

        long now = ctx.clock().now();
        for (Hash peer : organizer.selectAllPeers()) {
            PeerProfile profile = organizer.getProfile(peer);
            if (!profile.getIsActive(now)) {
                System.out.println("Peer " + peer.toBase64().substring(0,4)
                           + " [" + (organizer.isFast(peer) ? "IF+R" :
                                     organizer.isHighCapacity(peer) ? "IR  " :
                                     organizer.isFailing(peer) ? "IX  " : "I   ") + "]: "
                           + " Speed:\t" + fmt.format(profile.getSpeedValue())
                           + " Capacity:\t" + fmt.format(profile.getCapacityValue())
                           + " Integration:\t" + fmt.format(profile.getIntegrationValue())
                           + " Active?\t" + profile.getIsActive(now));
            } else {
                System.out.println("Peer " + peer.toBase64().substring(0,4)
                           + " [" + (organizer.isFast(peer) ? "F+R " :
                                     organizer.isHighCapacity(peer) ? "R   " :
                                     organizer.isFailing(peer) ? "X   " : "    ") + "]: "
                           + " Speed:\t" + fmt.format(profile.getSpeedValue())
                           + " Capacity:\t" + fmt.format(profile.getCapacityValue())
                           + " Integration:\t" + fmt.format(profile.getIntegrationValue())
                           + " Active?\t" + profile.getIsActive(now));
            }
        }

        System.out.println("Thresholds:");
        System.out.println("Speed:       " + num(organizer.getSpeedThreshold()) + " (" + organizer.countFastPeers() + " fast peers)");
        System.out.println("Capacity:    " + num(organizer.getCapacityThreshold()) + " (" + organizer.countHighCapacityPeers() + " reliable peers)");
    }

}
