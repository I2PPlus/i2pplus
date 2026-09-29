package net.i2p.router.tunnel.pool;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.BanLogger;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportImpl;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.LHMCache;
import net.i2p.util.ObjectCounter;
import net.i2p.stat.RateConstants;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;
import net.i2p.util.SimpleTimer2;

/**
 * Count how often we have accepted a tunnel with the peer as the previous or next hop.
 * We limit each peer to a percentage of all participating tunnels, subject to minimum
 * and maximum values for the limit.
 *
 * This offers basic protection against simple attacks but is not a complete solution,
 * as by design, we don't know the originator of a tunnel request.
 *
 * This also effectively limits the number of tunnels between any given pair of routers,
 * which probably isn't a bad thing.
 *
 * @since 0.8.4
 */
public class ParticipatingThrottler {
    private final RouterContext context;
    private final ObjectCounter<Hash> counter;
    private final Log _log;
    private BanLogger _banLogger;
    /**
     *  True on platforms we treat as slow. Selects a more conservative
     *  percentage divisor (300 rather than 100) and a lower {@link #_minLimit}
     *  cap in {@link #baseLimitFor}. Package-visible so the limit arithmetic
     *  can be pinned for both branches without a RouterContext.
     *  @since 0.9.71+
     */
    static final boolean IS_SLOW = SystemVersion.isSlow();
    private static final boolean DEFAULT_BLOCK_OLD_ROUTERS = true;
    private static final String PROP_BLOCK_OLD_ROUTERS = "router.blockOldRouters";

    /**
     * Min tunnels per peer before throttling.
     * @since 0.9.70+
     */
    public static volatile int _minLimit = SystemVersion.isSlow() ? 40 : 80;
    /**
     * Max tunnels per peer before throttling.
     * @since 0.9.70+
     */
    public static volatile int _maxLimit = SystemVersion.isSlow() ? 150 : 300;
    /**
     * Max pct of participating tunnels per peer.
     * @since 0.9.70+
     */
    public static volatile int _percentLimit = 10;
    /**
     * Rejection threshold: start rejecting at count/limit ratio this high (30-100, default 70%).
     * @since 0.9.70+
     */
    public static volatile int _rejectThreshold = 70;
    /**
     * Rejection steepness: 100=linear ramp, 200=quadratic, higher=faster escalation.
     * @since 0.9.70+
     */
    public static volatile int _rejectSteepness = 200;
    /**
     * Load weight: how much load inflates effective ratio (0-300%, default 100%).
     * @since 0.9.70+
     */
    public static volatile int _loadWeight = 100;

    /** Cached RateStat handle for the shared load-score computation; refreshed when the context changes. */
    private static volatile RouterContext _scoreCtx;
    private static volatile RateStat _bwQueueRateStat;

    /**
     *  Minimum interval between two full load-score recomputations, in ms.
     *
     *  <p>The score is consulted on every throttled participating request and
     *  is shared with {@code RequestThrottler}, but its inputs are all coarse:
     *  job queue lag in whole milliseconds, a 60s-smoothed CPU load, a
     *  1/5/15-minute load average and a 60s bandwidth rate. None of them move
     *  meaningfully inside one second, so recomputing per request only buys
     *  CPU. Staleness is bounded at {@code LOAD_SCORE_CACHE_MS} of wall clock
     *  plus whatever the underlying inputs are themselves smoothed over, which
     *  is invisible against a rejection curve that turns over on a per-request
     *  random draw.
     *
     *  @since 0.9.71+
     */
    private static final long LOAD_SCORE_CACHE_MS = 1000;
    /** Wall-clock ms the cached load score was computed, or 0 if never. */
    private static volatile long _loadScoreQueried;
    /** Cached 0.0-1.0 load score; only valid once {@link #_loadScoreQueried} is set. */
    private static volatile float _loadScore;
    /** Context the cached load score was computed against; a different context invalidates it. */
    private static volatile RouterContext _loadScoreCtx;

    /**
     *  Minimum interval between re-reading the two limit inputs that cannot
     *  change under us within a request, in ms.
     *
     *  <p>{@code router.maxParticipatingTunnels} is a static configuration
     *  property and {@code getSystemLoad()} reports a load average; both were
     *  re-read on every participating request even though neither moves on a
     *  second timescale. Staleness is bounded at
     *  {@code LIMIT_INVARIANT_CACHE_MS}: a limit can therefore lag a live
     *  property change or a load swing by at most that much. The TTL is
     *  deliberately the same short 1s the load score uses rather than something
     *  longer - the limit is a policy value that gates participation, and
     *  {@link #computeLimit} still re-reads every live input (participating
     *  count, bandwidth usage) on each call, so only the two invariants are
     *  smoothed.
     *
     *  @since 0.9.71+
     */
    private static final long LIMIT_INVARIANT_CACHE_MS = 1000;
    /** Wall-clock ms the cached limit invariants were read, or 0 if never. */
    private static volatile long _limitInvQueried;
    /** Cached {@code router.maxParticipatingTunnels}; only valid once {@link #_limitInvQueried} is set. */
    private static volatile int _maxParticipatingTunnels = 7500;
    /** Cached system load 0-100; only valid once {@link #_limitInvQueried} is set. */
    private static volatile int _systemLoad;
    /** Context the cached limit invariants were read against; a different context invalidates them. */
    private static volatile RouterContext _limitInvCtx;

