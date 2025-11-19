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
 * Organizes peer profiles into tiers (fast, high-capacity, etc.) based on performance metrics.
 * This class does not actively update profiles — call reorganize() periodically.
 */
public class ProfileOrganizer {
    private final Log _log;
    private final RouterContext _context;
    private final Map<Hash, PeerProfile> _fastPeers;
    private final Map<Hash, PeerProfile> _highCapacityPeers;
    private final Map<Hash, PeerProfile> _wellIntegratedPeers;
    private final Map<Hash, PeerProfile> _notFailingPeers;
    private final List<Hash> _notFailingPeersList;
    private Hash _us;
    private final ProfilePersistenceHelper _persistenceHelper;
    private Set<PeerProfile> _strictCapacityOrder;
    private double _thresholdSpeedValue;
    private double _thresholdCapacityValue;
    private double _thresholdIntegrationValue;
    private final InverseCapacityComparator _comp;

    public static final String PROP_MINIMUM_FAST_PEERS = "profileOrganizer.minFastPeers";
    public static final int DEFAULT_MINIMUM_FAST_PEERS = 400;
    private static final int DEFAULT_MAXIMUM_FAST_PEERS = 500;
    private static final int ABSOLUTE_MAX_FAST_PEERS = 600;

    public static final String PROP_MINIMUM_HIGH_CAPACITY_PEERS = "profileOrganizer.minHighCapacityPeers";
    public static final int DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS = 500;
    private static final int ABSOLUTE_MAX_HIGHCAP_PEERS = 800;

    private static final long[] RATES = {
        60 * 1000L,
        5 * 60 * 1000L,
        10 * 60 * 1000L,
        30 * 60 * 1000L,
        60 * 60 * 1000L,
        24 * 60 * 60 * 1000L
    };

    private final ReentrantReadWriteLock _reorganizeLock = new ReentrantReadWriteLock(false);

    public ProfileOrganizer(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(ProfileOrganizer.class);
        _comp = new InverseCapacityComparator();
        _fastPeers = new HashMap<>(512);
        _highCapacityPeers = new HashMap<>(512);
        _notFailingPeersList = new ArrayList<>(4096);
        _notFailingPeers = new HashMap<>(4096);
        _persistenceHelper = new ProfilePersistenceHelper(_context);
        _strictCapacityOrder = new TreeSet<>(_comp);
        _wellIntegratedPeers = new HashMap<>(512);

        _context.statManager().createRateStat("peer.profileCoalesceTime", "Time to coalesce peer stats (ms)", "Peers", RATES);
        _context.statManager().createRateStat("peer.profilePlaceTime", "Time to sort peers into tiers (ms)", "Peers", RATES);
        _context.statManager().createRateStat("peer.profileReorgTime", "Time to reorganize peers (ms)", "Peers", RATES);
        _context.statManager().createRateStat("peer.profileThresholdTime", "Time to determine tier thresholds (ms)", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.failedLookupRate", "NetDb Lookup failure rate", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.profileSortTime", "Time to sort peers (ms)", "Peers", RATES);
    }

    private void getReadLock() {_reorganizeLock.readLock().lock();}
    private boolean tryReadLock() {return _reorganizeLock.readLock().tryLock();}
    private void releaseReadLock() {_reorganizeLock.readLock().unlock();}
    private boolean tryWriteLock() {return _reorganizeLock.writeLock().tryLock();}
    private boolean getWriteLock() {
        try {
            boolean rv = _reorganizeLock.writeLock().tryLock(3000, TimeUnit.MILLISECONDS);
            if (!rv && _log.shouldWarn()) {
                _log.warn("No lock, size is: " + _reorganizeLock.getQueueLength(), new Exception("rats"));
            }
            return rv;
        } catch (InterruptedException ignored) {
            return false;
        }
    }
    private void releaseWriteLock() {_reorganizeLock.writeLock().unlock();}

