package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
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
import net.i2p.router.TunnelInfo;
import net.i2p.router.crypto.ratchet.MuxedPQSKM;
import net.i2p.router.crypto.ratchet.MuxedSKM;
import net.i2p.router.crypto.ratchet.RatchetSKM;
import net.i2p.router.crypto.ratchet.RatchetSessionTag;
import net.i2p.router.networkdb.kademlia.MessageWrapper;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Repeatedly tests a single tunnel for its lifetime to ensure it remains functional.
 * Sends a garlic-encrypted DeliveryStatusMessage through the tunnel and validates the reply.
 * Now includes adaptive backoff, avoids over-penalizing peers on transient failures,
 * and limits the number of concurrent tunnel tests to avoid overwhelming the router.
 */
public class TestJob extends JobImpl {
    private final Log _log;
    private final TunnelPool _pool;
    private final PooledTunnelCreatorConfig _cfg;
    private final AtomicBoolean _found = new AtomicBoolean();
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


    /**
     * Maximum number of tunnel tests that can run concurrently.
     * Prevents overwhelming the router with too many simultaneous tunnel tests.
     * This value can be adjusted based on system capacity.
     * Tunable via i2p.tunnel.testJob.maxConcurrent (default: 64 fast / 32 slow)
     */
    private int getMaxConcurrentTests() {
        return getContext().getProperty("i2p.tunnel.testJob.maxConcurrent",
                                        SystemVersion.isSlow() ? 32 : 64);
    }

    /**
     * Get the minimum test period from config or default (15s).
     * Tunable via i2p.tunnel.testJob.minTestPeriod (default: 15000).
     */
    private int getMinTestPeriod() {
        // Must be >= the 20s minimum in dispatchOutbound to prevent the
        // ReplySelector from expiring before the message arrives.
        return getContext().getProperty("i2p.tunnel.testJob.minTestPeriod", 20*1000);
    }

    /**
     * Get the maximum test period from config or default (30s).
     * Tunable via i2p.tunnel.testJob.maxTestPeriod (default: 30000).
     */
    private int getMaxTestPeriod() {
        return getContext().getProperty("i2p.tunnel.testJob.maxTestPeriod", 30*1000);
    }

    // Adaptive testing frequency constants
    private static int getMinTestDelay(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.testJob.minTestDelay", 30*1000);
    }
    private static int getMaxTestDelay(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.testJob.maxTestDelay", 90*1000);
    }
    private static final int SUCCESS_HISTORY_SIZE = 3; // Track last 3 results
    private static final int MAX_LAG_FOR_SCHEDULE = 150;
    /** Hard ceiling on consecutive test failures for server pool tunnels.
     *  Without this, dead server pool tunnels accumulate indefinitely because
     *  incrementTestFailures() keeps them alive for the LS republish cycle.
     *  At 10+ failures the tunnel is clearly dead — force removal. */
    private static final int MAX_SERVER_POOL_TEST_FAILURES = 10;
    private static double getPoolCoverageThreshold(RouterContext ctx) {
        String val = ctx.getProperty("i2p.tunnel.testJob.poolCoverageThreshold");
        if (val != null) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return 0.95;
    }
    private static int getMaxExploratoryPerPool(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.testJob.maxExploratoryPerPool", 12);
    }
    private static int getMaxClientPerPool(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.testJob.maxClientPerPool", 24);
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
     * Maximum number of TestJob instances that should be queued before deferring new ones.
     * Prevents job queue saturation from too many waiting tunnel tests.
     * Tunable via i2p.tunnel.testJob.maxQueued (default: 192 fast / 96 slow)
     */
    public static volatile int maxQueuedTests = SystemVersion.isSlow() ? 64 : 96;

    /**
     *  Base max queued tests value, read from PROP or static default.
     *  @param ctx router context
     *  @return configured base max queued tests
     */
    private static int getBaseMaxQueuedTests(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.testJob.maxQueued",
                               SystemVersion.isSlow() ? 64 : 96);
    }

