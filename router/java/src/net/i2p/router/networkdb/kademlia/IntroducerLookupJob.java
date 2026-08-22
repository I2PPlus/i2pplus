package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Background maintenance job that keeps introducer RouterInfos warm in the
 * local netDb.
 *
 * Unreachable (U-cap) peers publish SSU2 introducers as router hashes (the
 * "ih0".."ih4" options on their SSU/SSU2 addresses). When we need an inbound
 * connection from such a peer - or need to reach one ourselves -
 * EstablishmentManager looks up each introducer RI on demand; if the RI is
 * not cached locally, establishment stalls while the search runs, and
 * IntroductionManager must fall back to ad-hoc direct queries. This job
 * reviews the introducers of known unreachable peers on a slow schedule and
 * issues remote RouterInfo lookups for any introducer missing locally, so
 * hole-punching has its prerequisites ready before a connection is attempted.
 *
 * Pacing and politeness toward floodfills:
 * a randomized 5-7 minute cycle whose query phase is capped at 30s wall time
 * per cycle (no per-cycle count cap), at most {@link #MAX_CONCURRENT_LOOKUPS}
 * searches in flight, per-introducer exponential backoff after failures, and
 * a dedup/backoff map shared across cycles (which also gates keys already
 * covered by an overlapping search elsewhere). Each search inherits the
 * standard adaptive RouterInfo lookup deadline used by peer lookups.
 * Floodfill diversity is provided by the netdb selection layer itself:
 * FloodfillPeerSelector shuffles candidates within quality tiers, skips
 * floodfills queried in the last 60s (markQueried cooldown), and spreads
 * same-/16 IP clusters, so repeated keys don't repeatedly land on the same
 * floodfills and no single one sees enough volume to trigger throttling.
 * Concurrent duplicate searches of the same key are additionally coalesced
 * by IterativeSearchJob. SSU1 introducers carry no router hash
 * (IP/port/intro-key only) and cannot be resolved by netDB lookup; they are
 * skipped here and handled by the transport's existing IP-based introduction
 * path.
 *
 * @since 0.9.71+
 */
class IntroducerLookupJob extends JobImpl {
    private final Log _log;
    private final FloodfillNetworkDatabaseFacade _facade;

    /**
     * Introducer hash -&gt; {last attempt time, consecutive failures}.
     * Entries are removed on lookup success and pruned when oversized.
     */
    private final Map<Hash, long[]> _attempts = new ConcurrentHashMap<>();

    /** Remote introducer searches currently in flight. */
    private final AtomicInteger _inFlight = new AtomicInteger();

    /** Base delay between cycles, jittered ±CYCLE_JITTER before each requeue. */
    private static final long CYCLE_INTERVAL = 5 * 60 * 1000L;
    private static final long CYCLE_JITTER = 2 * 60 * 1000L;
    /** First run 8-12 minutes after startup: let the netdb fill from bootstrap first. */
    private static final long STARTUP_DELAY_MIN = 8 * 60 * 1000L;
    private static final long STARTUP_DELAY_JITTER = 4 * 60 * 1000L;
    /**
     * Wall-time budget for one cycle's query phase: lookups are issued until
     * this window closes (no per-cycle count cap), and each lookup's deadline
     * is clamped to the budget left at issue time, so every query of a cycle
     * completes within ~30s of cycle start.
     */
    private static final long CYCLE_QUERY_BUDGET_MS = 30 * 1000L;
    /** Maximum remote searches in flight during the query phase. */
    private static final int MAX_CONCURRENT_LOOKUPS = 4;
    /**
     * Issue floor: IterativeSearchJob floors RouterInfo lookup deadlines at
     * 2s anyway, so a search with less budget left is not worth issuing.
     */
    private static final int MIN_USEFUL_TIMEOUT_MS = 2000;
    /** Re-attempt gate while a lookup may still be in flight (fails == 0). */
    private static final long MIN_RETRY_MS = 10 * 60 * 1000L;
    /** Base backoff after a failed lookup, doubling per consecutive failure. */
    private static final long FAIL_BACKOFF_MS = 30 * 60 * 1000L;
    private static final long MAX_FAIL_BACKOFF_MS = 6L * 60 * 60 * 1000L;
    /** Prune threshold for the attempts map. */
    private static final int ATTEMPT_MAP_MAX = 256;
    /**
     *  Introducer slots per address. Mirrors UDPAddress.MAX_INTRODUCERS,
     *  which is package-private in the transport package.
     */
    private static final int MAX_INTRO_SLOTS = 5;
    /** Introducer relay tag option prefix ("itag0".."itag4"); required in every slot. */
    private static final String PROP_INTRO_TAG_PREFIX = "itag";
    /** SSU2 introducer router hash option prefix ("ih0".."ih4"). */
    private static final String PROP_INTRO_HASH_PREFIX = "ih";

    /**
     *  Constructor. Sets the staggered initial start time.
     *
     *  @param ctx the router context
     *  @param facade the floodfill network database facade
     *  @since 0.9.71+
     */
    public IntroducerLookupJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(IntroducerLookupJob.class);
        _facade = facade;
        getTiming().setStartAfter(ctx.clock().now() +
                                  STARTUP_DELAY_MIN +
                                  ctx.random().nextLong(STARTUP_DELAY_JITTER));
        ctx.statManager().createRateStat("netDb.introducerRILookups",
                                         "Remote introducer RouterInfo lookups issued",
                                         "NetworkDatabase",
                                         new long[] { 60*1000L, 10*60*1000L, 60*60*1000L });
    }

    public String getName() { return "Lookup Missing Introducers"; }

    @Override
    public void runJob() {
        RouterContext ctx = getContext();
        long now = ctx.clock().now();
        Set<RouterInfo> routers = _facade.getRouters();
        Set<Hash> pending = null;
        if (routers != null) {
            Hash us = ctx.routerHash();
            for (RouterInfo ri : routers) {
                // Cheap caps-first filter; most routers are not U-cap and
                // parsing their addresses is skipped entirely.
                if (!isUnreachablePeer(ri))
                    continue;
                for (Hash h : getIntroducerHashes(ri.getAddresses())) {
                    if (h.equals(us) || ctx.banlist().isBanlisted(h))
                        continue;
                    if (_facade.lookupRouterInfoLocally(h) != null)
                        continue;  // already cached
                    if (!canAttempt(now, _attempts.get(h)))
                        continue;
                    if (pending == null)
                        pending = new HashSet<>();
                    pending.add(h);
                }
            }
        }
        pruneAttempts(now);
        long deadline = now + CYCLE_QUERY_BUDGET_MS;
        int issued = 0;
        if (pending != null) {
            for (Hash h : pending) {
                if (_inFlight.get() >= MAX_CONCURRENT_LOOKUPS)
                    break;
                int timeoutMs = issueTimeout(deadline - ctx.clock().now(),
                                             IterativeSearchJob.getMaxRouterInfoLookupTime());
                if (timeoutMs <= 0)
                    break;  // query budget exhausted
                requestLookup(ctx, h, now, timeoutMs);
                issued++;
            }
            // Keys left unissued were never gated in _attempts, so they stay
            // eligible and are picked up first next cycle.
        }
        if (_log.shouldDebug() && issued > 0) {
            _log.debug("IntroducerLookupJob: issued " + issued + " of " +
                       pending.size() + " missing introducer RI lookups" +
                       " (" + _inFlight.get() + " in flight, " +
                       _attempts.size() + " tracked)");
        }
        requeue(nextInterval(ctx));
    }

    /**
     *  Record the attempt and issue one non-blocking remote RI lookup with
     *  the given deadline (never past this cycle's query budget, never above
     *  the standard adaptive RouterInfo lookup cap).
     *  Success clears the tracking entry (the RI is then cached locally and
     *  expires through the normal netdb lifecycle); failure increments the
     *  consecutive-failure count, which lengthens the retry backoff. Both
     *  outcomes release a concurrency slot.
     */
    private void requestLookup(RouterContext ctx, final Hash h, long now, final int timeoutMs) {
        _attempts.compute(h, (k, v) -> v == null ? new long[] {now, 0}
                                                 : new long[] {now, v[1]});
        _inFlight.incrementAndGet();
        Job onFind = new JobImpl(ctx) {
            @Override
            public String getName() { return "Introducer Lookup"; }
            @Override
            public void runJob() {
                _attempts.remove(h);
                _inFlight.decrementAndGet();
            }
        };
        Job onFail = new JobImpl(ctx) {
            @Override
            public String getName() { return "Introducer Lookup Retry"; }
            @Override
            public void runJob() {
                _attempts.computeIfPresent(h, (k, v) -> new long[] {v[0], v[1] + 1});
                _inFlight.decrementAndGet();
            }
        };
        _facade.lookupRouterInfoRemote(h, onFind, onFail, timeoutMs);
        ctx.statManager().addRateData("netDb.introducerRILookups", 1);
    }

    /** Next cycle delay: CYCLE_INTERVAL with uniform ±CYCLE_JITTER. */
    private long nextInterval(RouterContext ctx) {
        return CYCLE_INTERVAL - CYCLE_JITTER + ctx.random().nextLong(2 * CYCLE_JITTER + 1);
    }

    /**
     *  Bound the attempts map: drop entries whose last attempt predates the
     *  maximum possible backoff window (they would fail canAttempt() as
     *  eligible anyway and would be recreated fresh).
     */
    private void pruneAttempts(long now) {
        if (_attempts.size() <= ATTEMPT_MAP_MAX)
            return;
        long cutoff = now - MAX_FAIL_BACKOFF_MS;
        for (Iterator<Map.Entry<Hash, long[]>> iter = _attempts.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<Hash, long[]> e = iter.next();
            if (e.getValue()[0] < cutoff)
                iter.remove();
            if (_attempts.size() <= ATTEMPT_MAP_MAX)
                break;
        }
    }

    /**
     *  Whether the peer publishes the unreachable capability ('U'), i.e. it
     *  is firewalled / cannot accept unsolicited connections and therefore
     *  relies on introducers for inbound connectivity.
     *
     *  @param ri the peer's RouterInfo (non-null)
     *  @return true if the capabilities string contains 'U'
     *  @since 0.9.71+
     */
    static boolean isUnreachablePeer(RouterInfo ri) {
        String caps = ri.getCapabilities();
        return caps != null && caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
    }

    /**
     *  Extract SSU2 introducer router hashes ("ih0".."ih4") from the given
     *  addresses. An introducer slot exists only if its required relay tag
     *  option ("itag{i}") is present; SSU1-only slots (ihost/iport/ikey with
     *  no "ih") carry no hash and yield nothing. Duplicates removed.
     *  Pure decision — no context access, safe for unit tests.
     *
     *  @param addresses the peer's transport addresses, may be null or empty
     *  @return the introducer hashes, never null
     *  @since 0.9.71+
     */
    static List<Hash> getIntroducerHashes(Collection<RouterAddress> addresses) {
        List<Hash> rv = new ArrayList<>(0);
        if (addresses == null)
            return rv;
        for (RouterAddress ra : addresses) {
            if (ra == null)
                continue;
            for (int i = 0; i < MAX_INTRO_SLOTS; i++) {
                if (ra.getOption(PROP_INTRO_TAG_PREFIX + i) == null)
                    continue;
                Hash h = parseIntroducerHash(ra.getOption(PROP_INTRO_HASH_PREFIX + i));
                if (h != null && !rv.contains(h))
                    rv.add(h);
            }
        }
        return rv;
    }

    /**
     *  Decode an "ih{i}" option value into a Hash: Base64 of exactly 32
     *  bytes, else null. Pure decision — safe for unit tests.
     *
     *  @param b64 the option value, may be null
     *  @return the hash, or null if absent/malformed
     *  @since 0.9.71+
     */
    static Hash parseIntroducerHash(String b64) {
        if (b64 == null)
            return null;
        byte[] b = Base64.decode(b64);
        if (b == null || b.length != Hash.HASH_LENGTH)
            return null;
        return Hash.create(b);
    }

    /**
     *  Retry backoff for an introducer whose last lookup failed {@code fails}
     *  consecutive times: 0 (in flight / find pending) → {@link #MIN_RETRY_MS};
     *  1+ → {@link #FAIL_BACKOFF_MS} doubling per failure, capped at
     *  {@link #MAX_FAIL_BACKOFF_MS}. Pure decision — safe for unit tests.
     *
     *  @param fails consecutive failed lookup attempts
     *  @return the backoff in milliseconds
     *  @since 0.9.71+
     */
    static long backoffMillis(int fails) {
        if (fails <= 0)
            return MIN_RETRY_MS;
        long ms = FAIL_BACKOFF_MS;
        for (int i = 1; i < fails; i++) {
            ms <<= 1;
            if (ms >= MAX_FAIL_BACKOFF_MS)
                break;
        }
        return Math.min(ms, MAX_FAIL_BACKOFF_MS);
    }

    /**
     *  Gate for issuing a lookup now: true when never attempted, or when the
     *  elapsed time since the last attempt meets the state's backoff.
     *  Pure decision — safe for unit tests.
     *
     *  @param now current time in milliseconds
     *  @param state {last attempt time, consecutive failures}, or null
     *  @return true if a lookup may be attempted
     *  @since 0.9.71+
     */
    static boolean canAttempt(long now, long[] state) {
        if (state == null)
            return true;
        return now - state[0] >= backoffMillis((int) state[1]);
    }

    /**
     *  Deadline for one issued lookup: the standard adaptive RouterInfo
     *  lookup cap (the same deadline peer/transit next-hop lookups get),
     *  never extended past this cycle's remaining query budget, and zero
     *  when too little budget remains to fund a useful search.
     *  Pure decision — safe for unit tests.
     *
     *  @param remainingBudgetMs ms left in this cycle's query phase
     *  @param adaptiveCapMs current adaptive RouterInfo lookup deadline cap
     *  @return timeout in ms, or 0 when no useful lookup remains
     *  @since 0.9.71+
     */
    static int issueTimeout(long remainingBudgetMs, int adaptiveCapMs) {
        if (remainingBudgetMs < MIN_USEFUL_TIMEOUT_MS) {return 0;}
        return (int) Math.min(remainingBudgetMs, adaptiveCapMs);
    }
}