    /**
     * The participating minimum limit.
     * @return min limit
     */
    public static int getParticipatingMinLimit() { return _minLimit; }
    /**
     * The participating minimum limit.
     * @param val min limit
     */
    public static void setParticipatingMinLimit(int val) { _minLimit = Math.max(20, Math.min(500, val)); }
    /**
     * The participating maximum limit.
     * @return max limit
     */
    public static int getParticipatingMaxLimit() { return _maxLimit; }
    /**
     * The participating maximum limit.
     * @param val max limit
     */
    public static void setParticipatingMaxLimit(int val) { _maxLimit = Math.max(50, Math.min(1000, val)); }
    /**
     * The participating percent limit.
     * @return pct limit
     */
    public static int getParticipatingPctLimit() { return _percentLimit; }
    /**
     * The participating percent limit.
     * @param val pct limit
     */
    public static void setParticipatingPctLimit(int val) { _percentLimit = Math.max(5, Math.min(100, val)); }
    /**
     * The rejection threshold.
     * @return reject threshold
     */
    public static int getRejectThreshold() { return _rejectThreshold; }
    /**
     * The rejection threshold.
     * @param val reject threshold
     */
    public static void setRejectThreshold(int val) { _rejectThreshold = Math.max(30, Math.min(100, val)); }
    /**
     * The rejection steepness.
     * @return reject steepness
     */
    public static int getRejectSteepness() { return _rejectSteepness; }
    /**
     * The rejection steepness.
     * @param val reject steepness
     */
    public static void setRejectSteepness(int val) { _rejectSteepness = Math.max(100, Math.min(500, val)); }
    /**
     * The load weight.
     * @return load weight
     */
    public static int getLoadWeight() { return _loadWeight; }
    /**
     * The load weight.
     * @param val load weight
     */
    public static void setLoadWeight(int val) { _loadWeight = Math.max(0, Math.min(300, val)); }
    // Cleanup interval in ms - 90 seconds
    private static final long CLEAN_TIME = 90 * 1000L;
    private static final long[] RATES = { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR };
    private static final String MIN_VERSION = "0.9.66";

    /**
     * Result of throttling decision for tunnel participation requests.
     * Determines whether to accept, reject, or drop a tunnel request.
     */
    public enum Result {
    /** Request accepted. */
    ACCEPT,
    /** Request rejected (retry later). */
    REJECT,
    /** Request dropped (too many requests). */
    DROP
}

    /**
     * Constrain a configured property to an allowed range, so a single
     * expression both reads the property and clamps it rather than storing
     * the raw value and overwriting it unread on the next line.
     *
     * @param val the value read from the properties
     * @param lo the lowest permitted value
     * @param hi the highest permitted value
     * @return val limited to [lo, hi]
     */
    private static int clamp(int val, int lo, int hi) {
        return Math.max(lo, Math.min(hi, val));
    }

    /** Participating throttler */
    ParticipatingThrottler(RouterContext ctx) {
        this.context = ctx;
        this.counter = new ObjectCounter<>();
        this._log = ctx.logManager().getLog(ParticipatingThrottler.class);
        _banLogger = new BanLogger();
        _banLogger.initialize(ctx);
        // Initialize from config, Tuner will override at runtime.
        // Each read is clamped in the same expression so the raw value is
        // never stored and then overwritten unread.
        // Enforce minimums to prevent integer division truncation to zero
        _minLimit = Math.max(20, ctx.getProperty("i2p.tunnel.participatingThrottle.minLimit", SystemVersion.isSlow() ? 40 : 80));
        _maxLimit = Math.max(50, ctx.getProperty("i2p.tunnel.participatingThrottle.maxLimit", SystemVersion.isSlow() ? 150 : 300));
        _percentLimit = Math.max(5, ctx.getProperty("i2p.tunnel.participatingThrottle.percentLimit", 10));
        // Probabilistic rejection params, Tuner will override at runtime
        _rejectThreshold = clamp(ctx.getProperty("i2p.tunnel.participatingThrottle.rejectThreshold", 70), 30, 100);
        _rejectSteepness = clamp(ctx.getProperty("i2p.tunnel.participatingThrottle.rejectSteepness", 200), 100, 500);
        _loadWeight = clamp(ctx.getProperty("i2p.tunnel.participatingThrottle.loadWeight", 100), 0, 300);
        ctx.statManager().createRequiredRateStat("tunnel.throttleParticipatingAccept", "Participating throttle accepts", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.throttleParticipatingReject", "Participating throttle rejects", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.throttleParticipatingDrop", "Participating throttle drops", "Tunnels [Participating]", RATES);
        new Cleaner().schedule(CLEAN_TIME);
    }

    /**
     * Determines whether to throttle tunnel participation for the given router,
     * counting the request against the peer's limit.
     *
     * @param h the hash of the router to check
     * @return the throttling Result (ACCEPT, REJECT, or DROP)
     */
    Result shouldThrottle(Hash h) {
        return shouldThrottle(h, true);
    }

