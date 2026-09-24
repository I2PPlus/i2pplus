package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.stat.RateConstants;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Banlist;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Single threaded controller of the tunnel creation process, spanning all tunnel pools.
 * Essentially, this loops across the pools, sees which want to build tunnels, and fires
 * off the necessary activities if the load allows.  If nothing wants to build any tunnels,
 * it waits for a short period before looping again (or until it is told that something
 * changed, such as a tunnel failed, new client started up, or tunnel creation was aborted).
 *
 * Note that the default 11 minute tunnel expiration is assumed in here.
 *
 * As of 0.8.11, inbound request handling is done in a separate thread.
 */
public class BuildExecutor implements Runnable {
    private static int getTunnelTargetMin(RouterContext ctx) {
        return ctx != null ? ctx.getProperty("i2p.tunnel.build.targetMin", 2) : 2;
    }

    /**
     *  Priority score for build urgency.
     *  Higher = build sooner. Must be a pure function of pool state so the
     *  snapshot comparator remains a consistent total order.
     *  Zero-hop emergency pools are not ranked here — they are ordered by
     *  {@link #DISPATCH_COMPARATOR} ahead of every scored pool, so this
     *  score only arbitrates among ordinary demand.
     */
    private static int score(TunnelPool p) {
        int active = p.getUsableTunnelCount();
        int target = Math.max(2, p.getSettings().getTotalQuantity());
        int deficit = target - active;
        // Tier 1: collapsed (0 usable) outranks everything.
        if (active == 0) {return 1 << 20;}
        // Tier 2: near-collapse (1-2 active) gets a large boost.
        int s = active <= 2 ? (1 << 16) : 0;
        // Tier 3: larger deficit builds sooner.
        s += Math.max(0, deficit) * 100;
        // Tier 4: paired balance — if this pool's pair is ahead, boost it.
        TunnelPool paired = p.getPairedPool();
        if (paired != null) {
            int pairActive = paired.getActiveTunnelCount();
            int behind = pairActive - active;
            if (behind > 0) {s += behind * 10;}
        }
        return s;
    }

    /**
     *  Comparator for snapshotted {@code Object[2]} rows where
     *  {@code row[0]} is the score (Integer) and {@code row[1]} is the
     *  pool.  Higher score first, then stable tiebreaker.
     *  Must never be called with rows from different array instances
     *  (identityHashCode is only unique within one snapshot).
     */
    private static final Comparator<Object[]> SCORE_COMPARATOR =
            (a, b) -> {
                int cmp = Integer.compare((int) b[0], (int) a[0]);
                if (cmp != 0) return cmp;
                return Integer.compare(System.identityHashCode(a[1]), System.identityHashCode(b[1]));
            };

    /**
     *  Dispatch comparator for snapshotted {@code Object[3]} rows where
     *  {@code row[0]} is the score (Integer), {@code row[1]} the pool and
     *  {@code row[2]} the zero-hop emergency flag (Boolean): emergency
     *  replacements go first regardless of deficit, then higher score
     *  first.  Deliberately no identity-hash tiebreaker — Arrays.sort is
     *  stable, so full ties keep their allocation order from
     *  calculatePairedBuilds() instead of an arbitrary per-session order
     *  that could starve one of two equally-ranked pools indefinitely.
     *
     *  @since 0.9.71+
     */
    static final Comparator<Object[]> DISPATCH_COMPARATOR =
            (a, b) -> {
                int cmp = Boolean.compare((Boolean) b[2], (Boolean) a[2]);
                if (cmp != 0) {return cmp;}
                return Integer.compare((int) b[0], (int) a[0]);
            };

    /**
     *  Whether this pass should treat the pool as a zero-hop emergency:
     *  a multi-hop-configured, non-ping pool currently serving traffic
     *  through a length-1 bootstrap fallback.  Such tunnels provide no
     *  anonymity, so their replacement gets an unconditional build floor
     *  in calculatePairedBuilds() and first claim on dispatch slots in
     *  run2().  Single shared definition so the allocation stage and the
     *  dispatch stage can never disagree about which pools are urgent.
     *
     *  @param pool candidate pool, non-null
     *  @return true if the pool carries a zero-hop fallback it did not ask for
     *  @since 0.9.71+
     */
    static boolean isZeroHopEmergency(TunnelPool pool) {
        String nick = pool.getSettings().getDestinationNickname();
        boolean isPing = nick != null && nick.startsWith("Ping");
        return !isPing && !pool.getSettings().isZeroHop()
               && pool.hasZeroHopFallback();
    }

    /**
     *  Effective tunnel-count target a pool builds toward.  Pools flagged
     *  keepConfiguredQty (ping pools, and pools expressly configured for
     *  zero hops where a length-1 tunnel IS the product) keep their
     *  configured quantity; all others hold at least targetMin tunnels
     *  per direction so a single failure can't empty the pool or starve
     *  the LeaseSet.
     *
     *  @param ctx router context, for the configurable floor and tuner buffer
     *  @param s settings of the pool in question
     *  @param keepConfiguredQuantity true to return the configured quantity verbatim
     *  @return the effective target: the configured quantity when flagged,
     *          else at least 2
     *  @since 0.9.71+
     */
    static int effectiveTarget(RouterContext ctx, TunnelPoolSettings s, boolean keepConfiguredQuantity) {
        int wantedCount = s.getTotalQuantity();
        if (keepConfiguredQuantity) {return wantedCount;}
        return Math.max(2, Math.max(getTunnelTargetMin(ctx),
                                    wantedCount + getTunnelTargetBuffer(ctx)));
    }

    private final Set<Long> _recentBuildIds = new LinkedHashSet<>(129);
    private final RouterContext _context;
    private final Log _log;
    private final TunnelPoolManager _manager;
    private final GhostPeerManager _ghostPeerManager;
    private final Object _currentlyBuilding; // Notify lock
    private final ConcurrentHashMap<Long, PooledTunnelCreatorConfig> _currentlyBuildingMap; // indexed by ptcc.getReplyMessageId()
    private final ConcurrentHashMap<Long, PooledTunnelCreatorConfig> _recentlyBuildingMap; // indexed by ptcc.getReplyMessageId()
    private volatile boolean _isRunning;
    private boolean _repoll;
    private long _lastBuildPassTime;
    private final AtomicInteger _buildSuccessCount = new AtomicInteger();
    private final AtomicInteger _buildFailureCount = new AtomicInteger();
    private final ConcurrentHashMap<TunnelPool, Long> _lastRebuildTime = new ConcurrentHashMap<>(64);
    /**
     *  Throttle for peer-selection hot path: BldExecutor was pegging at 98%
     *  in ClientPeerSelector.selectSingleHop → IBGWExcluder.contains
     *  even after endpoint caching, because the tight loop called
     *  pool.configureNewTunnel() for every wanted pool without per-pool
     *  spacing.  Skip the heavy peer selection if this pool built within
     *  the throttle window and still has builds in flight.  Entries are
     *  dropped in shutdown()/removePoolState() so the map cannot leak.
     *  @since 0.9.71+
     */
    private final ConcurrentHashMap<TunnelPool, Long> _lastConfigureTime = new ConcurrentHashMap<>(16);
    private static final long CONFIGURE_THROTTLE_MS = 10000L;
    private final AtomicInteger _buildTimeoutCount = new AtomicInteger();
    private final AtomicInteger _firstHopSuccessCount = new AtomicInteger();
    private final AtomicInteger _firstHopFailureCount = new AtomicInteger();
    /**
     *  Sliding window of recent build results for smooth rate calculation.
     *  Replaces the old counter-halving approach which caused sawtooth
     *  oscillation in the timeout rate.  Each entry is a Result ordinal:
     *  SUCCESS(0), BAD_RESPONSE(1), ..., TIMEOUT(10), etc.  The window
     *  is indexed by a monotonically increasing counter modulo WINDOW_SIZE.
     *
     *  @since 0.9.71+
     */
    private static final int WINDOW_SIZE = 100;
    private final byte[] _buildResults = new byte[WINDOW_SIZE];
    private final AtomicInteger _windowWriteIndex = new AtomicInteger();
    /**
     *  Adaptive concurrency throttle: tracks the timeout rate and adjusts
     *  maxConcurrentBuilds dynamically.  When timeout rate exceeds 30%,
     *  builds are throttled to prevent overwhelming the IB reply path.
     *  When timeout rate drops below 15% and success exceeds 80%,
     *  concurrency is gradually restored.
     *
     *  @since 0.9.71+
     */
    private volatile double _timeoutRate;
    private volatile int _adaptiveMaxConcurrentBuilds;
    /**
     *  First-hop failure history: tracks recent first-hop failures to avoid
     *  repeatedly selecting peers that have recently failed as first hops.
     *  Maps: Hash -> [failureCount, lastFailureTimeMs]
     *
     *  @since 0.9.71+
     */
    private final ConcurrentHashMap<Hash, long[]> _firstHopFailureHistory = new ConcurrentHashMap<>(64);

    /**
     *  Maximum age (ms) for first-hop failure history entries.
     *  Entries older than this are ignored during lookup.
     *  Tunable via {@link Tuner}.
     *
     *  @since 0.9.71+
     */
    private static volatile long FIRST_HOP_FAILURE_COOLDOWN_MS = 2 * 60 * 1000L;

    /**
     *  Number of failures within {@link #FIRST_HOP_FAILURE_COOLDOWN_MS}
     *  required to skip a peer as first hop.  A single transient failure
     *  should not permanently exclude a peer; repeated failures indicate
     *  a persistent issue.
     *
     *  @since 0.9.71+
     */
    private static volatile int FIRST_HOP_FAILURE_THRESHOLD = 3;

    /**
     *  The first-hop failure cooldown in milliseconds.
     *  @return the cooldown in ms
     *  @since 0.9.71+
     */
    public static long getFirstHopFailureCooldownMs() { return FIRST_HOP_FAILURE_COOLDOWN_MS; }
    /**
     *  Set the first-hop failure cooldown (called by Tuner).
     *  @param ms cooldown in ms (60000-600000)
     *  @since 0.9.71+
     */
    public static void setFirstHopFailureCooldownMs(long ms) { FIRST_HOP_FAILURE_COOLDOWN_MS = Math.max(60_000, Math.min(600_000, ms)); }
    /**
     *  The first-hop failure threshold count.
     *  @return the threshold
     *  @since 0.9.71+
     */
    public static int getFirstHopFailureThreshold() { return FIRST_HOP_FAILURE_THRESHOLD; }
    /**
     *  Set the first-hop failure threshold (called by Tuner).
     *  @param count threshold count (1-10)
     *  @since 0.9.71+
     */
    public static void setFirstHopFailureThreshold(int count) { FIRST_HOP_FAILURE_THRESHOLD = Math.max(1, Math.min(10, count)); }
    /**
     *  Stale build pruning threshold fraction.  When the time elapsed
     *  since a build was configured exceeds this fraction of the adaptive
     *  timeout budget, the build is skipped (it would timeout anyway).
     *  Expressed as percentage (e.g. 40 means 40%).  Tunable via {@link Tuner}.
     *
     *  @since 0.9.71+
     */
    private static volatile int STALE_BUILD_THRESHOLD_PCT = 40;

    /**
     *  Maximum concurrent in-flight builds per pool per direction (inbound
     *  or outbound).  Enforces fair build distribution across pools so no
     *  single pool monopolizes build slots.  Each pool's inbound and
     *  outbound directions are tracked independently, so a pool with
     *  target 3/2 can have 2 in-flight inbound and 2 in-flight outbound
     *  simultaneously.
     *  Raised from 2 so incomplete-LeaseSet / depleted pools can stage
     *  replacements in parallel without serializing on the configure throttle.
     *
     *  @since 0.9.71+
     */
    private static final int MAX_PER_POOL_DIR = 4;

    /**
     *  The stale build pruning threshold percentage.
     *  @return the threshold percentage (30-80)
     *  @since 0.9.71+
     */
    public static int getStaleBuildThresholdPct() { return STALE_BUILD_THRESHOLD_PCT; }
    /**
     *  Set the stale build pruning threshold (called by Tuner).
     *  @param val the threshold percentage (30-80)
     *  @since 0.9.71+
     */
    public static void setStaleBuildThresholdPct(int val) { STALE_BUILD_THRESHOLD_PCT = Math.max(30, Math.min(80, val)); }
    /**
     * Per-pool consecutive build failure tracking for backoff.
     * When a pool exceeds CONSECUTIVE_FAILURE_THRESHOLD, builds are
     * paused for POOL_BACKOFF_MS to prevent build storms.
     * Maps: TunnelPool -> [consecutiveFailures, backoffUntilMs]
     *
     * Threshold of 5 allows transient failures (network hiccups, slow peers)
     * without triggering premature backoff.  Previous threshold of 3 caused
     * cascading pool collapses: 3 failures → backoff → tunnels expire →
     * EMERGENCY → more failures → higher backoff counter → death spiral.
     *
     * Backoff of 12s is long enough to avoid build storms but short enough
     * that tunnels expiring during backoff can be rebuilt before the pool
     * fully collapses.  Previous 8s was too short to prevent repeated storms
     * when the underlying issue was transient.
     */
    private static volatile int CONSECUTIVE_FAILURE_THRESHOLD = 5;
    private static volatile long POOL_BACKOFF_MS = 12 * 1000L;