    public void setUs(Hash us) {_us = us;}
    public Hash getUs() {return _us;}

    public double getSpeedThreshold() {return _thresholdSpeedValue;}
    public double getCapacityThreshold() {return _thresholdCapacityValue;}
    public double getIntegrationThreshold() {return _thresholdIntegrationValue;}

    public PeerProfile getProfile(Hash peer) {
        if (peer != null && peer.equals(_us)) return null;
        getReadLock();
        try {return locked_getProfile(peer);}
        finally {releaseReadLock();}
    }

    public PeerProfile getProfileNonblocking(Hash peer) {
        if (peer != null && peer.equals(_us)) return null;
        if (tryReadLock()) {
            try {return locked_getProfile(peer);}
            finally {releaseReadLock();}
        }
        return null;
    }

    PeerProfile getOrCreateProfileNonblocking(Hash peer) {
        if (peer == null || peer.equals(_us) || !tryReadLock()) return null;

        PeerProfile rv;
        try {rv = locked_getProfile(peer);}
        finally {releaseReadLock();}
        if (rv != null) return rv;

        rv = new PeerProfile(_context, peer);
        rv.setLastHeardAbout(rv.getFirstHeardAbout());
        rv.coalesceStats();
        if (!tryWriteLock()) return null;

        try {
            PeerProfile old = locked_getProfile(peer);
            if (old != null) return old;
            _notFailingPeers.put(peer, rv);
            _notFailingPeersList.add(peer);
            if (_thresholdCapacityValue <= rv.getCapacityValue() && isSelectable(peer) &&
                _highCapacityPeers.size() < getMaximumHighCapPeers()) {
                _highCapacityPeers.put(peer, rv);
            }
            _strictCapacityOrder.add(rv);
        } finally {releaseWriteLock();}
        return rv;
    }

    public PeerProfile addProfile(PeerProfile profile) {
        if (profile == null) return null;
        Hash peer = profile.getPeer();
        if (peer.equals(_us)) return null;

        if (_log.shouldInfo()) {
            _log.info("New profile created for [" + peer.toBase64().substring(0,6) + "]");
        }

        PeerProfile old = getProfile(peer);
        profile.coalesceStats();
        if (!getWriteLock()) return old;

        try {
            _notFailingPeers.put(peer, profile);
            if (old == null) _notFailingPeersList.add(peer);
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

    public int countActivePeers() {
        int activePeers = 0;
        long hideBefore = _context.clock().now() - 4*60*60*1000;

        getReadLock();
        try {
            for (PeerProfile profile : _notFailingPeers.values()) {
                if (profile.getLastSendSuccessful() >= hideBefore || profile.getLastHeardFrom() >= hideBefore) {
                    activePeers++;
                }
            }
        } finally {releaseReadLock();}
        return activePeers;
    }

    public int countActivePeersInLastHour() {
        int activePeers = 0;
        long hideBefore = _context.clock().now() - 60*60*1000;

        getReadLock();
        try {
            for (PeerProfile profile : _notFailingPeers.values()) {
                if (profile.getIsActive(60*60*1000) ||
                    profile.getLastSendSuccessful() >= hideBefore ||
                    profile.getLastSendFailed() >= hideBefore ||
                    profile.getLastHeardFrom() >= hideBefore) {
                    activePeers++;
                }
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

    void clearProfiles() {
        if (!getWriteLock()) return;
        try {
            _fastPeers.clear();
            _highCapacityPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();
            _wellIntegratedPeers.clear();
            _strictCapacityOrder.clear();
        } finally {releaseWriteLock();}
    }

    private static final int MAX_BAD_REPLIES_PER_HOUR = 40;

    public boolean peerSendsBadReplies(Hash peer) {
        PeerProfile profile = getProfile(peer);
        if (profile != null && profile.getIsExpandedDB()) {
            RateStat invalidReplyRateStat = profile.getDBHistory().getInvalidReplyRate();
            Rate invalidReplyRate = invalidReplyRateStat.getRate(60 * 60 * 1000L);
            RateStat failedLookupRateStat = profile.getDBHistory().getFailedLookupRate();
            Rate failedLookupRate = failedLookupRateStat.getRate(60 * 60 * 1000L);
            return invalidReplyRate.getCurrentTotalValue() > MAX_BAD_REPLIES_PER_HOUR ||
                   invalidReplyRate.getLastTotalValue() > MAX_BAD_REPLIES_PER_HOUR ||
                   failedLookupRate.getCurrentTotalValue() > MAX_BAD_REPLIES_PER_HOUR ||
                   failedLookupRate.getLastTotalValue() > MAX_BAD_REPLIES_PER_HOUR;
        }
        return false;
    }

    public boolean exportProfile(Hash profile, OutputStream out) throws IOException {
        PeerProfile prof = getProfile(profile);
        boolean rv = prof != null;
        if (rv) {_persistenceHelper.writeProfile(prof, out);}
        return rv;
    }

    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectFastPeers(howMany, exclude, matches, 0, null);
    }

    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        getReadLock();
        try {locked_selectPeers(_fastPeers, howMany, exclude, matches, mask, ipSet);}
        finally {releaseReadLock();}
        if (matches.size() < howMany) {
            if (_log.shouldDebug()) {
                _log.debug("Need " + howMany + " Fast peers for tunnel build -> " + matches.size() +
                           " found, selecting remainder from High Capacity tier...");
            }
            selectHighCapacityPeers(howMany, exclude, matches, mask, ipSet);
        }
    }

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
                _log.debug("Need " + howMany + " Fast peers for tunnel build -> " + matches.size() +
                           " found, selecting remainder from High Capacity tier...");
            selectHighCapacityPeers(howMany, exclude, matches, mask, ipSet);
        }
    }

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

    public void selectHighCapacityPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectHighCapacityPeers(howMany, exclude, matches, 0, null);
    }

