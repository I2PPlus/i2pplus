package net.i2p.router.tunnel.pool;

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
import net.i2p.router.TunnelPoolSettings;
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
    private static final int MIN_TEST_PERIOD = 10*1000;
    private static final int MAX_TEST_PERIOD = 20*1000;
    private static final int MAX_TEST_PERIOD_ATTACK = 40*1000; // 40s during attacks (longer timeout for higher latency)

    /**
     * Maximum number of tunnel tests that can run concurrently.
     * Prevents overwhelming the router with too many simultaneous tunnel tests.
     * This value can be adjusted based on system capacity.
     */
    private static final int MAX_CONCURRENT_TESTS = SystemVersion.isSlow() ? 16 : 32;

    // Adaptive testing frequency constants
    private static final int BASE_TEST_DELAY = 60 * 1000; // 60s base - increased frequency
    private static final int MIN_TEST_DELAY = 30 * 1000; // 30s minimum
    private static final int MAX_TEST_DELAY = 120 * 1000; // 120s maximum
    private static final int SUCCESS_HISTORY_SIZE = 10; // Track last 10 results

    /**
     * Maximum number of TestJob instances that should be queued before deferring new ones.
     * Prevents job queue saturation from too many waiting tunnel tests.
     */
    private static final int MAX_QUEUED_TESTS = SystemVersion.isSlow() ? 32 : 64;
    public static int maxQueuedTests = MAX_QUEUED_TESTS;

    /**
     * Maximum hard limit for total TestJob instances (queued + active).
     * Above this threshold, no new tests are scheduled until count decreases.
     * Prevents ever-increasing backlogs that could cause job lag.
     * Dynamic limit varies between this and BASE_TEST_JOB_LIMIT based on job lag.
     */
    public static final int MAX_TEST_JOB_LIMIT = SystemVersion.isSlow() ? 128 : 256;
    private static final int BASE_TEST_JOB_LIMIT = SystemVersion.isSlow() ? 64 : 128;

    /**
     * Calculate dynamic job limit based on router performance (job lag).
     * Scales from BASE_TEST_JOB_LIMIT to MAX_TEST_JOB_LIMIT based on observed lag.
     *
     * @param ctx the router context
     * @return dynamic limit between BASE_TEST_JOB_LIMIT and MAX_TEST_JOB_LIMIT
     */
    private static int getDynamicJobLimit(RouterContext ctx) {
        long avgLag = ctx.jobQueue().getAvgLag();
        long maxLag = ctx.jobQueue().getMaxLag();

        // Scale based on lag - lower lag allows higher limits
        if (avgLag < 5 && maxLag < 100) {
            return MAX_TEST_JOB_LIMIT;  // Excellent performance
        } else if (avgLag < 10 && maxLag < 200) {
            // Linear interpolation between base and max
            int range = MAX_TEST_JOB_LIMIT - BASE_TEST_JOB_LIMIT;
            int reduction = (int) (range * avgLag / 50);
            return MAX_TEST_JOB_LIMIT - reduction;
        } else if (avgLag < 10 && maxLag < 500) {
            return BASE_TEST_JOB_LIMIT + (MAX_TEST_JOB_LIMIT - BASE_TEST_JOB_LIMIT) / 2;
        } else {
            return BASE_TEST_JOB_LIMIT;  // Conservative when lag is high
        }
    }

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
     * Get the total number of TestJob instances (active + queued) using atomic counter.
     * This provides more reliable limiting than job queue counting alone.
     *
     * @return the total number of TestJob instances
     */
    private static int getTotalTestJobCount() {
        return TOTAL_TEST_JOBS.get();
    }

    /**
     * Get stat for total TestJobs - for monitoring
     * @return current total TestJob count
     */
    public static int getTotalTestJobCountStatic() {
        return TOTAL_TEST_JOBS.get();
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
            long recvId = cfg.getReceiveTunnelId(0).getTunnelId();
            long sendId = cfg.getSendTunnelId(0).getTunnelId();
            if (recvId != 0 && sendId != 0) {
                return Long.valueOf((recvId << 32) | (sendId & 0xFFFFFFFFL));
            }
            return cfg.getInstanceId();
        } catch (Exception e) {
            return cfg.getInstanceId();
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

    /** Ensures total job counter is decremented at most once */
    private final AtomicBoolean _counted = new AtomicBoolean(false);

    /**
     * Static method to check if a TestJob should be created and scheduled.
     * This prevents creating invalid job objects that would have timing issues.
     * @param ctx the router context
     * @param cfg the tunnel config
     * @return true if the job should be created and scheduled, false otherwise
     */
    public static boolean shouldSchedule(RouterContext ctx, PooledTunnelCreatorConfig cfg) {
        // Skip tunnel testing for ping tunnels - they're short-lived and don't need testing
        TunnelPool pool = cfg.getTunnelPool();
        Log log = ctx.logManager().getLog(TestJob.class);
        if (pool != null) {
            TunnelPoolSettings settings = pool.getSettings();
            // Check shouldTest flag first (more robust than nickname matching)
            if (!settings.shouldTest()) {
                if (log.shouldInfo()) {
                    log.info("Skipping test scheduling for tunnel with shouldTest=false");
                }
                return false;
            }
            // Fallback to nickname matching for backwards compatibility
            String tunnelNickname = settings.getDestinationNickname();
            boolean isPing = tunnelNickname != null && (tunnelNickname.equals("I2Ping") ||
                             (tunnelNickname.startsWith("Ping") && tunnelNickname.contains("[")));
            if (log.shouldDebug() && !isPing) {
                log.debug("Checking " + (tunnelNickname != null ? tunnelNickname : "") + " tunnel for test scheduling -> " + pool);
            } else if (isPing) {
                if (log.shouldInfo()) {
                    log.info("Skipping test scheduling for ping tunnel: " + tunnelNickname);
                }
                return false;
            }
        } else if (log.shouldWarn()) {
            log.warn("Tunnel pool is null for config: " + cfg);
        }

        // Check total job count limit
        int current = TOTAL_TEST_JOBS.get();
        if (current >= maxQueuedTests) {
            if (log.shouldInfo()) {
                log.info("Limit (" + maxQueuedTests + ") reached -> Not scheduling test for " + cfg);
            }
            return false;
        }

        // Check if this tunnel already has a test running
        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey != null && RUNNING_TESTS.containsKey(tunnelKey)) {
            if (log.shouldDebug()) {
                log.debug("Test already running for tunnel key " + tunnelKey + " -> Skipping duplicate test for " + cfg);
            }
            return false;
        }

        // Check pool coverage - avoid over-testing pools that already have running tests
        // Increased limits to better utilize global capacity (up to 512 with low lag)
        // Raised from 8/16 to 64/96 to allow large pools to test more tunnels concurrently
        if (pool != null) {
            String poolId = getPoolId(pool);
            AtomicInteger poolCount = POOL_TEST_COUNTS.get(poolId);
            if (poolCount != null && poolCount.get() > 0) {
                // For exploratory pools, limit but allow better coverage
                if (pool.getSettings().isExploratory() && poolCount.get() >= 64) {
                    if (log.shouldDebug()) {
                        log.debug("Pool " + poolId + " already has " + poolCount.get() + " tests running -> Deferring for better coverage");
                    }
                    return false;
                }
                // For client pools, allow more concurrency
                else if (!pool.getSettings().isExploratory() && poolCount.get() >= 96) {
                    if (log.shouldDebug()) {
                        log.debug("Pool " + poolId + " already has " + poolCount.get() + " tests running -> Deferring for better coverage");
                    }
                    return false;
                }
            }
        }

        // All validations passed - constructor will increment counter
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
     * Atomically increment total job counter and check limits.
     * This prevents race conditions in job scheduling.
     *
     * @param ctx the router context
     * @return true if job can proceed, false if limit exceeded
     */
    private static boolean tryIncrementTotalJobs(RouterContext ctx) {
        int current = TOTAL_TEST_JOBS.get();
        if (current >= MAX_TEST_JOB_LIMIT) {
            return false;
        }
        // Simple increment - avoid contention from do-while loop
        TOTAL_TEST_JOBS.incrementAndGet();
        return true;
    }

    /**
     * Atomically decrement total job counter.
     * Must be called when a test job completes or is cancelled.
     */
    private static void decrementTotalJobs() {
        int current;
        do {
            current = TOTAL_TEST_JOBS.get();
            if (current <= 0) {
                return;
            }
        } while (!TOTAL_TEST_JOBS.compareAndSet(current, current - 1));
    }

    /**
     * Invalidate a TestJob by tunnel key.
     * Called when a tunnel is removed from the pool to prevent stale tests from running.
     * @param tunnelKey the tunnel key (from ExpireLocalTunnelsJob.getTunnelKey)
     */
    public static void invalidate(Long tunnelKey) {
        if (tunnelKey == null) return;
        TestJob job = RUNNING_TESTS.get(tunnelKey);
        if (job != null) {
            job._valid = false;
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
        // Track pool test count for coverage management
        if (_pool != null) {
            String poolId = getPoolId(_pool);
            POOL_TEST_COUNTS.computeIfAbsent(poolId, k -> new AtomicInteger(0)).incrementAndGet();
        }

        // Register this test as running for the tunnel
        Long tunnelKey = getTunnelKey(cfg);
        if (tunnelKey != null) {
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
                _valid = false;
                return;
            }
        }

        // Increment total job counter after successful validation
        TOTAL_TEST_JOBS.incrementAndGet();
        _counted.set(true);
        // Start after delay; guard against negative start
        int delay = getDelay();
        delay = Math.max(0, delay);
        long startTime = ctx.clock().now() + delay;
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

        // Aggressive job lag gating - skip all tests when lag is high
        if (maxLag > 3000) {
            // Skip all tunnel tests when lag exceeds 3 seconds
            if (_log.shouldWarn()) {
                _log.warn("Aborted ALL tunnel tests due to high job lag (Max: " + maxLag + "ms) → " + _cfg);
            }
            ctx.statManager().addRateData("tunnel.testAbortedHighLag", _cfg.getLength());
            cleanupTunnelTracking();
            decrementTotalJobs();
            return; // Exit without rescheduling - test will be rescheduled normally later
        }

        if (maxLag > 1500 || avgLag > 50) {
            // Skip exploratory tunnels under moderate pressure
            if (isExploratory) {
                if (_log.shouldInfo()) {
                    _log.info("Skipping exploratory tunnel test due to job lag (Max: " + maxLag + " / Avg: " + avgLag + "ms) -> " + _cfg);
                }
                ctx.statManager().addRateData("tunnel.testExploratorySkipped", _cfg.getLength());
                if (!scheduleRetest(false)) {
                    cleanupTunnelTracking();
                    decrementTotalJobs();
                }
                return;
            }
        }

        if (maxLag > 5000 || avgLag > 100) {
            // Still test client tunnels unless lag is extreme
            if (_log.shouldWarn()) {
                _log.warn("Aborted test due to severe max job lag (Max: " + maxLag + " / Avg: " + avgLag + "ms) → " + _cfg);
            }
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            cleanupTunnelTracking();
            decrementTotalJobs();
            return; // Exit without rescheduling
        }

        // Calculate adaptive max queued tests based on system load
        if (maxLag < 1000 && avgLag < 3) {
            // Super low lag
            maxQueuedTests = MAX_TEST_JOB_LIMIT; // 128 / 384
        } else if (maxLag < 1200 && avgLag < 5) {
            // Very low lag - increase queue capacity for more thorough testing
            maxQueuedTests = MAX_QUEUED_TESTS * 4; // 128 / 256
        } else if (maxLag < 1500 && avgLag < 10) {
            // Low lag - moderate increase in queue capacity
            maxQueuedTests = MAX_QUEUED_TESTS * 3 / 2;
        } else {
            // Normal or high lag - use standard limits
            maxQueuedTests = MAX_QUEUED_TESTS * 2;
        }

        // Cap adaptive queue limit at hard limit to prevent exceeding configured maximum
        maxQueuedTests = Math.min(maxQueuedTests, MAX_TEST_JOB_LIMIT);

        // Update public static field for JobQueueHelper display
        TestJob.maxQueuedTests = maxQueuedTests;

        // Check for queue saturation and hard limits before proceeding
        int totalCount = getTotalTestJobCount();

        // Don't count ourselves in the total - we are already in the counter
        // and will be removed on cleanup
        if (totalCount >= MAX_TEST_JOB_LIMIT && totalCount > 1) {
            if (_log.shouldInfo()) {
                _log.info("Hard limit reached (" + totalCount + " >= " + MAX_TEST_JOB_LIMIT +
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
            // Under queue pressure, prioritize client tunnels
            if (isExploratory) {
                if (_log.shouldInfo()) {
                    _log.info("TestJob queue saturated -> Deprioritizing Exploratory tunnel test (" + totalCount + " >= " + maxQueuedTests + ")");
                }
                ctx.statManager().addRateData("tunnel.testExploratorySkipped", _cfg.getLength());
                if (!scheduleRetest(false)) {
                    cleanupTunnelTracking();
                    decrementTotalJobs();
                }
                return;
            }

            // Client tunnels get priority in saturated queue
            if (_log.shouldInfo()) {
                _log.info("TestJob queue saturated (" + totalCount + " >= " + maxQueuedTests + ") -> Rescheduling client tunnel test for " + _cfg);
            }

            ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
            cleanupTunnelTracking();
            decrementTotalJobs(); // Clean up counter since we won't proceed
            return; // Exit without rescheduling
        }

        // Concurrency control: Check and increment counter
        // Scale with global capacity to allow near 512 concurrent tests for high-capacity routers
        int maxTests = SystemVersion.isSlow() ? 128 : 256;

        // Increase max tests under low build success when we have capacity @since 0.9.68+
        double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
        boolean lowBuildSuccess = buildSuccess < 0.40;

        // Only reduce concurrency under severe load
        if (maxLag > 5000 || avgLag > 100) {
            maxTests = isExploratory ? 16 : 32;
        } else if (maxLag > 3000 || avgLag > 50) {
            maxTests = isExploratory ? 32 : 64;
        } else if (lowBuildSuccess && maxLag < 1500 && avgLag < 10) {
            // Low build success with low lag: increase capacity to build more tunnels faster
            maxTests = isExploratory ? 128 : 256;
        }

        int current;
        boolean concurrentTestStarted = false;
        do {
            current = CONCURRENT_TESTS.get();
            if (current >= maxTests) {
                if (_log.shouldInfo()) {
                    _log.info("Max " + maxTests + " concurrent tunnel tests reached \n* Rescheduling test for " + _cfg +
                              " (Queued: " + totalCount + ")");
                }
                ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
                // Always clean up tunnel tracking before returning - scheduleRetest creates a new job
                cleanupTunnelTracking();
                if (!scheduleRetest(false)) {
                    decrementTotalJobs();
                }
                return;
            }
        } while (!CONCURRENT_TESTS.compareAndSet(current, current + 1));
        concurrentTestStarted = true;

        // Wrap the rest in try-finally to ensure cleanup on timeout/interruption
        try {
            // Begin tunnel test logic
            _found = false;
            long now = ctx.clock().now();

            // Set test status to TESTING
            _cfg.setTestStarted();

            if (_cfg.isInbound()) {
                _replyTunnel = _cfg;
                _outTunnel = isExploratory ?
                    ctx.tunnelManager().selectOutboundTunnel() :
                    ctx.tunnelManager().selectOutboundTunnel(_pool.getSettings().getDestination());
            } else {
                _replyTunnel = isExploratory ?
                    ctx.tunnelManager().selectInboundTunnel() :
                    ctx.tunnelManager().selectInboundTunnel(_pool.getSettings().getDestination());
                _outTunnel = _cfg;
            }

            _otherTunnel = (_outTunnel instanceof PooledTunnelCreatorConfig)
                ? (PooledTunnelCreatorConfig) _outTunnel
                : (_replyTunnel instanceof PooledTunnelCreatorConfig)
                    ? (PooledTunnelCreatorConfig) _replyTunnel
                    : null;

            // Early viability guard
            if (_replyTunnel == null || _outTunnel == null) {
                if (_log.shouldWarn())
                    _log.warn("Insufficient tunnels to test " + _cfg + " with \n* " + _replyTunnel + " / " + _outTunnel);
                ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
                CONCURRENT_TESTS.decrementAndGet();
                cleanupTunnelTracking();
                decrementTotalJobs(); // Clean up total counter
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
                cleanupTunnelTracking();
                // Try to reschedule - if it fails, clean up total counter
                if (!scheduleRetest(false)) {
                    decrementTotalJobs();
                }
                return;
            }
        } catch (Throwable t) {
            // Ensure cleanup on any exception including interruption
            if (concurrentTestStarted) {
                CONCURRENT_TESTS.decrementAndGet();
            }
            cleanupTunnelTracking();
            decrementTotalJobs();
            throw t;
        }
        // Note: Normal completion (sendSuccess=true) relies on OnTestReply/OnTestTimeout
        // to decrement CONCURRENT_TESTS and handle TOTAL_TEST_JOBS decrement
        // This try-finally only handles abnormal exits (timeout/interruption/exception)
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

        // During any job lag, prefer unencrypted tests to reduce crypto overhead
        long maxLag = ctx.jobQueue().getMaxLag();
        long avgLag = ctx.jobQueue().getAvgLag();
        if (maxLag > 500 || avgLag > 3) {
            // Moderate lag: 75% unencrypted (25% encrypted)
            useEncryption = ctx.random().nextInt(4) == 0;
        }
        if (maxLag > 1500 || avgLag > 8) {
            // Higher lag: 90% unencrypted (10% encrypted)
            useEncryption = ctx.random().nextInt(10) == 0;
        }
        if (maxLag > 3000 || avgLag > 20) {
            // Severe lag: always unencrypted
            useEncryption = false;
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

        // If tunnel was last resort and now passed, clear the flag
        if (_cfg.isLastResort()) {
            _cfg.clearLastResort();
            if (_log.shouldInfo()) {
                _log.info("Last resort tunnel recovered and cleared: " + _cfg);
            }
        }

        if (_log.shouldDebug()) {
            _log.debug("Tunnel Test [#" + _id + "] succeeded in " + ms + "ms → " + _cfg + " (Success rate: " +
                       String.format("%.1f%%", getSuccessRate() * 100) + ")");
        }

        // Clean up session tags
        clearTestTags();
        if (!scheduleRetest(false)) {
            decrementTotalJobs(); // Clean up if couldn't reschedule
        }
    }

    private int getDelay() {
        // During attacks: use slightly lower base delay to test more frequently
        // but not too aggressive to avoid overwhelming the system
        boolean isUnderAttack = getContext().profileOrganizer().isLowBuildSuccess();
        int baseDelay = isUnderAttack ? (BASE_TEST_DELAY * 3 / 4) : BASE_TEST_DELAY; // 45s attack, 60s normal
        int failCount = _cfg.getTunnelFailures();
        int scaled = baseDelay;
        if (failCount > 0) {
            // Test failing tunnels more frequently to confirm and evict them faster
            // Tunnel will be removed after 2 consecutive failures
            int divisor = Math.min(1 << (failCount - 1), 4); // max 4x faster
            scaled = baseDelay / divisor;
        }
        // Ensure minimum delay and avoid negative values
        scaled = Math.max(scaled, MIN_TEST_DELAY);
        // Add a small jitter to avoid thundering herd (ensure positive jitter)
        int jitter = getContext().random().nextInt(Math.max(1, scaled / 3));
        return scaled + jitter;
    }

    private float getSuccessRate() {
        if (_successCount == 0) return 0.5f; // Assume 50% for new tunnels
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
            if (rskm != null && _ratchetEncryptTag != null) {
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
            decrementTotalJobs();
            return;
        }

        boolean isExploratory = _pool.getSettings().isExploratory();
        getContext().statManager().addRateData(
            isExploratory ? "tunnel.testExploratoryFailedTime" : "tunnel.testFailedTime",
            timeToFail);

        // Check if this is the last tunnel in the pool for non-exploratory pools
        int tunnelCount = _pool.getTunnelCount();
        boolean isLastTunnel = !isExploratory && tunnelCount <= 1;

        // Only fail the tunnel under test — do NOT blame _otherTunnel
        // Increment the tunnel's global failure count and check if we should continue
        boolean keepGoing = _cfg.tunnelFailed(); // This increments the counter and returns true if < MAX_CONSECUTIVE_TEST_FAILURES

        // Update test status for UI display
        _cfg.setTestFailed();

        if (_log.shouldWarn()) {
            _log.warn((isExploratory ? "Exploratory tunnel" : "Tunnel") + " Test failed in " + timeToFail + "ms → " + _cfg +
                      (isLastTunnel ? " \n* Warning: LAST TUNNEL -> Keeping as last resort until tunnels rebuilt..." : ""));
        }

        // If this is the last tunnel, mark it as last resort instead of removing
        if (isLastTunnel && !keepGoing) {
            _cfg.setLastResort();
            if (_log.shouldWarn()) {
                _log.warn("Tunnel marked as last resort (only tunnel in pool): " + _cfg);
            }
            // Trigger immediate replacement build
            _pool.triggerReplacementBuild();
            // Still schedule retest to see if it recovers
            if (!scheduleRetest(false)) {
                decrementTotalJobs();
            }
            return;
        }

        if (keepGoing) {
            // Keep testing - tunnel may recover
            if (!scheduleRetest(false)) {
                decrementTotalJobs(); // Clean up if couldn't reschedule
            }
        } else {
            getContext().statManager().addRateData(
                isExploratory ? "tunnel.testExploratoryFailedCompletelyTime" : "tunnel.testFailedCompletelyTime",
                timeToFail);

            // Tunnel has failed MAX consecutive tests - mark as completely failed
            // But don't remove immediately - keep it for recovery testing
            // It will be removed when replacements are available
            if (_log.shouldWarn()) {
                _log.warn((isExploratory ? "Exploratory tunnel" : "Tunnel") + " failed " +
                          net.i2p.router.tunnel.TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES +
                          " consecutive tests -> Marked as failed but keeping for recovery \n* " + _cfg);
            }

            _cfg.tunnelFailedCompletely();
            // Do NOT call _pool.tunnelFailed() - keep tunnel for potential recovery

            // Schedule recovery test to see if tunnel comes back
            if (scheduleRetest(false)) {
                if (_log.shouldInfo()) {
                    _log.info("Scheduling recovery test for failed tunnel: " + _cfg);
                }
            } else {
                _failureCount = 0;
                decrementTotalJobs();
            }
        }
    }

    private int getTestPeriod() {
        final RouterContext ctx = getContext();
        if (_outTunnel == null || _replyTunnel == null) return getMaxTestPeriod();

        RateStat tspt = ctx.statManager().getRate("transport.sendProcessingTime");
        int base = 0;
        if (tspt != null) {
            Rate r = tspt.getRate(RateConstants.ONE_MINUTE);
            if (r != null) {
                base = 3 * (int) r.getAverageValue();
            }
        }

        int totalHops = _outTunnel.getLength() + _replyTunnel.getLength();
        int calculated = base + (1000 * totalHops);
        int clamped = Math.max(MIN_TEST_PERIOD, calculated);
        return Math.min(clamped, getMaxTestPeriod());
    }

    /**
     * Get the maximum test period, extended during attacks.
     * @return MAX_TEST_PERIOD or MAX_TEST_PERIOD_ATTACK if under attack
     */
    private int getMaxTestPeriod() {
        boolean isUnderAttack = getContext().profileOrganizer().isLowBuildSuccess();
        return isUnderAttack ? MAX_TEST_PERIOD_ATTACK : MAX_TEST_PERIOD;
    }

    private boolean scheduleRetest() {
        return scheduleRetest(false);
    }

    private boolean scheduleRetest(boolean asap) {
        if (_pool == null || !_pool.isAlive()) return false;

        final RouterContext ctx = getContext();

        int totalCount = getTotalTestJobCount();
        if (totalCount >= MAX_TEST_JOB_LIMIT) {
            if (_log.shouldInfo()) {
                _log.info("Hard limit reached during reschedule (" + totalCount + " >= " + MAX_TEST_JOB_LIMIT +
                          ") \n* Skipping reschedule for " + _cfg);
            }
            return false;
        }

        // Create a new TestJob instance to avoid reentrancy issues
        // Constructor handles validation and counter increment atomically
        TestJob newJob = new TestJob(ctx, _cfg, _pool);
        if (!newJob.isValid()) {
            // Constructor already decremented counter if invalid
            return false;
        }

        int delay = getDelay();
        if (asap) {
            delay = Math.min(delay, BASE_TEST_DELAY / 2);
        }

        // Add stagger delay to spread out test jobs and prevent job queue spikes
        int staggerDelay = ctx.random().nextInt(5000); // 0-5 seconds
        delay += staggerDelay;

        // Ensure delay is not negative and set start time properly
        delay = Math.max(0, delay);
        long startTime = ctx.clock().now() + delay;
        newJob.getTiming().setStartAfter(startTime);
        ctx.jobQueue().addJob(newJob);
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
            if (_successTime < getTestPeriod()) {
                testSuccessful((int) _successTime);
            } else {
                testFailed(_successTime);
            }
            _found = true;
            // Clean up tunnel tracking to prevent memory leak
            cleanupTunnelTracking();
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
                testFailed(getContext().clock().now() - _started);
            }
            // Clean up tunnel tracking to prevent memory leak
            cleanupTunnelTracking();
            CONCURRENT_TESTS.decrementAndGet(); // Decrement counter
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
     * Ensure we clean up the total job counter and tunnel tracking when dropped.
     */
    @Override
    public void dropped() {
        cleanupTunnelTracking();
        if (_counted.compareAndSet(true, false)) {
            decrementTotalJobs();
        }
    }

}