package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 * Tests for {@link TunnelPool#reportSendFailure(TunnelInfo, int)} and the
 * pure threshold helpers {@link TunnelPool#exceedsRemovalThreshold(int)} /
 * {@link TunnelPool#exceedsRemovalThreshold(int, boolean)}.
 * Hard statuses share the cumulative counter with TestJob and remove only
 * after the count exceeds MAX_CONSECUTIVE_TEST_FAILURES (3); soft send
 * timeouts (status 3) increment only the soft counter and remove only above
 * SOFT_REMOVAL_THRESHOLD — they must not trip getTunnelFailed() or
 * selection gates at the hard bar.
 *
 * @since 0.9.71+
 */
public class TunnelPoolReportSendFailureTest {

    private RouterContext _ctx;
    private TunnelPoolSettings _settings;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _settings = new TunnelPoolSettings(Hash.FAKE_HASH, false);
        _settings.setLength(3);
    }

    // ---------- exceedsRemovalThreshold (pure) ----------

    @Test
    public void thresholdAtMaxIsFalse() {
        // Exactly MAX (3) is still under the removal bar
        assertFalse(TunnelPool.exceedsRemovalThreshold(TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES));
    }

    @Test
    public void thresholdAboveMaxIsTrue() {
        assertTrue(TunnelPool.exceedsRemovalThreshold(TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES + 1));
    }

    @Test
    public void thresholdZeroAndLowCountsAreFalse() {
        assertFalse(TunnelPool.exceedsRemovalThreshold(0));
        assertFalse(TunnelPool.exceedsRemovalThreshold(1));
        assertFalse(TunnelPool.exceedsRemovalThreshold(2));
    }

    // ---------- soft (status-3) threshold ----------

    @Test
    public void softThresholdBelowBarIsFalse() {
        assertFalse(TunnelPool.exceedsRemovalThreshold(TunnelPool.SOFT_REMOVAL_THRESHOLD, true));
        assertFalse(TunnelPool.exceedsRemovalThreshold(0, true));
        assertFalse(TunnelPool.exceedsRemovalThreshold(4, true));
        assertFalse(TunnelPool.exceedsRemovalThreshold(9, true));
    }

    @Test
    public void softThresholdAboveBarIsTrue() {
        assertTrue(TunnelPool.exceedsRemovalThreshold(TunnelPool.SOFT_REMOVAL_THRESHOLD + 1, true));
    }

    @Test
    public void softStatusIsDetected() {
        assertTrue(TunnelPool.isSoftSendFailure(
                MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE));
        assertFalse(TunnelPool.isSoftSendFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL));
        assertFalse(TunnelPool.isSoftSendFailure(
                MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS));
    }

    // ---------- reportSendFailure behavior ----------

    /**
     * A single hard dispatch failure must increment the shared counter
     * but must NOT remove the tunnel.
     */
    @Test
    public void singleFailureDoesNotRemove() {
        TunnelPool pool = createPool();
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger failures = new AtomicInteger(0);
        AtomicInteger soft = new AtomicInteger(0);
        TunnelInfo tunnel = stubTunnel(failed, failures, soft, 1);

        pool.reportSendFailure(tunnel, MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL);

        assertEquals(1, failures.get());
        assertEquals(0, soft.get());
        assertFalse(failed.get());
        assertFalse(TunnelPool.exceedsRemovalThreshold(failures.get()));
    }

    /**
     * The fourth cumulative failure (count exceeds MAX=3) takes the removal path.
     * Use length 1 so tellProfileFailed returns early without touching profiles.
     */
    @Test
    public void fourthCumulativeFailureTakesRemovalPath() {
        TunnelPool pool = createPool();
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger failures = new AtomicInteger(TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES);
        AtomicInteger soft = new AtomicInteger(0);
        TunnelInfo tunnel = stubTunnel(failed, failures, soft, 1);

        pool.reportSendFailure(tunnel, MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL);

        assertEquals(TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES + 1, failures.get());
        assertTrue(TunnelPool.exceedsRemovalThreshold(failures.get()));
    }

    /**
     * An already-failed tunnel is left alone.
     */
    @Test
    public void alreadyFailedTunnelIsSkipped() {
        TunnelPool pool = createPool();
        AtomicBoolean failed = new AtomicBoolean(true);
        AtomicInteger failures = new AtomicInteger(99);
        AtomicInteger soft = new AtomicInteger(0);
        TunnelInfo tunnel = stubTunnel(failed, failures, soft, 1);

        pool.reportSendFailure(tunnel, MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL);

        // No increment — early return on getTunnelFailed()
        assertEquals(99, failures.get());
        assertEquals(0, soft.get());
    }

    /**
     * A null tunnel must be a no-op (defensive — callers check _outTunnel).
     */
    @Test
    public void nullTunnelIsNoOp() {
        TunnelPool pool = createPool();
        pool.reportSendFailure(null, MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL);
        // Should not throw
    }

    /**
     * Soft status-3 failures increment ONLY the soft counter and do not
     * remove at the hard bar (4) — only above SOFT_REMOVAL_THRESHOLD.
     * This is the fix: soft must not trip getTunnelFailed()/passesScanGates.
     */
    @Test
    public void softTimeoutDoesNotTouchHardCounterOrRemoveAtHardBar() {
        TunnelPool pool = createPool();
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger failures = new AtomicInteger(TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES);
        AtomicInteger soft = new AtomicInteger(0);
        TunnelInfo tunnel = stubTunnel(failed, failures, soft, 1);

        pool.reportSendFailure(tunnel, MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE);

        // Soft only — hard counter unchanged, soft incremented once
        assertEquals(1, soft.get());
        assertEquals(TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES, failures.get());
        assertFalse(failed.get());
        assertFalse(TunnelPool.exceedsRemovalThreshold(soft.get(), true));
        // Hard count still at MAX from setup — soft did not push it over
        assertFalse(TunnelPool.exceedsRemovalThreshold(failures.get(), false));
    }

    /**
     * Soft status-3 failures remove once the soft counter exceeds
     * SOFT_REMOVAL_THRESHOLD. failWithCount uses the soft count so removal
     * works even when the hard counter is still 0.
     */
    @Test
    public void softTimeoutRemovesAboveSoftBar() {
        TunnelPool pool = createPool();
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger failures = new AtomicInteger(0);
        AtomicInteger soft = new AtomicInteger(TunnelPool.SOFT_REMOVAL_THRESHOLD);
        TunnelInfo tunnel = stubTunnel(failed, failures, soft, 1);

        pool.reportSendFailure(tunnel, MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE);

        assertEquals(TunnelPool.SOFT_REMOVAL_THRESHOLD + 1, soft.get());
        // Hard counter must stay at 0 — soft never touches it
        assertEquals(0, failures.get());
        assertTrue(TunnelPool.exceedsRemovalThreshold(soft.get(), true));
        // getTunnelFailed must remain false so selection gates stay open
        assertFalse(failed.get());
    }

    /**
     * Soft failures from a clean tunnel must not trip the scan gate
     * (consecutiveFailures > MAX) after four soft timeouts.
     */
    @Test
    public void fourSoftTimeoutsDoNotTripScanGate() {
        TunnelPool pool = createPool();
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger failures = new AtomicInteger(0);
        AtomicInteger soft = new AtomicInteger(0);
        TunnelInfo tunnel = stubTunnel(failed, failures, soft, 1);

        for (int i = 0; i < 4; i++) {
            pool.reportSendFailure(tunnel, MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE);
        }

        assertEquals(4, soft.get());
        assertEquals(0, failures.get());
        assertFalse(failed.get());
        // Scan gate keys on getConsecutiveFailures() — still 0, so passes
        assertFalse(failures.get() > TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES);
        TunnelInfo clean = mock(TunnelInfo.class);
        when(clean.getTunnelFailed()).thenReturn(false);
        when(clean.getConsecutiveFailures()).thenReturn(failures.get());
        when(clean.getExpiration()).thenReturn(2_000_000L);
        when(clean.getLength()).thenReturn(3);
        assertTrue(TunnelPool.passesScanGates(clean, 1_000_000L, false));
    }

    // ---------- LS thrash pure helpers ----------

    @Test
    public void shouldDeferMissingPublishedWhenRecentlyPublished() {
        long throttle = 2L * 60 * 1000;
        long now = 1_000_000L;
        // published within throttle → async in flight → defer
        assertTrue(TunnelPool.shouldDeferMissingPublished(now - 1, now, throttle));
        assertTrue(TunnelPool.shouldDeferMissingPublished(now - throttle + 1, now, throttle));
        // never published → force (first publish must go through)
        assertFalse(TunnelPool.shouldDeferMissingPublished(0, now, throttle));
        assertFalse(TunnelPool.shouldDeferMissingPublished(-throttle, now, throttle));
        // throttle expired → publish again
        assertFalse(TunnelPool.shouldDeferMissingPublished(now - throttle, now, throttle));
        assertFalse(TunnelPool.shouldDeferMissingPublished(now - throttle - 1, now, throttle));
    }

    @Test
    public void shouldSkipAsyncPublishOnlyWhenNullAndRecent() {
        long throttle = 2L * 60 * 1000;
        long now = 1_000_000L;
        // LS present → never skip based on async (caller may still throttle)
        assertFalse(TunnelPool.shouldSkipAsyncPublish(true, now - 1, now, throttle));
        assertFalse(TunnelPool.shouldSkipAsyncPublish(true, 0, now, throttle));
        // LS null + recent → skip (async in flight)
        assertTrue(TunnelPool.shouldSkipAsyncPublish(false, now - 1, now, throttle));
        assertTrue(TunnelPool.shouldSkipAsyncPublish(false, now - throttle + 1, now, throttle));
        // LS null + never published → do not skip (first publish)
        assertFalse(TunnelPool.shouldSkipAsyncPublish(false, 0, now, throttle));
        assertFalse(TunnelPool.shouldSkipAsyncPublish(false, -throttle, now, throttle));
        // LS null + throttle expired → publish again
        assertFalse(TunnelPool.shouldSkipAsyncPublish(false, now - throttle, now, throttle));
    }

    private TunnelPool createPool() {
        TunnelPoolManager mgr = mock(TunnelPoolManager.class);
        TunnelPeerSelector sel = mock(TunnelPeerSelector.class);
        return new TunnelPool(_ctx, mgr, _settings, sel);
    }

    /**
     * Mock tunnel whose hard and soft counters and failed-flag behave like
     * TunnelCreatorConfig. Length 1 keeps tellProfileFailed on its early-return path.
     * getTunnelFailed is driven only by the explicit failed flag — soft
     * status-3 reports must not touch the hard counter at all.
     */
    private TunnelInfo stubTunnel(AtomicBoolean failed, AtomicInteger failures,
                                                 AtomicInteger soft, int length) {
        TunnelCreatorConfig cfg = mock(TunnelCreatorConfig.class);
        when(cfg.getTunnelFailed()).thenAnswer(inv -> failed.get());
        when(cfg.getConsecutiveFailures()).thenAnswer(inv -> failures.get());
        when(cfg.getSoftFailures()).thenAnswer(inv -> soft.get());
        when(cfg.getDestination()).thenReturn(_settings.getDestination());
        when(cfg.isInbound()).thenReturn(false);
        when(cfg.getPeer(0)).thenReturn(Hash.create(new byte[Hash.HASH_LENGTH]));
        when(cfg.getLength()).thenReturn(length);
        when(cfg.getLastLatency()).thenReturn(100);
        when(cfg.getAverageLatency()).thenReturn(100);
        doAnswer(inv -> { failures.incrementAndGet(); return null; }).when(cfg).incrementTestFailures();
        doAnswer(inv -> { soft.incrementAndGet(); return null; }).when(cfg).incrementSoftFailures();
        doAnswer(inv -> {
            failures.set(TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES + 1);
            failed.set(true);
            return null;
        }).when(cfg).tunnelFailedCompletely();
        return cfg;
    }
}