    /**
     * Hard limit for total TestJob instances (queued + active).
     * Above this threshold, no new tests are scheduled until count decreases.
     * Prevents ever-increasing backlogs that could cause job lag.
     * Tunable via i2p.tunnel.testJob.hardLimit (default: 512 fast / 384 slow)
     */
    public static int getHardLimit(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.testJob.hardLimit",
                               SystemVersion.isSlow() ? 384 : 512);
    }

    /**
     * Atomic counter for total TestJob instances (active + queued).
     * Provides more reliable limiting than relying on job queue counting.
     */
    private static final AtomicInteger TOTAL_TEST_JOBS = new AtomicInteger(0);

    /**
     * Track which tunnels currently have tests running to prevent multiple concurrent tests per tunnel.
     * Key: tunnel key (Long), Value: TestJob instance
     */
    private static final ConcurrentHashMap<Long, TestJob> RUNNING_TESTS = new ConcurrentHashMap<>();

    /**
     * Track which tunnel pools currently have tests running to ensure better coverage across pools.
     * Key: pool identifier, Value: number of running tests in that pool
     */
    private static final ConcurrentHashMap<String, AtomicInteger> POOL_TEST_COUNTS = new ConcurrentHashMap<>();

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
     * Generate a unique identifier for a tunnel pool to track test coverage.
     * @param pool the tunnel pool
     * @return unique identifier for the pool
     */
    private static String getPoolId(TunnelPool pool) {
        if (pool == null) return "unknown";
        if (pool.getSettings().isExploratory()) {
            return pool.getSettings().isInbound() ? "exploratory-inbound" : "exploratory-outbound";
        } else {
            String nickname = pool.getSettings().getDestinationNickname();
            if (nickname != null) {
                return "client-" + nickname + "-" + (pool.getSettings().isInbound() ? "inbound" : "outbound");
            } else {
                return "client-" + pool.getSettings().getDestination().toBase32().substring(0,8) +
                       "-" + (pool.getSettings().isInbound() ? "inbound" : "outbound");
            }
        }
    }

    /** Flag to indicate if this job is valid and should be queued */
    private boolean _valid = true;

    /**
     * Get the total number of TestJob instances (active + queued) using atomic counter.
     * This provides more reliable limiting than job queue counting alone.
     *
     * @return the total number of TestJob instances
     */
    private static int getTotalTestJobCount() {
        return TOTAL_TEST_JOBS.get();
    }

    /**
     *  Get the current number of queued + active test jobs for capacity planning.
     *  @since 0.9.69+
     */
    public static int getCurrentTestJobCount() {
        return TOTAL_TEST_JOBS.get();
    }

    /**
     *  Get the maximum number of queued test jobs allowed before deferring.
     *  @since 0.9.69+
     */
    public static int getMaxTestJobs() {
        return maxQueuedTests;
    }

    /** Ensures total job counter is decremented at most once */
    private final AtomicBoolean _counted = new AtomicBoolean(true);

    /**
     * Static method to check if a TestJob should be created and scheduled.
     * This prevents creating invalid job objects that would have timing issues.
     * @param ctx the router context
     * @param cfg the tunnel config
     * @return true if the job should be created and scheduled, false otherwise
     */
    public static boolean shouldSchedule(RouterContext ctx, PooledTunnelCreatorConfig cfg) {
        // Skip testing if tunnel doesn't have valid IDs yet (not fully built).
        // Outbound tunnels only have a send tunnel ID at hop 0 (the gateway);
        // inbound tunnels only have a receive tunnel ID at hop 0.
        try {
            if (cfg.isInbound()) {
                long recvId = cfg.getReceiveTunnelId(0).getTunnelId();
                if (recvId == 0) return false;
            } else {
                long sendId = cfg.getSendTunnelId(0).getTunnelId();
                if (sendId == 0) return false;
            }
        } catch (Exception e) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldDebug()) {
                log.debug("Skipping test - tunnel not ready: " + cfg, e);
            }
            return false;
        }

        // Skip tunnel testing for ping tunnels - they're short-lived and don't need testing
        TunnelPool pool = cfg.getTunnelPool();
        if (pool != null) {
            String tunnelNickname = pool.getSettings().getDestinationNickname();
            if (tunnelNickname != null && (tunnelNickname.equals("I2Ping") ||
                (tunnelNickname.startsWith("Ping") && tunnelNickname.contains("[")))) {
                Log log = ctx.logManager().getLog(TestJob.class);
                if (log.shouldDebug()) {
                    log.debug("Skipping test scheduling for ping tunnel: " + tunnelNickname);
                }
                return false;
            }
        }

        // Skip testing if tunnel is scheduled for early expiry (already pruned)
        long now = ctx.clock().now();
        if (cfg.getExpiration() < now + TunnelPool.DEFAULT_PRUNE_EARLY_EXPIRY) {
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
        boolean isFirstTest = (cfg.getTestStatus() == net.i2p.router.TunnelTestStatus.UNTESTED);
        if (isFirstTest) {
            int current = TOTAL_TEST_JOBS.get();
            // Check capacity: critical pools (0 GOOD) always get through,
            // non-critical ones wait until queue headroom frees up.
            if (current >= maxQueuedTests) {
                if (pool != null && !pool.getSettings().isExploratory() &&
                    pool.getActiveTunnelCount() == 0) {
                    // critical — bypass the cap
                } else {
                    return false;
                }
            }
            // Per-pool cap applies to first tests too — without this,
            // multiple UNTESTED tunnels in the same pool all bypass the
            // cap and saturate the queue.
            if (pool != null && !pool.getSettings().isExploratory()) {
                String poolId = getPoolId(pool);
                // Critical pools (0 active tunnels) bypass the per-pool budget
                // to ensure UNTESTED tunnels get tested immediately.
                int activeCount = pool.getActiveTunnelCount();
                boolean poolCritical = activeCount == 0;
                if (!poolCritical) {
                    int poolTestBudget = 2;
                    AtomicInteger poolCount = POOL_TEST_COUNTS.computeIfAbsent(poolId, k -> new AtomicInteger(0));
                    int prev = poolCount.getAndIncrement();
                    if (prev >= poolTestBudget) {
                        poolCount.decrementAndGet();
                        return false;
                    }
                }
            }
            if (!TOTAL_TEST_JOBS.compareAndSet(current, current + 1)) {
                return false;
            }
            Long tunnelKey = getTunnelKey(cfg);
            if (tunnelKey != null && RUNNING_TESTS.containsKey(tunnelKey)) {
                TOTAL_TEST_JOBS.decrementAndGet();
                return false;
            }
            return true;
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
        int maxTestJobs = Math.min(maxQueuedTests, Math.max(activeRunners, Math.max(numPools * 3, 12)));
        int currentTestJobs = getTotalTestJobCount();
        boolean isCritical = false;
        if (pool != null && !pool.getSettings().isExploratory()) {
            int activeCount = pool.getActiveTunnelCount();
            int target = pool.getSettings().getTotalQuantity();
            isCritical = activeCount == 0 || (activeCount < target && activeCount <= 2);
            if (isCritical && !cfg.needsExpeditedTest()) {
                cfg.requestExpeditedTest();
            }
        }
        if (!cfg.needsExpeditedTest() && pool != null && !pool.getSettings().isExploratory() &&
            pool.getActiveTunnelCount() == 0) {
            cfg.requestExpeditedTest();
        }
        boolean isExpedited = cfg.needsExpeditedTest();
        long expeditedLagLimit = isExpedited ? MAX_LAG_FOR_SCHEDULE * 2 : MAX_LAG_FOR_SCHEDULE;
        int expeditedJobLimit = isExpedited ? maxTestJobs + maxTestJobs / 2 : maxTestJobs;
        if (!isCritical && (readyCount > activeRunners || maxLag > expeditedLagLimit || currentTestJobs >= expeditedJobLimit)) {
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

        int current = TOTAL_TEST_JOBS.get();
        if (!isCritical && current >= maxQueuedTests) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldInfo()) {
                log.info("Limit (" + maxQueuedTests + ") reached -> Not scheduling test for " + cfg);
            }
            return false;
        }

        if (!TOTAL_TEST_JOBS.compareAndSet(current, current + 1)) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldDebug()) {
                log.debug("Concurrent test limit reached -> Not scheduling test for " + cfg);
            }
            return false;
        }

        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey != null && RUNNING_TESTS.containsKey(tunnelKey)) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldDebug()) {
                log.debug("Test already running for tunnel key " + tunnelKey + " -> Skipping duplicate test for " + cfg);
            }
            return false;
        }

        if (pool != null) {
            String poolId = getPoolId(pool);
            // Critical pools (0 active tunnels) bypass the per-pool budget.
            // UNTESTED tunnels must always get test priority to prevent pool
            // collapse — without tested tunnels, the LeaseSet expires and
            // the destination becomes unreachable.
            int activeCount = pool.getActiveTunnelCount();
            boolean poolCritical = !pool.getSettings().isExploratory() && activeCount == 0;
            if (!poolCritical) {
                int poolTestBudget;
                if (pool.getSettings().isExploratory()) {
                    poolTestBudget = getMaxExploratoryPerPool(ctx);
                } else {
                    poolTestBudget = 2;
                }
                AtomicInteger poolCount = POOL_TEST_COUNTS.computeIfAbsent(poolId, k -> new AtomicInteger(0));
                int prev = poolCount.getAndIncrement();
                if (prev >= poolTestBudget) {
                    poolCount.decrementAndGet();
                    Log log = ctx.logManager().getLog(TestJob.class);
                    if (log.shouldDebug()) {
                        log.debug("Pool " + poolId + " has " + prev +
                              " tests (budget " + poolTestBudget + ") -> Deferring");
                    }
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Check if this TestJob instance is valid and should be queued.
     * @return true if valid, false if it should not be queued
     */
    public boolean isValid() {
        return _valid;
    }

    /**
     * Verify total job counter not over hard limit (slot reserved in shouldSchedule).
     * @param ctx the router context
     * @return true if under hard limit, false if exceeded
     */
    private static boolean isUnderHardLimit(RouterContext ctx) {
        int current = TOTAL_TEST_JOBS.get();
        return current < getHardLimit(ctx);
    }

    /**
     * Atomically decrement total job counter.
     * Used only by constructor invalidation paths that run before the job is
     * queued (and so can never race {@link #dropped()}).  Runtime completion
     * paths must use {@link #decrementIfCounted()} for idempotency.
     */
    private static void decrementTotalJobs() {
        TOTAL_TEST_JOBS.decrementAndGet();
    }

    /**
     * Idempotently decrement the total job counter for this instance.
     * Once queued, a job may complete on one thread and be dropped on another;
     * the {@code _counted} gate ensures the counter is only released once,
     * preventing drift that would break the scheduling caps.
     */
    private void decrementIfCounted() {
        if (_counted.compareAndSet(true, false)) {
            TOTAL_TEST_JOBS.decrementAndGet();
        }
    }

    /**
     * Clean up this test job from tunnel tracking.
     * Must be called when a test job completes or is cancelled.
     * Note: This does NOT affect the TOTAL_TEST_JOBS counter.
     */
    private void cleanupTunnelTracking() {
        Long tunnelKey = getTunnelKey(_cfg);
        if (tunnelKey != null) {
            RUNNING_TESTS.remove(tunnelKey, this);
        }

        // Clean up pool test count tracking
        TunnelPool pool = _pool;
        if (pool != null) {
            String poolId = getPoolId(pool);
            AtomicInteger poolCount = POOL_TEST_COUNTS.get(poolId);
            if (poolCount != null) {
                poolCount.decrementAndGet();
                // Remove from map if count reaches zero to prevent memory leak
                if (poolCount.get() <= 0) {
                    POOL_TEST_COUNTS.remove(poolId);
                }
            }
        }
    }

    public TestJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        super(ctx);
        _log = ctx.logManager().getLog(TestJob.class);
        _cfg = cfg;
        _pool = (pool != null) ? pool : cfg.getTunnelPool();
        if (_pool == null) {
            if (_log.shouldError()) {
                _log.error("Invalid Tunnel Test configuration → No pool for " + cfg, new Exception("origin"));
            }
            _valid = false;
            return;
        }
        // Pool test count was already claimed by shouldSchedule() —
        // don't double-increment here.  Cleanup paths below handle
        // the decrement if this TestJob is later invalidated.

        // Register this test as running for the tunnel
        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey == null) {
            if (_log.shouldWarn())
                _log.warn("Failed to generate tunnel key -> Invalidating test for " + cfg);
            if (_pool != null) {
                String poolId = getPoolId(_pool);
                AtomicInteger poolCount = POOL_TEST_COUNTS.get(poolId);
                if (poolCount != null) {
                    poolCount.decrementAndGet();
                    if (poolCount.get() <= 0) {
                        POOL_TEST_COUNTS.remove(poolId);
                    }
                }
            }
            decrementTotalJobs();
            _valid = false;
            return;
        }

        TestJob existing = RUNNING_TESTS.putIfAbsent(tunnelKey, this);
        if (existing != null) {
            if (_log.shouldDebug()) {
                _log.debug("Test already registered for tunnel key " + tunnelKey + " -> Invalidating duplicate test for " + cfg);
            }
            // Clean up pool registration since we're not proceeding
            if (_pool != null) {
                String poolId = getPoolId(_pool);
                AtomicInteger poolCount = POOL_TEST_COUNTS.get(poolId);
                if (poolCount != null) {
                    poolCount.decrementAndGet();
                    if (poolCount.get() <= 0) {
                        POOL_TEST_COUNTS.remove(poolId);
                    }
                }
            }
            decrementTotalJobs(); // Slot reserved in shouldSchedule, now unused
            _valid = false;
            return;
        }

        // Verify total job counter not over hard limit (slot reserved in shouldSchedule)
        if (!isUnderHardLimit(ctx)) {
            if (_log.shouldInfo()) {
                _log.info("Hard limit (" + getHardLimit(ctx) + ") reached -> Not scheduling test for " + cfg);
            }
            // Clean up tunnel registration
            if (tunnelKey != null) {
                RUNNING_TESTS.remove(tunnelKey, this);
            }
            // Clean up pool registration
            if (_pool != null) {
                String poolId = getPoolId(_pool);
                AtomicInteger poolCount = POOL_TEST_COUNTS.get(poolId);
                if (poolCount != null) {
                    poolCount.decrementAndGet();
                    if (poolCount.get() <= 0) {
                        POOL_TEST_COUNTS.remove(poolId);
                    }
                }
            }
            decrementTotalJobs(); // Slot reserved in shouldSchedule, now unused
            _valid = false;
            return;
        }
        // Test immediately after tunnel build completes.  The test period
        // (45-90s) already provides generous tolerance for transient latency.
        long startTime = ctx.clock().now();
        getTiming().setStartAfter(startTime);
    }

    @Override
    public String getName() {
        return "Test Local Tunnel";
    }

    @Override
    public void runJob() {
        final RouterContext ctx = getContext();
        if (_pool == null || !_pool.isAlive()) {
            cleanupTunnelTracking();
            decrementIfCounted();
            return;
        }

        // Check for graceful shutdown
        if (ctx.router().gracefulShutdownInProgress()) {
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

            // Client tunnels: defer under pressure but don't abort
            if (_log.shouldWarn()) {
                _log.warn("Deferring test due to job lag (" + maxLag + "ms) -> " + _cfg);
            }
            scheduleRetest(_cfg.needsExpeditedTest());
            cleanupTunnelTracking();
            decrementIfCounted();
            return;
        }

        // Begin tunnel test logic.  Reset the completion gate for this round —
        // the same TestJob instance is reused across retests.
        _found.set(false);
        long now = ctx.clock().now();

        // Set test status to TESTING
        _cfg.setTestStarted();

        if (_cfg.isInbound()) {
            // Skip testing inbound tunnels that recently received data —
            // they're obviously working and testing risks false failures on
            // high-bandwidth tunnels.  BUT only for tunnels already marked
            // GOOD — UNTESTED tunnels must be tested regardless.  Otherwise
            // active clients (e.g. I2PSnark) receive data on every new tunnel
            // before the test runs, the test is perpetually skipped, the
            // tunnel stays UNTESTED forever, and the pool never accumulates
            // GOOD tunnels → EMERGENCY build storm.
            if (_cfg.getTestStatus() == net.i2p.router.TunnelTestStatus.GOOD &&
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
                            if (t.getExpiration() > now) {
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
            if (_cfg.getTestStatus() != net.i2p.router.TunnelTestStatus.UNTESTED &&
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
                            if (t.getExpiration() > now) {
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
        long testExpiration = now + _testPeriod;

        DeliveryStatusMessage m = new DeliveryStatusMessage(ctx);
        m.setArrival(now);
        m.setMessageExpiration(testExpiration);
        m.setMessageId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));

        ReplySelector sel = new ReplySelector(m.getMessageId(), testExpiration);
        OnTestReply onReply = new OnTestReply();
        OnTestTimeout onTimeout = new OnTestTimeout(now);
        OutNetMessage msg = ctx.messageRegistry().registerPending(sel, onReply, onTimeout);
        onReply.setSentMessage(msg);

        boolean sendSuccess = sendTest(m, _testPeriod);
        if (!sendSuccess) {
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
     * @return true if message was sent successfully, false otherwise
     */
    private boolean sendTest(I2NPMessage m, int testPeriod) {
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

        ctx.tunnelDispatcher().dispatchOutbound(
            m,
            _outTunnel.getSendTunnelId(0),
            _replyTunnel.getReceiveTunnelId(0),
            _replyTunnel.getPeer(0)
        );
        return true;
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
        // If it exceeds 3s, demote the peer from fast/high-cap tiers immediately
        // so the peer selector avoids re-selecting this slow peer as a first hop.
        // Note: only demotes from tiers (not a full first-hop cooldown) so the peer
        // remains selectable if no fast-tier peers are available.
        if (ms > 3000 && _cfg.getLength() <= 2) {
            Hash peer = _cfg.getFarEnd();
            if (peer != null) {
                ctx.profileOrganizer().demoteIfHighLatency(peer);
                if (_log.shouldInfo()) {
                    _log.info("Demoting [" + peer.toBase64().substring(0,6) +
                              "] due to high latency (" + ms + "ms) on " + _cfg);
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
     * Called when the tunnel test fails.
     * @param timeToFail time in milliseconds before the test failed
     */
    private void testFailed(long timeToFail) {
        if (_pool == null || !_pool.isAlive()) {
            cleanupTunnelTracking();
            decrementIfCounted();
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
        // However, this trust is NOT unlimited.  A tunnel that keeps failing
        // tests despite recent traffic has a broken test reply path or is
        // genuinely degraded.  We allow a few exemptions before counting
        // failures normally, preventing immortal tunnels that block pool
        // recovery.
        if (_cfg.getVerifiedBytesTransferred() > 0) {
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
            int maxFailures = isDegraded() ? 3 : 2;
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
            int maxFailures = isDegraded() ? 5 : 3;
            if (currentFailures > maxFailures) {
                if (_log.shouldWarn()) {
                    _log.warn("Tunnel Test failed -> Removing " + _cfg +
                              " after " + currentFailures + " consecutive failures" +
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

    private int getTestPeriod() {
        final RouterContext ctx = getContext();
        if (_outTunnel == null || _replyTunnel == null) return 15*1000;

        // Use mainline's formula: 3x transport avg + 2.5s per hop.
        // No upper cap — slow networks need generous timeouts to avoid
        // false test failures that trigger unnecessary pool churn.
        RateStat tspt = ctx.statManager().getRate("transport.sendProcessingTime");
        if (tspt != null) {
            Rate r = tspt.getRate(60*1000L);
            if (r != null) {
                int delay = 3 * (int) r.getAverageValue();
                return delay + (2500 * (_outTunnel.getLength() + _replyTunnel.getLength()));
            }
        }
        return 15*1000;
    }

    private boolean scheduleRetest(boolean asap) {
        if (_pool == null || !_pool.isAlive()) return false;

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

        if (asap) {
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
        private final long _expiration;

        public ReplySelector(long id, long expiration) {
            _id = id;
            _expiration = expiration;
        }

        @Override public boolean continueMatching() {
            return !_found.get() && getContext().clock().now() < _expiration;
        }

        @Override public long getExpiration() { return _expiration; }

        @Override public boolean isMatch(I2NPMessage m) {
            return m.getType() == DeliveryStatusMessage.MESSAGE_TYPE &&
                   ((DeliveryStatusMessage) m).getMessageId() == _id;
        }
    }

    private class OnTestReply extends JobImpl implements ReplyJob {
        private long _successTime;
        private OutNetMessage _sentMessage;

        public OnTestReply() { super(TestJob.this.getContext()); }

        @Override public String getName() { return "Verify Tunnel Test"; }
        public void setSentMessage(OutNetMessage m) { _sentMessage = m; }

        @Override
        public void runJob() {
            if (_sentMessage != null)
                getContext().messageRegistry().unregisterPending(_sentMessage);
            // Claim completion atomically — the timeout job may fire concurrently.
            if (!_found.compareAndSet(false, true)) {return;}
            if (_successTime < _testPeriod) {
                testSuccessful((int) _successTime);
            } else {
                testFailed(_successTime);
            }
        }

        @Override
        public void setMessage(I2NPMessage message) {
            _successTime = getContext().clock().now() - ((DeliveryStatusMessage) message).getArrival();
        }
    }

    private class OnTestTimeout extends JobImpl {
        private final long _started;

        public OnTestTimeout(long now) {
            super(TestJob.this.getContext());
            _started = now;
        }

        @Override public String getName() { return "Timeout Tunnel Test"; }

        @Override
        public void runJob() {
            clearTestTags();
            // Claim completion atomically — the reply job may fire concurrently.
            if (_found.compareAndSet(false, true)) {
                testFailed(getContext().clock().now() - _started);
            }
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
     * Called when the job is dropped due to router overload.
     * Ensure we clean up the total job counter when dropped.
     */
    @Override
    public void dropped() {
        cleanupTunnelTracking();
        decrementIfCounted();
    }

}