    public void selectHighCapacityPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        getReadLock();
        try {
            locked_selectPeers(_highCapacityPeers, howMany, exclude, matches, mask, ipSet);
        } finally {releaseReadLock();}
        if (matches.size() < howMany) {
            if (_log.shouldDebug()) {
                _log.debug("Need " + (howMany > 1 ? "High Capacity peers" : "High Capacity peer") +
                           " for tunnel build -> " + matches.size() + " found, selecting from non-failing peers...");
            }
            selectNotFailingPeers(howMany, exclude, matches, mask, ipSet);
        }
    }

    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectNotFailingPeers(howMany, exclude, matches, false, 0, null);
    }

    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        selectNotFailingPeers(howMany, exclude, matches, false, mask, ipSet);
    }

    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing) {
        selectNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0, null);
    }

    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing,
                                     int mask, MaskedIPSet ipSet) {
        if (matches.size() < howMany) {
            selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, mask);
        }
    }

    public void selectActiveNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectActiveNotFailingPeers(howMany, exclude, matches, 0, null);
    }

    public void selectActiveNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        if (matches.size() < howMany) {
            List<Hash> connected = _context.commSystem().getEstablished();
            if (connected != null && !connected.isEmpty()) {
                getReadLock();
                try {locked_selectActive(connected, howMany, exclude, matches, mask, ipSet);}
                finally {releaseReadLock();}
            }
        }
    }

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
                _log.debug("Need " + howMany + " active, not failing peers -> " + matches.size() +
                           " found, selecting remainder from non-failing peers...");
            selectNotFailingPeers(howMany, exclude, matches, mask, ipSet);
        }
    }

    public void selectAllNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing) {
        selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0);
    }

    private void selectAllNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing, int mask) {
        if (matches.size() < howMany) {
            int needed = howMany - matches.size();
            List<Hash> selected = new ArrayList<>(needed);
            getReadLock();
            try {
                for (Iterator<Hash> iter = new RandomIterator<>(_notFailingPeersList); selected.size() < needed && iter.hasNext(); ) {
                    Hash cur = iter.next();
                    if (matches.contains(cur) || (exclude != null && exclude.contains(cur))) continue;
                    if (onlyNotFailing && _highCapacityPeers.containsKey(cur)) continue;
                    if (isSelectable(cur)) selected.add(cur);
                }
            } finally {
                releaseReadLock();
            }
            matches.addAll(selected);
        }

        if (matches.size() < howMany) {
            if (_log.shouldDebug()) {
                _log.debug("Need " + howMany + " Not Failing peers -> " + matches.size() +
                           " found, selecting remainder from general peers...");
            }

            Set<Hash> allPeers = selectAllPeers();
            for (Hash peer : allPeers) {
                if (matches.size() >= howMany) break;
                if (matches.contains(peer) || (exclude != null && exclude.contains(peer))) continue;
                if (isSelectable(peer)) matches.add(peer);
            }
        }
    }

    public Set<Hash> selectAllPeers() {
        getReadLock();
        try {
            Set<Hash> allPeers = new HashSet<>(_notFailingPeers.size() + _highCapacityPeers.size() + _fastPeers.size());
            allPeers.addAll(_notFailingPeers.keySet());
            allPeers.addAll(_highCapacityPeers.keySet());
            allPeers.addAll(_fastPeers.keySet());
            return allPeers;
        } finally {releaseReadLock();}
    }

    private static final long MIN_EXPIRE_TIME = 3 * 24 * 60 * 60 * 1000;
    private static final long MAX_EXPIRE_TIME = 4 * 7 * 24 * 60 * 60 * 1000;
    private static final int ENOUGH_PROFILES = SystemVersion.isSlow() ? 1000 : 2000;

    void reorganize() {
        reorganize(false, false);
    }

    void reorganize(boolean shouldCoalesce, boolean shouldDecay) {
        long now = _context.clock().now();
        long sortStart = System.currentTimeMillis();
        int profileCount = 0;
        int expiredCount = 0;

        long expireOlderThan = countNotFailingPeers() > ENOUGH_PROFILES
            ? 8 * 60 * 60 * 1000
            : 24 * 60 * 60 * 1000;

        if (shouldCoalesce && _context.router() != null &&
            _context.router().getUptime() > 60 * 60 * 1000 &&
            countNotFailingPeers() > (ENOUGH_PROFILES / 2)) {
            getReadLock();
            try {
                for (PeerProfile prof : _strictCapacityOrder) {
                    if (prof.getLastSendSuccessful() >= now - expireOlderThan) {
                        prof.coalesceOnly(shouldDecay);
                    }
                }
            } finally {
                releaseReadLock();
            }
        }

        Set<PeerProfile> allPeers;
        getReadLock();
        try {allPeers = new HashSet<>(_strictCapacityOrder);}
        finally {releaseReadLock();}

        Set<PeerProfile> reordered = new TreeSet<>(_comp);
        double totalCapacity = 0;
        double totalIntegration = 0;

        for (PeerProfile profile : allPeers) {
            if (_us.equals(profile.getPeer())) continue;
            if (profile.wasUnreachable()) continue;

            RouterInfo peerInfo = _context.netDb().lookupRouterInfoLocally(profile.getPeer());
            String bwTier = peerInfo != null && peerInfo.getBandwidthTier() != null
                ? peerInfo.getBandwidthTier() : "K";

            if (bwTier.equals("K") || bwTier.equals("L") || bwTier.equals("M")) continue;

            if (!profile.getIsActive(now)) continue;

            totalCapacity += profile.getCapacityValue();
            totalIntegration += profile.getIntegrationValue();
            reordered.add(profile);
        }

        int numNotFailing = reordered.size();
        double meanCapacity = avg(totalCapacity, numNotFailing);
        double thresholdAtMedian = 0;
        double thresholdAtMinHighCap = 0;
        double thresholdAtLowest = CapacityCalculator.GROWTH_FACTOR;
        int cur = 0;
        int numExceedingMean = 0;

        for (PeerProfile profile : reordered) {
            double val = profile.getCapacityValue();
            if (val > meanCapacity) numExceedingMean++;
            if (cur == reordered.size() / 2) thresholdAtMedian = val;
            if (cur == getMinimumHighCapacityPeers() - 1) thresholdAtMinHighCap = val;
            if (cur == reordered.size() - 1) thresholdAtLowest = val;
            cur++;
        }

        double newCapacityThreshold = calculateCapacityThreshold(
            meanCapacity, numExceedingMean, reordered.size(),
            thresholdAtMedian, thresholdAtMinHighCap, thresholdAtLowest
        );

        double newIntegrationThreshold = numNotFailing > 0 ? totalIntegration / numNotFailing : 1.0d;

        double newSpeedThreshold = calculateSpeedThreshold(reordered);

        long sortTime = System.currentTimeMillis() - sortStart;

        if (!getWriteLock()) {
            _log.warn("Failed to acquire write lock during reorganize -> Skipping update...");
            return;
        }

        try {
            Set<PeerProfile> newStrictCapacityOrder = new TreeSet<>(_comp);
            for (PeerProfile profile : _strictCapacityOrder) {
                if (profile.getLastSendSuccessful() >= now - expireOlderThan) {
                    profile.updateValues();
                    newStrictCapacityOrder.add(profile);
                    profileCount++;
                } else {
                    expiredCount++;
                }
            }

            _thresholdCapacityValue = newCapacityThreshold;
            _thresholdIntegrationValue = newIntegrationThreshold;
            _thresholdSpeedValue = newSpeedThreshold;

            _fastPeers.clear();
            _highCapacityPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();
            _wellIntegratedPeers.clear();

            for (PeerProfile profile : newStrictCapacityOrder) {
                locked_placeProfile(profile);
            }

            _strictCapacityOrder = newStrictCapacityOrder;

            if (_log.shouldInfo()) {
                _log.info("Profiles reorganized: " + expiredCount + " expired\n" +
                          "* Averages: [Integration: " + _thresholdIntegrationValue +
                          "] [Capacity: " + _thresholdCapacityValue +
                          "] [Speed: " + _thresholdSpeedValue + "]");
            }

            long total = System.currentTimeMillis() - sortStart;
            _context.statManager().addRateData("peer.profileSortTime", sortTime, profileCount);
            _context.statManager().addRateData("peer.profileThresholdTime", System.currentTimeMillis() - sortStart, profileCount);
            _context.statManager().addRateData("peer.profilePlaceTime", System.currentTimeMillis() - sortStart, profileCount);
            _context.statManager().addRateData("peer.profileReorgTime", total, profileCount);

        } finally {
            releaseWriteLock();
        }
    }

    private double calculateCapacityThreshold(double meanCapacity, int numExceedingMean, int totalPeers,
                                             double thresholdAtMedian, double thresholdAtMinHighCap,
                                             double thresholdAtLowest) {
        int minHighCap = getMinimumHighCapacityPeers();

        if (numExceedingMean >= minHighCap) {
            return meanCapacity;
        } else if (meanCapacity > thresholdAtMedian && totalPeers / 2 > minHighCap) {
            return thresholdAtMinHighCap;
        } else if (totalPeers / 2 >= minHighCap) {
            return thresholdAtMedian;
        } else {
            return Math.max(thresholdAtMinHighCap, thresholdAtLowest);
        }
    }

    private double calculateSpeedThreshold(Set<PeerProfile> reordered) {
        double total = 0;
        int count = 0;
        int minHighCap = getMinimumHighCapacityPeers();

        for (PeerProfile profile : reordered) {
            if (profile.getCapacityValue() >= _thresholdCapacityValue) {
                total += profile.getSpeedValue();
                int speedBonus = profile.getSpeedBonus();
                if (speedBonus >= 9999999) total -= 9999999;
                count++;
                if (count > minHighCap / 2 * 3) break;
            } else {
                break;
            }
        }

        return count > 0 ? ((total / count) / 7) * 5 : 0;
    }

    private final static double avg(double total, double quantity) {
        return quantity > 0 ? total / quantity : 0.0d;
    }

    private PeerProfile locked_getProfile(Hash peer) {
        return _notFailingPeers.get(peer);
    }

    private void locked_selectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude,
                                    Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        List<Hash> all = new ArrayList<>(peers.keySet());

        for (Iterator<Hash> iter = new RandomIterator<>(all); matches.size() < howMany && iter.hasNext(); ) {
            Hash peer = iter.next();
            if (toExclude != null && toExclude.contains(peer)) continue;
            if (matches.contains(peer)) continue;
            if (_us != null && _us.equals(peer)) continue;
            boolean ok = isSelectable(peer);
            if (ok) {
                ok = mask <= 0 || notRestricted(peer, ipSet, mask);
            } else {
                if (toExclude != null) toExclude.add(peer);
            }
            if (ok) matches.add(peer);
        }
    }

    private void locked_selectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude,
                                    Set<Hash> matches, SessionKey randomKey, Slice subTierMode,
                                    int mask, MaskedIPSet ipSet) {
        List<Hash> all = new ArrayList<>(peers.keySet());
        byte[] rk = randomKey.getData();
        long k0 = DataHelper.fromLong8(rk, 0);
        long k1 = DataHelper.fromLong8(rk, 8);

        for (Iterator<Hash> iter = new RandomIterator<>(all); matches.size() < howMany && iter.hasNext(); ) {
            Hash peer = iter.next();
            if (toExclude != null && toExclude.contains(peer)) continue;
            if (matches.contains(peer)) continue;
            if (_us.equals(peer)) continue;

            int subTier = getSubTier(peer, k0, k1);
            if ((subTier & subTierMode.mask) != subTierMode.val) continue;

            boolean ok = isSelectable(peer);
            if (ok) {
                ok = mask <= 0 || notRestricted(peer, ipSet, mask);
            } else if (toExclude != null) {
                toExclude.add(peer);
            }

            if (ok) matches.add(peer);
        }
    }

    private void locked_selectActive(List<Hash> connected, int howMany, Set<Hash> toExclude,
                                    Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        for (Iterator<Hash> iter = new RandomIterator<>(connected); matches.size() < howMany && iter.hasNext(); ) {
            Hash peer = iter.next();
            if (toExclude != null && toExclude.contains(peer)) continue;
            if (matches.contains(peer)) continue;
            if (_us.equals(peer)) continue;
            boolean ok = isSelectable(peer);
            if (ok) {
                ok = mask <= 0 || notRestricted(peer, ipSet, mask);
            } else if (toExclude != null) {
                toExclude.add(peer);
            }

            if (ok) matches.add(peer);
        }
    }

    private String getSubnetDescription(int mask) {
        switch (mask) {
            case 0: return "none";
            case 1: return "/8";
            case 2: return "/16";
            case 3: return "/24";
            case 4: return "/32";
            default: return "/unknown";
        }
    }

    private boolean notRestricted(Hash peer, MaskedIPSet ipSet, int mask) {
        Set<String> peerIPs = new MaskedIPSet(_context, peer, mask);
        if (ipSet.containsAny(peerIPs)) return false;
        ipSet.addAll(peerIPs);
        return true;
    }

    private int getSubTier(Hash peer, long k0, long k1) {
        return ((int) SipHashInline.hash24(k0, k1, peer.getData())) & 0x03;
    }

    public boolean isSelectable(Hash peer) {
        NetworkDatabaseFacade netDb = _context.netDb();
        if (netDb == null) return true;
        if (_context.router() == null) return true;
        if (_context.banlist() != null && _context.banlist().isBanlisted(peer)) return false;

        RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
        if (info != null) {
            String tier = DataHelper.stripHTML(info.getBandwidthTier());
            if (info.isHidden()) return false;
            if (tier.equals("L") || tier.equals("M") || tier.equals("N")) return false;
            return !TunnelPeerSelector.shouldExclude(_context, info);
        }
        return false;
    }

    private void locked_placeProfile(PeerProfile profile) {
        Hash peer = profile.getPeer();
        int minHighCap = _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS);
        _fastPeers.remove(peer);
        _highCapacityPeers.remove(peer);
        _wellIntegratedPeers.remove(peer);
        _notFailingPeers.put(peer, profile);
        _notFailingPeersList.add(peer);

        if (_thresholdCapacityValue <= profile.getCapacityValue() &&
            isSelectable(peer) && (_context.commSystem() == null ||
            !_context.commSystem().isInStrictCountry(peer))) {
            _highCapacityPeers.put(peer, profile);
            if (_thresholdSpeedValue <= profile.getSpeedValue() && profile.getIsActive()) {
                _fastPeers.put(peer, profile);
            }
        } else if (countHighCapacityPeers() < minHighCap && isSelectable(peer) && !_context.commSystem().isInStrictCountry(peer)) {
            _highCapacityPeers.put(peer, profile);
        }

        if (_thresholdIntegrationValue <= profile.getIntegrationValue()) {
            _wellIntegratedPeers.put(peer, profile);
        }
    }

    private boolean shouldDrop(PeerProfile profile) {
        return false;
    }

    protected int getMinimumFastPeers() {
        if (_context.router() == null) return DEFAULT_MINIMUM_FAST_PEERS;
        int known = _context.netDb().getKnownRouters();
        return _context.getProperty(PROP_MINIMUM_FAST_PEERS, known > 3000 ? Math.max(known / 15, DEFAULT_MINIMUM_FAST_PEERS) : DEFAULT_MINIMUM_FAST_PEERS);
    }

    protected int getMaximumFastPeers() {
        if (_context.router() == null) return ABSOLUTE_MAX_FAST_PEERS;
        int known = _context.netDb().getKnownRouters();
        return Math.max(known / 12, ABSOLUTE_MAX_FAST_PEERS);
    }

    protected int getMaximumHighCapPeers() {
        if (_context.router() == null) return ABSOLUTE_MAX_HIGHCAP_PEERS;
        int known = _context.netDb().getKnownRouters();
        return Math.max(known / 10, ABSOLUTE_MAX_HIGHCAP_PEERS);
    }

    protected int getMinimumHighCapacityPeers() {
        if (_context.router() == null) return DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS;
        int known = _context.netDb().getKnownRouters();
        return _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS, known > 3000 ? Math.max(known / 15, DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS) : DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS);
    }

    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
    private final static String num(double num) {synchronized (_fmt) {return _fmt.format(num);} }

    public static void main(String args[]) {
        if (args.length <= 0) {
            System.err.println("Usage: profileorganizer file.txt.gz [file2.txt.gz] ...");
            System.exit(1);
        }

        RouterContext ctx = new RouterContext(null);
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
                                     "I   ") + "]: "
                           + " Speed:\t" + fmt.format(profile.getSpeedValue())
                           + " Capacity:\t" + fmt.format(profile.getCapacityValue())
                           + " Integration:\t" + fmt.format(profile.getIntegrationValue())
                           + " Active?\t" + profile.getIsActive(now));
            } else {
                System.out.println("Peer " + peer.toBase64().substring(0,4)
                           + " [" + (organizer.isFast(peer) ? "F+R " :
                                     organizer.isHighCapacity(peer) ? "R   " :
                                     "    ") + "]: "
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