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
import net.i2p.router.crypto.ratchet.RatchetSessionTag;
import net.i2p.router.crypto.ratchet.RatchetSKM;
import net.i2p.router.networkdb.kademlia.MessageWrapper;
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

    /**
     * Maximum number of TestJob instances that should be queued before deferring new ones.
     * Prevents job queue saturation from too many waiting tunnel tests.
     */
    private static final int MAX_QUEUED_TESTS = SystemVersion.isSlow() ? 20 : 32;

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
     * Get the number of TestJob instances currently queued in the job queue.
     * This provides visibility into how many tunnel tests are waiting to be executed.
     *
     * @param ctx the router context
     * @return the total number of TestJob instances in all queue states
     */
    private static int getQueuedTestJobCount(RouterContext ctx) {
        if (ctx != null && ctx.jobQueue() != null) {
            return ctx.jobQueue().getTestJobCount();
        }
        return 0;
    }

    public TestJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        super(ctx);
        _log = ctx.logManager().getLog(TestJob.class);
        _cfg = cfg;
        _pool = (pool != null) ? pool : cfg.getTunnelPool();
        if (_pool == null && _log.shouldError()) {
            _log.error("Invalid Tunnel Test configuration → No pool for " + cfg, new Exception("origin"));
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
        if (ctx.router().gracefulShutdownInProgress()) return;

        long lag = ctx.jobQueue().getMaxLag();
        if (lag > 600) {
            if (_log.shouldWarn()) {
                _log.warn("Aborted test due to severe max job lag (" + lag + "ms) → " + _cfg);
            }
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            scheduleRetest();
            return;
        } else if (lag > 500) {
            if (_log.shouldWarn()) {
                _log.warn("Max permitted job lag exceeded (" + lag + "ms) -> Suspending " + _cfg + " tests...");
            }
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            return; // Exit without rescheduling
        }

        // Check for queue saturation and hard limits before proceeding
        int queuedCount = getQueuedTestJobCount(ctx);
        int totalCount = CONCURRENT_TESTS.get() + queuedCount;

        if (totalCount >= HARD_TEST_JOB_LIMIT) {
            if (_log.shouldWarn()) {
                _log.warn("TestJob hard limit reached (" + totalCount + " >= " + HARD_TEST_JOB_LIMIT +
                          ") -> Cancelling " + _cfg + " test...");
            }
            ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
            ctx.statManager().addRateData("jobQueue.testJobHardLimit", 1);
            return; // Exit without rescheduling
        }

        if (queuedCount >= MAX_QUEUED_TESTS) {
            if (_log.shouldInfo()) {
                _log.info("TestJob queue saturated (" + queuedCount + " >= " + MAX_QUEUED_TESTS +
                          ") -> Rescheduling " + _cfg + " test...");
            }
            ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
            scheduleRetest();
            return;
        }

        // Concurrency control: Check and increment counter
        // Adaptive limits based on lag to balance testing and performance
        int maxTests;
        if (lag > 300) {
            maxTests = 1; // High lag - minimal testing
        } else if (lag > 200) {
            maxTests = 3; // Moderate lag - minimal testing
        } else if (lag > 100) {
            maxTests = 4; // Low lag - reduced testing
        } else {
            maxTests = MAX_CONCURRENT_TESTS; // Normal operation
        }

        int current;
        do {
            current = CONCURRENT_TESTS.get();
            if (current >= maxTests) {
                if (_log.shouldInfo()) {
                    _log.info("Max " + maxTests + " concurrent tunnel tests reached -> Rescheduling test for " + _cfg +
                              " (queued: " + queuedCount + ")...");
                }
                ctx.statManager().addRateData("tunnel.testThrottled", _cfg.getLength());
                scheduleRetest();
                return;
            }
        } while (!CONCURRENT_TESTS.compareAndSet(current, current + 1));

        // Begin tunnel test logic
        _found = false;
        boolean isExploratory = _pool.getSettings().isExploratory();
        long now = ctx.clock().now();

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
            scheduleRetest();
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
            _log.debug("Sending " + (useEncryption ? "" : "unencrypted ") + "garlic test [#" + _id + "] of "+
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

        ctx.statManager().addRateData("tunnel.testSuccessLength", _cfg.getLength());
        ctx.statManager().addRateData("tunnel.testSuccessTime", ms);

        _outTunnel.incrementVerifiedBytesTransferred(1024);
        noteSuccess(ms, _outTunnel);
        noteSuccess(ms, _replyTunnel);

        _cfg.testJobSuccessful(ms);

        if (_log.shouldDebug()) {
            _log.debug("Tunnel Test [#" + _id + "] succeeded in " + ms + "ms → " + _cfg + " (concurrent tests: " +
                      CONCURRENT_TESTS.get() + ")");
        }

        // Clean up session tags
        clearTestTags();

        scheduleRetest();
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
            scheduleRetest(true);
        } else {
            getContext().statManager().addRateData(
                isExploratory ? "tunnel.testExploratoryFailedCompletelyTime" : "tunnel.testFailedCompletelyTime",
                timeToFail);
               _failureCount = 0;
        }

    }

    private int getDelay() {
        // Exponential backoff with a cap and jitter
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

    private int getTestPeriod() {
        final RouterContext ctx = getContext();
        if (_outTunnel == null || _replyTunnel == null) return MAX_TEST_PERIOD;

        RateStat tspt = ctx.statManager().getRate("transport.sendProcessingTime");
        int base = 0;
        if (tspt != null) {
            Rate r = tspt.getRate(60 * 1000);
            if (r != null) {
                base = 3 * (int) r.getAverageValue();
            }
        }

        int totalHops = _outTunnel.getLength() + _replyTunnel.getLength();
        int calculated = base + (1000 * totalHops);
        int clamped = Math.max(MIN_TEST_PERIOD, calculated);
        return Math.min(clamped, MAX_TEST_PERIOD);
    }

    private void scheduleRetest() {
        scheduleRetest(false);
    }

    private void scheduleRetest(boolean asap) {
        if (_pool == null || !_pool.isAlive()) return;

        final RouterContext ctx = getContext();

        // Check hard limit before scheduling
        int queuedCount = getQueuedTestJobCount(ctx);
        int totalCount = CONCURRENT_TESTS.get() + queuedCount;
        if (totalCount >= HARD_TEST_JOB_LIMIT) {
            if (_log.shouldInfo()) {
                _log.info("TestJob hard limit reached during reschedule (" + totalCount + " >= " + HARD_TEST_JOB_LIMIT +
                          ") -> Skipping reschedule for " + _cfg);
            }
            return; // Skip rescheduling to prevent backlog growth
        }

        int delay = getDelay();
        if (asap) {
            delay = Math.min(delay, TEST_DELAY / 2);
        }

        getTiming().setStartAfter(ctx.clock().now() + delay);
        ctx.jobQueue().addJob(this);
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
            if (_log.shouldDebug()) {
                _log.debug("Tunnel test reply received for " + _cfg + " (concurrent tests: " + CONCURRENT_TESTS.get() + ")");
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
            if (_log.shouldDebug()) {
                _log.debug("Tunnel test timeout for " + _cfg + " (concurrent tests: " + CONCURRENT_TESTS.get() + ")");
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