    /**
     * Determines whether to throttle tunnel participation for the given router.
     * When {@code countRequest} is set the request increments the peer's counter;
     * consult-only checks read the current count without advancing it, and never
     * DROP or ban on it: a count this request did not contribute to must not
     * silently kill it, and mid-chain hops would otherwise accumulate roughly
     * twice per tunnel via both their prev-hop and next-hop roles.
     *
     * @param h the hash of the router to check
     * @param countRequest true to increment the peer's counter
     * @return the throttling Result (ACCEPT, REJECT, or DROP)
     */
    Result shouldThrottle(Hash h, boolean countRequest) {
        RouterInfo ri = (RouterInfo) context.netDb().lookupLocallyWithoutValidation(h);
        Hash us = context.routerHash();
        String caps = ri != null ? ri.getCapabilities() : "";
        boolean isUs = ri != null && us.equals(ri.getIdentity().getHash());
        boolean isUnreachable = ri != null && !isUs && (caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
                                                        caps.indexOf(Router.CAPABILITY_REACHABLE) < 0);
        boolean isG = ri != null && !isUs && caps.indexOf(Router.CAPABILITY_NO_TUNNELS) >= 0;
        boolean isLowShare = ri != null && !isUs && (
            caps.indexOf(Router.CAPABILITY_BW12) >= 0 ||
            caps.indexOf(Router.CAPABILITY_BW32) >= 0 ||
            caps.indexOf(Router.CAPABILITY_BW64) >= 0 || isG);
        boolean isFast = ri != null && !isUs && (
            caps.indexOf(Router.CAPABILITY_BW256) >= 0||
            caps.indexOf(Router.CAPABILITY_BW512) >= 0||
            caps.indexOf(Router.CAPABILITY_BW_UNLIMITED) >= 0);
        boolean isLU = isUnreachable && isLowShare;
        byte[] padding = ri != null ? ri.getIdentity().getPadding() : null;
        boolean isCompressible = padding != null && padding.length >= 64 && DataHelper.eq(padding, 0, padding, 32, 32);
        int numTunnels = context.tunnelManager().getParticipatingCount();
        int limit = calculateLimit(numTunnels, isUnreachable, isLowShare, isFast);
        int count = countRequest ? counter.increment(h) : counter.count(h);
        Result rv;
        int bantime = isLU || isLowShare || isUnreachable ? 60*60*1000 : 4*60*60*1000;
        boolean shouldThrottle = context.getProperty(RequestThrottler.PROP_SHOULD_THROTTLE, RequestThrottler.DEFAULT_SHOULD_THROTTLE);
        boolean shouldDisconnect = context.getProperty(RequestThrottler.PROP_SHOULD_DISCONNECT, RequestThrottler.DEFAULT_SHOULD_DISCONNECT);
        boolean shouldBlockOldRouters = context.getProperty(PROP_BLOCK_OLD_ROUTERS, DEFAULT_BLOCK_OLD_ROUTERS);
        boolean isBanned = context.banlist().isBanlisted(h) ||
                           context.banlist().isBanlistedHostile(h) ||
                           context.banlist().isBanlistedForever(h);

        if (!isUs && isBanned) {return Result.DROP;} // return early if router's already banned

        // Router absent from our local netDb: we cannot classify it, so apply
        // count-based throttling only and resolve it in the background. This
        // branch previously fell through to the no-version handler below,
        // treating the missing RI as "no version" and banning innocent
        // previous hops for four hours - observed live as a 12k-entry
        // banlist while transit volume grew.
        if (ri == null) {
            scheduleRouterLookup(h);
            // count was already advanced above for this join
            int ucCount = counter.count(h);
            int ucLimit = calculateLimit(numTunnels, false, false, false);
            Result ucRv = evaluateThrottleConditions(ucCount, ucLimit, shouldThrottle,
                    false, false, false, h, "", false, 4*60*60*1000, null, countRequest);
            if (ucRv == Result.ACCEPT)
                {context.statManager().addRateData("tunnel.throttleParticipatingAccept", 1);}
            else if (ucRv == Result.REJECT)
                {context.statManager().addRateData("tunnel.throttleParticipatingReject", 1);}
            else
                {context.statManager().addRateData("tunnel.throttleParticipatingDrop", 1);}
            return ucRv;
        }

        String version = ri.getVersion();

        if (version.equals("0") || version.isEmpty()) {
            // transient reject for minor infractions; handleNoVersion escalates
            // to a ban only after repeated offenses inside its window
            handleNoVersion(shouldDisconnect, h, isBanned, caps, ri, "none");
            context.statManager().addRateData("tunnel.throttleParticipatingReject", 1);
            return Result.REJECT;
        }
        if (checkVersionAndCompressibility(version, isCompressible, shouldDisconnect, h, isBanned, caps, ri)) return Result.DROP;
        if (checkLowShareAndVersion(version, isLU, shouldBlockOldRouters, h, shouldDisconnect, isBanned, caps, bantime, ri)) return Result.DROP;
        if (checkUnreachableAndOld(version, isUnreachable, isFast, shouldBlockOldRouters, h, shouldDisconnect, isBanned, caps)) return Result.DROP;

        rv = evaluateThrottleConditions(count, limit, shouldThrottle, isFast, isLowShare, isUnreachable, h, caps, isBanned, bantime, ri, countRequest);
        if (rv == Result.ACCEPT) {
            context.statManager().addRateData("tunnel.throttleParticipatingAccept", 1);
        } else if (rv == Result.REJECT) {
            context.statManager().addRateData("tunnel.throttleParticipatingReject", 1);
        } else {
            context.statManager().addRateData("tunnel.throttleParticipatingDrop", 1);
        }
        return rv;
    }

