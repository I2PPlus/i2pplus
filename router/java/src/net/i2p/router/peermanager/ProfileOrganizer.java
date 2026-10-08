package net.i2p.router.peermanager;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.i2p.crypto.SipHashInline;
import net.i2p.data.DataHelper;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.transport.Transport;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.tunnel.pool.GhostPeerManager;
import net.i2p.router.tunnel.pool.TunnelPeerSelector;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.router.util.RandomIterator;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Categorizes peers into performance tiers (fast, high-capacity, well-integrated)
 * based on historical metrics from PeerProfile. Requires periodic reorganize() calls
 * to update peer classifications for optimal tunnel selection.
 *
 * Tier assignment during reorganize():
 * 1. All profiles are iterated in capacity order (via InverseCapacityComparator)
 * 2. Expired/unreachable/low-bandwidth peers are filtered out
 * 3. Speed, capacity, and integration thresholds are recalculated from the active set
 * 4. Each surviving profile is evaluated via lockedPromoteProfileToTiers()
 * 5. If fast/high-cap tiers fall below minimum thresholds, fallback passes fill gaps
 * (with selectability checks to prevent selecting unusable peers)
 * <p>
 * A promotion whose RouterInfo has aged past {@link #PROP_ROUTERINFO_REFRESH_AGE_MS}
 * and which has no proof of life is held back: the peer is left out of the tier
 * until a newer publication arrives on its own. Nothing is fetched on the peer's
 * behalf, unless holding the promotion would starve the tier it would have joined —
 * see {@link #shouldDeferPromotionOnStaleRouterInfo}.
 *
 * Concurrency: ReentrantReadWriteLock protects all tier maps.
 * reorganize() acquires the write lock; selection methods use the read lock.
 * getOrCreateProfileNonblocking() uses try-lock escalation for lock-free reads.
 */
public class ProfileOrganizer {
    /** Threshold below which the network is considered under attack */
    public static final double ATTACK_THRESHOLD = 0.40;
    private static final Comparator<PeerProfile> CAPACITY_COMPARATOR =
            Comparator.comparingDouble(PeerProfile::getCapacityValue);
    private final Log _log;
    private final RouterContext _context;
    private final Map<Hash, PeerProfile> _fastPeers;
    /**
     * Count of fast-tier peers that passed all tests (proven throughput or high-cap bypass).
     * Maintained exclusively via {@link #putFastPeer} and {@link #removeFastPeer}.
     */
    private int _fastQualityCount;

    /**
     * Last values published by {@link #reorganize(boolean)}, sampled by
     * CoalesceStatsEvent on the short cycle. Plain reads are enough: these are
     * gauges that hold their last level, and a torn pair would at worst report a
     * count from the previous reorganisation.
     */
    private volatile int _profileCount;
    /** @see #_profileCount */
    private volatile int _qualityCount;
    /** @see #_profileCount */
    private volatile int _activeProfileCount;

    /**
     * Profiles held in RAM, as of the last completed reorganisation.
     *
     * @return the in-RAM profile count
     * @since 0.9.72
     */
    public int getProfileCount() {return _profileCount;}

    /**
     * Peers passing the quality gates, as of the last completed reorganisation.
     *
     * @return the quality peer count
     * @since 0.9.72
     */
    public int getQualityCount() {return _qualityCount;}

    /**
     * Profiles with recent activity, as of the last completed reorganisation.
     *
     * <p>Computed during the reorganisation walk rather than on demand:
     * {@link #countActivePeersInLastHour()} iterates every profile while holding
     * _reorganizeLock, which is the lock tunnel peer selection contends on, and CoalesceStatsEvent
     * runs every 50s. Publishing it here costs one comparison per profile in a walk that already
     * holds the lock.
     *
     * @return the recently active profile count
     * @since 0.9.72
     */
    public int getActiveProfileCount() {return _activeProfileCount;}

    /** Window used by {@link #getActiveProfileCount()}, in ms. */
    private static final long ACTIVE_WINDOW_MS = 60 * 60 * 1000L;

    /**
     *  Per-cycle bounds and counters for the promotion deferral on a stale
     *  RouterInfo.  A cycle is opened by {@link #lockedRebuildTiers} and its
     *  counters are published by {@link #recordRouterInfoRefreshStats} before the
     *  reorg write lock is released.
     *  @since 0.9.72
     */
    private final RouterInfoRefresher _refresher = new RouterInfoRefresher();

    /**
     * Whether a profile shows activity within the active window: a peer test counted as active, a
     * successful or failed send, or any contact. Split out so the rule is testable without a
     * reorganise cycle or a live profile set.
     *
     * @param profile the profile
     * @param hideBefore cutoff time, i.e. now minus {@link #ACTIVE_WINDOW_MS}
     * @return true if the profile is recently active
     * @since 0.9.72
     */
    static boolean isActiveInWindow(PeerProfile profile, long hideBefore) {
        return profile.getIsActive(ACTIVE_WINDOW_MS) ||
               profile.getLastSendSuccessful() >= hideBefore ||
               profile.getLastSendFailed() >= hideBefore ||
               profile.getLastHeardFrom() >= hideBefore;
    }

    /** True when a peer in the fast tier has proven throughput or is in the high-cap tier. */
    private static boolean isQualityFastPeer(PeerProfile profile, Map<Hash, PeerProfile> highCap) {
        return profile.getPeakTunnel1mThroughputKBps() > 0 ||
               highCap.containsKey(profile.getPeer());
    }

    /** Add a peer to the fast tier, maintaining the quality counter. */
    private void putFastPeer(Hash peer, PeerProfile profile) {
        PeerProfile prev = _fastPeers.put(peer, profile);
        if (prev == null && isQualityFastPeer(profile, _highCapacityPeers))
            _fastQualityCount++;
    }

    /** Remove a peer from the fast tier, maintaining the quality counter. */
    private PeerProfile removeFastPeer(Hash peer) {
        PeerProfile removed = _fastPeers.remove(peer);
        if (removed != null && isQualityFastPeer(removed, _highCapacityPeers))
            _fastQualityCount--;
        return removed;
    }
    private final Map<Hash, PeerProfile> _highCapacityPeers;
    private final Map<Hash, PeerProfile> _wellIntegratedPeers;
    private final Map<Hash, PeerProfile> _notFailingPeers;
    private final List<Hash> _notFailingPeersList;
    /** Peers demoted via demoteIfUnreachable — excluded from promotion for TUNNEL_DEMOTION_COOLDOWN_MS */
    private final ConcurrentHashMap<Hash, Long> _demotedPeers = new ConcurrentHashMap<>(64);
    /** Strike count per peer for demoteIfUnreachable — only demote after DEMOTE_STRIKE_THRESHOLD strikes within the decay window */
    private final ConcurrentHashMap<Hash, Integer> _demoteStrikes = new ConcurrentHashMap<>(64);
    /** Last strike time per peer, so strikes expire after DEMOTE_STRIKE_DECAY_MS */
    private final ConcurrentHashMap<Hash, Long> _demoteStrikeTimes = new ConcurrentHashMap<>(64);
    public static final int DEMOTE_STRIKE_THRESHOLD = 3;
    /** A strike older than this no longer counts, so a peer that recovered is not demoted by one later failure */
    public static final long DEMOTE_STRIKE_DECAY_MS = 10 * 60 * 1000L;
    private static final int MAX_DEMOTED_PEERS = 1024;

    private Hash _us;
    private final ProfilePersistenceHelper _persistenceHelper;
    private Set<PeerProfile> _strictCapacityOrder;
    private double _thresholdSpeedValue;
    private double _thresholdCapacityValue;
    private double _thresholdIntegrationValue;
    private double _thresholdRTT;
    /**
     * First-measured fast-tier RTT boundary, captured during the initial
     * reorganize pass.  Used by {@link #computeAdaptiveRttCeiling} to floor the
     * adaptive ceiling at 2× the startup baseline when build success is
     * degraded, preventing RTT ceiling amplification during cascade failure.
     * @since 0.9.71+
     */
    private volatile double _baselineRTT;
    /**
     * Average peer test response time across fast peers with data, computed
     * during each reorganize.  Used by {@link PeerProfile#recalculateLowLatency()}
     * to tighten the low-latency threshold to 1.5× the cohort average when the
     * fast tier has sufficient data ({@value ClientPeerSelector#CROSS_POOL_DIVERSITY_THRESHOLD}).
     *
     * @since 0.9.71+
     */
    private volatile float _averageLowLatencyRTT;
    private final InverseCapacityComparator _comp;

    /**
     * PROP_MINIMUM_FAST_PEERS.
     */
    public static final String PROP_MINIMUM_FAST_PEERS = "profileOrganizer.minFastPeers";
    /**
     *  Network size above which tier thresholds scale with the network instead of
     *  staying at their configured default.
     */
    static final int SCALING_THRESHOLD = 3000;
    /**
     * _defaultMinFastPeers.
     */
    public static volatile int _defaultMinFastPeers = 1000;
    /** @since 0.9.70+ */
    public static int getDefaultMinFastPeers() { return _defaultMinFastPeers; }
    /** @since 0.9.70+ */
    public static void setDefaultMinFastPeers(int val) { _defaultMinFastPeers = Math.max(50, Math.min(2000, val)); }

    /**
     * PROP_MAX_ROUTERINFO_AGE_HOURS.
     */
    public static final String PROP_MAX_ROUTERINFO_AGE_HOURS = "profileOrganizer.maxRouterInfoAgeHours";
    /**
     * DEFAULT_MAX_ROUTERINFO_AGE_HOURS.
     */
    public static final int DEFAULT_MAX_ROUTERINFO_AGE_HOURS = 2;
    /**
     *  RouterInfo age past which a tier promotion is held back, in ms.
     *
     *  <p>Separate from {@link #PROP_MAX_ROUTERINFO_AGE_HOURS}, which is the bar
     *  a RouterInfo must clear to be selected at all. That bar is deliberately
     *  loose (2h) because a proof of life can stand in for a fresh RouterInfo;
     *  this one is a promotion-time policy with no such fallback, so it is set
     *  where the RouterInfo itself is likely to have been superseded: routers
     *  republish hourly, so anything older than one publishing interval has a
     *  good chance of describing a peer that has since changed.
     *
     *  <p>Holding a promotion costs the peer a tier slot and nothing else — no
     *  lookup is issued, so the only way a held peer re-enters is by being
     *  republished to, or by showing proof of life.
     *
     *  <p>Set to 0 or less to disable the deferral entirely, which restores plain
     *  promotion on whatever the netdb holds.
     *
     *  @since 0.9.72
     */
    public static final String PROP_ROUTERINFO_REFRESH_AGE_MS = "profileOrganizer.routerInfoRefreshAgeMs";
    /**
     *  One hour, i.e. one republish interval.
     *  @since 0.9.72
     */
    public static final long DEFAULT_ROUTERINFO_REFRESH_AGE_MS = 60 * 60 * 1000L;
    private static final long STARTUP_GRACE_PERIOD_MS = 10 * 60 * 1000L;
    private static final long PROOF_OF_LIFE_WINDOW_MS = 60 * 60 * 1000L;
    /**
     * PROP_MAXIMUM_FAST_PEERS.
     */
    public static final String PROP_MAXIMUM_FAST_PEERS = "profileOrganizer.maxFastPeers";
    /**
     * _defaultMaxFastPeers.
     */
    public static volatile int _defaultMaxFastPeers = 2000;
    /** @since 0.9.70+ */
    public static int getDefaultMaxFastPeers() { return _defaultMaxFastPeers; }
    /** @since 0.9.70+ */
    public static void setDefaultMaxFastPeers(int val) { _defaultMaxFastPeers = Math.max(200, Math.min(3000, val)); }

    /**
     * PROP_MINIMUM_HIGH_CAPACITY_PEERS.
     */
    public static final String PROP_MINIMUM_HIGH_CAPACITY_PEERS = "profileOrganizer.minHighCapacityPeers";
    /**
     * DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS.
     */
    public static final int DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS = 1000;
    /**
     * _defaultMinHighCapPeers.
     */
    public static volatile int _defaultMinHighCapPeers = DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS;
    /** @since 0.9.70+ */
    public static int getMinHighCapacityPeers() { return _defaultMinHighCapPeers; }
    /** @since 0.9.70+ */
    public static void setMinHighCapacityPeers(int val) { _defaultMinHighCapPeers = Math.max(50, Math.min(3000, val)); }
    /**
     * PROP_MAXIMUM_HIGH_CAPACITY_PEERS.
     */
    public static final String PROP_MAXIMUM_HIGH_CAPACITY_PEERS = "profileOrganizer.maxHighCapacityPeers";
    /**
     * _defaultMaxHighCapPeers.
     */
    public static volatile int _defaultMaxHighCapPeers = 3000;
    /** @since 0.9.70+ */
    public static int getDefaultMaxHighCapPeers() { return _defaultMaxHighCapPeers; }
    /** @since 0.9.70+ */
    public static void setDefaultMaxHighCapPeers(int val) { _defaultMaxHighCapPeers = Math.max(200, Math.min(6000, val)); }

    /** Minimum tunnel acceptance ratio (40%) to remain in high-capacity/fast tiers */
    private static final double MIN_TUNNEL_ACCEPTANCE_RATIO = 0.4;
    /** Minimum tunnel requests for statistical confidence */
    private static final int MIN_TUNNEL_REQUESTS = 50;
    /** Cooldown period (ms) after demotion before peer can be re-promoted */
    private static final long TUNNEL_DEMOTION_COOLDOWN_MS = 10 * 60 * 1000L; // 10 minutes
    /**
     *  Exclude peers from tunnel selection after this many cumulative failures
     *  OR when the lifetime failure ratio exceeds {@link #MAX_LIFETIME_FAILURE_RATIO},
     *  whichever is stricter.  The hard cap is high (50) to avoid permanently
     *  excluding long-lived peers that accumulated failures over days but are
     *  currently healthy.  Between {@link #SOFT_FAILURE_PENALTY_THRESHOLD} (20)
     *  and this cap, peers are penalized (lower selection priority) but not
     *  excluded — a lightweight exponential backoff that still allows recovery.
     *
     *  @since 0.9.71+ (raised from 20)
     */
    private static final long MAX_LIFETIME_TUNNEL_FAILURES = 50;
    /**
     *  Failure count above which peers receive a selection priority penalty
     *  but are not excluded.  Between this and {@link #MAX_LIFETIME_TUNNEL_FAILURES},
     *  peers are deprioritized rather than banned.
     *  @since 0.9.71+
     */
    private static final long SOFT_FAILURE_PENALTY_THRESHOLD = 20;
    /**
     *  Maximum lifetime failure ratio (failed / (agreed + failed)) before a peer
     *  is excluded.  A peer with 50%+ failure rate is unreliable regardless of
     *  absolute count.  Combined with {@link #MAX_LIFETIME_TUNNEL_FAILURES},
     *  this prevents long-lived peers with terrible records from staying selectable.
     *  @since 0.9.71+
     */
    private static final double MAX_LIFETIME_FAILURE_RATIO = 0.50;

    /**
     * When high-cap tier has at least this many peers, require actual capacity
     * threshold — stop bucket-filling with marginal peers.
     * @since 0.9.70+
     */
    private static final int MIN_HC_TIGHT_COUNT = 1000;
    /**
     * When fast tier has at least this many peers, require all tests
     * passing — peer test (low latency), active, no recent failures,
     * AND proven tunnel throughput.  Peers that are low-latency but
     * have never participated in a real tunnel are excluded.
     * @since 0.9.71+
     */
    private static final int MIN_FAST_QUALITY_COUNT = 300;

    /** Config property for the loss ratio above which a peer is demoted from fast/high-cap tiers. */
    public static final String PROP_LOSSY_THRESHOLD = "profileOrganizer.lossyThreshold";
    /** Default loss ratio threshold (20% of packets retransmitted). */
    private static final float DEFAULT_LOSSY_THRESHOLD = 0.20f;

    /**
     * Tuned lossy threshold, -1.0 = use router config.
     * Set by the Tuner when autotuning profileOrganizer.lossyThreshold.
     * @since 0.9.71+
     */
    private static volatile float _tunedLossyThreshold = -1.0f;

    /**
     * Set the lossy demotion threshold (called by Tuner).
     * @param ratio the loss ratio threshold
     * @since 0.9.71+
     */
    public static void setLossyThreshold(float ratio) { _tunedLossyThreshold = ratio; }

    /**
     * The lossy demotion threshold in effect, tuned value if set else config.
     * @param ctx the router context
     * @return the loss ratio threshold
     * @since 0.9.71+
     */
    public static float getLossyThreshold(RouterContext ctx) {
        float t = _tunedLossyThreshold;
        if (t >= 0.0f) return t;
        String p = ctx.getProperty(PROP_LOSSY_THRESHOLD);
        if (p != null) {
            try { return Float.parseFloat(p); } catch (NumberFormatException nfe) { /* use default */ }
        }
        return DEFAULT_LOSSY_THRESHOLD;
    }
    /** Config property for how long a reported loss ratio stays fresh. */
    public static final String PROP_LOSSY_WINDOW = "profileOrganizer.lossyWindow";
    /** Default freshness window in ms (10 minutes). */
    private static final long DEFAULT_LOSSY_WINDOW = 10 * 60 * 1000L;
    /** Config property for the minimum number of transmitted packets before a loss ratio is reported. */
    public static final String PROP_LOSSY_MIN_PACKETS = "profileOrganizer.lossyMinPackets";
    /** Default minimum packet sample size (20 = full 5% bucket resolution). */
    public static final int DEFAULT_LOSSY_MIN_PACKETS = 20;
    /**
     * Config property for the decayed loss score above which a peer is
     * de-prioritized in tier selection (soft signal, below the demotion
     * threshold). Default 10%: a peer can be consistently lossy without ever
     * crossing the hard demotion bar, and this keeps such peers out of the
     * preferred picks without evicting them.
     * @since 0.9.71+
     */
    public static final String PROP_LOSSY_MODERATE_THRESHOLD = "profileOrganizer.lossyModerateThreshold";
    private static final float DEFAULT_LOSSY_MODERATE_THRESHOLD = 0.10f;
    /**
     * Minimum time after a loss demotion before a peer may be re-admitted,
     * even with fresh clean evidence. Reduced from 30 min to 10 min to
     * accelerate tier recovery after congestion clears. Asymmetric
     * hysteresis: demotion is instant on weak evidence, re-admission
     * requires both this age and a fresh clean measurement (see
     * {@link #shouldReadmitLossy}).
     * @since 0.9.71+ (reduced from 30 min)
     */
    private static final long LOSS_READMIT_MIN_AGE = 10 * 60 * 1000L;
    /**
     * Selection penalty for moderately-lossy peers: their random priority
     * range is multiplied by this factor, so they are this many times less
     * likely to be picked than an equal-latency clean peer. Lossiness becomes
     * one of several selection signals instead of a hard gate.
     */
    private static final float LOSSY_SELECTION_PENALTY = 4.0f;

    /**
     * Latency used as a peer's selection weight when it has no measured
     * peer-test average. The widest weight means the widest priority range,
     * so an unmeasured peer is drawn last.
     * @since 0.9.71+
     */
    private static final float DEFAULT_PRIORITY_LATENCY_MS = 5000f;

    /**
     * Adaptive absolute-RTT ceiling for fast-tier selection. The fast tier is a
     * relative ranking (top N% by speed), so on a degraded network its members
     * can still sit at high absolute latency and intermittently drop build
     * messages. The existing {@link #demoteIfHighRTT} uses a fixed bar and only
     * fires on individual peer-test events. These constants define a selection-time
     * ceiling derived from the measured fast-tier RTT boundary ({@link #_thresholdRTT}):
     * the ceiling is the boundary times AUTO_RTT_MULTIPLIER, clamped to at least
     * AUTO_RTT_FLOOR_MS and at most AUTO_RTT_CAP_MS. Peers whose measured tunnel-test
     * RTT exceeds the ceiling are excluded from fast-tier picks (soft signal, not a
     * tier eviction — consistent with the lossiness-is-one-signal philosophy).
     * @since 0.9.71+
     */
    private static final double AUTO_RTT_MULTIPLIER = 3.0d;
    /** Floor: never exclude peers below this RTT, matching the demoteIfHighRTT bar. */
    private static final long AUTO_RTT_FLOOR_MS = 1500L;
    /** Absolute cap: the ceiling never rises above this even on a fully slow pool. */
    private static final long AUTO_RTT_CAP_MS = 3000L;




    /** Config property for the maximum number of peer profiles. */
    public static final String PROP_MAX_PROFILES = "profileOrganizer.maxProfiles";
    /** Runtime-adjustable default max profile count. */
    public static volatile int _defaultMaxProfiles = getDefaultMaxProfiles();
    /** @since 0.9.70+ */
    public static int getDefaultMaxProfilesValue() { return _defaultMaxProfiles; }
    /** @since 0.9.70+ */
    public static void setDefaultMaxProfiles(int val) { _defaultMaxProfiles = Math.max(MIN_MAX_PROFILES, Math.min(ABSOLUTE_MAX_PROFILES, val)); }
    /**
     * ABSOLUTE_MAX_PROFILES.
     */
    public static final int ABSOLUTE_MAX_PROFILES = 8000;
    /**
     * MIN_MAX_PROFILES.
     */
    public static final int MIN_MAX_PROFILES = 800;

    private static int getDefaultMaxProfiles() {
        if (SystemVersion.isSlow()) return MIN_MAX_PROFILES;
        return ABSOLUTE_MAX_PROFILES;
    }

    private static final long[] RATES = {
        RateConstants.ONE_MINUTE,
        RateConstants.FIVE_MINUTES,
        RateConstants.TEN_MINUTES,
        RateConstants.ONE_HOUR
    };

    private final ReentrantReadWriteLock _reorganizeLock = new ReentrantReadWriteLock(false);

    /**
     *  Timestamp of the last starved-tier warning; selections hold only the read
     *  lock, so this is read and written concurrently.
     *
     *  @since 0.9.71+
     */
    private volatile long _lastStarveWarn;

    /**
     * Creates a profile organizer with empty tier maps and registers
     * performance tracking statistics for peer classification.
     *
     * @param context the router context providing config, stats, and log access
     */
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
        // Counted here rather than in TunnelBuildEvent because every selection path
        // funnels through passesBasicGates. Compare with tunnel.buildBanHit: if this is
        // materially larger, a selection path is handing out banned peers.
        _context.statManager().createRequiredRateStat("tunnel.peerBannedAtSelection",
                "Banned peers rejected by the selection gates", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.failedLookupRate", "NetDb Lookup failure rate", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.profileCount", "Stored in RAM", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.activeProfileCount", "Number of active peer profiles", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.fastPeerCount", "Number of fast-tier peers", "Peers", RATES);
        // peer.fastPeerCount and router.fastPeers are fed from the same
        // _fastPeers set, as are peer.highCapPeerCount and router.highCapacityPeers, and
        // CoalesceStatsEvent feeds both members of each pair on its short cycle so the
        // one-minute window is always covered. Both are kept because each appears on
        // /configstats and dropping one would break saved graph selections. Never place
        // both members of a pair in one combined graph: they would plot on top of
        // each other.
        _context.statManager().createRequiredRateStat("peer.highCapPeerCount", "Number of high-capacity peers", "Peers", RATES);
        // The union of the two tiers. A peer can be both fast and high-capacity, so neither
        // count contains the other and the sum over-counts; this is what the "Fast" series
        // in the profile-tier graph has to be for the high-capacity line to be a true subset
        // of it rather than merely a smaller number.
        _context.statManager().createRequiredRateStat("peer.fastOrHighCapProfileCount",
                "Number of fast or high-capacity peers", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.qualityPeerCount", "Peers with good acceptance + recent activity", "Peers", RATES);
        // Promotion deferral on a stale RouterInfo. peer.routerInfoRefreshNeeded and
        // peer.promotedStaleRouterInfo are the hypothesis counters: together they
        // say how many promotions are being held, and how many are going ahead on a
        // RouterInfo already known to be stale. Neither implies any network work —
        // a held promotion is only a peer the tier does not have yet.
        _context.statManager().createRequiredRateStat("peer.routerInfoRefreshNeeded",
                "Tier promotions held back for a stale RouterInfo (both tiers counted)", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.promotedStaleRouterInfo",
                "Tier promotions accepted on a stale RouterInfo by the starvation guard", "Peers", RATES);
    }

    /**
     * Check if current router is firewalled to adjust peer selection thresholds
     * @return whether firewalled
     */
    private boolean isFirewalled() {
        CommSystemFacade.Status status = _context.commSystem().getStatus();
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
            Thread.currentThread().interrupt();
            return false;
        }
    }
    private void releaseWriteLock() {_reorganizeLock.writeLock().unlock();}

    /**
     * Store the local router hash for self-exclusion from peer selection.
     */
    public void setUs(Hash us) {_us = us;}

    /**
     * Self-exclusion hash for this router instance.
     *
     * @return the local router hash, or null if not set
     */
    public Hash getUs() {return _us;}

    /**
     * Current minimum speed value for the fast peer tier.
     *
     * @return the speed threshold computed during the last reorganize
     */
    public double getSpeedThreshold() {return _thresholdSpeedValue;}

    /**
     * Average tunnel test time (RTT in ms) of the peer at the fast-tier speed
     * boundary from the last reorganize, or 0.0 if never reorganized.
     *
     * @return the boundary RTT in ms
     * @since 0.9.71+
     */
    public double getFastRTTThreshold() {return _thresholdRTT;}

    /**
     * Current minimum capacity value for the high-capacity peer tier.
     *
     * @return the capacity threshold computed during the last reorganize
     */
    public double getCapacityThreshold() {return _thresholdCapacityValue;}

    /**
     * Current minimum integration value for the well-integrated peer tier.
     *
     * @return the integration threshold computed during the last reorganize
     */
    public double getIntegrationThreshold() {return _thresholdIntegrationValue;}

    /**
     * Retrieve the profile for a peer, or null if unknown.
     * Blocks on the reorganize read lock.
     *
     * @param peer the router hash to look up
     * @return the existing profile, or null if none exists
     */
    public PeerProfile getProfile(Hash peer) {
        if (peer != null && peer.equals(_us)) return null;
        getReadLock();
        try {return lockedGetProfile(peer);}
        finally {releaseReadLock();}
    }

    /**
     * Retrieve the profile for a peer without blocking.
     * Returns null immediately if the lock is contended.
     *
     * @param peer the router hash to look up
     * @return the existing profile, or null if none exists or lock unavailable
     */
    public PeerProfile getProfileNonblocking(Hash peer) {
        if (peer != null && peer.equals(_us)) return null;
        if (tryReadLock()) {
            try {return lockedGetProfile(peer);}
            finally {releaseReadLock();}
        }
        return null;
    }

    /**
     * Retrieve an existing profile or create a new one, without blocking on contention.
     * Uses try-lock escalation to avoid blocking the caller.
     *
     * @param peer the router hash to look up or create a profile for
     * @return the existing or new profile, or null if the peer is excluded or lock contention
     * @since 0.9.47, public since 0.9.70
     */
    public PeerProfile getOrCreateProfileNonblocking(Hash peer) {
        if (peer == null || peer.equals(_us) || !tryReadLock()) return null;
        PeerProfile rv;
        try {rv = lockedGetProfile(peer);}
        finally {releaseReadLock();}
        if (rv != null) return rv;

        if (isExcludedFromProfiling(peer)) return null;

        rv = new PeerProfile(_context, peer);
        rv.setLastHeardAbout(rv.getFirstHeardAbout());
        rv.coalesceStats();
        if (!tryWriteLock()) return null;

        try {
            PeerProfile old = lockedGetProfile(peer);
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

    /**
     * Insert a fully-constructed profile into the organizer.
     * The profile is added to the not-failing set and high-capacity
     * tier if it meets the current capacity threshold.
     *
     * @param profile the profile to add
     * @return the previous profile for the same peer, or null if none existed
     */
    public PeerProfile addProfile(PeerProfile profile) {
        if (profile == null) return null;
        Hash peer = profile.getPeer();
        if (peer.equals(_us)) return null;
        if (isExcludedFromProfiling(peer)) return null;

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
             enforceProfileCap();
        } finally {releaseWriteLock();}
        return old;
    }

    private int count(Map<Hash, PeerProfile> m) {
        getReadLock();
        try {return m.size();}
        finally {releaseReadLock();}
    }

    /**
     * Number of peers classified as fast tier.
     *
     * @return fast peer count (read-lock protected)
     */
    public int countFastPeers() {return count(_fastPeers);}

    /**
     * Number of peers classified as high-capacity tier.
     *
     * @return high-capacity peer count (read-lock protected)
     */
    public int countHighCapacityPeers() {return count(_highCapacityPeers);}

    /**
     * Total number of peer profiles in memory (all tiers).
     *
     * @return profile count (read-lock protected)
     */
    public int countNotFailingPeers() {return count(_notFailingPeers);}

    /**
     * Count peers with any send or hear activity in the last 4 hours.
     *
     * @return active peer count (read-lock protected)
     */
    public int countActivePeers() {
        int activePeers = 0;
        long hideBefore = _context.clock().now() - 4*60*60*1000L;

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

    /**
     * Count peers with activity (send, receive, or failure) within the last hour.
     *
     * @return recently active peer count (read-lock protected)
     */
    public int countActivePeersInLastHour() {
        int activePeers = 0;
        long hideBefore = _context.clock().now() - 60*60*1000L;

        getReadLock();
        try {
            for (PeerProfile profile : _notFailingPeers.values()) {
                if (profile.getIsActive(60*60*1000L) ||
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

    /**
     * Check whether a peer is classified in the fast tier.
     *
     * @param peer the router hash to check
     * @return true if the peer is in the fast tier set
     */
    public boolean isFast(Hash peer) {return isX(_fastPeers, peer);}

    /**
     * The number of quality peers currently in the fast tier.
     * A quality peer is fast and either high-capacity or has proven throughput.
     * Used by the renderer to gate demotion of failing peers — when the tier
     * is healthy (&ge;300 quality peers), non-ok peers are evicted instantly.
     *
     * @return the count of quality fast peers
     * @since 0.9.71+
     */
    public int getFastQualityCount() {return _fastQualityCount;}

    /**
     * Check whether a peer is classified in the high-capacity tier.
     *
     * @param peer the router hash to check
     * @return true if the peer is in the high-capacity tier set
     */
    public boolean isHighCapacity(Hash peer) {return isX(_highCapacityPeers, peer);}

    /**
     * Check whether a peer is classified in the well-integrated tier.
     *
     * @param peer the router hash to check
     * @return true if the peer is in the well-integrated tier set
     */
    public boolean isWellIntegrated(Hash peer) {return isX(_wellIntegratedPeers, peer);}

    /**
     * Remove all profiles and clear all tier maps.
     * Package-private: only called from tests and router shutdown paths.
     */
    void clearProfiles() {
        if (!getWriteLock()) return;
        try {
            _fastPeers.clear();
            _fastQualityCount = 0;
            _highCapacityPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();
            _wellIntegratedPeers.clear();
            _strictCapacityOrder.clear();
        } finally {releaseWriteLock();}
    }

    private static final int MAX_BAD_REPLIES_PER_HOUR = 40;

    /**
     * Check whether a peer sends an excessive rate of invalid or failed
     * netDb lookup replies (potential misbehavior indicator).
     *
     * @param peer the router hash to check
     * @return true if the peer exceeds the bad-reply threshold in the last hour
     */
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

    /**
     * Write a peer profile to the given output stream for persistence.
     *
     * @param profile the router hash identifying the profile to export
     * @param out the destination stream for the serialized profile
     * @return true if the profile was found and written, false if no profile exists
     * @throws IOException on write errors
     */
    public boolean exportProfile(Hash profile, OutputStream out) throws IOException {
        PeerProfile prof = getProfile(profile);
        boolean rv = prof != null;
        if (rv) {_persistenceHelper.writeProfile(prof, out);}
        return rv;
    }

    /**
     * Select up to howMany fast-tier peers, filling shortfalls from high-capacity.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     */
    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectFastPeers(howMany, exclude, matches, 0, null);
    }

    /**
     * Select fast-tier peers with IP-subnet diversity constraints.
     * Falls through to high-capacity tier on shortfall.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet mutable set tracking already-selected subnets
     */
    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        double buildSuccess = getTunnelBuildSuccess();
        getReadLock();
        try {lockedSelectPeers(_fastPeers, howMany, exclude, matches, mask, ipSet, buildSuccess, computeAdaptiveRttCeiling(_thresholdRTT, buildSuccess));}
        finally {releaseReadLock(); flushSelectionCounters();}
        if (matches.size() < howMany) {
            if (_log.shouldDebug()) {
                _log.debug("Need " + howMany + " Fast peers in tier -> " + matches.size() +
                           " found, selecting remainder from High Capacity tier...");
            }
            selectHighCapacityPeers(howMany, exclude, matches, mask, ipSet);
        }
    }

    /**
     * Select fast-tier peers deterministically sliced by a random key.
     * This enables different peers per tunnel to improve path diversity.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param randomKey key for deterministic sub-tier assignment
     * @param subTierMode slicing mode (SLICE_ALL to skip slicing)
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet mutable set tracking already-selected subnets
     */
    public void selectFastPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, SessionKey randomKey,
                                    Slice subTierMode, int mask, MaskedIPSet ipSet) {
        double buildSuccess = getTunnelBuildSuccess();
        getReadLock();
        try {
            long rttCeiling = computeAdaptiveRttCeiling(_thresholdRTT, buildSuccess);
            if (subTierMode != Slice.SLICE_ALL)
                lockedSelectPeers(_fastPeers, howMany, exclude, matches, randomKey, subTierMode, mask, ipSet, buildSuccess, rttCeiling);
            else
                lockedSelectPeers(_fastPeers, howMany, exclude, matches, mask, ipSet, buildSuccess, rttCeiling);
        } finally {releaseReadLock();}
        if (matches.size() < howMany) {
            if (_log.shouldDebug())
                _log.debug("Need " + howMany + " Fast peers in tier -> " + matches.size() +
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
        /** All peers */
        SLICE_ALL(0x00, 0),
        /** Peers in sub-tier 0 or 1 */
        SLICE_0_1(0x02, 0),
        /** Peers in sub-tier 2 or 3 */
        SLICE_2_3(0x02, 2),
        /** Peers in sub-tier 0 */
        SLICE_0(0x03, 0),
        /** Peers in sub-tier 1 */
        SLICE_1(0x03, 1),
        /** Peers in sub-tier 2 */
        SLICE_2(0x03, 2),
        /** Peers in sub-tier 3 */
        SLICE_3(0x03, 3);
        /** Selection mask */
        final int mask;
        /** Selection value */
        final int val;
        /** Create slice */
        Slice(int mask, int val) {
            this.mask = mask;
            this.val = val;
        }
    }

    /**
     * Select high-capacity peers, filling shortfalls from not-failing peers.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     */
    public void selectHighCapacityPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectHighCapacityPeers(howMany, exclude, matches, 0, null);
    }

    /**
     * Select high-capacity peers with IP-subnet diversity.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet mutable set tracking already-selected subnets
     */
     public void selectHighCapacityPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
         double buildSuccess = getTunnelBuildSuccess();
         getReadLock();
         try {
             // More lenient RTT ceiling for high-capacity peers:
             // they typically have higher latency than fast-tier peers.
             long cap = computeAdaptiveRttCeiling(_thresholdRTT, buildSuccess) * 2;
             long rttCeiling = Math.min(cap, AUTO_RTT_CAP_MS);
             lockedSelectPeers(_highCapacityPeers, howMany, exclude, matches, mask, ipSet, buildSuccess, rttCeiling);
         } finally {releaseReadLock(); flushSelectionCounters();}
        if (matches.size() < howMany) {
            if (_log.shouldDebug()) {
                _log.debug("Need " + (howMany > 1 ? "High Capacity peers" : "High Capacity peer") +
                           " in tier -> " + matches.size() + " found, selecting from non-failing peers...");
            }
            selectNotFailingPeers(howMany, exclude, matches, mask, ipSet);
        }
    }

    /**
     * Select from not-failing peers, excluding high-capacity tier if requested.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectNotFailingPeers(howMany, exclude, matches, false, 0, null);
    }

    /**
     * Select from not-failing peers with IP-subnet diversity.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet mutable set tracking already-selected subnets
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        selectNotFailingPeers(howMany, exclude, matches, false, mask, ipSet);
    }

    /**
     * Select from not-failing peers, optionally excluding the high-capacity tier.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param onlyNotFailing if true, exclude peers already in high-capacity tier
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing) {
        selectNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0, null);
    }

    /**
     * Full-parameter select from not-failing peers.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param onlyNotFailing if true, exclude peers already in high-capacity tier
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet mutable set tracking already-selected subnets
     */
     public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing,
                                     int mask, MaskedIPSet ipSet) {
         selectNotFailingPeers(howMany, exclude, matches, onlyNotFailing, mask, ipSet, false);
     }

    /**
     * Full-parameter select from not-failing peers.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param onlyNotFailing if true, exclude peers already in high-capacity tier
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet mutable set tracking already-selected subnets
     * @param preferUnproven if true, prioritize peers with no tunnel
     *        test history so they accumulate profiling data
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing,
                                    int mask, MaskedIPSet ipSet, boolean preferUnproven) {
        if (matches.size() < howMany) {
            selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, mask, ipSet,
                                     getTunnelBuildSuccess(), preferUnproven);
        }
    }

    /**
     * Select from currently connected (established) not-failing peers.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     */
    public void selectActiveNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectActiveNotFailingPeers(howMany, exclude, matches, 0, null);
    }

    /**
     * Select from currently connected peers with IP-subnet diversity.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet mutable set tracking already-selected subnets
     */
    public void selectActiveNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, int mask, MaskedIPSet ipSet) {
        if (matches.size() < howMany) {
            List<Hash> connected = _context.commSystem().getEstablished();
            if (connected != null && !connected.isEmpty()) {
                double buildSuccess = getTunnelBuildSuccess();
                getReadLock();
                try {lockedSelectActive(connected, howMany, exclude, matches, mask, ipSet, buildSuccess);}
                finally {releaseReadLock(); flushSelectionCounters();}
            }
        }
    }

    /**
     *  Non-blocking test peer selection: tries the full fast → high-cap →
     *  active chain under a single {@link #tryReadLock()}.  Returns false
     *  (leaving {@code matches} unchanged) if the read lock cannot be
     *  acquired — the caller should skip this round and requeue.
     *  Used exclusively by {@link PeerTestJob} to avoid blocking on
     *  reorganize()'s write lock during startup profiling.
     *
     *  @param howMany target number of peers
     *  @param exclude peers to exclude
     *  @param matches output set populated with selected peer hashes
     *  @return true if selection ran, false if lock was not acquired
     *  @since 0.9.71+
     */
    public boolean selectTestPeersNonBlocking(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        if (!tryReadLock()) return false;
        try {
            double buildSuccess = getTunnelBuildSuccess();
            long rttCeiling = computeAdaptiveRttCeiling(_thresholdRTT, buildSuccess);
            lockedSelectPeers(_fastPeers, howMany, exclude, matches, 0, null, buildSuccess, rttCeiling);
            if (matches.size() < howMany) {
                long cap = rttCeiling * 2;
                long hcRtt = Math.min(cap, AUTO_RTT_CAP_MS);
                lockedSelectPeers(_highCapacityPeers, howMany, exclude, matches, 0, null, buildSuccess, hcRtt);
            }
            if (matches.size() < howMany) {
                List<Hash> connected = _context.commSystem().getEstablished();
                if (connected != null && !connected.isEmpty()) {
                    lockedSelectActive(connected, howMany, exclude, matches, 0, null, buildSuccess);
                }
            }
            return true;
        } finally {releaseReadLock(); flushSelectionCounters();}
    }

    /**
     *  Select up to howMany peers from O/P/X bandwidth tiers (high shared bandwidth)
     *  that are not failing.  Falls through to selectAllNotFailingPeers on shortfall.
     *
     *  @param howMany target number of peers
     *  @param exclude peers to exclude (may be null)
     *  @param matches output set populated with selected peer hashes
     */
    public void selectHighBandwidthPeers(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        selectHighBandwidthPeers(howMany, exclude, matches, false, 0, null);
    }

    /**
     * Select high-bandwidth peers with tier-exclusion and IP-subnet diversity.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param onlyNotFailing if true, exclude peers already in high-capacity tier
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet mutable set tracking already-selected subnets
     */
    public void selectHighBandwidthPeers(int howMany, Set<Hash> exclude, Set<Hash> matches,
                                         boolean onlyNotFailing, int mask, MaskedIPSet ipSet) {
        if (matches.size() < howMany) {
            selectHighBandwidthPeers(howMany, exclude, matches, onlyNotFailing, mask, ipSet,
                                     getTunnelBuildSuccess());
        }
    }

    /**
     * Iterates not-failing peers randomly, filtering for O/P/X bandwidth tiers
     * with reliability checks (acceptance ratio, recent activity, peer tests).
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param onlyNotFailing if true, exclude peers already in high-capacity tier
     * @param buildSuccess the build success ratio, fetched once per scan
     */
    private void selectHighBandwidthPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing,
                                          int mask, MaskedIPSet ipSet, double buildSuccess) {
        if (matches.size() < howMany) {
            int needed = howMany - matches.size();
            List<Hash> selected = new ArrayList<>(needed);
            long now = _context.clock().now();
            getReadLock();
            try {
                for (Iterator<Hash> iter = new RandomIterator<>(_notFailingPeersList); selected.size() < needed && iter.hasNext(); ) {
                    Hash cur = iter.next();
                    if (matches.contains(cur) || (exclude != null && exclude.contains(cur))) continue;
                    if (onlyNotFailing && _highCapacityPeers.containsKey(cur)) continue;
                    PeerProfile profile = _notFailingPeers.get(cur);
                    if (!passesBasicGates(cur) || isExcessiveLifetimeFailure(profile)) continue;
                    RouterInfo info = lookupRouterInfoUnvalidated(cur);
                    if (info != null) {
                        if (isHighBandwidthTierName(DataHelper.stripHTML(info.getBandwidthTier()))) {
                            // Reliability check: skip peers with low acceptance or no recent activity
                            if (profile != null && !isReliableBandwidthPeer(profile, now,
                                                                            _context.commSystem().isEstablished(cur))) {
                                continue;
                            }
                            // Subnet diversity runs last: notRestricted() marks this
                            // peer's subnet (the mask's prefix length, e.g. /16) as
                            // used in ipSet, so running it before the checks above
                            // would consume that subnet for a peer then rejected.
                            if (mask > 0 && !notRestricted(cur, ipSet, mask)) continue;
                            selected.add(cur);
                        }
                    }
                }
            } finally {
                releaseReadLock();
                flushSelectionCounters();
            }
            matches.addAll(selected);
        }
        if (matches.size() < howMany) {
            selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, mask, ipSet, buildSuccess, false);
        }
    }

    /**
     *  Reliability gate for high-bandwidth (O/P/X tier) selection: the peer
     *  must show a tunnel acceptance ratio of at least 0.3 AND evidence of
     *  recent activity — a successful peer test within 10 minutes, a heard-
     *  from/send-success within 30 minutes, or an established commSystem
     *  connection.  Peers with no profile are not gated (no data yet).
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param profile the peer profile (non-null)
     *  @param now current time in ms
     *  @param established whether commSystem has an established connection
     *  @return whether the peer passes the reliability gate
     *  @since 0.9.71+
     */
    static boolean isReliableBandwidthPeer(PeerProfile profile, long now, boolean established) {
        if (profile.getTunnelAcceptanceRatio() < 0.3) return false;
        long tenMinutes = 10 * 60 * 1000L;
        long thirtyMinutes = 30 * 60 * 1000L;
        boolean recentTest = profile.getLastTestedSuccessfully() > 0 &&
            now - profile.getLastTestedSuccessfully() < tenMinutes;
        boolean recentActivity =
            (profile.getLastHeardFrom() > 0 && now - profile.getLastHeardFrom() < thirtyMinutes) ||
            (profile.getLastSendSuccessful() > 0 && now - profile.getLastSendSuccessful() < thirtyMinutes);
        return recentTest || recentActivity || established;
    }

    /**
     * Select from all not-failing peers randomly, falling through to general
     * peers if the not-failing set is insufficient.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param onlyNotFailing if true, exclude peers already in high-capacity tier
     */
    public void selectAllNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing) {
        selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, 0, null, getTunnelBuildSuccess(), false);
    }

    /**
     * Select from all not-failing peers randomly, honouring a /n subnet
     * diversity restriction.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param onlyNotFailing if true, exclude peers already in high-capacity tier
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet subnets already represented in this tunnel
     */
    public void selectAllNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches,
                                          boolean onlyNotFailing, int mask, MaskedIPSet ipSet) {
        selectAllNotFailingPeers(howMany, exclude, matches, onlyNotFailing, mask, ipSet,
                                 getTunnelBuildSuccess(), false);
    }

    /**
     * Random iterator over not-failing list with optional tier exclusion.
     * Falls through to selectAllPeers() if shortfall remains.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param onlyNotFailing if true, exclude peers already in high-capacity tier
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet subnets already represented in this tunnel, consulted only
     *        when mask is non-zero
     * @param preferUnproven if true, prioritize peers with no tunnel test history
     *        so they accumulate profiling data through exploratory builds
     */
     private void selectAllNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing,
                                      int mask, MaskedIPSet ipSet, double buildSuccess, boolean preferUnproven) {
        if (matches.size() < howMany) {
            int needed = howMany - matches.size();
            List<Hash> selected = new ArrayList<>(needed);
            long now = _context.clock().now();
            getReadLock();
            try {
                if (preferUnproven) {
                    // Two-pass: unproven peers first so they get profiled
                    for (int pass = 0; pass < 2 && selected.size() < needed; pass++) {
                        boolean inPass = (pass == 0);
                        for (Iterator<Hash> iter = new RandomIterator<>(_notFailingPeersList); selected.size() < needed && iter.hasNext(); ) {
                            Hash cur = iter.next();
                            if (matches.contains(cur) || (exclude != null && exclude.contains(cur))) continue;
                            if (onlyNotFailing && _highCapacityPeers.containsKey(cur)) continue;
                            PeerProfile prof = lockedGetProfile(cur);
                            if (prof != null && inLossProbation(prof, now)) continue;
                            // First pass: only unproven peers (totalRequests == 0)
                            // Second pass: all selectable peers
                            if (inPass && prof != null && prof.getTunnelHistory() != null &&
                                   prof.getTunnelHistory().getLifetimeAgreedTo() + prof.getTunnelHistory().getLifetimeRejected() > 0)
                                continue;
                            // Same /n diversity gate the tier selectors apply, so
                            // a caller reaching this last-resort pool still honours
                            // a configured subnet restriction.  Runs last: it marks
                            // the peer's subnet (the mask's prefix length) as used in
                            // ipSet, so it must not run for a peer rejected above.
                            if (isSelectable(cur, buildSuccess) &&
                                    (mask <= 0 || notRestricted(cur, ipSet, mask)))
                                selected.add(cur);
                        }
                    }
                } else {
                    for (Iterator<Hash> iter = new RandomIterator<>(_notFailingPeersList); selected.size() < needed && iter.hasNext(); ) {
                        Hash cur = iter.next();
                        if (matches.contains(cur) || (exclude != null && exclude.contains(cur))) continue;
                        if (onlyNotFailing && _highCapacityPeers.containsKey(cur)) continue;
                        // Keep peers in loss probation out of even the last-resort pool;
                        // they only get picked if literally nothing else is usable.
                        PeerProfile prof = lockedGetProfile(cur);
                        if (prof != null && inLossProbation(prof, now)) continue;
                        // Same /n diversity gate the tier selectors apply, so
                        // a caller reaching this last-resort pool still honours
                        // a configured subnet restriction.  Runs last: it marks
                        // the peer's subnet (the mask's prefix length) as used in
                        // ipSet, so it must not run for a peer rejected above.
                        if (isSelectable(cur, buildSuccess) &&
                                (mask <= 0 || notRestricted(cur, ipSet, mask)))
                            selected.add(cur);
                    }
                }
            } finally {
                releaseReadLock();
                flushSelectionCounters();
            }
            matches.addAll(selected);
        }

        if (matches.size() < howMany) {
            selectRemainderFromAllPeers(howMany, exclude, matches, buildSuccess);
        }
    }

    /**
     *  Fallback selection: fill the remaining slots from the union of all
     *  peers (fast + high-capacity + not-failing).
     *
     *  @param howMany target number of peers
     *  @param exclude peers to exclude (may be null)
     *  @param matches output set populated with selected peer hashes
     *  @param buildSuccess the build success ratio, fetched once per scan
     */
    private void selectRemainderFromAllPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, double buildSuccess) {
        if (_log.shouldDebug()) {
            _log.debug("Need " + howMany + " Not Failing peers -> " + matches.size() +
                       " found, selecting remainder from general peers...");
        }

        Set<Hash> allPeers = selectAllPeers();
        try {
            for (Hash peer : allPeers) {
                if (matches.size() >= howMany) break;
                if (matches.contains(peer) || (exclude != null && exclude.contains(peer))) continue;
                if (isSelectable(peer, buildSuccess)) matches.add(peer);
            }
        } finally {
            flushSelectionCounters();
        }
    }

    /**
     * Gather the union of all peers from fast, high-capacity, and not-failing tiers.
     *
     * @return a new set containing every tracked peer hash
     */
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

    /**
     * Return the current fast tier membership directly.
     * Used by the renderer to avoid iterating all peers for fast-only views.
     *
     * @return unmodifiable snapshot of fast tier hashes
     * @since 0.9.71+
     */
    public Set<Hash> selectFastPeers() {
        getReadLock();
        try {
            return java.util.Collections.unmodifiableSet(new HashSet<>(_fastPeers.keySet()));
        } finally {releaseReadLock();}
    }

    /**
     * Return the current high-capacity tier membership directly.
     * Used by the renderer to avoid iterating all peers for highcap-only views.
     *
     * @return unmodifiable snapshot of high-capacity tier hashes
     * @since 0.9.71+
     */
    public Set<Hash> selectHighCapacityPeers() {
        getReadLock();
        try {
            return java.util.Collections.unmodifiableSet(new HashSet<>(_highCapacityPeers.keySet()));
        } finally {releaseReadLock();}
    }

    /**
     * Return the current well-integrated tier membership directly.
     * Used by the renderer to snapshot integration status without
     * per-peer lock acquisitions during rendering.
     *
     * @return unmodifiable snapshot of well-integrated tier hashes
     * @since 0.9.71+
     */
    public Set<Hash> selectWellIntegratedPeers() {
        getReadLock();
        try {
            return java.util.Collections.unmodifiableSet(new HashSet<>(_wellIntegratedPeers.keySet()));
        } finally {releaseReadLock();}
    }

    /**
     * Last known number of profiles stored on disk, from the most recent load,
     * cleanup, or purge in the persistence helper.
     *
     * @return the stored profile count, 0 if the store has never been scanned
     * @since 0.9.71+
     */
    public int getStoredProfileCount() {return _persistenceHelper.getStoredProfileCount();}

    private static final int ENOUGH_PROFILES = getEnoughProfiles();

    private static int getEnoughProfiles() {
        if (SystemVersion.isSlow()) return 1000;
        long maxMemory = SystemVersion.getMaxMemory();
        if (maxMemory >= 4L * 1024 * 1024 * 1024) return 8000;
        if (maxMemory >= 2L * 1024 * 1024 * 1024) return 6000;
        if (maxMemory >= 1L * 1024 * 1024 * 1024) return 4000;
        return 2000;
    }

    /**
     * Run a standard reorganize round without coalescing.
     * Equivalent to reorganize(false).
     */
    void reorganize() {
        reorganize(false);
    }

    /**
     * Reorganizes peer profiles into performance-based tiers (fast, high-capacity, etc.) and expires stale entries.
     * <p>
     * This method:
     * <ul>
     *   <li>Coalesces stats if requested and uptime conditions are met. Peak
     *       throughput values are never decayed.</li>
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
     */
    /**
     *  Fraction of a tier population that must carry a usable first-hop RTT before
     *  latency is allowed to influence tier membership.
     *
     *  <p>A low-latency decision taken over a small measured subset describes that
     *  subset, not the tier: the peers we happen to have sessions with are the
     *  ones we have been talking to, so an early sample is drawn from whoever was
     *  contacted most recently rather than from the population. Requiring a
     *  majority of the tier to be measured keeps the decision anchored to the tier
     *  it is applied to.
     *
     * @param measured peers in the tier with a usable first-hop RTT
     * @param population peers in the tier
     * @return true when latency may be used to judge this tier
     * @since 0.9.71+
     */
    static boolean latencySampleIsBroadEnough(int measured, int population) {
        if (population <= 0) {return false;}
        return measured * 2 >= population;
    }

    /**
     *  Refresh direct-link RTT for tier peers that already have a transport
     *  session, and re-judge their low-latency standing.
     *
     *  <p>Decoupled from the build pre-connect on purpose. The pre-connect is a
     *  one-shot probe fired to force establishment, so it reads the transport at
     *  the least informative moment: a session that was just created has not
     *  completed a round trip and reports zero. This samples instead from sessions
     *  that have been up long enough to have measured, which is the value the
     *  latency tiers actually want.
     *
     *  <p>Peers with no session are skipped rather than probed. Probing here would
     *  re-create the problem above: establishing a session to measure it is what
     *  makes the measurement unavailable.
     *
     * @return number of peers whose RTT was refreshed
     * @since 0.9.71+
     */
    int sampleFirstHopRtts() {
        CommSystemFacade commSystem = _context.commSystem();
        if (commSystem == null) {return 0;}
        Collection<Transport> transports = commSystem.getTransports().values();
        if (transports.isEmpty()) {return 0;}
        long now = _context.clock().now();
        int sampled = 0;
        long rttSum = 0;
        long rttCount = 0;
        getReadLock();
        try {
            for (PeerProfile profile : sampleTierPeers()) {
                int rtt = sampleFirstHopRtt(profile, transports, now);
                if (rtt > 0) {
                    sampled++;
                    rttSum += rtt;
                    rttCount++;
                }
            }
        } finally {
            releaseReadLock();
        }
        // One stat sample per cycle carrying the cycle mean, not one per sampled
        // peer: the rate's consumers (console, jrobin) plot the average, so only
        // the within-cycle spread is lost.
        if (rttCount > 0) {
            _context.statManager().addRateData("tunnel.firstHopRtt",
                                              Math.round(rttSum / (double) rttCount));
        }
        if (sampled > 0 && _log.shouldDebug()) {
            _log.debug("Sampled first hop RTT for " + sampled + " tier peers");
        }
        return sampled;
    }

    /**
     *  Bounded random sample of the tier peers for RTT measurement, capped at
     *  {@link #DEFAULT_MIN_CANDIDATE_SAMPLE} with a rotated start so successive
     *  cycles do not always read the same prefix.
     *
     *  <p>Sampling is safe here because a peer's RTT is a property of its transport
     *  session, not an accumulator: a peer missed this cycle is measured in a
     *  later one.
     *
     *  @return the sampled profiles, at most {@link #sampleFirstHopRttLimit(int)}
     *  @since 0.9.71+
     */
    private List<PeerProfile> sampleTierPeers() {
        int fast = _fastPeers.size();
        int highCap = _highCapacityPeers.size();
        int total = fast + highCap;
        int limit = sampleFirstHopRttLimit(total);
        List<PeerProfile> sample = new ArrayList<>(Math.min(limit, total));
        if (total == 0) return sample;
        int offset = ThreadLocalRandom.current().nextInt(total);
        int idx = 0;
        for (PeerProfile profile : _fastPeers.values()) {
            if (sample.size() >= limit) break;
            if (((idx++ - offset) % total + total) % total < limit) sample.add(profile);
        }
        for (PeerProfile profile : _highCapacityPeers.values()) {
            if (sample.size() >= limit) break;
            if (((idx++ - offset) % total + total) % total < limit) sample.add(profile);
        }
        return sample;
    }

    /**
     *  How many tier peers to sample for first-hop RTT in one reorganize.
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param total combined size of the two tiers
     *  @return the sample size, never negative
     *  @since 0.9.71+
     */
    static int sampleFirstHopRttLimit(int total) {
        if (total <= 0) return 0;
        return Math.min(total, DEFAULT_MIN_CANDIDATE_SAMPLE);
    }

    /**
     * Record the best RTT any transport reports for this peer, if any.
     *
     * @return the recorded RTT in ms, or 0 if there was no measurement
     */
    private int sampleFirstHopRtt(PeerProfile profile, Collection<Transport> transports, long now) {
        if (profile == null) {return 0;}
        Hash peer = profile.getPeer();
        if (peer == null) {return 0;}
        int best = 0;
        for (Transport transport : transports) {
            int rtt = transport.getEstimatedRTT(peer);
            if (rtt > best) {best = rtt;}
        }
        if (best <= 0) {return 0;}
        profile.setFirstHopRtt(best, now);
        profile.recalculateLowLatency();
        return best;
    }

    /**
     *  Peers in either the fast or the high-capacity tier, counting each peer once.
     *
     *  <p>A peer can be both, so neither tier contains the other and adding the two
     *  counts over-reports. This is the number the profile-tier graph needs for its
     *  high-capacity line to be a true subset of the fast line, rather than merely a
     *  smaller number that happens to be plotted beside it.
     *
     *  <p>Walks the smaller tier and probes the larger, so the cost is
     *  {@code O(min(fast, highCap))} once per coalesce rather than a full merge.
     *
     *  @return size of the union of the two tiers
     *  @since 0.9.71+
     */
    int fastOrHighCapCount() {
        Map<Hash, PeerProfile> smaller = _fastPeers.size() <= _highCapacityPeers.size()
                                       ? _fastPeers : _highCapacityPeers;
        Map<Hash, PeerProfile> larger = smaller == _fastPeers ? _highCapacityPeers : _fastPeers;
        int overlap = 0;
        for (Hash peer : smaller.keySet()) {
            if (larger.containsKey(peer)) {overlap++;}
        }
        return _fastPeers.size() + _highCapacityPeers.size() - overlap;
    }

    /**
     *  Live size of the fast or high-capacity union, a peer in both tiers counted
     *  once.  Read-locked, so it is safe to call outside a coalesce pass.
     *
     *  <p>Callers that already hold the write lock — {@code reorganize()} — must
     *  call the package-visible {@link #fastOrHighCapCount()} directly; going
     *  through this accessor would re-enter the lock.
     *
     *  @return size of the union of the two tiers
     *  @see #fastOrHighCapCount()
     *  @since 0.9.71+
     */
    public int getFastOrHighCapCount() {
        getReadLock();
        try {
            return fastOrHighCapCount();
        } finally {
            releaseReadLock();
        }
    }

    void reorganize(boolean shouldCoalesce) {
        final long now = _context.clock().now();
        final long start = System.currentTimeMillis();

        // Refresh direct-link RTTs for the tiers before anything judges on them.
        // Without this the only sampling point was the build pre-connect, which
        // reads the transport immediately after forcing a new session -- always a
        // freshly established PeerState, which reports 0 and is discarded. The
        // 5-minute pre-connect cooldown then blocked the retry that would have
        // caught a measurement, so a peer needed two build selections five minutes
        // apart to record anything, and peers never selected for a build recorded
        // nothing at all. Observed coverage on a mature router was 3% of the fast
        // tier. Sampling here reads peers that already hold a session and so
        // already hold a measurement, and repeats on every reorganize.
        sampleFirstHopRtts();

        // Tiered expiration windows based on interaction level.
        // Active peers (Tier 1) retain long-term data for tunnel selection quality.
        // Passive peers (Tier 2) kept for 3 days to capture intermittent interactions.
        // Gossip-only peers (Tier 3) kept for 24h since floodfill stores are infrequent.
        final long expireActive = 7 * 24 * 60 * 60 * 1000L;    // 7 days
        final long expirePassive = 3 * 24 * 60 * 60 * 1000L;   // 3 days
        final long expireGossip = 24 * 60 * 60 * 1000L;        // 24 hours
        // Peers that never accepted or rejected a tunnel build for us aren't
        // useful — drop them from memory eventually. They'll be recreated
        // on demand if they ever respond to a build request.
        // Scale the window with the number of profiles stored: the fewer
        // profiles known, the longer we keep untracked peers around.
        int storedProfiles = _strictCapacityOrder.size();
        final long expireUntracked;
        if (storedProfiles < 2000) {
            expireUntracked = 4 * 60 * 60 * 1000L;         // 4 hours
        } else if (storedProfiles < 3000) {
            expireUntracked = 3 * 60 * 60 * 1000L;         // 3 hours
        } else if (storedProfiles < 4000) {
            expireUntracked = 2 * 60 * 60 * 1000L;         // 2 hours
        } else {
            expireUntracked = 60 * 60 * 1000L;             // 1 hour
        }

        // Optional coalescing (read-only, safe to skip if lock fails)
        if (shouldCoalesce && _context.router() != null &&
            _context.router().getUptime() > 30 * 60 * 1000L &&
            countNotFailingPeers() > (ENOUGH_PROFILES / 2)) {
            getReadLock();
            try {
                for (PeerProfile prof : _strictCapacityOrder) {
                    long lastSend = prof.getLastSendSuccessful();
                    long expireWindow = lastSend > 0 ? expireActive : expirePassive;
                    if (lastSend >= now - expireWindow) {
                        prof.coalesceOnly();
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
                    long lastSend = profile.getLastSendSuccessful();
                    long expireWindow = lastSend > 0 ? expireActive : expirePassive;
                    if (lastSend < now - expireWindow) {
                        estimatedExpired++;
                    }
                }
                // peer.profileEstimatedExpired is never registered, so recording it here was a
                // silent no-op; leave it uncounted rather than half-wire a dead stat.
            } finally {
                releaseReadLock();
            }

            _context.statManager().addRateData("peer.reorganizeLockFailures", 1, 0);
            return;
        }

        try {
            // Step 1: Build a new set of active, non-expired, non-blacklisted profiles
            CandidateSet built = buildCandidateProfiles(now, expireActive, expirePassive,
                                                        expireGossip, expireUntracked);
            Set<PeerProfile> newStrictCapacityOrder = built.profiles;
            int expiredCount = built.expiredCount;
            int profileCount = built.profileCount;
            double totalIntegration = built.totalIntegration;

            // Fetch once for the whole cycle; tier rebuild and quality counting
            // would otherwise re-read router statistics per profile.
            double buildSuccess = getTunnelBuildSuccess();

            // Step 2: Calculate new thresholds
            int numNotFailing = newStrictCapacityOrder.size();
            double newIntegrationThreshold = numNotFailing > 0 ? totalIntegration / numNotFailing : 1.0d;
            double newCapacityThreshold = calculateCapacityThresholdFromSet(newStrictCapacityOrder, numNotFailing);
            double newSpeedThreshold = calculateSpeedThreshold(newStrictCapacityOrder, now, newCapacityThreshold);

            // Step 3: Snapshot the old tier memberships as the restore fallback, then
            // clear and rebuild. Keys only: a peer still in _notFailingPeers after
            // the rebuild is by construction the same PeerProfile instance the
            // old map held.
            Set<Hash> oldFastPeers = new HashSet<>(_fastPeers.keySet());
            Set<Hash> oldHighCapPeers = new HashSet<>(_highCapacityPeers.keySet());
            _fastPeers.clear();
            _fastQualityCount = 0;
            _highCapacityPeers.clear();
            _wellIntegratedPeers.clear();
            _notFailingPeers.clear();
            _notFailingPeersList.clear();

            // Step 4: Reinsert all active profiles and assign tiers
            lockedRebuildTiers(newStrictCapacityOrder, buildSuccess);

            // Step 4a: Publish the promotion-deferral counters the rebuild opened
            // its cycle for. Read still under the write lock, so the numbers
            // reported belong to this cycle and to no other.
            recordRouterInfoRefreshStats();

            // Step 5: Fallback to ensure minimum fast peers
            int added = fillFastTierFallbacks(now, newStrictCapacityOrder, buildSuccess);

            // Step 6: Fallback to ensure minimum high-capacity peers
            int highCapAdded = fillHighCapFallback(now, newStrictCapacityOrder, buildSuccess);

            // Step 6a: Purge peers with recent tunnel failures from fast/high-cap tiers.
            purgeUnusableFromTiers(now);

            // Step 6c: Fallback to preserved pre-reorganize peers if rebuild shrunk tiers too much.
            restorePreservedPeers(now, oldFastPeers, oldHighCapPeers, buildSuccess);

            // Step 6d: Count quality peers (fast/high-cap with good acceptance + recent activity)
            // Used by tuner to adjust tier limits based on viable tunnel candidates
            int qualityCount = countQualityPeers(now, buildSuccess);
            // Recently-active profiles for peer.activeProfileCount. Counted here so the gauge
            // costs one pass inside the reorganise instead of a lock-taking scan every 50s.
            int activeCount = countActiveProfiles(now);

            // Step 6e: Compute average peer test RTT across fast peers with data.
            // When the fast tier is large enough, PeerProfile.recalculateLowLatency()
            // uses 1.5× this average instead of the fixed timeout-based cap.
            _averageLowLatencyRTT = computeAverageLowLatencyRTT();

            // Step 7: Update global thresholds
            _strictCapacityOrder = newStrictCapacityOrder;
            _thresholdCapacityValue = newCapacityThreshold;
            _thresholdIntegrationValue = newIntegrationThreshold;
            _thresholdSpeedValue = newSpeedThreshold;

            // Step 9: Log and record stats
            if (_log.shouldInfo()) {
                _log.info("Profiles reorganized: " + expiredCount + " expired, " +
                          profileCount + " retained. Thresholds -> " +
                          "Cap: " + num(_thresholdCapacityValue) +
                          ", Spd: " + num(_thresholdSpeedValue) +
                          ", Int: " + num(_thresholdIntegrationValue) +
                          " -> Fast peers: " + _fastPeers.size() + " (added " + added + " via fallback)" +
                          ", HighCap peers: " + _highCapacityPeers.size() + " (added " + highCapAdded + " via fallback)" +
                          ", Quality peers: " + qualityCount);
            }

            long total = System.currentTimeMillis() - start;
            _context.statManager().addRateData("peer.profileReorgTime", total, profileCount);
            // Publish the reorganisation's gauges for CoalesceStatsEvent to sample.
            // They used to be recorded here, but reorganize() runs every 30-250s
            // (REORGANIZE_TIME_LONG once uptime passes 2h) while RATES starts at
            // ONE_MINUTE, so an instant sample landed in only ~24% of completed
            // one-minute windows and the rest read zero events - a gauge must hold
            // its last level, not go blank. peer.profileCount is sampled from _profileCount;
            // peer.activeProfileCount is not sampled here because it needs a walk over the
            // profiles, and CoalesceStatsEvent takes it from countActivePeersInLastHour()
            // instead.
            _profileCount = profileCount;
            _qualityCount = qualityCount;
            _activeProfileCount = activeCount;
            // peer.expiredProfileCount is likewise never registered; see above.

            // Step 10: Enforce memory cap
            enforceProfileCap();

            // Step 11: Clean up persisted profiles
            purgeStaleProfileFiles();

        } finally {
            releaseWriteLock();
        }
    }

    /**
     *  Whether a profile has expired from its activity tier and should be
     *  purged: no activity (send/heard/heard-about) within the tier's
     *  expiration window.  Tiered expiration: Active > Passive > Gossip;
     *  profiles with no tunnel history are purged on the tighter untracked
     *  window regardless of tier.  A profile with zero timestamps sits in
     *  the gossip tier.
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param profile the profile
     *  @param now current time in ms
     *  @param expireActive active-tier window in ms
     *  @param expirePassive passive-tier window in ms
     *  @param expireGossip gossip-tier window in ms
     *  @param expireUntracked untracked window in ms
     *  @return whether the profile is expired
     *  @since 0.9.71+
     */
    static boolean isExpiredProfile(PeerProfile profile, long now, long expireActive, long expirePassive,
                                    long expireGossip, long expireUntracked) {
        long lastSend = profile.getLastSendSuccessful();
        long lastHeard = profile.getLastHeardFrom();
        long lastHeardAbout = profile.getLastHeardAbout();
        long expireWindow;
        if (lastSend > 0) {
            expireWindow = expireActive;
        } else if (lastHeard > 0 || lastHeardAbout > 0) {
            expireWindow = expirePassive;
        } else {
            expireWindow = expireGossip;
        }
        if (!profile.hasTunnelHistory()) {
            // No tunnel build participation — purge from memory quickly
            // regardless of activity tier.
            expireWindow = Math.min(expireWindow, expireUntracked);
        }
        long cutoff = Math.max(lastSend, Math.max(lastHeard, lastHeardAbout));
        return cutoff < now - expireWindow;
    }

    /**
     *  Builds the candidate profile set for this reorganize round, filtering
     *  expired, unreachable, and excluded profiles.
     */
    private CandidateSet buildCandidateProfiles(long now, long expireActive, long expirePassive,
                                                long expireGossip, long expireUntracked) {
        Set<PeerProfile> newStrictCapacityOrder = new TreeSet<>(_comp);
        double totalIntegration = 0;
        int expiredCount = 0;
        int profileCount = 0;

        for (PeerProfile profile : _strictCapacityOrder) {
            if (_us != null && _us.equals(profile.getPeer()) || profile.wasUnreachable()) {
                continue;
            }

            if (isExpiredProfile(profile, now, expireActive, expirePassive, expireGossip, expireUntracked)) {
                expiredCount++;
                profile.shrinkProfile();
                if (profile.getIsExpandedDB()) {
                    profile.shrinkDBProfile();
                }
                continue;
            }

            // Skip peers in low bandwidth tiers (K, L, M, Unknown) and G cap (no tunnels).
            // With isExcludedFromProfiling() gating profile creation, this is mainly
            // a safety net for legacy profiles loaded from disk.
            if (isExcludedFromProfiling(profile.getPeer())) {
                continue;
            }

            profile.updateValues(); // Refresh values (e.g., speed, capacity, integration)
            newStrictCapacityOrder.add(profile);
            totalIntegration += profile.getIntegrationValue();
            profileCount++;
        }
        return new CandidateSet(newStrictCapacityOrder, totalIntegration, expiredCount, profileCount);
    }

    /**
     *  Result of buildCandidateProfiles(): the candidate set plus the totals
     *  and counts consumed by the threshold and logging sections.
     */
    private static final class CandidateSet {
        final Set<PeerProfile> profiles;
        final double totalIntegration;
        final int expiredCount;
        final int profileCount;

        CandidateSet(Set<PeerProfile> profiles, double totalIntegration, int expiredCount, int profileCount) {
            this.profiles = profiles;
            this.totalIntegration = totalIntegration;
            this.expiredCount = expiredCount;
            this.profileCount = profileCount;
        }
    }

    /**
     *  Reinserts all active profiles into the tier maps.  Must be called with
     *  the write lock held.
     *
     *  <p>Also opens the promotion-deferral cycle, which the tier rebuild is the
     *  only place that can judge: a promotion is the moment a stale RouterInfo
     *  starts costing something, and this loop is the sole promotion path that
     *  sees the whole profile population at once.
     *
     *  @param candidates the surviving profile set
     *  @param buildSuccess cached tunnel build success ratio
     *  @since 0.9.72
     */
    private void lockedRebuildTiers(Set<PeerProfile> candidates, double buildSuccess) {
        // Fetched once for the whole rebuild; lockedPlaceProfile() would otherwise
        // re-walk the network database for every candidate.
        int known = _context.netDb().getKnownRouters();
        int active = _context.commSystem().countActivePeers();
        int minHighCap = getMinimumHighCapacityPeers(known);
        int minFast = getMinimumFastPeers(known);
        int maxFast = getMaximumFastPeers(known, active);
        int maxHighCap = getMaximumHighCapPeers(known, active);
        // The tier minimums are the starvation floors: they are what the fallback
        // passes below drive the tiers back up to, so holding a promotion back
        // while the tier is under one would only compete with those passes.
        _refresher.beginCycle(getRouterInfoRefreshAgeMs(), minFast, minHighCap);
        for (PeerProfile profile : candidates) {
            lockedPlaceProfile(profile, buildSuccess, minHighCap, maxFast, maxHighCap);
        }
    }

    /**
     *  Common gate for fast/high-cap tier fill passes: the peer must be
     *  selectable, have acceptable tunnel acceptance, no recent tunnel
     *  failures, and not be in loss probation.  Evaluation order preserves
     *  the original short-circuit chain (cheap pure checks before the
     *  netDb-backed selectability check is already handled by callers that
     *  test other cheap conditions first).
     *  <p>
     *  No side effects — safe to evaluate without holding locks.
     *
     *  @param profile the candidate profile
     *  @param buildSuccess the build success ratio in [0.0, 1.0]
     *  @param now current time in ms
     *  @return whether the peer passes the tier gates
     *  @since 0.9.71+
     */
    boolean passesTierGates(PeerProfile profile, double buildSuccess, long now) {
        // Use basic gates instead of isSelectable to avoid stale RouterInfo
        // proof-of-life filtering peers that were already vetted at tier entry.
        // The profile is already in hand, so the failure check takes it directly
        // rather than re-probing the map.
        return passesBasicGates(profile.getPeer()) &&
               !isExcessiveLifetimeFailure(profile) &&
               !isLowTunnelAcceptance(profile, buildSuccess, now) &&
               !hasRecentTunnelFailures(profile) &&
               !inLossProbation(profile, now);
    }

    /**
     *  True if the peer showed recent activity within the given cutoff: a
     *  heard-from or successful send at or after the cutoff.  Used to keep
     *  stale peers out of the fast/high-cap tiers.
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param profile the profile
     *  @param activeCutoff earliest allowed activity timestamp in ms
     *  @return whether the peer has recent activity
     *  @since 0.9.71+
     */
    static boolean hasRecentTierActivity(PeerProfile profile, long activeCutoff) {
        return profile.getLastHeardFrom() >= activeCutoff ||
               profile.getLastSendSuccessful() >= activeCutoff;
    }

    /**
     * Compute the adaptive absolute-RTT ceiling for fast-tier selection.
     * <p>
     * The ceiling is the measured fast-tier RTT boundary times a multiplier,
     * clamped to at least {@link #AUTO_RTT_FLOOR_MS} and at most
     * {@link #AUTO_RTT_CAP_MS}. Flooring keeps the bar at least as tight as the
     * existing fixed high-RTT demotion; capping prevents a uniformly-slow pool
     * from raising the ceiling without bound, so clearly-slow outliers are still
     * excluded. Pure decision — no context access, safe for unit tests.
     *
     * @param boundaryRttMs the fast-tier RTT boundary from the last reorganize
     *                      ({@link #_thresholdRTT}); its own scaling amplifies the
     *                      typical pool latency so the ceiling tracks the network
     * @return the selection ceiling in ms; never below {@link #AUTO_RTT_FLOOR_MS}
     *         and never above {@link #AUTO_RTT_CAP_MS}. A boundary at or below 0
     *         (no measurement yet) returns the floor so nothing is over-trimmed.
     * @since 0.9.71+
     */
    static long computeFastRttCeiling(double boundaryRttMs) {
        if (boundaryRttMs <= 0) return AUTO_RTT_FLOOR_MS;
        double scaled = boundaryRttMs * AUTO_RTT_MULTIPLIER;
        if (scaled <= AUTO_RTT_FLOOR_MS) return AUTO_RTT_FLOOR_MS;
        return (long) Math.min(scaled, AUTO_RTT_CAP_MS);
    }

    /**
     * Compute the adaptive absolute-RTT ceiling for fast-tier selection,
     * floored at 2× the startup baseline when build success is degraded.
     * Prevents RTT ceiling amplification during cascade failure: when the
     * entire network slows, the ceiling rises with it and peers that would
     * cause failures are allowed through.  The baseline floor keeps the
     * ceiling tight during degradation while allowing natural growth when
     * builds are healthy.
     *
     * @param boundaryRttMs the fast-tier RTT boundary from the last reorganize
     * @param buildSuccess current tunnel build success ratio [0.0, 1.0]
     * @return the selection ceiling in ms
     * @since 0.9.71+
     */
    long computeAdaptiveRttCeiling(double boundaryRttMs, double buildSuccess) {
        long ceiling = computeFastRttCeiling(boundaryRttMs);
        // During degradation (buildSuccess < 0.65), cap the ceiling so the
        // whole-network slowdown doesn't inflate it to 3000ms, which would
        // admit peers with rtt≈2500ms that cause build timeouts.
        // Cap at min(2×boundary, 1500ms) — enough headroom for legitimate
        // slow peers, tight enough to reject truly congested paths.
        if (buildSuccess < 0.65d) {
            long degradedCap = Math.min((long)(boundaryRttMs * 2), 1500);
            ceiling = Math.min(ceiling, Math.max(degradedCap, AUTO_RTT_FLOOR_MS));
        }
        return ceiling;
    }

    /**
     * True if the peer's measured tunnel-test RTT exceeds the adaptive selection
     * ceiling, indicating it should be excluded from this fast-tier pick while
     * still remaining in the tier (soft signal — the ceiling applies at selection
     * time, not tier membership). A zero/unknown RTT never trips the ceiling.
     *
     * @param profile the candidate profile
     * @param rttCeilingMs the adaptive ceiling from {@link #computeFastRttCeiling}
     * @return whether the peer is above the ceiling
     * @since 0.9.71+
     */
    static boolean aboveRttCeiling(PeerProfile profile, long rttCeilingMs) {
        float rtt = profile.getTunnelTestTimeAverage();
        return rtt > 0 && rtt >= rttCeilingMs;
    }

    /**
     *  Full eligibility gate for filling the fast/high-cap tiers from the
     *  active profile set: not ourselves, passes the tier gates, and active
     *  within the cutoff window.
     *
     *  @param profile the candidate profile
     *  @param buildSuccess the build success ratio in [0.0, 1.0]
     *  @param now current time in ms
     *  @param activeCutoff earliest allowed activity timestamp in ms
     *  @return whether the peer may be added to the tier
     *  @since 0.9.71+
     */
    private boolean isEligibleForTierFill(PeerProfile profile, double buildSuccess, long now, long activeCutoff) {
        if (profile.getPeer().equals(_us)) return false;
        if (!passesTierGates(profile, buildSuccess, now)) return false;
        // Require recent activity — don't fill fast tier with stale peers
        return hasRecentTierActivity(profile, activeCutoff);
    }

    /**
     *  Fills the fast tier to the minimum via up to three fallback passes.
     *  Must be called with the write lock held.  Returns the number added.
     */
    private int fillFastTierFallbacks(long now, Set<PeerProfile> activeProfiles, double buildSuccess) {
        int minFast = getMinimumFastPeers();
        int added = 0;
        int target = minFast;

        if (_fastPeers.size() < target) {
            // First, try from high-capacity peers (already tier-gated)
            List<PeerProfile> candidates = new ArrayList<>(_highCapacityPeers.values());
            if (candidates.isEmpty()) {
                // No high-cap peers — filter active profiles to fast-tier-capable only
                candidates = new ArrayList<>(activeProfiles.size());
                for (PeerProfile p : activeProfiles) {
                    if (isFastTierCapable(p.getPeer())) candidates.add(p);
                }
            }

            // Sort by speed descending
            candidates.sort((p1, p2) -> Double.compare(p2.getSpeedValue(), p1.getSpeedValue()));

            // First pass: low-latency peers (proven fast via tunnel builds or peer tests)
            for (int i = 0; i < candidates.size(); i++) {
                if (_fastPeers.size() >= target) break;
                PeerProfile profile = candidates.get(i);
                if (isFastTierCapable(profile.getPeer()) && profile.isLowLatency() &&
                    passesTierGates(profile, buildSuccess, now)) {
                    putFastPeer(profile.getPeer(), profile);
                    clearLossIfReadmitted(profile);
                    added++;
                }
            }

            // Second pass: active peers with current traffic
            double[] thresholds = { _thresholdSpeedValue, 0.3, 0.1, Double.MIN_VALUE };
            for (double threshold : thresholds) {
                if (_fastPeers.size() >= target) break;
                for (int i = 0; i < candidates.size(); i++) {
                    if (_fastPeers.size() >= target) break;
                    PeerProfile profile = candidates.get(i);
                    if (isFastTierCapable(profile.getPeer()) && profile.getIsActive() &&
                        profile.getSpeedValue() >= threshold &&
                        passesTierGates(profile, buildSuccess, now)) {
                        putFastPeer(profile.getPeer(), profile);
                        clearLossIfReadmitted(profile);
                        added++;
                    }
                }
            }

            // Third pass: accept any recently-active selectable peer to fill gaps
            if (_fastPeers.size() < target) {
                boolean inStartup = _context.router() != null &&
                                    _context.router().getUptime() < 15 * 60 * 1000L;
                long activeCutoff = inStartup ? now : now - TunnelPeerSelector.getActivityWindow(_context, buildSuccess);
                for (PeerProfile profile : activeProfiles) {
                    if (_fastPeers.size() >= target) break;
                    if (isFastTierCapable(profile.getPeer()) &&
                        isEligibleForTierFill(profile, buildSuccess, now, activeCutoff)) {
                        putFastPeer(profile.getPeer(), profile);
                        clearLossIfReadmitted(profile);
                        added++;
                    }
                }
            }
        }
        return added;
    }

    /**
     *  Fills the high-capacity tier to the minimum with recently-active
     *  selectable peers.  Must be called with the write lock held.  Returns
     *  the number added.
     */
    private int fillHighCapFallback(long now, Set<PeerProfile> activeProfiles, double buildSuccess) {
        int minHighCap = getMinimumHighCapacityPeers();
        int highCapAdded = 0;
        if (_highCapacityPeers.size() < minHighCap) {
            boolean inStartup = _context.router() != null &&
                                _context.router().getUptime() < 15 * 60 * 1000L;
            long activeCutoff = inStartup ? now : now - TunnelPeerSelector.getActivityWindow(_context, buildSuccess);
            for (PeerProfile profile : activeProfiles) {
                if (_highCapacityPeers.size() >= minHighCap) break;
                if (isHighBandwidthCapable(profile.getPeer()) &&
                    isEligibleForTierFill(profile, buildSuccess, now, activeCutoff)) {
                    _highCapacityPeers.put(profile.getPeer(), profile);
                    clearLossIfReadmitted(profile);
                    highCapAdded++;
                }
            }
        }
        return highCapAdded;
    }

    /**
     *  Purges peers with recent tunnel failures or in loss probation from the
     *  fast and high-capacity tiers.  Must be called with the write lock held.
     */
    private void purgeUnusableFromTiers(long now) {
        // Rebuild clears tiers, so this catches peers admitted via lockedPromoteProfileToTiers()
        // that subsequently developed failures during this reorganize window.
        if (_fastQualityCount >= MIN_FAST_QUALITY_COUNT) {
            purgeUnusableFromMap(_fastPeers, "fast", now);
        }
        if (_highCapacityPeers.size() >= MIN_HC_TIGHT_COUNT) {
            purgeUnusableFromMap(_highCapacityPeers, "high-cap", now);
        }
    }

    /**
     *  Removes peers with recent tunnel failures or in loss probation from
     *  the given tier map.  Must be called with the write lock held.
     *
     *  @param tier the tier map to purge
     *  @param tierName "fast" or "high-cap" for the log message
     *  @param now current time in ms
     */
    private void purgeUnusableFromMap(Map<Hash, PeerProfile> tier, String tierName, long now) {
        boolean isFast = (tier == _fastPeers);
        Iterator<Map.Entry<Hash, PeerProfile>> it = tier.entrySet().iterator();
        while (it.hasNext()) {
            PeerProfile profile = it.next().getValue();
            Hash peer = profile.getPeer();
            String reason = null;
            if (_context.banlist() != null && _context.banlist().isBanlisted(peer)) {
                reason = "banned";
            } else if (_context.commSystem() != null && _context.commSystem().wasUnreachable(peer)) {
                reason = "unreachable";
            } else if (isFast ? !isFastTierCapable(peer) : !isHighBandwidthCapable(peer)) {
                reason = "not tier-capable (bw tier or caps)";
            } else if (hasRecentTunnelFailures(profile) || inLossProbation(profile, now)) {
                reason = "recent tunnel failures";
            }
            if (reason != null) {
                if (_log.shouldDebug()) {
                    _log.debug("Purging peer [" + peer.toBase32().substring(0, 6) +
                               "] from " + tierName + " tier: " + reason);
                }
                it.remove();
                if (isFast && isQualityFastPeer(profile, _highCapacityPeers))
                    _fastQualityCount--;
            }
        }
    }

    /**
     *  Whether a shrunken tier needs restoration: below half its old size
     *  and the old size was substantial.  Prevents starvation when
     *  thresholds shift unfavorably.
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param currentSize current tier size
     *  @param oldSize pre-reorganize tier size
     *  @return whether the tier should be restored
     *  @since 0.9.71+
     */
    static boolean needsTierRestore(int currentSize, int oldSize) {
        return currentSize < oldSize / 2 && oldSize > 100;
    }

    /**
     *  Whether a preserved pre-reorganize tier entry may be re-added: not
     *  already present in the tier, selectable, no recent tunnel failures,
     *  not in loss probation (fast tier) or below the loss demotion
     *  threshold (high-cap tier), and acceptable tunnel acceptance.
     *  Evaluation order matches the original restore loops.
     *
     *  @param peer the peer hash
     *  @param profile the preserved profile
     *  @param tier the tier map being restored into
     *  @param buildSuccess the build success ratio in [0.0, 1.0]
     *  @param now current time in ms
     *  @param highCap whether restoring the high-cap tier (loss-demotion
     *         gate instead of loss probation)
     *  @return whether the peer may be restored
     *  @since 0.9.71+
     */
    private boolean isRestorableTierPeer(Hash peer, PeerProfile profile, Map<Hash, PeerProfile> tier,
                                         double buildSuccess, long now, boolean highCap) {
        if (tier.containsKey(peer)) return false;
        // Use basic gates instead of isSelectable to avoid stale RouterInfo
        // proof-of-life filtering peers that were already vetted at tier entry.
        if (!passesBasicGates(peer) || isExcessiveLifetimeFailure(profile)) return false;
        if (hasRecentTunnelFailures(profile)) return false;
        if (highCap ? hasHighLoss(profile, now) : inLossProbation(profile, now)) return false;
        return !isLowTunnelAcceptance(profile, buildSuccess, now);
    }

    /**
     *  Re-adds preserved pre-reorganize tier entries when the rebuild shrank
     *  the tiers too much.  Must be called with the write lock held.
     */
    private void restorePreservedPeers(long now, Set<Hash> oldFastPeers,
                                       Set<Hash> oldHighCapPeers, double buildSuccess) {
        // Prevents starvation when thresholds shift unfavorably — old entries that remain
        // selectable (no recent failures, no ban, no stale RI) are re-added.
        //
        // The profile is re-read from _notFailingPeers rather than carried in the
        // snapshot: a peer the rebuild dropped has nothing usable to restore, and
        // skipping it stops this fallback from resurrecting a profile that expired
        // or went unreachable in this very reorganize.
        int oldFastSize = oldFastPeers.size();
        if (needsTierRestore(_fastPeers.size(), oldFastSize)) {
            int restored = 0;
            for (Hash peer : oldFastPeers) {
                if (_fastPeers.size() >= oldFastSize) break;
                PeerProfile profile = lockedGetProfile(peer);
                if (profile == null) continue;
                if (isFastTierCapable(peer) &&
                    isRestorableTierPeer(peer, profile, _fastPeers, buildSuccess, now, false)) {
                    putFastPeer(peer, profile);
                    clearLossIfReadmitted(profile);
                    restored++;
                }
            }
            if (_log.shouldInfo()) {
                _log.info("Tier fallback: restored " + restored + " peers to fast tier (was " +
                          (_fastPeers.size() - restored) + ", old " + oldFastSize + ")");
            }
        }
        int oldHighCapSize = oldHighCapPeers.size();
        if (needsTierRestore(_highCapacityPeers.size(), oldHighCapSize)) {
            int restored = 0;
            for (Hash peer : oldHighCapPeers) {
                if (_highCapacityPeers.size() >= oldHighCapSize) break;
                PeerProfile profile = lockedGetProfile(peer);
                if (profile == null) continue;
                if (isHighBandwidthCapable(peer) &&
                    isRestorableTierPeer(peer, profile, _highCapacityPeers, buildSuccess, now, true)) {
                    _highCapacityPeers.put(peer, profile);
                    restored++;
                }
            }
            if (_log.shouldInfo()) {
                _log.info("Tier fallback: restored " + restored + " peers to high-cap tier (was " +
                          (_highCapacityPeers.size() - restored) + ", old " + oldHighCapSize + ")");
            }
        }
    }

    /**
     *  Counts profiles with recent activity, over the same set as
     *  {@link #countActivePeersInLastHour()} so the published gauge and the on-demand scan
     *  agree. Called during the reorganise, which already holds the write lock.
     */
    private int countActiveProfiles(long now) {
        int activeCount = 0;
        long hideBefore = now - ACTIVE_WINDOW_MS;
        for (PeerProfile profile : _notFailingPeers.values()) {
            if (isActiveInWindow(profile, hideBefore)) activeCount++;
        }
        return activeCount;
    }

    /**
     *  Counts quality peers (fast/high-cap with good acceptance plus recent activity).
     */
    private int countQualityPeers(long now, double buildSuccess) {
        int qualityCount = 0;
        long recentCutoff = now - 24 * 60 * 60 * 1000L; // 24 hours
        for (PeerProfile profile : _fastPeers.values()) {
            if (isQualityPeer(profile, recentCutoff, buildSuccess)) qualityCount++;
        }
        for (PeerProfile profile : _highCapacityPeers.values()) {
            if (!profile.isLowLatency() && isQualityPeer(profile, recentCutoff, buildSuccess)) qualityCount++;
        }
        return qualityCount;
    }

    /**
     * Remove stale profile files for peers outside the active set when over the cap.
     */
    public void purgeStaleProfileFiles() {
        int maxProfiles = ABSOLUTE_MAX_PROFILES;

        // selectAllPeers() materializes every tracked hash and purgeExcessProfiles()
        // only acts above the cap, so skip both while the known on-disk count is
        // well under it. The 10% margin covers the count being stale: it is
        // refreshed only on the store/load/delete cycle (every STORE_TIME, 15
        // min) while reorganize runs every 30-250s.
        int stored = getStoredProfileCount();
        if (stored > 0 && stored <= maxProfiles * 9 / 10) return;

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

        double[] capacities = new double[activeProfiles.size()];
        double totalCapacity = 0.0;
        int idx = 0;
        for (PeerProfile p : activeProfiles) {
            double cap = p.getCapacityValue();
            capacities[idx++] = cap;
            totalCapacity += cap;
        }
        double meanCapacity = totalCapacity / totalPeers;

        // One primitive sort, from which every order statistic below is read.
        Arrays.sort(capacities);
        int minHighCap = getMinimumHighCapacityPeers();
        double thresholdAtMedian = capacities[totalPeers / 2];
        double thresholdAtMinHighCap = (minHighCap <= totalPeers)
            ? capacities[Math.min(minHighCap - 1, totalPeers - 1)]
            : CapacityCalculator.GROWTH_FACTOR;
        double thresholdAtLowest = capacities[totalPeers - 1];
        int numExceedingMean = countExceedingMean(capacities, meanCapacity);
        return calculateCapacityThreshold(meanCapacity, numExceedingMean, totalPeers,
                                       thresholdAtMedian, thresholdAtMinHighCap, thresholdAtLowest);
    }

    /**
     *  How many of the given capacities are strictly above the mean.
     *
     *  <p>Scanned rather than derived from an index into the sorted array: the
     *  mean is a repeated value whenever capacities cluster, and
     *  {@code Arrays.binarySearch} returns only <em>some</em> index among equal
     *  entries, so an index-derived count silently includes peers whose capacity
     *  merely equals the mean and can flip the {@code >= minHighCap} branch.
     *  The scan is over a primitive array the sort above already walked.
     *
     *  @param capacities the capacities; the caller holds them sorted, but the
     *         count does not depend on their order
     *  @param meanCapacity the mean to compare against
     *  @return the number of entries greater than the mean, never negative
     *  @since 0.9.71+
     */
    static int countExceedingMean(double[] capacities, double meanCapacity) {
        int numExceedingMean = 0;
        for (double capacity : capacities) {
            if (capacity > meanCapacity) numExceedingMean++;
        }
        return numExceedingMean;
    }

    /**
     * Enforces the global profile cap by evicting the least valuable peers when over capacity.
     * <p>
     * Eviction priority (from most to least likely to be kept):
     * <ol>
     *   <li>Peers in _fastPeers (fast + reliable)</li>
     *   <li>Peers in _highCapacityPeers</li>
     *   <li>Peers with recent activity (last 48 hours)</li>
     *   <li>Peers with higher capacity value</li>
     * </ol>
     * <p>
     * This method assumes the write lock is held.
     */
    private void enforceProfileCap() {
        int maxProfiles = clampMaxProfiles(_context.getProperty(PROP_MAX_PROFILES, _defaultMaxProfiles));

        if (_notFailingPeers.size() <= maxProfiles) {
            return; // within limits
        }

        if (_log.shouldInfo()) {
            _log.info("Profiles stored in RAM (" + _notFailingPeers.size() +
                      ") exceeds hard limit of " + maxProfiles + " -> Evicting lowest quality profiles...");
        }

        // Build list of profiles eligible for eviction (not in critical tiers)
        List<PeerProfile> candidates = new ArrayList<>();
        long now = _context.clock().now();

        // Don't evict under network stress — profile churn makes recovery harder
        if (isLowBuildSuccess()) {
            if (_log.shouldWarn()) {
                // Report the value, not just that it was low. This guard sits on a composite
                // of six rate stats (expire/reject/success, client and exploratory), and counting
                // build events straight from the log implied a ratio near 0.56 - comfortably above
                // ATTACK_THRESHOLD - while this branch fired over two thousand times. Both cannot be
                // true, and the warning offered no way to tell which number was wrong. Suppressing
                // eviction is not cheap to be wrong about: the profile database stops being pruned
                // for as long as the condition holds.
                _log.warn("Low tunnel build success (" + getTunnelBuildSuccess()
                          + " < " + ATTACK_THRESHOLD
                          + ") — skipping profile eviction to preserve peer data");
            }
            return;
        }

        long activeThreshold = now - (48 * 60 * 60 * 1000L); // 48 hours

        // More lenient thresholds for firewalled routers
        boolean isFirewalledRouter = isFirewalled();
        int fastPeerLimit = isFirewalledRouter ? 3200 : 2000;
        int highCapacityLimit = isFirewalledRouter ? 4800 : 3000;

        for (PeerProfile profile : _notFailingPeers.values()) {
            if (isEvictable(profile, _fastPeers, _highCapacityPeers, activeThreshold,
                            fastPeerLimit, highCapacityLimit)) {
                candidates.add(profile);
            }
        }

        // Sort by capacity (lowest first) → evict low-capacity, inactive peers first
        candidates.sort(CAPACITY_COMPARATOR);

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
    }

    /**
     *  Clamps the configured max-profiles value into [100, ABSOLUTE_MAX_PROFILES].
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param maxProfiles the configured or default value
     *  @return the clamped value
     *  @since 0.9.71+
     */
    static int clampMaxProfiles(int maxProfiles) {
        if (maxProfiles < 100) maxProfiles = 100;
        if (maxProfiles > ABSOLUTE_MAX_PROFILES) maxProfiles = ABSOLUTE_MAX_PROFILES;
        return maxProfiles;
    }

    /**
     *  Whether a profile is eligible for eviction under the profile cap:
     *  not protected by a fast/high-cap tier slot that still has room below
     *  its limit, and no send or heard-from activity within the active window.
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param profile the profile under consideration
     *  @param fastPeers the current fast tier map
     *  @param highCapPeers the current high-cap tier map
     *  @param activeThreshold the activity window cutoff (now - 48h)
     *  @param fastPeerLimit fast tier size threshold for protection
     *  @param highCapacityLimit high-cap tier size threshold for protection
     *  @return whether the profile may be evicted
     *  @since 0.9.71+
     */
    static boolean isEvictable(PeerProfile profile, Map<Hash, PeerProfile> fastPeers,
                               Map<Hash, PeerProfile> highCapPeers, long activeThreshold,
                               int fastPeerLimit, int highCapacityLimit) {
        Hash peer = profile.getPeer();
        if ((fastPeers.containsKey(peer) && fastPeers.size() <= fastPeerLimit) ||
            (highCapPeers.containsKey(peer) && highCapPeers.size() <= highCapacityLimit)) {
            return false;
        }
        return profile.getLastSendSuccessful() < activeThreshold &&
               profile.getLastHeardFrom() < activeThreshold;
    }

    /**
     *  Whether the peer's last activity (send, heard-from, or heard-about)
     *  is older than the absent threshold — i.e. it has been absent from
     *  the netDb for at least that long.  A peer that was never contacted
     *  (all-zero timestamps) counts as stale.
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param profile the profile
     *  @param now current time in ms
     *  @param absentThreshold stale threshold in ms
     *  @return whether the peer is stale-absent
     *  @since 0.9.71+
     */
    static boolean isStaleAbsentPeer(PeerProfile profile, long now, long absentThreshold) {
        long lastActivity = Math.max(profile.getLastSendSuccessful(),
                              Math.max(profile.getLastHeardFrom(), profile.getLastHeardAbout()));
        return now - lastActivity > absentThreshold;
    }

    /**
     *  Evict profiles for peers no longer in the network database.
     *  Keeps profile files on disk — they'll be reloaded if the peer reappears.
     *  Acquires the write lock.
     */
    void evictProfilesNotInNetdb() {
        if (isLowBuildSuccess()) return;
        if (!getWriteLock()) return;
        try {
            NetworkDatabaseFacade netDb = _context.netDb();
            long now = _context.clock().now();
            long netdbAbsentThreshold = 60 * 60 * 1000L;
            List<Hash> toRemove = new ArrayList<>(128);
            for (PeerProfile profile : _notFailingPeers.values()) {
                Hash peer = profile.getPeer();
                if (_fastPeers.containsKey(peer) || _highCapacityPeers.containsKey(peer))
                    continue;
                RouterInfo info = netDb.lookupRouterInfoLocally(peer);
                if (info != null) continue;
                if (isStaleAbsentPeer(profile, now, netdbAbsentThreshold)) {
                    profile.shrinkProfile();
                    if (profile.getIsExpandedDB())
                        profile.shrinkDBProfile();
                    toRemove.add(peer);
                }
            }
            if (toRemove.isEmpty()) return;
            for (Hash peer : toRemove) {
                PeerProfile p = _notFailingPeers.remove(peer);
                if (p != null) {
                    _notFailingPeersList.remove(peer);
                    _strictCapacityOrder.remove(p);
                }
            }
            if (_log.shouldInfo())
                _log.info("Evicted " + toRemove.size() + " stale profiles (not in netdb) from RAM");
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

    private double calculateSpeedThreshold(Set<PeerProfile> reordered, long now, double capacityThreshold) {
        PeerProfile boundary = selectSpeedBoundary(reordered, now, capacityThreshold);
        if (boundary == null) return 0;

        // Record the boundary peer's measured latency for display and diagnostics
        _thresholdRTT = boundary.getTunnelTestTimeAverage();
        // Capture the baseline RTT on the first reorganize pass.  This is used
        // to floor the adaptive ceiling when the network is degraded, preventing
        // RTT ceiling amplification where the ceiling rises with the network
        // and peers that would cause failures are allowed through.
        if (_baselineRTT <= 0 && _thresholdRTT > 0) {
            _baselineRTT = _thresholdRTT;
        }
        return boundary.getSpeedValue();
    }

    /**
     *  Rank boundary for the speed threshold: the top 30% of qualifying peers,
     *  capped at 50.  0-based, so rank 0 is the fastest.
     *  <p>
     *  The qualifying count drives the rank, never the profile count: the profile
     *  count is an order of magnitude larger and would pin the rank at the cap,
     *  asking for a peer that does not exist.
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param qualifyingCount number of peers that passed the capacity and activity gates
     *  @return the rank to read the boundary peer from, or 0 for an empty set
     *  @since 0.9.71+
     */
    static int speedThresholdCutoff(int qualifyingCount) {
        if (qualifyingCount <= 0) return 0;
        return Math.min((int) (qualifyingCount * 0.3), 50);
    }

    /**
     *  The profile whose speed becomes the speed threshold: the one at rank
     *  {@link #speedThresholdCutoff(int)} of the qualifying set, ranked fastest
     *  first.  Only that one profile is ever read, and the rank is capped at 50,
     *  so a bounded top-K selection replaces a full sort of every profile.
     *
     *  @param profiles candidates, not necessarily sorted
     *  @param now current time in ms, for the activity test
     *  @param capacityThreshold minimum capacity to qualify
     *  @return the boundary profile, or null if none qualifies
     *  @since 0.9.71+
     */
    static PeerProfile selectSpeedBoundary(Collection<PeerProfile> profiles, long now,
                                           double capacityThreshold) {
        int qualifying = countQualifying(profiles, now, capacityThreshold);
        if (qualifying == 0) return null;
        return selectNthFastest(profiles, now, capacityThreshold, speedThresholdCutoff(qualifying) + 1);
    }

    /**
     *  Whether a profile qualifies for the speed-threshold ranking: enough
     *  capacity to count and recently active.  One definition, shared by the
     *  qualifying count and the bounded selection so the two passes cannot
     *  disagree about who is in the set.
     *
     *  @param profile the profile to test
     *  @param now current time in ms, for the activity test
     *  @param capacityThreshold minimum capacity to qualify
     *  @return true if the profile takes part in the speed ranking
     *  @since 0.9.71+
     */
    private static boolean qualifiesForSpeedRank(PeerProfile profile, long now, double capacityThreshold) {
        return profile.getCapacityValue() >= capacityThreshold && profile.getIsActive(now);
    }

    /**
     *  How many of the given profiles qualify for the speed-threshold ranking.
     *  The rank read from the ranked set is a fraction of this count, not of the
     *  profile count, so it has to be known before the boundary is selected.
     *
     *  @param profiles candidates, not necessarily sorted
     *  @param now current time in ms, for the activity test
     *  @param capacityThreshold minimum capacity to qualify
     *  @return the qualifying count, never negative
     *  @since 0.9.71+
     */
    static int countQualifying(Collection<PeerProfile> profiles, long now, double capacityThreshold) {
        int qualifying = 0;
        for (PeerProfile profile : profiles) {
            if (qualifiesForSpeedRank(profile, now, capacityThreshold)) qualifying++;
        }
        return qualifying;
    }

    /**
     *  Select the {@code k}-th fastest qualifying profile (1-based) without
     *  sorting the whole candidate set.
     *
     *  <p>Holds the {@code k} fastest profiles seen so far in an unsorted bounded
     *  array plus the index of the slowest: a slower profile is discarded in
     *  O(1), one that displaces the slowest costs a {@code k}-element rescan.
     *  Worst case O(n·k), and {@code k} is at most 51 for the only caller.
     *
     *  <p>Ties in speed may resolve to a different peer of equal speed than a
     *  full sort would have picked; only the boundary value is read.
     *
     *  @param profiles candidates, not necessarily sorted
     *  @param now current time in ms, for the activity test
     *  @param capacityThreshold minimum capacity to qualify
     *  @param k how many of the fastest to keep, 1-based
     *  @return the k-th fastest qualifying profile, or null if fewer than k qualify
     *  @since 0.9.71+
     */
    static PeerProfile selectNthFastest(Collection<PeerProfile> profiles, long now,
                                        double capacityThreshold, int k) {
        if (k <= 0) return null;
        PeerProfile[] top = new PeerProfile[k];
        double[] speeds = new double[k];
        int size = 0;
        // Index of the slowest kept profile. Rescanning for it keeps the loop a
        // straight scan, which is cheaper than a sift at k <= 51.
        int slowest = -1;
        for (PeerProfile profile : profiles) {
            if (!qualifiesForSpeedRank(profile, now, capacityThreshold)) continue;
            double speed = profile.getSpeedValue();
            if (size < k) {
                top[size] = profile;
                speeds[size] = speed;
                if (slowest < 0 || speed < speeds[slowest]) slowest = size;
                size++;
                continue;
            }
            if (speed <= speeds[slowest]) continue;
            top[slowest] = profile;
            speeds[slowest] = speed;
            int next = 0;
            for (int i = 1; i < k; i++) {
                if (speeds[i] < speeds[next]) next = i;
            }
            slowest = next;
        }
        // The k-th fastest is the slowest of the k kept.
        return size < k ? null : top[slowest];
    }

    /**
     *  Fetch a peer's local RouterInfo without re-running the network database's
     *  {@code validate()}.
     *
     *  <p>Every caller here reads raw RouterInfo content (tier, capabilities,
     *  addresses) and applies its own gate, so nothing depends on validation
     *  rejecting an entry that is present — it already ran when the entry was
     *  stored.  Callers must not use this to evict: the validating lookup's
     *  failure path calls {@code fail(key)}, which is the netDb's job.
     *
     *  <p>Falls back to {@code lookupRouterInfoLocally} on a miss, needed only
     *  for {@code DummyNetworkDatabaseFacade}: it serves
     *  {@code lookupRouterInfoLocally} from its own map and returns null from
     *  {@code lookupLocallyWithoutValidation}.
     *
     *  @param peer the peer hash
     *  @return the local RouterInfo, or null if absent or not a RouterInfo
     *  @since 0.9.71+
     */
    private RouterInfo lookupRouterInfoUnvalidated(Hash peer) {
        NetworkDatabaseFacade netDb = _context.netDb();
        if (netDb == null) return null;
        DatabaseEntry ds = netDb.lookupLocallyWithoutValidation(peer);
        if (ds != null) {
            return ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO ? (RouterInfo) ds : null;
        }
        return netDb.lookupRouterInfoLocally(peer);
    }

    /**
     *  Check if a peer should be excluded from profiling.
     *  Excludes low bandwidth tiers (K, L, M, Unknown) and G cap (no tunnels).
     *
     *  @return true if the peer should not be profiled
     *  @since 0.9.70+
     */
    boolean isExcludedFromProfiling(Hash peer) {
        RouterInfo peerInfo = lookupRouterInfoUnvalidated(peer);
        if (peerInfo == null) return true;
        String caps = peerInfo.getCapabilities();
        return caps.indexOf(Router.CAPABILITY_NO_TUNNELS) >= 0;
    }

    /**
     *  Check if a peer is in a low bandwidth tier (K, L, M, or Unknown).
     *
     *  @return true if the peer should be excluded from profiling
     *  @since 0.9.70+
     */
    boolean isLowBandwidthTier(Hash peer) {
        RouterInfo peerInfo = lookupRouterInfoUnvalidated(peer);
        if (peerInfo == null) return true; // no RouterInfo, assume low bandwidth
        String tier = peerInfo.getBandwidthTier();
        return isLowBandwidthTierName(tier);
    }

    /**
     *  Whether a bandwidth tier name is one of the tiers excluded from
     *  profiling.  Pure decision — no context access, safe for unit tests.
     *
     *  @param tier the advertised bandwidth tier
     *  @return true for K, L, M, or Unknown
     *  @since 0.9.71+
     */
    static boolean isLowBandwidthTierName(String tier) {
        return "K".equals(tier) || "L".equals(tier) || "M".equals(tier) || "Unknown".equals(tier);
    }

    /**
     *  Check if a peer has a congestion capability cap (D or E).
     *
     *  @return true if the peer is moderately or severely congested
     *  @since 0.9.70+
     */
    private boolean isCongestedPeer(Hash peer) {
        RouterInfo peerInfo = lookupRouterInfoUnvalidated(peer);
        if (peerInfo == null) return false;
        String caps = peerInfo.getCapabilities();
        return caps.indexOf(Router.CAPABILITY_CONGESTION_MODERATE) >= 0 ||
               caps.indexOf(Router.CAPABILITY_CONGESTION_SEVERE) >= 0;
    }

    /**
     *  Whether the peer qualifies for the high-capacity tier based on
     *  advertised bandwidth tier and capabilities.  Requires X/P/O bandwidth
     *  tier and no D (congestion), E (severe congestion), G (no tunnels),
     *  or U (firewalled/unreachable) capability flags.
     *
     *  <p>Bandwidth tier is a fact about the peer rather than an observation
     *  that can erode, so these peers are eligible for fast-tier fast-track
     *  regardless of measured throughput.
     *
     *  @param peer the peer to check
     *  @return true if X/P/O tier and no D/E/G/U caps
     *  @since 0.9.71+
     */
    private boolean isHighBandwidthCapable(Hash peer) {
        RouterInfo peerInfo = lookupRouterInfoUnvalidated(peer);
        if (peerInfo == null) return false;
        return qualifiesHighBandwidthTier(peerInfo.getBandwidthTier(), peerInfo.getCapabilities());
    }

    /**
     *  Whether a bandwidth tier and capability string together qualify the peer
     *  for the high-capacity / fast tiers: X/P/O tier and none of the D (moderate
     *  congestion), E (severe congestion), G (no tunnels) or U (unreachable)
     *  capability flags.
     *  <p>
     *  Pure decision — no context access, so a (tier, caps) pair already in hand
     *  can be tested without a router.
     *
     *  @param tier the advertised bandwidth tier
     *  @param caps the peer's capability string
     *  @return true if X/P/O tier and no D/E/G/U caps
     *  @since 0.9.71+
     */
    static boolean qualifiesHighBandwidthTier(String tier, String caps) {
        if (!"X".equals(tier) && !"P".equals(tier) && !"O".equals(tier)) return false;
        if (caps == null) return true;
        return caps.indexOf(Router.CAPABILITY_CONGESTION_MODERATE) < 0 &&
               caps.indexOf(Router.CAPABILITY_CONGESTION_SEVERE) < 0 &&
               caps.indexOf(Router.CAPABILITY_NO_TUNNELS) < 0 &&
               caps.indexOf(Router.CAPABILITY_UNREACHABLE) < 0;
    }

    /**
     * Whether a bandwidth tier is one of the high-capacity tiers (O, P, X).
     * <p>
     * Pure decision — no context access, safe for unit tests.
     *
     * @param tier the advertised bandwidth tier, already stripped of HTML
     * @return true for O, P, or X
     * @since 0.9.71+
     */
    static boolean isHighBandwidthTierName(String tier) {
        return "O".equals(tier) || "P".equals(tier) || "X".equals(tier);
    }

    /**
     * Whether the peer qualifies for the fast tier based on advertised
     * bandwidth tier and capabilities.  Currently identical to
     * {@link #isHighBandwidthCapable(Hash)} — both tiers reject X/P/O
     * with D/E/G/U caps.  Kept separate so fast tier can diverge
     * (e.g. stricter RTT gate) in the future.
     *
     * @param peer the peer to check
     * @return true if X/P/O tier and no D/E/G/U caps
     * @since 0.9.71+
     */
    private boolean isFastTierCapable(Hash peer) {
        return isHighBandwidthCapable(peer);
    }

    /**
     *  Get the high-capacity RTT ceiling for display and diagnostics.
     *  High-cap has no RTT gate — acceptance ratio and loss gates
     *  handle quality.  Returns 0 (no ceiling).
     *
     *  @return 0 (high-cap has no RTT ceiling)
     *  @since 0.9.71+
     */
    public double getHighCapRTTThreshold() {return 0;}

    private PeerProfile lockedGetProfile(Hash peer) {
        return _notFailingPeers.get(peer);
    }

    /**
     *  Minimum candidates to gate-check for a single-peer selection.  The sample
     *  is the per-attempt lottery size, not the whole reachable set: the scan
     *  start is rotated (see {@link #lockedSelectPeers}) so selections are
     *  spread across the tier instead of re-examining its first few entries.
     *
     *  <p>This floor, not the {@code howMany * 20} term, is what governs
     *  single-hop selection: every first-hop and last-hop call passes
     *  {@code howMany = 1}, giving {@code max(20, MIN)}.  The floor therefore
     *  sets how many candidates the caller's first-hop quality loop has to
     *  reject from before it relaxes its transport-session requirement, and
     *  that loop is what stands between a build and a gateway that cannot
     *  receive it.  A thin sample makes the loop run dry and descend rather
     *  than reject.
     *
     *  <p>Raised from 64 to {@link #DEFAULT_MIN_CANDIDATE_SAMPLE} (256).  Note
     *  this is 12x the {@code howMany * 20} term the formula intends, so the
     *  constant is the operative value for hop selection and is treated as a
     *  tunable rather than a detail.  Configurable via
     *  {@code profileOrganizer.minCandidateSample} so it can be raised to 512
     *  without a rebuild if 256 proves insufficient.
     *
     *  @since 0.9.71+
     */
    /** Default floor for candidates examined per selection. @since 0.9.71+ */
    static final int DEFAULT_MIN_CANDIDATE_SAMPLE = 256;
    /** Upper bound, so a typo cannot turn selection into a full tier scan. @since 0.9.71+ */
    static final int MAX_CANDIDATE_SAMPLE = 1024;
    /** Property for the floor; see {@link #getMinCandidateSample}. */
    public static final String PROP_MIN_CANDIDATE_SAMPLE = "profileOrganizer.minCandidateSample";
    private static volatile int _minCandidateSample = DEFAULT_MIN_CANDIDATE_SAMPLE;

    /**
     *  Read the candidate-sample floor, honouring the configured override.
     *  Clamped to {@code [16, }{@link #MAX_CANDIDATE_SAMPLE}{@code ]} so a bad
     *  value degrades to a sane range rather than scanning the whole tier or
     *  collapsing to a single candidate.
     *
     *  @param ctx the router context
     *  @return the floor to use for {@code maxCandidateSample}
     *  @since 0.9.71+
     */
    public static int getMinCandidateSample(RouterContext ctx) {
        if (ctx == null) {return _minCandidateSample;}
        return Math.max(16, Math.min(MAX_CANDIDATE_SAMPLE,
                                    ctx.getProperty(PROP_MIN_CANDIDATE_SAMPLE, _minCandidateSample)));
    }

    /**
     *  Override the candidate-sample floor at runtime.
     *  @param val the new floor, clamped to {@code [16, }{@link #MAX_CANDIDATE_SAMPLE}{@code ]}
     *  @since 0.9.71+
     */
    public static void setMinCandidateSample(int val) {
        _minCandidateSample = Math.max(16, Math.min(MAX_CANDIDATE_SAMPLE, val));
    }

    /**
     *  Minimum delay between "tier returned no candidates" warnings.  A starved
     *  tier is the signal that one gate is rejecting everything, so the warning
     *  carries the per-gate counts; it must not flood the log while starvation
     *  persists across build passes.
     *
     *  @since 0.9.71+
     */
    private static final long STARVE_WARN_INTERVAL_MS = 60 * 1000;

    /**
     *  Cap on how many tier peers to gate-check when selecting {@code howMany}
     *  tunnels.  Scanning all ~670 fast peers per selection was a CPU hot path;
     *  a 20× sample (min 20) cut work ~10× on large tiers but, because HashMap
     *  iteration order is stable, every selection examined the same prefix of
     *  the tier — the rest of it stayed unreachable while that prefix kept
     *  passing, so tier size never became choice.  With the scan start rotated,
     *  the sample is the size of one attempt's lottery: 64 gives
     *  lockedPickLowestPriority a real low-latency pick while remaining an
     *  order of magnitude cheaper than a full tier scan.  A 10× sample was tried
     *  before the original 20× and hurt build success, so the 20× multiplier for
     *  larger requests is kept.
     *
     *  @param howMany tunnels requested (may be 0 or negative)
     *  @param peerCount size of the tier map (may be 0)
     *  @return maximum candidates to examine, never negative
     *  @since 0.9.71+
     */
    static int maxCandidateSample(int howMany, int peerCount) {
        return maxCandidateSample(howMany, peerCount, _minCandidateSample);
    }

    /**
     *  Candidate-sample cap for a configurable floor.  Split out from
     *  {@link #maxCandidateSample(int, int)} so the arithmetic is testable
     *  without a RouterContext.
     *
     *  @param howMany peers the caller wants
     *  @param peerCount size of the tier being sampled
     *  @param floor minimum candidates to examine regardless of {@code howMany}
     *  @return maximum candidates to examine, never negative
     *  @since 0.9.71+
     */
    static int maxCandidateSample(int howMany, int peerCount, int floor) {
        if (peerCount <= 0)
            return 0;
        int need = Math.max(howMany, 1) * 20;
        return Math.min(peerCount, Math.max(need, floor));
    }

    private void lockedSelectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude,
                                    Set<Hash> matches, int mask, MaskedIPSet ipSet, double buildSuccess,
                                    long rttCeiling) {
        lockedSelectPeers(peers, howMany, toExclude, matches, null, null, mask, ipSet, buildSuccess, rttCeiling);
    }

    /**
     *  Collects up to {@link #maxCandidateSample} candidates from a tier and hands them to
     *  {@link #lockedPickLowestPriority}.
     *
     *  <p>The scan start is a random offset into the tier.  Tier maps are plain
     *  HashMaps, so iteration order is stable across calls; without the offset
     *  every hop of every pool examined the same prefix, which both starved the
     *  rest of the tier and piled "too many tunnels" onto the same few peers.
     *  Peers in the tier were already vetted by isSelectable at tier-entry time,
     *  so only fast-changing gates are re-checked here (banlist, ghosts,
     *  first-hop cooldown, lifetime failures) rather than full proof-of-life,
     *  which would reject too many peers on stale RouterInfo.
     *
     *  @param peers tier to scan; must be a field map so the starved warning can name it
     *  @param howMany peers requested
     *  @param toExclude live exclusion set, mutated with peers rejected by a hard gate
     *  @param matches output set, already holding any previously selected peers
     *  @param randomKey sub-tier slice key, or null to skip slicing
     *  @param subTierMode sub-tier mask, or null to skip slicing
     *  @param mask IP /n diversity restriction, 0 to disable
     *  @param ipSet mutable subnet set, null when mask is 0
     *  @param buildSuccess build success ratio fetched once by the caller
     *  @param rttCeiling soft ceiling; peers above it are skipped, never excluded
     */
    private void lockedSelectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude,
                                    Set<Hash> matches, SessionKey randomKey, Slice subTierMode,
                                    int mask, MaskedIPSet ipSet, double buildSuccess, long rttCeiling) {
        int peerCount = peers.size();
        if (peerCount == 0)
            return;
        int maxCandidates = maxCandidateSample(howMany, peerCount, getMinCandidateSample(_context));
        long k0 = 0;
        long k1 = 0;
        if (subTierMode != null) {
            byte[] rk = randomKey.getData();
            k0 = DataHelper.fromLong8(rk, 0);
            k1 = DataHelper.fromLong8(rk, 8);
        }

        // One draw per scan. This is a scan-position offset, not a security-relevant
        // value, so it needs no cryptographic generator.
        int start = peerCount > maxCandidates ? ThreadLocalRandom.current().nextInt(peerCount) : 0;
        List<Map.Entry<Hash, PeerProfile>> candidates =
                new ArrayList<Map.Entry<Hash, PeerProfile>>(Math.min(maxCandidates, peerCount));
        int examined = 0;
        int skipExcluded = 0;
        int skipPicked = 0;
        int skipGated = 0;
        int skipSliced = 0;
        int skipIp = 0;
        int skipRtt = 0;

        Iterator<Map.Entry<Hash, PeerProfile>> it = peers.entrySet().iterator();
        for (int i = 0; i < start && it.hasNext(); i++)
            it.next();
        // Examine at most peerCount entries so the window wraps once and covers
        // the whole tier exactly once, whichever entry it started from.
        while (examined < peerCount) {
            if (candidates.size() >= maxCandidates)
                break;
            if (!it.hasNext())
                it = peers.entrySet().iterator();
            if (!it.hasNext())
                break;
            Map.Entry<Hash, PeerProfile> entry = it.next();
            examined++;
            Hash peer = entry.getKey();
            if (toExclude != null && toExclude.contains(peer)) {
                skipExcluded++;
                continue;
            }
            // Self-exclusion is not repeated here: passesBasicGates runs on
            // every candidate a few lines below and rejects us there, so a
            // local check would only double-count us in skipPicked and make
            // the tier-starvation warning name the wrong gate.
            if (matches.contains(peer)) {
                skipPicked++;
                continue;
            }
            if (subTierMode != null && (getSubTier(peer, k0, k1) & subTierMode.mask) != subTierMode.val) {
                skipSliced++;
                continue;
            }
            if (!passesBasicGates(peer)) {
                if (toExclude != null) toExclude.add(peer);
                skipGated++;
                continue;
            }
            // entry.getValue() is the profile; the Hash form re-probes the map for it.
            if (isExcessiveLifetimeFailure(entry.getValue())) {
                if (toExclude != null) toExclude.add(peer);
                skipGated++;
                continue;
            }
            if (aboveRttCeiling(entry.getValue(), rttCeiling)) {
                // Don't add to toExclude — RTT is a soft signal, not a hard gate.
                // Filling the Excluder with RTT-only skips evicts more useful
                // entries (too-many-tunnels, etc.).
                skipRtt++;
                continue;
            }
            // Subnet diversity runs last: notRestricted() marks this peer's subnet
            // (the mask's prefix length, e.g. /16) as used in ipSet, so running it
            // before the RTT gate would consume that subnet for a peer then rejected.
            if (mask > 0 && !notRestricted(peer, ipSet, mask)) {
                skipIp++;
                continue;
            }
            candidates.add(entry);
        }

        if (candidates.isEmpty() && howMany > 0 && examined > 0 && skipExcluded + skipGated + skipIp + skipRtt > 0) {
            warnTierStarved(peers, examined, skipExcluded, skipGated, skipSliced, skipIp, skipRtt);
        }

        // Select with random priority proportional to latency:
        // lower latency = smaller range for random score = higher chance of selection.
        // Moderately-lossy peers get their range widened so they are picked only
        // when the clean candidates run out — lossiness as one signal, not a gate.
        lockedPickLowestPriority(candidates, howMany, matches);
    }

    /**
     *  Rate-limited warning naming the gate that emptied a tier scan.  A tier
     *  reporting zero candidates is invisible upstream — the caller just falls
     *  through to the next tier — so without this a 1000-peer tier rejected by a
     *  single gate looks identical to genuine scarcity.
     *
     *  @param peers the tier that produced no candidates
     *  @param examined entries walked before giving up
     *  @param skipExcluded rejected by the live exclusion set
     *  @param skipGated rejected by a hard gate (ban, ghost, first-hop, lifetime failures)
     *  @param skipSliced rejected by the sub-tier slice
     *  @param skipIp rejected by IP diversity
     *  @param skipRtt above the adaptive RTT ceiling
     *  @since 0.9.71+
     */
    private void warnTierStarved(Map<Hash, PeerProfile> peers, int examined, int skipExcluded, int skipGated,
                                 int skipSliced, int skipIp, int skipRtt) {
        long now = System.currentTimeMillis();
        if (now - _lastStarveWarn < STARVE_WARN_INTERVAL_MS)
            return;
        _lastStarveWarn = now;
        if (_log.shouldWarn()) {
            String tier = peers == _fastPeers ? "fast"
                          : peers == _highCapacityPeers ? "highcapacity" : "tier";
            _log.warn("Peer " + tier + " tier returned no candidates after " + examined +
                      " examined (excluded=" + skipExcluded + " gated=" + skipGated +
                      " subtier=" + skipSliced + " ip=" + skipIp + " rtt=" + skipRtt + ")");
        }
    }

    /**
     *  Selects the lowest-priority candidates, penalizing moderately-lossy peers.
     *  Must be called with the read lock held.
     *
     *  <p>Both lossy thresholds are resolved once for the whole list rather than
     *  per candidate.  The lottery draw uses {@link ThreadLocalRandom} rather
     *  than {@code _context.random()}: it only breaks ties among peers that have
     *  already passed every gate, so it carries no cryptographic requirement,
     *  while {@code _context.random().nextFloat()} is a monitored read on the
     *  process-wide Fortuna instance and serialized every concurrent selection.
     */
    private void lockedPickLowestPriority(List<Map.Entry<Hash, PeerProfile>> candidates, int howMany,
                                           Set<Hash> matches) {
        // Select with random priority proportional to latency:
        // lower latency = smaller range for random score = higher chance of selection.
        // Moderately-lossy peers get their range widened so they are picked only
        // when the clean candidates run out — lossiness as one signal, not a gate.
        int sz = candidates.size();
        float[] priority = new float[sz];
        long now = _context.clock().now();
        float moderateThreshold = getModerateLossyThreshold(_context);
        float demoteThreshold = getLossyThreshold(_context);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Hash> keys = new ArrayList<>(sz);
        for (int i = 0; i < sz; i++) {
            PeerProfile profile = candidates.get(i).getValue();
            float lat = profile.getPeerTestTimeAverage();
            float prio = rnd.nextFloat() * (lat > 0 ? lat : DEFAULT_PRIORITY_LATENCY_MS);
            if (isModeratelyLossy(profile.getLossScore(now), moderateThreshold, demoteThreshold)) {
                prio *= LOSSY_SELECTION_PENALTY;
            }
            priority[i] = prio;
            keys.add(candidates.get(i).getKey());
        }
        pickLowestPriority(keys, priority, howMany, matches);
    }

    /**
     *  Choose up to {@code howMany} candidates in ascending priority order.
     *
     *  <p>This loop is the reason the peer-selection callers may size a fixed-capacity
     *  {@link net.i2p.util.ArraySet} to exactly the number of peers they asked for:
     *  <ul>
     *  <li>{@link ClientPeerSelector} builds four fallback sets as
     *      {@code new ArraySet<>(needed)} and passes {@code needed} as {@code howMany}.
     *  <li>{@link ExploratoryPeerSelector} builds {@code new ArraySet<>(1)} and asks for one peer.
     *  </ul>
     *  An {@link net.i2p.util.ArraySet} throws {@code SetFullException} once written past its
     *  capacity, so if this loop ever wrote more than {@code howMany} entries those callers
     *  would throw at runtime rather than degrade. The bound {@code s < howMany && s < sz} is
     *  therefore load-bearing, and it is pinned by
     *  {@code PeerSelectionBoundTest.picksNeverExceedHowMany} and its neighbours.
     *
     *  <p>Distinctness falls out of the swap rather than from a membership test: the chosen
     *  entry is moved to index {@code s} and the next inner scan starts at {@code s + 1}, so an
     *  entry already picked can never be revisited. {@code candidates} and {@code priority} are
     *  reordered in place to achieve that, which is why both are taken as mutable.
     *
     * @param candidates peer hashes, reordered in place
     * @param priority per-candidate score, parallel to {@code candidates}, reordered in place
     * @param howMany maximum entries to choose; values below one choose nothing
     * @param matches receives the chosen hashes
     * @return how many entries were actually added to {@code matches}
     * @since 0.9.71+
     */
    public static int pickLowestPriority(List<Hash> candidates, float[] priority, int howMany, Set<Hash> matches) {
        int sz = candidates.size();
        int picked = 0;
        for (int s = 0; s < howMany && s < sz; s++) {
            int best = s;
            for (int i = s + 1; i < sz; i++) {
                if (priority[i] < priority[best]) { best = i; }
            }
            if (matches.add(candidates.get(best))) { picked++; }
            float tmpP = priority[s]; priority[s] = priority[best]; priority[best] = tmpP;
            Hash tmpH = candidates.get(s); candidates.set(s, candidates.get(best)); candidates.set(best, tmpH);
        }
        return picked;
    }

/**
     *  Scan up to {@link #maxCandidateSample} established peers for the active
     *  tier, in {@link RandomIterator} order so the peers examined differ from
     *  call to call.  Capped for the same reason as {@link #lockedSelectPeers}:
     *  one attempt's worth of lottery, not a walk of the whole list.
     */
    private void lockedSelectActive(List<Hash> connected, int howMany, Set<Hash> toExclude,
                                    Set<Hash> matches, int mask, MaskedIPSet ipSet, double buildSuccess) {
        int peerCount = connected.size();
        int maxCandidates = maxCandidateSample(howMany, peerCount, getMinCandidateSample(_context));
        if (maxCandidates <= 0) return;
        int examined = 0;
        for (Iterator<Hash> iter = new RandomIterator<>(connected);
             matches.size() < howMany && examined < maxCandidates && iter.hasNext(); ) {
            Hash peer = iter.next();
            examined++;
            if (toExclude != null && toExclude.contains(peer)) continue;
            if (matches.contains(peer)) continue;
            // Self-exclusion is handled by passesBasicGates, reached via
            // isSelectable below. No local check, so the skip is not counted
            // twice.
            boolean ok = isSelectable(peer, buildSuccess);
            if (ok) {
                ok = mask <= 0 || notRestricted(peer, ipSet, mask);
            } else if (toExclude != null) {
                toExclude.add(peer);
            }

            if (ok) matches.add(peer);
        }
    }

    /**
     *  Subnet-diversity gate: reject the peer if any of its masked addresses,
     *  ports, or family option is already represented in {@code ipSet}, and
     *  otherwise claim them.  Claimed in one pass rather than through a
     *  throwaway {@link MaskedIPSet} that was then walked twice.
     */
    private boolean notRestricted(Hash peer, MaskedIPSet ipSet, int mask) {
        return addSameIPFingerprint(ipSet, peer, mask);
    }

    /**
     *  Claim a candidate's IP/port/family fingerprint in {@code keys},
     *  reporting whether anything was already claimed.
     *
     *  <p>Load-bearing asymmetry: the peer is rejected if <em>any</em> of its keys
     *  was already present, but <em>all</em> of its keys are added either way, so
     *  a peer colliding on its last key still leaves its earlier keys claimed.
     *  Claiming conditionally would make selection order-dependent.
     */
    private boolean addSameIPFingerprint(MaskedIPSet keys, Hash peer, int mask) {
        RouterContext ctx = _context;
        RouterInfo info = lookupRouterInfoUnvalidated(peer);
        boolean sameIP = false;
        byte[] commIP = ctx.commSystem() != null ? ctx.commSystem().getIP(peer) : null;
        if (commIP != null)
            sameIP |= !keys.add(maskedIPKey(commIP, mask));
        if (info != null) {
            for (RouterAddress pa : info.getAddresses()) {
                byte[] ip = pa.getIP();
                if (ip == null) continue;
                sameIP |= !keys.add(maskedIPKey(ip, mask));
                // Routers with a common port may be run by a single entity
                // with a common configuration
                int port = pa.getPort();
                if (port > 0)
                    sameIP |= !keys.add("p" + port);
            }
            String family = info.getOption("family");
            // Prefixed so an IP cannot be spoofed into a family match
            if (family != null)
                sameIP |= !keys.add('x' + family);
        }
        return sameIP;
    }

    /**
     *  Fingerprint key for one masked IP, byte-identical to the key
     *  {@code MaskedIPSet.maskedIP} builds for the same address and mask.
     *
     *  <p>Format: a family-delimiting leading char ('.' for IPv4, ':' for IPv6,
     *  which also doubles the matched byte count), then two '0'-offset hex
     *  nibbles per matched byte.  The leading char differs by address family and
     *  port keys begin with 'p', family keys with 'x', so an IP key can never
     *  equal a port or family key.  Keeping the {@link MaskedIPSet} format means
     *  an accumulator built by MaskedIPSet's own constructor still compares
     *  equal; the floodfill path's packed-{@code long} key cannot be used because
     *  the accumulator arrives as a {@code MaskedIPSet} from callers outside this
     *  package.  {@code ProfileOrganizerSelectionCostTest} pins the format.
     *
     *  @param ip an IPv4 (4-byte) or IPv6 (16-byte) address
     *  @param mask 1-4, the number of leading bytes to match
     *  @return the fingerprint key; equal keys mean "same masked subnet"
     *  @since 0.9.71+
     */
    static String maskedIPKey(byte[] ip, int mask) {
        final char delim;
        if (ip.length == 16) {
            mask *= 2;
            delim = ':';
        } else {
            delim = '.';
        }
        final char[] buf = new char[1 + (mask * 2)];
        buf[0] = delim;
        for (int i = 0; i < mask; i++) {
            // fake hex "0123456789:;<=>?"
            byte b = ip[i];
            buf[1 + (i * 2)] = (char) ('0' + ((b >> 4) & 0x0f));
            buf[2 + (i * 2)] = (char) ('0' + (b & 0x0f));
        }
        return new String(buf);
    }

    /**
     *  Sub-tier slice index for a peer, keyed off the session key so the same
     *  key always yields the same slice.
     */
    private int getSubTier(Hash peer, long k0, long k1) {
        return ((int) SipHashInline.hash24(k0, k1, peer.getData())) & 0x03;
    }

    /**
     * Whether the given peer is eligible for selection.
     * Fetches the build success ratio once; per-peer callers should use
     * {@link #isSelectable(Hash, double)} with a value fetched once per scan.
     *
     * @return whether selectable
     */
    public boolean isSelectable(Hash peer) {
        try {
            return isSelectable(peer, getTunnelBuildSuccess());
        } finally {
            flushSelectionCounters();
        }
    }

    /**
     * Whether the given peer is eligible for selection.
     *
     * @param buildSuccess the build success ratio, fetched once per scan
     * @return whether selectable
     */
    private boolean isSelectable(Hash peer, double buildSuccess) {
        NetworkDatabaseFacade netDb = _context.netDb();
        if (netDb == null) return true;
        if (_context.router() == null) return true;
        if (!passesBasicGates(peer)) return false;
        if (hasExcessiveLifetimeFailures(peer)) return false;
        // Unvalidated: every check a validating lookup would add on top of presence is
        // done below — banlist and XG/LU in passesBasicGates, and hidden flag,
        // age with proof-of-life fallback, transport establishment, usable
        // address and tier in hasValidRouterInfo.
        RouterInfo info = lookupRouterInfoUnvalidated(peer);
        if (info != null) return hasValidRouterInfo(peer, info, buildSuccess);
        return false;
    }

    /**
     *  Returns false when the peer is us, banlisted, or recently failed as
     *  first hop.
     *
     *  <p>The self check is first because this is the chokepoint every
     *  selection path funnels through, including
     *  {@link #selectRemainderFromAllPeers} via {@link #isSelectable(Hash, double)}.
     *  The local RouterInfo is present in our own netdb, so we are a legitimate
     *  entry in every candidate pool.
     *
     *  <p>Note that we are <em>supposed</em> to be in the tunnel we build, at
     *  one end: {@code ClientPeerSelector.finalizeSelection} inserts us
     *  directly, and {@code ExploratoryPeerSelector} adds us to its exclude
     *  set. Neither goes through this method, so excluding us here costs
     *  nothing. What this guard prevents is being drawn as an <em>extra</em>
     *  hop — a mid-hop in a tunnel we are already an end of, which can only
     *  produce a build that loops back on itself. The tier drawers
     *  ({@code lockedSelectPeers}, {@code lockedSelectActive}) each excluded
     *  self locally; the remainder path used when tiers cannot supply enough
     *  peers did not, so the two protections could disagree. Checking here
     *  rather than in each selection loop means no present or future path can
     *  reintroduce the gap.
     *
     *  <p>Scope, stated precisely because it was previously overstated: this
     *  guard has not been shown to fix any observed build failure. A router
     *  appearing in an expiring-build log line is expected — it is the
     *  gateway — so that log is not evidence of self-selection. Whether we
     *  were being drawn at a mid-hop has not been established.
     *
     *  @since 0.9.71+
     */
    private boolean passesBasicGates(Hash peer) {
        // Null-safe: _us is set by PeerManager's constructor, but a null here
        // must not turn a self-exclusion into an NPE on the hot path.
        if (_us != null && _us.equals(peer)) return false;
        if (_context.banlist() != null && _context.banlist().isBanlisted(peer)) {
            // Counted here because this is the one chokepoint every selection path
            // funnels through. If tunnel.buildBanHit ever exceeds this materially,
            // a path has stopped routing through these gates. Accumulated rather
            // than recorded here, so a sweep with mostly-banned candidates costs
            // one stat call rather than thousands; drained by
            // flushSelectionCounters().
            _bannedAtSelection.incrementAndGet();
            return false;
        }
        // Ghost peers are rejected here rather than filtered after selection:
        // a candidate slot spent on a peer that only times out on builds is a
        // slot not spent on one that can complete, and a post-selection filter
        // has no replacement left to offer.  Cheap ConcurrentHashMap lookup,
        // and ghosts already never enter the fast/high-cap tiers on promotion.
        TunnelManagerFacade tmf = _context.tunnelManager();
        if (tmf != null) {
            GhostPeerManager ghostMgr = tmf.getGhostPeerManager();
            if (ghostMgr != null && ghostMgr.isGhost(peer)) return false;
        }
        // Exclude peers that recently failed as first hop during tunnel builds.
        // Without this, the 5-min cooldown in TunnelPeerSelector is bypassed —
        // peers are selected by the tier system, fail as first hop, cooldown
        // expires, and they're selected again immediately.
        if (TunnelPeerSelector.isFirstHopFailing(_context, peer)) return false;
        return true;
    }

    /**
     *  Banned-candidate rejections seen since the last
     *  {@link #flushSelectionCounters()}.
     *  @since 0.9.71+
     */
    private final AtomicInteger _bannedAtSelection = new AtomicInteger();

    /**
     *  Record the banned-candidate rejections accumulated since the last flush
     *  as a single {@code tunnel.peerBannedAtSelection} sample.  The count stays
     *  exact: the atomic get-and-reset loses nothing, it only attributes a
     *  rejection to whichever scan drains it first.  A count of zero writes
     *  nothing.
     *
     *  @since 0.9.71+
     */
    private void flushSelectionCounters() {
        int banned = _bannedAtSelection.getAndSet(0);
        if (banned > 0) {
            _context.statManager().addRateData("tunnel.peerBannedAtSelection", banned);
        }
    }

    /**
     *  Returns true when the peer has excessive cumulative tunnel failures.
     *  Uses a dual gate: hard exclusion at {@link #MAX_LIFETIME_TUNNEL_FAILURES}
     *  failures or when the lifetime failure ratio exceeds
     *  {@link #MAX_LIFETIME_FAILURE_RATIO}.  Between
     *  {@link #SOFT_FAILURE_PENALTY_THRESHOLD} and the hard cap, peers are not
     *  excluded but receive a selection priority penalty (handled by the caller
     *  via {@link #getExcessiveFailurePenalty(Hash)}).
     *  <p>
     *  The blame system (tunnelFailed()) only increments statistics — it never
     *  bans.  Without this check, peers with 200+ failures keep getting selected
     *  because the ghost peer system clears on any success and the first-hop
     *  cooldown is only 5 minutes.
     *
     *  @param peer the peer hash
     *  @return true when the peer should be excluded from selection
     */
    private boolean hasExcessiveLifetimeFailures(Hash peer) {
        return isExcessiveLifetimeFailure(getProfileNonblocking(peer));
    }

    /**
     *  Whether the given profile carries excessive cumulative tunnel failures.
     *  Same dual gate as {@link #hasExcessiveLifetimeFailures(Hash)}, for callers
     *  that already hold the profile and so avoid a re-probe per candidate.  A
     *  null profile or null history is not a failure.
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param prof the profile, may be null
     *  @return true when the peer should be excluded from selection
     *  @since 0.9.71+
     */
    static boolean isExcessiveLifetimeFailure(PeerProfile prof) {
        if (prof == null) return false;
        TunnelHistory th = prof.getTunnelHistory();
        if (th == null) return false;
        long lifetimeFailed = th.getLifetimeFailed();
        if (lifetimeFailed > MAX_LIFETIME_TUNNEL_FAILURES) return true;
        long totalRequests = th.getLifetimeAgreedTo() + lifetimeFailed;
        if (totalRequests > 0) {
            double ratio = (double) lifetimeFailed / totalRequests;
            if (ratio > MAX_LIFETIME_FAILURE_RATIO) return true;
        }
        return false;
    }

    /**
     *  Selection penalty multiplier for peers between the soft and hard failure
     *  thresholds.  Peers with {@link #SOFT_FAILURE_PENALTY_THRESHOLD} to
     *  {@link #MAX_LIFETIME_TUNNEL_FAILURES} lifetime failures are not excluded
     *  but receive a penalty that lowers their selection priority.  Returns
     *  1.0 (no penalty) for peers below the soft threshold or above the hard
     *  threshold (they'd be excluded anyway).  Pure decision — no side effects.
     *
     *  @param peer the peer hash
     *  @return a penalty multiplier in (1.0, 2.0] — higher means more penalized
     *  @since 0.9.71+
     */
    static double getExcessiveFailurePenalty(Hash peer, PeerProfile prof) {
        if (prof == null) return 1.0;
        long lifetimeFailed = prof.getTunnelHistory().getLifetimeFailed();
        if (lifetimeFailed <= SOFT_FAILURE_PENALTY_THRESHOLD) return 1.0;
        if (lifetimeFailed > MAX_LIFETIME_TUNNEL_FAILURES) return 1.0;
        // Linear ramp from 1.0 (at soft threshold) to 2.0 (at hard cap)
        double frac = (double) (lifetimeFailed - SOFT_FAILURE_PENALTY_THRESHOLD) /
                      (MAX_LIFETIME_TUNNEL_FAILURES - SOFT_FAILURE_PENALTY_THRESHOLD);
        return 1.0 + frac;
    }

    /**
     *  Checks the RouterInfo for hidden flag, stale proof of life, usable
     *  transport address, bandwidth tier, and tunnel exclusion.
     */
    private boolean hasValidRouterInfo(Hash peer, RouterInfo info, double buildSuccess) {
        if (info.isHidden()) return false;
        long now = _context.clock().now();
        long maxAge = getMaxRouterInfoAgeMs();
        // RouterInfo is stale — demand proof of life to trust it.
        // This check applies regardless of startup grace period: peers
        // with stale RouterInfo and no handled requests are not trusted
        // as tunnel targets, even during startup. The startup grace only
        // relaxes the transport-established requirement below.
        if (info.getPublished() < now - maxAge &&
            !hasRecentProofOfLife(_context.profileOrganizer().getProfile(peer), now)) {
            return false;
        }
        // During normal operation, require established transport connection.
        // During startup, allow non-established peers since we're still
        // building connections, but the proof-of-life check above still
        // prevents using peers with stale RouterInfo and no history.
        if (_context.router() != null && _context.router().getUptime() > STARTUP_GRACE_PERIOD_MS &&
            !_context.commSystem().isEstablished(peer)) {
            return false;
        }
        // Peers without a reachable NTCP2 or SSU2 address cannot be used
        // for outbound builds.  Filtering just by transport style misses
        // peers whose RouterInfo has the right style but null IP, invalid
        // port, or a private/internal address — selecting them leads to
        // immediate transport failure and an 8h ban with logspam.
        if (!hasUsableTransportAddress(info)) return false;
        if (isExcludedBuildTier(info.getBandwidthTier())) return false;
        return !TunnelPeerSelector.shouldExclude(_context, info, buildSuccess);
    }

    /**
     *  Whether a RouterInfo's advertised bandwidth tier is one that cannot host
     *  a tunnel: L, M, or N.
     *  <p>
     *  The raw string is compared directly rather than through
     *  {@code DataHelper.stripHTML}, which is not a weakening: stripHTML only
     *  substitutes spaces for {@code < > " '}, so {@code stripHTML(t).equals("L")}
     *  holds exactly when {@code t} is the one-character string {@code "L"}.
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param rawTier the tier string as advertised, possibly HTML-obfuscated
     *  @return whether the tier excludes the peer from tunnel hosting
     *  @since 0.9.71+
     */
    static boolean isExcludedBuildTier(String rawTier) {
        if (rawTier == null) return false;
        return "L".equals(rawTier) || "M".equals(rawTier) || "N".equals(rawTier);
    }

    /**
     *  Maximum RouterInfo age in ms, cached alongside
     *  {@link #getTunnelBuildSuccess()} on the same
     *  {@link #BUILD_SUCCESS_CACHE_MS} cadence: it is a constant read per
     *  candidate, and 15s of staleness on a proof-of-life bar is immaterial.
     *
     *  @return the max RouterInfo age in ms
     *  @since 0.9.71+
     */
    private long getMaxRouterInfoAgeMs() {
        long now = _context.clock().now();
        if (now - _cachedMaxRouterInfoAgeTime < BUILD_SUCCESS_CACHE_MS) {
            return _cachedMaxRouterInfoAge;
        }
        long age = _context.getProperty(PROP_MAX_ROUTERINFO_AGE_HOURS, DEFAULT_MAX_ROUTERINFO_AGE_HOURS)
                   * 3600_000L;
        _cachedMaxRouterInfoAge = age;
        _cachedMaxRouterInfoAgeTime = now;
        return age;
    }

    /** Cached {@link #getMaxRouterInfoAgeMs()}, and the timestamp of the fetch. @since 0.9.71+ */
    private volatile long _cachedMaxRouterInfoAge = DEFAULT_MAX_ROUTERINFO_AGE_HOURS * 3600_000L;
    /** When {@link #_cachedMaxRouterInfoAge} was fetched. @since 0.9.71+ */
    private volatile long _cachedMaxRouterInfoAgeTime;

    /**
     *  Returns true when the RouterInfo has a reachable NTCP/NTCP2/SSU/SSU2 address.
     */
    private boolean hasUsableTransportAddress(RouterInfo info) {
        boolean hasUsableAddress = false;
        for (RouterAddress ra : info.getAddresses()) {
            String style = ra.getTransportStyle();
            if (!"NTCP".equals(style) && !"NTCP2".equals(style) &&
                !"SSU".equals(style) && !"SSU2".equals(style))
                continue;
            byte[] ip = ra.getIP();
            if (ip == null) continue;
            if (!TransportUtil.isValidPort(ra.getPort())) continue;
            hasUsableAddress = true;
            break;
        }
        return hasUsableAddress;
    }

    /**
     *  True if the peer has shown proof of life within
     *  {@link #PROOF_OF_LIFE_WINDOW_MS}: a successful send, heard-from, or
     *  heard-about within the last hour.  A null profile (never seen) is not
     *  evidence of life.  Used to trust a RouterInfo that is older than the
     *  maximum age — the RouterInfo alone is not enough, the peer must have
     *  been active recently.
     *  <p>
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param profile the peer profile, null if none
     *  @param now current time in ms
     *  @return whether the peer has recent proof of life
     *  @since 0.9.71+
     */
    static boolean hasRecentProofOfLife(PeerProfile profile, long now) {
        if (profile == null) return false;
        return profile.getLastSendSuccessful() > now - PROOF_OF_LIFE_WINDOW_MS ||
               profile.getLastHeardFrom() > now - PROOF_OF_LIFE_WINDOW_MS ||
               profile.getLastHeardAbout() > now - PROOF_OF_LIFE_WINDOW_MS;
    }

    private void lockedPlaceProfile(PeerProfile profile, double buildSuccess) {
        lockedPlaceProfile(profile, buildSuccess,
                           getMinimumHighCapacityPeers(),
                           getMaximumFastPeers(),
                           getMaximumHighCapPeers());
    }

    /**
     * Variant taking the tier thresholds, which the caller has already fetched for
     * the whole scan.
     *
     * @param profile the profile to place
     * @param buildSuccess cached tunnel build success ratio
     * @param minHighCap minimum high-capacity peers for this scan
     * @param maxFast maximum fast peers for this scan
     * @param maxHighCap maximum high-capacity peers for this scan
     * @since 0.9.71+
     */
    private void lockedPlaceProfile(PeerProfile profile, double buildSuccess,
                                    int minHighCap, int maxFast, int maxHighCap) {
        Hash peer = profile.getPeer();

        // Remove existing entries (idempotent)
        removeFastPeer(peer);
        _highCapacityPeers.remove(peer);
        _wellIntegratedPeers.remove(peer);

        // Always add/update in notFailing set
        _notFailingPeers.put(peer, profile);
        _notFailingPeersList.add(peer); // Note: O(n), but acceptable during reorg

        // Evaluate tier placement
        lockedPromoteProfileToTiers(profile, buildSuccess, minHighCap, maxFast, maxHighCap);
    }

    /**
     * Evaluate a profile for promotion to fast/high-cap tiers without touching
     * the notFailing structures.  Safe to call between reorganize cycles —
     * does not add duplicates to _notFailingPeersList.
     * Must be called with write lock held.
     */
    private void lockedPromoteProfileToTiers(PeerProfile profile, double buildSuccess) {
        lockedPromoteProfileToTiers(profile, buildSuccess,
                                    getMinimumHighCapacityPeers(),
                                    getMaximumFastPeers(),
                                    getMaximumHighCapPeers());
    }

    /**
     * Evaluate a profile for promotion to fast/high-cap tiers without touching
     * the notFailing structures.  Safe to call between reorganize cycles —
     * does not add duplicates to _notFailingPeersList.
     * Must be called with write lock held.
     *
     * <p>The three tier thresholds are passed in rather than read here. Each one
     * costs a walk of the whole network database, and this runs once per profile
     * in a scan, so reading them per profile made a single pass cost
     * profiles-times-a-database-scan. They depend only on network size and the
     * active peer count, neither of which changes during a pass, so the caller
     * fetches them once for the whole scan.
     *
* <p>A promotion whose RouterInfo is older than
     * {@link #PROP_ROUTERINFO_REFRESH_AGE_MS} and which has no proof of life is
     * held back, leaving the peer out of the tier — see
     * {@link #holdPromotionForRefresh}. No lookup is issued on its behalf, so a
     * held peer re-enters only by being republished to, or by showing life.
     * {@link #promoteToFillTiers}, which fills gaps from the incremental
     * add/remove paths rather than from the rebuild walk, applies the same
     * per-peer gates and so inherits the same hold.
     *
     * @param profile the profile to evaluate
     * @param buildSuccess cached tunnel build success ratio
     * @param minHighCap minimum high-capacity peers, fetched once per scan
     * @param maxFast maximum fast peers, fetched once per scan
     * @param maxHighCap maximum high-capacity peers, fetched once per scan
     * @since 0.9.71+
     */
    private void lockedPromoteProfileToTiers(PeerProfile profile, double buildSuccess,
                                             int minHighCap, int maxFast, int maxHighCap) {
        Hash peer = profile.getPeer();
        PeerProfile notFailingProfile = _notFailingPeers.get(peer);

        // Ghost peers are excluded from promotion: a peer that consistently fails
        // to respond to tunnel builds must not re-enter fast/high-cap tiers even
        // if its profile stats are still above threshold.  The ghost check is
        // cheap (ConcurrentHashMap lookup) and runs only during reorganize.
        TunnelManagerFacade tmf = _context.tunnelManager();
        if (tmf != null) {
            GhostPeerManager ghostMgr = tmf.getGhostPeerManager();
            if (ghostMgr != null && ghostMgr.isGhost(peer)) {
                if (_log.shouldDebug()) {
                    _log.debug("Skipping ghost peer from promotion: " + peer.toBase32().substring(0, 6));
                }
                return;
            }
        }

        boolean recentFailures = hasRecentTunnelFailures(profile);

        if (skipsPromotion(profile, peer, buildSuccess)) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping peer [" + peer.toBase32().substring(0, 6) +
                           "] from promotion: notPeerSelectable=" + !passesBasicGates(peer) +
                           " excessiveFailures=" + hasExcessiveLifetimeFailures(peer) +
                           " sameObj=" + (profile == notFailingProfile));
            }
            return;
        }
        // Passing the gate means loss probation is over (either never started
        // or readmission conditions were met) — clear the flag so the cleared
        // state is persisted.
        clearLossIfReadmitted(profile);

        // Only the peers that clear every gate above reach this line, so the
        // staleness test costs one netDb map lookup for a candidate that was
        // actually going to be promoted.
        long now = _context.clock().now();
        boolean staleRouterInfo = promotionNeedsRouterInfoRefresh(profile, now);

        double effectiveCapThreshold = Math.max(_thresholdCapacityValue, CapacityCalculator.GROWTH_FACTOR);
        double effectiveSpeedThreshold = _thresholdSpeedValue;

        // High-capacity tier
        // High-cap means proven ability to host tunnels with X/P/O bandwidth.
        // Admission requires:
        // - skipsPromotion() passed (acceptance ratio, loss, congestion)
        // - isHighBandwidthCapable: X/P/O tier, no D/E/G caps
        // - Capacity above threshold, or room in the tier
        boolean hcNeedsFilling = _highCapacityPeers.size() < minHighCap;
        boolean hcHasRoom = _highCapacityPeers.size() < maxHighCap;
        boolean hcTight = _highCapacityPeers.size() >= MIN_HC_TIGHT_COUNT;
        if (!_highCapacityPeers.containsKey(peer)) {
            boolean hasCapacity = profile.getCapacityValue() >= effectiveCapThreshold;
            boolean tierRoom = !hcTight && (hcNeedsFilling || hcHasRoom);
            boolean noRecentBlock = !(!hcTight && recentFailures);
            if (noRecentBlock && (hasCapacity || tierRoom) && isHighBandwidthCapable(peer) &&
                !holdPromotionForRefresh(peer, staleRouterInfo, _highCapacityPeers.size(),
                                         _refresher.highCapFloor())) {
                _highCapacityPeers.put(peer, profile);
            }
        }

        // Fast tier
        // Fast = high-cap + responsive, with strict bandwidth gate:
        // X/P/O only, no D/E/G/U caps.  Three admission paths:
        // 1. High-cap responsive: already high-cap, low-latency or untested.
        // 2. Quality mode: all tests passing, active, proven throughput.
        // 3. Filling mode: speed-based or low-latency bypass.
        if (!_fastPeers.containsKey(peer) && _fastPeers.size() < maxFast &&
            isFastTierCapable(peer)) {
            boolean hasProvenThroughput = profile.getPeakTunnel1mThroughputKBps() > 0;
            boolean alreadyHighCap = _highCapacityPeers.containsKey(peer);
            boolean fastQuality = _fastQualityCount >= MIN_FAST_QUALITY_COUNT;
            // The three admission paths decide whether this peer is a fast-tier
            // candidate at all; the stale-RouterInfo hold is applied to the
            // decision rather than to the branch, so a peer these paths would
            // have dropped is not counted as a deferral.
            boolean fastCandidate;
            if (!recentFailures && alreadyHighCap &&
                (profile.isLowLatency() || !profile.hasBeenTested())) {
                // High-cap responsive: peer is in high-cap (X/P/O or
                // capacity-qualified) and is low-latency or untested.
                // Tested-but-slow peers are excluded — their bandwidth
                // tier is real, but their responsiveness isn't.
                fastCandidate = true;
            } else if (fastQuality) {
                // Quality mode: all tests passing — peer test, active,
                // no recent failures, AND proven throughput (or already high-cap)
                fastCandidate = profile.isLowLatency() && profile.getIsActive() &&
                                !recentFailures && (hasProvenThroughput || alreadyHighCap);
            } else {
                // Filling mode: speed-based or low-latency bypass, but still reject recent failures
                fastCandidate = !recentFailures &&
                                ((profile.getSpeedValue() >= effectiveSpeedThreshold &&
                                  (profile.getIsActive() || hasProvenThroughput)) ||
                                 profile.isLowLatency());
            }
            if (fastCandidate &&
                !holdPromotionForRefresh(peer, staleRouterInfo, _fastPeers.size(),
                                         _refresher.fastFloor())) {
                putFastPeer(peer, profile);
            }
        }

        // Integration tier
        if (!_wellIntegratedPeers.containsKey(peer) &&
            profile.getIntegrationValue() >= _thresholdIntegrationValue) {
            _wellIntegratedPeers.put(peer, profile);
        }
    }

    /**
     *  Whether this peer's stored RouterInfo is stale enough that promoting on it
     *  risks a build failure.
     *
     *  <p>Reads the same staleness notion {@link #hasValidRouterInfo} uses — older
     *  than the threshold and no proof of life — against the shorter
     *  {@link #PROP_ROUTERINFO_REFRESH_AGE_MS}. Proof of life exempts working
     *  peers, which is what keeps the affected population small: a peer we have
     *  talked to in the last hour is judged on what it just told us, not on how
     *  old its last publication is.
     *
     *  @param profile the profile being promoted
     *  @param now current time in ms
     *  @return whether the stored RouterInfo is too old to promote on
     *  @since 0.9.72
     */
    private boolean promotionNeedsRouterInfoRefresh(PeerProfile profile, long now) {
        // No open cycle means no hold, so skip the netDb read entirely: with the
        // property disabled this costs nothing per candidate.
        if (!_refresher.isDeferring()) return false;
        if (profile == null) return false;
        RouterInfo info = lookupRouterInfoUnvalidated(profile.getPeer());
        return needsRouterInfoRefresh(info, profile, now, _refresher.refreshAgeMs());
    }

    /**
     *  Whether a RouterInfo is old enough that promoting on it risks a build
     *  failure: published before the threshold and no proof of life.
     *
     *  <p>An absent RouterInfo is not stale, it is missing, and is not this
     *  predicate's business: {@link #isHighBandwidthCapable} and
     *  {@link #isFastTierCapable} already refuse to promote a peer with no
     *  RouterInfo at all, so there is no promotion here to hold.
     *
     *  <p>Pure decision — no context access, safe for unit tests.
     *
     *  @param info the peer's stored RouterInfo, may be null
     *  @param profile the peer profile supplying proof of life, may be null
     *  @param now current time in ms
     *  @param refreshAgeMs staleness threshold in ms
     *  @return whether the RouterInfo is too stale to promote on
     *  @since 0.9.72
     */
    static boolean needsRouterInfoRefresh(RouterInfo info, PeerProfile profile, long now, long refreshAgeMs) {
        if (info == null) return false;
        if (refreshAgeMs <= 0) return false;
        if (info.getPublished() >= now - refreshAgeMs) return false;
        return !hasRecentProofOfLife(profile, now);
    }

    /**
     *  Apply the hold to one tier insert: leave the peer out of the tier, or let
     *  it in.
     *
     *  <p>The starvation guard is the reason this is not a plain veto.  A tier
     *  that is already at its configured minimum is one the fallback passes
     *  would defend anyway, so leaving a stale peer out of it costs nothing
     *  there.  Below the minimum the hold would compete with those passes for
     *  the same candidates and the tier could end up short, which is the worse
     *  of the two failures: a stale promotion risks one bad build, an empty tier
     *  sends every build back to the general pool.  When the guard opens, the
     *  promotion is counted so the team can see exactly how often that happens.
     *
     *  @param peer the peer being promoted
     *  @param staleRouterInfo whether the peer carries a stale RouterInfo
     *  @param tierSize current size of the tier being joined
     *  @param safeFloor size at or above which this tier can leave it out
     *  @return whether the promotion must be held back
     *  @since 0.9.72
     */
    private boolean holdPromotionForRefresh(Hash peer, boolean staleRouterInfo, int tierSize,
                                            int safeFloor) {
        if (!staleRouterInfo) return false;
        if (!_refresher.isDeferring()) return false;
        if (shouldDeferPromotionOnStaleRouterInfo(tierSize, safeFloor)) {
            _refresher.noteDeferral();
            return true;
        }
        if (_log.shouldDebug()) {
            _log.debug("Promoting peer [" + peer.toBase32().substring(0, 6) +
                       "] on a stale RouterInfo: tier at " + tierSize + " below floor " + safeFloor);
        }
        _refresher.noteStalePromotion();
        return false;
    }

    /**
     *  Whether a promotion may be held back on a stale RouterInfo without
     *  risking the tier it would join.
     *
     *  <p>The floor is the tier's configured minimum, which is also what
     *  {@link #fillFastTierFallbacks} and {@link #fillHighCapFallback} drive the
     *  tier back up to and what {@link #restorePreservedPeers} protects at half.
     *  At or above the minimum the tier can afford to leave the peer out; below
     *  it, it cannot.
     *
     *  <p>Pure decision — no context access, safe for unit tests.
     *
     *  @param tierSize current size of the tier the peer would join
     *  @param safeFloor size at or above which the tier can leave it out
     *  @return whether the promotion should be held back
     *  @since 0.9.72
     */
    static boolean shouldDeferPromotionOnStaleRouterInfo(int tierSize, int safeFloor) {
        return tierSize >= safeFloor;
    }

    /**
     *  RouterInfo age past which a promotion is held back, in ms.  Read once per
     *  reorganisation cycle rather than cached per candidate, because that is
     *  the only place it is consulted.
     *
     *  @return the staleness threshold in ms; &lt;= 0 disables the hold
     *  @since 0.9.72
     */
    private long getRouterInfoRefreshAgeMs() {
        return _context.getProperty(PROP_ROUTERINFO_REFRESH_AGE_MS, DEFAULT_ROUTERINFO_REFRESH_AGE_MS);
    }

    /**
     *  Publish the promotion-deferral counters for the cycle just finished.
     *
     *  <p>{@code peer.routerInfoRefreshNeeded} is a population, so it is written
     *  every cycle including zero — a flat zero line is the signal that nothing
     *  is being held back. The stale-promotion count is an event count and
     *  follows the existing convention of writing nothing when it is zero.
     *
     *  @since 0.9.72
     */
    private void recordRouterInfoRefreshStats() {
        int deferred = _refresher.deferredCount();
        _context.statManager().addRateData("peer.routerInfoRefreshNeeded", deferred);
        int stale = _refresher.promotedStaleCount();
        if (stale > 0) {
            _context.statManager().addRateData("peer.promotedStaleRouterInfo", stale);
        }
        if (_log.shouldInfo() && (deferred > 0 || stale > 0)) {
            _log.info("Stale RouterInfo hold: " + deferred + " promotions held, " + stale +
                      " promoted on a stale RouterInfo");
        }
    }

    /**
     *  Whether the peer should be skipped for tier promotion: not selectable,
     *  in a strict country, low tunnel acceptance, high-latency penalty,
     *  congested, or in loss probation.  Mirrors the eligibility gates of
     *  lockedPlaceProfile().
     *  <p>
     *  No side effects — safe to evaluate without holding locks.
     *
     *  @param profile the profile under consideration
     *  @param peer the peer hash
     *  @param buildSuccess the tunnel build success ratio in [0.0, 1.0]
     *  @return whether promotion should be skipped
     *  @since 0.9.71+
     */
     boolean skipsPromotion(PeerProfile profile, Hash peer, double buildSuccess) {
         long now = _context.clock().now();
         boolean isStrictCountry = _context.commSystem() != null && _context.commSystem().isInStrictCountry(peer);
         // Use basic gates instead of isSelectable to avoid stale RouterInfo
         // proof-of-life filtering peers that were already vetted at tier entry.
         boolean isPeerSelectable = passesBasicGates(peer) && !isExcessiveLifetimeFailure(profile);
         boolean lowTunnelAcceptance = isLowTunnelAcceptance(profile, buildSuccess, now);
         boolean congested = isCongestedPeer(peer);
         boolean highLoss = inLossProbation(profile, now);
         return !isPeerSelectable || isStrictCountry || lowTunnelAcceptance || congested || highLoss;
     }

    /**
     * After a demotion vacates slots in fast/high-cap tiers, scan the
     * capacity-ordered peer list and promote the next best candidates
     * to fill the openings.  Must be called with write lock held.
     */
    private void promoteToFillTiers() {
        // One network-database walk and one active-peer count for the whole scan.
        // Both are O(network), and the loop below runs once per profile.
        int known = _context.netDb().getKnownRouters();
        int active = _context.commSystem().countActivePeers();
        int fastTarget = getMaximumFastPeers(known, active);
        int highCapTarget = getMaximumHighCapPeers(known, active);
        int minHighCap = getMinimumHighCapacityPeers(known);
        int fastBefore = _fastPeers.size();
        int highCapBefore = _highCapacityPeers.size();

        if (fastBefore >= fastTarget && highCapBefore >= highCapTarget)
            return;

        if (_log.shouldDebug()) {
            _log.debug("promoteToFillTiers: fast=" + fastBefore + "/" + fastTarget +
                       " highCap=" + highCapBefore + "/" + highCapTarget);
        }

        // Fetch once for the whole scan; promotion checks per profile would
        // otherwise re-read router statistics for every candidate.
        double buildSuccess = getTunnelBuildSuccess();

        for (PeerProfile profile : _strictCapacityOrder) {
            if (_fastPeers.size() >= fastTarget && _highCapacityPeers.size() >= highCapTarget)
                break;

            Hash peer = profile.getPeer();
            boolean wasFast = _fastPeers.containsKey(peer);
            boolean wasHighCap = _highCapacityPeers.containsKey(peer);
            if (wasFast && wasHighCap)
                continue;

            // Use live profile from _notFailingPeers, which has current
            // capacityBonus, capacityValue, etc. — the TreeSet's copy may be stale
            PeerProfile liveProfile = _notFailingPeers.get(peer);
            if (liveProfile != null)
                lockedPromoteProfileToTiers(liveProfile, buildSuccess,
                                            minHighCap, fastTarget, highCapTarget);
            else
                lockedPromoteProfileToTiers(profile, buildSuccess,
                                            minHighCap, fastTarget, highCapTarget);

            if (_log.shouldInfo()) {
                boolean nowFast = _fastPeers.containsKey(peer);
                boolean nowHighCap = _highCapacityPeers.containsKey(peer);
                if (nowFast != wasFast || nowHighCap != wasHighCap) {
                    PeerProfile nfProfile = _notFailingPeers.get(peer);
                    _log.info("Promoted peer [" + peer.toBase32().substring(0, 6) +
                              "] fast=" + wasFast + "->" + nowFast +
                              " highCap=" + wasHighCap + "->" + nowHighCap +
                              " capBonus=" + profile.getCapacityBonus() +
                              " raw=" + profile.getCapacityBonusRaw() +
                              " sameObj=" + (profile == nfProfile));
                }
            }
        }
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers if its RouterInfo is stale
     * and it has no recent proof of life (fails isSelectable). Non-blocking — only acts
     * if the peer is currently in those tiers.
     */
    public void demoteIfStale(Hash peer) {
        if (!isSelectable(peer)) {
            if (!getWriteLock()) return;
            try {
                boolean inFast = _fastPeers.containsKey(peer);
                boolean inHighCap = _highCapacityPeers.containsKey(peer);
                if (inFast || inHighCap) {
                    if (_log.shouldInfo()) {
                        _log.info("Demoting peer [" + peer.toBase32().substring(0, 6) +
                                  "] from fast/high-cap tiers due to stale RouterInfo / no proof of life");
                    }
                    if (inFast) removeFastPeer(peer);
                    if (inHighCap) _highCapacityPeers.remove(peer);
                    promoteToFillTiers();
                }
            } finally {
                releaseWriteLock();
            }
        }
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers if its capacityBonus is -30
     * (UI shows ✖ for high latency). Non-blocking.
     */
    public void demoteIfHighLatency(Hash peer) {
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase32().substring(0, 6) +
                              "] from fast/high-cap tiers due to high latency (capacityBonus = -30)");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers if it has a congestion cap (D/E).
     * Sets capacityBonus = -30 so the UI reflects the demotion immediately.
     * Non-blocking - only acts if the peer is currently in those tiers.
     */
    public void demoteIfCongested(Hash peer) {
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase32().substring(0, 6) +
                              "] from fast/high-cap tiers due to congestion cap (D/E)");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers when a peer test
     * shows it is no longer low-latency.  Called from PeerTestJob when a
     * test result arrives that contradicts the peer's tier membership.
     * Non-blocking — only acts if the peer is currently in those tiers.
     *
     * @param peer the peer
     * @since 0.9.71+
     */
    public void demoteIfNotLowLatency(Hash peer) {
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if ((inFast || inHighCap) && !isLowLatency(peer)) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase32().substring(0, 6) +
                              "] from fast/high-cap tiers: no longer low-latency");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Check whether the peer's profile has low-latency status.
     * Non-blocking — returns the profile's flag without acquiring locks.
     */
    private boolean isLowLatency(Hash peer) {
        PeerProfile profile = getProfileNonblocking(peer);
        return profile != null && profile.isLowLatency();
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers if it is banned.
     * Sets capacityBonus = -30 so the UI reflects the demotion immediately.
     * Non-blocking - only acts if the peer is currently in those tiers.
     * @since 0.9.71+
     */
    public void demoteIfBanned(Hash peer) {
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase32().substring(0, 6) +
                              "] from fast/high-cap tiers: banned");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers if it is unreachable.
     * Sets capacityBonus = -30 so the UI reflects the demotion immediately.
     * Non-blocking - only acts if the peer is currently in those tiers.
     * @since 0.9.71+
     */
    /**
     *  Record a measured direct-link RTT against a peer.
     *
     *  <p>Owns the profile write so callers holding a transport measurement do
     *  not have to know how the value is stored or aged out. The value decays
     *  on the existing Active tier window, so a first-hop latency never
     *  outlives the evidence class it belongs to.
     *
     * @param peer the measured peer
     * @param rtt the measured round trip time in ms
     * @param when timestamp of the measurement
     * @since 0.9.71+
     */
    public void noteFirstHopRtt(Hash peer, int rtt, long when) {
        if (rtt <= 0) {return;}
        PeerProfile profile = getProfile(peer);
        if (profile == null) {return;}
        profile.setFirstHopRtt(rtt, when);
        _context.statManager().addRateData("tunnel.firstHopRtt", rtt);
        refreshLowLatencyFromFirstHop(profile);
    }



    private void refreshLowLatencyFromFirstHop(PeerProfile profile) {
        // The floor is absolute, so no population average and no minimum sample
        // count is consulted: the bar does not move as RTTs age out of the
        // measured set. See PeerProfile.LOW_LATENCY_FLOOR_MS.
        Boolean low = profile.getLowLatencyForFirstHopRtt(_context.clock().now(),
                                                            PeerProfile.LOW_LATENCY_CEILING_MS);
        if (low == null) {return;}
        if (profile.isLowLatency() == low.booleanValue()) {return;}
        profile.setLowLatency(low.booleanValue());
    }

    public void demoteIfUnreachableNow(Hash peer) {
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase32().substring(0, 6) +
                              "] from fast/high-cap tiers: unreachable");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers if it has G cap (no tunnels).
     * Sets capacityBonus = -30 so the UI reflects the demotion immediately.
     * Non-blocking - only acts if the peer is currently in those tiers.
     */
    public void demoteIfNoTunnel(Hash peer) {
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase32().substring(0, 6) +
                              "] from fast/high-cap tiers due to no tunnel built recently");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers when our transport
     * connection to it failed as a tunnel first hop (unreachable).
     * Uses a 3-strike threshold within the decay window to avoid premature
     * demotion — a transient failure (e.g. pre-connect race) should not remove
     * a peer from tiers, and strikes older than the window expire so a peer
     * that recovered is not demoted by one later failure.
     * Does not touch the tunnel failure counter; the caller records the
     * failure blame (e.g. profileManager().tunnelFailed()).
     */
    public void demoteIfUnreachable(Hash peer) {
        if (!getWriteLock()) return;
        long now = _context.clock().now();
        try {
            // Evict stale demotion cooldowns (entries >10 min old)
            long cooldownCutoff = now - TUNNEL_DEMOTION_COOLDOWN_MS;
            _demotedPeers.entrySet().removeIf(e -> e.getValue() < cooldownCutoff);
            // Cap size to prevent unbounded growth under pathological conditions
            if (_demotedPeers.size() > MAX_DEMOTED_PEERS) {
                _demotedPeers.clear();
            }
            // Prune expired strikes; drop strikes whose timestamp is gone after
            // a decay-window sweep so the two maps stay consistent. Only sweep
            // when large to keep this off the common path.
            if (_demoteStrikes.size() > 32) {
                long strikeCutoff = now - DEMOTE_STRIKE_DECAY_MS;
                _demoteStrikeTimes.entrySet().removeIf(e -> e.getValue() < strikeCutoff);
                _demoteStrikes.keySet().removeIf(k -> !_demoteStrikeTimes.containsKey(k));
            }
            // Cap size to prevent unbounded growth under pathological conditions
            if (_demoteStrikes.size() > 128 || _demoteStrikeTimes.size() > 128) {
                _demoteStrikes.clear();
                _demoteStrikeTimes.clear();
            }
            // Strike tracking: only demote after threshold failures within the decay window
            Long lastStrikeTime = _demoteStrikeTimes.get(peer);
            int strikes = nextStrikeCount(_demoteStrikes.getOrDefault(peer, Integer.valueOf(0)),
                                          lastStrikeTime != null ? lastStrikeTime.longValue() : 0L,
                                          now);
            _demoteStrikes.put(peer, Integer.valueOf(strikes));
            _demoteStrikeTimes.put(peer, Long.valueOf(now));
            if (strikes < DEMOTE_STRIKE_THRESHOLD) {
                return;
            }
            // Reset strikes and proceed with demotion
            _demoteStrikes.remove(peer);
            _demoteStrikeTimes.remove(peer);
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase32().substring(0, 6) +
                              "] from fast/high-cap tiers after " + DEMOTE_STRIKE_THRESHOLD +
                              " unreachable first-hop failures");
                }
                _demotedPeers.put(peer, now);
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Batch-demote peers from fast/high-cap tiers under a single write lock.
     * Peers not currently in any tier are silently skipped.
     * One {@link #promoteToFillTiers()} call after all removals.
     *
     * @param peers peers to evict — may be empty (no-op, no lock)
     * @since 0.9.71+
     */
    public void demoteBatch(Set<Hash> peers) {
        if (peers.isEmpty()) return;
        if (!getWriteLock()) return;
        try {
            int removed = 0;
            for (Hash peer : peers) {
                boolean inFast = _fastPeers.containsKey(peer);
                boolean inHighCap = _highCapacityPeers.containsKey(peer);
                if (inFast || inHighCap) {
                    if (inFast) removeFastPeer(peer);
                    if (inHighCap) _highCapacityPeers.remove(peer);
                    removed++;
                }
            }
            if (removed > 0) {
                if (_log.shouldInfo()) {
                    _log.info("Batch demoted " + removed + " of " + peers.size() + " peers from fast/high-cap tiers");
                }
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Batch-remove peers from fast tier only, keeping them in high-cap.
     * Used to demote peers with test failures from fast to high cap.
     * Does NOT call promoteToFillTiers() — re-promotion would undo the
     * demotion. The next full reorganize() fills any gaps.
     *
     * @param peers peers to remove from fast tier — may be empty (no-op, no lock)
     * @since 0.9.71+
     */
    public void demoteFastOnlyBatch(Set<Hash> peers) {
        if (peers.isEmpty()) return;
        if (!getWriteLock()) return;
        try {
            int removed = 0;
            for (Hash peer : peers) {
                if (_fastPeers.containsKey(peer)) {
                    removeFastPeer(peer);
                    removed++;
                }
            }
            if (removed > 0 && _log.shouldInfo()) {
                _log.info("Batch demoted " + removed + " of " + peers.size() + " peers from fast tier (kept in high-cap)");
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Compute the next strike count after a failure, applying time-window
     * decay. A previous strike older than DEMOTE_STRIKE_DECAY_MS no longer
     * counts, so a peer that recovered is not demoted by one later failure.
     *
     * @param previousStrikes strike count before this failure (0 if none)
     * @param lastStrikeTime time of the previous strike (0 if none)
     * @param now current time
     * @return the new strike count, at least 1
     * @since 0.9.71+
     */
    static int nextStrikeCount(int previousStrikes, long lastStrikeTime, long now) {
        if (previousStrikes <= 0 || lastStrikeTime <= 0 || now - lastStrikeTime >= DEMOTE_STRIKE_DECAY_MS) {
            return 1;
        }
        return previousStrikes + 1;
    }

    /**
     *  Demote a peer from fast/high-cap tiers when its direct-link latency is poor.
     *
     *  <p>{@code responseTimeMs} is the round trip through an entire multi-hop
     *  tunnel, which {@code TestJob.noteSuccess} attributes to every peer in that
     *  tunnel. That is a reasonable profile statistic but the wrong input for
     *  first-hop selection: a peer with a fast direct link is blamed for the other
     *  hops it happened to be a member of, and the result is compared against a
     *  first-hop threshold. In production that evicted a large share of the fast
     *  tier on a latency distribution taken across the wrong hops, shrinking the
     *  candidate pool and leaving slower survivors.
     *
     *  <p>The tier decision therefore uses {@link PeerProfile#getFirstHopRtt},
     *  measured on the direct link. The passed-in tunnel time is not consulted.
     *  A peer with no recorded first-hop RTT is left alone: unknown is not slow.
     *
     * @param peer the peer to evaluate
     * @param responseTimeMs full-tunnel test round trip, unused by the tier decision
     * @since 0.9.71+
     */
    void demoteIfHighRTT(Hash peer, long responseTimeMs) {
        PeerProfile profile = getProfile(peer);
        if (profile == null) {return;}
        demoteIfSlowFirstHop(peer, profile.getFirstHopRtt(_context.clock().now()));
    }

    /**
     *  Whether a measured first-hop RTT warrants removal from the fast tiers.
     *
     *  <p>Pure so the policy is testable without a populated profile. An unknown
     *  measurement (negative) is never slow: we have not probed the peer, and
     *  evicting on absent evidence would drain the tier of exactly the peers we
     *  have not looked at yet.
     *
     * @param firstHopRttMs direct-link RTT in ms, negative when unknown
     * @param thresholdMs the latency ceiling in ms
     * @return true if the peer should be evicted from the fast/high-cap tiers
     * @since 0.9.71+
     */
    static boolean firstHopRttIsSlow(int firstHopRttMs, int thresholdMs) {
        return firstHopRttMs >= 0 && firstHopRttMs >= thresholdMs;
    }

    /**
     *  Remove a peer from the fast/high-cap tiers if its measured direct-link RTT
     *  reaches the low-latency threshold.
     *
     *  <p>The threshold is unchanged at {@code router.peerTestTimeout * 2}; only the
     *  measurement changed, from a whole tunnel to one hop.
     *
     * @param peer the peer to evaluate
     * @param firstHopRttMs direct-link RTT in ms, negative when unknown
     * @since 0.9.71+
     */
    void demoteIfSlowFirstHop(Hash peer, int firstHopRttMs) {
        int timeout = _context.getProperty("router.peerTestTimeout", 750);
        if (!firstHopRttIsSlow(firstHopRttMs, timeout * 2)) {return;}
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase32().substring(0, 6) +
                              "] from fast/high-cap tiers due to high first hop RTT: " +
                              firstHopRttMs + "ms (threshold: " + (timeout * 2) + "ms)");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Check if peer has low tunnel acceptance ratio (< 40%).
     * Peers with no tunnel test history (totalRequests == 0) are
     * considered low — they have no evidence of tunnel-building
     * reliability and must not be placed in fast/high-cap tiers.
     * Uses persisted accept/reject counts even with low sample sizes at startup.
     * Also checks for recent bandwidth rejections and applies cooldown.
     * <p>
     * Takes the build success ratio as a parameter — it is fetched once per
     * selection and passed down; {@link #getTunnelBuildSuccess()} performs 6
     * stat lookups and must never run per candidate peer.
     *
     * @param profile the peer profile
     * @param buildSuccess the tunnel build success ratio in [0.0, 1.0]
     * @param now current time in ms
     * @return whether low tunnel acceptance (true = exclude from fast/high-cap tiers)
     * @since 0.9.71+
     */
    private boolean isLowTunnelAcceptance(PeerProfile profile, double buildSuccess, long now) {
        TunnelHistory th = profile.getTunnelHistory();
        if (th == null) return false;

        long agreed = th.getLifetimeAgreedTo();
        long rejected = th.getLifetimeRejected();
        long totalRequests = agreed + rejected;

        if (totalRequests == 0) {
            // No tunnel test history — allow alive peers into tiers so they
            // can accumulate tunnel data.  Stale peers (not heard from recently)
            // are excluded; under-performers are evicted by existing mechanisms.
            return !hasRecentTierActivity(profile, now - NO_HISTORY_ACTIVITY_WINDOW_MS);
        }

        double ratio = (double) agreed / totalRequests;

        // During high-stress (many peers rejecting), lower the threshold
        // to avoid excluding peers that are only rejecting due to network-
        // wide congestion rather than their own limitations.
        boolean highStress = buildSuccess < ATTACK_THRESHOLD;

        if (totalRequests < MIN_TUNNEL_REQUESTS) {
            return isLowAcceptanceLowSample(profile, ratio, highStress);
        }
        return isLowAcceptanceMature(profile, th, ratio, agreed, rejected, highStress);
    }

    /**
     *  Small-sample decision: below {@link #MIN_TUNNEL_REQUESTS} lifetime
     *  requests, apply the loose startup thresholds.
     *
     *  @param profile the peer profile
     *  @param ratio the acceptance ratio in [0.0, 1.0]
     *  @param highStress whether build success is below the attack threshold
     *  @return whether low tunnel acceptance
     *  @since 0.9.71+ (extracted from isLowTunnelAcceptance)
     */
    private boolean isLowAcceptanceLowSample(PeerProfile profile, double ratio, boolean highStress) {
        double threshold = highStress ? 0.10 : 0.20;
        if (ratio >= threshold) return false;
        if (_log.shouldDebug()) {
            _log.debug("Demoting peer from fast tier (startup): " +
                       profile.getPeer().toBase32().substring(0, 6) +
                       " ratio: " + String.format("%.2f", ratio * 100) + "% below " + String.format("%.0f", threshold * 100) + "% threshold");
        }
        return true;
    }

    /**
     *  Mature decision: effective minimum ratio, with a cooldown for
     *  recent bandwidth rejections.
     *
     *  @param profile the peer profile
     *  @param th the tunnel history (non-null)
     *  @param ratio the acceptance ratio in [0.0, 1.0]
     *  @param agreed lifetime accepted requests
     *  @param rejected lifetime rejected requests
     *  @param highStress whether build success is below the attack threshold
     *  @return whether low tunnel acceptance
     *  @since 0.9.71+ (extracted from isLowTunnelAcceptance)
     */
    private boolean isLowAcceptanceMature(PeerProfile profile, TunnelHistory th, double ratio,
                                          long agreed, long rejected, boolean highStress) {
        double effectiveMinRatio = highStress ? 0.10 : MIN_TUNNEL_ACCEPTANCE_RATIO;
        if (ratio >= effectiveMinRatio) {
            return false;
        }

        long now = _context.clock().now();
        long lastBwReject = th.getLastRejectedBandwidth();
        long timedOut = th.getLifetimeTimedOut();
        if (lastBwReject > 0 && (now - lastBwReject) < TUNNEL_DEMOTION_COOLDOWN_MS) {
            if (_log.shouldDebug()) {
                long remaining = (TUNNEL_DEMOTION_COOLDOWN_MS - (now - lastBwReject)) / 60000;
                _log.debug("Demoting peer from high-cap tier (bandwidth cooldown active): " +
                           profile.getPeer().toBase32().substring(0, 6) +
                           " ratio: " + String.format("%.2f", ratio * 100) + "% (" + agreed + " accept / " +
                           rejected + " reject / " + timedOut + " timeout), cooldown: " + remaining + "min remaining");
            }
            return true;
        }

        if (_log.shouldDebug()) {
            _log.debug("Demoting peer from high-cap tier due to low tunnel acceptance: " +
                       profile.getPeer().toBase32().substring(0, 6) +
                       " ratio: " + String.format("%.2f", ratio * 100) + "% (" + agreed + " accept / " +
                       rejected + " reject / " + timedOut + " timeout)");
        }
        return true;
    }

    /**
     * Check if peer is a quality tunnel candidate: good acceptance ratio,
     * selectable, and recently active. Used by tuner to adjust tier limits
     * based on viable peers, not just raw counts.
     *
     * @return whether quality peer
     * @since 0.9.70+
     */
    private boolean isQualityPeer(PeerProfile profile, long recentCutoff, double buildSuccess) {
        if (profile.getPeer().equals(_us)) return false;
        if (!isSelectable(profile.getPeer(), buildSuccess)) return false;

        // Must have recent activity (last send within 24h)
        long lastSend = profile.getLastSendSuccessful();
        if (lastSend < recentCutoff) return false;

        // Check acceptance ratio
        TunnelHistory th = profile.getTunnelHistory();
        if (th != null) {
            long agreed = th.getLifetimeAgreedTo();
            long rejected = th.getLifetimeRejected();
            long totalRequests = agreed + rejected;
            if (totalRequests >= MIN_TUNNEL_REQUESTS) {
                double ratio = (double) agreed / totalRequests;
                if (ratio < MIN_TUNNEL_ACCEPTANCE_RATIO) return false;
            }
        }

        return true;
    }

    /**
     * Check if a peer has had recent tunnel failures (failed tests or builds).
     * Used to gate fast/high-cap tier admission when tier counts are healthy.
     * Requires more than 3 failures in the last hour AND either a failure
     * ratio above 30% or more than 5 absolute failures/hour — peers with
     * 1-3 transient failures during a network blip stay in tiers rather
     * than being purged.  This prevents tier collapse during brief
     * network-wide events where many peers experience isolated failures.
     * @return true if the peer has sustained recent tunnel failures
     * @since 0.9.70+
     */
    private boolean hasRecentTunnelFailures(PeerProfile profile) {
        TunnelHistory th = profile.getTunnelHistory();
        if (th == null) return false;
        Rate failed = th.getFailedRate().getRate(RateConstants.ONE_HOUR);
        if (failed == null) return false;
        long failCount = failed.getCurrentEventCount();
        // Allow transient failures during network blips
        if (failCount <= 15) return false;
        // Reject peers with sustained failures (>30% failure ratio)
        long agreed = th.getLifetimeAgreedTo();
        long rejected = th.getLifetimeRejected();
        long total = agreed + rejected + failCount;
        if (total > 0 && (double) failCount / total > 0.3) return true;
        // Reject peers with high absolute failure count (>15 failures/hour)
        return false;
    }

    /**
     * True if the peer's decayed loss score is at or above
     * {@link #PROP_LOSSY_THRESHOLD}. The score decays by 50% per hour since
     * the last transport report, so a peer stays gated for roughly an hour
     * after a severe loss episode even if the transport goes quiet — the
     * decay, not a freshness window, provides the slow forgiveness.
     *
     * @param profile the profile
     * @param now current time in ms
     * @since 0.9.71+
     */
    private boolean hasHighLoss(PeerProfile profile, long now) {
        float score = profile.getLossScore(now);
        if (score <= 0.0f) return false;
        return score >= getLossyThreshold(_context);
    }

    /**
     * True if the peer is in loss probation: it was demoted from the fast/
     * high-cap tiers for packet loss and has not yet produced fresh clean
     * evidence of recovery (see {@link #shouldReadmitLossy}). Also covers
     * peers whose decayed score is at or above the demotion threshold even if
     * the demotion flag was never set, e.g. a profile loaded from disk.
     *
     * @param profile the profile
     * @param now current time in ms
     * @since 0.9.71+
     */
    private boolean inLossProbation(PeerProfile profile, long now) {
        if (hasHighLoss(profile, now)) return true;
        if (!profile.isLossy()) return false;
        return !shouldReadmitLossy(profile, now);
    }

    /**
     * True if a demoted peer may be re-admitted to the tiers: at least
     * {@link #LOSS_READMIT_MIN_AGE} has passed since the demotion AND the
     * transport has reported a loss ratio below the demotion threshold within
     * {@link #PROP_LOSSY_WINDOW}. A stale or absent ratio is not evidence of
     * recovery — this is what breaks the avoid-then-forget loop, because a
     * peer that recovered organically accumulates new packets (netDb traffic
     * continues while it is demoted) and reports a fresh clean ratio, while a
     * peer that is still lossy never does.
     * <p>
     * Fast-track: peers with a fresh clean loss report (loss ratio below 5%
     * within the lossy freshness window, default 10 min) are readmitted
     * immediately regardless of the minimum age, accelerating tier recovery
     * after brief congestion episodes.
     *
     * @param profile the profile
     * @param now current time in ms
     * @return true if the peer may be re-admitted
     * @since 0.9.71+
     */
    private boolean shouldReadmitLossy(PeerProfile profile, long now) {
        long since = profile.getLossySince();
        if (since <= 0) return true;
        float threshold = getLossyThreshold(_context);
        boolean freshClean = now - profile.getLossRatioLastUpdate() < _context.getProperty(PROP_LOSSY_WINDOW, DEFAULT_LOSSY_WINDOW) &&
                             profile.getLossRatio() < threshold;
        // Fast-track: peer has fresh clean evidence (loss ratio below threshold
        // within the freshness window) AND the loss ratio is very low (<5%),
        // indicating recovery from brief congestion.  Skip the minimum age
        // requirement to accelerate tier re-admission.
        if (freshClean && profile.getLossRatio() < 0.05f) return true;
        if (now - since < LOSS_READMIT_MIN_AGE) return false;
        return freshClean;
    }

    /**
     * Clear the loss demotion flag once a peer has been re-admitted, so the
     * cleared state is persisted and re-admission is not re-evaluated on every
     * reorganize. Must be called with the write lock held.
     *
     * @param profile the profile
     * @since 0.9.71+
     */
    private void clearLossIfReadmitted(PeerProfile profile) {
        if (profile.isLossy()) {
            profile.clearLossy();
            if (_log.shouldDebug()) {
                _log.debug("Peer [" + profile.getPeer().toBase32().substring(0, 6) +
                           "] re-admitted after loss probation: loss score " +
                           profile.getLossScore(_context.clock().now()));
            }
        }
    }

    /**
     * Instantly remove a peer from the fast/high-capacity tiers when its decayed loss
     * score exceeds the threshold, and mark it for loss probation. Called from the
     * transport path via ProfileManagerImpl so a lossy peer is demoted as soon as its
     * retransmit ratio crosses the bar, instead of waiting for the next reorganize
     * cycle. Non-blocking: if a reorganize currently holds the write lock, the
     * reorganize purge catches the peer instead.
     *
     * @param peer the peer
     * @since 0.9.71+
     */
    public void demoteIfLossy(Hash peer) {
        if (!tryWriteLock()) return;
        try {
            PeerProfile profile = lockedGetProfile(peer);
            if (profile != null && hasHighLoss(profile, _context.clock().now())) {
                boolean wasFast = removeFastPeer(peer) != null;
                boolean wasHighCap = _highCapacityPeers.remove(peer) != null;
                // Start probation even if the peer was already out of the tiers,
                // so it cannot be re-promoted on stale values before the
                // readmission conditions are met.
                profile.setLossySince(_context.clock().now());
                if ((wasFast || wasHighCap) && _log.shouldDebug()) {
                    _log.debug("Demoting peer [" + peer.toBase32().substring(0, 6) +
                               "] from fast/high-cap tiers: loss " + profile.getLossScore(_context.clock().now()));
                }
            }
        } finally {releaseWriteLock();}
    }

    /**
     * The moderate de-prioritization threshold in effect, read straight from
     * config (there is no Tuner override, unlike {@link #getLossyThreshold}).
     * Separate so a caller walking a whole candidate list can resolve both
     * thresholds once instead of paying two config lookups per candidate.
     *
     * @param ctx the router context
     * @return the loss score at or above which a peer is de-prioritized at selection
     * @since 0.9.71+
     */
    public static float getModerateLossyThreshold(RouterContext ctx) {
        return ctx.getProperty(PROP_LOSSY_MODERATE_THRESHOLD, DEFAULT_LOSSY_MODERATE_THRESHOLD);
    }

    /**
     * Whether a decayed loss score falls in the moderate band: at or above the
     * moderate threshold but below the demotion threshold. Such peers remain
     * eligible for the tiers — they never crossed the hard bar — but are
     * de-prioritized at selection time (see LOSSY_SELECTION_PENALTY).
     * <p>
     * Pure decision — the score and both thresholds are passed in so a caller
     * scanning a candidate list resolves them once, safe for unit tests.
     *
     * @param score the peer's decayed loss score
     * @param moderateThreshold lower bound of the band, inclusive
     * @param demoteThreshold upper bound of the band, exclusive
     * @return whether the score is in the moderate band
     * @since 0.9.71+
     */
    static boolean isModeratelyLossy(float score, float moderateThreshold, float demoteThreshold) {
        if (score <= 0.0f) return false;
        return score >= moderateThreshold && score < demoteThreshold;
    }

    /**
     * Average loss ratio across the fast and high-capacity tiers with a fresh
     * measurement, as a fraction (0.0 - 1.0). Peers whose loss data is older than
     * {@link #PROP_LOSSY_WINDOW} are skipped; 0.0 if no fresh data. The tiers are
     * the baseline because the threshold governs demotion FROM them: as lossy peers
     * are evicted the average drops and the bar tightens. Used by the Tuner to
     * adapt {@link #PROP_LOSSY_THRESHOLD} to the network.
     *
     * @since 0.9.71+
     */
    public float getAverageLossRatio() {
        if (!tryReadLock()) return 0.0f;
        try {return averageLossRatio(_fastPeers, _highCapacityPeers);}
        finally {releaseReadLock();}
    }

    /**
     * Average loss ratio across the fast tier with a fresh measurement, as a
     * fraction (0.0 - 1.0); 0.0 if none. See {@link #getAverageLossRatio()}.
     *
     * @since 0.9.71+
     */
    public float getFastAverageLossRatio() {
        if (!tryReadLock()) return 0.0f;
        try {return averageLossRatio(_fastPeers);}
        finally {releaseReadLock();}
    }

    /**
     * Average loss ratio across the high-capacity tier with a fresh measurement,
     * as a fraction (0.0 - 1.0); 0.0 if none. See {@link #getAverageLossRatio()}.
     *
     * @since 0.9.71+
     */
    public float getHighCapAverageLossRatio() {
        if (!tryReadLock()) return 0.0f;
        try {return averageLossRatio(_highCapacityPeers);}
        finally {releaseReadLock();}
    }

    /**
     * Median loss ratio of the union of the fast and high-capacity tiers with a
     * fresh measurement, as a fraction (0.0 - 1.0); 0.0 if none. The median is
     * robust to the worst peers dragging the mean up, so the auto-tuned demotion
     * threshold tracks the typical tier member. See {@link #getAverageLossRatio()}.
     *
     * @since 0.9.71+
     */
    public float getMedianLossRatio() {
        if (!tryReadLock()) return 0.0f;
        try {return medianLossRatio(_fastPeers, _highCapacityPeers);}
        finally {releaseReadLock();}
    }

    /**
     * Mean loss ratio of the union of the given tiers, restricted to profiles
     * with a measurement fresh within {@link #PROP_LOSSY_WINDOW}.
     *
     * @param tiers the tier maps to average over
     * @return the mean as a fraction, 0.0 if no fresh data
     * @since 0.9.71+
     */
    private float averageLossRatio(Map<Hash, PeerProfile>... tiers) {
        List<Float> ratios = freshLossRatios(tiers);
        if (ratios.isEmpty()) return 0.0f;
        float sum = 0.0f;
        for (float ratio : ratios) sum += ratio;
        return sum / ratios.size();
    }

    /**
     * Median loss ratio of the union of the given tiers, restricted to profiles
     * with a measurement fresh within {@link #PROP_LOSSY_WINDOW}.
     *
     * @param tiers the tier maps to average over
     * @return the median as a fraction, 0.0 if no fresh data
     * @since 0.9.71+
     */
    private float medianLossRatio(Map<Hash, PeerProfile>... tiers) {
        List<Float> ratios = freshLossRatios(tiers);
        int n = ratios.size();
        if (n == 0) return 0.0f;
        return n % 2 == 1 ? ratios.get(n / 2) : (ratios.get(n / 2 - 1) + ratios.get(n / 2)) / 2.0f;
    }

    /**
     * Fresh loss ratios (positive and within {@link #PROP_LOSSY_WINDOW}) of the
     * union of the given tiers, sorted ascending; empty if none.
     *
     * @param tiers the tier maps to sample from
     * @return the sorted fresh ratios
     * @since 0.9.71+
     */
    private List<Float> freshLossRatios(Map<Hash, PeerProfile>... tiers) {
        long now = _context.clock().now();
        long window = _context.getProperty(PROP_LOSSY_WINDOW, DEFAULT_LOSSY_WINDOW);
        Set<Hash> seen = new HashSet<>();
        List<Float> rv = new ArrayList<>();
        for (Map<Hash, PeerProfile> tier : tiers) {
            for (Map.Entry<Hash, PeerProfile> e : tier.entrySet()) {
                if (!seen.add(e.getKey())) continue;
                float ratio = e.getValue().getLossRatio();
                if (ratio > 0.0f && now - e.getValue().getLossRatioLastUpdate() < window)
                    rv.add(ratio);
            }
        }
        rv.sort(Float::compare);
        return rv;
    }

    /**
     *  Fallback tier size for a threshold that scales with network size.
     *
     *  <p>Networks at or below {@link #SCALING_THRESHOLD} known routers use the
     *  configured base; above it the base scales with a fraction of the network.
     *
     *  @param known routers in the network database
     *  @param base configured size to use, and the floor once scaling applies
     *  @param divisor network size is divided by this once scaling applies
     *  @return the size to use unless a property overrides it
     *  @since 0.9.71+
     */
    static int defaultTierSize(int known, int base, int divisor) {
        return known > SCALING_THRESHOLD ? Math.max(known / divisor, base) : base;
    }

    /**
     *  Ceiling on the fast tier from the number of reachable peers.
     *
     *  <p>1.5x headroom over active for near-active peers, never below {@code base}.
     *
     *  @param active peers reachable over any transport
     *  @param base floor to keep even when very few peers are reachable
     *  @return the fast tier ceiling
     *  @since 0.9.71+
     */
    static int fastActiveCap(int active, int base) {
        return Math.max(active + active / 2, base);
    }

    /**
     *  Ceiling on the high-capacity tier from the number of reachable peers.
     *
     *  <p>2x headroom, since high-cap is a broader category, never below {@code base}.
     *
     *  @param active peers reachable over any transport
     *  @param base floor to keep even when very few peers are reachable
     *  @return the high-capacity tier ceiling
     *  @since 0.9.71+
     */
    static int highCapActiveCap(int active, int base) {
        return Math.max(active * 2, base);
    }

    /**
     * Minimum number of peers to keep in the fast tier.
     *
     * @return the minimum fast peers
     */
    protected int getMinimumFastPeers() {
        if (_context.router() == null) return _defaultMinFastPeers;
        return getMinimumFastPeers(_context.netDb().getKnownRouters());
    }

    /**
     * Variant taking the network size, for callers that already know it.
     *
     * <p>{@code getKnownRouters()} walks every entry in the network database, which is
     * far too expensive to repeat once per candidate.
     *
     * @param known routers in the network database
     * @return the minimum fast peers
     * @since 0.9.71+
     */
    protected int getMinimumFastPeers(int known) {
        if (_context.router() == null) return _defaultMinFastPeers;
        return _context.getProperty(PROP_MINIMUM_FAST_PEERS,
                                    defaultTierSize(known, _defaultMinFastPeers, 15));
    }

    /**
     * Maximum number of peers to keep in the fast tier, capped by active peers.
     *
     * @return the maximum fast peers
     */
    protected int getMaximumFastPeers() {
        if (_context.router() == null) return _defaultMaxFastPeers;
        return getMaximumFastPeers(_context.netDb().getKnownRouters(),
                                   _context.commSystem().countActivePeers());
    }

    /**
     * Variant taking both expensive inputs, for callers that already have them.
     *
     * @param known routers in the network database
     * @param active peers currently reachable over any transport
     * @return the maximum fast peers
     * @since 0.9.71+
     */
    protected int getMaximumFastPeers(int known, int active) {
        if (_context.router() == null) return _defaultMaxFastPeers;
        int maxFromKnown = _context.getProperty(PROP_MAXIMUM_FAST_PEERS,
                                                defaultTierSize(known, _defaultMaxFastPeers, 12));
        // Cap fast tier by active peers — having more fast peers than are
        // actually reachable wastes builds on stale/unresponsive peers.
        if (active > 0) {
            return Math.min(maxFromKnown, fastActiveCap(active, _defaultMinFastPeers));
        }
        return maxFromKnown;
    }

    /**
     * Current number of peers in the fast tier.
     * Used by selectors to decide cross-pool diversity mode.
     *
     * @return fast tier size
     * @since 0.9.70
     */
    public int getFastPeerCount() {
        return _fastPeers.size();
    }

    /**
     *  Current high-capacity tier population.  Paired with
     *  {@link #getFastPeerCount()} so first-hop selection can report the pool
     *  it drew from: a 256-candidate sample means something very different
     *  against a 200-peer tier (the whole pool) than a 1500-peer one (a
     *  fraction), and the two call for different responses.
     *
     *  @return the number of peers in the high-capacity tier
     *  @since 0.9.71+
     */
    public int getHighCapPeerCount() {
        return _highCapacityPeers.size();
    }

    /**
     *  The candidate-sample floor currently in effect, for logging.
     *  @param ctx the router context
     *  @return the floor passed to {@link #maxCandidateSample}
     *  @since 0.9.71+
     */
    public int getEffectiveCandidateSample(RouterContext ctx) {
        return getMinCandidateSample(ctx);
    }

    /**
     * Maximum number of peers to keep in the high-capacity tier, capped by active peers.
     *
     * @return the maximum high cap peers
     */
    protected int getMaximumHighCapPeers() {
        if (_context.router() == null) return _defaultMaxHighCapPeers;
        return getMaximumHighCapPeers(_context.netDb().getKnownRouters(),
                                      _context.commSystem().countActivePeers());
    }

    /**
     * Variant taking both expensive inputs, for callers that already have them.
     *
     * @param known routers in the network database
     * @param active peers currently reachable over any transport
     * @return the maximum high cap peers
     * @since 0.9.71+
     */
    protected int getMaximumHighCapPeers(int known, int active) {
        if (_context.router() == null) return _defaultMaxHighCapPeers;
        int maxFromKnown = _context.getProperty(PROP_MAXIMUM_HIGH_CAPACITY_PEERS,
                                                defaultTierSize(known, _defaultMaxHighCapPeers, 10));
        // Cap high-cap tier by active peers — same rationale as fast tier cap.
        if (active > 0) {
            return Math.min(maxFromKnown, highCapActiveCap(active, _defaultMinHighCapPeers));
        }
        return maxFromKnown;
    }

    /**
     * Minimum number of peers to keep in the high-capacity tier.
     *
     * @return the minimum high capacity peers
     */
    protected int getMinimumHighCapacityPeers() {
        if (_context.router() == null) return _defaultMinHighCapPeers;
        return getMinimumHighCapacityPeers(_context.netDb().getKnownRouters());
    }

    /**
     * Variant taking the network size, for callers that already know it.
     *
     * @param known routers in the network database
     * @return the minimum high capacity peers
     * @since 0.9.71+
     */
    protected int getMinimumHighCapacityPeers(int known) {
        if (_context.router() == null) return _defaultMinHighCapPeers;
        return _context.getProperty(PROP_MINIMUM_HIGH_CAPACITY_PEERS,
                                    defaultTierSize(known, _defaultMinHighCapPeers, 15));
    }

    private static final DecimalFormat _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
    private static final String num(double num) {synchronized (_fmt) {return _fmt.format(num);} }

    /**
     * Command-line entry point; reads profile dump files and prints thresholds.
     */
    public static void main(String[] args) {
        if (args.length <= 0) {
            System.err.println("Usage: profileorganizer file.txt.gz [file2.txt.gz] ..."); // NOSONAR CLI tool
            System.exit(1);
        }

        RouterContext ctx = new RouterContext(null);
        ProfileOrganizer organizer = new ProfileOrganizer(ctx);
        organizer.setUs(Hash.FAKE_HASH);
        ProfilePersistenceHelper helper = new ProfilePersistenceHelper(ctx);

        for (int i = 0; i < args.length; i++) {
            PeerProfile profile = helper.readProfile(new File(args[i]), 0);
            if (profile == null) {
                System.err.println("Could not load profile " + args[i]); // NOSONAR CLI tool
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

    /**
     *  Short-lived cache for {@link #getTunnelBuildSuccess()}.  The 6 RateStat
     *  lookups are expensive when called per-candidate in peer selection (up to
     *  400 peers × 6 lookups).  The ratio is a 10-minute rolling average, so
     *  a 15s cache (<2.5% staleness) eliminates ~40x redundant lookups with
     *  negligible impact on decision accuracy.
     */
    private volatile double _cachedBuildSuccess = 1.0;
    private volatile long _cachedBuildSuccessTime;
    private static final long BUILD_SUCCESS_CACHE_MS = 15_000;

    /**
     *  Peers with zero tunnel history are allowed into fast/high-cap tiers if
     *  they were heard from within this window.  Alive peers that haven't yet
     *  built tunnels get a chance; dead peers are excluded.  Under-performers
     *  are evicted by existing mechanisms (latency, acceptance ratio, loss
     *  probation) within minutes.
     *
     *  @since 0.9.71+
     */
    private static final long NO_HISTORY_ACTIVITY_WINDOW_MS = 5 * 60 * 1000L;

    /**
     *  Recent tunnel build success ratio, from router statistics.
     *  <p>
     *  TEN_MINUTES window: a short window reacts quickly to network health
     *  changes, so the attack-mode gates below track the current situation
     *  instead of lifetime averages.  When no data is available (stats not
     *  yet created at boot, or an empty window), the ratio is 1.0 — no data
     *  means neutral, not "under attack"; a missing-stat 0.0 put the router
     *  into permanent attack mode before stats existed.
     *
     *  <p>The cache covers the no-data path too: on fallback the cached 1.0 is
     *  (re)stamped so a missing StatManager early in boot cannot turn every
     *  per-candidate call into a fresh 6-lookup scan.
     *
     *  @return the tunnel build success in [0.0, 1.0], 1.0 when no data
     *  @since 0.9.71+
     */
    /**
     *  Maps an unknown build-success reading onto a neutral 1.0.
     *
     *  <p>An absent stat, or a window in which nothing has settled yet, is not evidence of
     *  failure. Reading it as anything else would trip {@link #isLowBuildSuccess()} and switch
     *  off profile eviction, lengthen ghost cooldowns and put the first-hop selector into its
     *  defensive path for no reason - so "unknown" has to arrive upstream of every one of those
     *  as 1.0.
     *
     *  @param ratio the raw ratio, or NaN when unknown
     *  @return {@code ratio}, or 1.0 when it is NaN
     *  @since 0.9.71+
     */
    static double neutraliseUnknownBuildSuccess(double ratio) {
        return Double.isNaN(ratio) ? 1.0 : ratio;
    }

    public double getTunnelBuildSuccess() {
        long now = _context.clock().now();
        if (now - _cachedBuildSuccessTime < BUILD_SUCCESS_CACHE_MS) {
            return _cachedBuildSuccess;
        }
        // Single source of truth: see SystemVersion.getTunnelBuildSuccessRatio(). This used to
        // read getCurrentEventCount() off the ten minute rate, which is the current *partial*
        // period, so the value was a fraction of one bucket and drifted under the attack
        // threshold on noise - switching off profile eviction and lengthening ghost cooldowns
        // for as long as it held. Kept 1.0 for an absent or empty window, as before.
        double result = neutraliseUnknownBuildSuccess(
            SystemVersion.getTunnelBuildSuccessRatio(_context.statManager(),
                                                    SystemVersion.BUILD_SUCCESS_WINDOW_MS));
        _cachedBuildSuccess = result;
        _cachedBuildSuccessTime = now;
        return result;
    }

    /**
     * Whether recent tunnel build success is below the attack threshold.
     *
     * @return whether low build success
     */
    public boolean isLowBuildSuccess() {
        double buildSuccess = getTunnelBuildSuccess();
        return buildSuccess < ATTACK_THRESHOLD;
    }

    /**
     * Persist the given profile to disk.
     */
    public void writeProfile(PeerProfile profile) {
        _persistenceHelper.writeProfile(profile);
    }

    /**
     * Average peer test response time (ms) across fast peers with data,
     * computed during each {@link #reorganize()}.
     * Used by {@link PeerProfile#recalculateLowLatency()} as a self-tightening
     * low-latency cap (1× the cohort average) when the fast tier has ≥ 300 peers.
     * Falls back to a fixed 1.5× timeout cap for cold-start profiles.
     *
     * @return the average RTT in ms, 0 if fewer than 3 peers have data
     * @since 0.9.71+
     */
    public float getAverageLowLatencyRTT() {
        return _averageLowLatencyRTT;
    }

    /**
     * Compute average peer test response time across fast peers with data.
     * Pure decision — no write lock required; reads the already-rebuilt _fastPeers.
     *
     * @return the average RTT in ms, 0 if fewer than 3 peers have data
     * @since 0.9.71+
     */
    private float computeAverageLowLatencyRTT() {
        long totalRTT = 0;
        int count = 0;
        for (PeerProfile p : _fastPeers.values()) {
            float rtt = p.getPeerTestTimeAverage();
            if (rtt > 0) {
                totalRTT += rtt;
                count++;
            }
        }
        return count >= 3 ? (float) totalRTT / count : 0;
    }

}