    /**
     *  Jittered backoff: randomize within ±33% of {@link #POOL_BACKOFF_MS}
     *  to prevent synchronized backoff where all pools are skipped in the
     *  same cycle.  With a 12s base, the effective range is 8-16s.
     *
     *  @return a jittered backoff duration in ms
     *  @since 0.9.71+
     */
    private long jitteredBackoff() {
        long jitter = POOL_BACKOFF_MS / 3;
        return POOL_BACKOFF_MS - jitter + _context.random().nextLong(2 * jitter + 1);
    }

    /**
     * The pool failure threshold.
     * @return the threshold
     * @since 0.9.70+
     */
    public static int getPoolFailureThreshold() { return CONSECUTIVE_FAILURE_THRESHOLD; }
    /**
     * The pool failure threshold.
     * @param val the threshold value (1-20)
     * @since 0.9.70+
     */
    public static void setPoolFailureThreshold(int val) { CONSECUTIVE_FAILURE_THRESHOLD = Math.max(1, Math.min(20, val)); }
    /**
     * The pool backoff time in milliseconds.
     * @return the backoff in ms
     * @since 0.9.70+
     */
    public static long getPoolBackoffMs() { return POOL_BACKOFF_MS; }
    /**
     * The pool backoff time in milliseconds.
     * @param val the backoff in ms (1000-60000)
     * @since 0.9.70+
     */
    public static void setPoolBackoffMs(long val) { POOL_BACKOFF_MS = Math.max(1000, Math.min(60000, val)); }
    private final ConcurrentHashMap<TunnelPool, long[]> _poolFailureState = new ConcurrentHashMap<>(64);
    private long _lastKeepAliveTime;
    private volatile long _adaptiveTimeout;
    private volatile long _adaptiveFirstHopTimeout;

    /**
     * The tunnel lifetime from config, delegated to TunnelPool.
     *
     * @param ctx the router context
     * @return the tunnel lifetime in milliseconds
     */
    static int getTunnelLifetime(RouterContext ctx) {
        return TunnelPool.getTunnelLifetime(ctx);
    }

    /**
     * The target build buffer from config or default (0).
     * Extra tunnels to maintain beyond the configured quantity.
     * Tunable via i2p.tunnel.targetBuffer (default: 0).
     * Tuned value overrides config, when set by the Tuner.
     * @since 0.9.71+
     */
    private static volatile int _tunedTargetBuffer = -1;

    /**
     * The target build buffer from config or default (0).
     * Extra tunnels to maintain beyond the configured quantity.
     * Tunable via i2p.tunnel.targetBuffer (default: 0).
     *
     * @param ctx the router context
     * @return the target buffer count
     */
    public static int getTunnelTargetBuffer(RouterContext ctx) {
        int tuned = _tunedTargetBuffer;
        if (tuned >= 0) return tuned;
        return ctx != null ? ctx.getProperty("i2p.tunnel.targetBuffer", 0) : 0;
    }

    /**
     * Set the target build buffer (called by Tuner).
     *
     * @param count the target buffer count
     * @since 0.9.71+
     */
    public static void setTunnelTargetBuffer(int count) { _tunedTargetBuffer = count; }

    /**
     * The GOOD deficit throttle interval from config or default (30s).
     * Minimum time between GOOD-tunnel deficit rebuilds for non-critical pools.
     * Tunable via i2p.tunnel.goodDeficitThrottle (default: 30000).
     * Tuned value overrides config, when set by the Tuner.
     * @since 0.9.71+
     */
    private static volatile long _tunedGoodDeficitThrottle = -1;

    /**
     * The GOOD deficit throttle interval from config or default (30s).
     * Minimum time between GOOD-tunnel deficit rebuilds for non-critical pools.
     * Tunable via i2p.tunnel.goodDeficitThrottle (default: 30000).
     *
     * @param ctx the router context
     * @return the throttle interval in milliseconds
     */
    public static long getGoodDeficitThrottle(RouterContext ctx) {
        long tuned = _tunedGoodDeficitThrottle;
        if (tuned >= 0) return tuned;
        return ctx.getProperty("i2p.tunnel.goodDeficitThrottle", 30000);
    }

    /**
     * Set the GOOD deficit throttle interval (called by Tuner).
     *
     * @param ms the throttle interval in milliseconds
     * @since 0.9.71+
     */
    public static void setGoodDeficitThrottle(long ms) { _tunedGoodDeficitThrottle = ms; }
    /**
     * The maximum number of concurrent tunnel builds allowed.
     * Calculated based on CPU cores and configurable multiplier
     *
     * @return maximum concurrent builds allowed
     * @since 0.9.70+ configurable via setter
     */
    private static volatile int _maxConcurrentBuilds = Math.max(SystemVersion.getCores() * 4, 32);

    /**
     * The maximum number of concurrent builds allowed.
     * @return maximum concurrent builds
     * @since 0.9.70+
     */
    public static int getMaxConcurrentBuilds() { return _maxConcurrentBuilds; }

    /**
     *  The adaptive maximum concurrent builds, adjusted based on the
     *  current timeout rate.  Returns the throttled value when the
     *  timeout rate exceeds {@link #CONCURRENCY_THROTTLE_THRESHOLD},
     *  otherwise returns the configured maximum.
     *
     *  @return adaptive maximum concurrent builds
     *  @since 0.9.71+
     */
    int getAdaptiveMaxConcurrentBuilds() { return _adaptiveMaxConcurrentBuilds; }

    /**
     *  The current timeout rate (0.0-1.0) for adaptive throttling.
     *
     *  @return the timeout rate
     *  @since 0.9.71+
     */
    double getTimeoutRate() { return _timeoutRate; }

    /**
     *  Package-visible for tests: returns how many results are in the window.
     *  @since 0.9.71+
     */
    int getWindowCount() { return Math.min(_windowWriteIndex.get(), WINDOW_SIZE); }

    /**
     * The maximum number of concurrent builds allowed.
     * @param val the maximum concurrent builds
     * @since 0.9.70+
     */
    public static void setMaxConcurrentBuilds(int val) { _maxConcurrentBuilds = Math.max(8, Math.min(256, val)); }

    private static final int LOOP_TIME = 15000;

    /**
     *  Minimum time between consecutive build passes.  Fast build completions
     *  wake the loop (buildComplete notifyAll), so without a floor the loop
     *  re-passes near-continuously during a cascade (~180 builds/min observed)
     *  instead of building in fewer, higher-quality bursts.
     *  @since 0.9.71+
     */
    private static final int MIN_BUILD_SPACING_MS = 2000;

    /**
     *  Pool-backoff counting excludes results that are not peer failures:
     *  SUCCESS, DUP_ID (already handled), REJECT (peer said no),
     *  NO_TUNNELS (local resource condition), NO_NETDB (local netdb miss),
     *  and SKIPPED (local policy, no build dispatched).
     *
     *  @param result the build result
     *  @return true if the result increments the pool consecutive-failure counter
     *  @since 0.9.71+
     */
    static boolean countsAsPoolFailure(Result result) {
        return result != Result.SUCCESS && result != Result.DUP_ID &&
               result != Result.REJECT && result != Result.NO_TUNNELS &&
               result != Result.NO_NETDB && result != Result.SKIPPED;
    }

    /**
     *  Remaining build-pass spacing in ms: 0 when the spacing floor since the
     *  last pass is satisfied, otherwise the time still left to wait.
     *
     *  @param lastPassTime the end of the last build pass in ms
     *  @param now the current time in ms
     *  @return ms still to wait, 0 if the floor is met
     *  @since 0.9.71+
     */
    static long spacingDelay(long lastPassTime, long now) {
        return Math.max(0, MIN_BUILD_SPACING_MS - (now - lastPassTime));
    }

    private static final int TUNNEL_POOLS = 8;
    /** keepAlive() cadence in the executor loop (see run2()) */
    private static final long KEEPALIVE_INTERVAL_MS = 30 * 1000L;

    private static long getGracePeriod(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.build.gracePeriod", 60*1000);
    }
    private static final long[] RATES = { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR };
    /**
     * Check if full statistics are enabled.
     * @return true if full statistics are enabled
     */
    public boolean fullStats() {return _context.getBooleanProperty("stat.full");}

    /** Build result enumeration. @since 0.9.53 */
    enum Result {
        /** Build succeeded */
        SUCCESS,
        /** Build was rejected */
        REJECT,
        /** Build timed out */
        TIMEOUT,
        /** Bad response received */
        BAD_RESPONSE,
        /** Duplicate build ID */
        DUP_ID,
        /** No paired or exploratory tunnel was available to send the build */
        NO_TUNNELS,
        /** A hop's RouterInfo was not available in the local netdb */
        NO_NETDB,
        /** Build configuration skipped by local policy before dispatch */
        SKIPPED,
        /** Other failure */
        OTHER_FAILURE
    }