    /**
     * Calculates the participation limit for tunnels based on the number of tunnels
     * and router capabilities such as reachability and bandwidth share.
     * Relaxes limits when we have spare capacity (bandwidth headroom + available transit slots).
     * Enforces a floor (2 for low-share/unreachable, 5 for normal) to prevent zero limits
     * from integer division truncation at low percentage values.
     *
     * Only the two inputs that cannot move under us - the
     * {@code router.maxParticipatingTunnels} property and the system load
     * average - are served from a {@link #LIMIT_INVARIANT_CACHE_MS} cache.
     * Everything that reflects current router state (participating count,
     * outbound bandwidth limit, bandwidth queue usage) is re-read on every
     * call, and all of the arithmetic lives in {@link #computeLimit}.
     *
     * @param numTunnels the current count of participating tunnels
     * @param isUnreachable true if the router is unreachable
     * @param isLowShare true if the router has low bandwidth share
     * @param isFast true if the router has high bandwidth share
     * @return the per-peer tunnel participation limit, floored to prevent starvation
     */
    private int calculateLimit(int numTunnels, boolean isUnreachable, boolean isLowShare, boolean isFast) {
        refreshLimitInvariants();
        int maxTunnels = _maxParticipatingTunnels;
        int maxBps = context.bandwidthLimiter().getOutboundKBytesPerSecond() * 1024;
        double bwUsage = 0;
        if (maxBps > 0) {
            RateStat rs = context.statManager().getRate("tunnel.participating InBps");
            if (rs != null) {
                Rate rate = rs.getRate(60 * 1000L);
                if (rate != null && rate.getLastEventCount() > 0)
                    bwUsage = rate.getAverageValue() / maxBps;
            }
        }
        return computeLimit(numTunnels, isUnreachable, isLowShare, isFast, maxTunnels, bwUsage, _systemLoad);
    }

    /**
     * Re-reads the two invariant limit inputs when the cache is stale.
     * Publication order matters: both values and the context are written before
     * the timestamp, so a thread that sees a fresh timestamp also sees the
     * matching values. A concurrent duplicate refresh is harmless - the worst
     * case is one extra property read plus one extra load sample, so no lock.
     *
     * @since 0.9.71+
     */
    private void refreshLimitInvariants() {
        long now = System.currentTimeMillis();
        if (!limitInvariantCacheStale(_limitInvQueried, now, _limitInvCtx, context))
            return;
        _maxParticipatingTunnels = context.getProperty("router.maxParticipatingTunnels", 7500);
        _systemLoad = SystemVersion.getSystemLoad();
        _limitInvCtx = context;
        _limitInvQueried = now;
    }

    /**
     *  Whether the two invariant limit inputs are old enough to re-read.
     *  Never-read ({@code lastQueried == 0}) counts as stale so the first call
     *  reads them rather than trusting the field initializers. A swapped
     *  RouterContext (unit tests build their own) invalidates regardless of
     *  age, as does a wall-clock step backwards, which a plain age comparison
     *  would keep serving until the clock caught up again.
     *
     *  @param lastQueried the wall-clock ms of the previous read, or 0
     *  @param now the current wall-clock ms
     *  @param cachedCtx the context the cached values were read against
     *  @param ctx the context being asked for now
     *  @return true if the cached values should be refreshed
     *  @since 0.9.71+
     */
    static boolean limitInvariantCacheStale(long lastQueried, long now, RouterContext cachedCtx, RouterContext ctx) {
        return lastQueried == 0 || cachedCtx != ctx || now < lastQueried || now - lastQueried >= LIMIT_INVARIANT_CACHE_MS;
    }

    /**
     *  The participation-limit arithmetic, with every input supplied, so it can
     *  be exercised without a RouterContext. Reads the policy fields
     *  ({@link #_minLimit}, {@link #_maxLimit}, {@link #_percentLimit},
     *  {@link #IS_SLOW}) and applies the capacity relaxation, the idle-CPU
     *  bonus and the participation floor.
     *
     * @param numTunnels the current count of participating tunnels
     * @param isUnreachable true if the router is unreachable
     * @param isLowShare true if the router has low bandwidth share
     * @param isFast true if the router has high bandwidth share; not consumed by
     *               the current arithmetic, kept so the signature matches
     *               {@link #calculateLimit} and {@link #shouldThrottle(Hash, boolean)}
     * @param maxTunnels the configured participating tunnel ceiling
     * @param bwUsage outbound bandwidth usage as a fraction of the limit, 0 when unknown
     * @param sysLoad system load 0-100
     * @return the per-peer tunnel participation limit, floored to prevent starvation
     * @since 0.9.71+
     */
    static int computeLimit(int numTunnels, boolean isUnreachable, boolean isLowShare, boolean isFast,
                            int maxTunnels, double bwUsage, int sysLoad) {
        int baseLimit = baseLimitFor(numTunnels, isUnreachable, isLowShare,
                                     _minLimit, _maxLimit, _percentLimit, IS_SLOW);

        // Capacity-based relaxation: if we have plenty of headroom, allow more tunnels per peer
        double usagePct = (double) numTunnels / maxTunnels;

        // Plenty of capacity: < 50% tunnel slots used AND < 60% bandwidth used
        if (usagePct < 0.5 && bwUsage < 0.6) {
            // Relax by up to 50% based on spare capacity
            double spareSlots = 1.0 - usagePct * 2; // 0-1, higher = more spare
            double spareBw = 1.0 - bwUsage * 1.67;  // 0-1, higher = more spare
            double relaxation = Math.min(spareSlots, spareBw);
            baseLimit = (int) (baseLimit * (1.0 + relaxation * 0.5));
        }

        // System load relaxation: further boost when CPU is idle
        if (sysLoad < 30) {
            // Up to 25% bonus when system is very idle (0% → 25%, 30% → 0%)
            double idleBonus = (30 - sysLoad) / 30.0 * 0.25;
            baseLimit = (int) (baseLimit * (1.0 + idleBonus));
        }

        // Floor: always allow at least some participation per peer
        return Math.max(baseLimit, isUnreachable || isLowShare ? 2 : 5);
    }

