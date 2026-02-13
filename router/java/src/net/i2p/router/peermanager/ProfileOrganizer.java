package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.i2p.crypto.SipHashInline;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.pool.GhostPeerManager;
import net.i2p.router.tunnel.pool.TunnelPeerSelector;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.SimpleTimer;
import net.i2p.router.util.RandomIterator;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Categorizes peers into performance tiers based on historical metrics. Requires periodic reorganize() calls to update peer classifications for optimal tunnel selection.
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

    public static final String PROP_MAX_PROFILES = "profileOrganizer.maxProfiles";
    /**
     * Calculate default max profiles based on available heap memory.
     * Each profile roughly takes 256KB of heap (profile data + overhead).
     * Scale from 400 (128MB heap) to 3000 (4GB+ heap).
     *
     * @return the default max profiles based on available memory
     * @since 0.9.68+
     */
    public static int getDefaultMaxProfiles() {
        long maxMemory = SystemVersion.getMaxMemory();
        long maxMB = maxMemory / (1024 * 1024);
        // Scale: 128MB -> 400, 512MB -> 1200, 4GB -> 3000
        int calculated;
        if (maxMB < 2048) {
            // Low memory: aggressive scaling (profile ~= maxMB * 2.34)
            calculated = (int) (maxMB * 2.34);
        } else {
            // High memory: conservative scaling (profile ~= maxMB * 0.73)
            calculated = (int) (maxMB * 0.73);
        }
        // Clamp to reasonable bounds
        calculated = Math.max(600, Math.min(5000, calculated));
        // Slow systems get a lower ceiling
        return SystemVersion.isSlow() ? Math.min(calculated, 1200) : calculated;
    }
    public static final int ABSOLUTE_MAX_PROFILES = 6000;

    private static final long[] RATES = {
        RateConstants.ONE_MINUTE,
        RateConstants.FIVE_MINUTES,
        RateConstants.TEN_MINUTES,
        RateConstants.ONE_HOUR,
        RateConstants.ONE_DAY
    };

    private final ReentrantReadWriteLock _reorganizeLock = new ReentrantReadWriteLock(false);

    public ProfileOrganizer(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(ProfileOrganizer.class);
        _comp = new InverseCapacityComparator();
        _fastPeers = new HashMap<>(1024);
        _highCapacityPeers = new HashMap<>(1024);
        _notFailingPeersList = new ArrayList<>(4096);
        _notFailingPeers = new HashMap<>(4096);
        _persistenceHelper = new ProfilePersistenceHelper(_context);
        _strictCapacityOrder = new TreeSet<>(_comp);
        _wellIntegratedPeers = new HashMap<>(1024);

        _context.statManager().createRateStat("peer.profileCoalesceTime", "Time to coalesce peer stats (ms)", "Peers", RATES);
        _context.statManager().createRateStat("peer.profilePlaceTime", "Time to sort peers into tiers (ms)", "Peers", RATES);
        _context.statManager().createRateStat("peer.profileReorgTime", "Time to reorganize peers (ms)", "Peers", RATES);
        _context.statManager().createRateStat("peer.profileThresholdTime", "Time to determine tier thresholds (ms)", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.failedLookupRate", "NetDb Lookup failure rate", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.profileSortTime", "Time to sort peers (ms)", "Peers", RATES);

        // Ghost demotion job - runs frequently to remove ghosts from tiers
        _context.simpleTimer2().addPeriodicEvent(new GhostDemoter(), 75 * 1000);

        // Ghost reset job - runs every 5-10 minutes to reset tunnel history for ghosts
        // giving them a chance to recover. Uses shorter interval when under attack.
        _context.simpleTimer2().addPeriodicEvent(new GhostResetter(), 5 * 60 * 1000);
    }

    /**
     * Lightweight ghost demotion job - runs every 75 seconds to quickly remove
     * ghost peers from fast/high capacity tiers without recalculating all scores.
     */
    private class GhostDemoter implements SimpleTimer.TimedEvent {
        public void timeReached() {
            long start = System.currentTimeMillis();
            int demoted = 0;

            // Only run if we have write lock available quickly
            if (!tryWriteLock()) {
                return;
            }

            try {
                // Collect ghosts to remove (avoid CME during iteration)
                Set<Hash> ghostsInFast = new HashSet<>();
                for (Map.Entry<Hash, PeerProfile> entry : _fastPeers.entrySet()) {
                    PeerProfile profile = entry.getValue();
                    Hash peer = entry.getKey();
                    if (isGhostPeer(profile, peer)) {
                        ghostsInFast.add(peer);
                    }
                }

                Set<Hash> ghostsInHighCap = new HashSet<>();
                for (Map.Entry<Hash, PeerProfile> entry : _highCapacityPeers.entrySet()) {
                    PeerProfile profile = entry.getValue();
                    Hash peer = entry.getKey();
                    if (isGhostPeer(profile, peer)) {
                        ghostsInHighCap.add(peer);
                    }
                }

                // Remove ghosts
                for (Hash peer : ghostsInFast) {
                    _fastPeers.remove(peer);
                    demoted++;
                    if (_log.shouldDebug()) {
                        _log.debug("Demoted ghost from fast peers: " + peer.toBase64().substring(0, 6));
                    }
                }

                for (Hash peer : ghostsInHighCap) {
                    _highCapacityPeers.remove(peer);
                    demoted++;
                    if (_log.shouldDebug()) {
                        _log.debug("Demoted ghost from high capacity peers: " + peer.toBase64().substring(0, 6));
                    }
                }

                if (demoted > 0 && _log.shouldInfo()) {
                    _log.info("Demoted " + demoted + " ghost peers from tiers in " +
                              (System.currentTimeMillis() - start) + "ms");
                }
            } finally {
                releaseWriteLock();
            }
        }
    }

    /**
     * Periodic ghost reset job - resets tunnel history for ghost peers so they can recover.
     * Runs every 5 minutes, or more frequently when under attack.
     */
    private class GhostResetter implements SimpleTimer.TimedEvent {
        public void timeReached() {
            int resetCount = 0;
            long now = _context.clock().now();
            long uptime = _context.router().getUptime();

            // Check if we're under attack (low tunnel build success)
            double buildSuccess = getTunnelBuildSuccess();
            // Also true if 0% success (botnet attack from startup)
            boolean underAttack = (buildSuccess >= 0 && buildSuccess < 0.4);
            long resetInterval = underAttack ? 5 * 60 * 1000 : 10 * 60 * 1000;

            if (!tryWriteLock()) {
                return;
            }

            try {
                // Reset tunnel history for ghost peers that haven't been heard from recently
                for (PeerProfile profile : _notFailingPeers.values()) {
                    Hash peer = profile.getPeer();

                    if (isGhostPeer(profile, peer)) {
                        TunnelHistory th = profile.getTunnelHistory();
                        if (th != null) {
                            // Only reset if ghost has been inactive for the reset interval
                            long lastRejected = Math.max(
                                Math.max(th.getLastRejectedCritical(), th.getLastRejectedBandwidth()),
                                Math.max(th.getLastRejectedTransient(), th.getLastRejectedProbabalistic())
                            );
                            long lastActivity = Math.max(th.getLastAgreedTo(), lastRejected);
                            if (now - lastActivity > resetInterval) {
                                // Reset tunnel history to give peer a fresh start
                                th.reset();
                                resetCount++;
                                if (_log.shouldDebug()) {
                                    _log.debug("Reset tunnel history for ghost peer: " + peer.toBase64().substring(0, 6));
                                }
                            }
                        }
                    }
                }

                if (resetCount > 0 && _log.shouldInfo()) {
                    _log.info("Reset tunnel history for " + resetCount + " ghost peers (attack: " + underAttack + ")");
                }
            } finally {
                releaseWriteLock();
            }

            // Reschedule with adjusted interval
            long nextDelay = underAttack ? 5 * 60 * 1000 : 10 * 60 * 1000;
            _context.simpleTimer2().addEvent(this, nextDelay);
        }
    }

    /**
     * Check if current router is firewalled to adjust peer selection thresholds
     */
    private boolean isGhostPeer(PeerProfile profile, Hash peer) {
        if (profile == null) return false;

        // Check GhostPeerManager first (detects timeout-based ghosts) @since 0.9.68+
        try {
            GhostPeerManager ghostManager = _context.tunnelManager().getGhostPeerManager();
            if (ghostManager != null && ghostManager.isGhost(peer)) {
                if (_log.shouldDebug()) {
                    _log.debug("Peer " + peer.toBase32().substring(0, 6) + " is a ghost (timeout-based)");
                }
                return true;
            }
        } catch (Exception e) {
            // Ignore - GhostPeerManager may not be available
        }

        // Fall back to TunnelHistory-based ghost detection
        TunnelHistory th = profile.getTunnelHistory();
        if (th == null) return false;

        long agreed = th.getLifetimeAgreedTo();
        long rejected = th.getLifetimeRejected();

        // Ghost patterns:
        // 1. 0 accepts, >10 rejections
        // 2. <5 accepts, >50 rejections, rejected > 10x accepted
        return (agreed == 0 && rejected > 10) ||
               (agreed < 5 && rejected > 50 && rejected > agreed * 10);
    }

    /**
     * Check if current router is firewalled to adjust peer selection thresholds
     */
    private boolean isFirewalled() {
        net.i2p.router.CommSystemFacade.Status status = _context.commSystem().getStatus();
        return status != null && (status.toString().contains("FIREWALLED") ||
                                status.toString().contains("REJECT_UNSOLICITED"));
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

    /**
     * Get the current tunnel build success rate.
     * Uses both exploratory and client tunnel stats.
     * Returns 0 if no data available.
     * @return build success rate as a fraction (0.0 to 1.0), or 0 if unknown
     */
    public double getTunnelBuildSuccess() {
        try {
            RateStat eExpl = _context.statManager().getRate("tunnel.buildExploratoryExpire");
            RateStat rExpl = _context.statManager().getRate("tunnel.buildExploratoryReject");
            RateStat sExpl = _context.statManager().getRate("tunnel.buildExploratorySuccess");
            RateStat eClient = _context.statManager().getRate("tunnel.buildClientExpire");
            RateStat rClient = _context.statManager().getRate("tunnel.buildClientReject");
            RateStat sClient = _context.statManager().getRate("tunnel.buildClientSuccess");
            RateStat dup = _context.statManager().getRate("tunnel.buildDuplicate");
            if (eExpl != null && rExpl != null && sExpl != null &&
                eClient != null && rClient != null && sClient != null && dup != null) {
                Rate er = eExpl.getRate(RateConstants.TEN_MINUTES);
                Rate rr = rExpl.getRate(RateConstants.TEN_MINUTES);
                Rate sr = sExpl.getRate(RateConstants.TEN_MINUTES);
                Rate erClient = eClient.getRate(RateConstants.TEN_MINUTES);
                Rate rrClient = rClient.getRate(RateConstants.TEN_MINUTES);
                Rate srClient = sClient.getRate(RateConstants.TEN_MINUTES);
                Rate dr = dup.getRate(RateConstants.TEN_MINUTES);
                if (er != null && rr != null && sr != null &&
                    erClient != null && rrClient != null && srClient != null && dr != null) {
                    RateAverages ra = RateAverages.getTemp();
                    long ec = er.computeAverages(ra, false).getTotalEventCount();
                    long rc = rr.computeAverages(ra, false).getTotalEventCount();
                    long sc = sr.computeAverages(ra, false).getTotalEventCount();
                    long ecClient = erClient.computeAverages(ra, false).getTotalEventCount();
                    long rcClient = rrClient.computeAverages(ra, false).getTotalEventCount();
                    long scClient = srClient.computeAverages(ra, false).getTotalEventCount();
                    long dc = dr.computeAverages(ra, false).getTotalEventCount();
                    long tot = ec + rc + sc + ecClient + rcClient + scClient;
                    long totalSuccess = sc + scClient;
                    if (tot > 0) {
                        return (double) totalSuccess / tot;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore - return 0 on error
        }
        return 0;
    }

    public boolean isLowBuildSuccess() {
        double buildSuccess = getTunnelBuildSuccess();
        return buildSuccess < 0.40;
    }

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

        PeerProfile old = getProfile(peer);
        if (old != null) {
            return old; // Profile already exists, no need to log or recreate
        }

        if (_log.shouldInfo()) {
            _log.info("New profile created for [" + peer.toBase64().substring(0,6) + "]");
        }

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
             enforceProfileCap();
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
            Rate invalidReplyRate = invalidReplyRateStat.getRate(RateConstants.ONE_HOUR);
            RateStat failedLookupRateStat = profile.getDBHistory().getFailedLookupRate();
            Rate failedLookupRate = failedLookupRateStat.getRate(RateConstants.ONE_HOUR);
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

    /**
     * Defines peer selection slicing modes for tier-based peer organization.
     * <p>
     * Enumerates different strategies for dividing peers into subsets
     * based on performance tiers and capacity requirements.
     * Used to control which portions of the peer population
     * are considered during selection operations.
     * <p>
     * Supports bit masking for combining multiple selection criteria
     * and provides predefined constants for common slicing patterns
     * including full selection, tier-based selection, and
     * capacity-limited selection modes.
     *
     * @since 0.9.17
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
        selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, mask);
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
    private static final int ENOUGH_PROFILES = SystemVersion.isSlow() ? 1000 : 4000;

    void reorganize() {
        reorganize(false, false);
    }

    /**
     * Reorganizes peer profiles into performance-based tiers (fast, high-capacity, etc.) and expires stale entries.
     * <p>
     * This method:
     * <ul>
     *   <li>Coalesces and decays stats if requested and uptime conditions are met.</li>
     *   <li>Filters out unreachable, inactive, or low-tier peers.</li>
     *   <li>Recalculates dynamic thresholds for speed, capacity, and integration.</li>
     *   <li>Rebuilds internal tier maps and the global profile ordering.</li>
     *   <li>Expires profiles that haven't been active recently to bound memory usage.</li>
     * </ul>
     * <p>
     * <strong>Memory Safety:</strong> To prevent unbounded memory growth (e.g., OOM after 8+ hours),
     * this method ensures that expired profiles are removed from all data structures—even if the full
     * reorganization is skipped due to lock contention. A best-effort expiration pass runs outside the
     * write lock to mitigate leaks during high contention.
     *
     * @param shouldCoalesce if {@code true}, coalesce statistics for active profiles
     * @param shouldDecay if {@code true} and coalescing is performed, apply decay to historical stats
     */
    void reorganize(boolean shouldCoalesce, boolean shouldDecay) {
        final long now = _context.clock().now();
        final long start = System.currentTimeMillis();
        long uptime = _context.router().getUptime();
        int profileCount = 0;
        int expiredCount = 0;
        boolean underAttack = isLowBuildSuccess();

        // Memory pressure: reduce expiration time to free memory faster
        // Only activate after warmup period to avoid false triggers during startup
        final long WARMUP_TIME = 15*60*1000;
        boolean memoryPressure = false;
        if (uptime > WARMUP_TIME) {
            Runtime rt = Runtime.getRuntime();
            long maxMemory = rt.maxMemory();
            long usedMemory = maxMemory - rt.freeMemory();
            double usageRatio = (double) usedMemory / maxMemory;
            memoryPressure = usageRatio > 0.80;
        }

        long baseExpireTime = countNotFailingPeers() > ENOUGH_PROFILES
            ? 8 * 60 * 60 * 1000L   // 8 hours
            : 24 * 60 * 60 * 1000L; // 24 hours
        // Under memory pressure, use much shorter expiration time
        if (memoryPressure) {
            baseExpireTime = Math.min(baseExpireTime, 2 * 60 * 60 * 1000L); // Max 2 hours
        }
        // Keep profiles 2x longer when under attack
        final long expireOlderThan = underAttack ? baseExpireTime * 2 : baseExpireTime;

        // Optional coalescing (read-only, safe to skip if lock fails)
        if (shouldCoalesce && _context.router() != null && uptime > 30 * 60 * 1000 &&
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

        // Attempt to acquire write lock
        if (!getWriteLock()) {
            _log.warn("Write lock unavailable during reorganize; performing lightweight expiration...");

            getReadLock();
            try {
                int estimatedExpired = 0;
                for (PeerProfile profile : _strictCapacityOrder) {
                    if (profile.getLastSendSuccessful() < now - expireOlderThan) {
                        estimatedExpired++;
                    }
                }
                _context.statManager().addRateData("peer.profileEstimatedExpired", estimatedExpired, 0);
            } finally {
                releaseReadLock();
            }

            _context.statManager().addRateData("peer.reorganizeLockFailures", 1, 0);
            return;
        }

        try {
            // Step 1: Build a new set of active, non-expired, non-blacklisted profiles
            Set<PeerProfile> newStrictCapacityOrder = new TreeSet<>(_comp);
            double totalCapacity = 0;
            double totalIntegration = 0;

            for (PeerProfile profile : _strictCapacityOrder) {
                if (_us.equals(profile.getPeer()) || profile.wasUnreachable()) {
                    continue;
                }

                if (profile.getLastSendSuccessful() < now - expireOlderThan) {
                    expiredCount++;
                    continue;
                }

                // Skip peers in low bandwidth tiers
                RouterInfo peerInfo = _context.netDb().lookupRouterInfoLocally(profile.getPeer());
                String bwTier = (peerInfo != null && peerInfo.getBandwidthTier() != null)
                    ? peerInfo.getBandwidthTier() : "K";
                if ("K".equals(bwTier) || "L".equals(bwTier) || "M".equals(bwTier)) {
                    continue;
                }

                if (!profile.getIsActive(now)) {
                    continue;
                }

                profile.updateValues(); // Refresh values (e.g., speed, capacity, integration)
                newStrictCapacityOrder.add(profile);
                totalCapacity += profile.getCapacityValue();
                totalIntegration += profile.getIntegrationValue();
                profileCount++;
            }

            // Calculate new thresholds
            int numNotFailing = newStrictCapacityOrder.size();
            double newIntegrationThreshold = numNotFailing > 0 ? totalIntegration / numNotFailing : 1.0d;
            double newCapacityThreshold = calculateCapacityThresholdFromSet(newStrictCapacityOrder, numNotFailing);
            double newSpeedThreshold = calculateSpeedThreshold(newStrictCapacityOrder);

            // Adaptive relaxation: if build success is low, relax thresholds significantly
            // This allows more peers into fast/high cap tiers during network stress
            // With hysteresis to prevent oscillation between relaxed/strict modes
            if (isLowBuildSuccess() && _log.shouldInfo()) {
                _log.info("Low tunnel build success (" + ((int) (getTunnelBuildSuccess() * 100)) +
                          "%) - relaxing tier thresholds to expand peer pool");
            } else if (_log.shouldInfo()) {
                _log.info("Tunnel build success recovered (" + ((int) (getTunnelBuildSuccess() * 100)) +
                          "%) - restoring strict tier thresholds");
            }

            if (isLowBuildSuccess()) {
                // Reduce thresholds by 50% to allow more peers into tiers
                newCapacityThreshold *= 0.5;
                newSpeedThreshold *= 0.5;
                newIntegrationThreshold *= 0.5;
            }

            // Step 3: Clear and rebuild all tier maps
            _fastPeers.clear();
            _highCapacityPeers.clear();
            _wellIntegratedPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();

            // Step 4: Reinsert all active profiles and assign tiers
            for (PeerProfile profile : newStrictCapacityOrder) {
                locked_placeProfile(profile);
            }

            // Step 5: Fallback to ensure minimum fast peers
            int minFast = getMinimumFastPeers();
            int added = 0;
            int target = Math.max(minFast, getMinimumFastPeers());

            if (_fastPeers.size() < target) {
                // First, try from high-capacity peers
                List<PeerProfile> candidates = new ArrayList<>(_highCapacityPeers.values());
                if (candidates.isEmpty()) {
                    candidates = new ArrayList<>(newStrictCapacityOrder);
                }

                // Sort by speed descending
                candidates.sort((p1, p2) -> Double.compare(p2.getSpeedValue(), p1.getSpeedValue()));

                // Try with original threshold
                double threshold = _thresholdSpeedValue;
                for (PeerProfile profile : candidates) {
                    if (_fastPeers.size() >= target) break;
                    if (profile.getIsActive() && profile.getSpeedValue() >= threshold) {
                        _fastPeers.put(profile.getPeer(), profile);
                        added++;
                    }
                }

                // If still not enough, lower threshold and try again
                if (_fastPeers.size() < target) {
                    threshold = 0.3;
                    for (PeerProfile profile : candidates) {
                        if (_fastPeers.size() >= target) break;
                        if (profile.getIsActive() && profile.getSpeedValue() >= threshold) {
                            _fastPeers.put(profile.getPeer(), profile);
                            added++;
                        }
                    }
                }

                // If still not enough, lower threshold and try again
                if (_fastPeers.size() < target) {
                    for (PeerProfile profile : candidates) {
                        if (_fastPeers.size() >= target) break;
                        if (profile.getIsActive() && profile.getSpeedValue() > 0.1) {
                            _fastPeers.put(profile.getPeer(), profile);
                            added++;
                        }
                    }
                }

                // If still not enough, bypass threshold and try again
                if (_fastPeers.size() < target) {
                    for (PeerProfile profile : candidates) {
                        if (_fastPeers.size() >= target) break;
                        if (profile.getIsActive()) {
                            _fastPeers.put(profile.getPeer(), profile);
                            added++;
                        }
                    }
                }
            }

            // Step 6: Update global thresholds
            _strictCapacityOrder = newStrictCapacityOrder;
            _thresholdCapacityValue = newCapacityThreshold;
            _thresholdIntegrationValue = newIntegrationThreshold;
            _thresholdSpeedValue = newSpeedThreshold;

            // Step 7: Log and record stats
            if (_log.shouldInfo()) {
                _log.info("Profiles reorganized: " + expiredCount + " expired, " +
                          profileCount + " retained \n* Thresholds: " +
                          "Capacity: " + num(_thresholdCapacityValue) +
                          ", Seed: " + num(_thresholdSpeedValue) +
                          ", Integration: " + num(_thresholdIntegrationValue) +
                          " \n* Fast peers: " + _fastPeers.size() + " (added " + added + " via fallback)");
            }

            // Step 8: Bootstrap fast/highcap peers from high-bandwidth tiers if insufficient
            int minHighCap = getMinimumHighCapacityPeers();
            if (_fastPeers.isEmpty() || _highCapacityPeers.size() < minHighCap) {
                bootstrapFastPeersFromNetDb(target);
            }

            long total = System.currentTimeMillis() - start;
            _context.statManager().addRateData("peer.profileReorgTime", total, profileCount);
            _context.statManager().addRateData("peer.activeProfileCount", profileCount, 0);
            _context.statManager().addRateData("peer.expiredProfileCount", expiredCount, 0);

            // Step 9: Enforce memory cap
            enforceProfileCap();

            // Step 10: Clean up persisted profiles
            purgeStaleProfileFiles();

        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Bootstrap fast and high capacity peers from high-bandwidth routers in NetDB.
     * Prioritizes X tier (unlimited), then P tier (512KBps), then O tier (256KBps).
     * These are temporary until profile manager initializes with real performance data.
     * @param target the target number of fast peers to bootstrap
     */
    private void bootstrapFastPeersFromNetDb(int target) {
        if (_context.netDb() == null) return;

        int fastBootstrapped = 0;
        int highCapBootstrapped = 0;
        Set<Hash> allRouters = _context.netDb().getAllRouters();
        if (allRouters == null || allRouters.isEmpty()) return;

        // Collect candidates by tier (X > P > O)
        List<RouterInfo> xTier = new ArrayList<>();
        List<RouterInfo> pTier = new ArrayList<>();
        List<RouterInfo> oTier = new ArrayList<>();

        for (Hash peer : allRouters) {
            if (_us.equals(peer)) continue;
            if (_fastPeers.containsKey(peer)) continue;

            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(peer);
            if (ri == null) continue;
            if (ri.isHidden()) continue;

            String caps = ri.getCapabilities();
            if (caps == null) continue;

            // Check for high bandwidth tiers in priority order
            if (caps.indexOf(Router.CAPABILITY_BW_UNLIMITED) >= 0) {
                xTier.add(ri);
            } else if (caps.indexOf(Router.CAPABILITY_BW512) >= 0) {
                pTier.add(ri);
            } else if (caps.indexOf(Router.CAPABILITY_BW256) >= 0) {
                oTier.add(ri);
            }
        }

        // Add candidates in priority order: X tier first, then P, then O
        List<RouterInfo> candidates = new ArrayList<>(xTier.size() + pTier.size() + oTier.size());
        candidates.addAll(xTier);
        candidates.addAll(pTier);
        candidates.addAll(oTier);

        int minHighCap = getMinimumHighCapacityPeers();

        // Create profiles and add to tiers
        for (RouterInfo ri : candidates) {
            Hash peer = ri.getIdentity().getHash();
            PeerProfile profile = locked_getProfile(peer);

            if (profile == null) {
                // Create new profile for this peer
                profile = new PeerProfile(_context, peer);
                profile.setLastHeardAbout(_context.clock().now());
                profile.coalesceStats();
                _notFailingPeers.put(peer, profile);
                _notFailingPeersList.add(peer);
                _strictCapacityOrder.add(profile);
            }

            // Add to high capacity tier if needed
            if (_highCapacityPeers.size() < minHighCap && isSelectable(peer)) {
                _highCapacityPeers.put(peer, profile);
                highCapBootstrapped++;
            }

            // Add to fast peers if still needed (bypass ghost checks during bootstrap)
            if (_fastPeers.size() < target) {
                _fastPeers.put(peer, profile);
                fastBootstrapped++;
            }

            if (_log.shouldDebug()) {
                _log.debug("Bootstrapped peer from NetDB: " + peer.toBase64().substring(0, 6) +
                          " (bandwidth: " + ri.getBandwidthTier() + ")");
            }
        }

        if ((fastBootstrapped > 0 || highCapBootstrapped > 0) && _log.shouldInfo()) {
            _log.info("Bootstrapped " + fastBootstrapped + " fast and " + highCapBootstrapped +
                      " high capacity peers from NetDB (X: " + xTier.size() + ", P: " + pTier.size() +
                      ", O: " + oTier.size() + ")");
        }
    }

    public void purgeStaleProfileFiles() {
        int maxProfiles = ABSOLUTE_MAX_PROFILES;

        // Get all currently active peers (those we keep in memory)
        Set<Hash> activePeers = selectAllPeers(); // includes fast, high-cap, not-failing

        // Let persistence helper delete files NOT in activePeers, if over cap
        _persistenceHelper.purgeExcessProfiles(activePeers, maxProfiles);
    }

    /**
     * Helper to calculate capacity threshold from a pre-filtered set of active profiles.
     * Avoids re-iterating the full set multiple times.
     */
    private double calculateCapacityThresholdFromSet(Set<PeerProfile> activeProfiles, int totalPeers) {
        if (totalPeers == 0) return CapacityCalculator.GROWTH_FACTOR;

        List<Double> capacities = new ArrayList<>(activeProfiles.size());
        double totalCapacity = 0.0;
        for (PeerProfile p : activeProfiles) {
            double cap = p.getCapacityValue();
            capacities.add(cap);
            totalCapacity += cap;
        }
        double meanCapacity = totalCapacity / totalPeers;

        // Sort to find median and Nth peer
        capacities.sort(Double::compareTo);
        int minHighCap = getMinimumHighCapacityPeers();
       double thresholdAtMedian = capacities.get(totalPeers / 2);
        double thresholdAtMinHighCap = (minHighCap <= totalPeers)
           ? capacities.get(Math.min(minHighCap - 1, totalPeers - 1))
           : CapacityCalculator.GROWTH_FACTOR;
        double thresholdAtLowest = capacities.get(totalPeers - 1);
         int numExceedingMean = (int) capacities.stream().filter(v -> v > meanCapacity).count();
         return calculateCapacityThreshold(meanCapacity, numExceedingMean, totalPeers,
                                        thresholdAtMedian, thresholdAtMinHighCap, thresholdAtLowest);
    }

    /**
     * Enforces the global profile cap by evicting the least valuable peers when over capacity.
     * <p>
     * Eviction priority (from most to least likely to be kept):
     * <ol>
     *   <li>Peers in _fastPeers (fast + reliable)</li>
     *   <li>Peers in _highCapacityPeers</li>
     *   <li>Peers with recent activity (last 2 hours)</li>
     *   <li>Peers with higher capacity value</li>
     * </ol>
     * <p>
     * This method assumes the write lock is held.
     */
    private void enforceProfileCap() {
        int maxProfiles = _context.getProperty(PROP_MAX_PROFILES, getDefaultMaxProfiles());
        if (maxProfiles < 100) maxProfiles = 100;
        if (maxProfiles > ABSOLUTE_MAX_PROFILES) maxProfiles = ABSOLUTE_MAX_PROFILES;

        // Be more protective during attacks - increase effective cap
        boolean underAttack = isLowBuildSuccess();
        if (underAttack) {
            maxProfiles = (int)(maxProfiles * 1.5);
            if (maxProfiles > ABSOLUTE_MAX_PROFILES) maxProfiles = ABSOLUTE_MAX_PROFILES;
        }

        // Memory pressure detection - aggressively reduce profiles when heap is near capacity
        // Only activate after initial warmup period (15 minutes) to avoid false triggers during startup
        long uptime = _context.router().getUptime();
        final long WARMUP_TIME = 15*60*1000;
        if (uptime > WARMUP_TIME) {
            Runtime rt = Runtime.getRuntime();
            long maxMemory = rt.maxMemory();
            long usedMemory = maxMemory - rt.freeMemory();
            double usageRatio = (double) usedMemory / maxMemory;
            if (usageRatio > 0.95) {
                int reductionFactor = usageRatio >= 0.99 ? 3 : (usageRatio > 0.95 ? 2 : 1);
                int originalMax = maxProfiles;
                maxProfiles = Math.max(1000, maxProfiles / reductionFactor);
                if (_log.shouldInfo()) {
                    _log.info("Memory pressure detected (" + String.format("%.1f%%", usageRatio * 100) +
                              ") -> Reducing profile limit from " + originalMax + " to " + maxProfiles + "...");
                }
            }
        }

        if (_notFailingPeers.size() <= maxProfiles) {
            return; // within limits
        }

        // Only log if significantly over limit (reduces log spam during minor spikes)
        if (_notFailingPeers.size() > maxProfiles + 50 && _log.shouldInfo()) {
            _log.info("Profiles stored in RAM (" + _notFailingPeers.size() +
                      ") exceeds hard limit of " + maxProfiles + " -> Evicting lowest quality profiles...");
        }

        // Build list of profiles eligible for eviction (not in critical tiers)
        List<PeerProfile> candidates = new ArrayList<>();
        long now = _context.clock().now();
        long activeThreshold = now - (4 * 60 * 60 * 1000L); // 4 hours

        for (PeerProfile profile : _notFailingPeers.values()) {
            Hash peer = profile.getPeer();

            // More lenient thresholds for firewalled routers
            boolean isFirewalledRouter = isFirewalled();
            int fastPeerLimit = isFirewalledRouter ? 1600 : 800;
            int highCapacityLimit = isFirewalledRouter ? 2400 : 1200;

            if ((_fastPeers.containsKey(peer) && _fastPeers.size() <= fastPeerLimit) ||
                (_highCapacityPeers.containsKey(peer) && _highCapacityPeers.size() <= highCapacityLimit)) {
                continue; // protected
            }

            // Keep peers active in last 4 hours - use lastHeardFrom as primary criteria
            if (profile.getLastHeardFrom() >= activeThreshold ||
                profile.getLastSendSuccessful() >= activeThreshold) {
                continue;
            }
            candidates.add(profile);
        }

        // Sort by capacity (lowest first) → evict low-capacity, inactive peers first
        candidates.sort(Comparator.comparingDouble(PeerProfile::getCapacityValue));

        int toEvict = _notFailingPeers.size() - maxProfiles;
        int evicted = 0;
        Iterator<PeerProfile> iter = candidates.iterator();

        while (evicted < toEvict && iter.hasNext()) {
            PeerProfile profile = iter.next();
            Hash peer = profile.getPeer();

            // Remove from all structures
            _notFailingPeers.remove(peer);
            _notFailingPeersList.remove(peer); // O(n), but acceptable for rare eviction
            _strictCapacityOrder.remove(profile);
            // Note: _fastPeers / _highCapacityPeers already excluded above

            evicted++;
        }

        if (_log.shouldInfo()) {
            _log.info("Evicted " + evicted + " low-priority profiles to enforce cap (" + maxProfiles + ")");
        }

        //_context.statManager().addRateData("peer.profileEvicted", evicted, 0);
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
        List<PeerProfile> candidates = new ArrayList<>();
        for (PeerProfile profile : reordered) {
            if (profile.getCapacityValue() >= _thresholdCapacityValue && profile.getIsActive()) {
                candidates.add(profile);
            }
        }

        if (candidates.isEmpty()) return 0;

        // Sort by speed descending
        candidates.sort((p1, p2) -> Double.compare(p2.getSpeedValue(), p1.getSpeedValue()));
        int cutoff = Math.min((int)(candidates.size() * 0.3), 50); // Top 30% or 50 peers

        return candidates.get(cutoff).getSpeedValue();
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
            // Check transport compatibility - peers we can't connect to should never be selectable
            if (!canConnectToPeer(info)) return false;
            return !TunnelPeerSelector.shouldExclude(_context, info);
        }
        return false;
    }

    /**
     * Check if we can connect to a peer based on transport compatibility.
     * Peers with incompatible transports (e.g., IPv6-only when we're IPv4-only)
     * should never be selected into fast/high-cap tiers.
     *
     * @param peerInfo the peer's RouterInfo
     * @return true if there's at least one compatible transport
     */
    private boolean canConnectToPeer(RouterInfo peerInfo) {
        int ourOutboundMask = getOurOutboundMask();
        int peerMask = getPeerConnectMask(peerInfo);
        return (ourOutboundMask & peerMask) != 0;
    }

    /**
     * Get our outbound transport mask based on comm system status.
     */
    private int getOurOutboundMask() {
        int mask = 0;
        boolean ntcpDisabled = !_context.getBooleanPropertyDefaultTrue("i2np.ntcp.enable");
        boolean ssuDisabled = !_context.getBooleanPropertyDefaultTrue("i2np.udp.enable");

        CommSystemFacade.Status status = _context.commSystem().getStatus();
        switch (status) {
            case OK:
            case IPV4_OK_IPV6_FIREWALLED:
            case IPV4_UNKNOWN_IPV6_OK:
            case IPV4_FIREWALLED_IPV6_OK:
            case IPV4_SNAT_IPV6_OK:
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
                if (!ntcpDisabled) mask |= 0x01; // NTCP_V4
                if (!ssuDisabled) {
                    mask |= 0x02; // SSU_V4
                    mask |= 0x10; // SSU2_V4
                }
                break;
            case IPV4_DISABLED_IPV6_OK:
            case IPV4_DISABLED_IPV6_UNKNOWN:
            case IPV4_DISABLED_IPV6_FIREWALLED:
                if (!ntcpDisabled) mask |= 0x04; // NTCP_V6
                if (!ssuDisabled) {
                    mask |= 0x08; // SSU_V6
                    mask |= 0x20; // SSU2_V6
                }
                break;
            default:
                if (!ntcpDisabled) mask |= 0x01; // NTCP_V4
                if (!ssuDisabled) {
                    mask |= 0x02; // SSU_V4
                    mask |= 0x10; // SSU2_V4
                }
                break;
        }
        return mask;
    }

    /**
     * Extract the transport connect mask from a peer's RouterInfo.
     */
    private int getPeerConnectMask(RouterInfo ri) {
        int mask = 0;
        for (net.i2p.data.router.RouterAddress ra : ri.getAddresses()) {
            String style = ra.getTransportStyle();
            String host = ra.getHost();
            if ("NTCP2".equals(style)) {
                if (host != null) {
                    if (host.contains(":")) mask |= 0x04; // NTCP_V6
                    else mask |= 0x01; // NTCP_V4
                } else {
                    String caps = ra.getOption("caps");
                    if (caps != null) {
                        if (caps.contains("4")) mask |= 0x01;
                        if (caps.contains("6")) mask |= 0x04;
                    }
                }
            } else if ("SSU".equals(style)) {
                boolean v2 = ra.getOption("v") != null;
                if (host == null) {
                    String caps = ra.getOption("caps");
                    if (caps != null) {
                        if (caps.contains("4")) {
                            mask |= 0x02;
                            if (v2) mask |= 0x10;
                        }
                        if (caps.contains("6")) {
                            mask |= 0x08;
                            if (v2) mask |= 0x20;
                        }
                    }
                } else if (host.contains(":")) {
                    mask |= 0x08;
                    if (v2) mask |= 0x20;
                } else {
                    mask |= 0x02;
                    if (v2) mask |= 0x10;
                }
            } else if ("SSU2".equals(style)) {
                if (host == null) {
                    String caps = ra.getOption("caps");
                    if (caps != null) {
                        if (caps.contains("4")) mask |= 0x10;
                        if (caps.contains("6")) mask |= 0x20;
                    }
                } else if (host.contains(":")) {
                    mask |= 0x20;
                } else {
                    mask |= 0x10;
                }
            }
        }
        // If no addresses, assume IPv4 only (conservative default)
        if (mask == 0) mask = 0x01 | 0x02 | 0x10;
        return mask;
    }

    private void locked_placeProfile(PeerProfile profile) {
        Hash peer = profile.getPeer();
        int minHighCap = getMinimumHighCapacityPeers(); // Uses context property
        double effectiveCapThreshold = Math.max(_thresholdCapacityValue, CapacityCalculator.GROWTH_FACTOR);
        double effectiveSpeedThreshold = _thresholdSpeedValue;

        // Remove existing entries (idempotent)
        _fastPeers.remove(peer);
        _highCapacityPeers.remove(peer);
        _wellIntegratedPeers.remove(peer);

        // Always add/update in notFailing set
        _notFailingPeers.put(peer, profile);
        _notFailingPeersList.add(peer); // Note: O(n), but acceptable during reorg

        boolean isStrictCountry = _context.commSystem() != null && _context.commSystem().isInStrictCountry(peer);
        boolean isPeerSelectable = isSelectable(peer);

        // Check if peer is a ghost (rejects most tunnel requests)
        boolean isGhost = isGhostPeer(profile, peer);

        // Decide if peer qualifies for high-capacity tier
        if (!isGhost && profile.getCapacityValue() >= effectiveCapThreshold && isPeerSelectable && !isStrictCountry) {
            _highCapacityPeers.put(peer, profile);

            // Also check if peer qualifies for fast tier
            if (profile.getSpeedValue() >= effectiveSpeedThreshold && profile.getIsActive()) {
                _fastPeers.put(peer, profile);
            }
        } else if (!isGhost && _highCapacityPeers.size() < minHighCap && isPeerSelectable && !isStrictCountry) {
            _highCapacityPeers.put(peer, profile);
        }

        // Integration tier
        if (profile.getIntegrationValue() >= _thresholdIntegrationValue) {
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