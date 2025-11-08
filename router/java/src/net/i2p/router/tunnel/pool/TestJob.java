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
import net.i2p.stat.StatManager;
import net.i2p.util.Log;

/**
 * Periodically tests a tunnel to ensure it's functional.
 * Implements exponential backoff and retry logic to reduce false negatives.
 * Avoids rescheduling if tunnel pool is dead or router is shutting down.
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

    /** Base delay before first test (in ms) */
    private static final int TEST_DELAY = 3 * 60 * 1000;

    /** Max failures before logging a warning */
    private static final int MAX_FAILURES_BEFORE_ABORT = 3;

    /** Max job queue lag before deferring test (in ms) */
    private static final int MAX_JOB_LAG = 200;

    /** Max number of consecutive failures before backoff */
    private static final int MAX_FAILURE_RETRIES = 3;

    private int _failureCount = 0;

    public TestJob(RouterContext ctx, PooledTunnelCreatorConfig cfg, TunnelPool pool) {
        super(ctx);
        _log = ctx.logManager().getLog(TestJob.class);
        _cfg = cfg;
        _pool = pool != null ? pool : cfg.getTunnelPool();

        if (_pool == null && _log.shouldError()) {
            _log.error("Invalid tunnel test configuration -> No pool for " + cfg, new Exception("origin"));
        }

        getTiming().setStartAfter(getDelay() + ctx.clock().now());
    }

    @Override
    public String getName() {
        return "Test Local Tunnel";
    }

    @Override
    public void runJob() {
        if (_pool == null || !_pool.isAlive()) return;
        final RouterContext ctx = getContext();
        long lag = ctx.jobQueue().getMaxLag();

        // Avoid false negatives due to job queue lag
        if (lag > MAX_JOB_LAG) {
            if (_log.shouldWarn()) {
                _log.warn("Deferred test due to job lag (" + lag + "ms) -> " + _cfg);
            }
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            scheduleRetest();
            return;
        }

        if (ctx.router().gracefulShutdownInProgress()) return;

        _found = false;
        selectTunnels();

        if (_replyTunnel == null || _outTunnel == null) {
            if (_log.shouldWarn()) {
                _log.warn("Insufficient tunnels to test " + _cfg + " with: " + _replyTunnel + " / " + _outTunnel);
            }
            ctx.statManager().addRateData("tunnel.testAborted", _cfg.getLength());
            scheduleRetest();
        } else {
            int testPeriod = getTestPeriod();
            long now = ctx.clock().now();
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
            sendTest(m, testPeriod);
        }
    }

    /**
     * Selects inbound/outbound tunnels based on config.
     */
    private void selectTunnels() {
        final RouterContext ctx = getContext();
        boolean isExpl = _pool.getSettings().isExploratory();
        if (_cfg.isInbound()) {
            _replyTunnel = _cfg;
            _outTunnel = isExpl ?
                ctx.tunnelManager().selectOutboundTunnel() :
                ctx.tunnelManager().selectOutboundTunnel(_pool.getSettings().getDestination());
        } else {
            _replyTunnel = isExpl ?
                ctx.tunnelManager().selectInboundTunnel() :
                ctx.tunnelManager().selectInboundTunnel(_pool.getSettings().getDestination());
            _outTunnel = _cfg;
        }
        _otherTunnel = (PooledTunnelCreatorConfig) (_cfg.isInbound() ? _outTunnel : _replyTunnel);
    }

    private void sendTest(I2NPMessage m, int testPeriod) {
        final RouterContext ctx = getContext();
        _id = __id.getAndIncrement();
        MessageWrapper.OneTimeSession sess = null;

        if (ctx.random().nextInt(4) != 0) {
            if (_cfg.isInbound() && !_pool.getSettings().isExploratory()) {
                sess = MessageWrapper.generateSession(ctx, _pool.getSettings().getDestination(), testPeriod, false);
            } else {
                sess = MessageWrapper.generateSession(ctx, testPeriod);
            }

            if (sess == null) {
                scheduleRetest();
                return;
            }

            if (sess.tag != null) {
                _encryptTag = sess.tag;
                m = MessageWrapper.wrap(ctx, m, sess.key, sess.tag);
            } else {
                _ratchetEncryptTag = sess.rtag;
                m = MessageWrapper.wrap(ctx, m, sess.key, sess.rtag);
            }

            if (m == null) {
                scheduleRetest();
                return;
            }
        } else {
            if (_log.shouldDebug()) {
                _log.debug("Sending unencrypted garlic test to provide cover for NetDb replies...");
            }
        }

        if (_log.shouldDebug()) {
            _log.debug("Sending garlic test [#" + _id + "] of " + _outTunnel + " / " + _replyTunnel);
        }

        ctx.tunnelDispatcher().dispatchOutbound(m, _outTunnel.getSendTunnelId(0),
                                               _replyTunnel.getReceiveTunnelId(0),
                                               _replyTunnel.getPeer(0));
    }

    public void testSuccessful(int ms) {
        if (_pool == null || !_pool.isAlive()) return;

        RouterContext ctx = getContext();
        StatManager stat = ctx.statManager();
        stat.addRateData("tunnel.testSuccessLength", _cfg.getLength());
        stat.addRateData("tunnel.testSuccessTime", ms);
        _outTunnel.incrementVerifiedBytesTransferred(1024);

        noteSuccess(ms, _outTunnel);
        noteSuccess(ms, _replyTunnel);
        cleanupTags();

        _cfg.testJobSuccessful(ms);
        if (_otherTunnel.getLength() > 1) {
            _otherTunnel.testJobSuccessful(ms);
        }

        if (_log.shouldDebug()) {
            _log.debug("Tunnel Test [#" + _id + "] succeeded in " + ms + "ms -> " + _cfg);
        }

        _failureCount = 0; // Reset retry counter on success
        scheduleRetest();
    }

    private void noteSuccess(long ms, TunnelInfo tunnel) {
        if (tunnel != null) {
            for (int i = 0; i < tunnel.getLength(); i++) {
                getContext().profileManager().tunnelTestSucceeded(tunnel.getPeer(i), ms);
            }
        }
    }

    private void testFailed(long timeToFail) {
        if (_pool == null || !_pool.isAlive()) return;

        _failureCount++;
        if (_failureCount >= MAX_FAILURES_BEFORE_ABORT) {
            if (_log.shouldWarn() && getContext().router().getUptime() > 5*60*1000) {
                boolean isExpl = _pool.getSettings().isExploratory();
                _log.warn((isExpl ? "Exploratory tunnel" : "Tunnel") +
                          " Test failed after " + _failureCount + " attempts in " + timeToFail + "ms -> " + _cfg);
            }
            _failureCount = 0;
        }

        if (_found) {
            noteSuccess(timeToFail, _outTunnel);
            noteSuccess(timeToFail, _replyTunnel);
        }

        boolean isExpl = _pool.getSettings().isExploratory();
        StatManager stat = getContext().statManager();
        stat.addRateData(isExpl ? "tunnel.testExploratoryFailedTime" : "tunnel.testFailedTime", timeToFail);

        boolean keepGoing = _cfg.tunnelFailed();
        if (_otherTunnel.getLength() > 1) {
            _otherTunnel.tunnelFailed();
        }

        cleanupTags();

        if (keepGoing) {
            scheduleRetestWithBackoff();
        } else {
            stat.addRateData(isExpl ? "tunnel.testExploratoryFailedCompletelyTime" : "tunnel.testFailedCompletelyTime", timeToFail);
        }
    }

    private void cleanupTags() {
        if (_encryptTag != null || _ratchetEncryptTag != null) {
            SessionKeyManager skm;
            if (_cfg.isInbound() && !_pool.getSettings().isExploratory()) {
                skm = getContext().clientManager().getClientSessionKeyManager(_pool.getSettings().getDestination());
            } else {
                skm = getContext().sessionKeyManager();
            }

            if (skm != null) {
                if (_encryptTag != null) {
                    skm.consumeTag(_encryptTag); // AES
                } else if (_ratchetEncryptTag != null) {
                    RatchetSKM rskm = getRatchetSKM(skm);
                    if (rskm != null) {
                        rskm.consumeTag(_ratchetEncryptTag);
                    }
                }
            }
        }
    }

    private RatchetSKM getRatchetSKM(SessionKeyManager skm) {
        if (skm instanceof RatchetSKM) {
            return (RatchetSKM) skm;
        } else if (skm instanceof MuxedSKM) {
            return ((MuxedSKM) skm).getECSKM();
        } else if (skm instanceof MuxedPQSKM) {
            return ((MuxedPQSKM) skm).getECSKM();
        }
        return null;
    }

    private int getDelay() {
        return TEST_DELAY + getContext().random().nextInt(TEST_DELAY / 3);
    }

    private int getTestPeriod() {
        if (_outTunnel == null || _replyTunnel == null) return 30_000;

        RateStat tspt = getContext().statManager().getRate("transport.sendProcessingTime");
        int avg = tspt != null ? (int) tspt.getRate(60_000).getAverageValue() : 500;
        int delay = 3 * avg;
        return delay + 2500 * (_outTunnel.getLength() + _replyTunnel.getLength());
    }

    private void scheduleRetest() {
        scheduleRetest(false);
    }

    /**
     * Schedule next test, optionally ASAP.
     */
    private void scheduleRetest(boolean asap) {
        if (_pool == null || !_pool.isAlive()) return;
        RouterContext ctx = getContext();
        long now = ctx.clock().now();

        if (asap) {
            if (_cfg.getExpiration() > now + 60_000) {
                requeue(TEST_DELAY / 4 + ctx.random().nextInt(TEST_DELAY / 4));
            }
        } else {
            int delay = getDelay();
            if (_cfg.getExpiration() > now + delay + 3 * getTestPeriod()) {
                requeue(delay);
            }
        }
    }

    /**
     * Schedule next test with exponential backoff.
     */
    private void scheduleRetestWithBackoff() {
        if (_pool == null || !_pool.isAlive()) return;
        RouterContext ctx = getContext();
        long now = ctx.clock().now();

        int delay = Math.min(TEST_DELAY * (1 << Math.min(_failureCount, MAX_FAILURE_RETRIES)), 5 * 60 * 1000);
        delay += ctx.random().nextInt(delay / 4);

        if (_cfg.getExpiration() > now + delay + 3 * getTestPeriod()) {
            requeue(delay);
        }
    }

    private class ReplySelector implements MessageSelector {
        private final long _id;
        private final long _expiration;

        public ReplySelector(long id, long expiration) {
            _id = id;
            _expiration = expiration;
        }

        @Override
        public boolean continueMatching() {
            return !_found && getContext().clock().now() < _expiration;
        }

        @Override
        public long getExpiration() {
            return _expiration;
        }

        @Override
        public boolean isMatch(I2NPMessage message) {
            return message.getType() == DeliveryStatusMessage.MESSAGE_TYPE &&
                   ((DeliveryStatusMessage) message).getMessageId() == _id;
        }

        @Override
        public String toString() {
            return "Testing tunnel " + (_found ? "" : " waiting for ") + _id + " -> " + _cfg ;
        }
    }

    private class OnTestReply extends JobImpl implements ReplyJob {
        private long _successTime;
        private OutNetMessage _sentMessage;

        public OnTestReply() {
            super(TestJob.this.getContext());
        }

        @Override
        public String getName() {
            return "Verify Tunnel Test";
        }

        public void setSentMessage(OutNetMessage m) {
            _sentMessage = m;
        }

        @Override
        public void runJob() {
            if (_sentMessage != null) {
                getContext().messageRegistry().unregisterPending(_sentMessage);
            }
            cleanupTags();
            if (_successTime < getTestPeriod()) {
                testSuccessful((int) _successTime);
            } else {
                testFailed(_successTime);
            }
            _found = true;
        }

        @Override
        public void setMessage(I2NPMessage message) {
            _successTime = getContext().clock().now() - ((DeliveryStatusMessage) message).getArrival();
        }

        @Override
        public String toString() {
            return "Testing tunnel " + _cfg + " successful after " + _successTime + "ms";
        }
    }

    private class OnTestTimeout extends JobImpl {
        private final long _started;

        public OnTestTimeout(long now) {
            super(TestJob.this.getContext());
            _started = now;
        }

        @Override
        public String getName() {
            return "Timeout Tunnel Test";
        }

        @Override
        public void runJob() {
            if (_log.shouldDebug()) {
                _log.debug("Tunnel Test [#" + _id + "] timeout -> Found? " + _found);
            }
            testFailed(getContext().clock().now() - _started);
        }

        @Override
        public String toString() {
            return "Testing tunnel " + _cfg + " timed out";
        }
    }
}