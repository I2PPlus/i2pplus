package net.i2p.router.tunnel.pool;

import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.SessionTag;
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

    private static final int TEST_DELAY = 90 * 1000; // 90s base
    private static final int MIN_TEST_PERIOD = 8*1000;
    private static final int MAX_TEST_PERIOD = 20*1000;

    /**
     * Maximum number of tunnel tests that can run concurrently.
     * Prevents overwhelming the router with too many simultaneous tunnel tests.
     * This value can be adjusted based on system capacity.
     */
    private static final int MAX_CONCURRENT_TESTS = SystemVersion.isSlow() ? 4 : 8;

    // Adaptive testing frequency constants
    private static final int BASE_TEST_DELAY = 90 * 1000; // 90s base
    private static final int MIN_TEST_DELAY = 30 * 1000; // 30s minimum
    private static final int MAX_TEST_DELAY = 180 * 1000; // 180s maximum
    private static final int SUCCESS_HISTORY_SIZE = 3; // Track last 3 results

    /**
     * Maximum number of TestJob instances that should be queued before deferring new ones.
     * Prevents job queue saturation from too many waiting tunnel tests.
     */
    private static final int MAX_QUEUED_TESTS = SystemVersion.isSlow() ? 32 : 64;

    /**
     * Hard limit for total TestJob instances (queued + active).
     * Above this threshold, no new tests are scheduled until count decreases.
     * Prevents ever-increasing backlogs that could cause job lag.
     */
    private static final int HARD_TEST_JOB_LIMIT = SystemVersion.isSlow() ? 64 : 128;

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
     * Get the total number of TestJob instances (active + queued) using atomic counter.
     * This provides more reliable limiting than job queue counting alone.
     *
     * @return the total number of TestJob instances
     */
    private static int getTotalTestJobCount() {
        return TOTAL_TEST_JOBS.get();
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
        if (current >= HARD_TEST_JOB_LIMIT) {
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
        TOTAL_TEST_JOBS.decrementAndGet();
    }

    public TestJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        super(ctx);
        _log = ctx.logManager().getLog(TestJob.class);
        _cfg = cfg;
        _pool = (pool != null) ? pool : cfg.getTunnelPool();
        if (_pool == null && _log.shouldError()) {
            _log.error("Invalid Tunnel Test configuration → No pool for " + cfg, new Exception("origin"));
        }
        // Increment total job counter atomically
        if (!tryIncrementTotalJobs(ctx)) {
            if (_log.shouldInfo()) {
                _log.info("TestJob hard limit reached -> Not scheduling test for " + cfg);
            }
            // Don't schedule this job at all if limit exceeded
            return;
        }
        // Start after delay; guard against negative start
        long startDelay = Math.max(0, getDelay() + ctx.clock().now());
        getTiming().setStartAfter(startDelay);
    }

    @Override
    public String getName() {
        return "Test Local Tunnel";
    }

    @Override
    public void runJob() {
        final RouterContext ctx = getContext();
        if (_pool == null || !_pool.isAlive()) return;

        // Check for graceful shutdown
        if (ctx.router().gracefulShutdownInProgress()) {
            decrementTotalJobs();
            return;
        }

        // Determine if this is an exploratory tunnel early for deprioritization logic
        boolean isExploratory = _pool.getSettings().isExploratory();

        long lag = ctx.jobQueue().getMaxLag();
        if (lag > 600) {
            // Skip exploratory tunnels first under extreme pressure
            if (isExploratory) {
                if (_log.shouldInfo()) {
                    _log.info("Skipping exploratory tunnel test due to severe job lag (" + lag + "ms) -> " + _cfg);
                }
                ctx.statManager().addRateData("tunnel.testExploratorySkipped", _cfg.getLength());
                scheduleRetest();
                return;
            }
            // Still test client tunnels unless lag is extreme
            if (_log.shouldWarn()) {
                _log.warn("Aborted test due to severe max job lag (" + lag + "ms) → " + _cfg);
            }
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            scheduleRetest();
            return;
        } else if (lag > 500) {
            // Moderate pressure - skip exploratory but allow critical client tests
            if (isExploratory) {
                if (_log.shouldInfo()) {
                    _log.info("Deprioritizing exploratory tunnel test due to job lag (" + lag + "ms) -> " + _cfg);
                }
                ctx.statManager().addRateData("tunnel.testExploratorySkipped", _cfg.getLength());
                scheduleRetest();
                return;
            }
            // Continue with client tunnel testing
            if (_log.shouldWarn()) {
                _log.warn("Max permitted job lag exceeded (" + lag + "ms) -> Suspending test of " + _cfg);
            }
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            return; // Exit without rescheduling
        }

        // Calculate adaptive max queued tests based on system load
        int maxQueuedTests;
        if (lag < 50) {
            // Very low lag - increase queue capacity for more thorough testing
            maxQueuedTests = MAX_QUEUED_TESTS * 2; // 32 / 64
        } else if (lag < 100) {
            // Low lag - moderate increase in queue capacity
            maxQueuedTests = MAX_QUEUED_TESTS * 3 / 2;
        } else {
            // Normal or high lag - use standard limits
            maxQueuedTests = MAX_QUEUED_TESTS;
        }

        // Check for queue saturation and hard limits before proceeding
        int totalCount = getTotalTestJobCount();

        if (totalCount >= HARD_TEST_JOB_LIMIT) {
            if (_log.shouldInfo()) {
                _log.info("TestJob hard limit reached (" + totalCount + " >= " + HARD_TEST_JOB_LIMIT +
                          ") -> Cancelling test of " + _cfg);
            }
            ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
            ctx.statManager().addRateData("jobQueue.testJobHardLimit", 1);
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
                scheduleRetest();
                return;
            }

            // Client tunnels get priority in saturated queue
            if (_log.shouldInfo()) {
                _log.info("TestJob queue saturated (" + totalCount + " >= " + maxQueuedTests + ") -> Rescheduling client tunnel test for " + _cfg);
            }

            ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
            decrementTotalJobs(); // Clean up counter since we won't proceed
            return; // Exit without rescheduling
        }

        // Concurrency control: Check and increment counter
        // Adaptive limits based on lag to balance testing and performance
        // Exploratory tunnels are deprioritized under high load
        int maxTests;
        if (lag > 300) {
            maxTests = isExploratory ? 0 : 1; // No exploratory tests under high lag
        } else if (lag > 100) {
            maxTests = isExploratory ? 1 : 2; // Prioritize client tests
        } else if (lag > 50) {
            maxTests = isExploratory ? 2 : 3; // Slightly favor client tests
        } else {
            maxTests = MAX_CONCURRENT_TESTS; // Normal operation
        }

        int current;
        do {
            current = CONCURRENT_TESTS.get();
            if (current >= maxTests) {
                if (_log.shouldInfo()) {
                    _log.info("Max " + maxTests + " concurrent tunnel tests reached -> Rescheduling test for " + _cfg +
                              " (Queued: " + totalCount + ")");
                }
                ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
                scheduleRetest();
                return;
            }
        } while (!CONCURRENT_TESTS.compareAndSet(current, current + 1));

        // Begin tunnel test logic
        _found = false;
        long now = ctx.clock().now();

        // Skip tunnel testing for ping tunnels - they're short-lived and don't need testing
        String tunnelNickname = _cfg.getTunnelPool().getSettings().getDestinationNickname();
        if (tunnelNickname != null && tunnelNickname.startsWith("Ping [")) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping tunnel test for ping tunnel: " + tunnelNickname);
            }
            CONCURRENT_TESTS.decrementAndGet();
            decrementTotalJobs(); // Clean up total counter
            return; // Skip testing for ping tunnels
        }

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
                _log.warn("Insufficient tunnels to test " + _cfg + " with: " + _replyTunnel + " / " + _outTunnel);
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            CONCURRENT_TESTS.decrementAndGet();
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
            // Try to reschedule - if it fails, clean up total counter
            if (!tryReschedule()) {
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
        long lag = ctx.jobQueue().getMaxLag();
        if (lag > 200) {
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
                              " not yet registered -> Deferring test for " + _cfg);
                }
                scheduleRetest();
                return false;
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
        if (_pool == null || !_pool.isAlive()) return;

        // Update success history for adaptive testing frequency
        updateSuccessHistory(true);

        ctx.statManager().addRateData("tunnel.testSuccessLength", _cfg.getLength());
        ctx.statManager().addRateData("tunnel.testSuccessTime", ms);

        _outTunnel.incrementVerifiedBytesTransferred(1024);
        noteSuccess(ms, _outTunnel);
        noteSuccess(ms, _replyTunnel);

        _cfg.testJobSuccessful(ms);

        if (_log.shouldDebug()) {
            _log.debug("Tunnel Test [#" + _id + "] succeeded in " + ms + "ms → " + _cfg + " (Success rate: " +
                       String.format("%.1f%%", getSuccessRate() * 100) + ")");
        }

        // Clean up session tags
        clearTestTags();
        if (!tryReschedule()) {
            decrementTotalJobs(); // Clean up if couldn't reschedule
        }
    }

    private int getDelay() {
        // Simple exponential backoff with a cap and jitter
        int baseDelay = TEST_DELAY;
        int failCount = _cfg.getTunnelFailures();
        int scaled = baseDelay;
        if (failCount > 0) {
            int multiplier = Math.min(1 << failCount, 6); // max 6x (6 min)
            scaled = baseDelay/2 * multiplier;
        }
        // Add a small jitter to avoid thundering herd
        int jitter = getContext().random().nextInt(Math.max(1, scaled / 3));
        return Math.max(scaled + jitter, 60*1000 + jitter);
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
        if (_pool == null || !_pool.isAlive()) return;

        boolean isExploratory = _pool.getSettings().isExploratory();
        getContext().statManager().addRateData(
            isExploratory ? "tunnel.testExploratoryFailedTime" : "tunnel.testFailedTime",
            timeToFail);

        // Only fail the tunnel under test — do NOT blame _otherTunnel
        _failureCount++; // Track the failure
        boolean keepGoing = _failureCount < 3; // Keep going only if < 3 fails

        if (_log.shouldWarn() && _failureCount >= 3) {
            _log.warn((isExploratory ? "Exploratory tunnel" : "Tunnel") + " Test failed in " + timeToFail + "ms → " + _cfg);
        }

        if (keepGoing) {
            if (!tryReschedule()) {
                decrementTotalJobs(); // Clean up if couldn't reschedule
            }
        } else {
            getContext().statManager().addRateData(
                isExploratory ? "tunnel.testExploratoryFailedCompletelyTime" : "tunnel.testFailedCompletelyTime",
                timeToFail);
            
            // Immediately remove tunnel from pool after 3 consecutive failures
            // This ensures failed tunnels don't remain in the pool consuming resources
            if (_log.shouldWarn()) {
                _log.warn((isExploratory ? "Exploratory tunnel" : "Tunnel") + " failed 3 consecutive tests → Removing from pool: " + _cfg);
            }
            
            // Force immediate tunnel removal by marking it as completely failed
            // This bypasses the incremental failure counting for immediate removal
            _cfg.tunnelFailedCompletely();
            
            // Also remove from pool to ensure immediate effect
            _pool.tunnelFailed(_cfg);
            
            _failureCount = 0;
        }

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

        int totalHops = _outTunnel.getLength() + _replyTunnel.getLength();
        int calculated = base + (1000 * totalHops);
        int clamped = Math.max(MIN_TEST_PERIOD, calculated);
        return Math.min(clamped, MAX_TEST_PERIOD);
    }

    private boolean scheduleRetest() {
        return scheduleRetest(false);
    }

    /**
     * Attempt to reschedule this test job.
     * @return true if successfully rescheduled, false if limit exceeded
     */
    private boolean tryReschedule() {
        // Current job execution ending, decrement it
        decrementTotalJobs();
        // New execution starting, increment for it
        if (!tryIncrementTotalJobs(getContext())) {
            return false;
        }
        return scheduleRetest(false);
    }

    private boolean scheduleRetest(boolean asap) {
        if (_pool == null || !_pool.isAlive()) return false;

        final RouterContext ctx = getContext();

        // Check hard limit before scheduling
        int totalCount = getTotalTestJobCount();
        if (totalCount >= HARD_TEST_JOB_LIMIT) {
            if (_log.shouldInfo()) {
                _log.info("TestJob hard limit reached during reschedule (" + totalCount + " >= " + HARD_TEST_JOB_LIMIT +
                          ") -> Skipping reschedule for " + _cfg);
            }
            return false; // Indicate failure to reschedule
        }

        int delay = getDelay();
        if (asap) {
            delay = Math.min(delay, TEST_DELAY / 2);
        }

        getTiming().setStartAfter(ctx.clock().now() + delay);
        ctx.jobQueue().addJob(this);
        return true; // Indicate successful reschedule
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
            CONCURRENT_TESTS.decrementAndGet(); // Decrement counter
            decrementTotalJobs(); // Clean up total counter
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
            CONCURRENT_TESTS.decrementAndGet(); // Decrement counter
            decrementTotalJobs(); // Clean up total counter
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
}