    /**
     *  Percentage share of participating tunnels granted to one peer, before
     *  any relaxation. Unreachable and low-share peers are held to a fifth of
     *  the nominal share (a tenth on slow platforms) and capped at the min
     *  limit; everyone else gets the full share and may reach three times the
     *  min limit, bounded below by half the max limit. The divisors are applied
     *  in floating point so a low {@code pctLimit} cannot truncate the share
     *  to zero.
     *
     * @param numTunnels the current count of participating tunnels
     * @param isUnreachable true if the router is unreachable
     * @param isLowShare true if the router has low bandwidth share
     * @param minLimit the min limit
     * @param maxLimit the max limit
     * @param pctLimit the max pct of participating tunnels per peer
     * @param isSlow true to use the conservative slow-platform divisor
     * @return the unrelaxed percentage-based limit
     * @since 0.9.71+
     */
    static int baseLimitFor(int numTunnels, boolean isUnreachable, boolean isLowShare,
                            int minLimit, int maxLimit, int pctLimit, boolean isSlow) {
        if (isUnreachable || isLowShare) {
            int pctBased = (int) ((double) numTunnels * pctLimit / 500.0);
            return Math.min(minLimit, Math.max(maxLimit / 20, pctBased));
        }
        if (isSlow) {
            int pctBased = (int) ((double) numTunnels * pctLimit / 300.0);
            return Math.min(minLimit, Math.max(maxLimit / 10, pctBased));
        }
        int pctBased = (int) ((double) numTunnels * pctLimit / 100.0);
        return Math.min((minLimit * 3), Math.max(maxLimit / 2, pctBased));
    }

