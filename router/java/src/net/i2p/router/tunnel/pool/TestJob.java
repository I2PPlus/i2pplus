package net.i2p.router.tunnel.pool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionTag;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.Tuner;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.crypto.ratchet.MuxedPQSKM;
import net.i2p.router.crypto.ratchet.MuxedSKM;
import net.i2p.router.crypto.ratchet.RatchetSKM;
import net.i2p.router.crypto.ratchet.RatchetSessionTag;
import net.i2p.router.networkdb.kademlia.MessageWrapper;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Repeatedly tests a single tunnel for its lifetime to ensure it remains functional.
 * Sends a garlic-encrypted DeliveryStatusMessage through the tunnel and validates the reply.
 * Now includes adaptive backoff, avoids over-penalizing peers on transient failures,
 * and limits the number of concurrent tunnel tests to avoid overwhelming the router.
 *
 * First tests are not queued one job at a time: offers go to a bounded
 * first-test buffer ({@link #offerFirstTest}) that a single paced pump job
 * drains in small batches under the in-flight gates, so build bursts cannot
 * flood the job queue.  Retests reuse this instance via {@link #scheduleRetest}.
 */
public class TestJob extends JobImpl {
    private final Log _log;
    private final TunnelPool _pool;
    private final PooledTunnelCreatorConfig _cfg;
    private TunnelInfo _outTunnel;
    private TunnelInfo _replyTunnel;
    private SessionTag _encryptTag;
    private RatchetSessionTag _ratchetEncryptTag;
    private static final AtomicInteger __id = new AtomicInteger();
    private int _testId;
    /** Test period for the current round, computed once at send time so the
     *  reply is judged against the same window used to set the expiration. */
    private int _testPeriod;

    /**
     * Maximum number of times a test can be deferred (no partner tunnel available)
     * before forcing a test or marking the tunnel as failed.  Prevents test deadlock
     * where both inbound and outbound pools are degraded and neither can test.
     * @since 0.9.69+
     */
    private static final int MAX_DEFERRED = 3;
    private int _deferredCount = 0;

    private static volatile RouterContext _cfgCtx;
    private static volatile long _cfgRefreshed;
    private static volatile int _cachedMaxConcurrent;
    private static volatile int _cachedMinTestPeriod;
    private static volatile int _cachedMaxTestPeriod;
    private static volatile int _cachedMinTestDelay;
    private static volatile int _cachedMaxTestDelay;
    private static volatile double _cachedPoolCoverageThreshold;
    private static volatile int _cachedMaxExploratoryPerPool;
    private static volatile int _cachedMaxClientPerPool;
    private static volatile int _cachedBaseMaxQueued;
    private static volatile int _cachedHardLimit;
    private static final long CONFIG_REFRESH_MS = 30 * 1000L;

    /**
     * Tuned test-job params, -1 = use router config.
     * Set by the Tuner when autotuning the corresponding testJob params.
     * @since 0.9.71+
     */
    private static volatile int _tunedMinTestPeriod = -1;
    private static volatile int _tunedMaxTestPeriod = -1;
    private static volatile int _tunedMinTestDelay = -1;
    private static volatile int _tunedMaxTestDelay = -1;

    /**
     * Set the min test period (called by Tuner).
     * @param ms the min test period in ms
     * @since 0.9.71+
     */
    public static void setMinTestPeriod(int ms) { _tunedMinTestPeriod = ms; }
    /**
     * Set the max test period (called by Tuner).
     * @param ms the max test period in ms
     * @since 0.9.71+
     */
    public static void setMaxTestPeriod(int ms) { _tunedMaxTestPeriod = ms; }
    /**
     * Set the min test delay (called by Tuner).
     * @param ms the min test delay in ms
     * @since 0.9.71+
     */
    public static void setMinTestDelay(int ms) { _tunedMinTestDelay = ms; }
    /**
     * Set the max test delay (called by Tuner).
     * @param ms the max test delay in ms
     * @since 0.9.71+
     */
    public static void setMaxTestDelay(int ms) { _tunedMaxTestDelay = ms; }
    /**
     * The min test period in effect, tuned value if set else config.
     * @param ctx the router context
     * @return the min test period in ms
     * @since 0.9.71+
     */
    public static int getMinTestPeriod(RouterContext ctx) {
        int t = _tunedMinTestPeriod;
        if (t >= 0) return t;
        refreshTestJobConfig(ctx);
        return _cachedMinTestPeriod;
    }
    /**
     * The max test period in effect, tuned value if set else config.
     * @param ctx the router context
     * @return the max test period in ms
     * @since 0.9.71+
     */
    public static int getMaxTestPeriod(RouterContext ctx) {
        int t = _tunedMaxTestPeriod;
        if (t >= 0) return t;
        refreshTestJobConfig(ctx);
        return _cachedMaxTestPeriod;
    }
    /**
     * The min test delay in effect, tuned value if set else config.
     * @param ctx the router context
     * @return the min test delay in ms
     * @since 0.9.71+
     */
    public static int getMinTestDelay(RouterContext ctx) {
        int t = _tunedMinTestDelay;
        if (t >= 0) return t;
        refreshTestJobConfig(ctx);
        return _cachedMinTestDelay;
    }
    /**
     * The max test delay in effect, tuned value if set else config.
     * @param ctx the router context
     * @return the max test delay in ms
     * @since 0.9.71+
     */
    public static int getMaxTestDelay(RouterContext ctx) {
        int t = _tunedMaxTestDelay;
        if (t >= 0) return t;
        refreshTestJobConfig(ctx);
        return _cachedMaxTestDelay;
    }

    /**
     *  Refresh the cached test-job configuration from properties at most once
     *  per CONFIG_REFRESH_MS, or immediately when the context changes.
     *  Benign race: duplicate refreshes are idempotent writes.
     */
    private static void refreshTestJobConfig(RouterContext ctx) {
        long now = ctx.clock().now();
        if (_cfgCtx == ctx && now - _cfgRefreshed < CONFIG_REFRESH_MS)
            return;
        _cachedMaxConcurrent = ctx.getProperty("i2p.tunnel.testJob.maxConcurrent",
                                               SystemVersion.isSlow() ? 32 : 64);
        _cachedMinTestPeriod = ctx.getProperty("i2p.tunnel.testJob.minTestPeriod", 3*1000);
        _cachedMaxTestPeriod = ctx.getProperty("i2p.tunnel.testJob.maxTestPeriod", 15*1000);
        _cachedMinTestDelay = ctx.getProperty("i2p.tunnel.testJob.minTestDelay", 30*1000);
        _cachedMaxTestDelay = ctx.getProperty("i2p.tunnel.testJob.maxTestDelay", 90*1000);
        _cachedMaxExploratoryPerPool = ctx.getProperty("i2p.tunnel.testJob.maxExploratoryPerPool", 12);
        _cachedMaxClientPerPool = ctx.getProperty("i2p.tunnel.testJob.maxClientPerPool", 24);
        _cachedBaseMaxQueued = ctx.getProperty("i2p.tunnel.testJob.maxQueued",
                                               SystemVersion.isSlow() ? 64 : 96);
        _cachedHardLimit = ctx.getProperty("i2p.tunnel.testJob.hardLimit",
                                           SystemVersion.isSlow() ? 384 : 512);
        String val = ctx.getProperty("i2p.tunnel.testJob.poolCoverageThreshold");
        if (val != null) {
            try {
                _cachedPoolCoverageThreshold = Double.parseDouble(val);
            } catch (NumberFormatException e) {
                // fall through
                _cachedPoolCoverageThreshold = 0.95;
            }
        } else {
            _cachedPoolCoverageThreshold = 0.95;
        }
        _cfgCtx = ctx;
        _cfgRefreshed = now;
    }

    /**
     * Maximum number of tunnel tests that can run concurrently.
     * Prevents overwhelming the router with too many simultaneous tunnel tests.
     * This value can be adjusted based on system capacity.
     * Tunable via i2p.tunnel.testJob.maxConcurrent (default: 64 fast / 32 slow)
     * @return the max concurrent tests
     */
    private static int getMaxConcurrentTests(RouterContext ctx) {
        refreshTestJobConfig(ctx);
        return _cachedMaxConcurrent;
    }

    /**
     * The minimum test period from config or default (3s).
     * Tunable via i2p.tunnel.testJob.minTestPeriod (default: 3000).
     * @return the min test period
     */
    private int getMinTestPeriod() {
        return getMinTestPeriod(getContext());
    }

    /**
     * The maximum test period from config or default (15s).
     * Tunable via i2p.tunnel.testJob.maxTestPeriod (default: 15000).
     * @return the max test period
     */
    private int getMaxTestPeriod() {
        return getMaxTestPeriod(getContext());
    }

    // Adaptive testing frequency constants
    private static final int SUCCESS_HISTORY_SIZE = 3; // Track last 3 results
    private static final int MAX_LAG_FOR_SCHEDULE = 150;
    /** Hard ceiling on consecutive test failures for server pool tunnels.
     *  Without this, dead server pool tunnels accumulate indefinitely because
     *  incrementTestFailures() keeps them alive for the LS republish cycle.
     *  At 10+ failures the tunnel is clearly dead — force removal. */
    private static final int MAX_SERVER_POOL_TEST_FAILURES = 10;
    private static double getPoolCoverageThreshold(RouterContext ctx) {
        refreshTestJobConfig(ctx);
        return _cachedPoolCoverageThreshold;
    }
    private static int getMaxExploratoryPerPool(RouterContext ctx) {
        refreshTestJobConfig(ctx);
        return _cachedMaxExploratoryPerPool;
    }
    private static int getMaxClientPerPool(RouterContext ctx) {
        refreshTestJobConfig(ctx);
        return _cachedMaxClientPerPool;
    }

    /**
     *  Per-pool test budget for client pools, bounded by three knobs:
     *  the Tuner budget (dynamic), the pool-coverage cap (at most
     *  coverageThreshold of a pool's active tunnels under test at once),
     *  and the absolute per-pool cap.  With defaults the Tuner budget
     *  (effectively unlimited) is superseded by the coverage cap.
     *
     *  Takes a pre-fetched active count: callers that already needed it
     *  (criticality checks, zero-active bypasses) avoid a second O(tunnels)
     *  scan inside this method.
     *
     *  @param ctx the router context
     *  @param pool the client pool, never null
     *  @param activeCount the pool's active tunnel count, fetched by the caller
     *  @return maximum concurrent test claims for this pool
     *  @since 0.9.71+
     */
    private static int getClientPoolTestBudget(RouterContext ctx, TunnelPool pool, int activeCount) {
        int coverageCap = Math.max(1, (int) Math.ceil(activeCount * getPoolCoverageThreshold(ctx)));
        return Math.min(Math.min(Tuner.getTestClientBudget(), coverageCap), getMaxClientPerPool(ctx));
    }
    private static final float DEFAULT_SUCCESS_RATE = 0.5f; // 50% for new tunnels

    /**
     *  How recently data must have flowed through a tunnel for us to trust
     *  it over the test result.  If data was seen within this window, the
     *  test failure is treated as a false negative (reply-path issue).
     *  @since 0.9.69+
     */
    private static final long RECENT_TRAFFIC_MS = 30 * 1000L;

    /**
     *  How recently a tunnel must have carried real (non-test) traffic for
     *  the pool to clear its FAILING flag and mark it GOOD.  Real traffic is
     *  proof the tunnel works — a tunnel receiving or sending data has not
     *  failed.  Only FAILING (not FAILED) tunnels are cleared, so a genuinely
     *  dead tunnel still accumulates failures during quiet periods and is
     *  removed.
     *  @since 0.9.71+
     */
    static final long TRAFFIC_PROOF_MS = 2 * 60 * 1000L;

    /**
     *  How long after the last real traffic a tunnel test is held back.  Tests
     *  are deferred while the tunnel is carrying recent traffic: the traffic
     *  proves it works, and testing now would waste a job-queue slot and risk a
     *  false negative under load.  The defer is load-aware, not absolute —
     *  during an ebb in test traffic the retest runs anyway (see
     *  {@link #shouldDeferActiveGoodTunnel}).  This is the recency window that
     *  arms the defer; the wait itself is bounded by
     *  {@link #ACTIVE_GOOD_EBB_RETRY_MS}.
     *  @since 0.9.71+
     */
    static final long TRAFFIC_DEFER_MS = 3 * 60 * 1000L;

    /**
     *  Shortest wait before re-checking an active-GOOD deferral.  Below this
     *  the recheck is not worth a queue slot: it would fire almost
     *  immediately and re-evaluate the same contention it just saw.
     *  @since 0.9.71+
     */
    static final long EBB_RETRY_FLOOR_MS = 5 * 1000L;

    /**
     *  Longest a retest may be pushed out while its tunnel is busy.  Waiting
     *  the full {@link #TRAFFIC_DEFER_MS} window let the tunnel expire out
     *  from under the retest chain (the expiry pre-check compares against
     *  delay + 3 test periods, so a long delay makes scheduleRetest() give up
     *  and drop the test entirely); a bounded retry instead brings the job
     *  back to re-check contention while the tunnel is still alive.
     *  @since 0.9.71+
     */
    static final long ACTIVE_GOOD_EBB_RETRY_MS = 30 * 1000L;

    /**
     *  In-flight cap divisor defining an "ebb" for active-GOOD retests: when
     *  fewer than {@code maxConcurrent / ACTIVE_GOOD_EBB_DIVISOR} tests are
     *  on the wire there is spare capacity to verify a busy tunnel.  With the
     *  default cap of 64 the ebb is below 16 in flight, leaving the rest of
     *  the cap for first-test dispatch so untested tunnels keep priority.
     *  @since 0.9.71+
     */
    static final int ACTIVE_GOOD_EBB_DIVISOR = 4;

    /**
     * Maximum number of TestJob instances that should be queued before deferring new ones.
     * Prevents job queue saturation from too many waiting tunnel tests.
     * Tunable via i2p.tunnel.testJob.maxQueued (default: 96 fast / 64 slow).
     * Dynamically overridden by the Tuner at runtime; when it still holds the
     * built-in default, the configured property value is applied on first use.
     */
    private static final int DEFAULT_QUEUED_LIMIT = SystemVersion.isSlow() ? 64 : 96;

    public static volatile int maxQueuedTests = DEFAULT_QUEUED_LIMIT;

    /**
     *  Refresh {@link #maxQueuedTests} from the configured property when the
     *  Tuner has not overridden the static dynamically.
     */
    private static void applyConfiguredQueuedLimit(RouterContext ctx) {
        int configured = getBaseMaxQueuedTests(ctx);
        if (maxQueuedTests == DEFAULT_QUEUED_LIMIT && maxQueuedTests != configured) {
            maxQueuedTests = configured;
        }
    }

    /**
     *  Base max queued tests value, read from PROP or static default.
     *  @param ctx router context
     *  @return configured base max queued tests
     */
    private static int getBaseMaxQueuedTests(RouterContext ctx) {
        refreshTestJobConfig(ctx);
        return _cachedBaseMaxQueued;
    }

    /**
     * Hard limit for total TestJob instances (queued + active).
     * Above this threshold, no new tests are scheduled until count decreases.
     * Prevents ever-increasing backlogs that could cause job lag.
     *
     * Scales down under job queue pressure to prevent TestJobs from
     * starving critical router jobs, but never below half the base
     * limit — see {@link #scaleHardLimit(int, long)}.
     *
     * Tunable via i2p.tunnel.testJob.hardLimit (default: 512 fast / 384 slow)
     * @param ctx the router context
     * @return the pressure-scaled hard limit
     */
    public static int getHardLimit(RouterContext ctx) {
        refreshTestJobConfig(ctx);
        // Scale down under job queue pressure so TestJobs don't starve
        // critical jobs like lease set renewal, tunnel building, and
        // database lookups.
        return scaleHardLimit(_cachedHardLimit, ctx.jobQueue().getMaxLag());
    }

    /**
     *  Scale the TestJob hard limit down when the job queue is lagging, with
     *  the scale-down floored at half the base limit.  Quartering under
     *  sustained lag starved the tunnel test queue — UNTESTED tunnels piled
     *  up while the shrunken limit blocked scheduling, and the pool never
     *  recovered enough GOOD tunnels to publish — so extreme lag now gets
     *  the same half-limit response as moderate lag.
     *
     *  @param base configured hard limit; values &lt;= 0 pass through untouched
     *  @param maxLag current job queue max lag (ms)
     *  @return base unchanged when lag is at or below 5s (or base is
     *          non-positive), otherwise at least {@code base / 2}
     *  @since 0.9.71+
     */
    static int scaleHardLimit(int base, long maxLag) {
        if (base <= 0 || maxLag <= 5_000) {
            return base;
        }
        return Math.max(base / 2, 1);
    }

    /**
     * Runtime state owned by a single router context: the first-test buffer
     * and its pump flag, the instance and in-flight gauges, and the
     * running-test map.  State is keyed by context because RouterContext
     * documents multiple router instances in one JVM — a static buffer would
     * let a pump for context A drain context B's candidates using A's clock,
     * queue, stats, and dispatcher.
     *
     * @since 0.9.71+
     */
    static final class BatchState {
        /**
         * TestJob instances (queued + running) in this context; the memory
         * bound checked against {@link #getHardLimit(RouterContext)}.
         */
        final AtomicInteger totalTestJobs = new AtomicInteger();

        /**
         * Which tunnels currently have a test instance registered, so a
         * duplicate cannot be created.  Key: tunnel key, Value: the instance.
         */
        final ConcurrentHashMap<Long, TestJob> runningTests =
                new ConcurrentHashMap<Long, TestJob>();

        /**
         * Outstanding test-instance claims per pool, one per created TestJob
         * (queued or running), released exactly once when the instance
         * terminates.  Feeds the per-pool budget in {@link #shouldSchedule}.
         * Key: pool identifier, Value: outstanding claims in that pool.
         */
        final ConcurrentHashMap<String, AtomicInteger> poolTestCounts =
                new ConcurrentHashMap<String, AtomicInteger>();

        /**
         * Tests dispatched to the network and awaiting a reply or timeout,
         * across all pools.  Unlike runningTests (which also counts delayed
         * retests parked in the queue), this measures pressure actually on
         * the wire and is the capacity gate for the batched first-test pump.
         */
        final AtomicInteger inFlight = new AtomicInteger();

        /**
         * Per-pool dispatched tests awaiting completion, for the client and
         * exploratory per-pool budget gates.
         * Key: pool identifier, Value: in-flight tests in that pool.
         */
        final ConcurrentHashMap<String, AtomicInteger> poolInFlight =
                new ConcurrentHashMap<String, AtomicInteger>();

        /** Guards firstTestBuffer and bufferedKeys. */
        final Object bufferLock = new Object();

        /** First-test candidates awaiting batched dispatch, oldest first. */
        final Deque<PendingTest> firstTestBuffer = new ArrayDeque<PendingTest>();

        /** Tunnel keys currently in firstTestBuffer, for offer-time dedupe. */
        final Set<Long> bufferedKeys = new HashSet<Long>();

        /** Set while a PumpJob is queued or running; cleared when the buffer drains. */
        final AtomicBoolean pumpQueued = new AtomicBoolean();

        /** Last time a batch-denial INFO line was logged (global rate limit). */
        final AtomicLong lastDenialLog = new AtomicLong();

        /**
         * True while a restart or shutdown is draining the subsystem: offers
         * and dispatch are refused until the reset finishes.
         */
        volatile boolean quiesced;
    }

    /**
     * One {@link BatchState} per router context.  Identity-keyed, since
     * RouterContext does not override equals; production holds exactly one
     * entry and tests hold one per mock context.
     */
    private static final ConcurrentHashMap<RouterContext, BatchState> BATCH_STATES =
            new ConcurrentHashMap<RouterContext, BatchState>();

    /**
     * The runtime state for a router context, created on first use.  Every
     * buffer, gauge, permit, and pump flag lives in the returned state, so
     * two contexts can never observe each other's dispatch state.
     *
     * @param ctx the router context, never null
     * @return that context's batch state, never null
     * @since 0.9.71+
     */
    static BatchState batchState(RouterContext ctx) {
        BatchState state = BATCH_STATES.get(ctx);
        if (state != null) return state;
        BatchState created = new BatchState();
        BatchState prev = BATCH_STATES.putIfAbsent(ctx, created);
        return prev != null ? prev : created;
    }

    /**
     * Register the batch-dispatch rate stats this class writes, so
     * {@code addRateData()} records real rates instead of reporting invalid
     * names.  Called once per router context from
     * {@code TunnelPoolManager}'s constructor.
     *
     *  <p>Units: every batch rate is an event count (value 1 per event), so a
     *  rate of N means N events in the period.  {@code testBufferOffered} and
     *  {@code testBufferRejected} count candidates, {@code testBufferDropped}
     *  counts candidates evicted or discarded as stale, {@code
     *  testBatchDenied} counts drain passes refused by a gate, and {@code
     *  testBatchDispatched} counts rounds actually sent.
     *
     * @param stats the context's stat manager, never null
     * @param periods the rate periods to record on, never null
     * @since 0.9.71+
     */
    static void registerBatchStats(StatManager stats, long[] periods) {
        stats.createRequiredRateStat("tunnel.testBufferOffered",
                "First-test candidates accepted into the batch buffer (count)",
                "Tunnels", periods);
        stats.createRequiredRateStat("tunnel.testBufferDropped",
                "First-test candidates evicted or discarded (count)",
                "Tunnels", periods);
        stats.createRequiredRateStat("tunnel.testBufferRejected",
                "Buffered candidates refused at dispatch (count)",
                "Tunnels", periods);
        stats.createRequiredRateStat("tunnel.testBatchDenied",
                "Batch drain passes denied by the in-flight gates (count)",
                "Tunnels", periods);
        stats.createRequiredRateStat("tunnel.testBatchDispatched",
                "Test rounds dispatched by the batch pump (count)",
                "Tunnels", periods);
    }

    /**
     * Start a per-context reset of the batch-dispatch subsystem: a router
     * restart, as called from {@code TunnelPoolManager.restart()} before the
     * pools are torn down.  Offers and dispatch are refused while the state is
     * quiesced, every live instance is cancelled (its pending message, its
     * round, and its slots), and the first-test buffer, its key set, the pump
     * flag, and the gauges are cleared — a buffered candidate or an inflated
     * gauge would otherwise outlive the tunnels it describes.
     *
     * <p>Must be paired with {@link #endBatchReset(RouterContext)}; until then
     * the context accepts no new test work.
     *
     * @param ctx the router context
     * @since 0.9.71+
     */
    static void beginBatchReset(RouterContext ctx) {
        BatchState state = batchState(ctx);
        // Quiesce first, so a claim taken while cancelling survives neither
        // this loop nor the gauge normalization below.
        state.quiesced = true;

        // Cancel every reachable instance.  The copy keeps the iteration safe
        // as each cancel unregisters itself from runningTests.
        for (TestJob job : new ArrayList<TestJob>(state.runningTests.values())) {
            job.cancelForReset();
        }

        // Empty the buffer: its candidates reference tunnels and pools the
        // restart is about to replace.
        synchronized (state.bufferLock) {
            state.firstTestBuffer.clear();
            state.bufferedKeys.clear();
        }
        state.pumpQueued.set(false);

        // Normalize the gauges.  The cancel loop already released what those
        // instances held; anything left belongs to an instance that never
        // registered a tunnel key and would otherwise hold its slot across
        // the whole restart.  Releases saturate at zero, so an instance that
        // completes after the reset cannot drive a gauge negative.
        state.runningTests.clear();
        state.totalTestJobs.set(0);
        state.poolTestCounts.clear();
        state.inFlight.set(0);
        state.poolInFlight.clear();
    }

    /**
     * Finish a reset started by {@link #beginBatchReset(RouterContext)}:
     * resume offers and pump dispatch.  The buffer was emptied by the reset,
     * so there is nothing queued to revive — the first new offer restarts the
     * pump.
     *
     * @param ctx the router context
     * @since 0.9.71+
     */
    static void endBatchReset(RouterContext ctx) {
        batchState(ctx).quiesced = false;
    }

    /**
     * The job queue is going away.  Its shutdown clears queued jobs without
     * running {@link #dropped()} or their timeout callbacks, so anything the
     * queue held would otherwise leak its slots, keep its pending selector,
     * and leave the pump flag set forever.  Reset the context's batch state
     * so those leaks cannot outlive the queue; there is no matching
     * {@code endBatchReset} because a dead queue never accepts work again
     * (offers are additionally refused by the {@code isAlive()} check).
     *
     * @param ctx the router context
     * @since 0.9.71+
     */
    public static void onJobQueueShutdown(RouterContext ctx) {
        beginBatchReset(ctx);
    }

    /**
     * Generate a unique key for a tunnel to track running tests.
     * Uses combination of receive and send tunnel IDs.
     * @param cfg the tunnel configuration
     * @return unique key for the tunnel, or null if unavailable
     */
    private static Long getTunnelKey(PooledTunnelCreatorConfig cfg) {
        if (cfg == null) return null;
        try {
            if (cfg.isInbound()) {
                long recvId = cfg.getReceiveTunnelId(0).getTunnelId();
                return Long.valueOf(recvId);
            } else {
                long sendId = cfg.getSendTunnelId(0).getTunnelId();
                return Long.valueOf(sendId);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Generate a stable, collision-free identifier for a tunnel pool, used as
     * the key for the per-pool instance and in-flight gauges.  Distinct pools
     * must never share a key or their budgets merge: client pools are keyed by
     * the full destination hash plus direction (nicknames may be shared and an
     * eight-character base32 prefix is not unique), while exploratory pools
     * have exactly one inbound and one outbound instance per context.
     *
     * @param pool the tunnel pool
     * @return a stable identifier for the pool, never null
     * @since 0.9.71+
     */
    static String getPoolId(TunnelPool pool) {
        if (pool == null) return "unknown";
        String direction = pool.getSettings().isInbound() ? "inbound" : "outbound";
        if (pool.getSettings().isExploratory()) {
            return "exploratory-" + direction;
        }
        Hash destination = pool.getSettings().getDestination();
        if (destination == null) {
            return "client-nodest-" + direction;
        }
        return "client-" + destination.toBase64() + "-" + direction;
    }

    /** Flag to indicate if this job is valid and should be queued */
    private boolean _valid = true;

    /**
     * The instance slots this job owns: the total counter, the per-pool claim
     * (with the pool id captured when it was taken), and the running-test
     * registration.  Every release goes through here so a claim is dropped at
     * most once and only by the instance that took it, even when the pool id
     * would differ today because pool settings changed mid-flight.
     */
    private volatile InstanceClaims _claims;

    /**
     * The round currently in flight on this instance, or the most recently
     * completed one.  Reply and timeout callbacks capture the token they were
     * created with and only complete it while it is still the active round, so
     * a delayed callback from an earlier retest can never complete, release,
     * or clear state belonging to a newer round.
     */
    private volatile RoundToken _round;

    /**
     * The last pending message registered for this instance's round, kept so a
     * reset or drop can unregister it even when the round was registered but
     * never armed (permit refusal).  Re-registering overwrites it; the
     * registry's unregister is idempotent, so a stale reference is harmless.
     */
    private volatile OutNetMessage _pendingMessage;

    /** Monotonic generation for round tokens; diagnostics only. */
    private final AtomicLong _roundGen = new AtomicLong();

    /**
     * Set when a restart, shutdown, or queue death cancelled this instance
     * while it was still queued.  A cancelled instance must not start a round
     * or requeue itself when the job queue eventually runs it.
     */
    private volatile boolean _terminated;

    /**
     * Current number of queued + active test jobs across all router contexts,
     * for capacity planning by {@code BuildExecutor}.
     *
     * @return the current test job count
     * @since 0.9.69+
     */
    public static int getCurrentTestJobCount() {
        int total = 0;
        for (BatchState state : BATCH_STATES.values()) {
            total += state.totalTestJobs.get();
        }
        return total;
    }

    /**
     * Maximum number of queued test jobs allowed before deferring.
     * @return the max test jobs
     * @since 0.9.69+
     */
    public static int getMaxTestJobs() {
        return maxQueuedTests;
    }

    /** This job's per-context runtime state. */
    private BatchState state() {
        return batchState(getContext());
    }

    /**
     * Static method to check if a TestJob should be created and scheduled.
     * This prevents creating invalid job objects that would have timing issues.
     * Note: the standard first-test entry points (build complete, stranded
     * sweep, last chance) now route through {@link #offerFirstTest}, which
     * applies the in-flight gates at drain time; this gate remains for
     * direct scheduling.
     *
     * <p>Advisory only: it decides whether an instance is worth creating but
     * claims nothing, so a rejection needs no rollback.  The atomic slot
     * claim happens once, in {@link #claimBatchSlot}, when the instance is
     * actually constructed — a read-only check can only be stale, never leak.
     *
     * @param ctx the router context
     * @param cfg the tunnel config
     * @return true if the job should be created and scheduled, false otherwise
     */
    public static boolean shouldSchedule(RouterContext ctx, PooledTunnelCreatorConfig cfg) {
        // Never schedule while a restart is draining the batch subsystem or
        // the job queue is dead: the job would sit unrunnable while holding
        // its instance slots, or be silently discarded by addJob().
        BatchState state = batchState(ctx);
        if (state.quiesced || !ctx.jobQueue().isAlive()) {
            return false;
        }

        // Skip testing if tunnel doesn't have valid IDs yet (not fully built).
        // Outbound tunnels only have a send tunnel ID at hop 0 (the gateway);
        // inbound tunnels only have a receive tunnel ID at hop 0.
        if (!hasValidTunnelIds(cfg)) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldDebug()) {
                log.debug("Skipping test - tunnel not ready: " + cfg);
            }
            return false;
        }

        // Skip tunnel testing for ping tunnels - they're short-lived and don't need testing
        TunnelPool pool = cfg.getTunnelPool();
        if (isPingTunnel(cfg)) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldDebug()) {
                log.debug("Skipping test scheduling for ping tunnel: " +
                          pool.getSettings().getDestinationNickname());
            }
            return false;
        }

        // Skip testing if tunnel is scheduled for early expiry (already pruned)
        long now = ctx.clock().now();
        if (isEarlyExpiry(cfg, now)) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldDebug()) {
                log.debug("Skipping test - tunnel scheduled for early expiry: " + cfg);
            }
            return false;
        }

        // First-ever test — schedule immediately for critical pools, but
        // respect the adaptive queue cap for non-critical ones.  Without a
        // cap here, newly built untested tunnels keep adding test jobs past
        // the limit, which prevents retests from running and causes tunnels
        // to stay UNTESTED indefinitely (a death spiral: UNTESTED tunnels
        // inflate the addTunnel() total, block new GOOD builds, and the pool
        // can never recover enough active tunnels to become non-critical).
        boolean isFirstTest = (cfg.getTestStatus() == TunnelTestStatus.UNTESTED);
        if (isFirstTest) {
            int current = state.totalTestJobs.get();
            // Critical pools (0 GOOD) always get through, non-critical ones
            // wait until queue headroom frees up.
            if (current >= maxQueuedTests && !isZeroActivePool(pool)) {
                return false;
            }
            if (pool != null && !pool.getSettings().isExploratory()) {
                int activeCount = pool.getActiveTunnelCount();
                if (activeCount > 0 && !hasPoolTestHeadroom(state, ctx, pool, activeCount)) {
                    return false;
                }
            }
            Long tunnelKey = getTunnelKey(cfg);
            if (tunnelKey != null && state.runningTests.containsKey(tunnelKey)) {
                return false;
            }
            if (state.runningTests.size() >= getMaxConcurrentTests(ctx)) {
                return false;
            }
            return canClaimInstance(state.totalTestJobs.get(), getHardLimit(ctx));
        }

        // Check if job queue is overloaded - skip scheduling if queue is backing up
        int readyCount = ctx.jobQueue().getReadyCount();
        long maxLag = ctx.jobQueue().getMaxLag();
        int activeRunners = ctx.jobQueue().getActiveRunnerCount();
        int numPools;
        if (pool != null) {
            List<TunnelPool> poolList = new ArrayList<>();
            ctx.tunnelManager().listPools(poolList);
            numPools = poolList.size();
        } else {
            numPools = 0;
        }
        int maxTestJobs = computeMaxTestJobs(maxQueuedTests, activeRunners, numPools);
        int currentTestJobs = state.totalTestJobs.get();
        boolean isCritical = false;
        if (pool != null && !pool.getSettings().isExploratory()) {
            int activeCount = pool.getActiveTunnelCount();
            int target = pool.getSettings().getTotalQuantity();
            isCritical = isPoolCritical(activeCount, target);
            // Deficit pools (below target but not yet critical) also run
            // expedited — the pool is under-filled exactly when its UNTESTED
            // backlog is holding publication back, so its tests go first
            // (higher lag tolerance, larger job allowance).  Queue-cap and
            // budget bypass stay reserved for critical pools only.
            if ((isCritical || isPoolDeficit(activeCount, target)) && !cfg.needsExpeditedTest()) {
                cfg.requestExpeditedTest();
            }
        }
        if (!cfg.needsExpeditedTest() && isZeroActivePool(pool)) {
            cfg.requestExpeditedTest();
        }
        boolean isExpedited = cfg.needsExpeditedTest();
        long expeditedLagLimit = isExpedited ? MAX_LAG_FOR_SCHEDULE * 2 : MAX_LAG_FOR_SCHEDULE;
        int expeditedJobLimit = isExpedited ? maxTestJobs + maxTestJobs / 2 : maxTestJobs;
        if (isTestQueueOverloaded(isCritical, readyCount, activeRunners, maxLag, expeditedLagLimit,
                                  currentTestJobs, expeditedJobLimit)) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldInfo()) {
                if (maxLag > expeditedLagLimit) {
                    log.info("High max Job queue lag (" + maxLag + "ms) -> Not scheduling test for " + cfg);
                } else {
                    log.info("Too many test jobs scheduled or running -> Not scheduling test for " + cfg);
                }
            }
            return false;
        }

        int current = state.totalTestJobs.get();
        if (!isCritical && current >= maxQueuedTests) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldInfo()) {
                log.info("Limit (" + maxQueuedTests + ") reached -> Not scheduling test for " + cfg);
            }
            return false;
        }
        if (!canClaimInstance(current, getHardLimit(ctx))) {
            return false;
        }

        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey != null && state.runningTests.containsKey(tunnelKey)) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldDebug()) {
                log.debug("Test already running for tunnel key " + tunnelKey + " -> Skipping duplicate test for " + cfg);
            }
            return false;
        }

        if (state.runningTests.size() >= getMaxConcurrentTests(ctx)) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldDebug()) {
                log.debug("Concurrent test limit (" + getMaxConcurrentTests(ctx) + ") reached -> Not scheduling test for " + cfg);
            }
            return false;
        }

        if (pool != null) {
            // Critical client pools (0 active tunnels) bypass the per-pool
            // budget.  UNTESTED tunnels must always get test priority to
            // prevent pool collapse — without tested tunnels, the LeaseSet
            // expires and the destination becomes unreachable.  Exploratory
            // pools use a fixed cap and never pay the O(tunnels) scan.
            boolean exploratory = pool.getSettings().isExploratory();
            int activeCount = exploratory ? 0 : pool.getActiveTunnelCount();
            if ((exploratory || activeCount > 0)
                    && !hasPoolTestHeadroom(state, ctx, pool, activeCount)) {
                Log log = ctx.logManager().getLog(TestJob.class);
                if (log.shouldDebug()) {
                    log.debug("Pool " + getPoolId(pool) + " at per-pool test budget -> Deferring");
                }
                return false;
            }
        }

        return true;
    }

    /**
     * Whether the per-pool instance budget still has room for one more test
     * in {@code pool}.  Read-only twin of {@link #claimPoolTestSlot}: the
     * caller may be stale by the time it claims, but it can never leak a
     * claim by rejecting.
     *
     * @param state the context's batch state, never null
     * @param ctx the router context (budget configuration)
     * @param pool the pool to check, never null
     * @param activeCount pool.getActiveTunnelCount() already read by the caller
     * @return true when one more test fits under the pool's budget
     * @since 0.9.71+
     */
    static boolean hasPoolTestHeadroom(BatchState state, RouterContext ctx,
                                       TunnelPool pool, int activeCount) {
        int budget = pool.getSettings().isExploratory()
                ? getMaxExploratoryPerPool(ctx)
                : getClientPoolTestBudget(ctx, pool, activeCount);
        AtomicInteger count = state.poolTestCounts.get(getPoolId(pool));
        return count == null || count.get() < budget;
    }

    /**
     * Sole authority for allocating a test instance slot: a slot is available
     * while the current count is strictly below the hard limit.  The bound is
     * the read itself, not a separate pre-check, so a caller can never observe
     * "under the limit", act, and leave the count at limit + 1.
     *
     * @param current the current instance count
     * @param hardLimit the configured maximum instance count
     * @return true when one more instance fits
     * @since 0.9.71+
     */
    static boolean canClaimInstance(int current, int hardLimit) {
        return current < hardLimit;
    }

    /**
     * Claim one per-pool test-instance slot for {@code poolId} in a router
     * context's runtime state.  The claim is done inside the map's compute()
     * so it can never land on a counter that a concurrent
     * {@link #releasePoolTestSlot} just removed (which would silently lose
     * the claim and weaken the pool budget).
     *
     * @param state the context's batch state, never null
     * @param poolId pool key, never null
     * @return the pool's claim count after this claim; exact when no other
     *         claim or release interleaves, otherwise a close reading used
     *         only for the soft budget check
     * @since 0.9.71+
     */
    static int claimPoolTestSlot(BatchState state, String poolId) {
        AtomicInteger count = state.poolTestCounts.compute(poolId, (k, c) -> {
            AtomicInteger a = c;
            if (a == null) a = new AtomicInteger();
            a.incrementAndGet();
            return a;
        });
        return count.get();
    }

    /**
     * Release one per-pool test-instance slot claimed by
     * {@link #claimPoolTestSlot}, removing the counter at zero so the map
     * stays bounded by the number of pools.  Decrement and removal happen in
     * one computeIfPresent() step: a plain decrement-then-remove can unlink a
     * counter a concurrent claim just incremented, losing the claim.
     *
     * @param state the context's batch state, never null
     * @param poolId pool key, never null
     * @since 0.9.71+
     */
    static void releasePoolTestSlot(BatchState state, String poolId) {
        state.poolTestCounts.computeIfPresent(poolId, (k, c) -> c.decrementAndGet() > 0 ? c : null);
    }

    /**
     * Whether the tunnel config has non-zero tunnel IDs at hop 0, i.e. the
     *  tunnel is fully built and can carry a test message.  Outbound tunnels
     *  only have a send tunnel ID at hop 0 (the gateway), inbound tunnels only
     *  a receive tunnel ID.  Any failure reading the IDs (not yet built) is
     *  treated as not ready; the caller logs the debug detail.
     *
     *  @param cfg the tunnel config, never null
     *  @return true if the applicable hop-0 ID is non-zero
     *  @since 0.9.71+
     */
    static boolean hasValidTunnelIds(PooledTunnelCreatorConfig cfg) {
        try {
            if (cfg.isInbound()) {
                return cfg.getReceiveTunnelId(0).getTunnelId() != 0;
            }
            return cfg.getSendTunnelId(0).getTunnelId() != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     *  Whether the tunnel belongs to a ping pool, which is short-lived and
     *  does not need testing.  Ping pools are named "I2Ping" or "Ping*[n]".
     *
     *  @param cfg the tunnel config, never null
     *  @return true if the pool nickname identifies a ping pool
     *  @since 0.9.71+
     */
    static boolean isPingTunnel(PooledTunnelCreatorConfig cfg) {
        TunnelPool pool = cfg.getTunnelPool();
        if (pool == null) return false;
        String nickname = pool.getSettings().getDestinationNickname();
        return nickname != null && (nickname.equals("I2Ping") ||
               (nickname.startsWith("Ping") && nickname.contains("[")));
    }

    /**
     *  Whether the tunnel expires so soon it is not worth testing — it has
     *  already been pruned for early expiry.
     *
     *  <p>A tunnel the pool explicitly admitted for a last-chance test is
     *  exempt until that admission's deadline: the sweep keeps stale UNTESTED
     *  tunnels with {@link TunnelPool#LAST_CHANCE_MIN_LIFE_MS} (60s) of life
     *  precisely so they get one first test, while this gate would otherwise
     *  drop anything inside the 120s early-expiry window the moment the test
     *  reaches the front of the queue.
     *
     *  @param cfg the tunnel config, never null
     *  @param now current time in ms
     *  @return true if the tunnel expires within the early-expiry window
     *          and no last-chance admission covers it
     *  @since 0.9.71+
     */
    static boolean isEarlyExpiry(PooledTunnelCreatorConfig cfg, long now) {
        if (cfg.isLastChanceAdmitted(now)) {
            return false;
        }
        return cfg.getExpiration() < now + TunnelPool.DEFAULT_PRUNE_EARLY_EXPIRY;
    }

    /**
     *  Whether a client pool has zero active tunnels, i.e. it is collapsed
     *  and its test jobs must bypass the queue cap and per-pool budget.
     *  Exploratory pools are never critical — they have no LeaseSet to feed.
     *
     *  @param pool the pool, may be null (then not critical)
     *  @return true if a non-exploratory pool with no active tunnels
     *  @since 0.9.71+
     */
    static boolean isZeroActivePool(TunnelPool pool) {
        return pool != null && !pool.getSettings().isExploratory() &&
               pool.getActiveTunnelCount() == 0;
    }

    /**
     *  Whether a pool is critically low on tunnels: no active tunnels at all,
     *  or at most 2 active against a larger target.  Critical pools get test
     *  priority so replacement tunnels are validated before the pool drains.
     *  NOTE: BuildExecutor.calculatePairedBuilds() carries an identical
     *  inline predicate — dedupe candidate if either side changes again.
     *
     *  @param activeCount current active tunnel count
     *  @param target configured total quantity
     *  @return true if the pool is critical
     *  @since 0.9.71+
     */
    static boolean isPoolCritical(int activeCount, int target) {
        return activeCount == 0 || (activeCount < target && activeCount <= 2);
    }

    /**
     *  Whether a pool is running below its target active-tunnel count.
     *  Deficit pools get expedited test treatment (see the expedited
     *  request in {@link #shouldSchedule}) so their UNTESTED backlog drains
     *  before the pool drops further behind — but unlike
     *  {@link #isPoolCritical(int, int)} they do not bypass queue caps or
     *  per-pool budgets, which stay reserved for collapsed pools.
     *
     *  @param activeCount current active tunnel count
     *  @param target configured total quantity
     *  @return true when the pool has fewer active tunnels than its target
     *  @since 0.9.71+
     */
    static boolean isPoolDeficit(int activeCount, int target) {
        return activeCount < target;
    }

    /**
     *  Test-job capacity derived from the queue state: at least 12 jobs (or
     *  3 per pool), scaled up by active runners, but never above the hard
     *  queued limit.
     *
     *  @param maxQueuedTests hard cap from {@link #maxQueuedTests}
     *  @param activeRunners current job-queue runner count
     *  @param numPools number of tunnel pools
     *  @return the capacity in test jobs
     *  @since 0.9.71+
     */
    static int computeMaxTestJobs(int maxQueuedTests, int activeRunners, int numPools) {
        return Math.min(maxQueuedTests, Math.max(activeRunners, Math.max(numPools * 3, 12)));
    }

    /**
     *  Whether the job queue is too backed up to accept another test job.
     *  Non-critical pools are deferred when the ready queue outruns the
     *  runners, the max lag exceeds the (possibly expedited) limit, or the
     *  current test job count meets the (possibly expedited) job limit.
     *  Critical pools always get through.
     *
     *  @param critical whether the pool is critical (bypasses the gate)
     *  @param readyCount jobs waiting in the queue
     *  @param activeRunners jobs being processed
     *  @param maxLag measured queue lag in ms
     *  @param expeditedLagLimit lag threshold (doubled for expedited)
     *  @param currentTestJobs running test job count
     *  @param expeditedJobLimit job threshold (1.5x for expedited)
     *  @return true if scheduling should be deferred
     *  @since 0.9.71+
     */
    static boolean isTestQueueOverloaded(boolean critical, int readyCount, int activeRunners,
                                         long maxLag, long expeditedLagLimit,
                                         int currentTestJobs, int expeditedJobLimit) {
        return !critical && (readyCount > activeRunners ||
                             maxLag > expeditedLagLimit ||
                             currentTestJobs >= expeditedJobLimit);
    }

    // ---------------- Batched first-test dispatch ----------------
    //
    //  First tests (fresh builds, stranded sweeps, last-chance offers) flow
    //  through a bounded buffer drained by a single PumpJob instead of one
    //  queue entry per tunnel.  The pump claims candidates under the
    //  in-flight gates and runs their TestJob directly, so draining a large
    //  UNTESTED backlog costs one ready-queue slot per batch — paced by
    //  queue health — instead of flooding the queue and having the queued
    //  cap drop (and lose) the excess.

    /**
     * Maximum first-test candidates held in the batch buffer.  When full the
     * oldest is evicted, which simply returns that tunnel to the unbuffered
     * state: recovery depends on the pool sweep re-offering it, so the buffer
     * is best-effort, not lossless — an eviction delays a test rather than
     * discarding it, but only for as long as the sweep runs again.
     * @since 0.9.71+
     */
    static final int MAX_BUFFERED_FIRST_TESTS = 128;

    /**
     * Candidates claimed from the buffer per pump run.  Small batches keep
     * a burst from hitting the network at once while still retiring up to
     * this many tunnels per ready-queue slot.
     * @since 0.9.71+
     */
    static final int PUMP_BATCH_SIZE = 4;

    /**
     * Candidates popped from the buffer per drain, larger than the dispatch
     * batch so a candidate that is denied only for its own pool can be
     * rotated to the tail while the scan continues to the candidates behind
     * it, instead of blocking the whole run at the head.
     * @since 0.9.71+
     */
    static final int DRAIN_SCAN_LIMIT = PUMP_BATCH_SIZE * 2;

    /**
     * Stranded-UNTESTED candidates a pool sweep offers per pass — bounds one
     * ensure cycle's contribution to the buffer.
     * @since 0.9.71+
     */
    static final int MAX_STRANDED_OFFERS_PER_SWEEP = 32;

    /** Pump requeue delay while the job queue is quiet. @since 0.9.71+ */
    static final long PUMP_DELAY_HEALTHY_MS = 250;

    /** Pump requeue delay while the job queue is busy. @since 0.9.71+ */
    static final long PUMP_DELAY_BUSY_MS = 1000;

    /** Pump requeue delay when the job queue is badly lagging. @since 0.9.71+ */
    static final long PUMP_DELAY_LAGGED_MS = 3000;

    /** Job-queue lag above which the pump uses the busy delay (ms). @since 0.9.71+ */
    static final long PUMP_BUSY_LAG_MS = MAX_LAG_FOR_SCHEDULE;

    /** Job-queue lag above which the pump uses the lagged delay (ms). @since 0.9.71+ */
    static final long PUMP_LAGGED_LAG_MS = 3000;

    /** Minimum spacing between batch-denial INFO lines (ms). @since 0.9.71+ */
    static final long DENIAL_LOG_INTERVAL_MS = 3 * 60 * 1000L;

    /**
     * A first-test candidate waiting in the batch buffer.
     */
    static final class PendingTest {
        final PooledTunnelCreatorConfig cfg;
        final TunnelPool pool;
        /**
         * Tunnel key captured at offer time.  Held so the buffer can be
         * de-duplicated and evicted consistently even if the config's state
         * changes while it waits (or the key read fails later), which keeps
         * bufferedKeys an exact mirror of the buffer contents.
         */
        final Long key;
        /**
         * Pool identity captured at offer time, so a buffered candidate can
         * be re-verified against its own pool at drain time without trusting
         * a possibly-null cfg.getTunnelPool() read.
         */
        final String poolId;

        PendingTest(PooledTunnelCreatorConfig cfg, TunnelPool pool) {
            this.cfg = cfg;
            this.pool = pool;
            this.key = getTunnelKey(cfg);
            this.poolId = getPoolId(pool);
        }
    }

    /**
     * Offer a tunnel's first test to the batched dispatch buffer instead of
     * creating a queue entry per tunnel.  All first-test producers (fresh
     * builds, stranded-UNTESTED sweeps, last-chance offers) funnel through
     * here: the buffer absorbs bursts, a single {@link PumpJob} drains it in
     * small batches paced by queue health, and the in-flight gates decide
     * what actually dispatches.  Candidates are validated at admission by
     * {@link #shouldRejectAtOffer}, so a stale, built-but-rejected, or no
     * longer owned config never evicts a valid one from the bounded buffer;
     * duplicate offers of the same tunnel key are dropped as well.  Gates are
     * re-evaluated at drain time, so a denial here is only a later retry.
     * Every call also (re)starts the pump when it is idle, so a pump lost to
     * queue overload is revived by the next offer (the sweeps re-offer at
     * least every minute).
     *
     * @param ctx the router context
     * @param cfg the tunnel config (first test only; retests keep the
     *            existing queue path so delayed retests never sit in the buffer)
     * @param pool the pool owning the tunnel; may be null (read from cfg)
     * @return true if the candidate was newly buffered
     * @since 0.9.71+
     */
    static boolean offerFirstTest(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        if (ctx == null || cfg == null) return false;
        if (ctx.router().gracefulShutdownInProgress()) return false;
        BatchState state = batchState(ctx);
        // Refuse new work while a restart drains the subsystem or the queue
        // is gone: a buffered candidate would sit unreachable, holding a
        // buffer slot nobody will ever drain.
        if (state.quiesced || !ctx.jobQueue().isAlive()) return false;
        if (pool == null) pool = cfg.getTunnelPool();
        boolean rejected = shouldRejectAtOffer(ctx, cfg, pool);
        if (rejected) {
            // The pool already dropped or never took this build; say so at
            // debug so a silently refused build is still traceable.
            Log log = ctx.logManager() != null ? ctx.logManager().getLog(TestJob.class) : null;
            if (log != null && log.shouldDebug()) {
                log.debug("Not buffering first test for " + cfg);
            }
        }
        PendingTest pending = new PendingTest(cfg, pool);
        Long key = pending.key;
        boolean newlyBuffered = false;
        if (!rejected && key != null && !state.runningTests.containsKey(key)) {
            List<PendingTest> evicted = Collections.emptyList();
            synchronized (state.bufferLock) {
                if (state.bufferedKeys.add(key)) {
                    evicted = offerBounded(state.firstTestBuffer, pending, MAX_BUFFERED_FIRST_TESTS);
                    for (PendingTest old : evicted) {
                        if (old.key != null) {
                            state.bufferedKeys.remove(old.key);
                        }
                    }
                    newlyBuffered = true;
                }
            }
            // Stats outside the lock: addRateData takes stat-manager locks the
            // buffer must never sit behind.
            for (int i = 0; i < evicted.size(); i++) {
                ctx.statManager().addRateData("tunnel.testBufferDropped", 1);
            }
            if (newlyBuffered) {
                ctx.statManager().addRateData("tunnel.testBufferOffered", 1);
            }
        }
        // Revive the pump on every offer, including deduped ones: a pump
        // dropped under queue overload must not wait for a future successful
        // offer to drain a buffer that is already non-empty.  The buffer
        // add above precedes this CAS so a pump cannot observe an empty
        // buffer and clear the flag after work has landed.
        if (state.pumpQueued.compareAndSet(false, true)) {
            // addJob() silently discards when the queue is dead, which would
            // leave the flag set forever; clear it so a revived queue (or a
            // later offer) can start a pump again.
            if (ctx.jobQueue().isAlive()) {
                ctx.jobQueue().addJob(new PumpJob(ctx));
            } else {
                state.pumpQueued.set(false);
            }
        }
        return newlyBuffered;
    }

    /**
     * Drain up to {@code max} removable elements from the front of
     * {@code buf}.  An element is removed when {@code eligible} returns true
     * (dispatch it, or discard it as stale); the first element that is not
     * eligible stays at the head and ends the claim, so a temporarily denied
     * candidate pauses the batch behind it instead of being skipped and
     * retried out of order, while permanently-stale entries never wedge the
     * head (they test as eligible and are dropped by the caller).
     *
     * @param buf the buffer to claim from, modified in place
     * @param max maximum number of elements to claim
     * @param eligible removal predicate; false leaves the element in place
     * @return the claimed elements in buffer order; never null
     * @since 0.9.71+
     */
    static <T> List<T> claimBatch(Deque<T> buf, int max, Predicate<T> eligible) {
        List<T> claimed = new ArrayList<T>();
        while (claimed.size() < max && !buf.isEmpty()) {
            T head = buf.peekFirst();
            if (head == null || !eligible.test(head)) {
                break;
            }
            buf.pollFirst();
            claimed.add(head);
        }
        return claimed;
    }

    /**
     * Pop up to {@code max} elements from the front of {@code buf} with no
     * eligibility gate.  Callers that must not evaluate anything (expensive
     * checks, lock-ordering rules) use this and triage the popped elements
     * themselves, re-buffering the ones that cannot proceed.
     *
     * @param buf the buffer to claim from, modified in place
     * @param max maximum number of elements to claim
     * @return the claimed elements in buffer order; never null
     * @since 0.9.71+
     */
    static <T> List<T> claimBatch(Deque<T> buf, int max) {
        List<T> claimed = new ArrayList<T>();
        while (claimed.size() < max && !buf.isEmpty()) {
            T head = buf.pollFirst();
            if (head == null) break;
            claimed.add(head);
        }
        return claimed;
    }

    /**
     * Append {@code elem} to the buffer, evicting from the front while at or
     * above {@code bound} so the buffer can never grow without limit.
     *
     * @param buf the buffer to append to
     * @param elem the element to append
     * @param bound maximum buffer size after the append; non-positive means unbounded
     * @return the evicted elements oldest first; empty when nothing was evicted
     * @since 0.9.71+
     */
    static <T> List<T> offerBounded(Deque<T> buf, T elem, int bound) {
        List<T> evicted = new ArrayList<T>(1);
        while (bound > 0 && buf.size() >= bound) {
            T old = buf.pollFirst();
            if (old == null) break;
            evicted.add(old);
        }
        buf.addLast(elem);
        return evicted;
    }

    /**
     * Pump requeue delay for the current job-queue lag: brisk while quiet,
     * slower when busy, slowest when lagging, so draining a test backlog
     * never competes with router-critical jobs.
     *
     * @param maxLag current job-queue max lag (ms)
     * @return requeue delay in ms
     * @since 0.9.71+
     */
    static long pumpDelayMs(long maxLag) {
        if (maxLag > PUMP_LAGGED_LAG_MS) return PUMP_DELAY_LAGGED_MS;
        if (maxLag > PUMP_BUSY_LAG_MS) return PUMP_DELAY_BUSY_MS;
        return PUMP_DELAY_HEALTHY_MS;
    }

    /**
     * Why a batch candidate cannot dispatch yet, or null when it can.
     * Side-effect free (claims happen in {@link #claimBatchSlot} after the
     * gate passes) and measured on in-flight pressure — tests actually on
     * the wire — so delayed retests holding queue slots no longer starve
     * first tests.
     *
     * @param inFlight dispatched tests awaiting completion (all pools)
     * @param maxConcurrent configured in-flight cap
     * @param poolInFlight dispatched tests for this pool
     * @param poolBudget per-pool in-flight budget, or -1 to skip the pool gate
     * @param total current TestJob instances (memory bound)
     * @param hardLimit instance memory bound
     * @return denial reason, or null when the candidate is admitted
     * @since 0.9.71+
     */
    static String batchDenialReason(int inFlight, int maxConcurrent,
                                    int poolInFlight, int poolBudget,
                                    int total, int hardLimit) {
        if (inFlight >= maxConcurrent) {
            return "in-flight cap (" + inFlight + "/" + maxConcurrent + ")";
        }
        if (poolBudget >= 0 && poolInFlight >= poolBudget) {
            return "pool budget (" + poolInFlight + "/" + poolBudget + ")";
        }
        if (total >= hardLimit) {
            return "hard limit (" + total + "/" + hardLimit + ")";
        }
        return null;
    }

    /**
     * Per-pool in-flight budget for a pool in its current state, or -1 when
     * the pool gate is skipped.  -1 covers a null pool and a collapsed client
     * pool (zero active tunnels): their UNTESTED tunnels must drain or the
     * LeaseSet never republishes, mirroring the critical-pool bypass in
     * {@link #shouldSchedule}.  Exploratory pools use their fixed cap.
     *
     * @param ctx the router context
     * @param pool the owning pool, or null
     * @return the budget, or -1 to skip the pool gate
     * @since 0.9.71+
     */
    static int poolInFlightBudget(RouterContext ctx, TunnelPool pool) {
        if (pool == null) return -1;
        if (pool.getSettings().isExploratory()) return getMaxExploratoryPerPool(ctx);
        // One O(tunnels) scan feeds both the zero-active bypass and the
        // client budget itself.
        int activeCount = pool.getActiveTunnelCount();
        if (activeCount <= 0) return -1;
        return getClientPoolTestBudget(ctx, pool, activeCount);
    }

    /**
     * Live gate evaluation for one buffered candidate: reads the in-flight
     * gauges and configured budgets from the context's runtime state, then
     * defers to the pure overload.
     *
     * @param ctx the router context
     * @param cfg the tunnel config
     * @param pool the owning pool (may be null; read from cfg)
     * @return denial reason, or null when the candidate is admitted
     * @since 0.9.71+
     */
    static String batchDenialReason(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        if (cfg == null) return "no config";
        if (pool == null) pool = cfg.getTunnelPool();
        BatchState state = batchState(ctx);
        int poolBudget = poolInFlightBudget(ctx, pool);
        int poolInFlight = 0;
        if (pool != null) {
            AtomicInteger infl = state.poolInFlight.get(getPoolId(pool));
            if (infl != null) poolInFlight = infl.get();
        }
        return batchDenialReason(state.inFlight.get(), getMaxConcurrentTests(ctx),
                                 poolInFlight, poolBudget,
                                 state.totalTestJobs.get(), getHardLimit(ctx));
    }

    /**
     * Whether a buffered candidate should be discarded instead of tested:
     * its pool died, it was never fully built, it is a ping tunnel or was
     * pruned for early expiry, its test already started (registered while
     * buffered), or it is no longer UNTESTED (a prior dispatch tested or
     * traffic-proven it while it waited).
     *
     * @param ctx the router context (clock for the early-expiry check)
     * @param cfg the buffered tunnel config
     * @param pool the owning pool (may be null; read from cfg)
     * @return true when the candidate must be dropped rather than tested
     * @since 0.9.71+
     */
    static boolean shouldDropPending(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        if (cfg == null) return true;
        if (pool == null) pool = cfg.getTunnelPool();
        if (pool == null || !pool.isAlive()) return true;
        if (!hasValidTunnelIds(cfg)) return true;
        if (isPingTunnel(cfg)) return true;
        if (isEarlyExpiry(cfg, ctx.clock().now())) return true;
        if (cfg.getTestStatus() != TunnelTestStatus.UNTESTED) return true;
        Long key = getTunnelKey(cfg);
        if (key == null || batchState(ctx).runningTests.containsKey(key)) return true;
        // Cheapest non-null check last: testing a tunnel whose pool no longer
        // lists it (trimmed, replaced, or rejected by addTunnel) spends a
        // dispatch the pool will immediately discard.  Aliased pools delegate
        // listTunnels() to their alias, so membership follows the tunnel's
        // real pool either way.
        return !isPoolMember(pool, cfg);
    }

    /**
     * Whether a pool still owns a tunnel config, i.e. the config is one of
     * the pool's live tunnels.  Approximates the pool-ownership half of
     * {@link TunnelPool#addTunnel}, which drops a config it does not own;
     * rejected builds therefore never enter the buffer, and a config trimmed
     * while buffered is dropped before it can dispatch.
     *
     * @param pool the pool to check, never null
     * @param cfg the tunnel config to look for, never null
     * @return true when the pool currently lists the config
     * @since 0.9.71+
     */
    static boolean isPoolMember(TunnelPool pool, PooledTunnelCreatorConfig cfg) {
        if (pool == null || cfg == null) return false;
        List<TunnelInfo> tunnels = pool.listTunnels();
        return tunnels != null && tunnels.contains(cfg);
    }

    /**
     * Whether an offer must be refused outright: no pool owns the tunnel, it
     * was never fully built, it is a ping tunnel, it is already expired
     * (only the drain-time early-expiry window is left to
     * {@link #shouldDropPending}, since last-chance offers arrive inside it
     * on purpose), its test already started, or it is no longer UNTESTED.
     * Keeps invalid candidates out of the buffer so the pump never pays a
     * rejected dispatch for them.
     *
     * @param ctx the router context (clock and running-test registry)
     * @param cfg the offered tunnel config
     * @param pool the owning pool (may be null; read from cfg)
     * @return true when the offer must be refused
     * @since 0.9.71+
     */
    static boolean shouldRejectAtOffer(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        if (cfg == null) return true;
        if (pool == null) pool = cfg.getTunnelPool();
        if (pool == null || !pool.isAlive()) return true;
        if (!hasValidTunnelIds(cfg)) return true;
        if (isPingTunnel(cfg)) return true;
        if (cfg.getExpiration() <= ctx.clock().now()) return true;
        if (cfg.getTestStatus() != TunnelTestStatus.UNTESTED) return true;
        Long key = getTunnelKey(cfg);
        if (key == null || batchState(ctx).runningTests.containsKey(key)) return true;
        return !isPoolMember(pool, cfg);
    }

    /**
     * Decrement a gauge without letting it fall below zero.  {@link
     * #beginBatchReset(RouterContext)} normalizes the gauges while instances
     * are still alive, so an instance releasing after the reset must not drag
     * its counter negative — a negative instance count would admit more than
     * {@link #getHardLimit(RouterContext)} jobs.
     *
     * @param gauge the counter to decrement, never null
     * @since 0.9.71+
     */
    static void saturatingDecrement(AtomicInteger gauge) {
        for (;;) {
            int current = gauge.get();
            if (current <= 0) {
                if (gauge.compareAndSet(current, 0)) return;
            } else if (gauge.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    /**
     * The instance slots one TestJob owns: the total counter (memory bound),
     * the per-pool claim, and the running-test registration.  Built when the
     * slots are taken and handed to the instance, so every release goes
     * through one CAS-gated object instead of ad-hoc decrements — a path that
     * runs twice (drop racing completion) becomes a no-op, and the pool id is
     * captured once rather than recomputed from possibly-changed pool
     * settings at release time.
     *
     * @since 0.9.71+
     */
    static final class InstanceClaims {
        /** Pool identity captured when the claim was taken; null when there is no pool. */
        final String poolId;
        /** True until the total counter has been released. */
        final AtomicBoolean totalHeld = new AtomicBoolean(true);
        /** True while the per-pool claim is outstanding. */
        final AtomicBoolean poolHeld;
        /** True once the running-test registration succeeded. */
        final AtomicBoolean runningHeld = new AtomicBoolean(false);

        InstanceClaims(String poolId) {
            this.poolId = poolId;
            this.poolHeld = new AtomicBoolean(poolId != null);
        }

        /**
         * Release the total counter once.
         *
         * @param state the context's batch state
         * @since 0.9.71+
         */
        void releaseTotal(BatchState state) {
            if (totalHeld.compareAndSet(true, false)) {
                saturatingDecrement(state.totalTestJobs);
            }
        }

        /**
         * Release the per-pool claim once, under the id captured at claim time.
         *
         * @param state the context's batch state
         * @since 0.9.71+
         */
        void releasePool(BatchState state) {
            if (poolId != null && poolHeld.compareAndSet(true, false)) {
                releasePoolTestSlot(state, poolId);
            }
        }

        /**
         * Release whatever this object still holds: the running-test
         * registration, the pool claim, and the total counter.  Used by the
         * constructor's invalidation paths, where no single one of the three
         * is known to have been taken.
         *
         * @param state the context's batch state
         * @param instance the registered instance, or null when registration never happened
         * @param tunnelKey the running-test key, or null when none exists
         * @since 0.9.71+
         */
        void releaseAll(BatchState state, TestJob instance, Long tunnelKey) {
            if (tunnelKey != null && runningHeld.compareAndSet(true, false)) {
                state.runningTests.remove(tunnelKey, instance);
            }
            releasePool(state);
            releaseTotal(state);
        }
    }

    /**
     * Reserve the instance slots a batched TestJob needs: the total counter
     * (memory bound) and the per-pool claim.  The pool slot is claimed
     * whenever the instance has a pool — exactly the condition under which
     * the constructor's invalidation paths and {@link #cleanupTunnelTracking}
     * release it — so claims and releases can never drift apart as pool state
     * (active count, exploratory) changes between claim and release.
     * Called only after {@link #batchDenialReason(int, int, int, int, int, int)}
     * passed, so a lost CAS race is a rare skip the caller re-buffers.
     *
     * @param ctx the router context
     * @param cfg the tunnel config
     * @param pool the owning pool (may be null; read from cfg)
     * @return the acquired claims, or null when the memory bound is reached
     *         or a concurrent claim won the CAS race
     * @since 0.9.71+
     */
    static InstanceClaims claimBatchSlot(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        if (pool == null) pool = cfg.getTunnelPool();
        BatchState state = batchState(ctx);
        int current = state.totalTestJobs.get();
        if (!canClaimInstance(current, getHardLimit(ctx))) {
            return null;
        }
        if (!state.totalTestJobs.compareAndSet(current, current + 1)) {
            return null;
        }
        String poolId = pool != null ? getPoolId(pool) : null;
        if (poolId != null) {
            claimPoolTestSlot(state, poolId);
        }
        return new InstanceClaims(poolId);
    }

    /**
     * Insert {@code pending} at the buffer head, preserving their order, and
     * evict from the tail while the buffer is over its bound, returning what
     * was evicted so the caller can drop their keys.  One call does both so a
     * re-buffered batch can never push the buffer past its limit, and the
     * batch just returned to the head can never be the batch immediately
     * evicted from the tail (insert-then-trim is ordered; trim-then-insert is
     * not).
     *
     * @param buf the buffer to insert into, modified in place
     * @param pending candidates in original buffer order, head-most first
     * @param bound maximum buffer size after the insert; non-positive means unbounded
     * @return the candidates evicted from the tail; never null
     * @since 0.9.71+
     */
    static <T> List<T> rebufferBounded(Deque<T> buf, List<T> pending, int bound) {
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }
        for (int i = pending.size() - 1; i >= 0; i--) {
            buf.addFirst(pending.get(i));
        }
        List<T> evicted = new ArrayList<T>();
        while (bound > 0 && buf.size() > bound) {
            T old = buf.pollLast();
            if (old == null) break;
            evicted.add(old);
        }
        return evicted;
    }

    /**
     * Return candidates to the buffer head, preserving their order, after a
     * gate denial or a lost claim race so the next pump run retries them
     * instead of waiting for a sweep re-offer.  An entry whose tunnel key is
     * already buffered again (a concurrent re-offer while it was popped) is
     * dropped rather than duplicated, and any tail eviction it causes has its
     * keys removed so bufferedKeys stays an exact mirror of the buffer.
     *
     * @param state the context's batch state
     * @param ctx the router context (for the eviction stat)
     * @param pending candidates in original buffer order; empty is a no-op
     * @since 0.9.71+
     */
    static void rebufferAll(BatchState state, RouterContext ctx, List<PendingTest> pending) {
        if (pending.isEmpty()) {
            return;
        }
        List<PendingTest> evicted;
        // The dedupe runs under the same lock as every other bufferedKeys
        // mutation: an unsynchronized HashSet.add here would race the offer
        // and drain paths and could corrupt the set.
        synchronized (state.bufferLock) {
            List<PendingTest> toInsert = new ArrayList<PendingTest>(pending.size());
            for (PendingTest p : pending) {
                if (p == null) continue;
                if (p.key == null || state.bufferedKeys.add(p.key)) {
                    toInsert.add(p);
                }
            }
            if (toInsert.isEmpty()) {
                return;
            }
            evicted = rebufferBounded(state.firstTestBuffer, toInsert, MAX_BUFFERED_FIRST_TESTS);
            for (PendingTest old : evicted) {
                if (old != null && old.key != null) {
                    state.bufferedKeys.remove(old.key);
                }
            }
        }
        // Stats outside the lock: addRateData takes stat-manager locks the
        // buffer must never sit behind.  An evicted candidate returns to the
        // unbuffered state and the sweeps re-offer it.
        for (int i = 0; i < evicted.size(); i++) {
            ctx.statManager().addRateData("tunnel.testBufferDropped", 1);
        }
    }

    /**
     * Put one candidate back at the buffer tail, used to rotate a candidate
     * that is denied only for its own pool behind the rest of the scan so the
     * drain is not blocked at the head by it.  Refused when the buffer is
     * full — the candidate simply returns to the unbuffered state and the
     * sweeps re-offer it, rather than evicting an older candidate to make
     * room for one that was just taken out.
     *
     * @param state the context's batch state
     * @param p the candidate to re-queue
     * @since 0.9.71+
     */
    static void rebufferOne(BatchState state, PendingTest p) {
        if (p == null) return;
        synchronized (state.bufferLock) {
            if (state.firstTestBuffer.size() >= MAX_BUFFERED_FIRST_TESTS) return;
            if (p.key == null || state.bufferedKeys.add(p.key)) {
                state.firstTestBuffer.addLast(p);
            }
        }
    }

    /**
     * Whether a denial reason is scoped to one pool, and therefore can be
     * worked around by trying the candidates behind it, rather than being a
     * global gate that stops the whole scan.
     *
     * @param reason denial reason from {@link #batchDenialReason(int, int, int, int, int, int)}
     * @return true when only the candidate's own pool is over budget
     * @since 0.9.71+
     */
    static boolean isPoolScopedDenial(String reason) {
        return reason != null && reason.startsWith("pool budget");
    }

    /**
     * Reorder one scan so the candidates that are about to expire go first:
     * last-chance admitted tests sorted by soonest expiration, then everything
     * else in its original buffer order.  Last-chance tunnels exist only
     * because they are within {@link TunnelPool#LAST_CHANCE_MIN_LIFE_MS} of
     * expiry, so letting an ordinary candidate ahead of one in the same scan
     * can burn the only test the dying tunnel will ever get.
     *
     * @param scan the popped candidates, in buffer order
     * @param now the current time
     * @return the prioritized order; never null, same elements
     * @since 0.9.71+
     */
    static List<PendingTest> prioritizeBatch(List<PendingTest> scan, long now) {
        List<PendingTest> out = new ArrayList<PendingTest>(scan.size());
        List<PendingTest> lastChance = new ArrayList<PendingTest>();
        for (PendingTest p : scan) {
            if (p != null && p.cfg != null && p.cfg.isLastChanceAdmitted(now)) {
                lastChance.add(p);
            } else if (p != null) {
                out.add(p);
            }
        }
        if (!lastChance.isEmpty()) {
            Collections.sort(lastChance, new Comparator<PendingTest>() {
                @Override
                public int compare(PendingTest a, PendingTest b) {
                    return Long.compare(a.cfg.getExpiration(), b.cfg.getExpiration());
                }
            });
            out.addAll(0, lastChance);
        }
        return out;
    }

    /**
     * Rate-limited INFO line for a batch denial, so the reason a backlog is
     * not draining stays visible without flooding the log.
     *
     * @param state the context's batch state (denial-log timestamp)
     * @param ctx the router context
     * @param reason the denial reason from the gate
     * @since 0.9.71+
     */
    private static void logBatchDenial(BatchState state, RouterContext ctx, String reason) {
        long now = ctx.clock().now();
        long last = state.lastDenialLog.get();
        if (now - last >= DENIAL_LOG_INTERVAL_MS && state.lastDenialLog.compareAndSet(last, now)) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldInfo()) {
                log.info("Batched first-test denied (" + reason + ") -> candidates stay buffered");
            }
        }
    }

    /**
     * Drain one batch from the first-test buffer and dispatch admitted
     * candidates directly (no per-tunnel queue entry).
     *
     * The pop runs under the buffer lock with zero evaluation — gate checks
     * call pool.getActiveTunnelCount(), which takes the pool's tunnel lock,
     * and taking pool locks under the buffer lock both stalls every offer
     * behind an O(tunnels) scan and invites lock-order inversion.  More than
     * one dispatch batch is popped so a candidate that must go to the back can
     * rotate past the ones behind it; stale candidates are discarded, a
     * pool-scoped denial rotates just that candidate to the tail, and a global
     * denial or lost claim race re-buffers the untested remainder in order
     * (FIFO preserved, nothing skipped) and ends the run.
     *
     * @param ctx the router context
     * @return tests dispatched this run
     * @since 0.9.71+
     */
    static int drainBuffer(RouterContext ctx) {
        BatchState state = batchState(ctx);
        if (state.quiesced) {
            return 0;
        }
        List<PendingTest> scan;
        synchronized (state.bufferLock) {
            scan = claimBatch(state.firstTestBuffer, DRAIN_SCAN_LIMIT);
            for (PendingTest p : scan) {
                if (p.key != null) {
                    state.bufferedKeys.remove(p.key);
                }
            }
        }
        long now = ctx.clock().now();
        List<PendingTest> batch = prioritizeBatch(scan, now);
        int dispatched = 0;
        for (int i = 0; i < batch.size(); i++) {
            PendingTest p = batch.get(i);
            if (shouldDropPending(ctx, p.cfg, p.pool)
                    || !p.poolId.equals(getPoolId(p.pool))) {
                ctx.statManager().addRateData("tunnel.testBufferRejected", 1);
                continue;
            }
            String reason = batchDenialReason(ctx, p.cfg, p.pool);
            if (reason != null) {
                ctx.statManager().addRateData("tunnel.testBatchDenied", 1);
                logBatchDenial(state, ctx, reason);
                if (isPoolScopedDenial(reason)) {
                    // Only this pool is full: let the rest of the scan past it
                    // instead of wedging the head of the buffer.
                    rebufferOne(state, p);
                    continue;
                }
                rebufferAll(state, ctx, batch.subList(i, batch.size()));
                break;
            }
            if (dispatched >= PUMP_BATCH_SIZE) {
                rebufferAll(state, ctx, batch.subList(i, batch.size()));
                break;
            }
            InstanceClaims claims = claimBatchSlot(ctx, p.cfg, p.pool);
            if (claims == null) {
                rebufferAll(state, ctx, batch.subList(i, batch.size()));
                break;
            }
            TestJob job = new TestJob(ctx, p.cfg, p.pool, claims);
            if (!job.isValid()) {
                // The constructor released the claims on its invalid paths.
                ctx.statManager().addRateData("tunnel.testBufferRejected", 1);
                continue;
            }
            dispatched++;
            ctx.statManager().addRateData("tunnel.testBatchDispatched", 1);
            try {
                job.runJob();
            } catch (Throwable t) {
                // Release the instance's claims so a thrown test cannot leak
                // the counters that gate further dispatch.
                job.dropped();
                Log log = ctx.logManager().getLog(TestJob.class);
                if (log.shouldError()) {
                    log.error("Batched test dispatch failed for " + p.cfg, t);
                }
            }
        }
        return dispatched;
    }

    /**
     * Identity of one dispatched test round: the generation it belongs to, the
     * pool it counts against, the deadline it expires at, and the release
     * switches for the permits it reserved.  Reply, timeout, and dispatch
     * failure all hold the token they were built for, so a delayed callback
     * from an earlier round can never complete, release, or clear state that
     * now belongs to a later one — the old flags on the instance had no way to
     * tell which round they referred to.
     *
     * @since 0.9.71+
     */
    static final class RoundToken {
        /** Monotonic generation, for logs and token identity. */
        final long generation;
        /** Pool the in-flight permits were taken from; null when the job has no pool. */
        final String poolId;
        /** When the round stops being eligible for a reply. */
        final long expiration;
        /** True once one path has claimed this round; later claims are no-ops. */
        final AtomicBoolean completed = new AtomicBoolean(false);
        /** True while this round holds the global and per-pool in-flight permits. */
        final AtomicBoolean permitsHeld = new AtomicBoolean(false);

        /**
         * @param generation monotonic round generation
         * @param poolId pool identity captured at creation, or null
         * @param expiration reply deadline in ms
         */
        RoundToken(long generation, String poolId, long expiration) {
            this.generation = generation;
            this.poolId = poolId;
            this.expiration = expiration;
        }
    }

    /**
     * First path to claim a round wins: the round must still be the active one
     * and must not have been claimed before.  Pure so the reply-vs-timeout race
     * is testable without a live job — the reply job and the timeout job can
     * both reach it, and exactly one may proceed to completion.
     *
     * @param active the round the instance currently considers live, or null
     * @param token the round the caller is completing
     * @return true when the caller owns the completion
     * @since 0.9.71+
     */
    static boolean claimRoundCompletion(RoundToken active, RoundToken token) {
        return token != null && active == token && token.completed.compareAndSet(false, true);
    }

    /**
     * Claim the round for this completion attempt.
     *
     * @param token the round the callback was built for
     * @return true when the caller owns the completion
     * @since 0.9.71+
     */
    boolean claimRound(RoundToken token) {
        return claimRoundCompletion(_round, token);
    }

    /**
     * Take the global and per-pool in-flight permits for a round, atomically.
     * Static over its {@link BatchState} so the reservation rules are testable
     * without a live instance: the global count is taken with a CAS loop so
     * two dispatchers cannot both read "one slot left" and both take it; the
     * pool count is reserved inside the map's compute(), which either
     * increments a counter it checked or leaves the map untouched when the
     * budget is exhausted.  A failed pool reserve releases the global count
     * it just took, so a refusal never leaks a permit; a token that already
     * holds its permits is refused rather than double-counted.
     *
     * @param state the context's batch state
     * @param maxConcurrent global in-flight cap
     * @param poolId pool identity captured on the token, or null to skip the per-pool gate
     * @param poolBudget per-pool budget; negative means unbounded
     * @param token the round being armed
     * @return true when both permits were taken and the token holds them
     * @since 0.9.71+
     */
    static boolean tryReserveInFlight(BatchState state, int maxConcurrent,
                                      String poolId, int poolBudget, RoundToken token) {
        if (token.permitsHeld.get()) {
            return false;
        }
        int current;
        do {
            current = state.inFlight.get();
            if (current >= maxConcurrent) {
                return false;
            }
        } while (!state.inFlight.compareAndSet(current, current + 1));

        if (poolId != null) {
            final boolean[] reserved = {false};
            state.poolInFlight.compute(poolId, (k, c) -> {
                int used = c != null ? c.get() : 0;
                if (poolBudget >= 0 && used >= poolBudget) {
                    // At budget: leave the map exactly as it was.
                    reserved[0] = false;
                    return c;
                }
                AtomicInteger a = c != null ? c : new AtomicInteger();
                a.incrementAndGet();
                reserved[0] = true;
                return a;
            });
            if (!reserved[0]) {
                state.inFlight.decrementAndGet();
                return false;
            }
        }
        token.permitsHeld.set(true);
        return true;
    }

    /**
     * Reserve the in-flight permits a round needs before it goes on the wire,
     * and arm the round as this instance's active one.  Both gates are taken
     * atomically by {@link #tryReserveInFlight}, and arming happens only on
     * success, so a refused round can never be completed later by a
     * straggler callback.  Retests reserve here too: without this they would
     * bypass the per-pool budget that first tests pay at drain time.
     *
     * @param token the round being armed
     * @return true when both permits were taken and the round may dispatch
     * @since 0.9.71+
     */
    boolean reserveInFlight(RoundToken token) {
        final RouterContext ctx = getContext();
        final int budget = token.poolId != null ? poolInFlightBudget(ctx, _pool) : -1;
        if (!tryReserveInFlight(batchState(ctx), getMaxConcurrentTests(ctx),
                token.poolId, budget, token)) {
            return false;
        }
        // Arm only now that the permits are held: a refused round leaves the
        // previous round (or none) active, so its callbacks are inert.
        _round = token;
        return true;
    }

    /**
     * Release the in-flight permits a round reserved, once.  The CAS makes a
     * second terminal for the same round (reply racing timeout, a drop after
     * completion) a no-op, so the gauges cannot drift negative; the per-pool
     * decrement unlinks its counter at zero atomically, so a concurrent reserve
     * can never increment an unlinked counter.  Static so the exact-release
     * and double-release rules are testable without a live instance.
     *
     * @param state the context's batch state
     * @param token the round whose permits are being returned, or null
     * @since 0.9.71+
     */
    static void releaseTokenPermits(BatchState state, RoundToken token) {
        if (token == null || !token.permitsHeld.compareAndSet(true, false)) {
            return;
        }
        saturatingDecrement(state.inFlight);
        if (token.poolId != null) {
            state.poolInFlight.computeIfPresent(token.poolId, (k, c) -> c.decrementAndGet() > 0 ? c : null);
        }
    }

    /**
     * Release the in-flight permits a round reserved, once.
     *
     * @param token the round whose permits are being returned, or null
     * @see #releaseTokenPermits(BatchState, RoundToken)
     * @since 0.9.71+
     */
    void releaseRound(RoundToken token) {
        releaseTokenPermits(state(), token);
    }

    /**
     * Single queue entry that drains the first-test buffer in small batches.
     * Batched tunnels are dispatched directly from this job (no per-tunnel
     * queue entry), so draining a large UNTESTED backlog costs one ready
     * queue slot and a health-paced trickle instead of one slot per tunnel.
     * The next run is queued while the buffer is non-empty and the flag
     * stays set; the flag clears on an empty buffer, on shutdown, or when
     * this job is dropped under overload (the next offer revives it).
     */
    private static class PumpJob extends JobImpl {
        private final RouterContext _ctx;

        PumpJob(RouterContext ctx) {
            super(ctx);
            _ctx = ctx;
        }

        @Override
        public String getName() {
            return "Batch Tunnel Test Pump";
        }

        @Override
        public void runJob() {
            BatchState state = batchState(_ctx);
            boolean shuttingDown = _ctx.router().gracefulShutdownInProgress() || state.quiesced;
            if (shuttingDown || !_ctx.jobQueue().isAlive()) {
                state.pumpQueued.set(false);
                return;
            }
            try {
                drainBuffer(_ctx);
            } catch (Throwable t) {
                // Keep the pump alive: a non-empty buffer is requeued below
                // and retried under the usual pacing.
                Log log = _ctx.logManager().getLog(TestJob.class);
                if (log.shouldError()) {
                    log.error("Batched test pump drain failed", t);
                }
            }
            boolean empty;
            synchronized (state.bufferLock) {
                empty = state.firstTestBuffer.isEmpty();
            }
            if (!empty) {
                scheduleNext();
                return;
            }
            state.pumpQueued.set(false);
            synchronized (state.bufferLock) {
                empty = state.firstTestBuffer.isEmpty();
            }
            if (empty) return;
            // Raced with an offer that saw the flag still set: revive here.
            if (state.pumpQueued.compareAndSet(false, true)) {
                scheduleNext();
            }
        }

        @Override
        public void dropped() {
            // Do not reschedule from here — under overload that would spin.
            // The next offerFirstTest() (build or sweep) restarts the pump.
            batchState(_ctx).pumpQueued.set(false);
        }

        private void scheduleNext() {
            PumpJob next = new PumpJob(_ctx);
            next.getTiming().setStartAfter(_ctx.clock().now() +
                    pumpDelayMs(_ctx.jobQueue().getMaxLag()));
            // addJob() silently drops the job once the queue is dead; clear the
            // flag first in that case so a buffer still holding candidates is
            // not left waiting on a pump that was never queued.
            if (_ctx.jobQueue().isAlive()) {
                _ctx.jobQueue().addJob(next);
            } else {
                batchState(_ctx).pumpQueued.set(false);
            }
        }
    }

    /**
     * Check if this TestJob instance is valid and should be queued.
     * @return true if valid, false if it should not be queued
     */
    public boolean isValid() {
        return _valid;
    }

    /**
     * Release this instance's total-counter slot, once.  Idempotent so a path
     * that runs twice (a completion racing a queue drop) cannot drive the
     * count negative, which would break the scheduling caps permanently.
     *
     * @since 0.9.71+
     */
    private void decrementIfCounted() {
        InstanceClaims claims = _claims;
        if (claims != null) {
            claims.releaseTotal(state());
        }
    }

    /**
     * Clean up this test job from tunnel tracking.
     * Must be called when a test job completes or is cancelled.
     * Idempotent: the pool slot is released only when this call actually
     * removes the instance's running-test registration, so a duplicate
     * cleanup (defer path racing a terminal path) cannot double-release.
     * Note: This does NOT affect the total counter, which
     * {@link #decrementIfCounted()} owns.
     */
    private void cleanupTunnelTracking() {
        InstanceClaims claims = _claims;
        if (claims == null) {
            return;
        }
        BatchState state = state();
        Long tunnelKey = getTunnelKey(_cfg);
        if (tunnelKey == null || !claims.runningHeld.compareAndSet(true, false)) {
            return;
        }
        if (state.runningTests.remove(tunnelKey, this)) {
            // Only a registration that really existed owns the pool claim;
            // releasing here keeps claim and release paired exactly once.
            claims.releasePool(state);
        }
    }

    /**
     * TestJob.  Claims the instance slots itself, so a direct construction
     * cannot run without them; returns an invalid instance (nothing claimed)
     * when the memory bound or a concurrent claim refuses.
     *
     * @param ctx the router context
     * @param cfg the tunnel config
     * @param pool the owning pool (may be null; read from cfg)
     */
    public TestJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        this(ctx, cfg, pool, claimBatchSlot(ctx, cfg, pool));
    }

    /**
     * TestJob for a caller that already holds the instance slots, such as the
     * batch pump, which must claim before it constructs so a refused candidate
     * can go back to the buffer instead of being dropped.
     *
     * @param ctx the router context
     * @param cfg the tunnel config
     * @param pool the owning pool (may be null; read from cfg)
     * @param claims the slots already claimed for this instance; null refuses it
     */
    TestJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool, InstanceClaims claims) {
        super(ctx);
        applyConfiguredQueuedLimit(ctx);
        _log = ctx.logManager().getLog(TestJob.class);
        _cfg = cfg;
        _pool = (pool != null) ? pool : cfg.getTunnelPool();
        _claims = claims;
        if (claims == null) {
            // The claim failed: there is nothing to release and nothing to
            // register, so refuse before touching any shared state.
            if (_log.shouldDebug()) {
                _log.debug("Instance slots unavailable -> Not scheduling test for " + cfg);
            }
            _valid = false;
            return;
        }
        BatchState state = batchState(ctx);
        if (_pool == null) {
            if (_log.shouldError()) {
                _log.error("Invalid Tunnel Test configuration → No pool for " + cfg, new Exception("origin"));
            }
            // No pool means no pool-slot claim was made, but the total slot
            // was — release it or the instance cap leaks one slot every time
            // this fires.
            claims.releaseAll(state, null, null);
            _valid = false;
            return;
        }

        // Register this test as running for the tunnel
        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey == null) {
            if (_log.shouldWarn())
                _log.warn("Failed to generate tunnel key -> Invalidating test for " + cfg);
            claims.releaseAll(state, null, null);
            _valid = false;
            return;
        }

        TestJob existing = state.runningTests.putIfAbsent(tunnelKey, this);
        if (existing != null) {
            if (_log.shouldDebug()) {
                _log.debug("Test already registered for tunnel key " + tunnelKey + " -> Invalidating duplicate test for " + cfg);
            }
            claims.releaseAll(state, null, null);
            _valid = false;
            return;
        }
        claims.runningHeld.set(true);
        // Test immediately after tunnel build completes.  The test period
        // (45-90s) already provides generous tolerance for transient latency.
        long startTime = ctx.clock().now();
        getTiming().setStartAfter(startTime);
    }

    /**
     * The name of this job.
     *
     * @return the name
     */
    @Override
    public String getName() {
        return "Test Local Tunnel";
    }

    /**
     * Run the tunnel test and report success or failure.
     */
    @Override
    public void runJob() {
        final RouterContext ctx = getContext();
        if (_pool == null || !_pool.isAlive()) {
            cleanupTunnelTracking();
            decrementIfCounted();
            return;
        }

        // Check for graceful shutdown, a restart draining the batch subsystem,
        // and a queue that died while this job sat ready.  All three would
        // leave a dispatched round (or an instance slot) held by a job that
        // can never finish.
        if (ctx.router().gracefulShutdownInProgress() || state().quiesced || _terminated
                || !ctx.jobQueue().isAlive()) {
            cleanupTunnelTracking();
            decrementIfCounted();
            return;
        }

        // Determine if this is an exploratory tunnel early for deprioritization logic
        boolean isExploratory = _pool.getSettings().isExploratory();

        long maxLag = ctx.jobQueue().getMaxLag();
        if (maxLag > 3000) {
            // Skip exploratory tunnels first under pressure
            if (isExploratory) {
                if (_log.shouldInfo()) {
                    _log.info("Skipping exploratory tunnel test due to job lag (" + maxLag + "ms) -> " + _cfg);
                }
                ctx.statManager().addRateData("tunnel.testExploratorySkipped", _cfg.getLength());
                if (!scheduleRetest(_cfg.needsExpeditedTest())) {
                    cleanupTunnelTracking();
                    decrementIfCounted();
                }
                return;
            }

            // Client tunnels: defer under pressure but don't abort.  Release
            // the registration only when the retest cannot be queued — while
            // the job stays queued it must keep holding its running-test
            // entry and counters, or a sweep re-offer can create a duplicate
            // TestJob and the instance cap permanently under-counts.
            if (_log.shouldWarn()) {
                _log.warn("Deferring test due to job lag (" + maxLag + "ms) -> " + _cfg);
            }
            if (!scheduleRetest(_cfg.needsExpeditedTest())) {
                cleanupTunnelTracking();
                decrementIfCounted();
            }
            return;
        }

        // Begin tunnel test logic.  Each round builds its own RoundToken, so
        // there is no shared completion gate to reset for the retest.
        long now = ctx.clock().now();

        // Defer retests of GOOD tunnels that are provably carrying real
        // traffic — the traffic shows they're in active use, and testing
        // them now wastes a job-queue slot and risks a false negative on
        // high-bandwidth tunnels.  The defer holds only while test slots
        // are contended; during an ebb (see shouldDeferActiveGoodTunnel)
        // the retest runs now, so a continuously-busy GOOD tunnel still
        // gets a fresh latency sample for the console instead of staying
        // unverified forever.  Deferral is GOOD-only: UNTESTED tunnels
        // must be tested regardless (otherwise active clients receive data
        // on every new tunnel before the test runs, the test is perpetually
        // skipped, the tunnel stays UNTESTED forever, and the pool never
        // accumulates GOOD tunnels → EMERGENCY build storm), and FAILING
        // tunnels are tested promptly — the test is the arbiter for a
        // suspect tunnel, since outbound dispatch alone is not proof of
        // delivery.  Must run before setTestStarted() flips the status to
        // TESTING.
        long lastTraffic = _cfg.getLastRealTraffic();
        if (shouldDeferActiveGoodTunnel(lastTraffic, now, _cfg.getTestStatus(),
                                        state().inFlight.get(), getMaxConcurrentTests(ctx))) {
            if (_log.shouldInfo()) {
                _log.info("Deferring test on " + _cfg + " -> Real traffic " +
                          (now - lastTraffic) + "ms ago");
            }
            if (!scheduleRetest(false, true)) {
                cleanupTunnelTracking();
                decrementIfCounted();
            }
            return;
        }

        // Set test status to TESTING
        _cfg.setTestStarted();

        if (_cfg.isInbound()) {
            _replyTunnel = _cfg;
            if (isExploratory) {
                _outTunnel = ctx.tunnelManager().selectOutboundTunnel();
            } else {
                _outTunnel = ctx.tunnelManager().selectOutboundTunnel(_pool.getSettings().getDestination());
                if (_outTunnel == null) {
                    // No GOOD outbound tunnel — use any non-expired tunnel from
                    // the paired outbound pool.  Untested tunnels are functional
                    // (they just haven't been tested yet) and work fine as test
                    // partners.  This prevents the deadlock where both directions
                    // defer because neither has a tested partner.
                    TunnelPool paired = _pool.getPairedPool();
                    if (paired != null) {
                        for (TunnelInfo t : paired.listTunnels()) {
                            if (t.getExpiration() > now && t.getLength() > 1) {
                                _outTunnel = t;
                                break;
                            }
                        }
                    }
                    if (_outTunnel == null) {
                        // Fall back to exploratory outbound tunnel so tests can
                        // still proceed when the paired pool is empty (e.g. both
                        // directions cycling through failures).  Without this,
                        // server pools deadlock: inbound tests need outbound
                        // partners and vice versa, and neither can ever pass.
                        _outTunnel = ctx.tunnelManager().selectOutboundTunnel();
                        if (_outTunnel != null && _log.shouldWarn())
                            _log.warn("Falling back to exploratory outbound tunnel for test of " + _cfg);
                    }
                }
                if (_outTunnel == null) {
                    _deferredCount++;
                    if (_deferredCount >= MAX_DEFERRED) {
                        // Test deadlock: both pools degraded, neither has a partner.
                        // Mark this tunnel as failed to trigger pool recovery rather
                        // than letting it sit UNTESTED forever blocking builds.
                        if (_log.shouldWarn()) {
                            _log.warn("Test deadlock after " + _deferredCount + " deferrals -> marking " +
                                      _cfg + " as failed (pool may be recovering)");
                        }
                        _cfg.incrementTestFailures();
                        _cfg.setTestFailed();
                        cleanupTunnelTracking();
                        decrementIfCounted();
                        return;
                    }
                    if (_log.shouldWarn())
                        _log.warn("No outbound tunnel for test of " + _cfg +
                                  " -> Deferring (" + _deferredCount + "/" + MAX_DEFERRED + ", pool may be recovering)");
                    ctx.statManager().addRateData("tunnel.testDeferred", _cfg.getLength());
                    if (!scheduleRetest(false)) {
                        cleanupTunnelTracking();
                        decrementIfCounted();
                    }
                    return;
                }
            }
        } else {
            _outTunnel = _cfg;
            // Skip testing outbound tunnels that recently sent data —
            // they're obviously working and testing risks false failures
            // on high-traffic paths.  Tunnel must be tested at least once
            // so every tunnel gets an initial latency reading.
            if (_cfg.getTestStatus() != TunnelTestStatus.UNTESTED &&
                ctx.clock().now() - _cfg.getLastTransferred() < getMaxTestDelay(ctx)) {
                if (_log.shouldInfo()) {
                    _log.info("Skipping test on " + _cfg + " -> Data recently received");
                }
                if (!scheduleRetest(false)) {
                    cleanupTunnelTracking();
                    decrementIfCounted();
                }
                return;
            }
            if (isExploratory) {
                _replyTunnel = ctx.tunnelManager().selectInboundTunnel();
            } else {
                _replyTunnel = ctx.tunnelManager().selectInboundTunnel(_pool.getSettings().getDestination());
                if (_replyTunnel == null) {
                    // No GOOD inbound tunnel — use any non-expired tunnel from
                    // the paired inbound pool.  Same rationale as above: untested
                    // tunnels work fine as reply receivers for outbound tests.
                    TunnelPool paired = _pool.getPairedPool();
                    if (paired != null) {
                        for (TunnelInfo t : paired.listTunnels()) {
                            if (t.getExpiration() > now && t.getLength() > 1) {
                                _replyTunnel = t;
                                break;
                            }
                        }
                    }
                    if (_replyTunnel == null) {
                        // Fall back to exploratory inbound tunnel so tests can
                        // still proceed when the paired pool is empty.
                        _replyTunnel = ctx.tunnelManager().selectInboundTunnel();
                        if (_replyTunnel != null && _log.shouldWarn()) {
                            _log.warn("Falling back to exploratory inbound tunnel for test of " + _cfg);
                        }
                    }
                }
                if (_replyTunnel == null) {
                    _deferredCount++;
                    if (_deferredCount >= MAX_DEFERRED) {
                        // Test deadlock: both pools degraded, neither has a partner.
                        // Mark this tunnel as failed to trigger pool recovery rather
                        // than letting it sit UNTESTED forever blocking builds.
                        if (_log.shouldWarn()) {
                            _log.warn("Test deadlock after " + _deferredCount + " deferrals -> marking " +
                                      _cfg + " as failed (pool may be recovering)");
                        }
                        _cfg.incrementTestFailures();
                        _cfg.setTestFailed();
                        cleanupTunnelTracking();
                        decrementIfCounted();
                        return;
                    }
                    if (_log.shouldWarn()) {
                        _log.warn("No inbound tunnel for test of " + _cfg + " -> Deferring (" +
                                  _deferredCount + "/" + MAX_DEFERRED + ", pool may be recovering)");
                    }
                    ctx.statManager().addRateData("tunnel.testDeferred", _cfg.getLength());
                    if (!scheduleRetest(false)) {
                        cleanupTunnelTracking();
                        decrementIfCounted();
                    }
                    return;
                }
            }
        }

        if (_replyTunnel == null || _outTunnel == null) {
            if (_log.shouldWarn()) {
                _log.warn("Insufficient tunnels to test " + _cfg + " with: " + _replyTunnel + " / " + _outTunnel);
            }
            ctx.statManager().addRateData("tunnel.testDeferred", _cfg.getLength());
            if (!scheduleRetest(false)) {
                cleanupTunnelTracking();
                decrementIfCounted();
            }
            return;
        }

        // Compute the test period once for this round and reuse it when judging
        // the reply — recomputing from live stats could shift the window.
        _testPeriod = getTestPeriod();
        ctx.statManager().addRateData("tunnel.testPeriod", _testPeriod);
        long testExpiration = now + _testPeriod;

        DeliveryStatusMessage m = new DeliveryStatusMessage(ctx);
        m.setArrival(now);
        m.setMessageExpiration(testExpiration);
        m.setMessageId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));

        // One RoundToken spans this whole round: the selector, the reply job,
        // and the timeout job all hold it, so a callback from an earlier
        // retest can never complete, release, or clear state belonging to a
        // later round.
        RoundToken token = new RoundToken(_roundGen.incrementAndGet(),
                _pool != null ? getPoolId(_pool) : null, testExpiration);
        ReplySelector sel = new ReplySelector(m.getMessageId(), token);
        OnTestReply onReply = new OnTestReply(token);
        OnTestTimeout onTimeout = new OnTestTimeout(now, token);
        OutNetMessage msg = ctx.messageRegistry().registerPending(sel, onReply, onTimeout);
        _pendingMessage = msg;
        onReply.setSentMessage(msg);
        onTimeout.setSentMessage(msg);

        boolean sendSuccess = sendTest(m, _testPeriod, token);
        if (!sendSuccess) {
            // Nothing went out: release the registration so the callbacks
            // cannot fire for a round that never dispatched, and consume any
            // session tags a wrap or reserve failure already generated — they
            // belong to this round and nothing will ever use them.
            ctx.messageRegistry().unregisterPending(msg);
            clearTestTags();
            // Try to reschedule - if it fails, clean up tunnel tracking and total counter
            if (!scheduleRetest(_cfg.needsExpeditedTest())) {
                cleanupTunnelTracking();
                decrementIfCounted();
            }
        }
    }

    /**
     * Send the tunnel test message through the selected tunnels.
     * @param m the message to send
     * @param testPeriod time in ms before message expiration
     * @param token the round this dispatch belongs to
     * @return true if message was sent successfully, false otherwise
     */
    private boolean sendTest(I2NPMessage m, int testPeriod, RoundToken token) {
        final RouterContext ctx = getContext();
        _testId = __id.getAndIncrement();

        // Prefer secure paths but still allow unencrypted as cover
        boolean useEncryption = ctx.random().nextInt(4) != 0;

        // During high job lag, prefer unencrypted tests to reduce crypto overhead
        long maxLag = ctx.jobQueue().getMaxLag();
        long avgLag = ctx.jobQueue().getAvgLag();
        if (maxLag > 1000 || avgLag > 10) {
            useEncryption = ctx.random().nextInt(2) != 0; // 50% chance unencrypted
        }

        if (useEncryption) {
            MessageWrapper.OneTimeSession sess;
            if (_cfg.isInbound() && !_pool.getSettings().isExploratory()) {
                sess = MessageWrapper.generateSession(ctx, _pool.getSettings().getDestination(), testPeriod, false);
            } else {
                sess = MessageWrapper.generateSession(ctx, testPeriod);
            }

            if (sess == null) {
                return false;
            }

            if (sess.tag != null) {
                _encryptTag = sess.tag;
                m = MessageWrapper.wrap(ctx, m, sess.key, sess.tag);
            } else {
                _ratchetEncryptTag = sess.rtag;
                m = MessageWrapper.wrap(ctx, m, sess.key, sess.rtag);
            }

            if (m == null) {
                return false;
            }
        }

        if (_log.shouldDebug()) {
            _log.debug("Sending " + (useEncryption ? "" : "unencrypted ") + "garlic test [#" + _testId +
                       "] exp=" + DataHelper.formatDuration(m.getMessageExpiration() - ctx.clock().now()) +
                       " testPeriod=" + testPeriod + "ms \n* " +
                       _outTunnel + " / " + _replyTunnel);
        }

        // The round is going on the wire: arm it and take its permits now, so
        // a wrap failure above never reserves anything and the gauges never
        // lead the dispatch.
        if (!reserveInFlight(token)) {
            if (_log.shouldDebug()) {
                _log.debug("In-flight budget exhausted -> Not dispatching test [#" + _testId + "] for " + _cfg);
            }
            return false;
        }
        boolean dispatched;
        try {
            dispatched = ctx.tunnelDispatcher().dispatchOutbound(
                m,
                _outTunnel.getSendTunnelId(0),
                _replyTunnel.getReceiveTunnelId(0),
                _replyTunnel.getPeer(0)
            );
        } catch (Throwable t) {
            if (_log.shouldError()) {
                _log.error("Test dispatch threw for " + _cfg, t);
            }
            abandonRound(token);
            return false;
        }
        if (!dispatched) {
            abandonRound(token);
            return false;
        }
        return true;
    }

    /**
     * Give up a round that was armed but never made it onto the wire: mark it
     * claimed so any straggler callback is a no-op, then return its permits.
     *
     * @param token the round being abandoned
     * @since 0.9.71+
     */
    void abandonRound(RoundToken token) {
        if (claimRound(token)) {
            releaseRound(token);
        }
    }

    /**
     * Called when the tunnel test completes successfully.
     * Updates statistics and schedules the next test.
     * @param ms time in milliseconds the test took to succeed
     */
    public void testSuccessful(int ms) {
        final RouterContext ctx = getContext();
        if (_pool == null || !_pool.isAlive()) {
            cleanupTunnelTracking();
            decrementIfCounted();
            return;
        }

        // Update success history for adaptive testing frequency
        updateSuccessHistory(true);

        ctx.statManager().addRateData("tunnel.testSuccessLength", _cfg.getLength());
        ctx.statManager().addRateData("tunnel.testSuccessTime", ms);

        _outTunnel.incrementVerifiedBytesTransferred(1024);
        noteSuccess(ms, _outTunnel);
        noteSuccess(ms, _replyTunnel);

        // For 0-hop/1-hop tunnels, latency IS the direct RTT to the far-end peer.
        // Demote the peer from fast/high-cap tiers when its test latency is well
        // above the network's current baseline RTT, so the peer selector avoids
        // re-selecting a genuinely slow peer as a first hop. The threshold tracks
        // udp.sendConfirmTime (actual message RTT) rather than a fixed 3s, so a
        // transient network-wide latency spike does not demote every peer at once
        // and starve the fast-peer pool (which would collapse tunnel building).
        // Note: only demotes from tiers (not a full first-hop cooldown) so the peer
        // remains selectable if no fast-tier peers are available.
        if (_cfg.getLength() <= 2) {
            long demoteThreshold = getLatencyDemotionThreshold(ctx);
            if (ms > demoteThreshold) {
                Hash peer = _cfg.getFarEnd();
                if (peer != null) {
                    ctx.profileOrganizer().demoteIfHighLatency(peer);
                    if (_log.shouldInfo()) {
                        _log.info("Demoting [" + peer.toBase64().substring(0,6) +
                                  "] due to high latency (" + ms + "ms) on " + _cfg);
                    }
                }
            }
        }

        _cfg.testJobSuccessful(ms);
        // Share success credit with the paired tunnels — mirrors mainline
        // behavior.  Both inbound and outbound tunnels get their failure
        // counters reset on a successful test round-trip.
        if (_outTunnel instanceof PooledTunnelCreatorConfig) {
            ((PooledTunnelCreatorConfig) _outTunnel).testJobSuccessful(ms);
        }
        if (_replyTunnel instanceof PooledTunnelCreatorConfig) {
            ((PooledTunnelCreatorConfig) _replyTunnel).testJobSuccessful(ms);
        }
        _cfg.clearExpeditedTest();

        if (_log.shouldDebug()) {
            _log.debug("Tunnel Test [#" + _testId + "] succeeded in " + ms + "ms → " + _cfg + " (Success rate: " +
                       String.format("%.1f%%", getSuccessRate() * 100) + ")");
        }

        // Clean up session tags
        clearTestTags();
        // Use expedited scheduling if tunnel needs faster retesting
        boolean needsExpedited = _cfg.needsExpeditedTest();
        if (!scheduleRetest(needsExpedited)) {
            cleanupTunnelTracking();
            decrementIfCounted(); // Clean up if couldn't reschedule
        }
    }

    /**
     *  Whether tunnel build success has fallen below the attack threshold.
     *  @return true when build success is below the attack threshold,
     *          indicating the router is struggling to find suitable peers.
     *          In this state, the test cycle should run more slowly and
     *          tolerate more failures to avoid wasting pool build capacity.
     */
    private boolean isDegraded() {
        try {
            return getContext().profileOrganizer().getTunnelBuildSuccess() < ProfileOrganizer.ATTACK_THRESHOLD;
        } catch (Exception e) {
            return false;
        }
    }

    private int getDelay() {
        // Minimum 30s between retests; scale up for reliable tunnels
        // so the test queue prioritizes UNTESTED and failing tunnels.
        // Reliable tunnels that have passed all recent tests only need
        // occasional verification — aggressive retesting consumes slots
        // that could be testing new or recovering tunnels.
        float successRate = getSuccessRate();
        int scaled;
        if (successRate >= 1.0f) {
            scaled = getMinTestDelay(getContext()) * 4; // 100% success → 4x delay (2 min)
        } else if (successRate > 0.5f) {
            scaled = getMinTestDelay(getContext()) * 3 / 2; // >50% success → 1.5x delay
        } else {
            scaled = getMinTestDelay(getContext()); // unreliable or new → fastest retest
        }
        // Backoff for tunnels with many failures: reduce retest frequency to
        // avoid saturating the test queue with DeliveryStatusMessage
        // reply-through failures.  These aren't tunnel problems — the test
        // protocol itself is broken — so there's no value in rapid retesting.
        int failures = _cfg.getTunnelFailures();
        if (failures >= 3) {
            scaled += getMinTestDelay(getContext()) * (failures - 1); // +60s for 3, +90s for 4
        }
        // Degraded mode: multiply delay by 2.  When the router has few
        // connected peers, test messages take longer to round-trip and
        // rapid retesting just saturates the queue.
        if (isDegraded()) {
            scaled *= 2;
        }
        // Tuner retest backoff: when the job queue is under pressure, slow
        // retesting of all tunnels to free test capacity for UNTESTED ones.
        // Applied after degraded mode so the two stack predictably.
        int tunerBackoff = Tuner.getTestRetestBackoff();
        if (tunerBackoff > 100) {
            scaled = scaled * tunerBackoff / 100;
        }
        scaled = Math.min(scaled, getMaxTestDelay(getContext()) * 2);
        // Add a small jitter to avoid thundering herd (ensure positive jitter)
        int jitter = getContext().random().nextInt(Math.max(1, scaled / 3));
        return scaled + jitter;
    }

    private float getSuccessRate() {
        if (_successCount == 0) return DEFAULT_SUCCESS_RATE;
        return (float) _successCount / SUCCESS_HISTORY_SIZE;
    }

    private void updateSuccessHistory(boolean success) {
        // Remove old success from count if it exists
        if (_successHistory[_successHistoryIndex]) {
            _successCount--;
        }
        // Add new success to count
        if (success) {
            _successCount++;
        }
        // Update history
        _successHistory[_successHistoryIndex] = success;
        _successHistoryIndex = (_successHistoryIndex + 1) % SUCCESS_HISTORY_SIZE;
    }

    /**
     * Clears session tags used in the current test to avoid leaks.
     * Consolidated cleanup logic to reduce code duplication.
     */
    private void clearTestTags() {
        if (_encryptTag != null) {
            SessionKeyManager skm = getSessionKeyManager();
            if (skm != null) {
                skm.consumeTag(_encryptTag);
            }
            _encryptTag = null;
        }
        if (_ratchetEncryptTag != null) {
            RatchetSKM rskm = getRatchetKeyManager();
            if (rskm != null) {
                rskm.consumeTag(_ratchetEncryptTag);
            }
            _ratchetEncryptTag = null;
        }
    }

    private void noteSuccess(long ms, TunnelInfo tunnel) {
        if (tunnel != null) {
            for (int i = 0; i < tunnel.getLength(); i++) {
                getContext().profileManager().tunnelTestSucceeded(tunnel.getPeer(i), ms);
            }
        }
    }


    private volatile boolean _successHistory[] = new boolean[SUCCESS_HISTORY_SIZE];
    private volatile int _successHistoryIndex = 0;
    private volatile int _successCount = 0;

    /**
     *  Base removal bar for data-carrying and client/exploratory tunnels.
     *  Under degraded mode (low build success), allow more consecutive failures
     *  before removal so pool churn does not waste scarce build capacity.
     *  @return the base max consecutive failures before removal
     *  @since 0.9.71+
     */
    static int baseRemovalThreshold(boolean degraded) {
        return degraded ? 5 : 3;
    }

    /**
     *  Raise the removal bar when the pool is nearly empty so a burst of
     *  concurrent test failures cannot cascade the last tunnels out before
     *  replacements finish building.  Thin pools (remaining ≤ 2) get +2
     *  extra failures of grace.
     *
     *  @param base the base threshold from {@link #baseRemovalThreshold}
     *  @param remainingTunnels tunnels still in the pool after this removal
     *  @return the effective max consecutive failures before removal
     *  @since 0.9.71+
     */
    static int removalThreshold(int base, int remainingTunnels) {
        if (remainingTunnels <= 2) {
            return base + 2;
        }
        return base;
    }

    /**
     *  Whether an ASAP retest should be deferred because the pool is thin
     *  and the tunnel already has failures.  Scheduling ASAP retries for a
     *  failing tunnel while the pool has ≤ 2 remaining tunnels competes
     *  with replacement builds and risks cascading the last survivors out.
     *
     *  @param remainingTunnels tunnels still in the pool
     *  @param failures consecutive test failures for this tunnel
     *  @return true when the retest should use the normal (non-ASAP) delay
     *  @since 0.9.71+
     */
    static boolean shouldDeferFailingRetest(int remainingTunnels, int failures) {
        return remainingTunnels <= 2 && failures > 0;
    }

    /**
     *  Whether a retest of a GOOD tunnel carrying recent real traffic must
     *  wait for the traffic to stop.  The traffic defer exists so busy
     *  tunnels don't burn test slots or risk load-induced false negatives,
     *  but deferring unconditionally starves a continuously-busy GOOD tunnel
     *  of any test at all — it keeps no fresh latency sample, so the console
     *  never shows a latency for it.  So the defer is load-aware: it holds
     *  only while test slots are actually contended (in-flight at or above
     *  {@code maxConcurrent / ACTIVE_GOOD_EBB_DIVISOR}); during an ebb the
     *  retest runs now and refreshes the latency sample.  Untested tunnels
     *  keep strict priority — they are never subject to this defer, and the
     *  ebb line leaves most of the in-flight cap free for first tests.
     *
     *  @param lastTraffic ms timestamp of the tunnel's last real traffic,
     *         or <= 0 when it has carried none
     *  @param now current time in ms
     *  @param status the tunnel's current test status
     *  @param inFlight tests currently dispatched to the network (all pools)
     *  @param maxConcurrent configured in-flight cap
     *  @return true when the retest should wait for the traffic to ebb
     *  @since 0.9.71+
     */
    static boolean shouldDeferActiveGoodTunnel(long lastTraffic, long now, TunnelTestStatus status,
                                               int inFlight, int maxConcurrent) {
        if (lastTraffic <= 0 || now - lastTraffic >= TRAFFIC_DEFER_MS) return false;
        if (status != TunnelTestStatus.GOOD) return false;
        if (maxConcurrent <= 0) return true;
        return inFlight * ACTIVE_GOOD_EBB_DIVISOR >= maxConcurrent;
    }

    /**
     *  Delay for a retest that was held back because its tunnel is busy:
     *  the time left in the traffic window, clamped to
     *  [{@link #EBB_RETRY_FLOOR_MS}, {@link #ACTIVE_GOOD_EBB_RETRY_MS}], or
     *  the normal retest delay when there is nothing left to wait for.
     *
     *  <p>Unclamped, the remaining window is up to
     *  {@link #TRAFFIC_DEFER_MS}, which is long enough for the tunnel to
     *  expire out from under the retest chain — scheduleRetest() refuses a
     *  delay it cannot fit before expiration and drops the test entirely.
     *  Clamping turns the wait into a short poll: come back while the
     *  tunnel is still alive, re-check contention, and run as soon as it
     *  has ebbed.
     *
     *  @param remainingDeferMs ms left in the traffic window; <= 0 when none
     *  @param normalDelayMs the delay to use when nothing is being waited out
     *  @return the delay in ms, never negative
     *  @since 0.9.71+
     */
    static long activeGoodDeferDelay(long remainingDeferMs, long normalDelayMs) {
        if (remainingDeferMs <= 0) {
            return normalDelayMs;
        }
        return Math.min(Math.max(remainingDeferMs, EBB_RETRY_FLOOR_MS), ACTIVE_GOOD_EBB_RETRY_MS);
    }

    /**
     * Called when the tunnel test fails: record the failure, defer or remove
     * the tunnel through the pool, and schedule the next test.
     *
     * @param timeToFail time in milliseconds the test ran before failing
     */
    private void testFailed(long timeToFail) {
        if (_pool == null || !_pool.isAlive()) {
            cleanupTunnelTracking();
            decrementIfCounted();
            return;
        }

        // Reply-path protection: if the partner tunnel — the one the test was
        // routed through, not the tunnel under test — is 0-hop, the failure is
        // a partner artifact (the paired pool is degraded), not evidence
        // against this tunnel.  Defer rather than count, otherwise a degraded
        // paired pool cascades removals into this pool.
        TunnelInfo partner = _cfg.isInbound() ? _outTunnel : _replyTunnel;
        if (partner != null && partner.getLength() <= 1) {
            if (_log.shouldWarn()) {
                _log.warn("Tunnel Test failed -> 0-hop partner " + partner +
                          " -> Deferring test of " + _cfg);
            }
            getContext().statManager().addRateData("tunnel.testDeferred", _cfg.getLength());
            if (!scheduleRetest(false)) {
                cleanupTunnelTracking();
                decrementIfCounted();
            }
            return;
        }

        // Record the failed round so getSuccessRate() reflects reality and
        // getDelay() retests a failing tunnel sooner rather than slower.
        updateSuccessHistory(false);

        boolean isExploratory = _pool.getSettings().isExploratory();
        getContext().statManager().addRateData(
            isExploratory ? "tunnel.testExploratoryFailedTime" : "tunnel.testFailedTime",
            timeToFail);

        _cfg.clearExpeditedTest();

        // Data-verified trust: a tunnel that has successfully transferred real
        // data has proven itself in production.  Test failures for such tunnels
        // are usually due to reply-path issues (the remote peer used for the
        // return path) or temporary network congestion, not the tunnel itself.
        //
        // However, this trust must be INBOUND-ONLY.  For an inbound tunnel,
        // verified bytes are data that actually reached us, proving the tunnel
        // works in the inbound direction; a test failure is then a reply-path
        // (outbound) false negative and shielding it is correct.  For an OUTBOUND
        // (or exploratory) tunnel, verified bytes are data we sent out — that
        // proves nothing about the reply path returning.  A failing test means
        // the round trip did not complete, so the outbound tunnel may be dead;
        // shielding it would let a dead tunnel persist and block replacement
        // with a working one.  So only inbound tunnels get the traffic exemption.
        //
        // This trust is NOT unlimited.  A tunnel that keeps failing tests despite
        // recent traffic has a broken test reply path or is genuinely degraded.
        // We allow a few exemptions before counting failures normally, preventing
        // immortal tunnels that block pool recovery.
        if (_cfg.getVerifiedBytesTransferred() > 0 && _cfg.isInbound()) {
            getContext().statManager().addRateData(
                "tunnel.testFailedDataTrust", _cfg.getVerifiedBytesTransferred());
            long lastTransfer = _cfg.getLastTransferred();
            long nowMs = System.currentTimeMillis();
            long staleMs = nowMs - lastTransfer;
            if (staleMs < RECENT_TRAFFIC_MS) {
                // Recently carried data — likely a reply-path false negative.
                // Allow up to 1 recent-traffic exemption; after that, treat
                // as a normal failure so the tunnel doesn't become immortal.
                // Reduced from 2 to 1 to prevent broken tunnels from being kept
                // alive too long when they're failing tests but recently carried
                // data — this blocks replacement with working tunnels.
                int recentExemptions = _cfg.getRecentTestExemptions();
                if (recentExemptions < 1) {
                    _cfg.incrementRecentTestExemptions();
                    if (_log.shouldWarn()) {
                        _log.warn("Tunnel Test failed -> Keeping data-carrying tunnel (recent traffic, exemption " +
                                  (recentExemptions + 1) + "/1) \n* " + _cfg +
                                  " -> Verified: " + _cfg.getVerifiedBytesTransferred() +
                                  " bytes, last transfer " + staleMs + "ms ago");
                    }
                    if (!scheduleRetest(false)) {
                        cleanupTunnelTracking();
                        decrementIfCounted();
                    }
                    return;
                }
                // Exceeded recent-traffic exemption limit — count as failure
                if (_log.shouldWarn()) {
                    _log.warn("Tunnel Test failed -> Recent traffic exemption exhausted \n* " + _cfg +
                              " -> Verified: " + _cfg.getVerifiedBytesTransferred() +
                              " bytes, last transfer " + staleMs + "ms ago — counting failure");
                }
            }
            // Stale data or exhausted exemptions — count failure toward removal.
            _cfg.incrementTestFailures();
            _cfg.setTestFailed();
            int currentFailures = _cfg.getTunnelFailures();
            int maxFailures = removalThreshold(baseRemovalThreshold(isDegraded()), _pool.size());
            if (currentFailures > maxFailures) {
                if (_log.shouldWarn()) {
                    _log.warn("Tunnel Test failed -> Removing data-carrying tunnel after " +
                              currentFailures + " consecutive failures \n* " + _cfg +
                              " -> Verified: " + _cfg.getVerifiedBytesTransferred() +
                              " bytes, last transfer " + staleMs + "ms ago");
                }
                getContext().statManager().addRateData(
                    isExploratory ? "tunnel.testExploratoryFailedCompletelyTime" : "tunnel.testFailedCompletelyTime",
                    timeToFail);
                _cfg.tunnelFailedCompletely();
                _pool.tunnelFailed(_cfg);
                cleanupTunnelTracking();
                decrementIfCounted();
                return;
            }
            if (_log.shouldWarn()) {
                _log.warn("Tunnel Test failed -> Keeping data-carrying tunnel \n* " + _cfg +
                          " -> Verified: " + _cfg.getVerifiedBytesTransferred() +
                          " bytes, failures=" + currentFailures + "/" + maxFailures +
                          ", last transfer " + staleMs + "ms ago");
            }
            // Normal delay — don't monopolize the test slot with ASAP retries
            if (!scheduleRetest(false)) {
                cleanupTunnelTracking();
                decrementIfCounted();
            }
            return;
        }

        // Server pools (inbound, non-exploratory) must never lose tunnels
        // mid-lifecycle — they're referenced by the published LeaseSet.
        // Mark them FAILING/FAILED but keep them in the pool until the next
        // LeaseSet republish, when pruneNonGoodTunnels() will clean up
        // (only if enough GOOD tunnels exist to serve the replacement).
        // This prevents the LeaseSet from going empty and ensures smooth
        // tunnel replacement at republish time.
        boolean isServerPool = _pool.getSettings().isInbound() && !isExploratory;

        // Always count the failure against the tunnel under test.
        // Any partner tunnel is better than none — the 3-strike model
        // (or server-pool keep-for-LS-cycle) handles this robustly.
        if (isServerPool) {
            // Server pool: mark failed but don't remove immediately.
            // pruneNonGoodTunnels() handles removal at LS republish.
            // However, route through fail() when failures are high so the
            // zombie ceiling in fail() can trigger — otherwise
            // failures accumulate indefinitely via incrementalTestFailures()
            // without ever being checked, creating zombie tunnels.
            // At >1 failures the tunnel is clearly broken — route through
            // fail() promptly so the zombie ceiling can trigger and prevent
            // pool collapse from accumulating dead tunnels.
            // Reduced from >2 to >1 to prevent broken tunnels from being kept
            // alive too long when they're failing tests — this blocks
            // replacement with working tunnels.
            _cfg.incrementTestFailures();
            _cfg.setTestFailed();
            _pool.notifyServerPoolTestFailed();
            int failures = _cfg.getTunnelFailures();
            if (failures > MAX_SERVER_POOL_TEST_FAILURES) {
                // Hard ceiling: too many consecutive failures — force removal.
                // Without this, dead server pool tunnels accumulate indefinitely
                // because incrementTestFailures() keeps them alive for the
                // LeaseSet republish cycle.  Peer lREgvu had 210 failures
                // in 10 seconds and was never excluded from selection.
                if (_log.shouldWarn()) {
                    _log.warn("Tunnel Test failed -> Removing server pool tunnel after " +
                              failures + " consecutive failures: " + _cfg);
                }
                _cfg.tunnelFailedCompletely();
                _pool.tunnelFailed(_cfg);
                // Dead at the hard ceiling — stop testing it.  Falling through to
                // scheduleRetest() would keep re-queueing tests (and holding the
                // test slot) for a tunnel we just gave up on, until it expires.
                // pruneNonGoodTunnels() removes it from the pool at LS republish.
                cleanupTunnelTracking();
                decrementIfCounted();
                return;
            } else if (failures > 1) {
                // Route through fail() so zombie ceiling can trigger
                _pool.tunnelFailed(_cfg);
            }
            if (_log.shouldWarn()) {
                _log.warn("Tunnel Test failed -> " + _cfg +
                          " (" + failures + " consecutive) — kept for LS cycle");
            }
        } else {
            // Client/exploratory: count failures with adaptive thresholds.
            // Under degraded mode (low build success), allow more consecutive
            // failures before removal.  This prevents pool churn from wasting
            // build resources when the router is struggling to find good peers.
            _cfg.incrementTestFailures();
            _cfg.setTestFailed();
            int currentFailures = _cfg.getTunnelFailures();
            int maxFailures = removalThreshold(baseRemovalThreshold(isDegraded()), _pool.size());
            if (currentFailures > maxFailures) {
                if (_log.shouldWarn()) {
                    _log.warn("Tunnel Test failed -> Removing " + _cfg +
                              (maxFailures > 3 ? " (degraded mode)" : ""));
                }
                getContext().statManager().addRateData(
                    isExploratory ? "tunnel.testExploratoryFailedCompletelyTime" : "tunnel.testFailedCompletelyTime",
                    timeToFail);
                _cfg.tunnelFailedCompletely();
                _pool.tunnelFailed(_cfg);
                cleanupTunnelTracking();
                decrementIfCounted();
                return;
            }
        }
        // Schedule retest with failure-based delay
        if (!scheduleRetest(true)) {
            cleanupTunnelTracking();
            decrementIfCounted();
        }
    }

    /**
     * Compute the tunnel test failure window: how long a test may take before
     * the tunnel is declared failed.
     *
     * When a recent successful-test measurement exists, the window is 2x that
     * average — a test answered in ~2.3s is almost certainly dead if it needs
     * more than ~4.6s.  Before any measurements exist, fall back to mainline's
     * formula (3x transport send processing + 2.5s per hop), which assumes a
     * slow network so early tests don't false-fail.
     *
     * Always clamped to [minPeriod, maxPeriod], with min &lt;= max enforced by
     * construction so a crossed config can't widen the window unintentionally.
     * The pending-message expiration (and thus the reply selector) is tied to
     * the period itself, so a short window never strands a selector.
     *
     * @param successTimeAvgMs average tunnel.testSuccessTime over the last minute,
     *                          NaN if never measured
     * @param sendProcessingMs average transport.sendProcessingTime, NaN if unknown
     * @param outLen outbound tunnel length, &lt;= 0 if no outbound tunnel
     * @param replyLen reply tunnel length, &lt;= 0 if no reply tunnel
     * @param minPeriod minTestPeriod config
     * @param maxPeriod maxTestPeriod config
     * @return the failure window in ms
     * @since 0.9.70+
     */
    static int computeTestPeriod(double successTimeAvgMs, double sendProcessingMs,
                                 int outLen, int replyLen, int minPeriod, int maxPeriod) {
        int period;
        if (outLen <= 0 || replyLen <= 0) {
            period = 15*1000;
        } else if (!Double.isNaN(successTimeAvgMs) && successTimeAvgMs > 0) {
            period = (int) Math.round(2 * successTimeAvgMs);
        } else {
            int base = (!Double.isNaN(sendProcessingMs) && sendProcessingMs > 0) ?
                       (int) (3 * sendProcessingMs) : 15*1000;
            period = base + (2500 * (outLen + replyLen));
        }
        int lo = Math.min(minPeriod, maxPeriod);
        int hi = Math.max(minPeriod, maxPeriod);
        return Math.max(lo, Math.min(hi, period));
    }

    private int getTestPeriod() {
        final RouterContext ctx = getContext();
        double successTime = Double.NaN;
        double sendProcessing = Double.NaN;
        int outLen = 0;
        int replyLen = 0;
        if (_outTunnel != null && _replyTunnel != null) {
            outLen = _outTunnel.getLength();
            replyLen = _replyTunnel.getLength();
            RateStat st = ctx.statManager().getRate("tunnel.testSuccessTime");
            if (st != null) {
                Rate r = st.getRate(60*1000L);
                if (r != null && r.getLastEventCount() > 0)
                    successTime = r.getAverageValue();
            }
            RateStat tspt = ctx.statManager().getRate("transport.sendProcessingTime");
            if (tspt != null) {
                Rate r = tspt.getRate(60*1000L);
                if (r != null && r.getLastEventCount() > 0)
                    sendProcessing = r.getAverageValue();
            }
        }
        return computeTestPeriod(successTime, sendProcessing, outLen, replyLen,
                                 getMinTestPeriod(), getMaxTestPeriod());
    }

    /**
     *  Adaptive latency threshold for demoting a peer as "high latency" after a
     *  tunnel test. Uses the network's current baseline RTT (udp.sendConfirmTime,
     *  the time to send a message and receive its ACK) so that a network-wide
     *  latency spike does not demote every peer and starve the fast-peer pool.
     *  Floored at a fixed minimum so a genuinely slow peer is still demoted on a
     *  fast network.
     *
     *  @param ctx router context
     *  @return demotion threshold in ms
     *  @since 0.9.70+
     */
    private static long getLatencyDemotionThreshold(RouterContext ctx) {
        // Floor: a peer slower than this is demoted even on a fast network.
        long floor = 3000;
        RateStat rtt = ctx.statManager().getRate("udp.sendConfirmTime");
        if (rtt != null) {
            Rate r = rtt.getRate(60*1000L);
            if (r != null && r.getLastEventCount() > 0) {
                // Demote only when a peer's test RTT is ~3x the network baseline.
                long baseline = (long) (3 * r.getAverageValue());
                if (baseline > floor) return baseline;
            }
        }
        return floor;
    }

    private boolean scheduleRetest(boolean asap) {return scheduleRetest(asap, false);}

    /**
     *  Schedule a retest of this tunnel.
     *
     *  @param asap expedite the retest (failing tunnels)
     *  @param deferWhileActive when true, replace the delay with the bounded
     *         ebb retry from {@link #activeGoodDeferDelay}, so the job comes
     *         back to re-check contention shortly instead of vanishing for
     *         the full traffic window — a long push here is what used to let
     *         the tunnel expire out from under the retest chain
     *  @return true if the retest was scheduled
     *  @since 0.9.71+
     */
    boolean scheduleRetest(boolean asap, boolean deferWhileActive) {
        if (_pool == null || !_pool.isAlive()) return false;
        // Never requeue a job into a queue that cannot run it: addJob()
        // silently discards when the queue is dead, so the caller would take
        // the "scheduled" branch while the instance slots stay held forever.
        if (_terminated || state().quiesced || !getContext().jobQueue().isAlive()) {
            return false;
        }

        // Skip retest if the tunnel doesn't have a valid gateway ID anymore
        // (it may have been rebuilt/replaced mid-test).  Only hop 0's relevant
        // ID is populated: inbound tunnels have a receive ID, outbound tunnels
        // have a send ID.  Checking the wrong side yields null, so mirror the
        // direction-aware check used in shouldSchedule().
        TunnelId gwId = _cfg.isInbound() ? _cfg.getReceiveTunnelId(0) : _cfg.getSendTunnelId(0);
        if (gwId == null || gwId.getTunnelId() == 0) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping retest - tunnel gateway ID no longer valid: " + _cfg);
            }
            return false;
        }

        final RouterContext ctx = getContext();
        int delay = getDelay();

        if (deferWhileActive) {
            // Wait for the traffic to ebb, but only for a bounded window:
            // long enough to be worth waiting (floor), short enough that the
            // job comes back while the tunnel is still alive (cap).
            long lastTraffic = _cfg.getLastRealTraffic();
            long remaining = lastTraffic > 0 ? TRAFFIC_DEFER_MS - (ctx.clock().now() - lastTraffic) : 0;
            delay = (int) activeGoodDeferDelay(remaining, delay);
        }

        if (asap) {
            // Thin-pool guard: a failing tunnel must not monopolize ASAP
            // retest slots while the pool has ≤ 2 remaining tunnels —
            // defer to the normal delay so replacement builds win the race.
            if (shouldDeferFailingRetest(_pool.size(), _cfg.getTunnelFailures())) {
                if (_log.shouldDebug()) {
                    _log.debug("Deferring ASAP retest: pool is thin (" +
                               _pool.size() + " remaining, failures=" +
                               _cfg.getTunnelFailures() + ")");
                }
                if (_cfg.getExpiration() > ctx.clock().now() + delay + ((long) 3 * getTestPeriod())) {
                    getTiming().setStartAfter(ctx.clock().now() + delay);
                    ctx.jobQueue().addJob(this);
                    return true;
                }
                return false;
            }
            // As soon as possible: only skip if tunnel is about to expire
            if (_cfg.getExpiration() > ctx.clock().now() + (60 * 1000L)) {
                getTiming().setStartAfter(ctx.clock().now() + delay / 4);
                ctx.jobQueue().addJob(this);
                return true;
            }
        } else {
            // Normal retest: ensure tunnel will live long enough for the test
            if (_cfg.getExpiration() > ctx.clock().now() + delay + ((long) 3 * getTestPeriod())) {
                getTiming().setStartAfter(ctx.clock().now() + delay);
                ctx.jobQueue().addJob(this);
                return true;
            }
        }
        return false;
    }

    private class ReplySelector implements MessageSelector {
        private final long _id;
        private final RoundToken _token;

        /**
         * ReplySelector.
         * @param id the message id being waited on
         * @param token the round this selector belongs to
         */
        public ReplySelector(long id, RoundToken token) {
            _id = id;
            _token = token;
        }

        @Override public boolean continueMatching() {
            // Stop as soon as this round is done or superseded: a selector for
            // an earlier retest must not keep the registry holding a message
            // the instance has already moved past.
            return !_token.completed.get() && _token == _round
                    && getContext().clock().now() < _token.expiration;
        }

        @Override public long getExpiration() { return _token.expiration; }

        @Override public boolean isMatch(I2NPMessage m) {
            return m.getType() == DeliveryStatusMessage.MESSAGE_TYPE &&
                   ((DeliveryStatusMessage) m).getMessageId() == _id;
        }
    }

    private class OnTestReply extends JobImpl implements ReplyJob {
        private long _successTime;
        private OutNetMessage _sentMessage;
        private final RoundToken _token;

        /**
         * Handle the test reply and report the result.
         * @param token the round this callback was created for
         */
        public OnTestReply(RoundToken token) {
            super(TestJob.this.getContext());
            _token = token;
        }

        /**
         * Store the outbound message sent to the gateway for cleanup.
         */
        @Override public String getName() { return "Verify Tunnel Test"; }
        public void setSentMessage(OutNetMessage m) { _sentMessage = m; }

        /**
         * Complete the test, reporting success or failure.
         */
        @Override
        public void runJob() {
            OutNetMessage sent = _sentMessage;
            if (sent != null) {
                getContext().messageRegistry().unregisterPending(sent);
            }
            // Claim first — the timeout job may fire concurrently, and a
            // callback from an earlier round must not touch this one's state.
            if (!claimRound(_token)) {return;}
            releaseRound(_token);
            if (_successTime < _testPeriod) {
                testSuccessful((int) _successTime);
            } else {
                testFailed(_successTime);
            }
        }

        /**
         * Record the arrival time of the reply message.
         */
        @Override
        public void setMessage(I2NPMessage message) {
            _successTime = getContext().clock().now() - ((DeliveryStatusMessage) message).getArrival();
        }
    }

    private class OnTestTimeout extends JobImpl {
        private final long _started;
        private final RoundToken _token;
        private OutNetMessage _sentMessage;

        /**
         * Handle a test timeout, reporting failure.
         * @param now the time the round was dispatched
         * @param token the round this callback was created for
         */
        public OnTestTimeout(long now, RoundToken token) {
            super(TestJob.this.getContext());
            _started = now;
            _token = token;
        }

        /**
         * Report the test as failed on timeout.
         */
        @Override public String getName() { return "Timeout Tunnel Test"; }

        /**
         * Store the outbound message sent to the gateway for cleanup.
         */
        public void setSentMessage(OutNetMessage m) { _sentMessage = m; }

        @Override
        public void runJob() {
            OutNetMessage sent = _sentMessage;
            if (sent != null) {
                getContext().messageRegistry().unregisterPending(sent);
            }
            // Claim before touching shared state: a stale timeout from an
            // earlier round must not clear the tags of a round already in
            // flight, or that round's reply can no longer be decrypted.
            if (!claimRound(_token)) {return;}
            clearTestTags();
            releaseRound(_token);
            testFailed(getContext().clock().now() - _started);
        }
    }

    private SessionKeyManager getSessionKeyManager() {
        final RouterContext ctx = getContext();
        if (_cfg.isInbound() && !_pool.getSettings().isExploratory()) {
            return ctx.clientManager() != null ?
                ctx.clientManager().getClientSessionKeyManager(_pool.getSettings().getDestination()) :
                null;
        } else {
            return ctx.sessionKeyManager();
        }
    }

    private RatchetSKM getRatchetKeyManager() {
        SessionKeyManager skm = getSessionKeyManager();
        if (skm == null) return null;
        if (skm instanceof RatchetSKM) return (RatchetSKM) skm;
        if (skm instanceof MuxedSKM) return ((MuxedSKM) skm).getECSKM();
        if (skm instanceof MuxedPQSKM) return ((MuxedPQSKM) skm).getECSKM();
        return null;
    }

    /**
     * Called when the job is dropped due to router overload, when the batch
     * pump's catch sees a dispatch throw, or when a reset cancels it.
     * Equivalent to {@link #cancelForReset()}.
     */
    @Override
    public void dropped() {
        cancelForReset();
    }

    /**
     * Cancel this instance for a restart, a queue shutdown, or an overload
     * drop: unregister its pending message so no callback can arrive for a
     * round nobody is waiting on, claim the active round (so a reply or
     * timeout that races in cannot complete it), return that round's permits,
     * and release this instance's slots.  Every step is idempotent, so a
     * cancel racing a normal completion is safe.
     *
     * @since 0.9.71+
     */
    void cancelForReset() {
        _terminated = true;
        OutNetMessage sent = _pendingMessage;
        if (sent != null) {
            _pendingMessage = null;
            getContext().messageRegistry().unregisterPending(sent);
        }
        RoundToken token = _round;
        if (claimRound(token)) {
            // No callback will run for this round now, so drop its tags here
            // instead of waiting for a timeout that will be a no-op.
            clearTestTags();
            releaseRound(token);
        }
        cleanupTunnelTracking();
        decrementIfCounted();
    }

}