    /**
     * Create a new BuildExecutor.
     *
     * @param ctx the router context
     * @param mgr the tunnel pool manager
     * @param ghostMgr the ghost peer manager for tracking timeouts
     */
    public BuildExecutor(RouterContext ctx, TunnelPoolManager mgr, GhostPeerManager ghostMgr) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _manager = mgr;
        _ghostPeerManager = ghostMgr;
        _adaptiveTimeout = BuildRequestor.getRequestTimeout(ctx);
        _adaptiveFirstHopTimeout = BuildRequestor.getFirstHopTimeout(ctx);
        _adaptiveMaxConcurrentBuilds = getMaxConcurrentBuilds();
        _timeoutRate = 0.0;
        _currentlyBuilding = new Object();
        int maxConcurrentBuilds = getMaxConcurrentBuilds();
        _currentlyBuildingMap = new ConcurrentHashMap<>(maxConcurrentBuilds);
        _recentlyBuildingMap = new ConcurrentHashMap<>(4 * maxConcurrentBuilds);
        _context.statManager().createRequiredRateStat("tunnel.buildFailFirstHop", "OB tunnel build failure frequency (can't contact 1st hop)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildClientExpire", "No response to our build request", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildClientReject", "Response time for rejection (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildClientSuccess", "Response time for success (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildExploratoryExpire", "No response to our build request", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildExploratoryReject", "Response time for rejection (ms)", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildExploratorySuccess", "Response time for success (ms)", "Tunnels [Exploratory]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildRequestTime", "Time to build a tunnel request (ms)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.concurrentBuilds", "How many builds are going at once", "Tunnels", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("tunnel.buildSuccessRate", "Tunnel build success rate (0-100)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildFailureRate", "Tunnel build failure rate (0-100)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildTimeoutRate", "Tunnel build timeout rate (0-100)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildPacedOut", "Tunnel build skipped (1st hop busy)", "Tunnels", RATES);
        _context.statManager().createRequiredRateStat("tunnel.buildStalePruned", "Builds pruned due to stale queue", "Tunnels", RATES);

        StatManager statMgr = _context.statManager(); // Get stat manager, get recognized bandwidth tiers
        String bwTiers = RouterInfo.BW_CAPABILITY_CHARS; // For each bandwidth tier, create tunnel build agree/reject/expire stats
        for (int i = 0; i < bwTiers.length(); i++) {
            String bwTier = String.valueOf(bwTiers.charAt(i));
            statMgr.createRequiredRateStat("tunnel.tierAgree" + bwTier, "Agreed joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
            statMgr.createRequiredRateStat("tunnel.tierReject" + bwTier, "Rejected joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
            statMgr.createRequiredRateStat("tunnel.tierExpire" + bwTier, "Expired joins from bandwidth tier " + bwTier, "Tunnels [Participating]", RATES);
        }
    }

    /**
     *  Restart the build executor, clearing recent build state.
     *  @since 0.9
     */
    public synchronized void restart() {
        synchronized (_recentBuildIds) {_recentBuildIds.clear();}
        _currentlyBuildingMap.clear();
        _recentlyBuildingMap.clear();
    }

    /**
     *  Cannot be restarted.
     *  @since 0.9
     */
    public synchronized void shutdown() {
        _isRunning = false;
        _poolFailureState.clear();
        _lastRebuildTime.clear();
        _lastConfigureTime.clear();
        restart();
    }

    /**
     *  Remove failure state for a pool that is being removed.
     *  Prevents unbounded growth of _poolFailureState, _lastRebuildTime,
     *  and _lastConfigureTime across pool lifecycles.
     *  @param pool the pool to remove state for
     *  @since 0.9.70
     */
    void removePoolState(TunnelPool pool) {
        _poolFailureState.remove(pool);
        _lastRebuildTime.remove(pool);
        _lastConfigureTime.remove(pool);
    }

    /**
     *  Failure tracking state for a pool, created if absent.
     *  Uses get()+putIfAbsent() instead of computeIfAbsent() to avoid
     *  per-call allocation of the fallback array and reduce lock contention.
     *  @return existing or newly-created long[2]
     *  @since 0.9.70+
     */
    private long[] getOrCreatePoolState(TunnelPool pool) {
        long[] state = _poolFailureState.get(pool);
        if (state == null) {
            state = new long[]{0, 0};
            long[] existing = _poolFailureState.putIfAbsent(pool, state);
            if (existing != null)
                state = existing;
        }
        return state;
    }

    /**
     * Update success/failure/timeout counters and calculate adaptive timeout
     */
    private void updateBuildStats(Result result) {
        StatManager sm = _context.statManager();
        // Record result in sliding window
        int idx = _windowWriteIndex.getAndIncrement() % WINDOW_SIZE;
        _buildResults[idx] = (byte) result.ordinal();
        // Also track legacy counters for backward-compatible stat emission
        if (result == Result.SUCCESS) {
            _buildSuccessCount.incrementAndGet();
        } else if (result == Result.TIMEOUT) {
            _buildTimeoutCount.incrementAndGet();
        } else {
            _buildFailureCount.incrementAndGet();
        }
        // Compute rates from sliding window (smooth, no sawtooth artifact)
        int count = Math.min(_windowWriteIndex.get(), WINDOW_SIZE);
        if (count > 0) {
            int successes = 0, timeouts = 0, failures = 0;
            for (int i = 0; i < count; i++) {
                byte r = _buildResults[i];
                if (r == Result.SUCCESS.ordinal()) successes++;
                else if (r == Result.TIMEOUT.ordinal()) timeouts++;
                else if (r != 0) failures++;
            }
            sm.addRateData("tunnel.buildSuccessRate", (successes * 100) / count, 0);
            sm.addRateData("tunnel.buildFailureRate", ((long) failures * 100) / count, 0);
            sm.addRateData("tunnel.buildTimeoutRate", ((long) timeouts * 100) / count, 0);
        }
        // Recalculate adaptive timeout every 50 builds (smooth window, no counter reset)
        if (_windowWriteIndex.get() % 50 == 0 && _windowWriteIndex.get() >= 50) {
            calculateAdaptiveTimeoutFromSuccess();
        }
    }

    /**
     *  Adaptive concurrency throttle thresholds.
     *  When timeout rate exceeds {@code THROTTLE_THRESHOLD}, concurrent
     *  builds are reduced to prevent overwhelming the IB reply path.
     *  When timeout rate drops below {@code RESTORE_THRESHOLD} and success
     *  exceeds {@code RESTORE_SUCCESS_THRESHOLD}, concurrency is restored.
     *
     *  @since 0.9.71+
     */
    private static double CONCURRENCY_THROTTLE_THRESHOLD = 0.30;
    private static double CONCURRENCY_RESTORE_THRESHOLD = 0.15;
    private static double CONCURRENCY_RESTORE_SUCCESS_THRESHOLD = 0.80;

    /**
     *  The concurrency throttle threshold as a percentage (0-100).
     *  @return the threshold percentage
     *  @since 0.9.71+
     */
    public static int getConcurrencyThrottleThresholdPct() { return (int) (CONCURRENCY_THROTTLE_THRESHOLD * 100); }
    /**
     *  Set the concurrency throttle threshold (called by Tuner).
     *  @param pct the threshold percentage (15-50)
     *  @since 0.9.71+
     */
    public static void setConcurrencyThrottleThresholdPct(int pct) {
        CONCURRENCY_THROTTLE_THRESHOLD = Math.max(0.15, Math.min(0.50, pct / 100.0));
    }
    /**
     *  The concurrency restore threshold as a percentage (0-100).
     *  @return the restore threshold percentage
     *  @since 0.9.71+
     */
    public static int getConcurrencyRestoreThresholdPct() { return (int) (CONCURRENCY_RESTORE_THRESHOLD * 100); }

    /**
     *  Calculate the per-iteration build cap from the current timeout rate.
     *  Proportional scaling (1-4) avoids the binary oscillation that the
     *  old 2-vs-4 threshold caused around the 30% boundary.
     *
     *  @param timeoutRate the current timeout rate (0.0-1.0)
     *  @return cap between 1 and 4
     *  @since 0.9.71+
     */
    static int calculatePerIterationCap(double timeoutRate) {
        if (timeoutRate <= CONCURRENCY_RESTORE_THRESHOLD) return 4;
        if (timeoutRate <= CONCURRENCY_THROTTLE_THRESHOLD) return 3;
        if (timeoutRate <= 0.50) return 2;
        return 1;
    }

    /**
     * Calculate adaptive timeouts based on recorded build outcomes.
     * Starts from mainline's base values (13s/10s) and adjusts
     * marginally in either direction based on success rate.
     *
     * Adaptive ranges:
     * REQUEST_TIMEOUT:   13s base, 10-18s range
     * FIRST_HOP_TIMEOUT: 10s base, 8-15s range
     *
     * Also adjusts concurrent build capacity based on timeout rate
     * to prevent overwhelming the IB reply path.
     *
     * @since 0.9.71+ adaptive concurrency throttle added
     */
    private void calculateAdaptiveTimeoutFromSuccess() {
        int count = Math.min(_windowWriteIndex.get(), WINDOW_SIZE);
        if (count < 10) { return; }

        int successes = 0, timeouts = 0;
        for (int i = 0; i < count; i++) {
            byte r = _buildResults[i];
            if (r == Result.SUCCESS.ordinal()) successes++;
            else if (r == Result.TIMEOUT.ordinal()) timeouts++;
        }
        double successRate = (double) successes / count;
        double timeoutRate = (double) timeouts / count;
        _timeoutRate = timeoutRate;

        // Base timeout from mainline (13s normal, 15s slow)
        long baseTimeout = BuildRequestor.getRequestTimeout(_context);

        // Start at base, then adjust marginally based on success rate.
        // Under high timeout rates (>30%), give builds more time to complete
        // instead of timing them out prematurely, which wastes the build slot.
        _adaptiveTimeout = baseTimeout;

        if (successRate > 0.85) {
            // High success — network is fast. Reduce slightly.
            _adaptiveTimeout += -3 * 1000L;  // -3s
        } else if (successRate > 0.70) {
            // Good success — keep near base.
            _adaptiveTimeout += 0;
        } else if (successRate > 0.50) {
            // Moderate success — modest increase.
            _adaptiveTimeout += 2 * 1000L;  // +2s
        } else if (timeoutRate > CONCURRENCY_THROTTLE_THRESHOLD) {
            // High timeout rate — increase timeout significantly to reduce
            // spurious timeouts that waste build slots and drive the
            // cascade further.  The concurrency throttle (below) handles
            // the root cause (too many concurrent builds); this reduces
            // the symptom (premature timeout expiry).
            _adaptiveTimeout += 7 * 1000L;  // +7s
        } else {
            // Low success — increase to give slow builds more time.
            _adaptiveTimeout += 5 * 1000L;  // +5s
        }

        // Clamp: never below 10s regardless of rate; allow adaptive increase up to 30s
        if (_adaptiveTimeout < 10*1000L) { _adaptiveTimeout = 10*1000L; }
        if (_adaptiveTimeout > 30*1000L) { _adaptiveTimeout = 30*1000L; }

        // Adaptive concurrency throttle: reduce max concurrent builds when
        // timeout rate is high to prevent overwhelming the IB reply path.
        // The root cause of high timeout rates is IB tunnel congestion from
        // too many concurrent build replies.  Reducing concurrency improves
        // per-build success rate at the cost of slower aggregate build speed.
        int baseMax = getMaxConcurrentBuilds();
        if (timeoutRate > CONCURRENCY_THROTTLE_THRESHOLD) {
            // Throttle: reduce by 20% per threshold crossing (floored at 60% of base).
            // Softer step (was 75%/50%) to avoid over-throttling on transient spikes.
            int throttled = (int) (baseMax * 0.80);
            throttled = Math.max(throttled, (int) (baseMax * 0.60));
            if (_adaptiveMaxConcurrentBuilds > throttled) {
                _adaptiveMaxConcurrentBuilds = throttled;
            }
        } else if (timeoutRate < CONCURRENCY_RESTORE_THRESHOLD &&
                   successRate > CONCURRENCY_RESTORE_SUCCESS_THRESHOLD) {
            // Restore: increase by 25% toward base (never exceed base).
            // Faster recovery (was 10%) to avoid prolonged throttling after
            // a transient spike subsides.
            int restored = _adaptiveMaxConcurrentBuilds + Math.max(1, baseMax / 4);
            _adaptiveMaxConcurrentBuilds = Math.min(restored, baseMax);
        }

        // Also calculate adaptive first-hop timeout based on first-hop success rate
        int firstHopTotal = _firstHopSuccessCount.get() + _firstHopFailureCount.get();
        if (firstHopTotal >= 10) {
            double firstHopSuccessRate = (double) _firstHopSuccessCount.get() / firstHopTotal;

            // Base first-hop timeout from mainline (10s)
            long baseFirstHop = BuildRequestor.getFirstHopTimeout(_context);
            _adaptiveFirstHopTimeout = baseFirstHop;

            if (firstHopSuccessRate > 0.85) {
                _adaptiveFirstHopTimeout += -2 * 1000L;
            } else if (firstHopSuccessRate > 0.70) {
                _adaptiveFirstHopTimeout += 0;
            } else if (firstHopSuccessRate > 0.50) {
                _adaptiveFirstHopTimeout += 2 * 1000L;
            } else {
                _adaptiveFirstHopTimeout += 3 * 1000L;
            }

            // Clamp: 8-15s
            if (_adaptiveFirstHopTimeout < 8*1000L) { _adaptiveFirstHopTimeout = 8*1000L; }
            if (_adaptiveFirstHopTimeout > 15*1000L) { _adaptiveFirstHopTimeout = 15*1000L; }

            if (_log.shouldDebug()) {
                _log.debug("Adaptive first-hop timeout: " + (_adaptiveFirstHopTimeout / 1000) +
                           "s (success: " + (int)(firstHopSuccessRate * 100) +
                           "%, failures: " + _firstHopFailureCount.get() + "/" + firstHopTotal + ")");
            }
        }

        if (_log.shouldDebug()) {
            _log.debug("Adaptive timeout: " + (_adaptiveTimeout / 1000) +
                       "s (success: " + (int)(successRate * 100) +
                       "%, timeouts: " + timeouts +
                       "/" + count + ", concurrency: " + _adaptiveMaxConcurrentBuilds + "/" + baseMax + ")");
        }
    }

    /**
     * Calculate adaptive timeout based on tunnel characteristics and network conditions
     *
     * @param cfg the tunnel configuration
     * @return adaptive timeout in milliseconds
     */
    private long calculateAdaptiveTimeout(PooledTunnelCreatorConfig cfg) {
        long baseTimeout = _adaptiveTimeout;

        // Adjust timeout based on tunnel length
        int length = cfg.getLength();
        if (length > 3) {
            baseTimeout += (long) (length - 3) * 5*1000L;
        }

        // Adjust based on system load
        int cpuLoad = SystemVersion.getCPULoadAvg();
        if (cpuLoad > 90) {
            baseTimeout += 3*1000;
        } else if (cpuLoad > 80) {
            baseTimeout += 2*1000;
        }

        // Outbound builds have a longer reply path: the build reply comes back
        // through an IB exploratory tunnel.  If exploratory tunnels are congested
        // (2 tunnels handling 18+ concurrent build replies), OB builds timeout
        // at 2x the rate of IB builds (54% vs 80% success).  The SSU2 handshake
        // alone takes ~8.5s, so +5s was insufficient when the IB reply path is
        // also under load.  Raised to 8s to cover handshake + moderate queue.
        if (!cfg.isInbound()) {
            baseTimeout += 8 * 1000L;
        }

        // Feedforward from measured network RTT: when the baseline round-trip
        // time (udp.sendConfirmTime) is high, builds that would otherwise succeed
        // are timed out prematurely.  Ensure the timeout covers the recent RTT
        // plus a margin so a latency spike does not cause spurious build timeouts
        // (which starves tunnel building and collapses the participating count).
        long rttFloor = getRttTimeoutFloor();
        if (rttFloor > baseTimeout) {
            baseTimeout = rttFloor;
        }

        // Cap at 45s safety ceiling
        return Math.min(baseTimeout, 45*1000L);
    }

    /**
     *  Feedforward timeout floor derived from the network's recent baseline RTT
     *  (udp.sendConfirmTime, the time to send a message and receive its ACK).
     *  Returns a timeout floor of recent RTT plus a fixed margin, so build
     *  timeouts track actual network conditions instead of a fixed ceiling.
     *
     *  @return timeout floor in ms
     *  @since 0.9.70+
     */
    private long getRttTimeoutFloor() {
        long margin = 10 * 1000L;
        RateStat rtt = _context.statManager().getRate("udp.sendConfirmTime");
        if (rtt != null) {
            Rate r = rtt.getRate(60*1000L);
            if (r != null && r.getLastEventCount() > 0) {
                // Use the average RTT as a conservative baseline (the p95 is not
                // directly exposed; the average already captures sustained spikes).
                long baseline = (long) r.getAverageValue();
                if (baseline > 0) {
                    return baseline + margin;
                }
            }
        }
        return 0;
    }

    /**
     * Determines allowed number of concurrent tunnel builds based on system status,
     * bandwidth limits, build times, system resources, and current tunnel build activity.
     * Also handles expiration and cleanup of old build requests.
     *
     * @return allowed number of concurrent tunnel builds
     */
    private int allowed() {
        final CommSystemFacade csf = _context.commSystem();
        if (csf.getStatus() == Status.DISCONNECTED) {
            if (_log.shouldInfo()) {
                _log.info("allowed() returning 0: DISCONNECTED status, building=" + _currentlyBuildingMap.size());
            }
            return 0;
        }
        if (csf.isDummy() && csf.countActivePeers() <= 0) {
            if (_log.shouldInfo()) {
                _log.info("allowed() returning 0: dummy status with 0 active peers, building=" + _currentlyBuildingMap.size());
            }
            return 0;
        }

        // Cache repeated system version calls and constants
        final boolean isSlow = SystemVersion.isSlow();
        final long now = _context.clock().now();

        final int maxKBps = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        // Conservative build concurrency: builds are bandwidth-intensive and
        // each build sends through 2-3 hops.  Old formula (maxKBps / 2) allowed
        // 41 concurrent builds for 83KBps — overwhelming peers and causing 33%
        // build expires.  New formula limits to ~10 concurrent builds, forcing
        // higher quality per build instead of flooding the network.
        int allowed = Math.max(maxKBps / 8, 6);

        final RateStat rs = _context.statManager().getRate("tunnel.buildRequestTime");
        double avg = -1;
        if (rs != null) {
            Rate r = rs.getRate(RateConstants.ONE_MINUTE);
            if (r != null) {
                avg = r.getAverageValue();
            } else if (rs.getLifetimeAverageValue() > 0) {
                avg = rs.getLifetimeAverageValue();
            }
        }

        int maxConcurrentBuilds = getAdaptiveMaxConcurrentBuilds();
        // Use base (unthrottled) max for the throughput calculation so the
        // adaptive throttle doesn't compound with the bandwidth constraint.
        // Without this, throttling adaptiveMax from 32→25 also reduces
        // throughput, creating AND-stacking: bandwidth(10) AND adaptive(25).
        int baseMax = getMaxConcurrentBuilds();

        if (avg > 0) {
            int throttleFactor = isSlow ? 100 : 160;
            int throughput = (int)(throttleFactor * baseMax / avg);
            // Don't let throughput boost override the conservative base by more than 2x
            if (throughput > allowed * 2) {
                throughput = allowed * 2;
            }
            if (throughput > allowed) {
                allowed = throughput; // Modest boost when builds are fast
            } else if (throughput < allowed) {
                allowed = throughput; // Throttle when builds are slow
            }
        }

        // Cap: never exceed maxConcurrentBuilds
        if (allowed > maxConcurrentBuilds) {
            allowed = maxConcurrentBuilds;
        }

        // Allow override through property
        allowed = _context.getProperty("router.tunnelConcurrentBuilds", allowed);

        // Constants for expiration calculations
        final long TEN_MINUTES_MS = 10 * 60 * 1000L;
        final long expireRecentlyBefore = now + TEN_MINUTES_MS - BuildRequestor.getRequestTimeout(_context) + getGracePeriod(_context);

        // Expire really old build requests from recentlyBuilding map
        for (Iterator<PooledTunnelCreatorConfig> iter = _recentlyBuildingMap.values().iterator(); iter.hasNext(); ) {
            PooledTunnelCreatorConfig cfg = iter.next();
            if (cfg.getExpiration() <= expireRecentlyBefore) {
                iter.remove();
            }
        }

        List<PooledTunnelCreatorConfig> expired = null;
        Map<Long, Long> expiredTimeouts = null;

        /* Expire old build requests from currentlyBuilding map, move them to recentlyBuilding
         * NOTE: cfg.getExpiration() includes TUNNEL stagger (0-300s), so comparing against it
         * directly would make timeouts take 20-320s.
         * Use creation time + adaptiveTimeout for consistent 20s timeout regardless of stagger.
         */
        for (Iterator<PooledTunnelCreatorConfig> iter = _currentlyBuildingMap.values().iterator(); iter.hasNext(); ) {
            PooledTunnelCreatorConfig cfg = iter.next();
            long adaptiveTimeout = calculateAdaptiveTimeout(cfg);
            long created = cfg.getConfig(0).getCreation();
            if (created > 0 && now - created >= adaptiveTimeout) {
                PooledTunnelCreatorConfig existingCfg = _recentlyBuildingMap.putIfAbsent(Long.valueOf(cfg.getReplyMessageId()), cfg);
                if (existingCfg == null) {
                    iter.remove();
                    if (expired == null) {
                        expired = new ArrayList<>();
                    }
                    if (expiredTimeouts == null) {
                        expiredTimeouts = new HashMap<>(8);
                    }
                    expired.add(cfg);
                    // Remember the timeout actually used: calculateAdaptiveTimeout()
                    // varies per config (length, load, outbound, RTT floor).
                    expiredTimeouts.put(cfg.getReplyMessageId(), adaptiveTimeout);
                }
            }
        }

        // DIAGNOSTIC: report what allowed() found in the building map
        int concurrent = _currentlyBuildingMap.size();
        if (_log.shouldInfo() && concurrent > 0) {
            long now2 = _context.clock().now();
            long oldestAge = now2 - getOldestBuildingCreation();
            int expiredCount = expired != null ? expired.size() : 0;
            _log.info("allowed() buildingMap=" + concurrent +
                      " expired=" + expiredCount +
                      " oldestAge=" + oldestAge + "ms" +
                      " adaptiveTimeout=" + _adaptiveTimeout + "ms");
        }
        allowed -= concurrent;

        if (expired != null) {
            for (PooledTunnelCreatorConfig cfg : expired) {
                if (_log.shouldInfo()) {
                    Long used = expiredTimeouts.get(cfg.getReplyMessageId());
                    long secs = used != null ? used / 1000 : _adaptiveTimeout / 1000;
                    _log.info("Timeout (" + secs + "s) waiting for tunnel build reply -> " + cfg);
                }

                penalizeTimeout(cfg);

                TunnelPool pool = cfg.getTunnelPool();
                if (pool != null) {
                    pool.buildComplete(cfg, Result.TIMEOUT);
                }
                // Update per-pool failure tracking so pool backoff engages on
                // consecutive timeouts (same as BuildExecutor.buildComplete()
                // does for BAD_RESPONSE/OTHER_FAILURE).
                if (pool != null) {
                    long[] state = getOrCreatePoolState(pool);
                    synchronized (state) {
                        if (state[0] < CONSECUTIVE_FAILURE_THRESHOLD) {
                            state[0]++;
                        }
                        if (state[0] >= CONSECUTIVE_FAILURE_THRESHOLD) {
                            state[1] = _context.clock().now() + jitteredBackoff();
                        }
                    }
                }
                updateBuildStats(Result.TIMEOUT);
                if (cfg.getDestination() == null) {
                    _context.statManager().addRateData("tunnel.buildExploratoryExpire", 1);
                } else {
                    _context.statManager().addRateData("tunnel.buildClientExpire", 1);
                }
            }
        }

        _context.statManager().addRateData("tunnel.concurrentBuilds", concurrent);

        return allowed;
    }

    /**
     *  Get the creation timestamp of the oldest entry in _currentlyBuildingMap.
     *  Used for diagnostic logging only.
     *
     *  @return creation time of oldest build, or 0 if map is empty
     *  @since 0.9.71+
     */
    private long getOldestBuildingCreation() {
        long oldest = Long.MAX_VALUE;
        for (PooledTunnelCreatorConfig cfg : _currentlyBuildingMap.values()) {
            long c = cfg.getConfig(0).getCreation();
            if (c > 0 && c < oldest) {
                oldest = c;
            }
        }
        return oldest == Long.MAX_VALUE ? 0 : oldest;
    }

    /**
     *  Penalize the peers of an expired build.  Only the hop the build
     *  request was dispatched to — the gateway (peer 0) for inbound, the
     *  next hop (peer 1) for outbound, per
     *  {@link BuildRequestor#getBuildRequestPeer(PooledTunnelCreatorConfig)} —
     *  failed to deliver a reply; the other hops may never have received
     *  the request, or may be waiting downstream of a silent peer.  Blaming
     *  them all shrinks the pool on innocent peers during network-wide
     *  no-reply events, so the profile penalty and ghost mark go to the
     *  contacted hop only.  The per-tier expire stat and the didNotReply
     *  debug log stay per-hop for accounting and triage.
     *
     *  Every non-self hop is additionally cooled down out of the immediate
     *  retry selection ({@link #cooldownFailedPeers(PooledTunnelCreatorConfig)}),
     *  since any of them may have been the silent one.
     *
     *  @param cfg the expired build config, non-null
     *  @since 0.9.71+
     */
    void penalizeTimeout(PooledTunnelCreatorConfig cfg) {
        if (cfg.getLength() <= 1) {return;}
        cooldownFailedPeers(cfg);
        final int length = cfg.getLength();
        final Hash contacted = BuildRequestor.getBuildRequestPeer(cfg);
        for (int iPeer = 0; iPeer < length; iPeer++) {
            Hash peer = cfg.getPeer(iPeer);
            if (peer.equals(_context.routerHash())) {
                continue; // Skip self
            }
            // Presence-only check: this is for blame logging, unvalidated lookup is sufficient
            DatabaseEntry de = _context.netDb().lookupLocallyWithoutValidation(peer);
            String bwTier = "Unknown";
            if (de != null && de.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                RouterInfo ri = (RouterInfo) de;
                bwTier = ri.getBandwidthTier();
            }
            _context.statManager().addRateData("tunnel.tierExpire" + bwTier, 1);
            if (peer.equals(contacted)) {
                didNotReply(cfg.getReplyMessageId(), peer);
                _context.profileManager().tunnelTimedOut(peer);
                // Record timeout for ghost peer detection
                if (_ghostPeerManager != null) {
                    _ghostPeerManager.recordTimeout(peer);
                }
                // Immediate tier demotion: peers that fail to respond to build
                // requests are removed from fast/high-cap tiers without waiting
                // for the slower data-phase demoteIfUnreachable strike path.
                // This prevents re-selection of unresponsive peers in subsequent
                // builds where ghost filtering may not yet be active (first
                // timeout) or the ghost cooldown has just expired.
                _context.profileOrganizer().demoteIfUnreachable(peer);
            }
        }
    }

    /**
     *  Cool down the contacted hop of a failed build so the immediate retry
     *  selects a different peer.  Only the hop the build request was dispatched
     *  to (the gateway for inbound, the next hop for outbound) is responsible
     *  for delivering the reply — cooling the other hops punishes innocent peers
     *  and saturates the cooldown map during network-wide no-reply events
     *  (observed 247/247 client hops in cooldown on a single-destination router).
     *  Selection-scope only — entries live for
     *  {@link TunnelPeerSelector#PEER_SELECTION_COOLDOWN_MS} and both peer
     *  selectors consult this map; profile penalties are separate and go to
     *  the contacted hop only via
     *  {@link #penalizeTimeout(PooledTunnelCreatorConfig)}.
     *
     *  @param cfg the failed build config
     *  @since 0.9.71+
     */
    void cooldownFailedPeers(PooledTunnelCreatorConfig cfg) {
        if (cfg == null || cfg.getLength() <= 1) {return;}
        Hash contacted = BuildRequestor.getBuildRequestPeer(cfg);
        if (contacted != null && !contacted.equals(_context.routerHash())) {
            TunnelPeerSelector._peerCooldowns.put(contacted, _context.clock().now());
        }
    }

    /**
     * Starts the tunnel building process in a loop and takes care of error handling.
     * Catches a specific fatal error related to Java versioning and ensures the
     * running state is properly reset on exit.
     */
    public void run() {
        _isRunning = true;
        try {
            run2();
        } catch (NoSuchMethodError nsme) {
            // Known fatal error scenario with Java 8 compiler and bootclasspath
            String s = "Fatal error:" +
                       "\nJava 8 compiler used with JRE version " + System.getProperty("java.version") +
                       " and no bootclasspath specified." +
                       "\nUpdate to Java 8 or contact packager." +
                       "\nStop I2P+ now, it will not build tunnels!";
            _log.log(Log.CRIT, s, nsme);
            throw nsme;
        } finally {
            _isRunning = false;
        }
    }

    /**
     * Main loop that manages tunnel pools and schedules tunnel builds depending on
     * system resource availability, load, and tunnel states. Handles expiration
     * and waiting for new build opportunities with appropriate backoff.
     */
    private void run2() {
        int maxConcurrentBuilds = getMaxConcurrentBuilds();
        List<TunnelPool> wanted = new ArrayList<>(maxConcurrentBuilds);
        List<TunnelPool> pools = new ArrayList<>(TUNNEL_POOLS);

        while (_isRunning && !_manager.isShutdown()) {
            try {
                _repoll = false;
                _manager.listPools(pools);

                // DIAGNOSTIC: confirm BldExecutor thread is alive each iteration
                if (_log.shouldDebug()) {
                    int building = _currentlyBuildingMap.size();
                    int recently = _recentlyBuildingMap.size();
                    long buildingAge = building > 0 ? _context.clock().now() - getOldestBuildingCreation() : 0;
                    _log.debug("BldExecutor loop tick: building=" + building +
                              " recently=" + recently +
                              " buildingOldestAge=" + buildingAge + "ms" +
                              " pools=" + pools.size());
                }

                // Proactive republish LeaseSets when all tunnels are healthy
                for (TunnelPool pool : pools) {
                    if (pool.isAlive()) {
                        try {
                            pool.proactiveRepublishIfHealthy();
                        } catch (RuntimeException e) {
                            _log.log(Log.WARN, "Error in proactiveRepublishIfHealthy for " + pool, e);
                        }
                    }
                }

                /* Periodic keepalive to maintain transport sessions with top-tier peers.
                 * Runs every ~30s (time-based; the loop itself runs every 15s, so a
                 * counter-based cadence would only fire every ~7.5 min).
                 * Always pre-connects to non-established eligible peers to warm
                 * connections before builds need them.
                 */
                long keepAliveNow = _context.clock().now();
                if (keepAliveNow - _lastKeepAliveTime >= KEEPALIVE_INTERVAL_MS) {
                    _lastKeepAliveTime = keepAliveNow;
                    TunnelPeerSelector.keepAlive(_context, true);
                }

                // Simplified paired replenishment algorithm
                // Target: max(4, wanted + 2) tunnels per direction with > 5 min expiry
                wanted.clear();
                calculatePairedBuilds(pools, wanted);

                // Determine how many tunnels are allowed to build concurrently
                int allowed = allowed(); // also expires timed out requests
                allowed = buildZeroHopTunnels(wanted, allowed); // zero-hop tunnels build inline
                // Cap per-iteration builds to prevent flooding the network.
                int perIterationCap = calculatePerIterationCap(_timeoutRate);
                if (allowed > perIterationCap) allowed = perIterationCap;

                // Transport congestion backpressure: when the send pipeline is
                // backed up (>2s processing time), reduce builds so data messages
                // aren't queued behind build messages.  Without this, our own
                // tunnel builds flood the transport while RouterThrottleImpl only
                // blocks participating requests from OTHER routers.
                RateStat sendRS = _context.statManager().getRate("transport.sendProcessingTime");
                if (sendRS != null) {
                    Rate sendRate = sendRS.getRate(RateConstants.ONE_MINUTE);
                    if (sendRate != null && sendRate.getAverageValue() > 2000) {
                        allowed = Math.min(allowed, 2);
                    }
                }

                TunnelManagerFacade mgr = _context.tunnelManager();

                int freeTunnelCount = mgr != null ? mgr.getFreeTunnelCount() : 0;
                int outboundTunnelCount = mgr != null ? mgr.getOutboundTunnelCount() : 0;
                boolean noInboundOrOutbound = (mgr == null) || (freeTunnelCount <= 0 && outboundTunnelCount <= 0);

                if (noInboundOrOutbound) {
                    if (_log.shouldDebug()) {
                        _log.debug("noInboundOrOutbound=true: freeTunnels=" + freeTunnelCount +
                                  " outboundTunnels=" + outboundTunnelCount +
                                  " mgr=" + (mgr != null) +
                                  " buildingMap=" + _currentlyBuildingMap.size() +
                                  " allowed=" + allowed + " wanted=" + wanted.size());
                    }
                    // Kickstart inbound/outbound tunnels if missing to avoid stall
                    if (mgr != null) {
                        if (mgr.getFreeTunnelCount() <= 0) {
                            mgr.selectInboundTunnel();
                        }
                        if (mgr.getOutboundTunnelCount() <= 0) {
                            mgr.selectOutboundTunnel();
                        }
                    }
                    synchronized (_currentlyBuilding) {
                        if (!_repoll) {
                            if (_log.shouldDebug()) {
                                _log.debug("No tunnel to build with (Allowed / Requested: " + allowed + " / " +
                                           wanted.size() + ") -> Waiting for a moment...");
                            }
                            try {
                                int noTunnelWait = 250 + _context.random().nextInt(250);
                                _currentlyBuilding.wait(noTunnelWait);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                } else {
                    if (_log.shouldDebug()) {
                        _log.debug("DISPATCH branch: allowed=" + allowed + " wanted=" + wanted.size() +
                                  " buildingMap=" + _currentlyBuildingMap.size());
                    }
                    if (allowed > 0 && !wanted.isEmpty()) {
                        // Snapshot scores before sorting to avoid TimSort crash from
                        // concurrent tunnel state changes during comparison (activeTunnelCount
                        // can change as TestJobs complete or tunnels expire mid-sort).
                        // Sort by dispatch priority: zero-hop emergency replacements first
                        // (they provide no anonymity and must cycle out ahead of ordinary
                        // demand), then collapsed pools, then near-collapse, then by deficit
                        // (largest first).  Ties are left to the stable sort so equal-priority
                        // pools keep their allocation order from calculatePairedBuilds()
                        // instead of an arbitrary identity-hash order.
                        // For paired destinations, prioritize the direction further behind its pair
                        int sz = wanted.size();
                        Object[][] scored = new Object[sz][3];
                        for (int si = 0; si < sz; si++) {
                            TunnelPool p = wanted.get(si);
                            scored[si][0] = score(p);
                            scored[si][1] = p;
                            scored[si][2] = isZeroHopEmergency(p);
                        }
                        Arrays.sort(scored, DISPATCH_COMPARATOR);
                        for (int si = 0; si < sz; si++) {
                            wanted.set(si, (TunnelPool) scored[si][1]);
                        }

                        // Per-pool, per-direction in-flight cap: removes pools
                        // that already have MAX_PER_POOL_DIR builds in flight
                        // for a given direction (inbound or outbound).  Derived
                        // from _currentlyBuildingMap so there's no second map to
                        // keep in sync across all completion paths.
                        if (!_currentlyBuildingMap.isEmpty()) {
                            Map<TunnelPool, int[]> inflight = null;
                            for (PooledTunnelCreatorConfig bld : _currentlyBuildingMap.values()) {
                                TunnelPool p = bld.getTunnelPool();
                                if (p == null) continue;
                                if (inflight == null) inflight = new HashMap<>(4);
                                boolean in = p.getSettings().isInbound();
                                int[] dir = inflight.computeIfAbsent(p, k -> new int[2]);
                                dir[in ? 0 : 1]++;
                            }
                            if (inflight != null) {
                                Iterator<TunnelPool> wit = wanted.iterator();
                                while (wit.hasNext()) {
                                    TunnelPool pool = wit.next();
                                    int[] dir = inflight.get(pool);
                                    if (dir != null) {
                                        boolean in = pool.getSettings().isInbound();
                                        if ((in ? dir[0] : dir[1]) >= MAX_PER_POOL_DIR) {
                                            wit.remove();
                                        }
                                    }
                                }
                            }
                        }

                        for (int i = 0; i < allowed && !wanted.isEmpty(); i++) {
                            TunnelPool pool = wanted.remove(0);
                            // Throttle peer-selection hot path (see _lastConfigureTime).
                            // Only when at least 2 builds are already in flight for
                            // this pool: one pending build must not block the next
                            // configure for an incomplete-LeaseSet pool.
                            long nowCfg = System.currentTimeMillis();
                            Long lastCfg = _lastConfigureTime.get(pool);
                            if (lastCfg != null && nowCfg - lastCfg < CONFIGURE_THROTTLE_MS
                                && pool.getInProgressCount() >= 2) {
                                if (_log.shouldDebug()) {
                                    _log.debug("Throttling configureNewTunnel for " + pool +
                                               " (" + (nowCfg - lastCfg) + "ms since last, " +
                                               pool.getInProgressCount() + " in progress)");
                                }
                                continue;
                            }
                            _lastConfigureTime.put(pool, nowCfg);

                            long bef = System.currentTimeMillis();
                            PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                            if (cfg != null) {
                                if (cfg.getLength() <= 1 && !pool.needFallback()) {
                                    if (_log.shouldDebug()) {
                                        _log.debug("We don't need more fallbacks for " + pool);
                                    }
                                    i--;
                                    // Local policy skip, not a build failure:
                                    // must not feed the pool-backoff counter.
                                    pool.buildComplete(cfg, Result.SKIPPED);
                                    continue;
                                }
                                if (_log.shouldDebug()) {
                                    _log.debug("Configuring new tunnel [" + i + "] for " + pool);
                                }
                                buildTunnel(cfg);
                            }
                            // When cfg is null (fastFailTbrTarget pre-connecting),
                            // don't decrement i — the pool was removed from wanted
                            // and will be retried on the next 15s cycle.
                        }

                        /* Cancel excess in-progress builds to stay within budget.
                         * Only count building (in-progress) tunnels, not testing tunnels —
                         * they're different pipeline stages. Testing tunnels are built and
                         * being evaluated; building tunnels are still in construction.
                         * Uses the effective target (wantedCount + Tuner's targetBuffer)
                         * so cancellation doesn't override quality-driven build demand.
                         * When the Tuner raises targetBuffer to compensate for poor quality,
                         * the cancellation threshold rises accordingly — matching what
                         * calculatePairedBuilds() uses.
                         */
                        for (TunnelPool pool : pools) {
                            if (!pool.isAlive()) {
                                continue;
                            }
                            int buildingCount = pool.getInProgressCount();
                            // Zero-hop pools keep their configured quantity; the
                            // 2-tunnel floor ensures a single failure can't
                            // starve the pool.
                            int target = effectiveTarget(_context, pool.getSettings(),
                                                         pool.getSettings().isZeroHop());
                            int maxBuilding = Math.max(4, target * 2);
                            if (buildingCount > maxBuilding) {
                                for (PooledTunnelCreatorConfig cfg : pool.cancelExcessInProgress(maxBuilding)) {
                                    removeFromBuilding(cfg.getReplyMessageId());
                                }
                            }
                        }
                    }

                    synchronized (_currentlyBuilding) {
                        if (!_repoll) {
                            int delay = LOOP_TIME;
                            try {
                                _currentlyBuilding.wait(delay);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }

                    /* Build-pass spacing: cap how often the loop re-passes.
                     * buildComplete notifyAll's the loop on every completion
                     * (buildTime > 250ms), so without this floor a cascade
                     * cycles near-continuously instead of batching builds.
                     * The floor applies even when repolled (tunnel failed):
                     * state changes still gate, just not faster than the
                     * spacing allows.  Sleep outside the monitor.
                     */
                    long spacingLeft = spacingDelay(_lastBuildPassTime, System.currentTimeMillis());
                    if (spacingLeft > 0) {
                        try {
                            Thread.sleep(spacingLeft);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    _lastBuildPassTime = System.currentTimeMillis();
                }
            } catch (RuntimeException e) {
                _log.log(Log.CRIT, "Catastrophic Tunnel Manager failure", e);
                try {
                    Thread.sleep(LOOP_TIME);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            wanted.clear();
            pools.clear();
        }

        if (_log.shouldInfo()) {
            _log.info("Done building");
        }
    }

    /**
     * Iterate over the 0hop tunnels, running them all inline regardless of how many are allowed
     *
     * @return number of tunnels allowed after processing these zero hop tunnels (almost always the same as before)
     */
    private int buildZeroHopTunnels(List<TunnelPool> wanted, int allowed) {
        for (Iterator<TunnelPool> iter = wanted.iterator(); iter.hasNext(); ) {
            TunnelPool pool = iter.next();
            if (pool.getSettings().getLength() == 0) {
                PooledTunnelCreatorConfig cfg = pool.configureNewTunnel();
                if (cfg != null) {
                    if (_log.shouldDebug()) {
                        _log.debug("Configuring short tunnel for " + pool + " -> " + cfg);
                    }
                    buildTunnel(cfg);
                    if (cfg.getLength() > 1) {allowed--;} // oops... shouldn't have done that, but hey, it's not that bad...
                    iter.remove();
                } else {
                    if (_log.shouldDebug()) {_log.debug("Configured a NULL tunnel!");}
                }
            }
        }
        return allowed;
    }

    /**
     * Check if the executor is currently running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {return _isRunning;}

    /**
     *  Check if a pool is in backoff due to consecutive build failures.
     *  Uses a jittered backoff window (8-16s, centered on {@link #POOL_BACKOFF_MS})
     *  to prevent synchronized backoff where all pools are skipped in the same
     *  cycle.  During collapse (0 usable tunnels), backoff is skipped entirely
     *  so collapsed pools get rebuilt immediately instead of waiting.
     *  On backoff expiry, resets the failure counter so the pool gets a
     *  fresh window of attempts.
     *
     *  @param pool the tunnel pool to check
     *  @return true if the pool is in backoff
     */
    boolean isPoolInBackoff(TunnelPool pool) {
        long[] state = _poolFailureState.get(pool);
        if (state == null) return false;
        long backoffUntil = state[1];
        if (backoffUntil > 0 && _context.clock().now() < backoffUntil) {
            // During collapse (0 usable tunnels), skip backoff entirely
            // so collapsed pools get rebuilt immediately.  Without this,
            // all pools enter backoff simultaneously after a cascade,
            // causing synchronized starvation.
            if (pool.getUsableTunnelCount() == 0) {
                synchronized (state) {
                    state[0] = 0;
                    state[1] = 0;
                }
                return false;
            }
            return true;
        }
        // Backoff period expired — reset the counter so the pool gets a fresh
        // window of attempts.  Without this, the counter grows unboundedly
        // (timeout handler increments but success never resets because
        // pool.buildComplete(TIMEOUT) already removed the build config,
        // so BuildExecutor.buildComplete(SUCCESS) is never called).
        // synchronized(state) ensures the check-and-reset is atomic —
        // another thread cannot increment state[0] between the outer
        // backoffUntil > 0 read and the reset.
        synchronized (state) {
            if (state[1] > 0) {
                state[0] = 0;
                state[1] = 0;
            }
        }
        return false;
    }

    /**
     * Build a tunnel with the given configuration.
     * Burst builds to different peers freely, but never start a second
     * build request to a first-hop peer that already has a build in flight —
     * stacking requests on one peer floods it and makes the first build
     * answer slower.  Zero-hop tunnels build inline, unguarded.
     *
     * Early ban filtering: checks if the first-hop is banned before
     * dispatching the build, preventing wasted build slots on peers
     * that will reject the request.  Saves 12-18 build slots per minute.
     *
     * Stale build pruning: skips builds that would timeout before being
     * dispatched (queue wait + expected build time > timeout budget),
     * reducing wasted builds that would timeout anyway.
     *
     * @param cfg the tunnel configuration to build
     */
    void buildTunnel(PooledTunnelCreatorConfig cfg) {
        if (_log.shouldDebug()) {
            _log.debug("buildTunnel() entry: " + cfg + " buildingMapSize=" + _currentlyBuildingMap.size());
        }
        if (cfg.getLength() > 1 && !cfg.isBypassPacing() && hasBuildInFlightToFirstHop(cfg)) {
            _context.statManager().addRateData("tunnel.buildPacedOut", 1);
            if (_log.shouldDebug()) {
                _log.debug("buildTunnel() GATED (pacing): first hop already in flight for " + cfg);
            }
            // Never sent to the network, so no timeout will fire — remove it
            // now or the pool's _inProgress count leaks upward forever,
            // starving every cap-guard that gates on in-progress builds.
            cfg.getTunnelPool().removeInProgress(cfg);
            return;
        }

        // Early ban filtering: check if any hop is banned before dispatching.
        // BuildHandler emits buildBanHit when it receives a request for a banned
        // peer, but by then the build slot is wasted.  Checking all hops here
        // (not just the first) saves the slot for a build that could succeed —
        // a banned middle hop causes "Next peer is banned" drops at request time.
        if (cfg.getLength() > 1) {
            Banlist banlist = _context.banlist();
            for (int hop = 0; banlist != null && hop < cfg.getLength(); hop++) {
                Hash peer = cfg.getPeer(hop);
                if (peer != null && banlist.isBanlisted(peer)) {
                    if (_log.shouldDebug()) {
                        _log.debug("buildTunnel() GATED (ban): hop " + hop +
                                   " [" + peer.toBase64().substring(0, 6) +
                                   "] is banned for " + cfg);
                    }
                    _context.statManager().addRateData("tunnel.buildBanFiltered", 1);
                    cfg.getTunnelPool().removeInProgress(cfg);
                    return;
                }
            }
        }

        // Stale build pruning: skip builds that would timeout before being
        // dispatched.  The build's timeout budget is the adaptive timeout;
        // if the build has already consumed more than half of that budget
        // sitting in the queue, it will almost certainly timeout on the wire.
        if (cfg.getLength() > 1) {
            long created = cfg.getConfig(0).getCreation();
            if (created > 0) {
                long elapsed = _context.clock().now() - created;
                long timeoutBudget = calculateAdaptiveTimeout(cfg);
                // Prune if we've used >threshold% of the timeout budget before dispatch
                if (elapsed > (timeoutBudget * STALE_BUILD_THRESHOLD_PCT / 100)) {
                    if (_log.shouldDebug()) {
                        _log.debug("buildTunnel() GATED (stale): elapsed " + (elapsed / 1000) + "s of " +
                                  (timeoutBudget / 1000) + "s budget for " + cfg);
                    }
                    _context.statManager().addRateData("tunnel.buildStalePruned", 1);
                    cfg.getTunnelPool().removeInProgress(cfg);
                    return;
                }
            }
        }

        long beforeBuild = System.currentTimeMillis();
        if (cfg.getLength() > 1) {
            do {cfg.setReplyMessageId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));} // should we allow an ID of 0?
            while (addToBuilding(cfg)); // if a dup, go araound again
            if (_log.shouldDebug()) {
                _log.debug("buildTunnel() dispatched (addToBuilding ok): replyId=" + cfg.getReplyMessageId() + " for " + cfg);
            }
        }
        boolean ok = BuildRequestor.request(_context, cfg, this, _adaptiveFirstHopTimeout);
        if (!ok) {
            if (_log.shouldDebug()) {
                _log.debug("buildTunnel() BuildRequestor.request() returned false for " + cfg);
            }
            return;
        }
        if (cfg.getLength() > 1) {
            long buildTime = System.currentTimeMillis() - beforeBuild;
            _context.statManager().addRateData("tunnel.buildRequestTime", buildTime);
        }
        long id = cfg.getReplyMessageId();
        if (id > 0) {
            synchronized (_recentBuildIds) {
                _recentBuildIds.add(id);
                // LinkedHashSet keeps insertion order: trim the oldest, not
                // everything, so wasRecentlyBuilding() still detects late
                // duplicate replies.
                trimFifo(_recentBuildIds, 128);
            }
        }
    }

    /**
     *  Whether another build request is already in flight whose first hop
     *  (the peer the request is dispatched to) matches this config's.
     *  Prevents stacking multiple build requests onto a single peer while
     *  allowing bursts to different peers.  Emergency builds bypass this via
     *  {@link PooledTunnelCreatorConfig#isBypassPacing()}.
     *
     *  @param cfg the prospective build
     *  @return true if a build to the same first hop is already in flight
     */
    /**
     *  Prevent stacking multiple build requests onto a single peer while
     *  allowing bursts to different peers.  Emergency builds bypass this via
     *  {@link PooledTunnelCreatorConfig#isBypassPacing()}.
     *
     *  Also skips peers that have recently failed as first hops more than
     *  {@link #FIRST_HOP_FAILURE_THRESHOLD} times within the cooldown window.
     *
     *  @param cfg the prospective build
     *  @return true if a build to the same first hop is already in flight
     *         or the peer has recently failed repeatedly as first hop
     */
    private boolean hasBuildInFlightToFirstHop(PooledTunnelCreatorConfig cfg) {
        Hash firstHop = BuildRequestor.getBuildRequestPeer(cfg);
        if (firstHop == null) {return false;}
        if (hasRecentlyFailedAsFirstHop(firstHop)) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping build for " + cfg +
                           " — first hop [" + firstHop.toBase64().substring(0, 6) +
                           "] has " + getFirstHopFailureCount(firstHop) + " recent failures");
            }
            return true;
        }
        for (PooledTunnelCreatorConfig inFlight : _currentlyBuildingMap.values()) {
            if (inFlight == cfg) {continue;}
            Hash otherFirstHop = BuildRequestor.getBuildRequestPeer(inFlight);
            if (firstHop.equals(otherFirstHop)) {return true;}
        }
        return false;
    }

    /**
     *  Get the recent first-hop failure count for a peer (for logging).
     *
     *  @param hash the peer hash
     *  @return the failure count, or 0 if no history
     *  @since 0.9.71+
     */
    private int getFirstHopFailureCount(Hash hash) {
        long[] state = _firstHopFailureHistory.get(hash);
        if (state == null) {return 0;}
        synchronized (state) {
            return (int) state[0];
        }
    }

    /**
     *  Record a first-hop failure for a given peer hash.  Called from
     *  {@link #buildComplete} when a build fails due to first-hop issues.
     *  Uses exponential decay: old failure counts decay over time, so a
     *  peer that failed once 10 minutes ago but succeeded since is not
     *  penalized.
     *
     *  @param hash the first-hop peer identity
     *  @since 0.9.71+
     */
    private void recordFirstHopFailure(Hash hash) {
        if (hash == null) {return;}
        long now = _context.clock().now();
        long[] state = _firstHopFailureHistory.computeIfAbsent(hash, k -> new long[2]);
        synchronized (state) {
            // Decay: if last failure was more than cooldown ago, reset count
            if (state[1] > 0 && (now - state[1]) > FIRST_HOP_FAILURE_COOLDOWN_MS) {
                state[0] = 0;
            }
            state[0]++;
            state[1] = now;
        }
    }

    /**
     *  Check whether a peer has recently failed as a first hop more than
     *  {@link #FIRST_HOP_FAILURE_THRESHOLD} times within the cooldown window.
     *  Used by {@link #hasBuildInFlightToFirstHop} to skip peers with a
     *  pattern of repeated first-hop failures.
     *
     *  @param hash the first-hop peer identity
     *  @return true if the peer has exceeded the failure threshold
     *  @since 0.9.71+
     */
    private boolean hasRecentlyFailedAsFirstHop(Hash hash) {
        if (hash == null) {return false;}
        long[] state = _firstHopFailureHistory.get(hash);
        if (state == null) {return false;}
        synchronized (state) {
            if (state[1] == 0) {return false;}
            long now = _context.clock().now();
            if ((now - state[1]) > FIRST_HOP_FAILURE_COOLDOWN_MS) {
                // Expired: prune entry to prevent memory leak
                _firstHopFailureHistory.remove(hash);
                return false;
            }
            return state[0] >= FIRST_HOP_FAILURE_THRESHOLD;
        }
    }

    /**
     * Handle a completed tunnel build.
     *
     * @param cfg the tunnel configuration to build
     * @param result the build result (success, failure, etc.)
     * @since 0.9.53 added result parameter
     */
    public void buildComplete(PooledTunnelCreatorConfig cfg, Result result) {
        buildComplete(cfg, result, null);
    }

    /**
     *  Handle a completed tunnel build with additional detail.
     *
     *  @param cfg the tunnel configuration that completed
     *  @param result the build result (success, failure, etc.)
     *  @param detail additional detail on the result
     *  @since 0.9.53
     */
    public void buildComplete(PooledTunnelCreatorConfig cfg, Result result, String detail) {
        if (_log.shouldInfo()) {
            if ((result == Result.OTHER_FAILURE || result == Result.NO_TUNNELS ||
                 result == Result.NO_NETDB) && detail != null) {
                _log.info("Build failed -> " + detail + " for " + cfg);
            } else {
                _log.info("Build complete (" + result + ") for " + cfg);
            }
        }
        cfg.getTunnelPool().buildComplete(cfg, result);
        if (cfg.getLength() > 1) {removeFromBuilding(cfg.getReplyMessageId());}
        long now = _context.clock().now();
        long buildTime = now - cfg.getConfig(0).getCreation();
        TunnelPool pool = cfg.getTunnelPool();

        /* Per-pool consecutive failure tracking for backoff.
         * On SUCCESS: reset the counter so future failures start fresh.
         * On failure: increment counter; if threshold exceeded, set backoff
         * timestamp so calculatePairedBuilds() skips this pool temporarily.
         * REJECT is excluded — a peer that responds "no" (overloaded, no
         * capacity) is fundamentally different from a peer that doesn't
         * respond at all (TIMEOUT).  Backoff doesn't fix capacity issues
         * and prevents builds for pools that could succeed with a different
         * peer selection.
         * NO_TUNNELS is excluded too — no paired tunnel is a local resource
         * condition, not a peer failure; counting it would push healthy
         * pools into backoff during cascades.
         */
        if (result == Result.SUCCESS) {
            _poolFailureState.remove(pool);
        } else if (countsAsPoolFailure(result)) {
            long[] state = getOrCreatePoolState(pool);
            synchronized (state) {
                if (state[0] < CONSECUTIVE_FAILURE_THRESHOLD) {
                    state[0]++;
                }
                if (state[0] >= CONSECUTIVE_FAILURE_THRESHOLD) {
                    state[1] = _context.clock().now() + jitteredBackoff();
                    if (_log.shouldDebug()) {
                        _log.debug("Pool backoff engaged after " + (int) state[0] +
                                   " consecutive failures for " + pool);
                    }
                }
            }
        }

        /* Track first-hop success/failure.
         * OTHER_FAILURE with buildTime >= 1000 means the build message couldn't
         * be delivered to the first hop (TunnelBuildFirstHopFailJob fires after
         * ~10s).  Count these as first-hop failures so the adaptive first-hop
         * timeout and pool quantity reduction can respond.
         */
        boolean firstHopFailure = (result == Result.OTHER_FAILURE && buildTime >= 1000);
        if (firstHopFailure) {
            if (pool != null)
                pool.incrementBuildTimeout();
            // Record first-hop failure for skip-if-recently-failed logic
            Hash firstHop = BuildRequestor.getBuildRequestPeer(cfg);
            recordFirstHopFailure(firstHop);
        }
        // Also record TIMEOUT and BAD_RESPONSE as first-hop failures
        // since they indicate the selected first hop could not deliver
        if (result == Result.TIMEOUT || result == Result.BAD_RESPONSE) {
            Hash firstHop = BuildRequestor.getBuildRequestPeer(cfg);
            recordFirstHopFailure(firstHop);
        }

        /* Exclude non-latency failures from adaptive timeout stats.
         * - Immediate OTHER_FAILURE (< 50ms): no-paired-tunnel or send errors
         * - First-hop failures (OTHER_FAILURE, buildTime >= 1000): unreachable peers
         *   via TunnelBuildFirstHopFailJob
         * - TIMEOUT results: the build waited the full adaptive timeout with no
         *   reply.  Including TIMEOUTs in the adaptive calculation ensures
         *   the timeout doesn't decrease below what the network can actually
         *   support.  Excluding them inflates the apparent success rate,
         *   driving the timeout DOWN and causing MORE timeouts (positive
         *   feedback loop).  The adaptive clamp (10-25s) prevents TIMEOUTs
         *   from inflating the timeout beyond reason.
         */
        if (result == Result.SUCCESS ||
            (buildTime >= 50 && !firstHopFailure)) {
            updateBuildStats(result);
        }
        if (result == Result.SUCCESS) {
            _firstHopSuccessCount.incrementAndGet();
        } else if (result == Result.TIMEOUT || result == Result.BAD_RESPONSE || firstHopFailure) {
            _firstHopFailureCount.incrementAndGet();
        }

        /* Only wake up the build thread if it took a reasonable amount of time -
         * this prevents high CPU usage when there is no network connection
         * (via BuildRequestor.TunnelBuildFirstHopFailJob)
         */
        if (buildTime > 250) {
            synchronized (_currentlyBuilding) {_currentlyBuilding.notifyAll();}
        } else if (cfg.getLength() > 1 && _log.shouldInfo() && buildTime < 50) {
            _log.info("Build completed fast (" + buildTime + "ms) -> " + cfg);
        }
        long expireBefore = now + 10*60*1000L - BuildRequestor.getRequestTimeout(_context);
        if (cfg.getExpiration() <= expireBefore && _log.shouldDebug()) {
            _log.debug("Build completed for expired tunnel -> " + cfg);
        }
        if (result == Result.SUCCESS) {
            _manager.buildComplete(cfg);
            ExpireJob.scheduleExpiration(_context, cfg);

            /* Mark participating peers as low-latency when build completes quickly.
             * Only write profile when the flag actually changes to avoid disk churn.
             */
            int peerTimeout = _context.getProperty("router.peerTestTimeout", 750);
            int lowLatencyThreshold = 3 * peerTimeout;
            boolean lowLat = buildTime < lowLatencyThreshold;
            Hash selfHash = _context.routerHash();
            for (int i = 0; i < cfg.getLength(); i++) {
                Hash peer = cfg.getPeer(i);
                if (peer != null && !peer.equals(selfHash)) {
                    PeerProfile prof = _context.profileOrganizer().getProfile(peer);
                    if (prof != null && prof.isLowLatency() != lowLat) {
                        prof.setLowLatency(lowLat);
                        _context.profileOrganizer().writeProfile(prof);
                    }
                }
            }

            // Record successful tunnel participation for ghost peer detection
            if (_ghostPeerManager != null) {
                for (int i = 0; i < cfg.getLength(); i++) {
                    Hash peer = cfg.getPeer(i);
                    if (peer != null && !peer.equals(selfHash)) {
                        _ghostPeerManager.recordSuccess(peer);
                    }
                }
            }
        }
    }

    /**
     * Check if a tunnel build was recently attempted.
     *
     * @param replyId the reply message ID to check
     * @return true if the build was recently attempted, false otherwise
     */
    public boolean wasRecentlyBuilding(long replyId) {
        synchronized (_recentBuildIds) {
            return _recentBuildIds.contains(replyId);
        }
    }

    /**
     *  Trim a FIFO id set to at most {@code max} entries, dropping the oldest
     *  (head) entries first.  Insertion order is preserved by the caller.
     *
     *  @param ids the id set, held by the caller's monitor
     *  @param max the maximum size to keep
     *  @since 0.9.71+
     */
    static void trimFifo(Set<Long> ids, int max) {
        while (ids.size() > max) {
            Long oldest = ids.iterator().next();
            ids.remove(oldest);
        }
    }

    /**
     * Signal the executor to repoll for tunnel building opportunities.
     */
    public void repoll() {
        synchronized (_currentlyBuilding) {
            _repoll = true;
            _currentlyBuilding.notifyAll();
        }
    }

    /**
     * Log that a peer did not reply to a tunnel build request.
     *
     * @param tunnel the tunnel
     * @param peer the peer
     */
    private void didNotReply(long tunnel, Hash peer) {
        if (_log.shouldDebug()) {
            _log.debug("No reply from [" + peer.toBase64().substring(0,6) + "] to join [Tunnel " + tunnel + "]");
        }
    }

    /**
     *  Only do this for non-fallback tunnels.
     *  @return true if refused because of a duplicate key
     *  @since 0.7.12
     */
    private boolean addToBuilding(PooledTunnelCreatorConfig cfg) {
        return _currentlyBuildingMap.putIfAbsent(Long.valueOf(cfg.getReplyMessageId()), cfg) != null;
    }

    /**
     *  This returns the PTCC up to a minute after it 'expired', thus allowing us to
     *  still use a tunnel if it was accepted, and to update peer stats.
     *  This means that manager.buildComplete() could be called more than once, and
     *  a build can be failed or successful after it was timed out,
     *  which will affect the stats and profiles.
     *  But that's ok. A peer that rejects slowly gets penalized twice, for example.
     *
     *  @param id the build message ID
     *  @return ptcc or null
     *  @since 0.7.12
     */
    PooledTunnelCreatorConfig removeFromBuilding(long id) {
        Long key = Long.valueOf(id);
        PooledTunnelCreatorConfig rv = _currentlyBuildingMap.remove(key);
        if (rv != null) {
            if (_log.shouldDebug()) {
                long rtt = _context.clock().now() - rv.getConfig(0).getCreation();
                if (rtt < 0) {rtt = 0;}
                _log.debug("removeFromBuilding(): reply received (RTT: " + rtt + "ms) mapSize=" +
                          _currentlyBuildingMap.size() + " for: " + rv);
            }
            return rv;
        }
        rv = _recentlyBuildingMap.remove(key);
        if (rv != null) {
            if (_log.shouldInfo()) {
                long rtt = _context.clock().now() - rv.getConfig(0).getCreation();
                if (rtt < 0) {rtt = 0;}
                _log.info("Received late reply (RTT: " + rtt + "ms) for: " + rv);
            }
        }
        return rv;
    }

    /**
     * Graduated-urgency replenishment - replaces simple deficit-based approach.
     * Uses expiry-window urgency multipliers from mainline I2P:
     * 1x at 210-270s, 2x at 150-210s, 4x at 90-150s, 6x at <90s.
     * This ensures tunnels are rebuilt predictively before they expire,
     * rather than only reactively after failure.
     */
    private void calculatePairedBuilds(List<TunnelPool> pools, List<TunnelPool> wanted) {
        long now = _context.clock().now();

        /* Pre-collect per-direction targets for paired destinations.
         * Used for proportional build allocation so one direction of a pair
         * can't cannibalize the other's share of the build pool.
         */
        Map<Hash, int[]> pairTargets = collectPairTargets(pools);

        // Track per-direction builds requested in this iteration for capping
        Map<Hash, int[]> pairRequested = new HashMap<>(pools.size());

        // Sort pools by urgency: collapsed (0 usable) first, then near-collapse
        // (1-2 usable), then the rest.  Without this, a healthy pool early in the
        // list consumes build slots (or triggers the proportional cap) before a
        // collapsed pool later in the list gets any.
        // Snapshot scores before sorting to avoid TimSort crash from concurrent
        // state changes (usableTunnelCount can change mid-sort).
        List<TunnelPool> sorted = new ArrayList<>(pools);
        int ss = sorted.size();
        Object[][] scored2 = new Object[ss][2];
        for (int si = 0; si < ss; si++) {
            TunnelPool p = sorted.get(si);
            scored2[si][0] = computeUrgencyScore(p.getUsableTunnelCount(),
                                                 Math.max(2, p.getSettings().getTotalQuantity()));
            scored2[si][1] = p;
        }
        Arrays.sort(scored2, SCORE_COMPARATOR);
        for (int si = 0; si < ss; si++) {
            sorted.set(si, (TunnelPool) scored2[si][1]);
        }

        for (TunnelPool pool : sorted) {
            if (!pool.isAlive()) {continue;}

            String nickname = pool.getSettings().getDestinationNickname();
            boolean isPing = nickname != null && nickname.startsWith("Ping");

            // Ping and zero-hop pools keep their configured quantity; all
            // others hold at least 2 tunnels per direction so a single
            // failure can't empty the pool or starve the LeaseSet.
            int target = effectiveTarget(_context, pool.getSettings(),
                                         isPing || pool.getSettings().isZeroHop());

            List<TunnelInfo> tunnels = pool.listTunnels();

            ExpiryBuckets buckets = countExpiryBuckets(tunnels, now);

            int inProgress = pool.getInProgressCount();
            int remainingWanted = target - buckets.expireLater;
            // Zero-hop tunnels never satisfy demand in multi-hop pools: they
            // stay in fallbackCount so replacement builds are prioritized.
            // Only pools explicitly configured as zero-hop credit their
            // fallbacks, since there a length-1 tunnel IS the product.
            if (pool.getSettings().isZeroHop()) remainingWanted -= buckets.fallbackCount;

            /* Walk through urgency windows, counting what's covered by later-expiring tunnels.
             * This uses ALL tunnels (including FAILING) so retained tunnels fill the deficit
             * and prevent unnecessary builds.
             */
            for (int i = 0; i < buckets.expire330s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < buckets.expire270s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < buckets.expire210s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < buckets.expire150s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < buckets.expire90s && remainingWanted > 0; i++) remainingWanted--;
            for (int i = 0; i < buckets.expire30s && remainingWanted > 0; i++) remainingWanted--;

            int builds;
            /* Check if pool is critically low on GOOD tunnels before entering
             * build calculation. Critical pools bypass the GOOD_DEFICIT_THROTTLE_MS
             * and test queue cap so replacement builds don't lag behind expiry.
             */
            int activeCount = pool.getActiveTunnelCount();
            boolean isCritical = !isPing && (activeCount == 0 || (activeCount < target && activeCount <= 2));
            // A zero-hop fallback in a multi-hop-configured pool is an
            // emergency: it provides no anonymity and must be replaced as
            // fast as possible, bypassing budget/cap gating like a critical
            // pool. Replacement demand for it is added after the normal
            // build calculation below.
            boolean zeroEmergency = isZeroHopEmergency(pool);
            /* Don't overbuild when we already have untested tunnels queued for testing.
             * If enough pending tunnels are waiting for test results to cover the target,
             * let those complete before building more. Otherwise we pile up untested tunnels
             * faster than the test queue can process them (25/2, 40/2).
             */
            if (isCritical) {
                int needed = target - activeCount;
                /* When there are zero GOOD tunnels, always treat as critical
                 * regardless of testing count. Testing tunnels are UNTESTED
                 * and may never complete if the TestJob queue is saturated —
                 * waiting for them allows the pool to drain to zero.
                 */
                if (activeCount > 0 && pool.getTestingTunnelCount() >= needed) {
                    isCritical = false;
                }
            }
            // Count usable tunnels matching addTunnel() cap: exclude FAILED/FAILING
            int usableCount = 0;
            for (TunnelInfo ti : tunnels) {
                TunnelTestStatus ts = ti.getTestStatus();
                if (ts != TunnelTestStatus.FAILED && ts != TunnelTestStatus.FAILING && !ti.getTunnelFailed()) {
                    usableCount++;
                }
            }

            if (remainingWanted > 0) {
                /* Deficit — build just enough to fill the gap; the
                 * 60s loop catches subsequent needs and ensureSufficientTunnels
                 * covers event-driven fills between loops.
                 */
                builds = buckets.expire330s + buckets.expire270s + buckets.expire210s + buckets.expire150s + buckets.expire90s + buckets.expire30s + remainingWanted;
            } else {
                /* At capacity — skip proactive building, addTunnel() would reject.
                 * Only deficit builds (above) bypass this check since they fill
                 * an actual shortage and the cap handles overflow.
                 */
                if (usableCount >= Math.max(target + 2, 2)) {
                    continue;
                }
                /* Sufficient count — proactively replace GOOD tunnels approaching expiry.
                 * Start at 330s (5.5 min) so replacements have time to build before originals
                 * expire at 660s (11 min). FAILING tunnels are NOT proactively replaced —
                 * they stay until near-expiry (handled by ensureSufficientTunnels) or
                 * natural expiry. This prevents build-spam when all tunnels are FAILING.
                 * BUT: if there aren't enough GOOD tunnels to meet the target, build replacements
                 * so the pool doesn't get stuck with 0 GOOD tunnels.
                 * Without this, when all tunnels are FAILING the pool has zero viable tunnels but
                 * doesn't trigger builds (numerical deficit is satisfied by FAILING tunnels).
                 */
                builds = Math.min(buckets.goodExpire330s + buckets.goodExpire270s + buckets.goodExpire210s, target);
                int goodDeficit = target - buckets.goodExpireLater;
                if (goodDeficit > 0) {
                    /* Don't build when untested tunnels can cover the deficit.
                     * UNTESTED tunnels are recently built and awaiting testing —
                     * building more just piles up untested tunnels faster than
                     * the test queue can process them.
                     */
                    int untestedCount = 0;
                    for (TunnelInfo ti : tunnels) {
                        if (ti.getTestStatus() == TunnelTestStatus.UNTESTED) {
                            untestedCount++;
                        }
                    }
                    if (untestedCount < goodDeficit) {
                        if (isCritical) {
                            builds += goodDeficit;
                        } else {
                            long nowMs = System.currentTimeMillis();
                            Long lastRebuild = _lastRebuildTime.get(pool);
                            if (lastRebuild == null || nowMs - lastRebuild >= getGoodDeficitThrottle(_context)) {
                                builds += goodDeficit;
                                _lastRebuildTime.put(pool, nowMs);
                            }
                        }
                    }
                }
            }

            /* Proactive latency improvement: when GOOD tunnels have high average
             * latency, build replacements to get lower-latency candidates for
             * the next LeaseSet publication.  Only when the pool isn't critical
             * (has at least some GOOD tunnels) — capacity takes priority.
             */
            if (buckets.goodCount > 0 && !isCritical) {
                long avgLatency = buckets.totalLatency / buckets.goodCount;
                int latencyThreshold = _context.getProperty("router.latencyBuildThreshold", 1500);
                if (avgLatency > latencyThreshold) {
                    int excess = (int)((avgLatency - latencyThreshold) / 500);
                    int latencyBuilds = Math.min(excess, target);
                    if (latencyBuilds > 0) {
                        builds += latencyBuilds;
                    }
                }
            }

            /* Always subtract inProgress to prevent overbuilding.
             * Critical pools (0 active) get a minimum of 1 build via the
             * EMERGENCY path in ensureSufficientTunnels(), so they don't need
             * the bypass here.  The old bypass caused 300+ excess cancellations
             * per session: calculatePairedBuilds would fire builds ignoring
             * in-flight count, then cancelExcessInProgress would immediately
             * trim them, wasting build slots.
             */
            builds -= inProgress;
            // Zero-hop emergency floor: one replacement build per fallback
            // tunnel per pass, ahead of any cap or budget arithmetic — the
            // placeholders must cycle out as fast as selection allows.
            if (zeroEmergency) {
                builds = Math.max(builds, pool.countZeroHopFallbacks());
            }
            if (builds <= 0) continue;

            /* Cap at 2x target to prevent overbuilding.
             * When test queue is saturated (>80% full), also cap builds per pool
             * so we don't pile up untested tunnels faster than they can be tested.
             * Each free test slot supports ~2 concurrent builds.
             * Critical pools (low GOOD count) bypass the test queue cap — they need
             * builds regardless. Emergency test priority in TestJob.shouldSchedule()
             * ensures new tunnels get tested ASAP.
             */
            int maxBuilds = Math.max(target * 2, 2);
            if (builds > maxBuilds) builds = maxBuilds;
            if (!isCritical && !zeroEmergency) {
                int testJobs = TestJob.getCurrentTestJobCount();
                int maxTestJobs = TestJob.getMaxTestJobs();
                if (testJobs > maxTestJobs * 4 / 5) {
                    int free = Math.max(maxTestJobs - testJobs, 0);
                    int maxByTestCap = Math.max(free * 2, 1);
                    if (builds > maxByTestCap) builds = maxByTestCap;
                }
            }

            /* Proportional per-direction cap for paired pools.
             * When both directions of a pair are building, the pair's combined
             * per-iteration budget is maxBuilds (the per-pool cap). Each direction
             * gets its proportional share by target ratio, preventing one direction
             * from cannibalizing the build pool and starving the other.
             * Exception: collapsed pools (0 active) bypass the cap — a dead pool
             * needs all the builds it can get regardless of what the healthy
             * direction has.
             */
            Hash dest = pool.getSettings().getDestination();
            if (dest != null && activeCount > 0 && !zeroEmergency) {
                int[] pt = pairTargets.get(dest);
                if (pt != null && pt[0] > 0 && pt[1] > 0) {
                    int totalTarget = pt[0] + pt[1];
                    int myQty = pool.getSettings().isInbound() ? pt[0] : pt[1];
                    // Pair budget = max of both directions' per-pool maxBuilds
                    int pairBudget = Math.max(maxBuilds, Math.max(pt[0], pt[1]) * 2);
                    int dirCap = Math.max(1, pairBudget * myQty / totalTarget);
                    int[] pr = pairRequested.get(dest);
                    if (pr == null) {
                        pr = new int[2]; // [inboundUsed, outboundUsed]
                        pairRequested.put(dest, pr);
                    }
                    int dirUsed = pool.getSettings().isInbound() ? pr[0] : pr[1];
                    int remaining = dirCap - dirUsed;
                    if (builds > remaining) {
                        builds = Math.max(remaining, 0);
                    }
                    if (pool.getSettings().isInbound()) {
                        pr[0] += builds;
                    } else {
                        pr[1] += builds;
                    }
                }
            }

            for (int i = 0; i < builds; i++) {
                wanted.add(pool);
            }
        }
    }

    /**
     *  Collect per-direction build quantity targets for paired destinations,
     *  used by the proportional per-direction cap in calculatePairedBuilds().
     *  For each destination the inbound and outbound quantities are the maxima
     *  of the quantities configured on the pools for that direction, so a pair
     *  with asymmetric quantities (e.g. 4 inbound / 2 outbound) splits the
     *  build budget proportionally instead of 50/50.
     *
     *  @param pools pools to scan; dead pools and pools without a destination
     *               are skipped
     *  @return destination -&gt; int[2] {inboundQty, outboundQty}, never null
     *  @since 0.9.71+
     */
    static Map<Hash, int[]> collectPairTargets(List<TunnelPool> pools) {
        Map<Hash, int[]> pairTargets = new HashMap<>(pools.size());
        for (TunnelPool p : pools) {
            if (!p.isAlive()) continue;
            Hash dest = p.getSettings().getDestination();
            if (dest == null) continue;
            int qty = p.getSettings().getTotalQuantity();
            int[] dirs = pairTargets.get(dest);
            if (dirs == null) {
                dirs = new int[2]; // [inboundQty, outboundQty]
                pairTargets.put(dest, dirs);
            }
            if (p.getSettings().isInbound()) {
                if (dirs[0] == 0 || qty > dirs[0]) dirs[0] = qty;
            } else {
                if (dirs[1] == 0 || qty > dirs[1]) dirs[1] = qty;
            }
        }
        return pairTargets;
    }

    /**
     *  Urgency score used to sort pools before build allocation, so a
     *  collapsed or near-collapse pool earlier in the list cannot consume
     *  build slots (or trigger the proportional cap) before an urgent pool
     *  later in the list gets any.  Scores are tiered far apart on purpose:
     *  0 usable tunnels (1 &lt;&lt; 20) outrank 1-2 usable (1 &lt;&lt; 16) which outrank
     *  any numerical deficit, so the sort order is stable regardless of
     *  configured quantities.
     *
     *  @param usableCount current usable tunnel count of the pool
     *  @param target minimum desired tunnel count (already clamped to >= 2)
     *  @return urgency score; larger sorts earlier
     *  @since 0.9.71+
     */
    static int computeUrgencyScore(int usableCount, int target) {
        if (usableCount == 0) {return 1 << 20;}
        else if (usableCount <= 2) {return 1 << 16;}
        else {return Math.max(0, target - usableCount);}
    }

    /**
     *  Count a pool's tunnels into cumulative expiry-window buckets.
     *  Tunnels at or below one window boundary fall into that window (and no
     *  later one), so expire30s is "expires within 30s", expire90s is
     *  "within 90s but not 30s", and so on; expireLater covers everything
     *  beyond 330s.  Each bucket has a GOOD-only twin (good status and at
     *  most 1 consecutive failure) used for proactive replacement, plus
     *  aggregates: zero-hop fallback count, GOOD count and their total
     *  latency.
     *
     *  Length-1 tunnels always land in fallbackCount and never fill the
     *  expiry buckets, so pools that don't explicitly request zero-hop
     *  tunnels keep building replacements until the fallback is gone.
     *
     *  @param tunnels tunnels of one pool, never null
     *  @param now current time, used to compute time-to-expiry
     *  @return bucket counts, never null
     *  @since 0.9.71+
     */
    static ExpiryBuckets countExpiryBuckets(List<TunnelInfo> tunnels, long now) {
        int fallbackCount = 0;
        int expire30s = 0;
        int expire90s = 0;
        int expire150s = 0;
        int expire210s = 0;
        int expire270s = 0;
        int expire330s = 0;
        int expireLater = 0;
        int goodExpire30s = 0;
        int goodExpire90s = 0;
        int goodExpire150s = 0;
        int goodExpire210s = 0;
        int goodExpire270s = 0;
        int goodExpire330s = 0;
        int goodExpireLater = 0;
        int goodCount = 0;
        long totalLatency = 0;

        for (TunnelInfo info : tunnels) {
            // Length-1 tunnels are fallbacks, never demand-satisfiers: route
            // them to fallbackCount regardless of pool settings so pools that
            // don't explicitly request zero-hop keep replacing them
            if (info.getLength() <= 1) {
                fallbackCount++;
                continue;
            }

            /* Skip completely dead tunnels — they'll be removed by ExpireJob
             * and must NOT fill the deficit or they'll block replacement builds.
             */
            if (info.getTunnelFailed() || info.getConsecutiveFailures() > 3) {continue;}
            boolean isGood = info.getTestStatus() == TunnelTestStatus.GOOD &&
                             info.getConsecutiveFailures() <= 1;
            if (isGood) {
                goodCount++;
                int lat = TunnelPool.getTunnelAvgLatency(info);
                if (lat > 0) totalLatency += lat;
            }
            long timeToExpire = info.getExpiration() - now;
            if (timeToExpire <= 0) {
                expire30s++;
                if (isGood) goodExpire30s++;
            } else if (timeToExpire <= 30*1000) {
                expire30s++;
                if (isGood) goodExpire30s++;
            } else if (timeToExpire <= 90*1000) {
                expire90s++;
                if (isGood) goodExpire90s++;
            } else if (timeToExpire <= 150*1000) {
                expire150s++;
                if (isGood) goodExpire150s++;
            } else if (timeToExpire <= 210*1000) {
                expire210s++;
                if (isGood) goodExpire210s++;
            } else if (timeToExpire <= 270*1000) {
                expire270s++;
                if (isGood) goodExpire270s++;
            } else if (timeToExpire <= 330*1000) {
                expire330s++;
                if (isGood) goodExpire330s++;
            } else {
                expireLater++;
                if (isGood) goodExpireLater++;
            }
        }
        return new ExpiryBuckets(fallbackCount, expire30s, expire90s, expire150s, expire210s, expire270s,
                                 expire330s, expireLater, goodExpire30s, goodExpire90s, goodExpire150s,
                                 goodExpire210s, goodExpire270s, goodExpire330s, goodExpireLater,
                                 goodCount, totalLatency);
    }

    /**
     *  Immutable result of {@link BuildExecutor#countExpiryBuckets(List, long)}.
     */
    static class ExpiryBuckets {
        public final int fallbackCount;
        public final int expire30s, expire90s, expire150s, expire210s, expire270s, expire330s, expireLater;
        public final int goodExpire30s, goodExpire90s, goodExpire150s, goodExpire210s, goodExpire270s,
                         goodExpire330s, goodExpireLater;
        public final int goodCount;
        public final long totalLatency;

        ExpiryBuckets(int fallbackCount, int expire30s, int expire90s, int expire150s, int expire210s,
                      int expire270s, int expire330s, int expireLater, int goodExpire30s, int goodExpire90s,
                      int goodExpire150s, int goodExpire210s, int goodExpire270s, int goodExpire330s,
                      int goodExpireLater, int goodCount, long totalLatency) {
            this.fallbackCount = fallbackCount;
            this.expire30s = expire30s;
            this.expire90s = expire90s;
            this.expire150s = expire150s;
            this.expire210s = expire210s;
            this.expire270s = expire270s;
            this.expire330s = expire330s;
            this.expireLater = expireLater;
            this.goodExpire30s = goodExpire30s;
            this.goodExpire90s = goodExpire90s;
            this.goodExpire150s = goodExpire150s;
            this.goodExpire210s = goodExpire210s;
            this.goodExpire270s = goodExpire270s;
            this.goodExpire330s = goodExpire330s;
            this.goodExpireLater = goodExpireLater;
            this.goodCount = goodCount;
            this.totalLatency = totalLatency;
        }
    }

}