    /**
     * Handles joins from routers whose held RouterInfo carries no version.
     *
     * Minor infraction: the offending join is answered with a transient
     * reject (the caller maps Result.REJECT accordingly) and only repeated
     * no-version offenses within {@link #NO_VERSION_ESCALATION_MS} graduate
     * to a banlist entry. A single sighting is far more likely a stale or
     * buggy router than an attacker, and an immediate multi-hour ban on
     * first sight was poisoning the peer pool.
     *
     * @param shouldDisconnect whether to disconnect the router after banning
     * @param h the router hash
     * @param isBanned true if already banned
     * @param caps router capabilities string, used in log lines only
     * @param ri RouterInfo for IP extraction and logging
     * @param version router version string, used if disconnect is scheduled
     */
    private void handleNoVersion(boolean shouldDisconnect, Hash h, boolean isBanned, String caps, RouterInfo ri, String version) {
        if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h, version), 11*60*1000L);}
        long[] off = _noVersionOffenses.get(h);
        long now = context.clock().now();
        if (off == null || now - off[1] > NO_VERSION_ESCALATION_MS) {
            off = new long[] {1, now};
            _noVersionOffenses.put(h, off);
        } else {
            off[0]++;
            off[1] = now;
        }
        if (_log.shouldWarn()) {
            _log.warn("Join from Router [" + h.toBase64().substring(0,6) + "] with no version" +
                      " -> rejecting transient (offense " + off[0] + ')');
        }
        if (!isBanned && off[0] >= NO_VERSION_BAN_THRESHOLD &&
            "true".equals(context.getProperty("router.banlist.enableNoVersionBan", "true"))) {
            // one hour: the escalated ban only stops repeated wasted joins
            // from one identity, which rotation defeats anyway - keep it
            // short enough to self-heal if we misclassified
            int escalatedBanTime = 60*60*1000;
            String ipPort = TransportImpl.getRouterIPPort(ri);
            String banReason = "Repeated no-version RouterInfo";
            _banLogger.logBan(h, ipPort, banReason, escalatedBanTime, ri);
            context.banlist().banlistRouter(h, "" + banReason, null, null, context.clock().now() + escalatedBanTime);
            if (_log.shouldWarn()) {
                _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for " + (escalatedBanTime / 60000) +
                          "m after " + off[0] + " no-version joins");
            }
        }
    }

    /** No-version offense state per hash: {count, lastOffenseMs}. Bounded LRU. */
    private final LHMCache<Hash, long[]> _noVersionOffenses = new LHMCache<>(64);
    /** Repeated no-version joins inside this window escalate to a ban. */
    private static final long NO_VERSION_ESCALATION_MS = 30*60*1000L;
    /** Offenses inside the escalation window before a ban is issued. */
    private static final int NO_VERSION_BAN_THRESHOLD = 3;

    /**
     *  Rate-limited background resolution for transit peers missing from our
     *  local netDb, so throttler decisions graduate from count-only to full
     *  classification without blocking or hammering the netdb.
     */
    private void scheduleRouterLookup(Hash h) {
        Long last = _recentLookups.get(h);
        long now = context.clock().now();
        if (last != null && now - last < ROUTER_LOOKUP_RETRY_MS) {return;}
        _recentLookups.put(h, Long.valueOf(now));
        context.netDb().lookupRouterInfo(h, null, null, 15*1000);
    }
    private final LHMCache<Hash, Long> _recentLookups = new LHMCache<>(256);
    private static final long ROUTER_LOOKUP_RETRY_MS = 10*60*1000L;

    /**
     * Checks router version and compressibility of RouterInfo to decide on banning.
     *
     * @param version router software version string
     * @param isCompressible true if RouterInfo data is compressible (potentially suspicious)
     * @param shouldDisconnect whether to disconnect after banning
     * @param h router hash
     * @param isBanned true if already banned
     * @param caps router capabilities string
     * @return true if the router should be dropped (banned), false otherwise
     */
    private boolean checkVersionAndCompressibility(String version, boolean isCompressible, boolean shouldDisconnect, Hash h, boolean isBanned, String caps, RouterInfo ri) {
        if (VersionComparator.comp(version, "0.9.57") < 0 && isCompressible) {
            if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h, version), 11*60*1000L);}
            if (!isBanned && _log.shouldWarn()) {
                _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for 24h -> Compressible RouterInfo / " + version);
            }
            String ipPort = TransportImpl.getRouterIPPort(ri);
            String banReason = "Compressible RouterInfo & older than 0.9.57";
            _banLogger.logBan(h, ipPort, banReason, 24*60*60*1000L, ri);
            context.banlist().banlistRouter(h, "" + banReason, null, null, context.clock().now() + 24*60*60*1000);
            return true;
        }
        return false;
    }

    /**
     * Checks if a low-share, old-version router should be banned and disconnected.
     *
     * @param version router version string
     * @param isLU true if router is low share and unreachable
     * @param shouldBlockOldRouters whether to block old routers
     * @param h router hash
     * @param shouldDisconnect whether to disconnect after banning
     * @param isBanned true if already banned
     * @param caps router capabilities string
     * @param bantime ban duration in milliseconds
     * @return true if the router should be dropped (banned), false otherwise
     */
    private boolean checkLowShareAndVersion(String version, boolean isLU, boolean shouldBlockOldRouters, Hash h,
                                            boolean shouldDisconnect, boolean isBanned, String caps, int bantime, RouterInfo ri) {
        if (VersionComparator.comp(version, MIN_VERSION) < 0 && isLU && shouldBlockOldRouters) {
            if (shouldDisconnect) {
                context.commSystem().forceDisconnect(h, "Old version " + version);
            }
            if (!isBanned && _log.shouldWarn()) {
                _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for " + (bantime / 60000) +
                          "m -> " + version + (caps.isEmpty() ? "" : " / " + caps));
            }
            if (context.banlist().isLuBanEnabled()) {
                String ipPort = TransportImpl.getRouterIPPort(ri);
                String banReason = "Old and slow (" + version + ")";
                _banLogger.logBan(h, ipPort, banReason, bantime, ri);
                context.banlist().banlistRouter(h, "" + banReason, null, null, context.clock().now() + bantime);
            }
            return true;
        }
        return false;
    }

    /**
     * Checks if an unreachable and old router should be banned or ignored.
     *
     * @param version router version string
     * @param isUnreachable true if router is unreachable
     * @param isFast true if router has high bandwidth
     * @param shouldBlockOldRouters whether to block old routers
     * @param h router hash
     * @param shouldDisconnect whether to disconnect after banning
     * @param isBanned true if already banned
     * @param caps router capabilities string
     * @return true if the router should be dropped (ignored), false otherwise
     */
    private boolean checkUnreachableAndOld(String version, boolean isUnreachable, boolean isFast, boolean shouldBlockOldRouters, Hash h,
                                           boolean shouldDisconnect, boolean isBanned, String caps) {
        if (VersionComparator.comp(version, MIN_VERSION) < 0 && isUnreachable && shouldBlockOldRouters && !isFast) {
            if (shouldDisconnect) {context.simpleTimer2().addEvent(new Disconnector(h, version), 11*60*1000L);}
            if (_log.shouldWarn()) {
                _log.warn("Ignoring Tunnel Request from Router [" + h.toBase64().substring(0,6) + "] -> " + version + (caps.isEmpty() ? "" : " / " + caps));
            }
            return true;
        }
        return false;
    }

    /**
     * Evaluates whether to accept, reject, or drop tunnel requests based on
     * probabilistic rejection curve modulated by system load.
     * Replaces the old deterministic count &gt; limit cutoff with a smooth
     * probability function that ramps from 0 at the reject threshold to
     * near-1 under load or high ratio.
     *
     * @param count current participation count for the router
     * @param limit maximum allowed participation
     * @param shouldThrottle true if throttling is enabled
     * @param isFast true if router is high bandwidth
     * @param isLowShare true if router is low bandwidth
     * @param isUnreachable true if router is unreachable
     * @param h router hash
     * @param caps router capabilities string
     * @param isBanned true if router is already banned
     * @param bantime ban duration in milliseconds
     * @param ri the RouterInfo, for ban logging
     * @param countRequest true when this request advanced the counter;
     *                     consult-only checks downgrade DROP to REJECT and
     *                     never ban on a count they did not contribute to
     * @return Result indicating ACCEPT, REJECT, or DROP action
     */
    private Result evaluateThrottleConditions(int count, int limit, boolean shouldThrottle, boolean isFast, boolean isLowShare,
                                              boolean isUnreachable, Hash h, String caps, boolean isBanned, int bantime, RouterInfo ri,
                                              boolean countRequest) {
        if (!shouldThrottle || limit <= 0)
            return Result.ACCEPT;

        float ratio = (float) count / limit;
        float load = calculateLoadScore(context);
        float threshold = _rejectThreshold / 100.0f;
        float steepness = _rejectSteepness / 100.0f;

        // Inflate effective ratio by load: at full load (1.0) with _loadWeight=100,
        // effective ratio = 2x actual. At zero load, no inflation.
        float effectiveRatio = ratio * (1.0f + load * _loadWeight / 100.0f);

        if (effectiveRatio >= threshold) {
            // When threshold < 1.0, range spans from threshold to 1.0 (full saturation).
            // When threshold >= 1.0 (tuned higher by the auto-tuner), use threshold
            // itself as the range so the curve spans from threshold to 2*threshold.
            float range = threshold < 1.0f ? (1.0f - threshold) : threshold;
            float normalized = Math.min(1.0f, (effectiveRatio - threshold) / range);
            // steepness=100 → linear, steepness=200 → quadratic (faster ramp at high ratios)
            float prob = (float) Math.pow(normalized, 100.0f / steepness);
            prob = Math.min(1.0f, Math.max(0.0f, prob));

            if (context.random().nextFloat() < prob) {
                if ((countRequest && (ratio >= 2.0f || (isUnreachable && count > limit + 30) ||
                     (isLowShare && count > limit + 20)))) {
                    handleExcessiveRequests(h, caps, count, limit, bantime, ri);
                    return Result.DROP;
                }
                if (!countRequest && ratio >= 2.0f) {
                    // Consult-only: too high to accept silently, but this request
                    // did not advance the counter — reject instead of dropping,
                    // and leave bans to counting requests.
                    return Result.REJECT;
                }
                _logHighRequestCount(h, caps, count, limit);
                return Result.REJECT;
            }
        }
        _logAcceptRequest(caps, count);
        return Result.ACCEPT;
    }

    /**
     * Computes a 0.0–1.0 load score from job queue lag, CPU load, system load,
     * and bandwidth queue pressure, reusing the score computed within the last
     * {@link #LOAD_SCORE_CACHE_MS}. Used to shift the rejection probability
     * curve left when the router is under load. Shared by
     * ParticipatingThrottler and RequestThrottler; both the score and the
     * RateStat handle are cached per context.
     *
     * Publication order matters: the score and its context are written before
     * the timestamp, so a thread that sees a fresh timestamp also sees the
     * matching score. Concurrent callers may both compute - the worst case is
     * one redundant computation per interval, so no lock is taken.
     *
     * @param context the context to read the load inputs from
     * @return the load score, 0.0 (idle) to 1.0 (saturated)
     * @see #computeLoadScore(RouterContext)
     * @since 0.9.71+
     */
    static float calculateLoadScore(RouterContext context) {
        long now = System.currentTimeMillis();
        if (!loadScoreCacheStale(_loadScoreQueried, now, _loadScoreCtx, context))
            return _loadScore;
        float score = computeLoadScore(context);
        _loadScore = score;
        _loadScoreCtx = context;
        _loadScoreQueried = now;
        return score;
    }

    /**
     *  Whether the cached load score is old enough to recompute.
     *  Never-computed ({@code lastQueried == 0}) counts as stale so the first
     *  call computes rather than returning the 0.0 initializer. A swapped
     *  RouterContext (unit tests build their own) invalidates regardless of
     *  age, as does a wall-clock step backwards, which a plain age comparison
     *  would keep serving until the clock caught up again.
     *
     *  @param lastQueried the wall-clock ms of the previous computation, or 0
     *  @param now the current wall-clock ms
     *  @param cachedCtx the context the cached score was computed against
     *  @param ctx the context being asked for now
     *  @return true if the score should be recomputed
     *  @since 0.9.71+
     */
    static boolean loadScoreCacheStale(long lastQueried, long now, RouterContext cachedCtx, RouterContext ctx) {
        return lastQueried == 0 || cachedCtx != ctx || now < lastQueried || now - lastQueried >= LOAD_SCORE_CACHE_MS;
    }

    /**
     * Uncached 0.0–1.0 load score from job queue lag, CPU load, system load,
     * and bandwidth queue pressure. Job lag contributes 40% of the score and
     * saturates at 1000ms, CPU load 25%, system load 20%, and bandwidth queue
     * pressure 15%, saturating at 100KB/s; each input is clamped to its own
     * range so a lagging counter cannot push the total past 1.0.
     *
     * @param context the context to read the load inputs from
     * @return the load score, 0.0 (idle) to 1.0 (saturated)
     * @since 0.9.71+
     */
    static float computeLoadScore(RouterContext context) {
        float score = 0.0f;
        // Job lag: 0ms → 0, 1000ms+ → 1.0 (40% weight)
        long lag = context.jobQueue().getMaxLag();
        score += Math.min(1.0f, lag / 1000.0f) * 0.4f;
        // CPU load: 0% → 0, 100% → 1.0 (25% weight)
        int cpuLoad = SystemVersion.getCPULoadAvg();
        if (cpuLoad > 0)
            score += Math.min(1.0f, cpuLoad / 100.0f) * 0.25f;
        // System load: 0 → 0, 100 → 1.0 (20% weight)
        int sysLoad = SystemVersion.getSystemLoad();
        score += Math.min(1.0f, sysLoad / 100.0f) * 0.2f;
        // Bandwidth queue: 0 → 0, 100KB+ → 1.0 (15% weight)
        RateStat bwRs = getBwQueueRateStat(context);
        if (bwRs != null) {
            net.i2p.stat.Rate rate = bwRs.getRate(60000);
            if (rate != null && rate.getLastEventCount() > 0) {
                score += Math.min(1.0f, (float)(rate.getAverageValue() / 100000.0)) * 0.15f;
            }
        }
        return Math.min(1.0f, score);
    }

    /**
     * Resolves the "bwLimiter.participatingBandwidthQueue" RateStat once per
     * context. The stat is registered during startup and never replaced, so
     * the handle is only looked up again when the context changes. A stat that
     * is not registered yet leaves the cache untouched and returns null, so a
     * later call retries instead of caching the absence.
     *
     * @param context the context to resolve against
     * @return the cached stat, or null if it is not registered yet
     * @since 0.9.71+
     */
    private static RateStat getBwQueueRateStat(RouterContext context) {
        if (_scoreCtx != context) {
            RateStat rs = context.statManager().getRate("bwLimiter.participatingBandwidthQueue");
            if (rs != null) {
                _bwQueueRateStat = rs;
                _scoreCtx = context;
            }
            return rs;
        }
        return _bwQueueRateStat;
    }

    /**
     * Handles excessive tunnel request counts by banning and scheduling disconnect.
     *
     * @param h router hash
     * @param caps router capabilities string
     * @param count current participation count
     * @param limit participation limit
     * @param bantime ban duration in milliseconds
     * @param ri the router info for IP extraction and logging
     */
    private void handleExcessiveRequests(Hash h, String caps, int count, int limit, int bantime, RouterInfo ri) {
        if ("true".equals(context.getProperty("router.banlist.enableExcessiveTunnelRequestsBan", "true"))) {
            String ipPort = TransportImpl.getRouterIPPort(ri);
            String banReason = "Excessive tunnel requests";
            _banLogger.logBan(h, ipPort, banReason, bantime, ri);
            context.banlist().banlistRouter(h, "" + banReason, null, null, context.clock().now() + bantime);
            context.simpleTimer2().addEvent(new Disconnector(h, banReason), 11 * 60 * 1000L);
            if (_log.shouldWarn()) {
                _log.warn("Banning Router [" + h.toBase64().substring(0,6) + "] for " + (bantime / 60000) +
                          "m -> Excessive tunnel requests -> Count / Limit: " +
                          count + " / " + limit + " in 90s");
            }
        }
    }

    /** Logs warnings for high count tunnel request rejections. */
    private void _logHighRequestCount(Hash h, String caps, int count, int limit) {
        if (_log.shouldWarn()) {
            _log.warn("Rejecting Tunnel Requests from " + (caps != null ? caps : "") +
                      " Router [" + h.toBase64().substring(0,6) + "] -> Count / Limit: " +
                      count + " / " + limit + " in 90s");
        }
    }

    /** Logs debug information for accepted tunnel requests. */
    private void _logAcceptRequest(String caps, int count) {
        if (_log.shouldDebug()) {
            _log.debug("Accepting Tunnel Request from " + (!caps.isEmpty() ? caps : "") +
                       " Router -> Count: " + count + " in 90s");
        }
    }

    /**
     * Periodic timer event that clears the participation counts to reset throttling.
     * Reschedules itself each run so the counter is cleared every CLEAN_TIME; without
     * the reschedule the counter would clear only once then grow unbounded, leaking
     * memory and eventually banning peers on stale accumulated counts.
     */
    private class Cleaner extends SimpleTimer2.TimedEvent {
        /**
         * Periodically clear the burst counter.
         */
        public Cleaner() { super(context.simpleTimer2()); }
        /**
         * Clear the counter and reschedule.
         */
        @Override
        public void timeReached() {
            counter.clear();
            schedule(CLEAN_TIME);
        }
    }

    /**
     * Timer event that disconnects a router after a delay.
     */
    private class Disconnector extends SimpleTimer2.TimedEvent {
        private final Hash h;
        private final String version;
        /**
         * Force disconnect the peer on timer expiry.
         */
        public Disconnector(Hash h, String version) { super(context.simpleTimer2()); this.h = h; this.version = version; }
        /**
         * Force disconnect the peer.
         */
        public void timeReached() {
            String reason = (version == null || version.isEmpty()) ? "Old version" : "Old version (" + version + ")";
            context.commSystem().forceDisconnect(h, reason);
        }
    }
}
