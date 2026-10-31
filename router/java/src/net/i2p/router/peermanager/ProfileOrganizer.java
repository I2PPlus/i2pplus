package net.i2p.router.peermanager;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
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
import net.i2p.stat.StatManager;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Categorizes peers into performance tiers (fast, high-capacity, well-integrated)
 * based on historical metrics from {@link PeerProfile}. Requires periodic reorganize() calls
 * to update peer classifications for optimal tunnel selection.
 * <p>
 * Tier assignment during reorganize():
 * 1. All profiles are iterated in capacity order (via {@link InverseCapacityComparator})
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
 * <p>
 * Concurrency: ReentrantReadWriteLock protects all tier maps.
 * reorganize() acquires the write lock; selection methods use the read lock.
 * {@link #getOrCreateProfileNonblocking} uses try-lock escalation so a read path never
 * blocks on the write lock.
 */
public class ProfileOrganizer {
    /** Threshold below which the network is considered under attack. */
    public static final double ATTACK_THRESHOLD = 0.40;
    /**
     * Eviction order: least recently active first, then lowest capacity.
     * <p>Activity is the newest of last-sent-successfully, last-heard-from and
     * last-heard-about, matching {@link #isStaleAbsentPeer}. Ties fall through to a
     * capacity comparison so two equally idle peers are separated on what else is
     * known about them rather than on map order.
     *
     * @since 0.9.71+
     */
    private static final Comparator<PeerProfile> STALENESS_THEN_CAPACITY =
        (a, b) -> {
            long actA = a.getLastSendSuccessful();
            long actB = b.getLastSendSuccessful();
            actA = Math.max(actA, Math.max(a.getLastHeardFrom(), a.getLastHeardAbout()));
            actB = Math.max(actB, Math.max(b.getLastHeardFrom(), b.getLastHeardAbout()));
            int byAge = Long.compare(actA, actB);
            if (byAge != 0) {return byAge;}
            return Double.compare(a.getCapacityValue(), b.getCapacityValue());
        };
    /** Logger for profile reorganization. */
    private final Log _log;
    /** The router context. */
    private final RouterContext _context;
    /** Profiles of peers fast enough for tunnels. */
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
    /**
     * Profiles rated by the last organize run.
     *
     * @see #_profileCount
     */
    private volatile int _qualityCount;
    /**
     * Profiles currently in use by peers.
     *
     * @see #_profileCount
     */
    private volatile int _activeProfileCount;

    /**
     * Profiles held in RAM, as of the last completed reorganisation.
     *
     * @return the in-RAM profile count
     * @since 0.9.72+
     */
    public int getProfileCount() {return _profileCount;}

    /**
     * Peers passing the quality gates, as of the last completed reorganisation.
     *
     * @return the quality peer count
     * @since 0.9.72+
     */
    public int getQualityCount() {return _qualityCount;}

    /**
     * Profiles with recent activity, as of the last completed reorganisation.
     * <p>Computed during the reorganisation walk rather than on demand:
     * {@link #countActivePeersInLastHour} iterates every profile while holding
     * _reorganizeLock, which is the lock tunnel peer selection contends on, and CoalesceStatsEvent
     * runs every 50s. Publishing it here costs one comparison per profile in a walk that already
     * holds the lock.
     *
     * @return the recently active profile count
     * @since 0.9.72+
     */
    public int getActiveProfileCount() {return _activeProfileCount;}

    /** Window used by {@link #getActiveProfileCount}, in ms. */
    private static final long ACTIVE_WINDOW_MS = 60 * 60 * 1000L;

    /**
     * Per-cycle bounds and counters for the promotion deferral on a stale
     * RouterInfo.  A cycle is opened by {@link #lockedRebuildTiers} and its
     * counters are published by {@link #recordRouterInfoRefreshStats} before the
     * reorg write lock is released.
     * @since 0.9.72+
     */
    private final RouterInfoRefresher _refresher = new RouterInfoRefresher();

    /**
     * Whether a profile shows activity within the active window: a peer test counted as active, a
     * successful or failed send, or any contact. Split out so the rule is testable without a
     * reorganise cycle or a live profile set.
     *
     * @param profile profile whose send and heard-from timestamps are compared against the cutoff
     * @param hideBefore cutoff time, i.e. now minus {@link #ACTIVE_WINDOW_MS}
     * @return true if the profile is recently active
     * @since 0.9.72+
     */
    static boolean isActiveInWindow(PeerProfile profile, long hideBefore) {
        return profile.getIsActive(ACTIVE_WINDOW_MS) ||
               profile.getLastSendSuccessful() >= hideBefore ||
               profile.getLastSendFailed() >= hideBefore ||
               profile.getLastHeardFrom() >= hideBefore;
    }

    /**
     * True when a peer in the fast tier has proven throughput or is in the high-cap tier.
     *
     * @param profile the fast-tier profile to test
     * @param highCap the high-capacity tier, for a membership test by peer hash
     * @return true if peak 1m throughput is above 0 KBps or the peer is in highCap
     */
    private static boolean isQualityFastPeer(PeerProfile profile, Map<Hash, PeerProfile> highCap) {
        return profile.getPeakTunnel1mThroughputKBps() > 0 ||
               highCap.containsKey(profile.getPeer());
    }

    /**
     * Add a peer to the fast tier, maintaining the quality counter.
     *
     * @param peer the peer's hash, the tier map key
     * @param profile the profile to store for that peer, replacing any earlier entry
     */
    private void putFastPeer(Hash peer, PeerProfile profile) {
        PeerProfile prev = _fastPeers.put(peer, profile);
        if (prev == null && isQualityFastPeer(profile, _highCapacityPeers))
            _fastQualityCount++;
    }

    /**
     * Remove a peer from the fast tier, maintaining the quality counter.
     *
     * @param peer the peer's hash, the tier map key
     * @return the profile stored for that peer, or null if it was not in the tier
     */
    private PeerProfile removeFastPeer(Hash peer) {
        PeerProfile removed = _fastPeers.remove(peer);
        if (removed != null && isQualityFastPeer(removed, _highCapacityPeers))
            _fastQualityCount--;
        return removed;
    }
    /** Profiles of peers with high capacity. */
    private final Map<Hash, PeerProfile> _highCapacityPeers;

    /**
     * Skip reasons from the promotion scan in flight, reported by
     * {@link #drainPromotionSkipTally}.
     * <p>Guarded by the write lock every caller already holds; see
     * {@link #recordPromotionSkip}.
     *
     * @since 0.9.71+
     */
    private final Map<String, Integer> _promotionSkipTally = new LinkedHashMap<>();

    /**
     * When {@link #promoteToFillTiers} last ran a full scan, in router time.
     *
     * @since 0.9.71+
     */
    private long _lastTierFillScan;

    /**
     * Whether either tier was under its target at the last tier-fill scan.
     * Widens the loss-readmit fast-track while short (see
     * {@link #lossyReadmitAllowed}); recomputed on every scan, so it lags a
     * demotion by at most the fill debounce.
     *
     * @since 0.9.71+
     */
    private volatile boolean _tiersShort;

    /**
     * Shortest gap between two full tier-refill scans.
     * <p>Well under the reorganise interval, so the debounce can never delay a
     * refill past the next cycle that would have done it anyway.
     *
     * @since 0.9.71+
     */
    static final long TIER_FILL_DEBOUNCE_MS = 5 * 1000L;
    /** Profiles of peers well integrated into the netdb. */
    private final Map<Hash, PeerProfile> _wellIntegratedPeers;
    /** Profiles of peers not currently failing. */
    private final Map<Hash, PeerProfile> _notFailingPeers;
    /**
     * Every tier this object retains, in a fixed order, so a pass that must read
     * the whole retained population walks one collection instead of repeating
     * itself per map.
     * <p>Assigned in the constructor once all four maps exist. The maps are
     * themselves final and are never replaced, so this never needs rebuilding.
     *
     * @since 0.9.71+
     */
    private final List<Map<Hash, PeerProfile>> _allTiers;
    /** Cached keys of the not-failing peers map. */
    private final List<Hash> _notFailingPeersList;
    /**
     * Strike count per peer for demoteIfUnreachable — only demote after
     * DEMOTE_STRIKE_THRESHOLD strikes within the decay window.
     */
    private final ConcurrentHashMap<Hash, Integer> _demoteStrikes = new ConcurrentHashMap<>(64);
    /** Last strike time per peer, so strikes expire after DEMOTE_STRIKE_DECAY_MS. */
    private final ConcurrentHashMap<Hash, Long> _demoteStrikeTimes = new ConcurrentHashMap<>(64);
    /** Unreachable first-hop failures needed inside the decay window before demoteIfUnreachable demotes. */
    public static final int DEMOTE_STRIKE_THRESHOLD = 3;
    /** A strike older than this no longer counts, so a peer that recovered is not demoted by one later failure. */
    public static final long DEMOTE_STRIKE_DECAY_MS = 10 * 60 * 1000L;

    /** Hash of this router. */
    private Hash _us;
    /** Saves and restores the peer profiles. */
    private final ProfilePersistenceHelper _persistenceHelper;
    /** All profiles ordered by inverse capacity. */
    private Set<PeerProfile> _strictCapacityOrder;
    /** Speed value a peer needs for the fast pool. */
    private double _thresholdSpeedValue;
    /** Capacity value a peer needs for the high-capacity pool. */
    private double _thresholdCapacityValue;
    /** Integration value a peer needs for the well-integrated pool. */
    private double _thresholdIntegrationValue;
    /** Round trip time peers are held to, in ms. */
    private double _thresholdRTT;
    /**
     * Fast-tier RTT boundary to floor the degraded-mode ceiling against, in ms.
     * <p>Re-anchored every {@link #BASELINE_REANCHOR_MS} on the reorganize pass
     * that measures a boundary, not captured once at startup. A single early
     * capture made the network condition of the router's first minutes the
     * permanent floor of the tightest ceiling a peer has to clear: the floor is
     * 2x this value, so a boundary of 200 ms observed at startup floored every
     * degraded ceiling at 400 ms for the life of the process.
     *
     * @see #BASELINE_REANCHOR_MS
     * @since 0.9.72+ (re-anchored)
     */
    private volatile double _baselineRTT;
    /**
     * When {@link #_baselineRTT} was last anchored, in ms since the epoch.
     *
     * @since 0.9.72+
     */
    private volatile long _baselineRTTTime;

    /**
     * How often the RTT baseline is re-anchored, in ms.
     * <p>A day. Long enough that the anti-amplification purpose survives — the
     * ceiling still must not chase a network that has become uniformly slow,
     * which is what the floor is for — and short enough that a condition
     * observed during startup stops binding a year later. The
     * {@link #AUTO_RTT_FLOOR_MS} and {@link #AUTO_RTT_CAP_MS} clamps stay in
     * front of it either way.
     *
     * @see #shouldReanchorBaseline(long, long)
     * @since 0.9.72+
     */
    static final long BASELINE_REANCHOR_MS = 24 * 60 * 60 * 1000L;

    /**
     * Whether the RTT baseline should be re-anchored now.
     * <p>Pure decision — no context access, safe for unit tests.
     *
     * @param baselineRTTTime when the baseline was last anchored, 0 if never
     * @param now current time in ms
     * @return true when there is no baseline yet, or the last one is older than
     *         {@link #BASELINE_REANCHOR_MS}
     * @since 0.9.72+
     */
    static boolean shouldReanchorBaseline(long baselineRTTTime, long now) {
        return baselineRTTTime <= 0 || now - baselineRTTTime > BASELINE_REANCHOR_MS;
    }
      /**
       * Calibrated cohort latency floor across fast peers with data, computed
       * during each reorganize by {@link #computeAverageLowLatencyRTT()} and
       * read through {@link #getAverageLowLatencyRTT}.
       * <p>Previously documented as tightening the low-latency threshold to 1.5x
       * the cohort average, but no caller ever consulted it: the value was
       * recomputed every cycle and discarded. It is now calibrated as the mean of
       * the fastest samples and applied as a second bound alongside the
       * per-transport floor.
       *
       * @since 0.9.71+
       */
    private volatile float _averageLowLatencyRTT;
    /** Comparator ordering profiles by inverse capacity. */
    private final InverseCapacityComparator _comp;

    /**
     * Calibrated latency floor per transport style, in ms, published by
     * {@link #sampleFirstHopRtts} once a style has enough samples. Absent
     * means that style has no calibrated opinion yet and the absolute rule
     * applies unchanged. Immutable after publication, so reads are lock-free.
     */
    private final Map<String, Long> _rttBaselines = new ConcurrentHashMap<>();

    /**
     * How many times the calibrated floor a peer's latency may sit under and
     * still count as low latency. Three is deliberately permissive: roughly half
     * of all peers fall within two times a top-50 floor, so this admits the fast
     * end of each transport's population and rejects the tail.
     */
    private static final int LOW_LATENCY_BASELINE_MULTIPLE_DEFAULT = 3;

    /** Property key for the calibrated low-latency multiple. @since 0.9.71+. */
    public static final String PROP_LOW_LATENCY_MULTIPLE =
        "i2p.router.profileOrganizer.lowLatencyBaselineMultiple";

    /**
     * How many times the calibrated floor a peer's latency may sit under and
     * still count as low latency, read once from
     * {@link #PROP_LOW_LATENCY_MULTIPLE}. Three is deliberately permissive:
     * roughly half of all peers fall within two times a top-50 floor, so this
     * admits the fast end of each transport's population and rejects the tail.
     * <p>Read at construction rather than per decision so the bar cannot shift
     * underneath a cycle, and held at a sane minimum so a misconfigured value
     * cannot make the floor vacuous.
     */
    private final int _lowLatencyBaselineMultiple;

    /**
     * Property key naming the fast tier's refill target.
     */
    public static final String PROP_MINIMUM_FAST_PEERS = "profileOrganizer.minFastPeers";
    /**
     * Network size above which tier thresholds scale with the network instead of
     * staying at their configured default.
     */
    static final int SCALING_THRESHOLD = 3000;
    /**
     * Fast tier refill target, in peers, as last configured or autotuned.
     */
    public static volatile int _defaultMinFastPeers = 1000;
    /**
     * Fast tier size the router refills toward.
     *
     * @return the current minimum number of fast-tier peers
     * @since 0.9.70+
     */
    public static int getDefaultMinFastPeers() { return _defaultMinFastPeers; }
    /**
     * Override the fast tier refill target, clamped to 50-2000 peers.
     *
     * @param val requested minimum fast tier size, in peers
     * @since 0.9.70+
     */
    public static void setDefaultMinFastPeers(int val) { _defaultMinFastPeers = Math.max(50, Math.min(2000, val)); }

    /**
     * Property key naming the RouterInfo age past which a RouterInfo is treated
     * as stale and must show proof of life to be selected, in hours.
     */
    public static final String PROP_MAX_ROUTERINFO_AGE_HOURS = "profileOrganizer.maxRouterInfoAgeHours";
    /**
     * Default RouterInfo age past which a RouterInfo is treated as stale and
     * must show proof of life to be selected, in hours.
     */
    public static final int DEFAULT_MAX_ROUTERINFO_AGE_HOURS = 2;
    /**
     * Property key naming the RouterInfo age past which a tier promotion is
     * held back, in ms.
     * <p>Set to 0 or less to disable the deferral entirely, which restores plain
     * promotion on whatever the netdb holds.
     *
     * @since 0.9.72+
     */
    public static final String PROP_ROUTERINFO_REFRESH_AGE_MS = "profileOrganizer.routerInfoRefreshAgeMs";
    /**
     * One hour, i.e. one republish interval.
     * @since 0.9.72+
     */
    public static final long DEFAULT_ROUTERINFO_REFRESH_AGE_MS = 60 * 60 * 1000L;
    /**
     * Property key naming how far ahead of the staleness bar a RouterInfo is
     * refreshed over the network, in ms.
     * <p>Set to 0 or less to disable the proactive refresh entirely. The deferral
     * it feeds is controlled separately, by
     * {@link #PROP_ROUTERINFO_REFRESH_AGE_MS}.
     *
     * @since 0.9.72+
     */
    public static final String PROP_ROUTERINFO_REFRESH_AHEAD_MS = "profileOrganizer.routerInfoRefreshAheadMs";
    /**
     * Grace period after startup before peers are penalized.
     */
    private static final long STARTUP_GRACE_PERIOD_MS = 10 * 60 * 1000L;
    /** Window for trusting a stale router info as proof of life. */
    private static final long PROOF_OF_LIFE_WINDOW_MS = 60 * 60 * 1000L;
    /**
     * Property key naming the fast tier's ceiling.
     */
    public static final String PROP_MAXIMUM_FAST_PEERS = "profileOrganizer.maxFastPeers";
    /**
     * Fast tier ceiling, in peers.
     */
    public static volatile int _defaultMaxFastPeers = 2000;
    /**
     * Ceiling on the fast tier.
     *
     * @return the current maximum number of fast-tier peers
     * @since 0.9.70+
     */
    public static int getDefaultMaxFastPeers() { return _defaultMaxFastPeers; }
    /**
     * Override the fast tier ceiling, clamped to 200-3000 peers.
     *
     * @param val requested maximum fast tier size, in peers
     * @since 0.9.70+
     */
    public static void setDefaultMaxFastPeers(int val) { _defaultMaxFastPeers = Math.max(200, Math.min(3000, val)); }

    /**
     * Property key naming the high-capacity tier's refill target.
     */
    public static final String PROP_MINIMUM_HIGH_CAPACITY_PEERS = "profileOrganizer.minHighCapacityPeers";
    /**
     * Default high-capacity tier refill target, in peers.
     */
    public static final int DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS = 1000;
    /**
     * High-capacity tier refill target, in peers, as last configured or autotuned.
     */
    public static volatile int _defaultMinHighCapPeers = DEFAULT_MINIMUM_HIGH_CAPACITY_PEERS;
    /**
     * High-capacity tier size the router refills toward.
     *
     * @return the current minimum number of high-capacity-tier peers
     * @since 0.9.70+
     */
    public static int getMinHighCapacityPeers() { return _defaultMinHighCapPeers; }
    /**
     * Override the high-capacity tier refill target, clamped to 50-3000 peers.
     *
     * @param val requested minimum high-capacity tier size, in peers
     * @since 0.9.70+
     */
    public static void setMinHighCapacityPeers(int val) { _defaultMinHighCapPeers = Math.max(50, Math.min(3000, val)); }
    /**
     * Property key naming the high-capacity tier's ceiling.
     */
    public static final String PROP_MAXIMUM_HIGH_CAPACITY_PEERS = "profileOrganizer.maxHighCapacityPeers";
    /**
     * High-capacity tier ceiling, in peers.
     */
    public static volatile int _defaultMaxHighCapPeers = 3000;
    /**
     * Ceiling on the high-capacity tier.
     *
     * @return the current maximum number of high-capacity-tier peers
     * @since 0.9.70+
     */
    public static int getDefaultMaxHighCapPeers() { return _defaultMaxHighCapPeers; }
    /**
     * Override the high-capacity tier ceiling, clamped to 200-6000 peers.
     *
     * @param val requested maximum high-capacity tier size, in peers
     * @since 0.9.70+
     */
    public static void setDefaultMaxHighCapPeers(int val) { _defaultMaxHighCapPeers = Math.max(200, Math.min(6000, val)); }

    /** Minimum tunnel acceptance ratio (40%) to remain in high-capacity/fast tiers. */
    private static final double MIN_TUNNEL_ACCEPTANCE_RATIO = 0.4;
    /**
     * Lifetime requests a peer needs before its acceptance ratio may exclude it.
     * <p>Package-visible because the tiers apply different floors to it and the
     * boundary between "judged" and "not judged" is exactly the difference. The
     * high-capacity tier passes this value and the fast tier passes one, so the
     * same counts give opposite answers on the two. {@code TierAdmissionFloorTest}
     * pins that boundary.
     *
     * @since 0.9.70+
     */
    static final int MIN_TUNNEL_REQUESTS = 50;

    /**
     * Lifetime requests a peer needs before its acceptance ratio may exclude it
     * from the <em>fast</em> tier, in admission, eviction and restoration alike.
     * <p>Five, not one. The fast tier used to pass one on all three paths, which
     * made eviction judge on a single sample while high-capacity admission
     * deliberately demanded fifty: one declined request and no accepted one is a
     * ratio of 0.00, under the 0.20 small-sample bar, so a peer was removed from
     * both tiers for one refusal - and on a router whose build success was 52%,
     * refusals are the commonest outcome of asking anybody anything. Five is
     * enough to tell a non-accepter from a peer we happened to ask once.
     * <p>Below {@link #MIN_TUNNEL_REQUESTS} the bar stays the loose 0.20, so
     * raising the floor does not tighten what a judged peer has to meet; it only
     * stops the unjudged being judged.
     *
     * @since 0.9.72+
     */
    static final int FAST_ACCEPTANCE_SAMPLES = 5;

    /**
     * How long a peer's accept/reject record stays evidence about the peer.
     * <p>The lifetime counts are decayed by a fixed fraction every 8 hours
     * ({@link TunnelHistory#decayLifetimeCounters(long)}), which scales the
     * numerator and the denominator of the acceptance ratio by the same factor:
     * the ratio is invariant under its own decay. Truncation removes at most one
     * from each side, proportionally more from the smaller side, so a ratio below
     * the bar drifts <em>down</em> - 4 agreed / 6 rejected is 0.400, then 0.428,
     * 0.333, 0.500, and ends at 0 agreed / 1 rejected = 0.0. The only thing that
     * raises it is new agreements, and those require the peer to be selected,
     * which requires tier membership. A peer once below the bar therefore stays
     * below it for the rest of the router's life: the ratchet behind the tier
     * decline.
     * <p>A week, because that is the horizon over which a peer's own capacity and
     * configuration plausibly change, and it is long enough that a peer we are
     * actively building through - whose evidence is refreshed by definition - is
     * judged at full strength.
     *
     * @see #acceptanceEvidenceExpired(long, long)
     * @since 0.9.72+
     */
    static final long ACCEPTANCE_EVIDENCE_TTL_MS = 7 * 24 * 60 * 60 * 1000L;

    /**
     * Whether a peer's accept/reject record is too old to be evidence about the
     * peer, judged from the last time it took part in a build decision.
     * <p>A peer that has never been asked ({@code lastBuildActivity == 0}) is not
     * expired: it has no record to be stale, and the zero-history path in
     * {@link #isLowTunnelAcceptance} handles it.
     * <p>Pure decision - no context access, safe for unit tests.
     *
     * @param lastBuildActivity when the peer last agreed, refused, timed out or
     *                          had a joined tunnel fail, from
     *                          {@link TunnelHistory#getLastBuildActivity()}; 0 if never
     * @param now current time in ms
     * @return true when the ratio built from the lifetime counts says nothing
     *         about the peer today
     * @since 0.9.72+
     */
    static boolean acceptanceEvidenceExpired(long lastBuildActivity, long now) {
        return lastBuildActivity > 0 && now - lastBuildActivity > ACCEPTANCE_EVIDENCE_TTL_MS;
    }
    /** Cooldown period (ms) after demotion before peer can be re-promoted. */
    private static final long TUNNEL_DEMOTION_COOLDOWN_MS = 10 * 60 * 1000L; // 10 minutes
    /**
     * Exclude peers from tunnel selection after this many cumulative failures
     * OR when the lifetime failure ratio exceeds {@link #MAX_LIFETIME_FAILURE_RATIO},
     * whichever is stricter.  The hard cap is high (50) to avoid permanently
     * excluding long-lived peers that accumulated failures over days but are
     * currently healthy.  Between {@link #SOFT_FAILURE_PENALTY_THRESHOLD} (20)
     * and this cap, peers are penalized (lower selection priority) but not
     * excluded — a lightweight exponential backoff that still allows recovery.
     *
     * @since 0.9.71+ (raised from 20)
     */
    private static final long MAX_LIFETIME_TUNNEL_FAILURES = 50;
    /**
     * Failure count above which peers receive a selection priority penalty
     * but are not excluded.  Between this and {@link #MAX_LIFETIME_TUNNEL_FAILURES},
     * peers are deprioritized rather than banned.
     * @since 0.9.71+
     */
    private static final long SOFT_FAILURE_PENALTY_THRESHOLD = 20;
    /**
     * Maximum lifetime failure ratio (failed / (agreed + failed)) before a peer
     * is excluded.  A peer with 50%+ failure rate is unreliable regardless of
     * absolute count.  Combined with {@link #MAX_LIFETIME_TUNNEL_FAILURES},
     * this prevents long-lived peers with terrible records from staying selectable.
     * @since 0.9.71+
     */
    private static final double MAX_LIFETIME_FAILURE_RATIO = 0.50;

    /**
     * The population's own build-failure rate, used as the prior when shrinking a
     * peer's lifetime failure ratio toward the network's behaviour.
     * <p>0.45, from the same observation that set {@link #ATTACK_THRESHOLD} at
     * 0.40 build success: a healthy router sees roughly half of its build requests
     * fail, for reasons that have nothing to do with any particular peer - the
     * other four hops, the clock, the transport. A peer's ratio is only
     * informative to the extent it departs from that.
     *
     * @see #isExcessiveLifetimeFailure(PeerProfile, long)
     * @since 0.9.72+
     */
    private static final double POPULATION_FAILURE_RATIO = 0.45;

    /**
     * Weight of the population prior above, in pseudo-counts, when shrinking a
     * peer's failure ratio.
     * <p>40 requests: a peer with 40 or more of its own samples is judged by its
     * own record, a peer with 3 is judged almost entirely by the population. High
     * enough that a genuine non-performer is still excluded - 60 failures out of
     * 100 shrinks to 0.56, over the bar - and low enough that a peer with two
     * failures out of three is not.
     *
     * @see #isExcessiveLifetimeFailure(PeerProfile, long)
     * @since 0.9.72+
     */
    private static final double FAILURE_PRIOR_STRENGTH = 40.0;

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
    private static final int MIN_FAST_QUALITY_COUNT = 500;

    /**
     * Whether high-capacity <em>admission</em> requires acceptable tunnel
     * acceptance. False: high capacity is the reservoir the fast tier is seeded
     * from, so requiring acceptance here would filter it by the same criterion
     * that empties the fast tier, leaving nothing to seed from.
     *
     * @since 0.9.71+
     */
    static final boolean HIGH_CAP_ADMISSION_REQUIRES_ACCEPTANCE = false;

    /**
     * Whether fast-tier admission requires acceptable tunnel acceptance. True:
     * the fast tier is the selective tier, and a peer that declines most of our
     * build requests is wasted work wherever it is listed.
     *
     * @since 0.9.71+
     */
    static final boolean FAST_ADMISSION_REQUIRES_ACCEPTANCE = true;

    /**
     * Whether acceptance-based eviction applies to both tiers. True: admission
     * is permissive and eviction is what keeps poor accepters from
     * accumulating, so the two must disagree on admission and agree on eviction.
     *
     * @since 0.9.71+
     */
    static final boolean EVICTION_REQUIRES_ACCEPTANCE_BOTH_TIERS = true;

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
    /** Default packet loss marking a peer moderately lossy. */
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
     * Longest a peer stays in loss probation when the transport has said nothing
     * about it in the meantime.
     * <p>Probation is cleared by a fresh transport report below the demotion
     * threshold, and a peer outside the tiers is offered no traffic, so it
     * generates no packets, so the transport reports nothing, so the probation
     * never clears. The score that caused the demotion decays to effectively zero
     * in four hours ({@link PeerProfile#LOSS_DECAY_HALF_LIFE_MS}, 16 half-lives),
     * so the evidence that made the peer a probationer is gone long before the
     * probation ends. Sixty minutes is six times
     * {@link #LOSS_READMIT_MIN_AGE} and fifteen loss-score half-lives: a peer that
     * is genuinely still lossy is re-demoted by the next report, immediately after
     * readmission, so the bound costs nothing on a real offender and removes a
     * permanent lock on everyone else.
     *
     * @see #lossProbationExpired(long, long)
     * @since 0.9.72+
     */
    private static final long LOSS_PROBATION_MAX_MS = 60 * 60 * 1000L;

    /**
     * Whether a loss probation has run past {@link #LOSS_PROBATION_MAX_MS} with
     * the transport reporting nothing.
     * <p>Pure decision - no context access, safe for unit tests.
     *
     * @param lossySince when the peer was demoted for loss, 0 if never
     * @param now current time in ms
     * @return true when the probation has expired on the clock
     * @since 0.9.72+
     */
    static boolean lossProbationExpired(long lossySince, long now) {
        return lossySince > 0 && now - lossySince >= LOSS_PROBATION_MAX_MS;
    }

    /**
     * Fast-track readmission: a fresh loss report below this ratio clears the
     * minimum age even when the tiers are full. Near-spotless evidence is
     * proof enough of recovery. When either tier is under target the
     * fast-track widens to any fresh below-threshold report (see
     * {@link #lossyReadmitAllowed}): a short tier cannot afford to hold a
     * peer out for a clean-but-not-spotless measurement.
     * @since 0.9.71+
     */
    private static final float LOSS_FAST_TRACK_RATIO = 0.05f;
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

    /**
     * Config property: hours a profile whose peer has never accepted or rejected
     * a build for us is retained. Zero or unset selects the store-size ladder in
     * {@link #untrackedExpiryMs(int, long)}.
     *
     * @since 0.9.72+
     */
    public static final String PROP_EXPIRE_UNTRACKED_HOURS = "profileOrganizer.expireUntrackedHours";
    /**
     * Config property: hours a profile with no send and no contact is retained.
     *
     * @since 0.9.72+
     */
    public static final String PROP_EXPIRE_GOSSIP_HOURS = "profileOrganizer.expireGossipHours";
    /**
     * Config property: hours a profile we have heard from, or heard about, is
     * retained.
     *
     * @since 0.9.72+
     */
    public static final String PROP_EXPIRE_PASSIVE_HOURS = "profileOrganizer.expirePassiveHours";
    /**
     * Config property: hours a profile we have successfully sent to is retained.
     *
     * @since 0.9.72+
     */
    public static final String PROP_EXPIRE_ACTIVE_HOURS = "profileOrganizer.expireActiveHours";

    /** Default retention for a peer we have sent to, in hours. */
    static final long DEFAULT_EXPIRE_ACTIVE_HOURS = 14;
    /** Default retention for a peer we have heard from or heard about, in hours. */
    static final long DEFAULT_EXPIRE_PASSIVE_HOURS = 7 * 24;
    /** Default retention for a peer we have only ever heard about nothing of, in hours. */
    static final long DEFAULT_EXPIRE_GOSSIP_HOURS = 72;
    /**
     * Default for the untracked window: unset, so the store-size ladder governs.
     *
     * @see #untrackedExpiryMs(int, long)
     */
    static final long DEFAULT_EXPIRE_UNTRACKED_HOURS = 0;

    /** One hour, in milliseconds. */
    private static final long ONE_HOUR_MS = 60 * 60 * 1000L;

    /**
     * Hours to milliseconds, saturating rather than overflowing.
     * <p>An operator can write any long into a config property, and
     * {@code hours * 3600000} overflows past 7.3e11 hours into negative
     * territory, which would expire every profile on every pass.
     *
     * @param hours the configured window in hours
     * @return the window in ms, {@link Long#MAX_VALUE} for a value that cannot be
     *         represented, and zero for a non-positive one
     * @since 0.9.72+
     */
    static long hoursToMs(long hours) {
        if (hours <= 0) {return 0;}
        if (hours > Long.MAX_VALUE / ONE_HOUR_MS) {return Long.MAX_VALUE;}
        return hours * ONE_HOUR_MS;
    }

    /**
     * How long a profile with no tunnel history is kept in memory.
     * <p>A peer with no tunnel history is the <em>normal</em> state of every
     * candidate the tier fill passes want to use, so this window sets how deep the
     * reservoir of unjudged peers is. It used to shrink as the store grew - one
     * hour once the store passed 4,000 profiles - which is backwards for the
     * purpose: a large store is the situation in which retention should be
     * <em>longer</em>, because it means we know more of the network and the peers
     * in the tail are the fresh candidates a starved tier needs. Deleting them
     * produced the churn this is measured on: a profile is dropped, recreated on
     * demand with an empty history, and then judged as "no evidence".
     * <p>Whether a peer is dead is answered honestly by the netdb - a peer absent
     * from the network database for over an hour is evicted by
     * {@link #isStaleAbsentPeer} - so the clock here only needs to bound memory,
     * and the memory bound is the profile cap.
     * <p>Pure decision - no context access, safe for unit tests.
     *
     * @param storedProfiles how many profiles the store holds
     * @param configuredHours an operator override in hours; non-positive selects
     *                        the ladder
     * @return the window in ms
     * @since 0.9.72+
     */
    static long untrackedExpiryMs(int storedProfiles, long configuredHours) {
        if (configuredHours > 0) {return hoursToMs(configuredHours);}
        // 24 hours once the store is large enough to be carrying the network's
        // X/P/O population, 12 below that, where a stale peer is cheap to drop
        // and cheap to relearn.
        return (storedProfiles >= 4000 ? 24 : 12) * ONE_HOUR_MS;
    }

    /** Runtime-adjustable default max profile count. */
    public static volatile int _defaultMaxProfiles = getDefaultMaxProfiles();
    /**
     * Profile count the reorganizer targets by default.
     *
     * @return the current default maximum number of stored profiles
     * @since 0.9.70+
     */
    public static int getDefaultMaxProfilesValue() { return _defaultMaxProfiles; }
    /**
     * Override the default profile count, clamped to
     * {@link #MIN_MAX_PROFILES}-{@link #ABSOLUTE_MAX_PROFILES}.
     *
     * @param val requested maximum profile count
     * @since 0.9.70+
     */
    public static void setDefaultMaxProfiles(int val) { _defaultMaxProfiles = Math.max(MIN_MAX_PROFILES, Math.min(ABSOLUTE_MAX_PROFILES, val)); }
    /**
     * Ceiling on profiles held in memory, in profiles.
     * <p>Bounds the RAM store only. What is kept <em>on disk</em> is governed
     * separately by {@link #MAX_STORED_PROFILE_FILES}, so raising this to let the
     * router use more of its persisted knowledge does not shorten the retention of
     * that knowledge across a restart.
     * <p>This is the hard upper bound of the clamp, not the operating value. The
     * default is sized from the heap by {@link #maxProfilesForHeap(long)}, so a
     * small-heap router never sees this number; it is reachable only on a router
     * with several gigabytes of heap, and by an operator setting
     * {@link #PROP_MAX_PROFILES}.
     */
    public static final int ABSOLUTE_MAX_PROFILES = 16000;
    /**
     * Profile files retained on disk before surplus ones are trimmed.
     * <p>Deliberately a different number, and a different concern, from
     * {@link #ABSOLUTE_MAX_PROFILES}: that one bounds profiles held in memory,
     * this one bounds what is kept on disk so it survives a restart. Using one
     * constant for both meant that lowering the disk floor would silently lower
     * the memory ceiling too.
     * <p>Below this count no file is deleted unless the peer is genuinely stale,
     * so the floor costs nothing while the store is smaller than it and only
     * starts trimming once the store exceeds it.
     * <p>Set above {@link #ABSOLUTE_MAX_PROFILES} on purpose: a profile evicted
     * from the RAM store to stay under the memory ceiling keeps its file, so the
     * history we accumulated on it survives and it is not rebuilt from zero when
     * it is needed again.
     *
     * @since 0.9.71+
     */
    public static final int MAX_STORED_PROFILE_FILES = 20000;
    /**
     * Floor for the in-memory profile cap, in profiles.
     * <p>Low enough that a small-heap router still runs; {@link #getDefaultMaxProfiles}
     * returns this instead of {@link #ABSOLUTE_MAX_PROFILES} on a slow system.
     */
    public static final int MIN_MAX_PROFILES = 800;

    /**
     * Approximate heap cost of one stored profile, in bytes.
     * <p>A profile is a few hundred bytes of fields, but the tunnel history it
     * carries is ten-odd {@link net.i2p.stat.RateStat}s, each with a
     * {@link net.i2p.stat.Rate} per period, and those dominate. Ten kilobytes is
     * the measured order of magnitude of an expanded profile, and the sizing
     * below is only meant to be within a factor of two of right.
     *
     * @see #maxProfilesForHeap(long)
     * @since 0.9.72+
     */
    static final int PROFILE_HEAP_BYTES = 10 * 1024;

    /**
     * Share of the maximum heap the profile store may occupy, in percent.
     * <p>Small, because the profile store competes with the lease sets, the
     * netdb, the tunnel pools and the streaming buffers, and it is the least
     * latency-sensitive of them.
     *
     * @see #maxProfilesForHeap(long)
     * @since 0.9.72+
     */
    static final int PROFILE_HEAP_PERCENT = 6;

    /**
     * Sizes the in-memory profile cap from the heap rather than from a peer
     * count.
     * <p>A count-derived cap is a guess about memory: it is set for the router
     * that was tested, and every other router pays — too low on a big heap, so
     * the tiers are starved of the profiles they need to fill to their minimum,
     * and too high on a small one, so the store pressures the GC. Free heap is
     * the quantity the cap is actually protecting, so read it from there.
     * <p>The result is clamped into {@link #MIN_MAX_PROFILES}-{@link
     * #ABSOLUTE_MAX_PROFILES}, with {@link #MIN_MAX_PROFILES} as the floor even
     * on a tiny heap: a store that small cannot fill a tier, and the router
     * needs the floor to have any peer knowledge at all.
     * <p>Pure decision — no context access, safe for unit tests.
     *
     * @param maxMemoryBytes the maximum heap in bytes, from
     *                       {@link SystemVersion#getMaxMemory()}
     * @return the profile count that fits the heap, never below
     *         {@link #MIN_MAX_PROFILES} and never above
     *         {@link #ABSOLUTE_MAX_PROFILES}
     * @since 0.9.72+
     */
    static int maxProfilesForHeap(long maxMemoryBytes) {
        if (maxMemoryBytes <= 0) {return MIN_MAX_PROFILES;}
        long byHeap = maxMemoryBytes / PROFILE_HEAP_BYTES * PROFILE_HEAP_PERCENT / 100;
        // 2000 is the practical floor for a router that is not classed as slow:
        // below it the tier minimums cannot be met from the store at all.
        long chosen = Math.max(2000, byHeap);
        return (int) Math.max(MIN_MAX_PROFILES, Math.min(ABSOLUTE_MAX_PROFILES, chosen));
    }

    /**
     * Returns the default profile count from the config.
     *
     * @return the maximum number of profiles
     */
    private static int getDefaultMaxProfiles() {
        if (SystemVersion.isSlow()) return MIN_MAX_PROFILES;
        return maxProfilesForHeap(SystemVersion.getMaxMemory());
    }

    /** Rate stat periods for the profile statistics. */
    private static final long[] RATES = {
        RateConstants.ONE_MINUTE,
        RateConstants.FIVE_MINUTES,
        RateConstants.TEN_MINUTES,
        RateConstants.ONE_HOUR
    };

    /** Lock guarding the profile maps during reorganization. */
    private final ReentrantReadWriteLock _reorganizeLock = new ReentrantReadWriteLock(false);

    /**
     * Timestamp of the last starved-tier warning; selections hold only the read
     * lock, so this is read and written concurrently.
     *
     * @since 0.9.71+
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
          _lowLatencyBaselineMultiple = Math.max(1, context.getProperty(
                  PROP_LOW_LATENCY_MULTIPLE, LOW_LATENCY_BASELINE_MULTIPLE_DEFAULT));
          _comp = new InverseCapacityComparator();
        _fastPeers = new HashMap<>(1024);
        _highCapacityPeers = new HashMap<>(1024);
        _notFailingPeersList = new ArrayList<>(4096);
        _notFailingPeers = new HashMap<>(4096);
        _persistenceHelper = new ProfilePersistenceHelper(_context);
        _strictCapacityOrder = new TreeSet<>(_comp);
        _wellIntegratedPeers = new HashMap<>(1024);
        _allTiers = Arrays.asList(_fastPeers, _highCapacityPeers,
                                  _wellIntegratedPeers, _notFailingPeers);

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
        // Immediate tier evictions with no strike accumulation. Retired: the two
        // callers that reached demoteIfUnreachableNow() now go through the strike
        // accounting (BuildExecutor) or record only a failed first hop (the
        // pre-connect probe sweep), so nothing evicts on a single event. The stat
        // is left registered so a reader of an old graph sees the name and the
        // zero, and the zero is the honest reading.
        _context.statManager().createRequiredRateStat("tunnel.firstHopUnreachableDemoted",
                "Peers evicted from the selection tiers as unreachable, with no strike accumulation",
                "Tunnels [Participating]", RATES);
        // Profile files held on disk. Fed from CoalesceStatsEvent on its short cycle,
        // from the count the persistence helper tracks, so it rises as profiles are
        // stored and falls only when the retention rules delete a file.
        _context.statManager().createRequiredRateStat("peer.storedProfileCount", "Profile files retained on disk", "Peers", RATES);
        // Reorganize passes that gave up rather than block on the write lock. Sustained
        // non-zero means reorganize is contending and tier decisions are being skipped.
        _context.statManager().createRequiredRateStat("peer.reorganizeLockFailures", "Reorganize passes skipped on lock contention", "Peers", RATES);
        // Promotion deferral on a stale RouterInfo. peer.routerInfoRefreshNeeded and
        // peer.promotedStaleRouterInfo are the hypothesis counters: together they
        // say how many promotions are being held, and how many are going ahead on a
        // RouterInfo already known to be stale. peer.routerInfoProactiveRefresh is
        // the response: the lookups issued to keep a RouterInfo from reaching the
        // bar at all, and peer.routerInfoRefreshDropped is the ones the queue cap
        // refused. Read the three together — a needed population that falls as the
        // refresh count rises is the mechanism working.
        _context.statManager().createRequiredRateStat("peer.routerInfoRefreshNeeded",
                "Tier promotions held back for a stale RouterInfo (both tiers counted)", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.promotedStaleRouterInfo",
                "Tier promotions accepted on a stale RouterInfo by the starvation guard", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.routerInfoProactiveRefresh",
                "RouterInfo refresh searches issued ahead of the staleness bar", "Peers", RATES);
        _context.statManager().createRequiredRateStat("peer.routerInfoRefreshDropped",
                "RouterInfo refreshes refused by the queue cap", "Peers", RATES);
    }

    /**
     * Check if current router is firewalled to adjust peer selection thresholds.
     * @return true if the comm system reports FIREWALLED or REJECT_UNSOLICITED
     */
    private boolean isFirewalled() {
            CommSystemFacade.Status status = _context.commSystem().getStatus();
            return status != null && (status.toString().contains("FIREWALLED") ||
                                    status.toString().contains("REJECT_UNSOLICITED"));
        }

      /** Takes the reorganization read lock. */
      private void getReadLock() {_reorganizeLock.readLock().lock();}
    /**
     * Takes the read lock if it is free.
     *
     * @return true if the lock was taken
     */
    private boolean tryReadLock() {return _reorganizeLock.readLock().tryLock();}
    /** Releases the reorganization read lock. */
    private void releaseReadLock() {_reorganizeLock.readLock().unlock();}
    /**
     * Takes the write lock if it is free.
     *
     * @return true if the lock was taken
     */
    private boolean tryWriteLock() {return _reorganizeLock.writeLock().tryLock();}
    /**
     * Takes the reorganization write lock, waiting if needed.
     *
     * @return true if the lock was taken
     */
    private boolean getWriteLock() {
        try {
            boolean rv = _reorganizeLock.writeLock().tryLock(3000, TimeUnit.MILLISECONDS);
            if (!rv && _log.shouldWarn()) {
                _log.warn("Could not acquire the reorganize write lock, queue length: " + _reorganizeLock.getQueueLength());
            }
            return rv;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    /** Releases the reorganization write lock. */
    private void releaseWriteLock() {_reorganizeLock.writeLock().unlock();}

    /**
     * Store the local router hash for self-exclusion from peer selection.
     *
     * @param us our own router hash
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
        // A profile loaded from disk arrives with both the latency measurement and
        // the low-latency flag that measurement produced, but the two were written
        // at different moments: the flag is a snapshot from the last time
        // recalculateLowLatency() ran, which may be hours before the RTT it was
        // derived from was written. Re-derive it from the loaded RTT so the two
        // agree at load time.
        //
        // recalculateLowLatency() deliberately leaves the flag alone when the RTT
        // has aged past its validity window, so this cannot clear a flag on the
        // strength of a stale measurement - a profile that was low latency and no
        // longer measurable keeps the verdict it was last given, which is the same
        // reason the flag is persisted at all: to seed the fast tier before this
        // router has measured anything itself.
        //
        // No calibrated floor is passed: at this point in startup the transport
        // baselines describe the *previous* run, not this router's current links,
        // and mixing the two would judge a freshly-loaded profile against a
        // population it was not part of. The absolute rule applies until the first
        // sampling cycle of this run publishes fresh baselines.
        profile.recalculateLowLatency();
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

    /**
     * Counts the profiles in one pool map.
     *
     * @param m the pool map
     * @return the number of profiles
     */
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

    /**
     * Checks whether a peer is in one pool map.
     *
     * @param m the pool map
     * @param peer the peer hash
     * @return true if the peer is in the map
     */
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
     * The count gates fast-tier promotion internally: at or above
     * MIN_FAST_QUALITY_COUNT (500) the tier is treated as healthy.
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

    /** Bad netdb replies per hour before a peer is penalized. */
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
     * How many slots are still free in a cascade's destination set.
     * <p>Every tier selector takes {@code howMany} as the <em>total</em> the caller wants
     * held in {@code matches} when the cascade returns, and every tier — the first one
     * included — asks for only the shortfall this reports. That single convention is what
     * keeps the arithmetic in one place: a call site that pre-subtracts
     * {@code matches.size()} itself is a second copy of this computation, and the two
     * copies disagree the moment one of them drifts.
     * <p>The failure mode that makes it worth centralising: a caller that sized an
     * {@link net.i2p.util.ArraySet} to exactly {@code howMany} has already filled part of
     * it by the time a later tier runs, so a tier that wrote {@code howMany} again would
     * overflow it and {@link net.i2p.util.ArraySet} would throw {@code SetFullException} — surfaced as
     * {@code pickLowestPriority} crashing, which names the symptom rather than the
     * cascade arithmetic behind it.
     *
     * @param howMany total peers the caller wants held in matches across the whole cascade
     * @param matches peers picked so far, by the caller and by the tiers that already ran
     * @return slots still needed, never negative
     * @since 0.9.71+
     */
    static int remainingToSelect(int howMany, Set<Hash> matches) {
        return Math.max(0, howMany - matches.size());
    }

    /**
     * Whether a cascade still has slots left to fill.
     * <p>A guard, not a computation the callers should repeat: every tier-selector
     * shortfall branch needs the same {@code remaining > 0} question, and writing it as
     * {@code matches.size() < howMany} in ten places is what let one site drift.
     *
     * @param howMany peers the caller asked for in total
     * @param matches peers picked by the tiers that ran before this one
     * @return true if the set still needs more peers
     * @since 0.9.71+
     */
    static boolean stillSelecting(int howMany, Set<Hash> matches) {
        return remainingToSelect(howMany, matches) > 0;
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
        try {lockedSelectPeers(_fastPeers, remainingToSelect(howMany, matches), exclude, matches, mask, ipSet, buildSuccess, computeAdaptiveRttCeiling(_thresholdRTT, buildSuccess));}
        finally {releaseReadLock(); flushSelectionCounters();}
        if (stillSelecting(howMany, matches)) {
            if (_log.shouldDebug()) {
                _log.debug("Need " + howMany + " Fast peers in tier -> " + matches.size() +
                           " found, selecting remainder from High Capacity tier...");
            }
            selectHighCapacityPeers(remainingToSelect(howMany, matches), exclude, matches, mask, ipSet);
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
                lockedSelectPeers(_fastPeers, remainingToSelect(howMany, matches), exclude, matches, randomKey, subTierMode, mask, ipSet, buildSuccess, rttCeiling);
            else
                lockedSelectPeers(_fastPeers, remainingToSelect(howMany, matches), exclude, matches, mask, ipSet, buildSuccess, rttCeiling);
        } finally {releaseReadLock();}
        if (stillSelecting(howMany, matches)) {
            if (_log.shouldDebug())
                _log.debug("Need " + howMany + " Fast peers in tier -> " + matches.size() +
                           " found, selecting remainder from High Capacity tier...");
            selectHighCapacityPeers(remainingToSelect(howMany, matches), exclude, matches, mask, ipSet);
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
        /** All peers. */
        SLICE_ALL(0x00, 0),
        /** Peers in sub-tier 0 or 1. */
        SLICE_0_1(0x02, 0),
        /** Peers in sub-tier 2 or 3. */
        SLICE_2_3(0x02, 2),
        /** Peers in sub-tier 0. */
        SLICE_0(0x03, 0),
        /** Peers in sub-tier 1. */
        SLICE_1(0x03, 1),
        /** Peers in sub-tier 2. */
        SLICE_2(0x03, 2),
        /** Peers in sub-tier 3. */
        SLICE_3(0x03, 3);
        /** Selection mask. */
        final int mask;
        /** Selection value. */
        final int val;
        /**
         * Creates one masked slice.
         *
         * @param mask the bits to match
         * @param val the value those bits must have
         */
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
             lockedSelectPeers(_highCapacityPeers, remainingToSelect(howMany, matches), exclude, matches, mask, ipSet, buildSuccess, rttCeiling);
         } finally {releaseReadLock(); flushSelectionCounters();}
        if (stillSelecting(howMany, matches)) {
            if (_log.shouldDebug()) {
                _log.debug("Need " + (howMany > 1 ? "High Capacity peers" : "High Capacity peer") +
                           " in tier -> " + matches.size() + " found, selecting from non-failing peers...");
            }
            selectNotFailingPeers(remainingToSelect(howMany, matches), exclude, matches, mask, ipSet);
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
     *                       test history so they accumulate profiling data
     */
    public void selectNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing,
                                    int mask, MaskedIPSet ipSet, boolean preferUnproven) {
        if (stillSelecting(howMany, matches)) {
            selectAllNotFailingPeers(remainingToSelect(howMany, matches), exclude, matches, onlyNotFailing, mask, ipSet,
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
        if (stillSelecting(howMany, matches)) {
            List<Hash> connected = _context.commSystem().getEstablished();
            if (connected != null && !connected.isEmpty()) {
                double buildSuccess = getTunnelBuildSuccess();
                getReadLock();
                try {lockedSelectActive(connected, remainingToSelect(howMany, matches), exclude, matches, mask, ipSet, buildSuccess);}
                finally {releaseReadLock(); flushSelectionCounters();}
            }
        }
    }

    /**
     * Non-blocking test peer selection: tries the full fast → high-cap →
     * active chain under a single {@link #tryReadLock}.  Returns false
     * (leaving {@code matches} unchanged) if the read lock cannot be
     * acquired. The caller should skip this round and requeue.
     * Used exclusively by {@link PeerTestJob} to avoid blocking on
     * reorganize()'s write lock during startup profiling.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude
     * @param matches output set populated with selected peer hashes
     * @return true if selection ran, false if lock was not acquired
     * @since 0.9.71+
     */
    public boolean selectTestPeersNonBlocking(int howMany, Set<Hash> exclude, Set<Hash> matches) {
        if (!tryReadLock()) return false;
        try {
            double buildSuccess = getTunnelBuildSuccess();
            long rttCeiling = computeAdaptiveRttCeiling(_thresholdRTT, buildSuccess);
            lockedSelectPeers(_fastPeers, remainingToSelect(howMany, matches), exclude, matches, 0, null, buildSuccess, rttCeiling);
            if (stillSelecting(howMany, matches)) {
                long cap = rttCeiling * 2;
                long hcRtt = Math.min(cap, AUTO_RTT_CAP_MS);
                lockedSelectPeers(_highCapacityPeers, remainingToSelect(howMany, matches), exclude, matches, 0, null, buildSuccess, hcRtt);
            }
            if (stillSelecting(howMany, matches)) {
                List<Hash> connected = _context.commSystem().getEstablished();
                if (connected != null && !connected.isEmpty()) {
                    lockedSelectActive(connected, remainingToSelect(howMany, matches), exclude, matches, 0, null, buildSuccess);
                }
            }
            return true;
        } finally {releaseReadLock(); flushSelectionCounters();}
    }

    /**
     * Select up to howMany peers from O/P/X bandwidth tiers (high shared bandwidth)
     * that are not failing.  Falls through to selectAllNotFailingPeers on shortfall.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
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
        if (stillSelecting(howMany, matches)) {
            selectHighBandwidthPeers(remainingToSelect(howMany, matches), exclude, matches, onlyNotFailing, mask, ipSet,
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
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet mutable set tracking already-selected subnets, or null when mask is 0
     * @param buildSuccess the build success ratio, fetched once per scan
     */
    private void selectHighBandwidthPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing,
                                          int mask, MaskedIPSet ipSet, double buildSuccess) {
        int needed = remainingToSelect(howMany, matches);
        if (needed > 0) {
            List<Hash> selected = new ArrayList<>(needed);
            long now = _context.clock().now();
            getReadLock();
            try {
                for (Iterator<Hash> iter = new RandomIterator<>(_notFailingPeersList); selected.size() < needed && iter.hasNext(); ) {
                    Hash cur = iter.next();
                    if (matches.contains(cur) || (exclude != null && exclude.contains(cur))) continue;
                    if (onlyNotFailing && _highCapacityPeers.containsKey(cur)) continue;
                    PeerProfile profile = _notFailingPeers.get(cur);
                    if (!passesBasicGates(cur) || isExcessiveLifetimeFailure(profile, now)) continue;
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
        if (stillSelecting(howMany, matches)) {
            selectAllNotFailingPeers(remainingToSelect(howMany, matches), exclude, matches, onlyNotFailing, mask, ipSet,
                                     buildSuccess, false);
        }
    }

    /**
     * Reliability gate for high-bandwidth (O/P/X tier) selection: the peer
     * must show a tunnel acceptance ratio of at least 0.3 AND evidence of
     * recent activity. A successful peer test within 10 minutes, a heard-
     * from/send-success within 30 minutes, or an established commSystem
     * connection.  Peers with no profile are not gated (no data yet).
     * <p>
     * Pure decision — no context access, safe for unit tests.
     *
     * @param profile the peer profile (non-null)
     * @param now current time in ms
     * @param established whether commSystem has an established connection
     * @return whether the peer passes the reliability gate
     * @since 0.9.71+
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
     * Falls through to {@link #selectAllPeers()} if shortfall remains.
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param onlyNotFailing if true, exclude peers already in high-capacity tier
     * @param mask bitmask length for /n diversity restriction (0 to disable)
     * @param ipSet subnets already represented in this tunnel, consulted only
     *              when mask is non-zero
     * @param buildSuccess the build success ratio, fetched once per scan and
     *                     consumed by the selectability gate
     * @param preferUnproven if true, prioritize peers with no tunnel test history
     *                       so they accumulate profiling data through exploratory builds
     */
     private void selectAllNotFailingPeers(int howMany, Set<Hash> exclude, Set<Hash> matches, boolean onlyNotFailing,
                                      int mask, MaskedIPSet ipSet, double buildSuccess, boolean preferUnproven) {
        int needed = remainingToSelect(howMany, matches);
        if (needed > 0) {
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

        if (stillSelecting(howMany, matches)) {
            selectRemainderFromAllPeers(remainingToSelect(howMany, matches), exclude, matches, buildSuccess);
        }
    }

    /**
     * Fallback selection: fill the remaining slots from the union of all
     * peers (fast + high-capacity + not-failing).
     *
     * @param howMany target number of peers
     * @param exclude peers to exclude (may be null)
     * @param matches output set populated with selected peer hashes
     * @param buildSuccess the build success ratio, fetched once per scan
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

    /** Profile count considered enough for full selection. */
    private static final int ENOUGH_PROFILES = getEnoughProfiles();

    /**
     * Derives the enough-profiles count from the config.
     *
     * @return the profile count
     */
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
     * Fraction of a tier population that must carry a usable first-hop RTT before
     * latency is allowed to influence tier membership.
     * <p>A low-latency decision taken over a small measured subset describes that
     * subset, not the tier: the peers we happen to have sessions with are the
     * ones we have been talking to, so an early sample is drawn from whoever was
     * contacted most recently rather than from the population. Requiring a
     * majority of the tier to be measured keeps the decision anchored to the tier
     * it is applied to.
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
     * Refresh direct-link RTT for tier peers that already have a transport
     * session, and re-judge their low-latency standing.
     * <p>Decoupled from the build pre-connect on purpose. The pre-connect is a
     * one-shot probe fired to force establishment, so it reads the transport at
     * the least informative moment: a session that was just created has not
     * completed a round trip and reports zero. This samples instead from sessions
     * that have been up long enough to have measured, which is the value the
     * latency tiers actually want.
     * <p>Peers with no session are skipped rather than probed. Probing here would
     * re-create the problem above: establishing a session to measure it is what
     * makes the measurement unavailable.
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
          // Fresh baselines for this cycle, keyed by transport style. Built
          // here rather than kept across cycles: a baseline is meant to describe
          // the present, and carrying one forward would let a transient
          // congestion moment keep setting the bar long after it passed.
          //
          // Fed only from the transport that supplied each peer's best reading,
          // so a bucket describes peers actually served over that transport.
          // Feeding every session would let a peer whose SSU2 path is better
          // inflate the NTCP2 bucket, raising the NTCP2 bar and admitting links
          // that are genuinely slow - the same mixing of populations the
          // calibration exists to undo.
          Map<String, ProtocolRttBaseline> baselines = new HashMap<>();
          getReadLock();
          try {
              for (PeerProfile profile : sampleTierPeers()) {
                  int rtt = sampleFirstHopRtt(profile, transports, now);
                  if (rtt > 0) {
                      sampled++;
                      rttSum += rtt;
                      rttCount++;
                      feedBaseline(baselines, profile, transports);
                  }
              }
          } finally {
              releaseReadLock();
          }
          publishBaselines(baselines);
          publishLatencyCoverage();
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
     * Add this peer's best measured latency to its winning transport's baseline.
     * <p>The same transport whose value {@link #sampleFirstHopRtt} recorded: the
     * fastest of the peer's sessions. A peer with no readable latency contributes
     * nothing, which is correct - a baseline describes measured links.
     *
     * @param baselines    per-style accumulators for this cycle
     * @param profile      the sampled peer
     * @param transports   live transports to query
     * @since 0.9.71+
     */
    private void feedBaseline(Map<String, ProtocolRttBaseline> baselines,
                              PeerProfile profile, Collection<Transport> transports) {
        Hash peer = profile.getPeer();
        if (peer == null || baselines == null) {return;}
        String style = null;
        int best = 0;
        for (Transport transport : transports) {
            int rtt = transport.getEstimatedRTT(peer);
            if (rtt > best) {
                best = rtt;
                style = transport.getStyle();
            }
        }
        if (best <= 0 || style == null) {return;}
        ProtocolRttBaseline baseline = baselines.get(style);
        if (baseline == null) {
            baseline = new ProtocolRttBaseline();
            baselines.put(style, baseline);
        }
        baseline.add(best);
    }

    /**
     * Publish this cycle's baselines and report them as stats.
     * <p>The stat is what makes the calibration observable rather than assumed:
     * a baseline of 0 means the transport has not yet gathered enough samples,
     * and the low-latency judgement is running on the absolute floor unchanged.
     * Watching the sample count alongside the value is the check that a
     * transport is actually supplying latency.
     *
     * @param baselines per-style accumulators built this cycle
     * @since 0.9.71+
     */
    private void publishBaselines(Map<String, ProtocolRttBaseline> baselines) {
        _rttBaselines.clear();
        if (baselines == null || baselines.isEmpty()) {return;}
        StatManager sm = _context.statManager();
        for (Map.Entry<String, ProtocolRttBaseline> e : baselines.entrySet()) {
            ProtocolRttBaseline b = e.getValue();
            String style = e.getKey();
            long clamped = b.getClampedBaselineMs(PeerProfile.LOW_LATENCY_FLOOR_MS,
                                                  _lowLatencyBaselineMultiple);
            // Registered here, immediately before first use, rather than in the
            // constructor: transports are added after this object is built, so
            // enumerating them at construction finds none, and StatManager
            // silently drops names that were never registered - the calibration
            // would publish nothing at all and look broken rather than
            // unreported. createRequiredRateStat is idempotent, so re-registering
            // an existing name each cycle is harmless.
            sm.createRequiredRateStat("peer.latencyBaseline." + style,
                                      "Calibrated latency floor for " + style + " (ms)",
                                      "Peers", RATES);
            sm.createRequiredRateStat("peer.latencySamples." + style,
                                      "Samples backing the " + style + " latency floor",
                                      "Peers", RATES);
            if (clamped > 0) {
                _rttBaselines.put(style, clamped);
                sm.addRateData("peer.latencyBaseline." + style, clamped, 60L * 1000);
            }
            // Sample count reported even when unknown: a zero baseline with a
            // growing count is the expected shape while a transport warms up.
            sm.addRateData("peer.latencySamples." + style, b.getSampleCount(), 60L * 1000);
        }
    }

    /**
     * The calibrated latency floor for a transport style, or -1 when that
     * transport has not gathered enough samples to have an opinion.
     * <p>Callers must treat -1 as "no calibrated opinion" and fall back to the
     * absolute rule, never as "this peer qualifies".
     *
     * @param style the transport style, e.g. "NTCP2" or "SSU2"
     * @return the calibrated floor in ms, or -1 if unknown
     * @since 0.9.71+
     */
    long getCalibratedLatencyFloorMs(String style) {
        if (style == null) {return -1;}
        Long floor = _rttBaselines.get(style);
        return (floor == null) ? -1 : floor.longValue();
    }

    /**
     * The calibrated latency floor for a peer, choosing the style of the
     * transport that measured it.
     *
     * @param peer the peer to look up
     * @return the calibrated floor in ms, or -1 if unknown
     * @since 0.9.71+
     */
    long getCalibratedLatencyFloorMs(Hash peer) {
        if (peer == null) {return -1;}
        CommSystemFacade comm = _context.commSystem();
        if (comm == null) {return -1;}
        String style = null;
        int best = 0;
        for (Transport transport : comm.getTransports().values()) {
            int rtt = transport.getEstimatedRTT(peer);
            if (rtt > best) {
                best = rtt;
                style = transport.getStyle();
            }
        }
        return (best <= 0) ? -1 : getCalibratedLatencyFloorMs(style);
    }

    /**
     * Scale a calibrated baseline into the latency a peer may sit under and
     * still count as low latency.
     * <p>The baseline is the mean of a style's fastest samples, so it is a
     * measure of where a transport's population <em>starts</em>, not where it
     * ends. Judging a peer against the raw baseline rejects the entire body of
     * an otherwise fast transport: with a 43ms baseline every peer above 43ms
     * is disqualified, which is a far stricter bar than
     * {@link #_lowLatencyBaselineMultiple} describes and the opposite of the
     * widening this bound is supposed to provide.
     * <p>Saturates rather than wraps, so an extreme baseline cannot overflow into
     * a negative bound that would read as "unknown" and silently disable the
     * check, nor a small positive one that would reject the whole population.
     *
     * @param baselineMs the calibrated baseline in ms, or a non-positive value
     *                   meaning that style has no calibrated opinion yet
     * @return the qualifying threshold in ms, or a non-positive value when
     *         {@code baselineMs} carries no opinion
     * @since 0.9.71+
     */
    /**
     * Report how much of each tier holds a current first-hop RTT.
     * <p>This is the question the other stats cannot answer. Tier size says how
     * many peers exist, and the baseline stats say how many samples fed the
     * floor, but neither says how many peers hold a reading that is still
     * inside the validity window - a tier can be fully counted and largely
     * unmeasured while looking identical to a healthy one from outside.
     * <p>Reported as a count against nothing rather than a ratio, because a ratio
     * computed over a shrinking tier flatters itself.
     *
     * @since 0.9.71+
     */
    private void publishLatencyCoverage() {
        long now = _context.clock().now();
        int fast = 0, fastWithRtt = 0, highCap = 0, highCapWithRtt = 0;
        getReadLock();
        try {
            for (PeerProfile profile : _fastPeers.values()) {
                fast++;
                if (profile.getFirstHopRtt(now) > 0) {fastWithRtt++;}
            }
            for (PeerProfile profile : _highCapacityPeers.values()) {
                highCap++;
                if (profile.getFirstHopRtt(now) > 0) {highCapWithRtt++;}
            }
        } finally {
            releaseReadLock();
        }
        // Registered at the point of use for the same reason as the baseline
        // stats: a name StatManager has never been told about is dropped, so a
        // name registered only in the constructor would silently publish
        // nothing. Both are idempotent.
        StatManager sm = _context.statManager();
        sm.createRequiredRateStat("peer.fastTierWithRtt",
                                  "Fast-tier peers holding a current first-hop RTT",
                                  "Peers", RATES);
        sm.createRequiredRateStat("peer.highCapWithRtt",
                                  "High-capacity peers holding a current first-hop RTT",
                                  "Peers", RATES);
        sm.addRateData("peer.fastTierWithRtt", fastWithRtt, 60L * 1000);
        sm.addRateData("peer.highCapWithRtt", highCapWithRtt, 60L * 1000);
        if (_log.shouldInfo() && fast > 0) {
            _log.info("Latency coverage: fast tier " + fastWithRtt + "/" + fast +
                      ", high capacity " + highCapWithRtt + "/" + highCap);
        }
    }

    /**
     * Scales a baseline timeout by the configured multiple.
     *
     * @param baselineMs the baseline timeout
     * @return the adjusted timeout
     */
    private long applyBaselineMultiple(long baselineMs) {
        return applyBaselineMultiple(baselineMs, _lowLatencyBaselineMultiple);
    }

    /**
     * Scale a calibrated baseline into the latency a peer may sit under and
     * still count as low latency.
     * <p>Pure arithmetic, extracted so the overflow and sentinel rules can be
     * exercised without a router context.
     *
     * @param baselineMs the calibrated baseline in ms
     * @param multiple   how many times it may be exceeded; values of 1 or less
     *                   return the baseline unscaled
     * @return the qualifying threshold in ms, or {@code baselineMs} unchanged
     *         when it carries no opinion
     * @since 0.9.71+
     */
    static long applyBaselineMultiple(long baselineMs, int multiple) {
        if (baselineMs <= 0) {return baselineMs;}
        if (multiple <= 1) {return baselineMs;}
        long threshold = baselineMs * multiple;
        if (threshold < baselineMs || threshold < 0) {return Long.MAX_VALUE;}
        return threshold;
    }

    /**
     * The qualifying latency threshold for a peer, from the calibrated baseline
     * of the transport that measured it.
     * <p>This is what the profile actually compares against - the baseline scaled
     * by {@link #PROP_LOW_LATENCY_MULTIPLE} - as opposed to
     * {@link #getCalibratedLatencyFloorMs(Hash)}, which returns the unscaled
     * baseline that {@code peer.latencyBaseline.*} reports.
     *
     * @param peer the peer to look up
     * @return the qualifying threshold in ms, or -1 if unknown
     * @since 0.9.71+
     */
    long getCalibratedLatencyThresholdMs(Hash peer) {
        return applyBaselineMultiple(getCalibratedLatencyFloorMs(peer));
    }

    /**
     * The qualifying latency threshold for the fast cohort, from the cohort
     * baseline of its fastest members.
     * <p>Scaled on the same terms as {@link #getCalibratedLatencyThresholdMs(Hash)}
     * so the two bounds stay comparable; a cohort mean and a transport baseline
     * are the same kind of quantity and neither is a cutoff on its own.
     *
     * @return the qualifying threshold in ms, or a non-positive value when the
     *         cohort has too few measured peers to hold an opinion
     * @since 0.9.71+
     */
    long getCohortLatencyThresholdMs() {
        return applyBaselineMultiple((long) getAverageLowLatencyRTT());
    }

    /**
     * Bounded random sample of the retained peers for RTT measurement, capped at
     * {@link #DEFAULT_MIN_CANDIDATE_SAMPLE} with a rotated start so successive
     * cycles do not always read the same prefix.
     * <p>Sampling is safe here because a peer's RTT is a property of its transport
     * session, not an accumulator: a peer missed this cycle is measured in a
     * later one.
     * <p>Covers every tier the Organizer retains, not just the two the fast tier
     * is drawn from. A profile outside the fast and high-capacity tiers can still
     * hold the only current measurement of a peer that later qualifies, and
     * reading the tiers it can currently be selected from would leave its
     * latency unmeasured until something promoted it - at which point the first
     * hop is chosen on no evidence at all. There is no larger population to read:
     * profiles are retained only in these maps, so this is the whole of what
     * exists.
     *
     * @return the sampled profiles, at most {@link #sampleFirstHopRttLimit(int)}
     * @since 0.9.71+
     */
    private List<PeerProfile> sampleTierPeers() {
        int total = _fastPeers.size() + _highCapacityPeers.size()
                  + _wellIntegratedPeers.size() + _notFailingPeers.size();
        int limit = sampleFirstHopRttLimit(total);
        List<PeerProfile> sample = new ArrayList<>(Math.min(limit, total));
        if (total == 0) return sample;
        int offset = ThreadLocalRandom.current().nextInt(total);
        int idx = 0;
        for (Map<Hash, PeerProfile> tier : _allTiers) {
            for (PeerProfile profile : tier.values()) {
                if (sample.size() >= limit) break;
                if (((idx++ - offset) % total + total) % total < limit) sample.add(profile);
            }
            if (sample.size() >= limit) break;
        }
        return sample;
    }

    /**
     * How many tier peers to sample for first-hop RTT in one reorganize.
     * <p>
     * Pure decision — no context access, safe for unit tests.
     *
     * @param total combined size of the two tiers
     * @return the sample size, never negative
     * @since 0.9.71+
     */
    static int sampleFirstHopRttLimit(int total) {
        if (total <= 0) return 0;
        return Math.min(total, DEFAULT_MIN_CANDIDATE_SAMPLE);
    }

    /**
     * Record the best RTT any transport reports for this peer, if any.
     *
     * @param profile the peer to sample; nothing is recorded when it is null
     * @param transports the peer's transports, each asked for its estimated RTT — the
     *                   largest reported value wins
     * @param now router time in ms, stamped onto the sample when the winning transport
     *            reports no measurement time
     * @return the recorded RTT in ms, or 0 if there was no measurement
     */
    private int sampleFirstHopRtt(PeerProfile profile, Collection<Transport> transports, long now) {
        if (profile == null) {return 0;}
        Hash peer = profile.getPeer();
        if (peer == null) {return 0;}
        int best = 0;
        Transport bestTransport = null;
        for (Transport transport : transports) {
            int rtt = transport.getEstimatedRTT(peer);
            if (rtt > best) {
                best = rtt;
                bestTransport = transport;
            }
        }
        if (best <= 0) {return 0;}
        // The winning transport says when it measured. Stamping `now` instead
        // would present a handshake-time reading as current on every cycle, which
        // both damps the stored value toward one stale sample and holds the
        // profile's staleness window open so the reading never expires.
        long measured = (bestTransport != null)
                            ? bestTransport.getEstimatedRTTTime(peer)
                            : Transport.RTT_TIME_UNKNOWN;
        if (measured == Transport.RTT_TIME_UNKNOWN || measured == Transport.RTT_TIME_CURRENT ||
            measured <= 0) {
            measured = now;
        }
        // Through the single recorder, so the sampling pass and the pre-connect
        // path damp and re-derive identically. The stat is deliberately not added
        // here: the caller reports one cycle aggregate rather than one sample per
        // peer, and adding both would count the same measurement twice.
        recordFirstHopRtt(profile, best, measured);
        return best;
    }

    /**
     * Peers in either the fast or the high-capacity tier, counting each peer once.
     * <p>A peer can be both, so neither tier contains the other and adding the two
     * counts over-reports. This is the number the profile-tier graph needs for its
     * high-capacity line to be a true subset of the fast line, rather than merely a
     * smaller number that happens to be plotted beside it.
     * <p>Walks the smaller tier and probes the larger, so the cost is
     * {@code O(min(fast, highCap))} once per coalesce rather than a full merge.
     *
     * @return size of the union of the two tiers
     * @since 0.9.71+
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
     * Live size of the fast or high-capacity union, a peer in both tiers counted
     * once.  Read-locked, so it is safe to call outside a coalesce pass.
     * <p>Callers that already hold the write lock — {@code reorganize()}. Must
     * call the package-visible {@link #fastOrHighCapCount} directly; going
     * through this accessor would re-enter the lock.
     *
     * @return size of the union of the two tiers
     * @see #fastOrHighCapCount()
     * @since 0.9.71+
     */
    public int getFastOrHighCapCount() {
        getReadLock();
        try {
            return fastOrHighCapCount();
        } finally {
            releaseReadLock();
        }
    }

    /**
     * Reorganizes peer profiles into performance-based tiers (fast, high-capacity, etc.) and expires stale entries.
     * <p>
     * This method:
     * <ul>
     * <li>Coalesces stats if requested and uptime conditions are met. Peak
     * throughput values are never decayed.</li>
     * <li>Filters out unreachable, inactive, or low-tier peers.</li>
     * <li>Recalculates dynamic thresholds for speed, capacity, and integration.</li>
     * <li>Rebuilds internal tier maps and the global profile ordering.</li>
     * <li>Expires profiles that haven't been active recently to bound memory usage.</li>
     * </ul>
     * <p>
     * <strong>Memory Safety:</strong> To prevent unbounded memory growth (e.g., OOM after 8+ hours),
     * this method ensures that expired profiles are removed from all data structures—even if the full
     * reorganization is skipped due to lock contention. A best-effort expiration pass runs outside the
     * write lock to mitigate leaks during high contention.
     *
     * @param shouldCoalesce if {@code true}, coalesce statistics for active profiles
     */
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

        // Tiered expiration windows, by how much we have actually interacted with
        // the peer. Active peers keep long-term data for tunnel selection quality;
        // passive peers keep it long enough to catch an intermittent interaction;
        // gossip-only peers are kept because a floodfill store is the only thing we
        // will ever learn about them. Each window is a property, because the right
        // value depends on how much of the network a router is trying to hold, and
        // the previous constants were a week and a day on a router whose tiers
        // needed peers the sweep had already deleted.
        final long expireActive = hoursToMs(_context.getProperty(PROP_EXPIRE_ACTIVE_HOURS,
                                                                 DEFAULT_EXPIRE_ACTIVE_HOURS));
        final long expirePassive = hoursToMs(_context.getProperty(PROP_EXPIRE_PASSIVE_HOURS,
                                                                  DEFAULT_EXPIRE_PASSIVE_HOURS));
        final long expireGossip = hoursToMs(_context.getProperty(PROP_EXPIRE_GOSSIP_HOURS,
                                                                 DEFAULT_EXPIRE_GOSSIP_HOURS));
        // A peer that has never accepted or rejected a build for us is the normal
        // state of every candidate the tier fills want to use, so this window
        // decides how deep the reservoir of unjudged peers is.
        final long expireUntracked = untrackedExpiryMs(_strictCapacityOrder.size(),
                                                       _context.getProperty(PROP_EXPIRE_UNTRACKED_HOURS,
                                                                            DEFAULT_EXPIRE_UNTRACKED_HOURS));

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
            _log.warn("Write lock unavailable during reorganize -> Lightweight expiration only");

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
            long tCoalesce = System.currentTimeMillis();

            // Fetch once for the whole cycle; tier rebuild and quality counting
            // would otherwise re-read router statistics per profile.
            double buildSuccess = getTunnelBuildSuccess();

            // Step 2: Calculate new thresholds
            int numNotFailing = newStrictCapacityOrder.size();
            double newIntegrationThreshold = numNotFailing > 0 ? totalIntegration / numNotFailing : 1.0d;
            double newCapacityThreshold = calculateCapacityThresholdFromSet(newStrictCapacityOrder, numNotFailing);
            double newSpeedThreshold = calculateSpeedThreshold(newStrictCapacityOrder, now, newCapacityThreshold);
            long tThreshold = System.currentTimeMillis();

            // Fetch once for the whole cycle; tier sizing, the purge's starvation
            // floors and the fill passes all read it, and each would otherwise
            // re-walk the network database.
            int knownRouters = _context.netDb().getKnownRouters();

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
            lockedRebuildTiers(newStrictCapacityOrder, buildSuccess, knownRouters);
            long tPlace = System.currentTimeMillis();
            _context.statManager().addRateData(
                    "peer.profileCoalesceTime", tCoalesce - start);
            _context.statManager().addRateData(
                    "peer.profileThresholdTime", tThreshold - tCoalesce);
            _context.statManager().addRateData(
                    "peer.profilePlaceTime", tPlace - tThreshold);

            // Step 4a: Publish the promotion-deferral counters the rebuild opened
            // its cycle for. Read still under the write lock, so the numbers
            // reported belong to this cycle and to no other.
            recordRouterInfoRefreshStats();
            if (_log.shouldInfo()) {
                String skips = drainPromotionSkipTally();
                if (!skips.isEmpty()) {
                    _log.info("Rebuild promotion skips:" + skips);
                }
            }

            // Step 5: Purge peers with recent tunnel failures, loss probation, or
            // poor tunnel acceptance from fast/high-cap tiers. This runs before
            // the fills, not after: acceptance is an eviction criterion for both
            // tiers while high-capacity admission ignores it, so a peer admitted
            // by the fill may be the very peer this pass is about to reject.
            // Purging afterwards would empty the tier and leave it empty until
            // the next cycle, which is a net shrink rather than a turnover.
            purgeUnusableFromTiers(now, buildSuccess, knownRouters);

            // Step 6: Fallback to ensure minimum fast peers
            int added = fillFastTierFallbacks(now, newStrictCapacityOrder, buildSuccess, knownRouters);

            // Step 6a: Fallback to ensure minimum high-capacity peers
            int highCapAdded = fillHighCapFallback(now, newStrictCapacityOrder, buildSuccess, knownRouters);

            // Step 6c: Fallback to preserved pre-reorganize peers if rebuild shrunk tiers too much.
            restorePreservedPeers(now, oldFastPeers, oldHighCapPeers, buildSuccess, knownRouters);

            // Step 6d: Count quality peers (fast/high-cap with good acceptance + recent activity)
            // Used by tuner to adjust tier limits based on viable tunnel candidates
            int qualityCount = countQualityPeers(now, buildSuccess);
            // Recently-active profiles for peer.activeProfileCount. Counted here so the gauge
            // costs one pass inside the reorganise instead of a lock-taking scan every 50s.
            int activeCount = countActiveProfiles(now);

            // Step 6e: Compute average peer test RTT across fast peers with data.
            // getCohortLatencyThresholdMs() scales this average by the configured
            // baseline multiple (default 3) into a fast-tier latency bound.
            _averageLowLatencyRTT = computeAverageLowLatencyRTT();

            // Step 7: Update global thresholds
            _strictCapacityOrder = newStrictCapacityOrder;
            _thresholdCapacityValue = newCapacityThreshold;
            _thresholdIntegrationValue = newIntegrationThreshold;
            _thresholdSpeedValue = newSpeedThreshold;

            // Step 9: Log and record stats
            if (_log.shouldInfo()) {
                // Fallback additions are the event; zero is the norm and stays
                // out of the line so the three tier counts fit one row.
                String fastExtra = added > 0 ? " (fallback +" + added + ')' : "";
                String capExtra = highCapAdded > 0 ? " (fallback +" + highCapAdded + ')' : "";
                _log.info("Profiles reorganized -> Expired " + expiredCount + " profiles" +
                          " * Retained: " + profileCount +
                          "\n* Thresholds -> Capacity score: " + num(_thresholdCapacityValue) +
                          " * Speed score: " + num(_thresholdSpeedValue) +
                          " * Integration score: " + num(_thresholdIntegrationValue) +
                          "\n* Fast peers: " + _fastPeers.size() + fastExtra +
                          " * HighCap peers: " + _highCapacityPeers.size() + capExtra +
                          " * Quality peers: " + qualityCount);
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

            // Step 10: Queue the proactive RouterInfo refreshes for the peers this
            // cycle kept in, or near, the tiers. Queued under the lock, issued by
            // releaseWriteLockAndRefresh() once it is dropped.
            scheduleRouterInfoRefreshes(now);

            // Step 11: Enforce memory cap
            enforceProfileCap();

        } finally {
            releaseWriteLockAndRefresh();
        }

        // Step 12: Clean up persisted profiles.
        //
        // Deliberately after the lock is released. The purge gunzips and
        // property-parses every stored profile file (up to MAX_STORED_PROFILE_FILES)
        // and does a netdb lookup per file, and _reorganizeLock is the lock tunnel
        // peer selection contends on - holding it across that I/O stalled every pool
        // build for as long as the walk took. selectAllPeers() takes the read lock
        // itself for the in-memory snapshot, so the only thing the unlocked window
        // can cost is deleting the file of a peer registered after that snapshot,
        // and its profile is re-stored on the next store cycle.
        purgeStaleProfileFiles();
    }

    /**
     * Whether a profile has expired from its activity tier and should be
     * purged: no activity (send/heard/heard-about) within the tier's
     * expiration window.  Tiered expiration: Active &gt; Passive &gt; Gossip;
     * profiles with no tunnel history are purged on the tighter untracked
     * window regardless of tier.  A profile with zero timestamps sits in
     * the gossip tier.
     * <p>
     * Pure decision. No context access, safe for unit tests.
     *
     * @param profile profile to test; its expiration window is picked from its tier,
     *                the tier of the newest activity timestamp it carries
     * @param now current time in ms
     * @param expireActive active-tier window in ms
     * @param expirePassive passive-tier window in ms
     * @param expireGossip gossip-tier window in ms
     * @param expireUntracked untracked window in ms
     * @return whether the profile is expired
     * @since 0.9.71+
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
     * Builds the candidate profile set for this reorganize round, filtering
     * expired, unreachable, and excluded profiles.
     *
     * @param now current time in ms, the reference point for the windows below
     * @param expireActive active-tier window in ms
     * @param expirePassive passive-tier window in ms
     * @param expireGossip gossip-tier window in ms
     * @param expireUntracked untracked window in ms, the tighter cap applied to
     *                        profiles with no tunnel history
     * @return the surviving profiles, with the integration total and the expired and
     *         kept counts
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
     * Result of {@link #buildCandidateProfiles(long, long, long, long, long)}: the
     * candidate set plus the totals and counts consumed by the threshold and
     * logging sections.
     */
    private static final class CandidateSet {
        /** The candidate profiles. */
        final Set<PeerProfile> profiles;
        /** Total integration value across the candidates. */
        final double totalIntegration;
        /** Candidates whose profiles have expired. */
        final int expiredCount;
        /** Total profiles held by the organizer. */
        final int profileCount;

        /**
         * Holds one candidate pool and its totals.
         *
         * @param profiles the candidate profiles
         * @param totalIntegration integration summed over the candidates
         * @param expiredCount candidates with expired profiles
         * @param profileCount total profiles held
         */
        CandidateSet(Set<PeerProfile> profiles, double totalIntegration, int expiredCount, int profileCount) {
            this.profiles = profiles;
            this.totalIntegration = totalIntegration;
            this.expiredCount = expiredCount;
            this.profileCount = profileCount;
        }
    }

    /**
     * Reinserts all active profiles into the tier maps.  Must be called with
     * the write lock held.
     * <p>Also opens the promotion-deferral cycle, which the tier rebuild is the
     * only place that can judge: a promotion is the moment a stale RouterInfo
     * starts costing something, and this loop is the sole promotion path that
     * sees the whole profile population at once.
     *
     * @param candidates the surviving profile set
     * @param buildSuccess cached tunnel build success ratio
     * @param known the routers in the network database, fetched once for the cycle
     * @since 0.9.72+
     */
    private void lockedRebuildTiers(Set<PeerProfile> candidates, double buildSuccess, int known) {
        // Fetched once by the caller for the whole cycle; lockedPlaceProfile() would
        // otherwise re-walk the network database for every candidate, and the purge
        // and fill passes that follow need the same number for their own sizing.
        int active = _context.commSystem().countActivePeers();
        int minHighCap = getMinimumHighCapacityPeers(known);
        int minFast = getMinimumFastPeers(known);
        int maxFast = getMaximumFastPeers(known, active);
        int maxHighCap = getMaximumHighCapPeers(known, active);
        // The tier minimums are the starvation floors: they are what the fallback
        // passes below drive the tiers back up to, so holding a promotion back
        // while the tier is under one would only compete with those passes.
        _refresher.beginCycle(getRouterInfoRefreshAgeMs(), minFast, minHighCap);
        long now = _context.clock().now();
        for (PeerProfile profile : candidates) {
            // Decay the acceptance and failure history here rather than in
            // PeerProfile.coalesceStats(): that path coalesces the RateStats and runs
            // updateValues() too, which is far more than this pass needs, and it is
            // only reachable from the profile-load path — where the interval has not
            // elapsed, so the decay never fired at all. A long-lived profile therefore
            // kept a lifetime ratio that no amount of good behaviour since could
            // repair. Walking the candidates on the reorganise cadence is what makes
            // it live, and decayLifetimeCounters() early-returns on every pass but the
            // one that crosses the interval, so the added cost is a volatile read.
            if (profile.getTunnelHistory() != null) {
                profile.getTunnelHistory().decayLifetimeCounters(now);
            }
            if (profile.getDBHistory() != null) {
                profile.getDBHistory().decayLifetimeCounters(now);
            }
            lockedPlaceProfile(profile, buildSuccess, minHighCap, maxFast, maxHighCap);
        }
    }

    /**
     * Common gate for fast/high-cap tier fill passes: the peer must be
     * selectable, have acceptable tunnel acceptance, no recent tunnel
     * failures, and not be in loss probation.  Evaluation order preserves
     * the original short-circuit chain (cheap pure checks before the
     * netDb-backed selectability check is already handled by callers that
     * test other cheap conditions first).
     * <p>
     * No side effects. Safe to evaluate without holding locks.
     * <p>Tunnel acceptance is required by the fast-tier passes and not by the
     * high-capacity pass. The two tiers do different jobs: high capacity is the
     * reservoir the fast tier is seeded from, so gating it on acceptance leaves
     * no deep supply to draw on — when a fast peer demotes, the peers beside it
     * in high capacity have already been filtered by the same criterion. High
     * capacity therefore admits any provably alive peer of the right bandwidth
     * class, and acceptance decides fast-tier membership only. A poor accepter
     * is still evicted from high capacity, so the reservoir turns over rather
     * than accumulating them.
     *
     * @param profile            the candidate profile
     * @param buildSuccess       the build success ratio in [0.0, 1.0]
     * @param now                current time in ms
     * @param requireAcceptance  whether the peer must also have acceptable tunnel
     * acceptance; false admits by capacity and
     * liveness alone
     * @return whether the peer passes the tier gates
     * @since 0.9.71+
     */
    boolean passesTierGates(PeerProfile profile, double buildSuccess, long now,
                            boolean requireAcceptance) {
        // Use basic gates instead of isSelectable to avoid stale RouterInfo
        // proof-of-life filtering peers that were already vetted at tier entry.
        // The profile is already in hand, so the failure check takes it directly
        // rather than re-probing the map.
        if (!passesBasicGates(profile.getPeer())) {return false;}
        if (isExcessiveLifetimeFailure(profile, now)) {return false;}
        // The fast-tier sample floor, not one: this gate runs on the fill passes
        // as well as the rebuild, so a floor of one rejected fill candidates on a
        // single refusal, which is the asymmetry the high-capacity path documents.
        if (requireAcceptance && isLowTunnelAcceptance(profile, buildSuccess, now, FAST_ACCEPTANCE_SAMPLES)) {return false;}
        if (hasRecentTunnelFailures(profile)) {return false;}
        return !inLossProbation(profile, now);
    }

    /**
     * Common gate for the fast-tier fill passes, which always require acceptable
     * tunnel acceptance.
     *
     * @param profile      the candidate profile
     * @param buildSuccess the build success ratio in [0.0, 1.0]
     * @param now          current time in ms
     * @return whether the peer passes the fast-tier gates
     * @since 0.9.71+
     */
    boolean passesTierGates(PeerProfile profile, double buildSuccess, long now) {
        return passesTierGates(profile, buildSuccess, now, true);
    }

    /**
     * True if the peer showed recent activity within the given cutoff: a
     * heard-from or successful send at or after the cutoff.  Used to keep
     * stale peers out of the fast/high-cap tiers.
     * <p>
     * Pure decision. No context access, safe for unit tests.
     *
     * @param profile profile whose heard-from and last-successful-send timestamps are checked
     * @param activeCutoff earliest allowed activity timestamp in ms
     * @return whether the peer has recent activity
     * @since 0.9.71+
     */
    static boolean hasRecentTierActivity(PeerProfile profile, long activeCutoff) {
        return hasRecentTierActivity(profile, activeCutoff, false);
    }

    /**
     * As above, with the transport's own answer included.
     * <p>Recency of our own traffic is a proxy for liveness. A session the
     * transport is holding right now is the thing itself: a peer we have not sent
     * anything to in four hours, with a live session, is alive, and a fill pass
     * that refuses it is refusing it for having been chosen last cycle.
     *
     * @param profile profile whose heard-from and last-successful-send timestamps are checked
     * @param activeCutoff earliest allowed activity timestamp in ms
     * @param reachableNow whether the transport currently holds a session with the peer
     * @return whether the peer has recent activity
     * @since 0.9.72+ (the {@code reachableNow} parameter)
     */
    static boolean hasRecentTierActivity(PeerProfile profile, long activeCutoff, boolean reachableNow) {
        return reachableNow
               || profile.getLastHeardFrom() >= activeCutoff
               || profile.getLastSendSuccessful() >= activeCutoff;
    }

    /**
     * The recency window a tier fill pass uses, widened by how far the tier is
     * below its minimum.
     * <p>The selection window is sized to pick the freshest peers; the refill
     * window is sized to find enough peers to reach a tier minimum, and those are
     * different jobs. At the base window of one hour, a tier at 227 of 1,000 was
     * offered only peers it had heard from in the last hour - a set that is
     * itself a function of the tier being full, so the fill could not repair the
     * shortage it was called for.
     * <p>Widening is proportional to the deficit and disappears at the tier
     * minimum, so a full tier keeps the strict bar. Bounded by
     * {@link TunnelPeerSelector#MAX_WINDOW_MS}, the same ceiling the selection
     * window has.
     * <p>Pure decision - no context access, safe for unit tests.
     *
     * @param baseWindowMs the selection window from
     *                     {@link TunnelPeerSelector#getActivityWindow}
     * @param tierSize this tier's current size
     * @param tierMin this tier's minimum, the size the fill is aiming at
     * @return the window in ms, at least {@code baseWindowMs}
     * @since 0.9.72+
     */
    static long tierFillActivityWindow(long baseWindowMs, int tierSize, int tierMin) {
        if (tierMin <= 0 || tierSize >= tierMin) {return baseWindowMs;}
        double deficit = Math.min(1.0d, (double) (tierMin - tierSize) / tierMin);
        // A tier at half its target gets three times the window; one at 23% of it
        // gets five times. Multiplying the base keeps the widening proportionate
        // on a small router whose base window is already long.
        long widened = baseWindowMs * (2 + (long) (4 * deficit));
        return Math.min(widened, TunnelPeerSelector.MAX_WINDOW_MS);
    }

    /**
     * Whether the transport currently holds a session with the peer.
     *
     * @param peer the peer hash
     * @return true if a session is established
     * @since 0.9.72+
     */
    private boolean isReachableNow(Hash peer) {
        return _context.commSystem() != null && _context.commSystem().isEstablished(peer);
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
     * still remaining in the tier (soft signal. The ceiling applies at selection
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
     * Full eligibility gate for filling the fast/high-cap tiers from the
     * active profile set: not ourselves, passes the tier gates, and active
     * within the cutoff window.
     *
     * @param profile the candidate profile
     * @param buildSuccess the build success ratio in [0.0, 1.0]
     * @param now current time in ms
     * @param activeCutoff earliest allowed activity timestamp in ms
     * @return whether the peer may be added to the tier
     * @since 0.9.71+
     */
    private boolean isEligibleForTierFill(PeerProfile profile, double buildSuccess, long now, long activeCutoff) {
        return isEligibleForTierFill(profile, buildSuccess, now, activeCutoff,
                                      FAST_ADMISSION_REQUIRES_ACCEPTANCE);
    }

    /**
     * Whether a peer may be admitted to a tier by a fill pass.
     *
     * @param profile           the candidate profile
     * @param buildSuccess      the build success ratio in [0.0, 1.0]
     * @param now               current time in ms
     * @param activeCutoff      peers without activity at or after this are skipped
     * @param requireAcceptance whether acceptable tunnel acceptance is required;
     *                          false for the high-capacity reservoir, which admits
     *                          by capacity and liveness alone
     * @return whether the peer may be admitted
     * @since 0.9.71+
     */
    private boolean isEligibleForTierFill(PeerProfile profile, double buildSuccess, long now,
                                          long activeCutoff, boolean requireAcceptance) {
        if (profile.getPeer().equals(_us)) return false;
        if (!passesTierGates(profile, buildSuccess, now, requireAcceptance)) return false;
        // Require recent activity — don't fill fast tier with stale peers. A live
        // session counts: it is the liveness the timestamp is standing in for.
        return hasRecentTierActivity(profile, activeCutoff, isReachableNow(profile.getPeer()));
    }

    /**
     * Fills the fast tier to the minimum via up to three fallback passes.
     * Must be called with the write lock held.  Returns the number added.
     *
     * @param now current time in ms, the reference for the tier gates and the
     *            activity cutoff of the last pass
     * @param activeProfiles profiles surviving this reorganize round; the candidate
     *                       pool, alongside the high-capacity tier's members
     * @param buildSuccess the build success ratio in [0.0, 1.0], fetched once per cycle
     * @param known the routers in the network database, which sets the tier minimum
     * @return how many profiles were added to the fast tier, 0 if it already met its
     *         minimum
     * @since 0.9.72+ (the {@code known} parameter)
     */
    private int fillFastTierFallbacks(long now, Set<PeerProfile> activeProfiles, double buildSuccess, int known) {
        int minFast = getMinimumFastPeers(known);
        int added = 0;
        int target = minFast;

        if (_fastPeers.size() < target) {
            // The high-capacity tier is the priority ordering, not the universe.
            // Its members are already tier-gated, so they are the first picks -
            // but using it as the only source made the fast tier's ceiling the
            // high-capacity tier's size. Measured: 227 fast peers with 243
            // high-cap, both tiers starved, the fast fill scanning a list of 243
            // and reporting itself done. The reservoir is meant to seed the fast
            // tier, not to bound it.
            List<PeerProfile> candidates = new ArrayList<>(activeProfiles.size() + _highCapacityPeers.size());
            Set<Hash> listed = new HashSet<>(candidates.size());
            for (PeerProfile p : _highCapacityPeers.values()) {
                if (listed.add(p.getPeer())) {candidates.add(p);}
            }
            for (PeerProfile p : activeProfiles) {
                if (listed.add(p.getPeer()) && isFastTierCapable(p.getPeer())) {candidates.add(p);}
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
                // The refill window, widened by the deficit, not the selection
                // window: this pass exists to reach a minimum the strict bar
                // cannot supply.
                long fillWindow = tierFillActivityWindow(
                        TunnelPeerSelector.getActivityWindow(_context, buildSuccess),
                        _fastPeers.size(), minFast);
                long activeCutoff = inStartup ? now : now - fillWindow;
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
     * Fills the high-capacity tier to the minimum with recently-active
     * selectable peers.  Must be called with the write lock held.  Returns
     * the number added.
     *
     * @param now current time in ms, the reference for the activity cutoff
     * @param activeProfiles profiles surviving this reorganize round, scanned in
     *                       iteration order until the minimum is reached
     * @param buildSuccess the build success ratio in [0.0, 1.0], also used to size
     *                     the activity window during normal operation
     * @param known the routers in the network database, which sets the tier minimum
     * @return how many profiles were added to the high-capacity tier, 0 if it already
     *         met its minimum
     * @since 0.9.72+ (the {@code known} parameter)
     */
    private int fillHighCapFallback(long now, Set<PeerProfile> activeProfiles, double buildSuccess, int known) {
        int minHighCap = getMinimumHighCapacityPeers(known);
        int highCapAdded = 0;
        if (_highCapacityPeers.size() < minHighCap) {
            boolean inStartup = _context.router() != null &&
                                _context.router().getUptime() < 15 * 60 * 1000L;
            long fillWindow = tierFillActivityWindow(
                    TunnelPeerSelector.getActivityWindow(_context, buildSuccess),
                    _highCapacityPeers.size(), minHighCap);
            long activeCutoff = inStartup ? now : now - fillWindow;
            for (PeerProfile profile : activeProfiles) {
                if (_highCapacityPeers.size() >= minHighCap) break;
                if (isHighBandwidthCapable(profile.getPeer()) &&
                    isEligibleForTierFill(profile, buildSuccess, now, activeCutoff, HIGH_CAP_ADMISSION_REQUIRES_ACCEPTANCE)) {
                    _highCapacityPeers.put(profile.getPeer(), profile);
                    clearLossIfReadmitted(profile);
                    highCapAdded++;
                }
            }
        }
        return highCapAdded;
    }

    /**
     * Evicts peers that stopped qualifying since the tiers were last rebuilt.
     * <p>This is a within-window catch, not the mechanism that turns the tiers
     * over. The rebuild at step 4 clears both tiers and repopulates them through
     * {@link #lockedPromoteProfileToTiers}, which applies acceptance, congestion,
     * loss-probation and excessive-failure gates to every peer it admits. A peer
     * that is already disqualified therefore never survives the rebuild, and the
     * only thing left for this pass to remove is a peer that qualified at rebuild
     * time and stopped qualifying during the reorg window that followed.
     * <p>The consequence for anyone reading the stats: with the rebuild doing the
     * filtering, every reason counts near zero in steady state, and that is the
     * expected reading rather than evidence that a gate is inert. An all-zero
     * {@code peer.tierEvicted.*} series over many cycles means "nothing degraded
     * mid-window", not "eviction is disabled". The gates this pass duplicates are
     * still load-bearing, but they live in the promotion path.
     * <p>Two reasons have no promotion-path counterpart and so are this pass's
     * own: {@link PurgeReason#UNREACHABLE}, which tests a momentary transport flag
     * that promotion never reads, and {@link PurgeReason#BANNED}, which promotion
     * covers only through {@link #passesBasicGates}. Those two are the ones to
     * watch when diagnosing.
     * <p>Must be called with the write lock held.
     *
     * @param now current time in ms
     * @param buildSuccess the build success ratio, which relaxes the acceptance threshold under stress
     * @param known the routers in the network database, which sets the tier minimums
     *              that are the soft-purge thresholds
     * @since 0.9.71+
     */
    private void purgeUnusableFromTiers(long now, double buildSuccess, int known) {
        // Catches peers admitted by lockedPromoteProfileToTiers() that developed
        // failures during this reorganize window; see the javadoc for why the
        // steady-state counts here are near zero.
        //
        // The size guard applies only to the soft reasons. A peer that is banned,
        // unreachable, or no longer bandwidth-capable is never a valid member at
        // any tier size, so withholding its eviction because the tier happens to
        // be small only leaves it selectable for another cycle. The soft reasons -
        // recent failures, loss probation, poor acceptance - are the ones that
        // genuinely risk emptying a starved tier, so those stay gated.
        int fastMin = getMinimumFastPeers(known);
        int highCapMin = getMinimumHighCapacityPeers(known);
        boolean fastSoft = _fastPeers.size() >= softPurgeThreshold(fastMin);
        boolean highCapSoft = _highCapacityPeers.size() >= softPurgeThreshold(highCapMin);
        Map<PurgeReason, Integer> reasons = new EnumMap<>(PurgeReason.class);
        purgeUnusableFromMap(_fastPeers, "fast", now, buildSuccess, fastSoft, reasons);
        purgeUnusableFromMap(_highCapacityPeers, "high-cap", now, buildSuccess, highCapSoft, reasons);
        publishPurgeReasonStats(reasons, fastSoft, highCapSoft, fastMin, highCapMin);
    }

    /**
     * Why a peer was evicted from a tier.
     * <p>Carries a stable lowercase key for the stat name. The whole point of
     * counting these is to compare them across cycles, and a message that embeds
     * a peer hash or a peer count would make every cycle a new stat name.
     *
     * @since 0.9.71+
     */
    enum PurgeReason {
        /** On the banlist, so never selectable again. Applies at any tier size. */
        BANNED("banned"),
        /** The transport cannot currently reach it. Applies at any tier size. */
        UNREACHABLE("unreachable"),
        /** No longer qualifies for this tier's bandwidth class. Applies at any tier size. */
        NOT_CAPABLE("notCapable"),
        /** Sustained recent build or test failures, or inside loss probation. Soft. */
        FAILURES("failures"),
        /** Declines too many of our tunnel requests. Soft. */
        ACCEPTANCE("acceptance");

        /** Stat-name suffix for this reason; stable so cycles are comparable. */
        private final String key;

        /**
         * Creates a reason with the given message key.
         *
         * @param k the stat-name suffix to use for this reason
         */
        PurgeReason(String k) {key = k;}

        /**
         * The stat-name suffix identifying this reason.
         *
         * @return the lowercase key used in {@code peer.tierEvicted.<key>}
         * @since 0.9.71+
         */
        String getKey() {return key;}
    }

    /**
     * Publish per-reason eviction counts for this reorganize cycle.
     * <p>Registered at the point of use, as with the other tier stats: StatManager
     * drops a name it has never been told about, so a name registered only in the
     * constructor would publish nothing at all. All five are registered every
     * cycle whether or not that reason fired, so a zero can be told apart from a
     * stat that is not reporting.
     *
     * @param reasons     counts accumulated by both tier passes, keyed by reason
     * @param fastSoft    whether the fast tier was at or above its soft-eviction floor
     * @param highCapSoft whether the high-capacity tier was at or above its floor
     * @param fastMin     the fast tier's soft-eviction floor, so a sudden stop in
     *                    the soft counts is explicable from the log alone
     * @param highCapMin  the high-capacity tier's soft-eviction floor
     * @since 0.9.71+
     */
    private void publishPurgeReasonStats(Map<PurgeReason, Integer> reasons, boolean fastSoft,
                                         boolean highCapSoft, int fastMin, int highCapMin) {
        StatManager sm = _context.statManager();
        for (PurgeReason r : PurgeReason.values()) {
            sm.createRequiredRateStat("peer.tierEvicted." + r.getKey(),
                                      "Tier peers evicted for " + r.getKey() + " this cycle",
                                      "Peers", RATES);
            Integer n = reasons.get(r);
            sm.addRateData("peer.tierEvicted." + r.getKey(), n != null ? n.intValue() : 0,
                           60L * 1000);
        }
        // A tier below its floor evicts nothing for the soft reasons, which reads
        // identically to "nothing was bad" unless it is published too.
        sm.createRequiredRateStat("peer.tierEvicted.softPurgeActive",
                                  "1 per tier that was at or above its soft-eviction floor",
                                  "Tiers", RATES);
        sm.addRateData("peer.tierEvicted.softPurgeActive",
                       (fastSoft ? 1 : 0) + (highCapSoft ? 1 : 0), 60L * 1000);
        if (_log.shouldInfo()) {
            _log.info("Tier eviction -> Banned: " + count(reasons, PurgeReason.BANNED) +
                      " * Unreachable: " + count(reasons, PurgeReason.UNREACHABLE) +
                      " * Not-capable: " + count(reasons, PurgeReason.NOT_CAPABLE) +
                      "\n* Failures: " + count(reasons, PurgeReason.FAILURES) +
                      " * Acceptance: " + count(reasons, PurgeReason.ACCEPTANCE) +
                      "\n* Soft eviction withheld below -> Fast: " + fastMin +
                      " * High-cap: " + highCapMin);
        }
    }

    /**
     * Count of one eviction reason in this cycle's tally.
     *
     * @param reasons this cycle's eviction counts, keyed by purge reason
     * @param reason the reason whose count is wanted
     * @return the count, zero if that reason never fired
     * @since 0.9.71+
     */
    private static int count(Map<PurgeReason, Integer> reasons, PurgeReason reason) {
        Integer n = reasons.get(reason);
        return (n != null) ? n.intValue() : 0;
    }

    /**
     * The smallest tier size at which peers may be evicted for a soft reason.
     * <p>This is the tier's own minimum, because that number already means "this
     * tier is healthy": it is the floor the fill passes drive the tier back up
     * to, and the size below which the pools begin to starve. Judging how well a
     * peer performs is a luxury of a tier that has the peers it asked for.
     * <p>The bar used to be a tenth of the known profiles, capped at 100. The cap
     * disabled the protection the guard was written to provide: on a 4,335-profile
     * router it put the bar at 100, so a 227-peer fast tier - 23% of its
     * 1,000-peer minimum, the state this router was measured in for two days -
     * was pruned for acceptance and failures at full strength every cycle while
     * it was starving.
     * <p>This does not switch eviction off. The categorical reasons - banned,
     * unreachable, no longer bandwidth-capable - apply at any tier size, and the
     * soft reasons resume in full the moment the tier reaches its minimum.
     * <p>Pure decision - no context access, safe for unit tests.
     *
     * @param tierMin the tier's own minimum size, from
     *                {@link #getMinimumFastPeers(int)} or
     *                {@link #getMinimumHighCapacityPeers(int)}
     * @return the minimum tier size at which soft eviction applies; no size
     *         qualifies when the minimum is unknown
     * @since 0.9.71+
     */
    static int softPurgeThreshold(int tierMin) {
        if (tierMin <= 0) {return Integer.MAX_VALUE;}
        return tierMin;
    }

    /**
     * Why a peer listed in a tier should be evicted, or null to keep it.
     * <p>Extracted from {@link #purgeUnusableFromMap} so the precedence and the
     * gating are testable without a router context. The order is a precedence,
     * not a set of independent checks: a banned peer is reported as banned even
     * if it is also unreachable, because that is the most actionable fact about
     * it and the one an operator greps for.
     * <p>The three categorical reasons apply at any tier size. The two soft ones
     * require {@code softEvictionAllowed}, which the caller derives from the
     * tier's own minimum, so a starved tier keeps peers that are merely
     * underperforming while still losing the ones that must not be there at all.
     * <p>Pure decision — no context access, safe for unit tests.
     *
     * @param banned               the peer is banlisted
     * @param unreachable          the transport reports the peer unreachable
     * @param tierCapable          the peer still meets its tier's bandwidth class
     * @param softEvictionAllowed  whether the tier is above the soft-purge threshold
     * @param hasRecentFailures    the peer has sustained recent build/test failures
     * @param inLossProbation      the peer is inside its loss-probation window
     * @param lowAcceptance        the peer's tunnel acceptance is below the bar
     * @return the reason to evict for, or null to leave the peer in place
     * @since 0.9.71+
     */
    static PurgeReason purgeReasonFor(boolean banned, boolean unreachable, boolean tierCapable,
                                      boolean softEvictionAllowed, boolean hasRecentFailures,
                                      boolean inLossProbation, boolean lowAcceptance) {
        if (banned) {return PurgeReason.BANNED;}
        if (unreachable) {return PurgeReason.UNREACHABLE;}
        if (!tierCapable) {return PurgeReason.NOT_CAPABLE;}
        if (softEvictionAllowed && (hasRecentFailures || inLossProbation)) {
            return PurgeReason.FAILURES;
        }
        if (EVICTION_REQUIRES_ACCEPTANCE_BOTH_TIERS && softEvictionAllowed && lowAcceptance) {
            return PurgeReason.ACCEPTANCE;
        }
        return null;
    }

    /**
     * Evicts every peer in one tier map that {@link #purgeReasonFor} rejects.
     * <p>Acceptance is an eviction criterion for <em>both</em> tiers, even where
     * high-capacity admission ignores it. A peer that declines too many of our
     * build requests is wasted work whichever tier lists it, and leaving such a
     * peer listed in the fast tier in particular means it keeps being offered as
     * a first hop. Admission is the permissive side of that trade; this is what
     * stops the permissiveness from accumulating poor accepters.
     * <p>Only {@code PurgeReason.BANNED} and {@code PurgeReason.UNREACHABLE} can
     * be first to fire here for a peer the rebuild had already admitted; the
     * other three are re-checks of gates the rebuild applied. See
     * {@link #purgeUnusableFromTiers} for why that makes the steady-state counts
     * near zero.
     *
     * @param tier the tier map to purge, mutated in place
     * @param tierName "fast" or "high-cap" for the log message
     * @param now current time in ms
     * @param buildSuccess the build success ratio in [0.0, 1.0], which relaxes the
     *                     acceptance threshold under stress so the tiers cannot be
     *                     emptied by a network-wide event
     * @param allowSoftEviction whether this tier is above
     *                          {@link #softPurgeThreshold(int)}, gating the soft reasons
     * @param reasons tally shared with the other tier's pass, accumulated in place
     * @since 0.9.71+
     */
    private void purgeUnusableFromMap(Map<Hash, PeerProfile> tier, String tierName, long now,
                                      double buildSuccess, boolean allowSoftEviction,
                                      Map<PurgeReason, Integer> reasons) {
        boolean isFast = (tier == _fastPeers);
        Iterator<Map.Entry<Hash, PeerProfile>> it = tier.entrySet().iterator();
        while (it.hasNext()) {
            PeerProfile profile = it.next().getValue();
            Hash peer = profile.getPeer();
            PurgeReason reason = purgeReasonFor(
                    _context.banlist() != null && _context.banlist().isBanlisted(peer),
                    _context.commSystem() != null && _context.commSystem().wasUnreachable(peer),
                    isFast ? isFastTierCapable(peer) : isHighBandwidthCapable(peer),
                    allowSoftEviction,
                    hasRecentTunnelFailures(profile),
                    inLossProbation(profile, now),
                    // Eviction judges on the same sample floor the tier's
                    // admission does: fifty for high capacity, whose admission
                    // demands fifty, five for fast. Judging on one sample is how
                    // a peer that declined a single request left both tiers.
                    isLowTunnelAcceptance(profile, buildSuccess, now,
                                          isFast ? FAST_ACCEPTANCE_SAMPLES : MIN_TUNNEL_REQUESTS));
            if (reason != null) {
                if (_log.shouldDebug()) {
                    _log.debug("Purging peer [" + peer.toBase64().substring(0, 6) +
                               "] from " + tierName + " tier: " + reason.getKey());
                }
                it.remove();
                Integer n = reasons.get(reason);
                reasons.put(reason, Integer.valueOf(n != null ? n.intValue() + 1 : 1));
                if (isFast && isQualityFastPeer(profile, _highCapacityPeers))
                    _fastQualityCount--;
            }
        }
    }

    /**
     * Whether a shrunken tier needs restoration: below half its old size
     * and the old size was substantial.  Prevents starvation when
     * thresholds shift unfavorably.
     * <p>
     * Pure decision — no context access, safe for unit tests.
     *
     * @param currentSize current tier size
     * @param oldSize pre-reorganize tier size
     * @return whether the tier should be restored
     * @since 0.9.71+
     */
    static boolean needsTierRestore(int currentSize, int oldSize) {
        return needsTierRestore(currentSize, oldSize, 0);
    }

    /**
     * As above, with the tier's own minimum included as a second trigger.
     * <p>The half-of-previous rule can only fire on a one-cycle collapse, and the
     * lossage measured here was not a collapse: the fast tier fell from 1,104 to
     * 192 over two days, one reorganize at a time, so every cycle's "previous
     * size" was already small and the trigger never fired. Over 48 hours the
     * router spent 47 of them below its 1,000-peer minimum with a restore
     * mechanism that reported nothing to do.
     * <p>So: restore whenever the tier is below the size it asked for and the
     * pre-reorganize snapshot holds peers the rebuild did not keep. The restore
     * path is not unconditional - each candidate still passes the basic gates,
     * the capability gate and the recent-failure gate, and when the tier is
     * starved the acceptance and loss-probation gates are relaxed (see
     * {@link #isRestorableTierPeer}) - so this widens when we look, not what we
     * accept.
     *
     * @param currentSize current tier size
     * @param oldSize pre-reorganize tier size
     * @param tierMin the tier's minimum, from
     *                {@link #getMinimumFastPeers(int)} or
     *                {@link #getMinimumHighCapacityPeers(int)}; non-positive
     *                selects the collapse rule alone
     * @return whether the tier should be restored
     * @since 0.9.72+ (the {@code tierMin} parameter)
     */
    static boolean needsTierRestore(int currentSize, int oldSize, int tierMin) {
        // The collapse rule, written out: calling the two-argument overload from
        // here would recurse straight back into this method.
        if (currentSize < oldSize / 2 && oldSize > 100) {return true;}
        return tierMin > 0 && currentSize < tierMin && oldSize > currentSize;
    }

    /**
     * Whether a preserved pre-reorganize tier entry may be re-added: not
     * already present in the tier, selectable, no recent tunnel failures,
     * not in loss probation (fast tier) or below the loss demotion
     * threshold (high-cap tier), and acceptable tunnel acceptance.
     * Evaluation order matches the original restore loops.
     * <p>When the tier is {@code starved}, the acceptance and loss gates are
     * skipped. Both are soft quality signals, and both are ratchets: acceptance
     * is judged on counts that decay multiplicatively, so a peer below the bar
     * cannot raise it without new agreements, and new agreements need the tier
     * membership this gate is withholding. Refusing a peer for being short of a
     * quality bar, in a tier that is short of peers, is the collapse this path
     * exists to undo. The categorical gates - banlist, unreachable, no longer
     * bandwidth-capable, excessive lifetime failures, failures in the last hour -
     * apply at any tier size.
     *
     * @param peer the peer hash
     * @param profile the preserved profile
     * @param tier the tier map being restored into
     * @param buildSuccess the build success ratio in [0.0, 1.0]
     * @param now current time in ms
     * @param highCap whether restoring the high-cap tier (loss-demotion
     *                gate instead of loss probation)
     * @param starved whether the tier is below its minimum, relaxing the soft gates
     * @return whether the peer may be restored
     * @since 0.9.72+ (the {@code starved} parameter)
     */
    private boolean isRestorableTierPeer(Hash peer, PeerProfile profile, Map<Hash, PeerProfile> tier,
                                         double buildSuccess, long now, boolean highCap,
                                         boolean starved) {
        if (tier.containsKey(peer)) return false;
        // Use basic gates instead of isSelectable to avoid stale RouterInfo
        // proof-of-life filtering peers that were already vetted at tier entry.
        if (!passesBasicGates(peer) || isExcessiveLifetimeFailure(profile, now)) return false;
        if (hasRecentTunnelFailures(profile)) return false;
        if (starved) return true;
        if (highCap ? hasHighLoss(profile, now) : inLossProbation(profile, now)) return false;
        // Acceptance is checked for both tiers here even though high-capacity
        // admission ignores it. Admission is deliberately permissive so the tier
        // is a deep reservoir; eviction is what keeps poor accepters out. A
        // restoration path that skipped the check would reinstate on the next
        // cycle exactly the peers eviction just removed. Same sample floor as
        // eviction, so a peer cannot be evicted under one bar and restored under
        // a looser one.
        return !isLowTunnelAcceptance(profile, buildSuccess, now, FAST_ACCEPTANCE_SAMPLES);
    }

    /**
     * Re-adds preserved pre-reorganize tier entries when the rebuild shrank
     * the tiers too much.  Must be called with the write lock held.
     *
     * @param now current time in ms, the reference for the loss and acceptance checks
     * @param oldFastPeers the fast tier's peer hashes as they were before the rebuild
     * @param oldHighCapPeers the high-capacity tier's hashes as they were before the
     *                        rebuild
     * @param buildSuccess the build success ratio in [0.0, 1.0], used by the
     *                     selectability and acceptance gates
     * @param known the routers in the network database, which sets the tier minimums
     * @since 0.9.72+ (the {@code known} parameter)
     */
    private void restorePreservedPeers(long now, Set<Hash> oldFastPeers,
                                       Set<Hash> oldHighCapPeers, double buildSuccess, int known) {
        // Prevents starvation when thresholds shift unfavorably — old entries that remain
        // selectable (no recent failures, no ban, no stale RI) are re-added.
        //
        // The profile is re-read from _notFailingPeers rather than carried in the
        // snapshot: a peer the rebuild dropped has nothing usable to restore, and
        // skipping it stops this fallback from resurrecting a profile that expired
        // or went unreachable in this very reorganize.
        int minFast = getMinimumFastPeers(known);
        int oldFastSize = oldFastPeers.size();
        boolean fastStarved = _fastPeers.size() < minFast;
        if (needsTierRestore(_fastPeers.size(), oldFastSize, minFast)) {
            // Aim at the tier minimum, not only at the old size: on a tier that
            // has been short for days, the old size is short too, and stopping at
            // it leaves the tier exactly as starved as it was.
            int target = Math.max(oldFastSize, minFast);
            int restored = 0;
            for (Hash peer : oldFastPeers) {
                if (_fastPeers.size() >= target) break;
                PeerProfile profile = lockedGetProfile(peer);
                if (profile == null) continue;
                if (isFastTierCapable(peer) &&
                    isRestorableTierPeer(peer, profile, _fastPeers, buildSuccess, now, false, fastStarved)) {
                    putFastPeer(peer, profile);
                    clearLossIfReadmitted(profile);
                    restored++;
                }
            }
            if (_log.shouldInfo() && restored > 0) {
                _log.info("Tier fallback -> Restored " + restored + " peers to fast tier (was " +
                          (_fastPeers.size() - restored) + ", old " + oldFastSize +
                          " * Target: " + target + ")");
            }
        }
        int minHighCap = getMinimumHighCapacityPeers(known);
        int oldHighCapSize = oldHighCapPeers.size();
        boolean highCapStarved = _highCapacityPeers.size() < minHighCap;
        if (needsTierRestore(_highCapacityPeers.size(), oldHighCapSize, minHighCap)) {
            int target = Math.max(oldHighCapSize, minHighCap);
            int restored = 0;
            for (Hash peer : oldHighCapPeers) {
                if (_highCapacityPeers.size() >= target) break;
                PeerProfile profile = lockedGetProfile(peer);
                if (profile == null) continue;
                if (isHighBandwidthCapable(peer) &&
                    isRestorableTierPeer(peer, profile, _highCapacityPeers, buildSuccess, now, true, highCapStarved)) {
                    _highCapacityPeers.put(peer, profile);
                    restored++;
                }
            }
            if (_log.shouldInfo() && restored > 0) {
                _log.info("Tier fallback -> Restored " + restored + " peers to high-cap tier (was " +
                          (_highCapacityPeers.size() - restored) + ", old " + oldHighCapSize +
                          " * Target: " + target + ")");
            }
        }
    }

    /**
     * Counts profiles with recent activity, over the same set as
     * {@link #countActivePeersInLastHour} so the published gauge and the on-demand scan
     * agree. Called during the reorganise, which already holds the write lock.
     *
     * @param now current time in ms; activity is anything at or after
     *            now minus {@link #ACTIVE_WINDOW_MS}
     * @return how many not-failing profiles are active inside the window
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
     * Counts quality peers (fast/high-cap with good acceptance plus recent activity).
     *
     * @param now current time in ms; the activity cutoff is 24 hours before it
     * @param buildSuccess the build success ratio in [0.0, 1.0], which scales the
     *                     acceptance requirement
     * @return how many distinct tier peers pass the quality test, each peer counted once
     */
    private int countQualityPeers(long now, double buildSuccess) {
        int qualityCount = 0;
        long recentCutoff = now - 24 * 60 * 60 * 1000L; // 24 hours
        // Each peer counted at most once. The tiers overlap - high capacity is a
        // superset of fast in practice - so a peer listed in both must be visited
        // once, not twice. Deduping on tier membership rather than on latency
        // keeps the number stable: latency flags arrive and change over time, so
        // a flag-keyed dedup would make this count drift as measurement arrives
        // rather than only as peers do.
        for (PeerProfile profile : _fastPeers.values()) {
            if (isQualityPeer(profile, recentCutoff, buildSuccess)) qualityCount++;
        }
        for (Map.Entry<Hash, PeerProfile> e : _highCapacityPeers.entrySet()) {
            if (_fastPeers.containsKey(e.getKey())) {continue;}
            if (isQualityPeer(e.getValue(), recentCutoff, buildSuccess)) qualityCount++;
        }
        return qualityCount;
    }

    /**
     * Remove profile files that are no longer worth keeping: those whose peer is
     * genuinely stale at any size, and those beyond the disk retention floor.
     * <p>See {@link ProfilePersistenceHelper#purgeExcessProfiles} for the two rules and
     * why neither lets a recently active peer be deleted for its bandwidth tier.
     */
    public void purgeStaleProfileFiles() {
        int retentionFloor = MAX_STORED_PROFILE_FILES;

        // selectAllPeers() materializes every tracked hash and the purge only acts on
        // files outside it, so skip both while the known on-disk count is well under the
        // floor. The 10% margin covers the count being stale: it is refreshed only on the
        // store/load/delete cycle (every STORE_TIME, 15 min) while reorganize runs every
        // 30-250s. It also defers genuinely-stale cleanup below the margin, which is the
        // conservative direction: files are retained rather than deleted early.
        int stored = getStoredProfileCount();
        if (stored > 0 && stored <= retentionFloor * 9 / 10) return;

        // Peers still held in memory, whose files must survive.
        Set<Hash> activePeers = selectAllPeers(); // includes fast, high-cap, not-failing

        _persistenceHelper.purgeExcessProfiles(activePeers, retentionFloor);
    }

    /**
     * Helper to calculate capacity threshold from a pre-filtered set of active profiles.
     * Avoids re-iterating the full set multiple times.
     *
     * @param activeProfiles the pre-filtered profiles whose capacity values are sampled
     * @param totalPeers the size of that set, used as the mean divisor and as the
     *                   highest index read; 0 skips the sampling entirely
     * @return the capacity threshold, {@link CapacityCalculator#GROWTH_FACTOR} when
     *         there are no peers to sample
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
     * How many of the given capacities are strictly above the mean.
     * <p>Scanned rather than derived from an index into the sorted array: the
     * mean is a repeated value whenever capacities cluster, and
     * {@code Arrays.binarySearch} returns only <em>some</em> index among equal
     * entries, so an index-derived count silently includes peers whose capacity
     * merely equals the mean and can flip the {@code >= minHighCap} branch.
     * The scan is over a primitive array the sort above already walked.
     *
     * @param capacities the capacities; the caller holds them sorted, but the
     *                   count does not depend on their order
     * @param meanCapacity the mean to compare against
     * @return the number of entries greater than the mean, never negative
     * @since 0.9.71+
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
     * <li>Peers in _fastPeers (fast + reliable)</li>
     * <li>Peers in _highCapacityPeers</li>
     * <li>Peers with recent activity (last 48 hours)</li>
     * <li>Peers with higher capacity value</li>
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

        // Under network stress, halve the eviction rate rather than stopping it.
        // Churn makes recovery harder, so a degraded network is a bad time to be
        // dropping peer knowledge — and a wholesale skip is worse than the churn
        // it avoids. This gate sits on a composite of six rate stats (expire /
        // reject / success, client and exploratory); while it holds, the store
        // stopped being pruned for as long as the condition lasted, which is
        // unbounded. Half the eviction bounds the growth at half the churn.
        boolean degraded = isLowBuildSuccess();
        if (degraded && _log.shouldWarn()) {
            _log.warn("Low tunnel build success (" + getTunnelBuildSuccess()
                      + " < " + ATTACK_THRESHOLD
                      + ") -> Halving profile eviction to preserve peer data");
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

        // Order by staleness first, capacity as the tiebreak: evict the peers we have
        // learned least about recently, and among equally stale ones those we learned
        // least about overall. Capacity alone was the previous key, which ranked a peer
        // contacted yesterday beside one untouched for a month and let the older survive
        // on a capacity margin.
        candidates.sort(STALENESS_THEN_CAPACITY);

        int toEvict = evictionDeficit(_notFailingPeers.size(), maxProfiles, degraded);
        int evicted = 0;
        Iterator<PeerProfile> iter = candidates.iterator();

        while (evicted < toEvict && iter.hasNext()) {
            PeerProfile profile = iter.next();
            Hash peer = profile.getPeer();

            // Remove from all structures
            _notFailingPeers.remove(peer);
            _notFailingPeersList.remove(peer); // O(n), but acceptable for rare eviction
            _strictCapacityOrder.remove(profile);
            // A tier member is only evictable while its tier is over the limit, per
            // isEvictable() below, so it can be evicted here with the tier entry still
            // live. Left alone, that entry would keep selecting a peer whose profile has
            // left _notFailingPeers and therefore no longer has its capacity value or
            // peer-test times refreshed. Drop it, through removeFastPeer() rather than
            // _fastPeers.remove() so _fastQualityCount stays in step; it gates promotion
            // at MIN_FAST_QUALITY_COUNT and drifts silently if incremented by hand.
            //
            // No replacement is promoted: the store is over the cap that excluded this
            // peer, so promoting another would evict one to add one, indefinitely.
            // The next reorganize refills the tiers through its own fallbacks.
            removeFastPeer(peer);
            _highCapacityPeers.remove(peer);

            evicted++;
        }

        if (_log.shouldInfo()) {
            _log.info("Evicted " + evicted + " low-priority profiles to enforce cap (" + maxProfiles + ")");
        }
    }

    /**
     * How many profiles to evict from a store that is over its cap.
     * <p>Under network stress the deficit is halved, not skipped: the store keeps
     * being pruned, at half the churn, so a long degraded period cannot grow it
     * without bound. The halving floors, so an odd deficit of one evicts nothing
     * while degraded — the conservative direction for a store we are protecting.
     * <p>Pure decision — no context access, safe for unit tests.
     *
     * @param storedProfiles how many profiles are held in memory
     * @param maxProfiles the cap in force, already clamped
     * @param degraded whether the tunnel build success ratio is below
     *                 {@link #ATTACK_THRESHOLD}
     * @return how many profiles to evict; zero at or under the cap
     * @since 0.9.72+
     */
    static int evictionDeficit(int storedProfiles, int maxProfiles, boolean degraded) {
        int toEvict = storedProfiles - maxProfiles;
        if (toEvict <= 0) {return 0;}
        return degraded ? toEvict / 2 : toEvict;
    }

    /**
     * Clamps the configured max-profiles value into [100, ABSOLUTE_MAX_PROFILES].
     * <p>
     * Pure decision — no context access, safe for unit tests.
     *
     * @param maxProfiles the configured or default value
     * @return the clamped value
     * @since 0.9.71+
     */
    static int clampMaxProfiles(int maxProfiles) {
        if (maxProfiles < 100) maxProfiles = 100;
        if (maxProfiles > ABSOLUTE_MAX_PROFILES) maxProfiles = ABSOLUTE_MAX_PROFILES;
        return maxProfiles;
    }

    /**
     * Whether a profile is eligible for eviction under the profile cap:
     * not protected by a fast/high-cap tier slot that still has room below
     * its limit, and no send or heard-from activity within the active window.
     * <p>
     * Pure decision. No context access, safe for unit tests.
     *
     * @param profile the profile under consideration
     * @param fastPeers the current fast tier map
     * @param highCapPeers the current high-cap tier map
     * @param activeThreshold the activity window cutoff (now - 48h)
     * @param fastPeerLimit fast tier size at or below which its members keep
     * eviction protection; above it, fast members are evictable like any other
     * @param highCapacityLimit high-cap tier size at or below which its members
     * keep eviction protection; above it, high-cap members are evictable
     * @return whether the profile may be evicted
     * @since 0.9.71+
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
     * Whether the peer's last activity (send, heard-from, or heard-about)
     * is older than the absent threshold. I.e. it has been absent from
     * the netDb for at least that long.  A peer that was never contacted
     * (all-zero timestamps) counts as stale.
     * <p>
     * Pure decision — no context access, safe for unit tests.
     *
     * @param profile profile whose newest send, heard-from or heard-about timestamp sets the age
     * @param now current time in ms
     * @param absentThreshold stale threshold in ms
     * @return whether the peer is stale-absent
     * @since 0.9.71+
     */
    static boolean isStaleAbsentPeer(PeerProfile profile, long now, long absentThreshold) {
        long lastActivity = Math.max(profile.getLastSendSuccessful(),
                              Math.max(profile.getLastHeardFrom(), profile.getLastHeardAbout()));
        return now - lastActivity > absentThreshold;
    }

    /**
     * Evict profiles for peers no longer in the network database.
     * Keeps profile files on disk — they'll be reloaded if the peer reappears.
     * Acquires the write lock.
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
            releaseWriteLockAndRefresh();
        }
    }

    /**
     * Interpolates the capacity threshold for selection.
     *
     * @param meanCapacity the mean capacity of the peers
     * @param numExceedingMean peers above the mean
     * @param totalPeers the peers considered
     * @param thresholdAtMedian the threshold when half are above
     * @param thresholdAtMinHighCap the threshold at the minimum high cap
     * @param thresholdAtLowest the threshold at the lowest point
     * @return the capacity threshold
     */
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

    /**
     * Computes the speed threshold over the high-capacity peers.
     *
     * @param reordered profiles ordered by capacity
     * @param now the current time
     * @param capacityThreshold the capacity threshold in effect
     * @return the speed threshold
     */
    private double calculateSpeedThreshold(Set<PeerProfile> reordered, long now, double capacityThreshold) {
        PeerProfile boundary = selectSpeedBoundary(reordered, now, capacityThreshold);
        if (boundary == null) return 0;

        // Record the boundary peer's measured latency for display and diagnostics
        _thresholdRTT = boundary.getTunnelTestTimeAverage();
        // Capture the baseline RTT on the first reorganize pass.  This is used
        // to floor the adaptive ceiling when the network is degraded, preventing
        // RTT ceiling amplification where the ceiling rises with the network
        // and peers that would cause failures are allowed through.
        // Re-anchor the baseline on a rolling window, not once at startup. The
        // degraded-mode ceiling is floored at 2x this value, so a boundary
        // measured in the router's first minutes set the tightest ceiling a peer
        // had to clear for the life of the process.
        if (_thresholdRTT > 0 && shouldReanchorBaseline(_baselineRTTTime, now)) {
            _baselineRTT = _thresholdRTT;
            _baselineRTTTime = now;
        }
        return boundary.getSpeedValue();
    }

    /**
     * Rank boundary for the speed threshold: the top 30% of qualifying peers,
     * capped at 50.  0-based, so rank 0 is the fastest.
     * <p>
     * The qualifying count drives the rank, never the profile count: the profile
     * count is an order of magnitude larger and would pin the rank at the cap,
     * asking for a peer that does not exist.
     * <p>
     * Pure decision. No context access, safe for unit tests.
     *
     * @param qualifyingCount number of peers that passed the capacity and activity gates
     * @return the rank to read the boundary peer from, or 0 for an empty set
     * @since 0.9.71+
     */
    static int speedThresholdCutoff(int qualifyingCount) {
        if (qualifyingCount <= 0) return 0;
        return Math.min((int) (qualifyingCount * 0.3), 50);
    }

    /**
     * The profile whose speed becomes the speed threshold: the one at rank
     * {@link #speedThresholdCutoff(int)} of the qualifying set, ranked fastest
     * first.  Only that one profile is ever read, and the rank is capped at 50,
     * so a bounded top-K selection replaces a full sort of every profile.
     *
     * @param profiles candidates, not necessarily sorted
     * @param now current time in ms, for the activity test
     * @param capacityThreshold minimum capacity to qualify
     * @return the boundary profile, or null if none qualifies
     * @since 0.9.71+
     */
    static PeerProfile selectSpeedBoundary(Collection<PeerProfile> profiles, long now,
                                           double capacityThreshold) {
        int qualifying = countQualifying(profiles, now, capacityThreshold);
        if (qualifying == 0) return null;
        return selectNthFastest(profiles, now, capacityThreshold, speedThresholdCutoff(qualifying) + 1);
    }

    /**
     * Whether a profile qualifies for the speed-threshold ranking: enough
     * capacity to count and recently active.  One definition, shared by the
     * qualifying count and the bounded selection so the two passes cannot
     * disagree about who is in the set.
     *
     * @param profile the profile to test
     * @param now current time in ms, for the activity test
     * @param capacityThreshold minimum capacity to qualify
     * @return true if the profile takes part in the speed ranking
     * @since 0.9.71+
     */
    private static boolean qualifiesForSpeedRank(PeerProfile profile, long now, double capacityThreshold) {
        return profile.getCapacityValue() >= capacityThreshold && profile.getIsActive(now);
    }

    /**
     * How many of the given profiles qualify for the speed-threshold ranking.
     * The rank read from the ranked set is a fraction of this count, not of the
     * profile count, so it has to be known before the boundary is selected.
     *
     * @param profiles candidates, not necessarily sorted
     * @param now current time in ms, for the activity test
     * @param capacityThreshold minimum capacity to qualify
     * @return the qualifying count, never negative
     * @since 0.9.71+
     */
    static int countQualifying(Collection<PeerProfile> profiles, long now, double capacityThreshold) {
        int qualifying = 0;
        for (PeerProfile profile : profiles) {
            if (qualifiesForSpeedRank(profile, now, capacityThreshold)) qualifying++;
        }
        return qualifying;
    }

    /**
     * Select the {@code k}-th fastest qualifying profile (1-based) without
     * sorting the whole candidate set.
     * <p>Holds the {@code k} fastest profiles seen so far in an unsorted bounded
     * array plus the index of the slowest: a slower profile is discarded in
     * O(1), one that displaces the slowest costs a {@code k}-element rescan.
     * Worst case O(n·k), and {@code k} is at most 51 for the only caller.
     * <p>Ties in speed may resolve to a different peer of equal speed than a
     * full sort would have picked; only the boundary value is read.
     *
     * @param profiles candidates, not necessarily sorted
     * @param now current time in ms, for the activity test
     * @param capacityThreshold minimum capacity to qualify
     * @param k how many of the fastest to keep, 1-based
     * @return the k-th fastest qualifying profile, or null if fewer than k qualify
     * @since 0.9.71+
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
     * Fetch a peer's local RouterInfo without re-running the network database's
     * {@code validate()}.
     * <p>Every caller here reads raw RouterInfo content (tier, capabilities,
     * addresses) and applies its own gate, so nothing depends on validation
     * rejecting an entry that is present. It already ran when the entry was
     * stored.  Callers must not use this to evict: the validating lookup's
     * failure path calls {@code fail(key)}, which is the netDb's job.
     * <p>Falls back to {@code lookupRouterInfoLocally} on a miss, needed only
     * for {@code DummyNetworkDatabaseFacade}: it serves
     * {@code lookupRouterInfoLocally} from its own map and returns null from
     * {@code lookupLocallyWithoutValidation}.
     *
     * @param peer the peer hash
     * @return the local RouterInfo, or null if absent or not a RouterInfo
     * @since 0.9.71+
     */
    private RouterInfo lookupRouterInfoUnvalidated(Hash peer) {
        return lookupRouterInfoUnvalidated(_context, peer);
    }

    /**
     * {@link #lookupRouterInfoUnvalidated(Hash)} with the context passed in, so
     * the fingerprint builder below is reachable from tests.
     *
     * @param ctx the context holding the NetDb to look the peer up in
     * @param peer the peer hash
     * @return the local RouterInfo, or null if absent or not a RouterInfo
     * @since 0.9.71+
     */
    static RouterInfo lookupRouterInfoUnvalidated(RouterContext ctx, Hash peer) {
        NetworkDatabaseFacade netDb = ctx.netDb();
        if (netDb == null) return null;
        DatabaseEntry ds = netDb.lookupLocallyWithoutValidation(peer);
        if (ds != null) {
            return ds.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO ? (RouterInfo) ds : null;
        }
        return netDb.lookupRouterInfoLocally(peer);
    }

    /**
     * Check if a peer should be excluded from profiling.
     * Excludes low bandwidth tiers (K, L, M, Unknown) and G cap (no tunnels).
     * <p>This is the single gate in front of profile creation, and it covers all
     * three ways one can be made: {@link #getOrCreateProfileNonblocking},
     * {@link #addProfile} (which is how stored profiles are loaded at startup), and
     * the periodic sweep. A peer failing it therefore has no profile at all, so it
     * costs no RAM and cannot be written to disk.
     * <p>The bandwidth half of this check was documented here but never performed:
     * the body tested only the no-tunnels capability, while the tier test sat in
     * {@link #isLowBandwidthTierName} with no caller in the codebase. Peers advertising
     * K, L, M or Unknown were profiled, tracked and persisted despite advertising
     * that they will not usefully host a tunnel.
     *
     * @param peer the peer hash
     * @return true if the peer should not be profiled
     * @since 0.9.70+
     */
    boolean isExcludedFromProfiling(Hash peer) {
        RouterInfo peerInfo = lookupRouterInfoUnvalidated(peer);
        if (peerInfo == null) {return true;}
        return isExcludedFromProfiling(peerInfo.getCapabilities(), peerInfo.getBandwidthTier());
    }

    /**
     * Should a peer advertising these capabilities and bandwidth tier be profiled?
     * <p>Pure decision form of {@link #isExcludedFromProfiling(Hash)}, so the rule
     * can be pinned without a RouterInfo or a network database.
     *
     * @param capabilities the peer's advertised capabilities, never null
     * @param bandwidthTier the peer's advertised bandwidth tier
     * @return true if no profile should be created for the peer
     * @since 0.9.71+
     */
    static boolean isExcludedFromProfiling(String capabilities, String bandwidthTier) {
        if (capabilities == null) {return true;}
        if (capabilities.indexOf(Router.CAPABILITY_NO_TUNNELS) >= 0) {return true;}
        return isLowBandwidthTierName(bandwidthTier);
    }

    /**
     * Whether a bandwidth tier name is one of the tiers excluded from
     * profiling.  Pure decision — no context access, safe for unit tests.
     *
     * @param tier the advertised bandwidth tier
     * @return true for K, L, M, or Unknown
     * @since 0.9.71+
     */
    static boolean isLowBandwidthTierName(String tier) {
        return "K".equals(tier) || "L".equals(tier) || "M".equals(tier) || "Unknown".equals(tier);
    }

    /**
     * Check if a peer has a congestion capability cap (D or E).
     *
     * @param peer the peer whose advertised capabilities are inspected; a peer with no
     *             RouterInfo in the database is not treated as congested
     * @return true if the peer is moderately or severely congested
     * @since 0.9.70+
     */
    private boolean isCongestedPeer(Hash peer) {
        RouterInfo peerInfo = lookupRouterInfoUnvalidated(peer);
        if (peerInfo == null) return false;
        String caps = peerInfo.getCapabilities();
        return caps.indexOf(Router.CAPABILITY_CONGESTION_MODERATE) >= 0 ||
               caps.indexOf(Router.CAPABILITY_CONGESTION_SEVERE) >= 0;
    }

    /**
     * Whether the peer qualifies for the high-capacity tier based on
     * advertised bandwidth tier and capabilities.  Requires X/P/O bandwidth
     * tier and no D (congestion), E (severe congestion), G (no tunnels),
     * or U (firewalled/unreachable) capability flags.
     * <p>Bandwidth tier is a fact about the peer rather than an observation
     * that can erode, so these peers are eligible for fast-tier fast-track
     * regardless of measured throughput.
     *
     * @param peer the peer to check
     * @return true if X/P/O tier and no D/E/G/U caps
     * @since 0.9.71+
     */
    private boolean isHighBandwidthCapable(Hash peer) {
        RouterInfo peerInfo = lookupRouterInfoUnvalidated(peer);
        if (peerInfo == null) return false;
        return qualifiesHighBandwidthTier(peerInfo.getBandwidthTier(), peerInfo.getCapabilities());
    }

    /**
     * Whether a bandwidth tier and capability string together qualify the peer
     * for the high-capacity / fast tiers: X/P/O tier and none of the D (moderate
     * congestion), E (severe congestion), G (no tunnels) or U (unreachable)
     * capability flags.
     * <p>
     * Pure decision — no context access, so a (tier, caps) pair already in hand
     * can be tested without a router.
     *
     * @param tier the advertised bandwidth tier
     * @param caps the peer's capability string
     * @return true if X/P/O tier and no D/E/G/U caps
     * @since 0.9.71+
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
     * Get the high-capacity RTT ceiling for display and diagnostics.
     * High-cap has no RTT gate — acceptance ratio and loss gates
     * handle quality.  Returns 0 (no ceiling).
     *
     * @return 0 (high-cap has no RTT ceiling)
     * @since 0.9.71+
     */
    public double getHighCapRTTThreshold() {return 0;}

    /**
     * Returns a peer's profile with the read lock held.
     *
     * @param peer the peer hash
     * @return the profile, or null if unknown
     */
    private PeerProfile lockedGetProfile(Hash peer) {
        return _notFailingPeers.get(peer);
    }

    /**
     * Minimum candidates to gate-check for a single-peer selection.  The sample
     * is the per-attempt lottery size, not the whole reachable set: the scan
     * start is rotated (see {@link #lockedSelectPeers}) so selections are
     * spread across the tier instead of re-examining its first few entries.
     * <p>This floor, not the {@code howMany * 20} term, is what governs
     * single-hop selection: every first-hop and last-hop call passes
     * {@code howMany = 1}, giving {@code max(20, MIN)}.  The floor therefore
     * sets how many candidates the caller's first-hop quality loop has to
     * reject from before it relaxes its transport-session requirement, and
     * that loop is what stands between a build and a gateway that cannot
     * receive it.  A thin sample makes the loop run dry and descend rather
     * than reject.
     * <p>Raised from 64 to {@link #DEFAULT_MIN_CANDIDATE_SAMPLE} (256).  Note
     * this is 12x the {@code howMany * 20} term the formula intends, so the
     * constant is the operative value for hop selection and is treated as a
     * tunable rather than a detail.  Configurable via
     * {@code profileOrganizer.minCandidateSample} so it can be raised to 512
     * without a rebuild if 256 proves insufficient.
     *
     * @since 0.9.71+
     */
    /** Default floor for candidates examined per selection. @since 0.9.71+. */
    static final int DEFAULT_MIN_CANDIDATE_SAMPLE = 256;
    /** Upper bound, so a typo cannot turn selection into a full tier scan. @since 0.9.71+. */
    static final int MAX_CANDIDATE_SAMPLE = 1024;
    /** Property for the floor; see {@link #getMinCandidateSample}. */
    public static final String PROP_MIN_CANDIDATE_SAMPLE = "profileOrganizer.minCandidateSample";
    /** Minimum candidate sample size for threshold math. */
    private static volatile int _minCandidateSample = DEFAULT_MIN_CANDIDATE_SAMPLE;

    /**
     * Read the candidate-sample floor, honouring the configured override.
     * Clamped to {@code [16, }{@link #MAX_CANDIDATE_SAMPLE}{@code ]} so a bad
     * value degrades to a sane range rather than scanning the whole tier or
     * collapsing to a single candidate.
     *
     * @param ctx the router context
     * @return the floor to use for {@code maxCandidateSample}
     * @since 0.9.71+
     */
    public static int getMinCandidateSample(RouterContext ctx) {
        if (ctx == null) {return _minCandidateSample;}
        return Math.max(16, Math.min(MAX_CANDIDATE_SAMPLE,
                                    ctx.getProperty(PROP_MIN_CANDIDATE_SAMPLE, _minCandidateSample)));
    }

    /**
     * Override the candidate-sample floor at runtime.
     * @param val the new floor, clamped to {@code [16, }{@link #MAX_CANDIDATE_SAMPLE}{@code ]}
     * @since 0.9.71+
     */
    public static void setMinCandidateSample(int val) {
        _minCandidateSample = Math.max(16, Math.min(MAX_CANDIDATE_SAMPLE, val));
    }

    /**
     * Minimum delay between "tier returned no candidates" warnings.  A starved
     * tier is the signal that one gate is rejecting everything, so the warning
     * carries the per-gate counts. It must not flood the log while starvation
     * persists across build passes.
     *
     * @since 0.9.71+
     */
    private static final long STARVE_WARN_INTERVAL_MS = 60 * 1000;

    /**
     * Cap on how many tier peers to gate-check when selecting {@code howMany}
     * tunnels.  Scanning all ~670 fast peers per selection was a CPU hot path;
     * a 20× sample (min 20) cut work ~10× on large tiers but, because HashMap
     * iteration order is stable, every selection examined the same prefix of
     * the tier — the rest of it stayed unreachable while that prefix kept
     * passing, so tier size never became choice.  With the scan start rotated,
     * the sample is the size of one attempt's lottery: 64 gives
     * lockedPickLowestPriority a real low-latency pick while remaining an
     * order of magnitude cheaper than a full tier scan.  A 10× sample was tried
     * before the original 20× and hurt build success, so the 20× multiplier for
     * larger requests is kept.
     *
     * @param howMany tunnels requested (may be 0 or negative)
     * @param peerCount size of the tier map (may be 0)
     * @return maximum candidates to examine, never negative
     * @since 0.9.71+
     */
    static int maxCandidateSample(int howMany, int peerCount) {
        return maxCandidateSample(howMany, peerCount, _minCandidateSample);
    }

    /**
     * Candidate-sample cap for a configurable floor.  Split out from
     * {@link #maxCandidateSample(int, int)} so the arithmetic is testable
     * without a RouterContext.
     *
     * @param howMany peers the caller wants
     * @param peerCount size of the tier being sampled
     * @param floor minimum candidates to examine regardless of {@code howMany}
     * @return maximum candidates to examine, never negative
     * @since 0.9.71+
     */
    static int maxCandidateSample(int howMany, int peerCount, int floor) {
        if (peerCount <= 0)
            return 0;
        int need = Math.max(howMany, 1) * 20;
        return Math.min(peerCount, Math.max(need, floor));
    }

    /**
     * Selects peers while holding the lock.
     *
     * @param peers the profiles to choose from
     * @param howMany the number of peers to select
     * @param toExclude the hashes to skip
     * @param matches the set to add the selections to
     * @param mask the network bits to match
     * @param ipSet the masked ips to match
     * @param buildSuccess the cached build success ratio
     * @param rttCeiling the largest acceptable round trip time
     */
    private void lockedSelectPeers(Map<Hash, PeerProfile> peers, int howMany, Set<Hash> toExclude,
                                    Set<Hash> matches, int mask, MaskedIPSet ipSet, double buildSuccess,
                                    long rttCeiling) {
        lockedSelectPeers(peers, howMany, toExclude, matches, null, null, mask, ipSet, buildSuccess, rttCeiling);
    }

    /**
     * Collects up to {@link #maxCandidateSample} candidates from a tier and hands them to
     * {@link #lockedPickLowestPriority}.
     * <p>The scan start is a random offset into the tier.  Tier maps are plain
     * HashMaps, so iteration order is stable across calls; without the offset
     * every hop of every pool examined the same prefix, which both starved the
     * rest of the tier and piled "too many tunnels" onto the same few peers.
     * Peers in the tier were already vetted by isSelectable at tier-entry time,
     * so only fast-changing gates are re-checked here (banlist, ghosts,
     * first-hop cooldown, lifetime failures) rather than full proof-of-life,
     * which would reject too many peers on stale RouterInfo.
     *
     * @param peers tier to scan; must be a field map so the starved warning can name it
     * @param howMany total peers the caller wants held in matches across the whole
     *                cascade, not this tier alone
     * @param toExclude live exclusion set, mutated with peers rejected by a hard gate
     * @param matches output set, already holding any previously selected peers
     * @param randomKey sub-tier slice key, or null to skip slicing
     * @param subTierMode sub-tier mask, or null to skip slicing
     * @param mask IP /n diversity restriction, 0 to disable
     * @param ipSet mutable subnet set, null when mask is 0
     * @param buildSuccess build success ratio fetched once by the caller
     * @param rttCeiling soft ceiling; peers above it are skipped, never excluded
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

        // One clock read for the whole scan: the lifetime-failure gate judges the
        // age of a peer's evidence, and this loop runs per candidate.
        long now = _context.clock().now();
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
            if (isExcessiveLifetimeFailure(entry.getValue(), now)) {
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

        // Every reason the logger reports must be able to trigger it. Omitting one
        // here means the tier starved by exactly that cause stays silent — which is
        // how sub-tier slicing came to be reported by warnTierStarved but never
        // able to call it.
        if (tierScanStarved(candidates.size(), howMany, examined,
                            skipExcluded, skipGated, skipSliced, skipIp, skipRtt)) {
            warnTierStarved(peers, examined, skipExcluded, skipGated, skipSliced, skipIp, skipRtt);
        }

        // Select with random priority proportional to latency:
        // lower latency = smaller range for random score = higher chance of selection.
        // Moderately-lossy peers get their range widened so they are picked only
        // when the clean candidates run out — lossiness as one signal, not a gate.
        lockedPickLowestPriority(candidates, howMany, matches);
    }

    /**
     * Rate-limited warning naming the gate that emptied a tier scan.  A tier
     * reporting zero candidates is invisible upstream. The caller just falls
     * through to the next tier — so without this a 1000-peer tier rejected by a
     * single gate looks identical to genuine scarcity.
     *
     * @param peers the tier that produced no candidates
     * @param examined entries walked before giving up
     * @param skipExcluded rejected by the live exclusion set
     * @param skipGated rejected by a hard gate (ban, ghost, first-hop, lifetime failures)
     * @param skipSliced rejected by the sub-tier slice
     * @param skipIp rejected by IP diversity
     * @param skipRtt above the adaptive RTT ceiling
     * @since 0.9.71+
     */
    private void warnTierStarved(Map<Hash, PeerProfile> peers, int examined, int skipExcluded, int skipGated,
                                 int skipSliced, int skipIp, int skipRtt) {
        long now = System.currentTimeMillis();
        if (now - _lastStarveWarn < STARVE_WARN_INTERVAL_MS)
            return;
        _lastStarveWarn = now;
        if (_log.shouldWarn()) {
            String tier = peers == _fastPeers ? "Fast"
                          : peers == _highCapacityPeers ? "High capacity" : "Standard";
            _log.warn(tier + " tier selection returned no candidates -> Examined " + examined + " peers"
                      + "\n* Excluded: " + skipExcluded + " * Gated: " + skipGated +
                      " * Sub-tier: " + skipSliced + " * IP gate: " + skipIp + " * RTT gate: " + skipRtt);
        }
    }

    /**
     * Which profile a promotion pass should act on: the live one when the map has
     * it, the caller's otherwise.
     * <p>The TreeSet's copy may be stale, so the live profile decides. Making the
     * choice a named decision is what lets the promotion and its log reach the same
     * object — they used to decide separately, so the log printed the copy's
     * capacity while the promotion ran on the live one.
     *
     * @param live the profile from {@code _notFailingPeers}, may be null
     * @param fallback the profile the caller already holds
     * @return the profile to act on, never null
     * @since 0.9.71+
     */
    static PeerProfile profileToPromote(PeerProfile live, PeerProfile fallback) {
        return live != null ? live : fallback;
    }

    /**
     * Whether a tier scan starved badly enough to be worth reporting.
     * <p>All five skip reasons participate. The guard used to sum only four of the
     * five it was about to pass to the logger, so a tier emptied purely by sub-tier
     * slicing produced no warning. The one starvation cause with a dedicated counter
     * was the one that stayed silent. Extracting the sum is what makes "every reason
     * counts" checkable.
     *
     * @param candidates how many candidates the scan produced
     * @param howMany peers the caller asked for
     * @param examined entries walked before giving up
     * @param skipExcluded rejected by the live exclusion set
     * @param skipGated rejected by a hard gate
     * @param skipSliced rejected by the sub-tier slice
     * @param skipIp rejected by IP diversity
     * @param skipRtt above the adaptive RTT ceiling
     * @return true when the scan found nothing and something specific caused it
     * @since 0.9.71+
     */
    static boolean tierScanStarved(int candidates, int howMany, int examined,
                                   int skipExcluded, int skipGated, int skipSliced,
                                   int skipIp, int skipRtt) {
        if (candidates > 0 || howMany <= 0 || examined <= 0) {return false;}
        return skipExcluded + skipGated + skipSliced + skipIp + skipRtt > 0;
    }

    /**
     * Selects the lowest-priority candidates, penalizing moderately-lossy peers.
     * Must be called with the read lock held.
     * <p>Both lossy thresholds are resolved once for the whole list rather than
     * per candidate.  The lottery draw uses {@link ThreadLocalRandom} rather
     * than {@code _context.random()}: it only breaks ties among peers that have
     * already passed every gate, so it carries no cryptographic requirement,
     * while {@code _context.random().nextFloat()} is a monitored read on the
     * process-wide Fortuna instance and serialized every concurrent selection.
     *
     * @param candidates the entries to choose from, each a peer hash with its profile
     * @param howMany maximum number of peers to pick; values below one pick nothing
     * @param matches output set receiving the chosen peer hashes
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
     * Choose up to {@code howMany} candidates in ascending priority order.
     * <p>This loop is the reason the peer-selection callers may size a fixed-capacity
     * {@link net.i2p.util.ArraySet} to exactly the number of peers they asked for:
     * <ul>
     * <li>{@code net.i2p.router.tunnel.pool.ClientPeerSelector} builds four fallback sets as
     * {@code new ArraySet<>(needed)} and passes {@code needed} as {@code howMany}.
     * <li>{@code net.i2p.router.tunnel.pool.ExploratoryPeerSelector} builds
     * {@code new ArraySet<>(1)} and asks for one peer.
     * </ul>
     * An {@link net.i2p.util.ArraySet} throws {@code SetFullException} once written past its
     * capacity, so if this loop ever wrote more than {@code howMany} entries those callers
     * would throw at runtime rather than degrade. The bound {@code s < howMany && s < sz} is
     * therefore load-bearing, and it is pinned by
     * {@code PeerSelectionBoundTest.picksNeverExceedHowMany} and its neighbours.
     * <p>Distinctness falls out of the swap rather than from a membership test: the chosen
     * entry is moved to index {@code s} and the next inner scan starts at {@code s + 1}, so an
     * entry already picked can never be revisited. {@code candidates} and {@code priority} are
     * reordered in place to achieve that, which is why both are taken as mutable.
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
     * Scan up to {@link #maxCandidateSample} established peers for the active
     * tier, in {@link RandomIterator} order so the peers examined differ from
     * call to call.  Capped for the same reason as {@link #lockedSelectPeers}:
     * one attempt's worth of lottery, not a walk of the whole list.
     *
     * @param connected the connected peers to draw from, in random-iterator order
     * @param howMany peers to add; the scan stops once matches reaches this count
     * @param toExclude peers to skip, or null; a peer failing the selectability gate is
     *                  added here so later passes skip it too
     * @param matches output set receiving the chosen hashes; its size counts against
     *                howMany
     * @param mask leading address bytes to match for /n subnet diversity, 0 to disable
     * @param ipSet fingerprints claimed so far, consulted only when mask is non-zero
     * @param buildSuccess the build success ratio in [0.0, 1.0], fetched once per scan
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
     * Subnet-diversity gate for the running router.
     *
     * @param peer the peer whose addresses, ports and family are fingerprinted
     * @param ipSet the fingerprints claimed by the peers evaluated so far
     * @param mask leading address bytes to match, as taken by
     *             {@link MaskedIPSet#maskedIPKey(byte[], int)}
     * @return true if the peer passed the gate and may be selected
     */
    private boolean notRestricted(Hash peer, MaskedIPSet ipSet, int mask) {
        return notRestricted(_context, peer, ipSet, mask);
    }

    /**
     * Subnet-diversity gate: does this peer pass the masked-subnet, port and
     * family check, and if it does, claim its fingerprints so that no later peer
     * sharing any one of them passes as well.  Every selection path taking a
     * {@code mask} and an {@code ipSet} calls this, and all of them read true as
     * "allowed".
     * <p>
     * Before the peer-selection refactor this built a throwaway
     * {@link MaskedIPSet}, tested {@code containsAny} against the accumulator,
     * then added the keys. Inlining the build folded the collision test into the
     * adds, which inverted the result and let a peer collide with itself; both
     * are corrected here while keeping the single pass over the RouterInfo.
     *
     * @param ctx supplies the comm system IP and the NetDb lookup
     * @param peer the peer whose addresses, ports and family are fingerprinted
     * @param ipSet the fingerprints claimed by the peers evaluated so far
     * @param mask leading address bytes to match, as taken by
     *             {@link MaskedIPSet#maskedIPKey(byte[], int)}
     * @return true if the peer passed the gate and may be selected
     * @since 0.9.71+ split out of the instance method so tests can drive it
     */
    static boolean notRestricted(RouterContext ctx, Hash peer, MaskedIPSet ipSet, int mask) {
        return tryClaimKeys(ipSet, fingerprints(ctx, peer, mask));
    }

    /**
     * One peer's fingerprints: the masked IP the comm system recorded, the
     * masked IP and port of every published address, and the family option.
     * <p>
     * Collected in their own set rather than added straight to the shared
     * accumulator, because a peer normally publishes two transports on one
     * address and its own keys overlap by design — a peer must not collide with
     * itself.
     *
     * @param ctx supplies the comm system IP and the NetDb lookup
     * @param peer the peer to fingerprint
     * @param mask leading address bytes to match, 1-4
     * @return the peer's fingerprint keys, empty if nothing is known about it
     * @since 0.9.71+
     */
    static Set<String> fingerprints(RouterContext ctx, Hash peer, int mask) {
        Set<String> keys = new HashSet<>(6);
        CommSystemFacade comm = ctx.commSystem();
        byte[] commIP = comm != null ? comm.getIP(peer) : null;
        if (commIP != null)
            keys.add(MaskedIPSet.maskedIPKey(commIP, mask));
        RouterInfo info = lookupRouterInfoUnvalidated(ctx, peer);
        if (info != null) {
            for (RouterAddress pa : info.getAddresses()) {
                byte[] ip = pa.getIP();
                if (ip == null) continue;
                keys.add(MaskedIPSet.maskedIPKey(ip, mask));
                // Routers with a common port may be run by a single entity
                // with a common configuration
                int port = pa.getPort();
                if (port > 0)
                    keys.add("p" + port);
            }
            String family = info.getOption("family");
            // Prefixed so an IP cannot be spoofed into a family match
            if (family != null)
                keys.add('x' + family);
        }
        return keys;
    }

    /**
     * The diversity gate itself: report whether any of this peer's fingerprints
     * is already claimed, and claim them all either way.  A peer passes only when
     * none of its keys was claimed, so one peer per masked subnet, per port and
     * per family gets through.
     * <p>
     * Claiming a rejected peer's keys is load-bearing: it keeps the outcome the
     * same whichever order the candidates are evaluated in, so a peer cannot
     * pass the gate merely because selection happened to look at it first.
     *
     * @param claimed the fingerprints claimed by the peers evaluated so far
     * @param candidateKeys one peer's fingerprints, already deduplicated
     * @return true if none of the candidate keys was claimed yet, i.e. the peer
     *         passed; an empty candidate list passes vacuously
     * @since 0.9.71+
     */
    static boolean tryClaimKeys(Set<String> claimed, Set<String> candidateKeys) {
        boolean collision = false;
        for (String key : candidateKeys) {
            if (claimed.contains(key))
                collision = true;
        }
        claimed.addAll(candidateKeys);
        return !collision;
    }

    /**
     * Sub-tier slice index for a peer, keyed off the session key so the same
     * key always yields the same slice.
     *
     * @param peer the peer to place
     * @param k0 first half of the session key the slice is keyed off
     * @param k1 second half of that session key
     * @return the slice index, 0-3
     */
    private int getSubTier(Hash peer, long k0, long k1) {
        return ((int) SipHashInline.hash24(k0, k1, peer.getData())) & 0x03;
    }

    /**
     * Whether the given peer is eligible for selection.
     * Fetches the build success ratio once; per-peer callers should use
     * {@link #isSelectable(Hash, double)} with a value fetched once per scan.
     *
     * @param peer hash of the peer to judge
     * @return true if the peer passes every selection gate
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
     * @param peer hash of the peer to judge
     * @param buildSuccess the build success ratio, fetched once per scan
     * @return true if the peer passes every selection gate
     */
    private boolean isSelectable(Hash peer, double buildSuccess) {
        NetworkDatabaseFacade netDb = _context.netDb();
        if (netDb == null) return true;
        if (_context.router() == null) return true;
        if (!passesBasicGates(peer)) return false;
        // Fetch the profile once. It is needed both for the lifetime-failure gate
        // and (for stale RouterInfo) the proof-of-life check below; fetching it
        // per-check took two read locks per candidate.
        PeerProfile prof = _context.profileOrganizer().getProfile(peer);
        if (isExcessiveLifetimeFailure(prof, _context.clock().now())) return false;
        // Unvalidated: every check a validating lookup would add on top of presence is
        // done below — banlist and XG/LU in passesBasicGates, and hidden flag,
        // age with proof-of-life fallback, transport establishment, usable
        // address and tier in hasValidRouterInfo.
        RouterInfo info = lookupRouterInfoUnvalidated(peer);
        if (info != null) return hasValidRouterInfo(peer, info, buildSuccess, prof);
        return false;
    }

    /**
     * Returns false when the peer is us, banlisted, or recently failed as
     * first hop.
     * <p>The self check is first because this is the chokepoint every
     * selection path funnels through, including
     * {@link #selectRemainderFromAllPeers} via {@link #isSelectable(Hash, double)}.
     * The local RouterInfo is present in our own netdb, so we are a legitimate
     * entry in every candidate pool.
     * <p>Note that we are <em>supposed</em> to be in the tunnel we build, at
     * one end: {@code ClientPeerSelector.finalizeSelection} inserts us
     * directly, and {@code ExploratoryPeerSelector} adds us to its exclude
     * set. Neither goes through this method, so excluding us here costs
     * nothing. What this guard prevents is being drawn as an <em>extra</em>
     * hop — a mid-hop in a tunnel we are already an end of, which can only
     * produce a build that loops back on itself. The tier drawers
     * ({@code lockedSelectPeers}, {@code lockedSelectActive}) each excluded
     * self locally; the remainder path used when tiers cannot supply enough
     * peers did not, so the two protections could disagree. Checking here
     * rather than in each selection loop means no present or future path can
     * reintroduce the gap.
     * <p>Scope, stated precisely because it was previously overstated: this
     * guard has not been shown to fix any observed build failure. A router
     * appearing in an expiring-build log line is expected — it is the
     * gateway — so that log is not evidence of self-selection. Whether we
     * were being drawn at a mid-hop has not been established.
     *
     * @param peer the candidate's hash, tested against self, the banlist, the ghost-peer
     *             set and the first-hop failure list
     * @return true if the peer is not us, not banlisted, not a ghost, and not recently
     *         failing as a first hop
     * @since 0.9.71+
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
     * Banned-candidate rejections seen since the last
     * {@link #flushSelectionCounters}.
     * @since 0.9.71+
     */
    private final AtomicInteger _bannedAtSelection = new AtomicInteger();

    /**
     * Record the banned-candidate rejections accumulated since the last flush
     * as a single {@code tunnel.peerBannedAtSelection} sample.  The count stays
     * exact: the atomic get-and-reset loses nothing, it only attributes a
     * rejection to whichever scan drains it first.  A count of zero writes
     * nothing.
     *
     * @since 0.9.71+
     */
    private void flushSelectionCounters() {
        int banned = _bannedAtSelection.getAndSet(0);
        if (banned > 0) {
            _context.statManager().addRateData("tunnel.peerBannedAtSelection", banned);
        }
    }

    /**
     * Whether the given profile carries excessive cumulative tunnel failures.
     * Dual gate: hard exclusion at {@link #MAX_LIFETIME_TUNNEL_FAILURES} failures
     * or when the lifetime failure ratio exceeds {@link #MAX_LIFETIME_FAILURE_RATIO}.
     * A null profile or null history is not a failure.
     * <p>
     * The blame system (tunnelFailed()) only increments statistics — it never
     * bans. Without this check, peers with 200+ failures keep getting selected
     * because the ghost peer system clears on any success and the first-hop
     * cooldown is only 5 minutes. Pure decision — no context access, safe for
     * unit tests.
     *
     * @param prof the profile, may be null
     * @param now current time in ms, the reference for the evidence TTL
     * @return true when the peer should be excluded from selection
     * @since 0.9.72+ (the {@code now} parameter)
     */
    static boolean isExcessiveLifetimeFailure(PeerProfile prof, long now) {
        if (prof == null) return false;
        TunnelHistory th = prof.getTunnelHistory();
        if (th == null) return false;
        long lifetimeFailed = th.getLifetimeFailed();
        if (lifetimeFailed > MAX_LIFETIME_TUNNEL_FAILURES) return true;
        long totalRequests = th.getLifetimeAgreedTo() + lifetimeFailed;
        if (totalRequests <= 0) return false;
        // The ratio arm is the one that cannot heal: the 8-hour decay scales the
        // numerator and the denominator of this ratio by the same factor, so it
        // is invariant under its own decay and only new agreements move it.
        // A peer we have not asked in a week has a record that describes last
        // month, and it cannot improve on its own. Un-judged, not excluded.
        if (acceptanceEvidenceExpired(th.getLastBuildActivity(), now)) return false;
        // Beta-style shrinkage: judge a peer by its own record once it has one,
        // and by how the network as a whole behaves when it does not. On a router
        // whose build success is 52%, "2 of the 3 requests I sent this peer
        // failed" is not a finding about the peer.
        double effective = (lifetimeFailed + FAILURE_PRIOR_STRENGTH * POPULATION_FAILURE_RATIO)
                           / (totalRequests + FAILURE_PRIOR_STRENGTH);
        return effective > MAX_LIFETIME_FAILURE_RATIO;
    }

    /**
     * Selection penalty multiplier for peers between the soft and hard failure
     * thresholds.  Peers with {@link #SOFT_FAILURE_PENALTY_THRESHOLD} to
     * {@link #MAX_LIFETIME_TUNNEL_FAILURES} lifetime failures are not excluded
     * but receive a penalty that lowers their selection priority.  Returns
     * 1.0 (no penalty) for peers below the soft threshold or above the hard
     * threshold (they'd be excluded anyway).  Pure decision — no side effects.
     *
     * @param peer the peer hash
     * @param prof the peer's profile, or null if it has none
     * @return a penalty multiplier in (1.0, 2.0] — higher means more penalized
     * @since 0.9.71+
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
     * Checks the RouterInfo for hidden flag, stale proof of life, usable
     * transport address, bandwidth tier, and tunnel exclusion.
     *
     * @param peer the peer's hash, used for the established-connection check
     * @param info the peer's RouterInfo, already looked up and not null
     * @param buildSuccess the build success ratio in [0.0, 1.0], which scales the
     *                     tunnel-exclusion check
     * @param prof the peer's profile, or null if it was never seen; consulted only for
     *             proof of life when the RouterInfo is stale
     * @return true if the RouterInfo passes every age, liveness and usability check
     */
    private boolean hasValidRouterInfo(Hash peer, RouterInfo info, double buildSuccess, PeerProfile prof) {
        if (info.isHidden()) return false;
        long now = _context.clock().now();
        long maxAge = getMaxRouterInfoAgeMs();
        // RouterInfo is stale — demand proof of life to trust it.
        // This check applies regardless of startup grace period: peers
        // with stale RouterInfo and no handled requests are not trusted
        // as tunnel targets, even during startup. The startup grace only
        // relaxes the transport-established requirement below.
        if (info.getPublished() < now - maxAge &&
            !hasRecentProofOfLife(prof, now)) {
            return false;
        }
        // During normal operation, require an established transport connection
        // or recent proof of life.
        //
        // The established-session arm alone was a one-way door for exactly the
        // peers the tiers are short of: an idle peer holds no session, so it
        // fails selection, so it earns no traffic, so nothing refreshes the
        // evidence that would let it be selected. Proof of life — a send success,
        // a message heard, or a reference heard about, inside an hour — is the
        // same liveness claim the RouterInfo-age check above already accepts, so
        // accepting it here is consistent with the rule this file already uses.
        // A peer with no contact of any kind in an hour is still refused.
        if (_context.router() != null && _context.router().getUptime() > STARTUP_GRACE_PERIOD_MS &&
            !_context.commSystem().isEstablished(peer) &&
            !hasRecentProofOfLife(prof, now)) {
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
     * Whether a RouterInfo's advertised bandwidth tier is one that cannot host
     * a tunnel: L, M, or N.
     * <p>
     * The raw string is compared directly rather than through
     * {@code DataHelper.stripHTML}, which is not a weakening: stripHTML only
     * substitutes spaces for {@code < > " '}, so {@code stripHTML(t).equals("L")}
     * holds exactly when {@code t} is the one-character string {@code "L"}.
     * <p>
     * Pure decision. No context access, safe for unit tests.
     *
     * @param rawTier the tier string as advertised, possibly HTML-obfuscated
     * @return whether the tier excludes the peer from tunnel hosting
     * @since 0.9.71+
     */
    static boolean isExcludedBuildTier(String rawTier) {
        if (rawTier == null) return false;
        return "L".equals(rawTier) || "M".equals(rawTier) || "N".equals(rawTier);
    }

    /**
     * Maximum RouterInfo age in ms, cached alongside
     * {@link #getTunnelBuildSuccess} on the same
     * {@link #BUILD_SUCCESS_CACHE_MS} cadence: it is a constant read per
     * candidate, and 15s of staleness on a proof-of-life bar is immaterial.
     *
     * @return the max RouterInfo age in ms
     * @since 0.9.71+
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

    /** Cached {@link #getMaxRouterInfoAgeMs}, and the timestamp of the fetch. @since 0.9.71+. */
    private volatile long _cachedMaxRouterInfoAge = DEFAULT_MAX_ROUTERINFO_AGE_HOURS * 3600_000L;
    /** When {@link #_cachedMaxRouterInfoAge} was fetched. @since 0.9.71+. */
    private volatile long _cachedMaxRouterInfoAgeTime;

    /**
     * Returns true when the RouterInfo has a reachable NTCP/NTCP2/SSU/SSU2 address.
     *
     * @param info the peer's RouterInfo whose addresses are scanned
     * @return true if at least one NTCP, NTCP2, SSU or SSU2 address carries a non-null
     *         IP and a valid port
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
     * True if the peer has shown proof of life within
     * {@link #PROOF_OF_LIFE_WINDOW_MS}: a successful send, heard-from, or
     * heard-about within the last hour.  A null profile (never seen) is not
     * evidence of life.  Used to trust a RouterInfo that is older than the
     * maximum age — the RouterInfo alone is not enough, the peer must have
     * been active recently.
     * <p>
     * Pure decision — no context access, safe for unit tests.
     *
     * @param profile the peer profile, null if none
     * @param now current time in ms
     * @return whether the peer has recent proof of life
     * @since 0.9.71+
     */
    static boolean hasRecentProofOfLife(PeerProfile profile, long now) {
        if (profile == null) return false;
        return profile.getLastSendSuccessful() > now - PROOF_OF_LIFE_WINDOW_MS ||
               profile.getLastHeardFrom() > now - PROOF_OF_LIFE_WINDOW_MS ||
               profile.getLastHeardAbout() > now - PROOF_OF_LIFE_WINDOW_MS;
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
     * <p>The three tier thresholds are passed in rather than read here. Each one
     * costs a walk of the whole network database, and this runs once per profile
     * in a scan, so reading them per profile made a single pass cost
     * profiles-times-a-database-scan. They depend only on network size and the
     * active peer count, neither of which changes during a pass, so the caller
     * fetches them once for the whole scan.
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
    /**
     * Evaluate a profile for promotion to the tiers, reporting whether the only
     * thing that stopped it was an unusable transport address.
     * <p>The return value exists for {@link #promoteToFillTiers}, whose
     * underfill log line wants to distinguish a candidate held back by policy
     * from a candidate that was never examined. That line used to carry a local
     * counter which was declared and then never incremented, so it always
     * reported zero holds and read as "the gate never fires" for an entire
     * diagnostic run.
     *
     * @param profile the profile to evaluate
     * @param buildSuccess the windowed build success ratio
     * @param minHighCap minimum high-capacity peers
     * @param maxFast maximum fast peers
     * @param maxHighCap maximum high-capacity peers
     * @return true only if promotion was held because the RouterInfo has no
     * usable transport address; false for every other outcome
     * @since 0.9.71+
     */
    private boolean lockedPromoteProfileToTiers(PeerProfile profile, double buildSuccess,
                                               int minHighCap, int maxFast, int maxHighCap) {
        Hash peer = profile.getPeer();
        PeerProfile notFailingProfile = _notFailingPeers.get(peer);
        // One read for the whole evaluation: the skip gate, the staleness test and
        // the debug line all judge the same instant.
        long now = _context.clock().now();

        // Ghost peers are excluded from promotion: a peer that consistently fails
        // to respond to tunnel builds must not re-enter fast/high-cap tiers even
        // if its profile stats are still above threshold.  The ghost check is
        // cheap (ConcurrentHashMap lookup) and runs only during reorganize.
        TunnelManagerFacade tmf = _context.tunnelManager();
        if (tmf != null) {
            GhostPeerManager ghostMgr = tmf.getGhostPeerManager();
            if (ghostMgr != null && ghostMgr.isGhost(peer)) {
                if (_log.shouldDebug()) {
                    _log.debug("Skipping ghost peer from promotion [" +
                               peer.toBase64().substring(0, 6) + "]");
                }
                return false;
            }
        }

        boolean recentFailures = hasRecentTunnelFailures(profile);

        String skipReason = promotionSkipReason(profile, peer, buildSuccess, now);
        if (skipReason != null) {
            recordPromotionSkip(skipReason);
            if (_log.shouldDebug()) {
                _log.debug("Skipping a peer from promotion -> " + skipReason
                           + "\n* Peer: [" + peer.toBase64().substring(0, 6) + ']'
                           + " * Stale profile: " + (profile != notFailingProfile));
            }
            return false;
        }
        // Passing the gate means loss probation is over (either never started
        // or readmission conditions were met) — clear the flag so the cleared
        // state is persisted.
        clearLossIfReadmitted(profile);

        // Only the peers that clear every gate above reach this line, so the
        // staleness test costs one netDb map lookup for a candidate that was
        // actually going to be promoted.
        boolean staleRouterInfo = promotionNeedsRouterInfoRefresh(profile, now);

        // A peer whose RouterInfo has no usable address left cannot be selected for a
        // build or pre-connected to, so promoting it only manufactures a candidate that is
        // guaranteed to fail. That failure then demotes the peer and promoteToFillTiers()
        // promotes the next equally-stale one, which is the treadmill this gate stops.
        if (hasNoUsableTransport(peer)) {
            _context.statManager().addRateData("tunnel.promotionHeldNoAddress", 1);
            _refresher.requestAddressRefresh(peer);
            if (_log.shouldDebug())
                _log.debug("Holding promotion for [" + peer.toBase64().substring(0, 6) +
                           "]: RouterInfo has no usable transport address");
            return true;
        }

        double effectiveCapThreshold = Math.max(_thresholdCapacityValue, CapacityCalculator.GROWTH_FACTOR);
        double effectiveSpeedThreshold = _thresholdSpeedValue;

        // High-capacity tier
        // High-cap means proven ability to host tunnels with X/P/O bandwidth.
        // Admission requires:
        // - the shared gates passed (loss, congestion, lifetime failures)
        // - acceptance only once the peer has MIN_TUNNEL_REQUESTS samples. This is
        //   the reservoir the fast tier seeds from, and judging a peer on fewer
        //   requests than that is how good peers stayed out of it.
        // - isHighBandwidthCapable: X/P/O tier, no D/E/G caps
        // - Capacity above threshold, or room in the tier
        if (isLowTunnelAcceptance(profile, buildSuccess, now, MIN_TUNNEL_REQUESTS)) {
            recordPromotionSkip("lowTunnelAcceptance.highCap");
            return false;
        }
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
        // The fast tier keeps the strict acceptance floor high capacity dropped: it is
        // the tier build selection actually draws from, so a peer that has declined
        // 80% of a handful of requests has no business being offered. "A handful" is
        // five requests, not one: below five there is no evidence, and a peer with a
        // single refusal in its lifetime has not been measured, only sampled.
        if (isLowTunnelAcceptance(profile, buildSuccess, now, FAST_ACCEPTANCE_SAMPLES)) {
            recordPromotionSkip("lowTunnelAcceptance.fast");
            return false;
        }
        if (!_fastPeers.containsKey(peer) && _fastPeers.size() < maxFast &&
            isFastTierCapable(peer)) {
            // Proven throughput is a dated credential, not a permanent one. The
            // peak ring is never decayed, so as an undated credential it pushed
            // _fastQualityCount past MIN_FAST_QUALITY_COUNT over days, the tier
            // flipped into quality mode, and quality mode demands 10-minute-fresh
            // activity of every candidate — which is a set the fill passes cannot
            // grow. The peak stays the capacity signal used for sorting.
            boolean hasProvenThroughput = profile.hasProvenThroughput(now);
            boolean alreadyHighCap = _highCapacityPeers.containsKey(peer);
            boolean fastQuality = _fastQualityCount >= MIN_FAST_QUALITY_COUNT;
            // The three admission paths decide whether this peer is a fast-tier
            // candidate at all; the stale-RouterInfo hold is applied to the
            // decision rather than to the branch, so a peer these paths would
            // have dropped is not counted as a deferral.
            boolean fastCandidate;
            if (!recentFailures && alreadyHighCap &&
                (profile.isLowLatency() || !profile.hasLatencyEvidence() ||
                 !profile.hasBeenTested(now))) {
                // High-cap responsive: peer is in high-cap (X/P/O or
                // capacity-qualified) and is low-latency, unmeasured for latency,
                // or untested for responsiveness.
                // Tested-and-slow peers are excluded — their bandwidth tier is
                // real, but their responsiveness is, today. "Today" is the point:
                // both of these measurements were undated, so a peer measured
                // slow once, years ago, was excluded from this path permanently,
                // and the only thing that refreshes a measurement is being
                // selected by a path this one gates.
                fastCandidate = true;
            } else if (fastQuality) {
                // Quality mode: all tests passing — peer test, active,
                // no recent failures, AND proven throughput (or already high-cap)
                fastCandidate = profile.isLowLatency() && profile.getIsActive() &&
                                !recentFailures && (hasProvenThroughput || alreadyHighCap);
            } else {
                // Filling mode: speed-based or low-latency bypass, but still reject recent failures.
                // An unmeasured peer takes the low-latency bypass: it has not been
                // shown to be slow, and the RTT is only refreshed by a probe or a
                // build, both of which need the peer to be selected.
                fastCandidate = !recentFailures &&
                                ((profile.getSpeedValue() >= effectiveSpeedThreshold &&
                                  (profile.getIsActive() || hasProvenThroughput)) ||
                                 profile.isLowLatency() || !profile.hasLatencyEvidence());
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
        return false;
    }

    /**
     * Does this peer's RouterInfo carry no address we could still send to?
     * <p>Reads the netdb unvalidated, matching what selection and the build requestor do, so
     * the gate cannot reject a peer on stricter grounds than the path that would later use
     * it. A missing entry counts as "no usable address": we cannot prove otherwise, and
     * promoting an unverifiable peer is precisely the case this gate exists to stop.
     *
     * @param peer hash of the candidate under consideration
     * @return true if the peer should be held out of the selection tiers
     * @since 0.9.71+
     */
    private boolean hasNoUsableTransport(Hash peer) {
        if (peer == null || peer.equals(_context.routerHash())) { return false; }
        DatabaseEntry de = _context.netDb().lookupLocallyWithoutValidation(peer);
        if (de == null || de.getType() != DatabaseEntry.KEY_TYPE_ROUTERINFO) { return true; }
        return !TransportUtil.hasUsableTransportAddress((RouterInfo) de);
    }

    /**
     * Whether this peer's stored RouterInfo is stale enough that promoting on it
     * risks a build failure.
     * <p>Reads the same staleness notion {@link #hasValidRouterInfo} uses — older
     * than the threshold and no proof of life — against the shorter
     * {@link #PROP_ROUTERINFO_REFRESH_AGE_MS}. Proof of life exempts working
     * peers, which is what keeps the affected population small: a peer we have
     * talked to in the last hour is judged on what it just told us, not on how
     * old its last publication is.
     *
     * @param profile the profile being promoted
     * @param now current time in ms
     * @return whether the stored RouterInfo is too old to promote on
     * @since 0.9.72+
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
     * Whether a RouterInfo is old enough that promoting on it risks a build
     * failure: published before the threshold and no proof of life.
     * <p>An absent RouterInfo is not stale, it is missing, and is not this
     * predicate's business: {@link #isHighBandwidthCapable} and
     * {@link #isFastTierCapable} already refuse to promote a peer with no
     * RouterInfo at all, so there is no promotion here to hold.
     * <p>Pure decision — no context access, safe for unit tests.
     *
     * @param info the peer's stored RouterInfo, may be null
     * @param profile the peer profile supplying proof of life, may be null
     * @param now current time in ms
     * @param refreshAgeMs staleness threshold in ms
     * @return whether the RouterInfo is too stale to promote on
     * @since 0.9.72+
     */
    static boolean needsRouterInfoRefresh(RouterInfo info, PeerProfile profile, long now, long refreshAgeMs) {
        if (info == null) return false;
        if (refreshAgeMs <= 0) return false;
        if (info.getPublished() >= now - refreshAgeMs) return false;
        return !hasRecentProofOfLife(profile, now);
    }

    /**
     * Apply the hold to one tier insert: leave the peer out of the tier, or let
     * it in.
     * <p>The starvation guard is the reason this is not a plain veto.  A tier
     * that is already at its configured minimum is one the fallback passes
     * would defend anyway, so leaving a stale peer out of it costs nothing
     * there.  Below the minimum the hold would compete with those passes for
     * the same candidates and the tier could end up short, which is the worse
     * of the two failures: a stale promotion risks one bad build, an empty tier
     * sends every build back to the general pool.  When the guard opens, the
     * promotion is counted so the team can see exactly how often that happens.
     *
     * @param peer the peer being promoted
     * @param staleRouterInfo whether the peer carries a stale RouterInfo
     * @param tierSize current size of the tier being joined
     * @param safeFloor size at or above which this tier can leave it out
     * @return whether the promotion must be held back
     * @since 0.9.72+
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
            _log.debug("Promoting peer [" + peer.toBase64().substring(0, 6) +
                       "] on a stale RouterInfo: tier at " + tierSize + " below floor " + safeFloor);
        }
        _refresher.noteStalePromotion();
        return false;
    }

    /**
     * Whether a promotion may be held back on a stale RouterInfo without
     * risking the tier it would join.
     * <p>The floor is the tier's configured minimum, which is also what
     * {@link #fillFastTierFallbacks} and {@link #fillHighCapFallback} drive the
     * tier back up to and what {@link #restorePreservedPeers} protects at half.
     * At or above the minimum the tier can afford to leave the peer out; below
     * it, it cannot.
     * <p>Pure decision — no context access, safe for unit tests.
     *
     * @param tierSize current size of the tier the peer would join
     * @param safeFloor size at or above which the tier can leave it out
     * @return whether the promotion should be held back
     * @since 0.9.72+
     */
    static boolean shouldDeferPromotionOnStaleRouterInfo(int tierSize, int safeFloor) {
        return tierSize >= safeFloor;
    }

    /**
     * RouterInfo age past which a promotion is held back, in ms.  Read once per
     * reorganisation cycle rather than cached per candidate, because that is
     * the only place it is consulted.
     * <p>Separate from {@link #PROP_MAX_ROUTERINFO_AGE_HOURS}, which is the bar
     * a RouterInfo must clear to be selected at all. That bar is deliberately
     * loose (2h) because a proof of life can stand in for a fresh RouterInfo;
     * this one is a promotion-time policy with no such fallback, so it is set
     * where the RouterInfo itself is likely to have been superseded: routers
     * republish hourly, so anything older than one publishing interval has a
     * good chance of describing a peer that has since changed.
     * <p>Holding a promotion costs the peer a tier slot and nothing else — no
     * lookup is issued, so the only way a held peer re-enters is by being
     * republished to, or by showing proof of life.
     *
     * @return the staleness threshold in ms; &lt;= 0 disables the hold
     * @since 0.9.72+
     */
    private long getRouterInfoRefreshAgeMs() {
        return _context.getProperty(PROP_ROUTERINFO_REFRESH_AGE_MS, DEFAULT_ROUTERINFO_REFRESH_AGE_MS);
    }

    /**
     * Publish the promotion-deferral counters for the cycle just finished.
     * <p>{@code peer.routerInfoRefreshNeeded} is a population, so it is written
     * every cycle including zero. A flat zero line is the signal that nothing
     * is being held back. The stale-promotion count is an event count and
     * follows the existing convention of writing nothing when it is zero.
     *
     * @since 0.9.72+
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
     * Counts one promotion skip against its reason.
     * <p>The per-peer line stays at DEBUG because a single pass walks the whole
     * capacity-ordered profile set — about 4,300 evaluations on the live router,
     * several passes a minute. Logging each one at INFO would bury the log; the
     * question being answered is which gate is doing the excluding, and that is a
     * distribution rather than a list.
     * <p>Not synchronised: every caller holds the write lock, and the tally is
     * cleared by whichever scan is about to report it.
     *
     * @param reason the reason {@link #promotionSkipReason} returned
     * @since 0.9.71+
     */
    private void recordPromotionSkip(String reason) {
        Integer n = _promotionSkipTally.get(reason);
        _promotionSkipTally.put(reason, Integer.valueOf(n != null ? n.intValue() + 1 : 1));
    }

    /**
     * Display form of a promotion skip reason: prose words with the first
     * letter capitalised, so the tally reads as a list of reasons rather than
     * as code identifiers. The identifiers themselves stay greppable in the
     * per-peer DEBUG line and in {@link #promotionSkipReason}.
     *
     * @param reason the reason {@link #promotionSkipReason} returned
     * @return the tally display form; the reason unchanged when unmapped
     * @since 0.9.71+
     */
    static String promotionSkipLabel(String reason) {
        switch (reason) {
            case "notPeerSelectable":            return "Not peer selectable";
            case "excessiveFailures":            return "Excessive failures";
            case "strictCountry":                return "Strict country";
            case "congested":                    return "Congested";
            case "lossProbation":                return "Loss probation";
            case "lowTunnelAcceptance.fast":     return "Low tunnel acceptance (fast)";
            case "lowTunnelAcceptance.highCap":  return "Low tunnel acceptance (high cap)";
            default:                             return reason;
        }
    }

    /**
     * Formats the promotion skip tally and clears it.
     *
     * @return {@code \n* } continuation lines of {@code Label: count} pairs
     *         (packed to about 80 chars per line), or the empty string when
     *         nothing was skipped since the last call
     * @since 0.9.71+
     */
    private String drainPromotionSkipTally() {
        if (_promotionSkipTally.isEmpty()) {return "";}
        StringBuilder buf = new StringBuilder(128);
        int line = 0;
        for (Map.Entry<String, Integer> e : _promotionSkipTally.entrySet()) {
            String pair = promotionSkipLabel(e.getKey()) + ": " + e.getValue().intValue();
            if (buf.length() == 0 || line + pair.length() + 3 > 80) {
                buf.append("\n* ");
                line = 3;
            } else {
                buf.append(" * ");
                line += 3;
            }
            buf.append(pair);
            line += pair.length();
        }
        _promotionSkipTally.clear();
        return buf.toString();
    }

    /**
     * Whether the peer should be skipped for tier promotion: not selectable,
     * in a strict country, low tunnel acceptance, high-latency penalty,
     * congested, or in loss probation.  Mirrors the eligibility gates of
     * {@link #lockedPlaceProfile}.
     * <p>
     * No side effects. Safe to evaluate without holding locks.
     *
     * @param profile the profile under consideration
     * @param peer the peer hash
     * @param buildSuccess the tunnel build success ratio in [0.0, 1.0]
     * @return whether promotion should be skipped
     * @since 0.9.71+
     */
    boolean skipsPromotion(PeerProfile profile, Hash peer, double buildSuccess) {
        return promotionSkipReason(profile, peer, buildSuccess, _context.clock().now()) != null;
    }

    /**
     * Why {@link #skipsPromotion} would skip this profile, or null when it would not.
     * <p>The one place the decision is made. The caller used to re-derive two of the
     * five gates so it could log them, which meant a peer held back by congestion,
     * loss probation, a strict country or low tunnel acceptance printed
     * {@code notPeerSelectable=false excessiveFailures=false} — a line reading "the
     * gates passed" while the peer was in fact skipped. Returning the reason from the
     * decision itself is what stops the log from guessing, and stops the two from
     * drifting apart again.
     * <p>Short-circuits on the first gate that fires, in the order the original
     * disjunction evaluated them. The boolean result is unchanged, because the
     * original was a pure OR over the same five conditions.
     *
     * @param profile the profile under consideration
     * @param peer the peer hash
     * @param buildSuccess the tunnel build success ratio in [0.0, 1.0]
     * @param now current time in ms
     * @return a short reason, or null when promotion may proceed
     * @since 0.9.71+
     */
    String promotionSkipReason(PeerProfile profile, Hash peer, double buildSuccess, long now) {
        // Basic gates rather than isSelectable, to avoid stale RouterInfo
        // proof-of-life filtering peers that were already vetted at tier entry.
        if (!passesBasicGates(peer)) {return "notPeerSelectable";}
        if (isExcessiveLifetimeFailure(profile, now)) {return "excessiveFailures";}
        boolean isStrictCountry = _context.commSystem() != null &&
                                  _context.commSystem().isInStrictCountry(peer);
        if (isStrictCountry) {return "strictCountry";}
        // Acceptance is deliberately NOT checked here. This gate is shared by both
        // tiers, but they answer different questions: high capacity is the reservoir
        // the fast tier is seeded from and needs depth, the fast tier is the one that
        // should refuse thin evidence. A single shared check can only pick one, and
        // picking the strict one is what emptied the reservoir. Each branch below
        // applies its own floor instead.
        if (isCongestedPeer(peer)) {return "congested";}
        if (inLossProbation(profile, now)) {return "lossProbation";}
        return null;
    }

    /**
     * After a demotion vacates slots in fast/high-cap tiers, scan the
     * capacity-ordered peer list and promote the next best candidates
     * to fill the openings.  Must be called with write lock held.
     */
    private void promoteToFillTiers() {
        // One network-database walk and one active-peer count for the whole scan.
        // Both are O(network), and the loop below runs once per profile.
        //
        // Debounced first. Every demotion calls this, and demotions arrive at tens
        // per minute while the tier that lost a member is refilled in full by the
        // next reorganise anyway (roughly every 75s on the live router). Scanning
        // between reorganises therefore repeated a whole-network walk to fill a slot
        // that the next cycle would have filled regardless. The gap is a floor, not
        // a ceiling: a demotion arriving inside it still leaves the tier short, and
        // the reorganise is what closes that, exactly as it did before.
        long now = _context.clock().now();
        if (now - _lastTierFillScan < TIER_FILL_DEBOUNCE_MS) {
            return;
        }
        _lastTierFillScan = now;
        int known = _context.netDb().getKnownRouters();
        int active = _context.commSystem().countActivePeers();
        int fastTarget = getMaximumFastPeers(known, active);
        int highCapTarget = getMaximumHighCapPeers(known, active);
        int minHighCap = getMinimumHighCapacityPeers(known);
        int fastBefore = _fastPeers.size();
        int highCapBefore = _highCapacityPeers.size();

        // Publish the shortfall before the early return so a full tier
        // clears the flag too; the readmit fast-track reads it lock-free.
        _tiersShort = fastBefore < fastTarget || highCapBefore < highCapTarget;

        if (fastBefore >= fastTarget && highCapBefore >= highCapTarget)
            return;

        if (_log.shouldDebug()) {
            _log.debug("Promoting peers to fill tiers -> Fast: " + fastBefore + " of " + fastTarget +
                       " * High capacity: " + highCapBefore + " of " + highCapTarget);
        }

        // Fetch once for the whole scan; promotion checks per profile would
        // otherwise re-read router statistics for every candidate.
        double buildSuccess = getTunnelBuildSuccess();

        // Why the tiers came up short. Short because there were no candidates is a discovery
        // problem; short because every candidate was gated is a policy problem, and the two
        // need different fixes. From outside they are indistinguishable, so count both.
        int examined = 0;
        int alreadyTiered = 0;
        // Incremented from the return of lockedPromoteProfileToTiers() below. This counter was
        // previously declared and never incremented, so it always logged zero and the gate it
        // was meant to expose looked inert for a full diagnostic run. The rate stat
        // tunnel.promotionHeldNoAddress is the aggregate; this is the per-scan detail.
        int heldNoAddress = 0;

        for (PeerProfile profile : _strictCapacityOrder) {
            if (_fastPeers.size() >= fastTarget && _highCapacityPeers.size() >= highCapTarget)
                break;

            Hash peer = profile.getPeer();
            examined++;
            boolean wasFast = _fastPeers.containsKey(peer);
            boolean wasHighCap = _highCapacityPeers.containsKey(peer);
            if (wasFast && wasHighCap) {
                alreadyTiered++;
                continue;
            }

            // Use live profile from _notFailingPeers, which has current
            // capacityBonus, capacityValue, etc. — the TreeSet's copy may be stale
            PeerProfile liveProfile = _notFailingPeers.get(peer);
            // One choice, used by both the promotion and the log below. They used to
            // decide separately: the promotion ran on liveProfile while the log
            // printed the TreeSet copy's capacity, so whenever the two differed —
            // the case the sameObj field exists to report — the numbers described
            // the object that was not promoted.
            PeerProfile promoted = profileToPromote(liveProfile, profile);
            boolean held = lockedPromoteProfileToTiers(promoted, buildSuccess,
                                                       minHighCap, fastTarget, highCapTarget);
            if (held) { heldNoAddress++; }

            if (_log.shouldInfo()) {
                boolean nowFast = _fastPeers.containsKey(peer);
                boolean nowHighCap = _highCapacityPeers.containsKey(peer);
                String transition = promotionTransition(wasFast, wasHighCap, nowFast, nowHighCap);
                if (transition != null) {
                    String evidence = promotionEvidence(promoted.getCapacityBonus(),
                                                        promoted.getCapacityBonusRaw(),
                                                        promoted == liveProfile);
                    _log.info("Promoted peer [" + peer.toBase64().substring(0, 6) + "] " + transition +
                              (evidence != null ? evidence : ""));
                }
            }
        }

        // Drain every scan so the tally cannot accumulate across cycles; the
        // skips are evidence for the shortfall, so they log inside its line.
        String skips = drainPromotionSkipTally();
        if (_fastPeers.size() < fastTarget || _highCapacityPeers.size() < highCapTarget) {
            _context.statManager().addRateData("peermanager.tiersUnderfilled", 1);
            if (_log.shouldInfo())
                _log.info(describeTierFill("the promotion scan",
                                           _fastPeers.size(), fastTarget,
                                           _highCapacityPeers.size(), highCapTarget,
                                           examined, alreadyTiered, heldNoAddress,
                                           skips));
        }
    }

    /**
     * Describe a tier promotion as a {@code from -> to} transition, naming only
     * the tiers actually gained. Logging unchanged membership (a tier that was
     * and stayed true) reports no event and buries the one that happened, so a
     * scan that changed nothing returns null and the caller logs nothing.
     * This method only adds; a lost tier is never reported here.
     *
     * @param wasFast     in the fast tier before the promotion
     * @param wasHighCap  in the high-capacity tier before the promotion
     * @param nowFast     in the fast tier after the promotion
     * @param nowHighCap  in the high-capacity tier after the promotion
     * @return e.g. {@code "highCap -> fast"}, {@code "none -> highCap"};
     *         null when no tier was gained
     * @since 0.9.71+
     */
    static String promotionTransition(boolean wasFast, boolean wasHighCap,
                                      boolean nowFast, boolean nowHighCap) {
        boolean gainedFast = nowFast && !wasFast;
        boolean gainedCap = nowHighCap && !wasHighCap;
        if (!gainedFast && !gainedCap) {return null;}
        String from = wasFast ? "fast" : (wasHighCap ? "highCap" : "none");
        StringBuilder to = new StringBuilder(20);
        if (gainedFast) {to.append("fast");}
        if (gainedCap) {
            if (to.length() > 0) {to.append(" + ");}
            to.append("highCap");
        }
        return from + " -> " + to;
    }

    /**
     * Evidence line for a promotion log: only the values that explain
     * something. A zero bonus or raw adds nothing to read, and a live-profile
     * match is the normal case. Only the anomaly (the promoted object was
     * not the live profile) earns its place. Returns null when every value
     * is default, so the log line stays one line for a routine promotion.
     *
     * @param bonus         the capacity bonus applied
     * @param raw           the unclamped bonus the bonus was derived from
     * @param promotedIsLive  whether the promoted object was the live profile
     * @return a {@code "\n* "} continuation line, or null when all default
     * @since 0.9.71+
     */
    static String promotionEvidence(int bonus, int raw, boolean promotedIsLive) {
        StringBuilder sb = new StringBuilder(48);
        if (bonus != 0) {sb.append("\n* Capacity bonus: ").append(bonus);}
        if (raw != 0) {sb.append(sb.length() > 0 ? " * " : "\n* ").append("Raw: ").append(raw);}
        if (!promotedIsLive) {sb.append(sb.length() > 0 ? " * " : "\n* ").append("No live profile -> Promoted a possibly stale profile copy");}
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Summarise why the selection tiers could not be filled.
     * <p>Short because there were no candidates left to try is a discovery problem. Short
     * because every candidate was gated out is a policy problem. Those call for different
     * fixes, and from outside the reorg they look identical, so the breakdown is reported
     * rather than inferred. How to read it: held-no-address above zero is a policy gate;
     * examined zero means no candidates were found. That guide lives here, not in the
     * emitted line. Zero counters report the absence of an event and stay out; the skip
     * tally rides along as evidence for this same shortfall.
     *
     * @param where the caller, for attribution
     * @param fast the fast-tier size achieved
     * @param fastTarget the fast-tier size wanted
     * @param highCap the high-capacity-tier size achieved
     * @param highCapTarget the high-capacity-tier size wanted
     * @param examined candidates walked, including those rejected by a gate
     * @param alreadyTiered candidates skipped because already in both tiers, omitted when zero
     * @param heldNoAddress candidates that cleared every gate except having no usable address, omitted when zero
     * @param skips the drained skip tally with its own {@code "\n* "} prefix, or empty
     * @return the shortfall and its causes, wrapped onto continuation lines
     * @since 0.9.71+
     */
    static String describeTierFill(String where, int fast, int fastTarget,
                                   int highCap, int highCapTarget,
                                   int examined, int alreadyTiered, int heldNoAddress,
                                   String skips) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("Tiers underfilled in ").append(where)
          .append(" -> Fast: ").append(fast).append('/').append(fastTarget)
          .append(" * High cap: ").append(highCap).append('/').append(highCapTarget)
          .append("\n* Examined: ").append(examined);
        if (alreadyTiered > 0) {sb.append(" * Already tiered: ").append(alreadyTiered);}
        if (heldNoAddress > 0) {sb.append(" * Held no address: ").append(heldNoAddress);}
        if (skips != null && !skips.isEmpty()) {sb.append(skips);}
        return sb.toString();
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers if its RouterInfo is stale
     * and it has no recent proof of life (fails isSelectable). Non-blocking — only acts
     * if the peer is currently in those tiers.
     *
     * @param peer hash of the peer to demote
     */
    public void demoteIfStale(Hash peer) {
        if (!isSelectable(peer)) {
            if (!getWriteLock()) return;
            try {
                boolean inFast = _fastPeers.containsKey(peer);
                boolean inHighCap = _highCapacityPeers.containsKey(peer);
                if (inFast || inHighCap) {
                    if (_log.shouldInfo()) {
                        _log.info("Demoting peer [" + peer.toBase64().substring(0, 6) +
                                  "] from fast/high-cap tiers — stale RouterInfo, no proof of life");
                    }
                    if (inFast) removeFastPeer(peer);
                    if (inHighCap) _highCapacityPeers.remove(peer);
                    promoteToFillTiers();
                }
            } finally {
                releaseWriteLockAndRefresh();
            }
        }
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers. The caller must have
     * already judged the peer high-latency; this only performs the tier
     * removal. Non-blocking.
     *
     * @param peer hash of the peer to demote
     */
    public void demoteIfHighLatency(Hash peer) {
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase64().substring(0, 6) +
                              "] from fast/high-cap tiers due to high latency");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLockAndRefresh();
        }
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers. The caller must have
     * already judged the peer congested (cap D/E); this only performs the tier
     * removal.
     * Non-blocking - only acts if the peer is currently in those tiers.
     *
     * @param peer hash of the peer to demote
     */
    public void demoteIfCongested(Hash peer) {
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase64().substring(0, 6) +
                              "] from fast/high-cap tiers due to congestion cap (D/E)");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLockAndRefresh();
        }
    }

    /**
     * Immediately demote a peer from the fast tier when a peer test shows it is
     * no longer low-latency.  Called from {@link PeerTestJob} when a test result
     * arrives that contradicts the peer's tier membership.  Non-blocking — only
     * acts if the peer is currently in the fast tier.
     * <p>High-capacity membership is deliberately left alone. It is earned from
     * capacity and throughput rather than latency, so a latency judgement does
     * not speak to it; see the comment in the body.
     *
     * @param peer hash of the peer to demote
     * @since 0.9.71+
     */
    public void demoteIfNotLowLatency(Hash peer) {
          if (!getWriteLock()) return;
          try {
              if (demotesFastTier(_fastPeers.containsKey(peer), isLowLatency(peer))) {
                  if (_log.shouldInfo()) {
                      _log.info("Demoting peer [" + peer.toBase64().substring(0, 6) +
                                "] from the fast tier: no longer low-latency");
                  }
                  removeFastPeer(peer);
                  promoteToFillTiers();
              }
          } finally {
              releaseWriteLockAndRefresh();
          }
      }

    /**
     * Whether losing the low-latency flag revokes membership of the fast tier.
     * <p>Fast-tier membership is the latency promotion, so losing the flag
     * revokes it. High-capacity membership is <em>not</em> revoked by this
     * signal: it is earned from advertised capacity and sustained throughput, so
     * a peer that has stopped being low latency has not stopped being high
     * capacity. This method used to evict from both tiers, which with a
     * calibrated per-transport floor meant a transport whose baseline rose
     * during congestion could strip bandwidth-qualified peers out of the tier
     * they had been admitted to.
     *
     * @param inFast      whether the peer is currently in the fast tier
     * @param lowLatency  whether the peer still holds the low-latency flag
     * @return true if the peer should leave the fast tier
     * @since 0.9.71+
     */
    static boolean demotesFastTier(boolean inFast, boolean lowLatency) {
        return inFast && !lowLatency;
    }

    /**
     * Check whether the peer's profile has low-latency status.
     * Non-blocking — returns the profile's flag without acquiring locks.
     *
     * @param peer the peer whose profile flag is read
     * @return true if a profile was found and it carries the low-latency flag
     */
    private boolean isLowLatency(Hash peer) {
        PeerProfile profile = getProfileNonblocking(peer);
        return profile != null && profile.isLowLatency();
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers. The caller must have
     * already confirmed the ban; this only performs the tier removal.
     * Non-blocking - only acts if the peer is currently in those tiers.
     * @param peer hash of the peer to demote
     * @since 0.9.71+
     */
    public void demoteIfBanned(Hash peer) {
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase64().substring(0, 6) +
                              "] from fast/high-cap tiers: banned");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLockAndRefresh();
        }
    }

    /**
     * The single recorder for a measured direct-link RTT.
     * <p>Every path that holds a transport measurement. The reorganize sampling
     * pass, the pre-connect warm-up, a handshake — funnels through here, so the
     * field is written one way and re-derived one way. Callers that only need the
     * write (no stat, because they report a cycle aggregate instead) call
     * {@link #recordFirstHopRtt}; callers that hold a peer hash instead of a
     * profile call {@link #noteFirstHopRtt}.
     * <p>Owns the profile write so callers holding a transport measurement do
     * not have to know how the value is stored or aged out. The value decays
     * on the existing Active tier window, so a first-hop latency never
     * outlives the evidence class it belongs to.
     *
     * @param profile the profile to record against
     * @param rtt the measured round trip time in ms
     * @param when timestamp of the measurement
     * @since 0.9.71+
     */
    private void recordFirstHopRtt(PeerProfile profile, int rtt, long when) {
        if (rtt <= 0) {return;}
        profile.setFirstHopRtt(rtt, when);
        // The one low-latency decision lives in PeerProfile; this only propagates
        // its answer to the field the tiers read.
        //
        // The calibrated floor is looked up here rather than inside the profile so
        // that a profile never has to reach back into the Organizer: the
        // measurement is the profile's, the transport's baseline is not.
        Long calibrated = null;
        Hash peer = profile.getPeer();
        if (peer != null) {
            long threshold = getCalibratedLatencyThresholdMs(peer);
            if (threshold > 0) {calibrated = Long.valueOf(threshold);}
        }
        // The cohort floor rides alongside: it is already recomputed this cycle
        // and applies to every peer we would be judging, so supplying it costs
        // nothing and keeps both bounds decided at one point. It can only remove a
        // peer the absolute rule had already accepted.
        Long cohort = null;
        long cohortThreshold = getCohortLatencyThresholdMs();
        if (cohortThreshold > 0) {cohort = Long.valueOf(cohortThreshold);}
        profile.recalculateLowLatency(calibrated, cohort);
    }

    /**
     * Record a measured direct-link RTT against a peer, by hash.
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
        recordFirstHopRtt(profile, rtt, when);
        _context.statManager().addRateData("tunnel.firstHopRtt", rtt);
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers. The caller must have
     * already judged the peer to have built no tunnel recently. This only
     * performs the tier removal.
     * Non-blocking - only acts if the peer is currently in those tiers.
     *
     * @param peer hash of the peer to demote
     */
    public void demoteIfNoTunnel(Hash peer) {
        if (!getWriteLock()) return;
        try {
            boolean inFast = _fastPeers.containsKey(peer);
            boolean inHighCap = _highCapacityPeers.containsKey(peer);
            if (inFast || inHighCap) {
                if (_log.shouldInfo()) {
                    _log.info("Demoting peer [" + peer.toBase64().substring(0, 6) +
                              "] from fast/high-cap tiers due to no tunnel built recently");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLockAndRefresh();
        }
    }

    /**
     * Immediately demote a peer from fast/high-cap tiers when our transport
     * connection to it failed as a tunnel first hop (unreachable).
     * Uses a 3-strike threshold within the decay window to avoid premature
     * demotion. A transient failure (e.g. pre-connect race) should not remove
     * a peer from tiers, and strikes older than the window expire so a peer
     * that recovered is not demoted by one later failure.
     * Does not touch the tunnel failure counter. The caller records the
     * failure blame (e.g. profileManager().tunnelFailed()).
     *
     * @param peer hash of the peer that failed as a first hop
     */
    public void demoteIfUnreachable(Hash peer) {
        if (!getWriteLock()) return;
        long now = _context.clock().now();
        try {
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
                    _log.info("Demoting peer [" + peer.toBase64().substring(0, 6) +
                              "] from fast/high-cap tiers after " + DEMOTE_STRIKE_THRESHOLD +
                              " unreachable first-hop failures");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLockAndRefresh();
        }
    }

    /**
     * Release the reorganize write lock, then run the RouterInfo work it queued.
     * <p>The single exit point for every path that runs a tier refill under the lock. Doing
     * the refresh here rather than at each call site is deliberate: a call site that forgot
     * it would leave the queue to fill and never drain, and there were ten of them. It has to
     * be here, and not inside {@link #promoteToFillTiers}, because a refill runs with the
     * lock held and issuing a lookup is network work that must never run under it.
     * <p>Both drains are here for the same reason: the peers whose address is
     * missing, and the peers whose RouterInfo is about to go stale.
     *
     * @since 0.9.72+ (the proactive refresh drain)
     */
    private void releaseWriteLockAndRefresh() {
        releaseWriteLock();
        refreshHeldPeers();
        refreshStaleRouterInfos();
    }

    /**
     * Issue RouterInfo lookups for peers held out of the tiers for want of a usable address.
     * <p>Called after the reorganize write lock is released. Doubly bounded - a per-drain
     * batch and a minimum interval between drains - so neither a large scan nor a burst of
     * demotions can turn this into a request flood.
     *
     * @since 0.9.71+
     */
    private void refreshHeldPeers() {
        long now = _context.clock().now();
        if (!_refresher.mayDrainAddressRefreshes(now)) { return; }
        List<Hash> batch = _refresher.takeAddressRefreshBatch(now);
        if (batch.isEmpty()) { return; }
        for (Hash peer : batch) {
            try {
                _context.netDb().lookupRouterInfo(peer, null, null, ADDRESS_REFRESH_TIMEOUT_MS);
            } catch (RuntimeException re) {
                // a lookup that cannot be started is not worth aborting the batch over
                _log.log(Log.WARN, "Could not refresh held peer [" +
                          peer.toBase64().substring(0, 6) + "]", re);
            }
        }
        _context.statManager().addRateData("tunnel.promotionRefreshIssued", batch.size());
    }

    /** Timeout for a RouterInfo lookup issued on behalf of a held peer. */
    private static final long ADDRESS_REFRESH_TIMEOUT_MS = 30 * 1000L;

    /**
     * Timeout for a proactive RouterInfo refresh.
     * <p>Shorter than {@link #ADDRESS_REFRESH_TIMEOUT_MS}: this is a renewal, not
     * a recovery, and a search that has not answered in a quarter of the time has
     * missed the window in which it keeps the peer promotable.
     */
    private static final long REFRESH_TIMEOUT_MS = 15 * 1000L;

    /**
     * How far back "a peer we have used" reaches for the refresh schedule, in ms.
     * <p>A day, not the hour the activity gauge uses. A RouterInfo refresh is
     * worth issuing for a peer selection may offer tomorrow, and the per-peer
     * cooldown, not the width of the set, is what bounds the cost.
     */
    static final long REFRESH_ACTIVITY_WINDOW_MS = 24 * 60 * 60 * 1000L;

    /**
     * Property: how far ahead of the staleness bar a RouterInfo is refreshed, in
     * ms. Zero or less disables the proactive refresh.
     *
     * @return the refresh-ahead window in ms
     * @since 0.9.72+
     */
    private long getRouterInfoRefreshAheadMs() {
        return _context.getProperty(PROP_ROUTERINFO_REFRESH_AHEAD_MS,
                                    RouterInfoRefresher.DEFAULT_REFRESH_AHEAD_MS);
    }

    /**
     * Queue a proactive RouterInfo refresh for the peers the tiers are made of.
     * <p>Must be called with the write lock held; it does no network work. The
     * lookups go out from {@link #refreshStaleRouterInfos()} after the lock is
     * released.
     * <p>The population is the tier members plus every profile with a successful
     * send in the last day. Those are the peers a stale RouterInfo costs us: the
     * deferral applies to tier members, and a peer outside the tiers is refreshed
     * so that it can re-enter them. A peer with no RouterInfo at all is left to
     * the missing-RouterInfo recovery drain, which already owns that case.
     * <p>The reason to refresh ahead of the bar rather than react to it: routers
     * republish hourly, so a RouterInfo reaches the bar on a schedule, and the
     * only re-entry routes from a hold were that the peer happened to republish
     * to us, or that we heard from it inside an hour. A peer that is idle, in a
     * tier that is short, has neither, and the hold is a ceiling that engages as
     * the tier approaches the size it is sized for.
     *
     * @param now current time in ms
     * @since 0.9.72+
     */
    private void scheduleRouterInfoRefreshes(long now) {
        long ahead = getRouterInfoRefreshAheadMs();
        if (ahead <= 0) {return;}
        int queued = 0;
        for (Hash peer : _fastPeers.keySet()) {
            if (scheduleRefresh(peer, now, ahead)) {queued++;}
        }
        for (Hash peer : _highCapacityPeers.keySet()) {
            if (scheduleRefresh(peer, now, ahead)) {queued++;}
        }
        long activeCutoff = now - REFRESH_ACTIVITY_WINDOW_MS;
        for (PeerProfile profile : _notFailingPeers.values()) {
            if (profile.getLastSendSuccessful() < activeCutoff) {continue;}
            if (scheduleRefresh(profile.getPeer(), now, ahead)) {queued++;}
        }
        if (_log.shouldDebug() && queued > 0) {
            _log.debug("RouterInfo refresh -> Queued " + queued + " lookups" +
                       " * Pending refreshes: " + _refresher.getRefreshPending() +
                       " * Ahead: " + DataHelper.formatDuration(ahead));
        }
    }

    /**
     * Whether one peer's RouterInfo should be refreshed now, and queue it if so.
     *
     * @param peer the peer to consider
     * @param now current time in ms
     * @param aheadMs the refresh-ahead window in ms
     * @return true if the peer was queued
     */
    private boolean scheduleRefresh(Hash peer, long now, long aheadMs) {
        // Cooldown first: it is a map read, and in steady state it refuses almost
        // every candidate, so the staleness test's database lookup is not paid
        // for a peer that would be refused anyway.
        if (_refresher.isRefreshCooling(peer, now)) {return false;}
        DatabaseEntry de = _context.netDb().lookupLocallyWithoutValidation(peer);
        if (!(de instanceof RouterInfo)) {return false;}
        if (!RouterInfoRefresher.isRouterInfoDueForRefresh(((RouterInfo) de).getPublished(), now, aheadMs)) {
            return false;
        }
        return _refresher.scheduleRefresh(peer, now);
    }

    /**
     * Issue the queued RouterInfo refreshes.
     * <p>Called after the reorganize write lock is released, next to
     * {@link #refreshHeldPeers()}, and doubly bounded the same way - a per-drain
     * batch and a minimum interval - so a tier of any size cannot turn this into
     * a request flood.
     *
     * @since 0.9.72+
     */
    private void refreshStaleRouterInfos() {
        long now = _context.clock().now();
        if (!_refresher.mayDrainRefreshes(now)) {return;}
        List<Hash> batch = _refresher.takeRefreshBatch(now);
        if (batch.isEmpty()) {return;}
        for (Hash peer : batch) {
            try {
                _context.netDb().refreshRouterInfo(peer, REFRESH_TIMEOUT_MS);
            } catch (RuntimeException re) {
                // A search that cannot be started is not worth aborting the batch over.
                _log.log(Log.WARN, "Could not start RouterInfo refresh for peer [" +
                          peer.toBase64().substring(0, 6) + "]", re);
            }
        }
        _context.statManager().addRateData("peer.routerInfoProactiveRefresh", batch.size());
        // The refresher counts drops cumulatively; a rate stat wants this drain's.
        int dropped = _refresher.getRefreshDropped() - _refreshDroppedReported;
        if (dropped > 0) {
            _refreshDroppedReported += dropped;
            _context.statManager().addRateData("peer.routerInfoRefreshDropped", dropped);
            if (_log.shouldInfo()) {
                _log.info("RouterInfo refresh -> Queue full, refused " + dropped +
                          " refreshes * Pending refreshes: " + _refresher.getRefreshPending());
            }
        }
    }

    /** Refreshes dropped by the queue cap that have already been reported. */
    private int _refreshDroppedReported;

    /**
     * Record that a stored profile was loaded for a peer the netdb cannot resolve.
     *
     * @param peer the peer whose RouterInfo is missing
     * @return true if recorded, false if the recovery list was already full
     * @since 0.9.72+
     */
    boolean noteMissingRouterInfo(Hash peer) { return _refresher.noteMissingRouterInfo(peer); }

    /**
     * Whether enough time has passed to request the next batch of missing RouterInfos.
     *
     * @param now current time in milliseconds
     * @return true if the drain interval has elapsed
     * @since 0.9.72+
     */
    boolean mayDrainMissingRouterInfo(long now) { return _refresher.mayDrainMissingRouterInfo(now); }

    /**
     * Hand over the next batch of peers whose RouterInfo should be requested.
     *
     * @param now current time in milliseconds
     * @return the peers to look up, possibly empty
     * @since 0.9.72+
     */
    List<Hash> takeMissingRouterInfoBatch(long now) { return _refresher.takeMissingRouterInfoBatch(now); }

    /**
     * Peers still waiting for their RouterInfo to be requested.
     *
     * @return the number pending
     * @since 0.9.72+
     */
    int getMissingRouterInfoPending() { return _refresher.getMissingRouterInfoPending(); }

    /**
     * Peers not recorded because the recovery list was full.
     *
     * @return the number dropped
     * @since 0.9.72+
     */
    int getMissingRouterInfoDropped() { return _refresher.getMissingRouterInfoDropped(); }

    /**
     * Compute the next strike count after a failure, applying time-window
     * decay. A previous strike older than DEMOTE_STRIKE_DECAY_MS no longer
     * counts, so a peer that recovered is not demoted by one later failure.
     *
     * @param previousStrikes strike count before this failure (0 if none)
     * @param lastStrikeTime time of the previous strike (0 if none)
     * @param now the current clock reading in milliseconds, compared against
     *            DEMOTE_STRIKE_DECAY_MS
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
     * Demote a peer from fast/high-cap tiers when its direct-link latency is poor.
     * <p>{@code responseTimeMs} is the round trip through an entire multi-hop
     * tunnel, which {@code TestJob.noteSuccess} attributes to every peer in that
     * tunnel. That is a reasonable profile statistic but the wrong input for
     * first-hop selection: a peer with a fast direct link is blamed for the other
     * hops it happened to be a member of, and the result is compared against a
     * first-hop threshold. In production that evicted a large share of the fast
     * tier on a latency distribution taken across the wrong hops, shrinking the
     * candidate pool and leaving slower survivors.
     * <p>The tier decision therefore uses {@link PeerProfile#getFirstHopRtt},
     * measured on the direct link. The passed-in tunnel time is not consulted.
     * A peer with no recorded first-hop RTT is left alone: unknown is not slow.
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
     * Whether a measured first-hop RTT warrants removal from the fast tiers.
     * <p>Pure so the policy is testable without a populated profile. An unknown
     * measurement (negative) is never slow: we have not probed the peer, and
     * evicting on absent evidence would drain the tier of exactly the peers we
     * have not looked at yet.
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
     * Remove a peer from the fast/high-cap tiers if its measured direct-link RTT
     * reaches the low-latency threshold.
     * <p>The threshold is unchanged at {@code router.peerTestTimeout * 2}; only the
     * measurement changed, from a whole tunnel to one hop.
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
                    _log.info("Demoting peer [" + peer.toBase64().substring(0, 6) +
                              "] from the fast and high-capacity tiers"
                              + "\n* First hop RTT: " + firstHopRttMs + "ms * Above threshold: " +
                              (timeout * 2) + "ms");
                }
                if (inFast) removeFastPeer(peer);
                if (inHighCap) _highCapacityPeers.remove(peer);
                promoteToFillTiers();
            }
        } finally {
            releaseWriteLockAndRefresh();
        }
    }

    /**
     * Whether a peer's tunnel acceptance is bad enough to exclude it, given its
     * counts and the sample floor the tier in question applies.
     * <p>Pure decision. No context access, safe for unit tests. The tiers differ
     * in the floor they pass, and that difference is the whole point: high capacity
     * is the reservoir the fast tier seeds from and cannot afford to judge a peer on
     * a handful of requests, while the fast tier is what build selection draws from
     * and can. Both bars fall under stress, because a peer rejecting our requests
     * during network-wide congestion has not demonstrated anything about itself.
     *
     * @param agreed lifetime requests the peer accepted
     * @param rejected lifetime requests the peer declined
     * @param minRequests samples required before acceptance may exclude at all
     * @param highStress whether build success is below {@link #ATTACK_THRESHOLD}
     * @return true if the peer should be excluded on acceptance
     * @since 0.9.71+
     */
    static boolean excludedOnAcceptance(long agreed, long rejected, int minRequests,
                                       boolean highStress) {
        long total = agreed + rejected;
        if (total <= 0) {return false;}
        if (total < minRequests) {return false;}
        double ratio = (double) agreed / total;
        double bar = (total < MIN_TUNNEL_REQUESTS) ? 0.20 : MIN_TUNNEL_ACCEPTANCE_RATIO;
        if (highStress) {bar = 0.10;}
        return ratio < bar;
    }

    /**
     * Check if peer has low tunnel acceptance ratio, decided from its lifetime
     * accept/reject counts via {@link #excludedOnAcceptance}, then refined by
     * the low-sample or mature path.
     * Peers with no tunnel test history are judged by recent activity instead:
     * an alive peer is allowed into the tiers to accumulate data, a stale one
     * is excluded.
     * The mature path also honours a recent bandwidth-rejection cooldown.
     * <p>
     * Takes the build success ratio as a parameter — it is fetched once per
     * selection and passed down; {@link #getTunnelBuildSuccess} performs 6
     * stat lookups and must never run per candidate peer.
     *
     * @param profile the peer profile
     * @param buildSuccess the tunnel build success ratio in [0.0, 1.0]
     * @param now current time in ms
     * @param minRequestsForJudgement how many lifetime requests the peer needs before
     * its acceptance ratio is allowed to exclude it at all
     * @return whether low tunnel acceptance (true = exclude from fast/high-cap tiers)
     * @since 0.9.71+
     */
    private boolean isLowTunnelAcceptance(PeerProfile profile, double buildSuccess, long now,
                                         int minRequestsForJudgement) {
        TunnelHistory th = profile.getTunnelHistory();
        if (th == null) return false;

        long agreed = th.getLifetimeAgreedTo();
        long rejected = th.getLifetimeRejected();
        long totalRequests = agreed + rejected;

        if (totalRequests == 0) {
            // No tunnel test history — allow alive peers into tiers so they
            // can accumulate tunnel data.  Stale peers (not heard from recently)
            // are excluded; under-performers are evicted by existing mechanisms.
            // A live session satisfies the bar: a peer with no history is the
            // normal state of every fill candidate, and acquiring history
            // requires being selected, which requires passing this gate.
            return !hasRecentTierActivity(profile, now - NO_HISTORY_ACTIVITY_WINDOW_MS,
                                          isReachableNow(profile.getPeer()));
        }

        // A record that has not been refreshed in a week is not evidence: the
        // counts decay multiplicatively, so the ratio they form is invariant
        // under the decay and can only be raised by new agreements, which need
        // the tier membership this very gate is withholding. Un-judged, not passed.
        if (acceptanceEvidenceExpired(th.getLastBuildActivity(), now)) {return false;}

        double ratio = (double) agreed / totalRequests;

        // During high-stress (many peers rejecting), lower the threshold
        // to avoid excluding peers that are only rejecting due to network-
        // wide congestion rather than their own limitations.
        boolean highStress = buildSuccess < ATTACK_THRESHOLD;

        // Too few requests to judge is not a pass and not a fail, it is no evidence.
        // Excluding here is how a peer was locked out on seven samples: 1 agreed,
        // 6 rejected, a 14% ratio measured before it had a history at all.
        if (!excludedOnAcceptance(agreed, rejected, minRequestsForJudgement, highStress)) {
            return false;
        }
        if (totalRequests < MIN_TUNNEL_REQUESTS) {
            return isLowAcceptanceLowSample(profile, ratio, highStress);
        }
        return isLowAcceptanceMature(profile, th, ratio, agreed, rejected, highStress);
    }

    /**
     * Small-sample decision: below {@link #MIN_TUNNEL_REQUESTS} lifetime
     * requests, apply the loose startup thresholds.
     *
     * @param profile the peer profile
     * @param ratio the acceptance ratio in [0.0, 1.0]
     * @param highStress whether build success is below the attack threshold
     * @return whether low tunnel acceptance
     * @since 0.9.71+ (extracted from isLowTunnelAcceptance)
     */
    private boolean isLowAcceptanceLowSample(PeerProfile profile, double ratio, boolean highStress) {
        double threshold = highStress ? 0.10 : 0.20;
        if (ratio >= threshold) return false;
        if (_log.shouldDebug()) {
            _log.debug("Demoting peer from fast tier (startup): [" +
                       profile.getPeer().toBase64().substring(0, 6) +
                       "]\n* ratio: " + String.format("%.2f", ratio * 100) + "% below " +
                       String.format("%.0f", threshold * 100) + "% threshold");
        }
        return true;
    }

    /**
     * Mature decision: effective minimum ratio, with a cooldown for
     * recent bandwidth rejections.
     *
     * @param profile the peer profile
     * @param th the tunnel history (non-null)
     * @param ratio the acceptance ratio in [0.0, 1.0]
     * @param agreed lifetime accepted requests
     * @param rejected lifetime rejected requests
     * @param highStress whether build success is below the attack threshold
     * @return whether low tunnel acceptance
     * @since 0.9.71+ (extracted from isLowTunnelAcceptance)
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
                 _log.debug("Demoting peer from high-cap tier (bandwidth cooldown active): [" +
                            profile.getPeer().toBase64().substring(0, 6) +
                            "]\n* ratio: " + String.format("%.2f", ratio * 100) + "%" +
                            " (" + agreed + " accept / " + rejected + " reject / " +
                            timedOut + " timeout)" +
                            "\n* Cooldown: " + remaining + " min remaining");
            }
            return true;
        }

        if (_log.shouldDebug()) {
             _log.debug("Demoting peer from high-cap tier due to low tunnel acceptance: [" +
                        profile.getPeer().toBase64().substring(0, 6) +
                        "]\n* ratio: " + String.format("%.2f", ratio * 100) + "%" +
                        " (" + agreed + " accept / " + rejected + " reject / " +
                        timedOut + " timeout)");
        }
        return true;
    }

    /**
     * Check if peer is a quality tunnel candidate: good acceptance ratio,
     * selectable, and recently active. Used by tuner to adjust tier limits
     * based on viable peers, not just raw counts.
     *
     * @param profile the tier peer's profile to judge
     * @param recentCutoff the earliest successful send that still counts as recent
     *                     activity
     * @param buildSuccess the build success ratio in [0.0, 1.0], passed to the
     *                     selectability gate
     * @return true if the peer is selectable, has sent successfully since
     *         recentCutoff, and clears the acceptance-ratio floor once it has
     *         MIN_TUNNEL_REQUESTS of tunnel history
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
     * Requires more than 15 failures in the last hour AND a failure ratio
     * above 30% of agreed + rejected + failed — peers with
     * 1-15 transient failures during a network blip stay in tiers rather
     * than being purged.  This prevents tier collapse during brief
     * network-wide events where many peers experience isolated failures.
     *
     * @param profile the peer's profile; a peer with no tunnel history, or no failure
     *                rate for the last hour, has nothing to report
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
        // Many failures but a low ratio — a busy peer, not a failing one
        return false;
    }

    /**
     * True if the peer's decayed loss score is at or above
     * {@link #PROP_LOSSY_THRESHOLD}. The score decays continuously by 50%
     * every 15 minutes since the last transport report, so a transient
     * congestion episode clears the gate in tens of minutes while a peer that
     * keeps reporting loss stays gated. Each report re-inflates the score.
     * The decay, not a freshness window, provides the slow forgiveness.
     *
     * @param profile profile carrying the decayed loss score to test against the threshold
     * @param now current time in ms
     * @return true if the decayed score is above zero and at or above the lossy threshold
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
     * @param profile profile whose loss demotion flag and loss score are checked
     * @param now current time in ms
     * @return true if the loss score is at the demotion threshold, or the peer is
     *         flagged lossy and has not yet shown fresh clean recovery
     * @since 0.9.71+
     */
    private boolean inLossProbation(PeerProfile profile, long now) {
        if (hasHighLoss(profile, now)) return true;
        if (!profile.isLossy()) return false;
        // The clock bound is checked ahead of the readmission test, and this stays
        // a predicate: the flag itself is cleared by clearLossIfReadmitted() on a
        // path that holds the write lock, so a selection scan never mutates a
        // profile.
        if (lossProbationExpired(profile.getLossySince(), now)) return false;
        return !shouldReadmitLossy(profile, now);
    }

    /**
     * True if a demoted peer may be re-admitted to the tiers: at least
     * {@link #LOSS_READMIT_MIN_AGE} has passed since the demotion AND the
     * transport has reported a loss ratio below the demotion threshold within
     * {@link #PROP_LOSSY_WINDOW}. A stale or absent ratio is not evidence of
     * recovery. This is what breaks the avoid-then-forget loop, because a
     * peer that recovered organically accumulates new packets (netDb traffic
     * continues while it is demoted) and reports a fresh clean ratio, while a
     * peer that is still lossy never does.
     * <p>
     * Fast-track: peers with a fresh clean loss report (loss ratio below 5%
     * within the lossy freshness window, default 10 min) are readmitted
     * immediately regardless of the minimum age, accelerating tier recovery
     * after brief congestion episodes. While either tier is under target the
     * fast-track widens to any fresh below-threshold report.
     *
     * @param profile profile holding the demotion timestamp and the last reported loss ratio
     * @param now current time in ms
     * @return true if the peer may be re-admitted
     * @since 0.9.71+
     */
    private boolean shouldReadmitLossy(PeerProfile profile, long now) {
        return lossyReadmitAllowed(profile.getLossRatio(), profile.getLossRatioLastUpdate(),
                                   profile.getLossySince(), now,
                                   _context.getProperty(PROP_LOSSY_WINDOW, DEFAULT_LOSSY_WINDOW),
                                   LOSS_READMIT_MIN_AGE, getLossyThreshold(_context),
                                   LOSS_FAST_TRACK_RATIO, _tiersShort);
    }

    /**
     * Pure decision behind {@link #shouldReadmitLossy}, pinned by tests.
     * A peer never demoted is always allowed. A peer the transport has never
     * reported on is allowed too: there is no evidence of loss for it to answer,
     * and demanding a report from a peer we are sending no traffic to is a closed
     * loop. Otherwise the transport must have reported within the freshness
     * window a ratio below the demotion threshold; that fresh-clean evidence
     * admits immediately when the ratio is near-spotless
     * ({@code fastTrackRatio}) or when the tiers are short ({@code tiersShort}) —
     * a short tier cannot afford to hold a peer out for a clean-but-not-spotless
     * measurement — and otherwise only after the minimum age has passed. A stale
     * report is not evidence of recovery.
     *
     * @param lossRatio last loss ratio reported by the transport, 0.0 = healthy
     * @param ratioLastUpdate when that ratio was reported, in ms; 0 if never
     * @param lossySince when the peer was demoted for loss, 0 if never
     * @param now current time in ms
     * @param freshWindowMs how old the last report may be and still count as fresh
     * @param minAgeMs minimum time since demotion before a not-spotless report admits
     * @param threshold the lossy demotion threshold the ratio must fall below
     * @param fastTrackRatio ratio below which the minimum age is skipped
     * @param tiersShort whether either tier is under target, widening the fast track
     * @return true if the peer may be re-admitted
     * @since 0.9.71+
     */
    static boolean lossyReadmitAllowed(float lossRatio, long ratioLastUpdate, long lossySince, long now,
                                       long freshWindowMs, long minAgeMs, float threshold,
                                       float fastTrackRatio, boolean tiersShort) {
        if (lossySince <= 0) return true;
        // Never reported is not "no clean evidence", it is "no evidence at all".
        // The fields are not carried across a restart by the transport, so a peer
        // that comes back from a persisted probation with no report on file was
        // not observed to be lossy - it was only ever remembered as one.
        if (ratioLastUpdate <= 0) return true;
        boolean freshClean = now - ratioLastUpdate < freshWindowMs && lossRatio < threshold;
        if (freshClean && (lossRatio < fastTrackRatio || tiersShort)) return true;
        if (now - lossySince < minAgeMs) return false;
        return freshClean;
    }

    /**
     * Clear the loss demotion flag once a peer has been re-admitted, so the
     * cleared state is persisted and re-admission is not re-evaluated on every
     * reorganize. Must be called with the write lock held.
     *
     * @param profile profile whose lossy flag is cleared, so re-admission is not re-checked every cycle
     * @since 0.9.71+
     */
    private void clearLossIfReadmitted(PeerProfile profile) {
        if (profile.isLossy()) {
            profile.clearLossy();
            if (_log.shouldDebug()) {
                _log.debug("Peer [" + profile.getPeer().toBase64().substring(0, 6) +
                           "] re-admitted after loss probation: loss score " +
                           profile.getLossScore(_context.clock().now()));
            }
        }
    }

    /**
     * Instantly remove a peer from the fast/high-capacity tiers when its decayed loss
     * score exceeds the threshold, and mark it for loss probation. Called from the
     * transport path via {@link ProfileManagerImpl} so a lossy peer is demoted as soon as its
     * retransmit ratio crosses the bar, instead of waiting for the next reorganize
     * cycle. Non-blocking: if a reorganize currently holds the write lock, the
     * reorganize purge catches the peer instead.
     *
     * @param peer hash of the peer to demote and place into loss probation
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
                    _log.debug("Demoting peer [" + peer.toBase64().substring(0, 6) +
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
     * eligible for the tiers. They never crossed the hard bar — but are
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
     * @return the mean loss ratio as a fraction of 1.0, or 0.0 if no fresh data
     * @since 0.9.71+
     */
    public float getAverageLossRatio() {
        if (!tryReadLock()) return 0.0f;
        try {return averageLossRatio(_fastPeers, _highCapacityPeers);}
        finally {releaseReadLock();}
    }

    /**
     * Average loss ratio across the fast tier with a fresh measurement, as a
     * fraction (0.0 - 1.0); 0.0 if none. See {@link #getAverageLossRatio}.
     *
     * @return the mean fast-tier loss ratio as a fraction of 1.0, or 0.0 if no fresh data
     * @since 0.9.71+
     */
    public float getFastAverageLossRatio() {
        if (!tryReadLock()) return 0.0f;
        try {return averageLossRatio(_fastPeers);}
        finally {releaseReadLock();}
    }

    /**
     * Average loss ratio across the high-capacity tier with a fresh measurement,
     * as a fraction (0.0 - 1.0); 0.0 if none. See {@link #getAverageLossRatio}.
     *
     * @return the mean high-capacity-tier loss ratio as a fraction of 1.0, or 0.0 if no fresh data
     * @since 0.9.71+
     */
    public float getHighCapAverageLossRatio() {
        if (!tryReadLock()) return 0.0f;
        try {return averageLossRatio(_highCapacityPeers);}
        finally {releaseReadLock();}
    }

    /**
     * Median loss ratio of the union of the fast and high-capacity tiers with a
     * fresh measurement, as a fraction (0.0 - 1.0). 0.0 if none. The median is
     * robust to the worst peers dragging the mean up, so the auto-tuned demotion
     * threshold tracks the typical tier member. See {@link #getAverageLossRatio}.
     *
     * @return the median tier loss ratio as a fraction of 1.0, or 0.0 if no fresh data
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
     * Fallback tier size for a threshold that scales with network size.
     * <p>Networks at or below {@link #SCALING_THRESHOLD} known routers use the
     * configured base; above it the base scales with a fraction of the network.
     *
     * @param known routers in the network database
     * @param base configured size to use, and the floor once scaling applies
     * @param divisor network size is divided by this once scaling applies
     * @return the size to use unless a property overrides it
     * @since 0.9.71+
     */
    static int defaultTierSize(int known, int base, int divisor) {
        return known > SCALING_THRESHOLD ? Math.max(known / divisor, base) : base;
    }

    /**
     * Ceiling on the fast tier from the number of reachable peers.
     * <p>1.5x headroom over active for near-active peers, never below {@code base}.
     *
     * @param active peers reachable over any transport
     * @param base floor to keep even when very few peers are reachable
     * @return the fast tier ceiling
     * @since 0.9.71+
     */
    static int fastActiveCap(int active, int base) {
        return Math.max(active + active / 2, base);
    }

    /**
     * Ceiling on the high-capacity tier from the number of reachable peers.
     * <p>One per reachable peer, never below {@code base}.
     * <p>Was {@code active * 2}. That asks for more members than there are peers to
     * put in them: at 1221 active peers it set a target of 2442 for a tier that
     * tops out around 700, so the target could never be met, the early return in
     * {@link #promoteToFillTiers} could never fire, and every demotion walked the
     * whole capacity-ordered profile set looking for peers to seat in a tier that
     * had already run out of candidates. A high-capacity member that is not
     * reachable is not reservoir depth, it is a slot that cannot be used.
     *
     * @param active peers reachable over any transport
     * @param base floor to keep even when very few peers are reachable
     * @return the high-capacity tier ceiling
     * @since 0.9.71+
     */
    static int highCapActiveCap(int active, int base) {
        return Math.max(active, base);
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
     * The fast tier's minimum size, for callers outside this package.
     * <p>This is the number that means "the fast tier is stocked". The peer-supply
     * gate in {@link net.i2p.router.tunnel.pool.TunnelPeerSelector#scarceFastPeers(int)}
     * is expressed against it, so a router is not told its supply is ample at 23%
     * of the tier it asked for.
     * <p>Reads the network database size, so it is a per-cycle call, not a
     * per-candidate one.
     *
     * @return the configured fast tier minimum
     * @since 0.9.72+
     */
    public int getMinimumFastPeersCount() {
        return getMinimumFastPeers();
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
     * Current high-capacity tier population.  Paired with
     * {@link #getFastPeerCount} so first-hop selection can report the pool
     * it drew from: a 256-candidate sample means something very different
     * against a 200-peer tier (the whole pool) than a 1500-peer one (a
     * fraction), and the two call for different responses.
     *
     * @return the number of peers in the high-capacity tier
     * @since 0.9.71+
     */
    public int getHighCapPeerCount() {
        return _highCapacityPeers.size();
    }

    /**
     * The candidate-sample floor currently in effect, for logging.
     * @param ctx the router context
     * @return the floor passed to {@link #maxCandidateSample}
     * @since 0.9.71+
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

    /** Shared formatter for the statistics display. */
    private static final DecimalFormat _fmt = new DecimalFormat("###,##0.00", new DecimalFormatSymbols(Locale.UK));
    /**
     * Formats one number for the statistics display.
     *
     * @param num the value to format
     * @return the formatted text
     */
    private static final String num(double num) {synchronized (_fmt) {return _fmt.format(num);} }

    /**
     * Command-line entry point; reads profile dump files and prints thresholds.
     *
     * @param args paths to gzipped profile dump files
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
     * Short-lived cache for {@link #getTunnelBuildSuccess}.  The 6 RateStat
     * lookups are expensive when called per-candidate in peer selection (up to
     * 400 peers × 6 lookups).  The ratio is a 10-minute rolling average, so
     * a 15s cache (&lt;2.5% staleness) eliminates ~40x redundant lookups with
     * negligible impact on decision accuracy.
     */
    private volatile double _cachedBuildSuccess = 1.0;
    /** When the build success ratio was last read. */
    private volatile long _cachedBuildSuccessTime;
    /** How long the cached build success ratio stays fresh. */
    private static final long BUILD_SUCCESS_CACHE_MS = 15_000;

    /**
     * Peers with zero tunnel history are allowed into fast/high-cap tiers if
     * they were heard from within this window.  Alive peers that haven't yet
     * built tunnels get a chance. Dead peers are excluded.  Under-performers
     * are evicted by existing mechanisms (latency, acceptance ratio, loss
     * probation) within minutes.
     *
     * @since 0.9.71+
     */
    private static final long NO_HISTORY_ACTIVITY_WINDOW_MS = 5 * 60 * 1000L;

    /**
     * Recent tunnel build success ratio, from router statistics.
     * <p>
     * TEN_MINUTES window: a short window reacts quickly to network health
     * changes, so the attack-mode gates below track the current situation
     * instead of lifetime averages.  When no data is available (stats not
     * yet created at boot, or an empty window), the ratio is 1.0 — no data
     * means neutral, not "under attack". A missing-stat 0.0 put the router
     * into permanent attack mode before stats existed.
     * <p>The cache covers the no-data path too: on fallback the cached 1.0 is
     * (re)stamped so a missing StatManager early in boot cannot turn every
     * per-candidate call into a fresh 6-lookup scan.
     *
     * @return the tunnel build success in [0.0, 1.0], 1.0 when no data
     * @since 0.9.71+
     */
    /**
     * Maps an unknown build-success reading onto a neutral 1.0.
     * <p>An absent stat, or a window in which nothing has settled yet, is not evidence of
     * failure. Reading it as anything else would trip {@link #isLowBuildSuccess} and switch
     * off profile eviction, lengthen ghost cooldowns and put the first-hop selector into its
     * defensive path for no reason - so "unknown" has to arrive upstream of every one of those
     * as 1.0.
     *
     * @param ratio the raw ratio, or NaN when unknown
     * @return {@code ratio}, or 1.0 when it is NaN
     * @since 0.9.71+
     */
    static double neutraliseUnknownBuildSuccess(double ratio) {
        return Double.isNaN(ratio) ? 1.0 : ratio;
    }

    /**
     * Tunnel build success ratio across the whole router, cached for
     * {@code BUILD_SUCCESS_CACHE_MS} because a single lookup costs several rate
     * reads. 1.0 (neutral) when the window is absent or empty.
     *
     * @return the build success ratio as a fraction of 1.0
     */
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
     *
     * @param profile profile to write
     */
    public void writeProfile(PeerProfile profile) {
        _persistenceHelper.writeProfile(profile);
    }

      /**
       * Calibrated latency floor across fast peers with data, computed during each
       * {@link #reorganize} as the mean of the fastest samples rather than of the
       * whole population.
       * <p>This is a second bound on the low-latency judgement, not a second
       * judgement: a peer must satisfy both the per-transport calibrated floor and
       * this cohort floor. It is measured from peer-test response times, a
       * different quantity from the direct-link RTT behind the per-transport
       * floor, so the two are never averaged together and this one cannot stand
       * in for the other.
       *
       * @return the cohort floor in ms, 0 when fewer than
       * {@link ProtocolRttBaseline#SAMPLES_FOR_BASELINE} peers have data
       * @since 0.9.71+
       */
    public float getAverageLowLatencyRTT() {
        return _averageLowLatencyRTT;
    }

    /**
     * Compute average peer test response time across fast peers with data.
     * Pure decision — no write lock required; reads the already-rebuilt _fastPeers.
     *
     * @return the average RTT in ms, 0 if fewer than
     *         {@link ProtocolRttBaseline#SAMPLES_FOR_BASELINE} peers have data
     * @since 0.9.71+
     */
    private float computeAverageLowLatencyRTT() {
        // Calibrated the same way as the per-transport floors: the mean of the
        // fastest samples rather than of the whole population, so it expresses an
        // achievable floor and does not follow the mean upward as the network
        // degrades. A mean over every peer would relax the bar precisely when
        // quality matters most.
        ProtocolRttBaseline baseline = new ProtocolRttBaseline();
        for (PeerProfile p : _fastPeers.values()) {
            float rtt = p.getPeerTestTimeAverage();
            if (rtt > 0) {baseline.add((int) rtt);}
        }
        long floor = baseline.getBaselineMs();
        return (floor > 0) ? (float) floor : 0;
      }

  }
