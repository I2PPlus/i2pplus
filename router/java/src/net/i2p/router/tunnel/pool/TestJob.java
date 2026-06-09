package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.crypto.SessionKeyManager;
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
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
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
    private boolean _found;
    private TunnelInfo _outTunnel;
    private TunnelInfo _replyTunnel;
    private PooledTunnelCreatorConfig _otherTunnel;
    private SessionTag _encryptTag;
    private RatchetSessionTag _ratchetEncryptTag;
    private static final AtomicInteger __id = new AtomicInteger();
    private int _id;
    private static final int MIN_TEST_PERIOD = 45*1000;
    private static final int MAX_TEST_PERIOD = 50*1000;

    /**
     * Maximum number of tunnel tests that can run concurrently.
     * Prevents overwhelming the router with too many simultaneous tunnel tests.
     * This value can be adjusted based on system capacity.
     */
    private static final int MAX_CONCURRENT_TESTS = SystemVersion.isSlow() ? 64 : 128;

    // Adaptive testing frequency constants
    private static final int MIN_TEST_DELAY = 30 * 1000; // 30s minimum
    private static final int MAX_TEST_DELAY = 90 * 1000; // 90s maximum
    private static final int SUCCESS_HISTORY_SIZE = 3; // Track last 3 results
    private static final int MIN_TEST_JOBS_PER_RUNNER = 20;
    private static final int MAX_LAG_FOR_SCHEDULE = 150;
    private static final double POOL_COVERAGE_THRESHOLD = 0.9; // 90% of maxQueuedTests
    private static final int MAX_EXPLORATORY_PER_POOL = 8;
    private static final int MAX_CLIENT_PER_POOL = 16;
    private static final float DEFAULT_SUCCESS_RATE = 0.5f; // 50% for new tunnels
    private static final int LAG_SEVERE_MAX = 2500;
    private static final int LAG_SEVERE_AVG = 15;
    private static final int MAX_LAG_RESCHEDULE = 200;

    /**
     * Maximum number of TestJob instances that should be queued before deferring new ones.
     * Prevents job queue saturation from too many waiting tunnel tests.
     */
    private static final int MAX_QUEUED_TESTS = SystemVersion.isSlow() ? 40 : 80;
    public static volatile int maxQueuedTests = MAX_QUEUED_TESTS;

    /**
     * Hard limit for total TestJob instances (queued + active).
     * Above this threshold, no new tests are scheduled until count decreases.
     * Prevents ever-increasing backlogs that could cause job lag.
     */
    public static final int HARD_TEST_JOB_LIMIT = SystemVersion.isSlow() ? 320 : 1024;

    /**
     * Static counter tracking the number of currently active tunnel tests.
     * This ensures that the router does not exceed MAX_CONCURRENT_TESTS.
     */
    private static final AtomicInteger CONCURRENT_TESTS = new AtomicInteger(0);

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

        // First-ever test — always schedule immediately.  An untested tunnel
        // is useless; the CONCURRENT_TESTS limit in runJob() will throttle
        // execution across the job queue.  Only skip for validity reasons
        // (ping tunnel, bad IDs, early expiry, or duplicate).
        boolean isFirstTest = (cfg.getTestStatus() == net.i2p.router.TunnelTestStatus.UNTESTED);
        if (isFirstTest) {
            // Still reserve a TOTAL_TEST_JOBS slot so the constructor and
            // test-completion cleanup balance correctly.
            int current = TOTAL_TEST_JOBS.get();
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
            List<TunnelPool> poolList = new ArrayList<TunnelPool>();
            ctx.tunnelManager().listPools(poolList);
            numPools = poolList.size();
        } else {
            numPools = 0;
        }
        int maxTestJobs = Math.min(maxQueuedTests, Math.max(activeRunners, Math.max(numPools * 12, 24)));
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
        int expeditedJobLimit = isExpedited ? maxTestJobs * 2 : maxTestJobs;
        if (!isCritical && (readyCount > activeRunners || maxLag > expeditedLagLimit || currentTestJobs >= expeditedJobLimit)) {
            Log log = ctx.logManager().getLog(TestJob.class);
            if (log.shouldInfo()) {
                log.info("Job queue lagging or too many test jobs (" + readyCount + " ready jobs, maxLag=" + maxLag +
                         "ms, testJobs=" + currentTestJobs + "/" + maxTestJobs + ", expedited=" + isExpedited + ") -> Not scheduling test for " + cfg);
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
                log.debug("Concurrent limit reached -> Not scheduling test for " + cfg);
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
            AtomicInteger poolCount = POOL_TEST_COUNTS.get(poolId);
            if (poolCount != null && poolCount.get() > 0) {
                if (pool.getSettings().isExploratory() && poolCount.get() >= MAX_EXPLORATORY_PER_POOL &&
                        current > (int)(maxQueuedTests * POOL_COVERAGE_THRESHOLD)) {
                    Log log = ctx.logManager().getLog(TestJob.class);
                    if (log.shouldDebug()) {
                        log.debug("Pool " + poolId + " already has " + poolCount.get() + " tests running -> Deferring for better coverage");
                    }
                    return false;
                }
                else if (!pool.getSettings().isExploratory() && poolCount.get() >= MAX_CLIENT_PER_POOL &&
                        current > (int)(maxQueuedTests * POOL_COVERAGE_THRESHOLD)) {
                    Log log = ctx.logManager().getLog(TestJob.class);
                    if (log.shouldDebug()) {
                        log.debug("Pool " + poolId + " already has " + poolCount.get() + " tests running -> Deferring for better coverage");
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
        return current < HARD_TEST_JOB_LIMIT;
    }

    /**
     * Atomically decrement total job counter.
     * Must be called when a test job completes or is cancelled.
     */
    private static void decrementTotalJobs() {
        TOTAL_TEST_JOBS.decrementAndGet();
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
        // Track pool test count for coverage management
        if (_pool != null) {
            String poolId = getPoolId(_pool);
            POOL_TEST_COUNTS.computeIfAbsent(poolId, k -> new AtomicInteger(0)).incrementAndGet();
        }

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
                _log.info("Hard limit (" + HARD_TEST_JOB_LIMIT + ") reached -> Not scheduling test for " + cfg);
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
            decrementTotalJobs();
            return;
        }

        // Check for graceful shutdown
        if (ctx.router().gracefulShutdownInProgress()) {
            cleanupTunnelTracking();
            decrementTotalJobs();
            return;
        }

        // Determine if this is an exploratory tunnel early for deprioritization logic
        boolean isExploratory = _pool.getSettings().isExploratory();

        long maxLag = ctx.jobQueue().getMaxLag();
        long avgLag = ctx.jobQueue().getAvgLag();
        if (maxLag > LAG_SEVERE_MAX || avgLag > LAG_SEVERE_AVG) {
            // Skip exploratory tunnels first under extreme pressure
            if (isExploratory) {
                if (_log.shouldInfo()) {
                    _log.info("Skipping exploratory tunnel test due to severe job lag (Max: " + maxLag + " / Avg: " + avgLag + "ms) -> " + _cfg);
                }
                ctx.statManager().addRateData("tunnel.testExploratorySkipped", _cfg.getLength());
                if (!scheduleRetest(_cfg.needsExpeditedTest())) {
                    cleanupTunnelTracking();
                    decrementTotalJobs();
                }
                return;
            }

            // Still test client tunnels unless lag is extreme
            if (_log.shouldWarn()) {
                _log.warn("Aborted test due to severe max job lag (Max: " + maxLag + " / Avg: " + avgLag + "ms) → " + _cfg);
            }
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            cleanupTunnelTracking();
            decrementTotalJobs();
            return; // Exit without rescheduling
        }

        // Calculate adaptive max queued tests based on system load.
        // Scale capacity when the queue has headroom, but keep it
        // bounded — excess tests don't improve success rate and only
        // recycle failing tunnels.
        if (maxLag < 500 && avgLag < 5) {
            // Very low lag — can handle substantial test throughput
            maxQueuedTests = MAX_QUEUED_TESTS * 10; // 400
        } else if (maxLag < 1000 && avgLag < 15) {
            // Low lag — moderate headroom
            maxQueuedTests = MAX_QUEUED_TESTS * 6; // 240
        } else if (maxLag < 1500 && avgLag < 30) {
            // Some lag — slight headroom
            maxQueuedTests = MAX_QUEUED_TESTS * 2; // 80
        } else {
            // Queue has lag — be conservative
            maxQueuedTests = MAX_QUEUED_TESTS; // 40
        }

        // Cap adaptive queue limit at hard limit to prevent exceeding configured maximum
        maxQueuedTests = Math.min(maxQueuedTests, HARD_TEST_JOB_LIMIT);

        // Update public static field for JobQueueHelper display
        TestJob.maxQueuedTests = maxQueuedTests;

        // Check for queue saturation and hard limits before proceeding
        int totalCount = getTotalTestJobCount();

        if (totalCount >= HARD_TEST_JOB_LIMIT) {
            if (_log.shouldInfo()) {
                _log.info("Hard limit reached (" + totalCount + " >= " + HARD_TEST_JOB_LIMIT +
                          ") -> Cancelling test of " + _cfg);
            }
            ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
            ctx.statManager().addRateData("jobQueue.testJobHardLimit", 1);
            cleanupTunnelTracking();
            decrementTotalJobs(); // Clean up counter since we won't proceed
            return; // Exit without rescheduling
        }

        // Check for queue saturation using atomic counter instead of job queue count
        // This prevents race conditions and ensures consistent limiting
        if (totalCount >= maxQueuedTests) {
            // Under queue pressure, deprioritize exploratory tunnels.
            // Drop exploratory tests to free capacity; reschedule client tunnels.
            if (isExploratory) {
                if (_log.shouldInfo()) {
                    _log.info("TestJob queue saturated -> Dropping exploratory tunnel test (" + totalCount + " >= " + maxQueuedTests + ")");
                }
                ctx.statManager().addRateData("tunnel.testExploratorySkipped", _cfg.getLength());
                cleanupTunnelTracking();
                decrementTotalJobs();
                return;
            }

            // Client tunnels get priority — reschedule them
            if (_log.shouldInfo()) {
                _log.info("TestJob queue saturated -> Rescheduling client tunnel test (" + totalCount + " >= " + maxQueuedTests + ") for " + _cfg);
            }
            ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
            if (!scheduleRetest(_cfg.needsExpeditedTest())) {
                cleanupTunnelTracking();
                decrementTotalJobs();
            }
            return;
        }

        // Concurrency control: Check and increment counter
        // Adaptive limits based on lag to balance testing and performance
        // Exploratory tunnels are deprioritized under high load
        int maxTests;
        if (maxLag > 3000 || avgLag > 100) {
            maxTests = isExploratory ? 8 : 12; // Severe load - reduce but don't stall
        } else if (maxLag > 2000 || avgLag > 50) {
            maxTests = isExploratory ? 12 : 24; // High load
        } else if (maxLag > 1000 || avgLag > 20) {
            maxTests = isExploratory ? 32 : 64; // Moderate load
        } else if (maxLag > 400 || avgLag > 5) {
            maxTests = isExploratory ? 64 : 128; // Low lag - relax limits
        } else {
            maxTests = isExploratory ? 128 : 256; // Very low lag (<1ms avg) - maximum throughput
        }

        int current;
        do {
            current = CONCURRENT_TESTS.get();
            if (current >= maxTests) {
                if (_log.shouldInfo()) {
                    _log.info("Max " + maxTests + " concurrent tunnel tests reached \n* Rescheduling test for " + _cfg +
                              " (Queued: " + totalCount + ")");
                }
                ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
                if (!scheduleRetest(_cfg.needsExpeditedTest())) {
                    cleanupTunnelTracking();
                    decrementTotalJobs();
                }
                return;
            }
        } while (!CONCURRENT_TESTS.compareAndSet(current, current + 1));

        // Begin tunnel test logic
        _found = false;
        long now = ctx.clock().now();

        // Set test status to TESTING
        _cfg.setTestStarted();

        if (_cfg.isInbound()) {
            // Skip testing inbound tunnels that recently received data —
            // they're obviously working and testing risks false failures on
            // high-bandwidth tunnels.
            if (ctx.clock().now() - _cfg.getLastTransferred() < MIN_TEST_DELAY) {
                if (_log.shouldWarn())
                    _log.warn("Skipping test on " + _cfg + " that recently received data");
                CONCURRENT_TESTS.decrementAndGet();
                if (!scheduleRetest(false)) {
                    cleanupTunnelTracking();
                    decrementTotalJobs();
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
                }
                if (_outTunnel == null) {
                    if (_log.shouldWarn())
                        _log.warn("No outbound tunnel for test of " + _cfg +
                                  " -> Deferring (pool may be recovering)");
                    ctx.statManager().addRateData("tunnel.testDeferred", _cfg.getLength());
                    CONCURRENT_TESTS.decrementAndGet();
                    if (!scheduleRetest(false)) {
                        cleanupTunnelTracking();
                        decrementTotalJobs();
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
                ctx.clock().now() - _cfg.getLastTransferred() < MIN_TEST_DELAY) {
                if (_log.shouldWarn())
                    _log.warn("Skipping test on " + _cfg + " that recently transferred data");
                CONCURRENT_TESTS.decrementAndGet();
                if (!scheduleRetest(false)) {
                    cleanupTunnelTracking();
                    decrementTotalJobs();
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
                }
                if (_replyTunnel == null) {
                    if (_log.shouldWarn())
                        _log.warn("No inbound tunnel for test of " + _cfg +
                                  " -> Deferring (pool may be recovering)");
                    ctx.statManager().addRateData("tunnel.testDeferred", _cfg.getLength());
                    CONCURRENT_TESTS.decrementAndGet();
                    if (!scheduleRetest(false)) {
                        cleanupTunnelTracking();
                        decrementTotalJobs();
                    }
                    return;
                }
            }
        }

        _otherTunnel = (_outTunnel instanceof PooledTunnelCreatorConfig)
            ? (PooledTunnelCreatorConfig) _outTunnel
            : (_replyTunnel instanceof PooledTunnelCreatorConfig)
                ? (PooledTunnelCreatorConfig) _replyTunnel
                : null;

        if (_replyTunnel == null || _outTunnel == null) {
            if (_log.shouldWarn())
                _log.warn("Insufficient tunnels to test " + _cfg + " with: " + _replyTunnel + " / " + _outTunnel);
            ctx.statManager().addRateData("tunnel.testDeferred", _cfg.getLength());
            CONCURRENT_TESTS.decrementAndGet();
            if (!scheduleRetest(false)) {
                cleanupTunnelTracking();
                decrementTotalJobs();
            }
            return;
        }

        int testPeriod = getTestPeriod();
        long testExpiration = now + testPeriod;

        DeliveryStatusMessage m = new DeliveryStatusMessage(ctx);
        m.setArrival(now);
        m.setMessageExpiration(testExpiration);
        m.setMessageId(ctx.random().nextLong(I2NPMessage.MAX_ID_VALUE));

        ReplySelector sel = new ReplySelector(m.getMessageId(), testExpiration);
        OnTestReply onReply = new OnTestReply();
        OnTestTimeout onTimeout = new OnTestTimeout(now);
        OutNetMessage msg = ctx.messageRegistry().registerPending(sel, onReply, onTimeout);
        onReply.setSentMessage(msg);

        boolean sendSuccess = sendTest(m, testPeriod);
        if (!sendSuccess) {
            CONCURRENT_TESTS.decrementAndGet();
            // Try to reschedule - if it fails, clean up tunnel tracking and total counter
            if (!scheduleRetest(_cfg.needsExpeditedTest())) {
                cleanupTunnelTracking();
                decrementTotalJobs();
            }
            return;
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
        _id = __id.getAndIncrement();

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
            _log.debug("Sending " + (useEncryption ? "" : "unencrypted ") + "garlic test [#" + _id + "] \n* " +
                       _outTunnel + " / " + _replyTunnel);
        }

        // Readiness check: ensure outbound gateway exists before dispatch
        if (_outTunnel != null && _replyTunnel != null) {
            if (!ctx.tunnelDispatcher().hasOutboundGateway(_outTunnel.getSendTunnelId(0))) {
                if (_log.shouldInfo()) {
                    _log.info("Outbound gateway for tunnel " + _outTunnel.getSendTunnelId(0) +
                              " not yet registered \n* Deferring test for " + _cfg);
                }
                return false; // Let caller handle cleanup & rescheduling
            }
            ctx.tunnelDispatcher().dispatchOutbound(
                m,
                _outTunnel.getSendTunnelId(0),
                _replyTunnel.getReceiveTunnelId(0),
                _replyTunnel.getPeer(0)
            );
            return true;
        } else {
            if (_log.shouldWarn()) {
                _log.warn("Tunnel test job skipped -> No tunnels available for " + _cfg);
            }
            // Don't decrement here - calling code will handle it
            return false;
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
            decrementTotalJobs();
            return;
        }

        // Update success history for adaptive testing frequency
        updateSuccessHistory(true);

        ctx.statManager().addRateData("tunnel.testSuccessLength", _cfg.getLength());
        ctx.statManager().addRateData("tunnel.testSuccessTime", ms);

        _outTunnel.incrementVerifiedBytesTransferred(1024);
        noteSuccess(ms, _outTunnel);
        noteSuccess(ms, _replyTunnel);

        _cfg.testJobSuccessful(ms);
        _cfg.clearExpeditedTest();

        if (_log.shouldDebug()) {
            _log.debug("Tunnel Test [#" + _id + "] succeeded in " + ms + "ms → " + _cfg + " (Success rate: " +
                       String.format("%.1f%%", getSuccessRate() * 100) + ")");
        }

        // Clean up session tags
        clearTestTags();
        // Use expedited scheduling if tunnel needs faster retesting
        boolean needsExpedited = _cfg.needsExpeditedTest();
        if (!scheduleRetest(needsExpedited)) {
            cleanupTunnelTracking();
            decrementTotalJobs(); // Clean up if couldn't reschedule
        }
    }

    private int getDelay() {
        // Minimum 30s between retests; scale up for reliable tunnels.
        // Failing tunnels retest at the minimum interval so they can recover,
        // reliable tunnels are tested less frequently.
        float successRate = getSuccessRate();
        int scaled;
        if (successRate >= 1.0f) {
            scaled = MIN_TEST_DELAY * 2; // 100% success → 2x delay
        } else if (successRate > 0.5f) {
            scaled = MIN_TEST_DELAY * 3 / 2; // >50% success → 1.5x delay
        } else {
            scaled = MIN_TEST_DELAY; // unreliable or new → fastest retest
        }
        scaled = Math.min(scaled, MAX_TEST_DELAY);
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

    private int _failureCount = 0;
    private boolean _successHistory[] = new boolean[SUCCESS_HISTORY_SIZE];
    private int _successHistoryIndex = 0;
    private int _successCount = 0;

    /**
     * Called when the tunnel test fails.
     * @param timeToFail time in milliseconds before the test failed
     */
    private void testFailed(long timeToFail) {
        if (_pool == null || !_pool.isAlive()) {
            cleanupTunnelTracking();
            decrementTotalJobs();
            return;
        }

        boolean isExploratory = _pool.getSettings().isExploratory();
        getContext().statManager().addRateData(
            isExploratory ? "tunnel.testExploratoryFailedTime" : "tunnel.testFailedTime",
            timeToFail);

        _cfg.clearExpeditedTest();

        // Client tunnels (having a non-null destination) must NEVER be removed
        // by TestJob — removal triggers churn and can leave the pool temporarily
        // depleted.  Check the tunnel's destination rather than the pool's
        // isExploratory() flag, because aliased pools delegate build/TestJob
        // management to the aliasOf (exploratory) pool while the tunnel itself
        // is a client tunnel.  Tunnels are deprioritized by selectTunnel() via
        // consecutiveFailures > 1 and expire naturally when their lifetime ends.
        // The only removal paths are natural expiry and budget pruning by ExpireJob.
        // For inbound client pools (no remote destination), check the pool itself.
        boolean isClientTunnel = _cfg.getDestination() != null || !_pool.getSettings().isExploratory();

        // Never deprioritize a client tunnel until the pool has at least one
        // GOOD tunnel to take over.  Without this, a pool where ALL tunnels
        // are failing will report DNR — keeping the tunnel alive (even degraded)
        // is better than having no tunnels at all.
        boolean isServerPool = _pool.getSettings().isInbound() && !_pool.getSettings().isExploratory();
        int activeCount = _pool.getActiveTunnelCount();
        boolean hasGoodReplacement;
        // A GOOD tunnel under test counts itself in getActiveTunnelCount().
        // If this is the only GOOD tunnel, removing it would leave the pool
        // empty — so require at least one OTHER good tunnel before allowing
        // removal of a previously-GOOD tunnel.  Non-GOOD tunnels (FAILING,
        // UNTESTED) don't count themselves, so > 0 is correct for those.
        // For server pools, require 2+ other GOOD tunnels to prevent a
        // simultaneous-removal race where both tunnels see each other as
        // replacements and both get removed.
        if (_cfg.getTestStatus() == net.i2p.router.TunnelTestStatus.GOOD) {
            hasGoodReplacement = isServerPool ? activeCount > 2 : activeCount > 1;
        } else {
            hasGoodReplacement = isServerPool ? activeCount > 1 : activeCount > 0;
        }

        if (isClientTunnel) {
            if (hasGoodReplacement) {
                _cfg.incrementTestFailures();
                _cfg.setTestFailed();
                // After 5 consecutive failures with a GOOD replacement
                // available, remove the tunnel — it's provably broken and
                // retesting only wastes resources.
                if (_cfg.getTunnelFailures() >= 5) {
                    // Don't remove if tunnel has carried user data — the data
                    // proves the tunnel is functional; transient test issues
                    // should not force replacement.
                    if (_cfg.getVerifiedBytesTransferred() > 0) {
                        if (_log.shouldWarn()) {
                            _log.warn("Tunnel Test failed -> Keeping data-carrying tunnel: " + _cfg +
                                      " (verified=" + _cfg.getVerifiedBytesTransferred() + " bytes)");
                        }
                        _cfg.clearTestFailures();
                        if (!scheduleRetest(false)) {
                            cleanupTunnelTracking();
                            decrementTotalJobs();
                        }
                        return;
                    }
                    if (_log.shouldWarn()) {
                        _log.warn("Tunnel Test failed -> Removing " + _cfg +
                                  " after " + _cfg.getTunnelFailures() + " consecutive failures");
                    }
                    _pool.removeTunnel(_cfg);
                    // not calling notifyServerPoolTestFailed() here since
                    // removeTunnel already triggers refreshLeaseSet
                    cleanupTunnelTracking();
                    decrementTotalJobs();
                    return;
                }
            }
            // Always trigger LS renewal so the LeaseSet is re-published with
            // the best remaining tunnel's lease (via findBestDegradedTunnel
            // fallback).  Without this, retained failing tunnels never trigger
            // fail() and the LS goes stale.
            _pool.notifyServerPoolTestFailed();

            if (_log.shouldWarn()) {
                _log.warn("Tunnel Test failed -> Client tunnel, keeping for retest: " + _cfg);
            }

            if (!scheduleRetest(false)) {
                cleanupTunnelTracking();
                decrementTotalJobs();
            }
            return;
        }

        // For previously GOOD tunnels (non-exploratory), keep testing and
        // only deprioritize if the pool has a GOOD replacement available.
        boolean wasGood = _cfg.getTestStatus() == net.i2p.router.TunnelTestStatus.GOOD;

        if (wasGood) {
            if (hasGoodReplacement) {
                _cfg.incrementTestFailures();
                _cfg.setTestFailed();
                // After 5 consecutive failures with a GOOD replacement
                // available, remove the tunnel — unless it carried data.
                if (_cfg.getTunnelFailures() >= 5) {
                    // Don't remove if tunnel has carried user data — the data
                    // proves the tunnel is functional; transient test issues
                    // should not force replacement.
                    if (_cfg.getVerifiedBytesTransferred() > 0) {
                        if (_log.shouldWarn()) {
                            _log.warn("Tunnel Test failed -> Keeping previously-GOOD data-carrying tunnel: " + _cfg +
                                      " (verified=" + _cfg.getVerifiedBytesTransferred() + " bytes)");
                        }
                        _cfg.clearTestFailures();
                        if (!scheduleRetest(false)) {
                            cleanupTunnelTracking();
                            decrementTotalJobs();
                        }
                        return;
                    }
                    if (_log.shouldWarn()) {
                        _log.warn("Tunnel Test failed -> Removing previously-GOOD " + _cfg +
                                  " after " + _cfg.getTunnelFailures() + " consecutive failures");
                    }
                    _pool.removeTunnel(_cfg);
                    cleanupTunnelTracking();
                    decrementTotalJobs();
                    return;
                }
            }

            if (_log.shouldWarn()) {
                _log.warn((isExploratory ? "Exploratory tunnel" : "Tunnel") +
                          " Test failed -> Previously GOOD, keeping for retest: " + _cfg);
            }

            if (!scheduleRetest(false)) {
                cleanupTunnelTracking();
                decrementTotalJobs();
            }
            return;
        }

        // First test or already failing: remove immediately
        // SAFETY NET: Non-exploratory tunnels (client/server) must NEVER be
        // removed by TestJob — they're caught by Branch 1 above. If one
        // reaches here, something is wrong; retain it and log diagnostics.
        if (_cfg.getDestination() != null || !_pool.getSettings().isExploratory()) {
            _cfg.incrementTestFailures();
            _cfg.setTestFailed();
            _pool.notifyServerPoolTestFailed();
            if (_log.shouldError()) {
                _log.error("BUG: Non-exploratory tunnel reached Branch 3! pool=" + _pool +
                           " isExploratory=" + isExploratory + " settings.isExploratory=" +
                           _pool.getSettings().isExploratory() + " cfg.dest=" +
                           _cfg.getDestination().toBase32().substring(0, 8) + " failures=" +
                           _cfg.getTunnelFailures());
            }
            if (!scheduleRetest(false)) {
                cleanupTunnelTracking();
                decrementTotalJobs();
            }
            return;
        }

        _cfg.tunnelFailed();
        _cfg.setTestFailed();

        if (_log.shouldWarn()) {
            _log.warn((isExploratory ? "Exploratory tunnel" : "Tunnel") + " Test failed -> Immediate removal: " + _cfg);
        }

        getContext().statManager().addRateData(
            isExploratory ? "tunnel.testExploratoryFailedCompletelyTime" : "tunnel.testFailedCompletelyTime",
            timeToFail);

        _cfg.tunnelFailedCompletely();
        _pool.tunnelFailed(_cfg);

        _failureCount = 0;

        cleanupTunnelTracking();
        decrementTotalJobs();
    }

    private int getTestPeriod() {
        final RouterContext ctx = getContext();
        if (_outTunnel == null || _replyTunnel == null) return MAX_TEST_PERIOD;

        RateStat tspt = ctx.statManager().getRate("transport.sendProcessingTime");
        int base = 0;
        if (tspt != null) {
            Rate r = tspt.getRate(RateConstants.ONE_MINUTE);
            if (r != null) {
                base = 3 * (int) r.getAverageValue();
            }
        }

        // If the pool has observed successful test latencies, allow more time
        // so that tests that complete on the upper edge of the distribution
        // are not spuriously timed out.
        RateStat testTimeStat = ctx.statManager().getRate("tunnel.testSuccessTime");
        if (testTimeStat != null) {
            Rate oneMin = testTimeStat.getRate(RateConstants.ONE_MINUTE);
            if (oneMin != null) {
                // 3x the average observed test time as a generous timeout
                base = Math.max(base, 3 * (int) oneMin.getAverageValue());
            }
        }

        int totalHops = _outTunnel.getLength() + _replyTunnel.getLength();
        int calculated = base + (1000 * totalHops);
        int clamped = Math.max(MIN_TEST_PERIOD, calculated);
        return Math.min(clamped, MAX_TEST_PERIOD);
    }

    private boolean scheduleRetest() {
        return scheduleRetest(false);
    }

    private boolean scheduleRetest(boolean asap) {
        if (_pool == null || !_pool.isAlive()) return false;

        // Skip retest if tunnel doesn't have valid IDs anymore (may have been rebuilt/replaced)
        try {
            long recvId = _cfg.getReceiveTunnelId(0).getTunnelId();
            long sendId = _cfg.getSendTunnelId(0).getTunnelId();
            if (recvId == 0 || sendId == 0) {
                if (_log.shouldDebug()) {
                    _log.debug("Skipping retest - tunnel IDs no longer valid: recv=" + recvId + ", send=" + sendId);
                }
                return false;
            }
        } catch (Exception e) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping retest - tunnel no longer accessible: " + _cfg, e);
            }
            return false;
        }

        final RouterContext ctx = getContext();

        // Check if job queue is overloaded - skip rescheduling if queue is backing up
        // Allow expedited tests to bypass some lag limits
        int readyCount = ctx.jobQueue().getReadyCount();
        long maxLag = ctx.jobQueue().getMaxLag();
        int activeRunners = ctx.jobQueue().getActiveRunnerCount();
        int numPools;
        if (_pool != null) {
            List<TunnelPool> poolList = new ArrayList<TunnelPool>();
            ctx.tunnelManager().listPools(poolList);
            numPools = poolList.size();
        } else {
            numPools = 0;
        }
        int maxTestJobs = Math.min(maxQueuedTests, Math.max(activeRunners, Math.max(numPools * 12, 24)));
        int totalCount = getTotalTestJobCount();
        boolean isExpedited = _cfg.needsExpeditedTest();
        long rescheduleLagLimit = isExpedited ? MAX_LAG_RESCHEDULE * 3 : MAX_LAG_RESCHEDULE;
        int rescheduleJobLimit = isExpedited ? maxTestJobs * 2 : maxTestJobs;
        if (readyCount > MAX_LAG_RESCHEDULE || maxLag > rescheduleLagLimit || totalCount >= rescheduleJobLimit) {
            if (_log.shouldInfo()) {
                _log.info("Job queue lagging or too many test jobs (" + readyCount + " ready jobs, maxLag=" + maxLag +
                         "ms, testJobs=" + totalCount + "/" + maxTestJobs + ", expedited=" + isExpedited + ") -> Skipping retest for " + _cfg);
            }
            return false;
        }

        if (totalCount >= HARD_TEST_JOB_LIMIT) {
            if (_log.shouldInfo()) {
                _log.info("Hard limit reached during reschedule (" + totalCount + " >= " + HARD_TEST_JOB_LIMIT +
                          ") \n* Skipping reschedule for " + _cfg);
            }
            return false;
        }

        int delay = getDelay();
        // asap path: no extra cap, getDelay() already respects MAX_TEST_DELAY

        // Ensure delay is not negative and set start time properly
        delay = Math.max(0, delay);
        long startTime = ctx.clock().now() + delay;
        getTiming().setStartAfter(startTime);
        ctx.jobQueue().addJob(this);
        return true;
    }

    private class ReplySelector implements MessageSelector {
        private final long _id;
        private final long _expiration;

        public ReplySelector(long id, long expiration) {
            _id = id;
            _expiration = expiration;
        }

        @Override public boolean continueMatching() {
            return !_found && getContext().clock().now() < _expiration;
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
            _found = true;
            if (_successTime < getTestPeriod()) {
                testSuccessful((int) _successTime);
            } else {
                testFailed(_successTime);
            }
            CONCURRENT_TESTS.decrementAndGet(); // Decrement counter
            if (_log.shouldDebug()) {
                _log.debug("Tunnel test reply received for " + _cfg + " (Active concurrent tests: " + CONCURRENT_TESTS.get() + ")");
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

            if (!_found) {
                _found = true;
                testFailed(getContext().clock().now() - _started);
                CONCURRENT_TESTS.decrementAndGet(); // Decrement counter
            }
            if (_log.shouldDebug()) {
                _log.debug("Tunnel test timeout for " + _cfg + " (Active concurrent tests: " + CONCURRENT_TESTS.get() + ")");
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
        if (_counted.compareAndSet(true, false)) {
            decrementTotalJobs();
        }
    }

}
