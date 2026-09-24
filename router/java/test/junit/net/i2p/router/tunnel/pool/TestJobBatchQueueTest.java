package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelTestStatus;
import net.i2p.util.Clock;

import org.junit.Test;

/**
 * Unit tests for the pure helpers behind the batched first-test dispatch:
 * buffer claiming ({@link TestJob#claimBatch}), bounded offering
 * ({@link TestJob#offerBounded}), queue-health pacing
 * ({@link TestJob#pumpDelayMs}), the in-flight admission gates
 * ({@link TestJob#batchDenialReason}), the drain-time staleness
 * predicate ({@link TestJob#shouldDropPending}), the per-pool
 * claim/release pairing ({@link TestJob#claimPoolTestSlot}), and the
 * load-aware GOOD-traffic defer ({@link TestJob#shouldDeferActiveGoodTunnel}).
 *
 * @since 0.9.71+
 */
public class TestJobBatchQueueTest {

    private static final long NOW = 1_000_000L;

    // ---------------- claimBatch ----------------

    @Test
    public void testClaimBatchDrainsFifoUpToMax() {
        Deque<String> buf = new ArrayDeque<String>(Arrays.asList("a", "b", "c"));
        List<String> claimed = TestJob.claimBatch(buf, 2, s -> true);
        assertEquals(Arrays.asList("a", "b"), claimed);
        assertEquals(1, buf.size());
        assertEquals("c", buf.peekFirst());
    }

    @Test
    public void testClaimBatchStopsAtFirstIneligibleAndKeepsItAtHead() {
        Deque<String> buf = new ArrayDeque<String>(Arrays.asList("ok1", "deny", "ok2"));
        List<String> claimed = TestJob.claimBatch(buf, 10, s -> s.startsWith("ok"));
        assertEquals(Collections.singletonList("ok1"), claimed);
        assertEquals(2, buf.size());
        assertEquals("deny", buf.peekFirst());
        assertEquals("ok2", buf.peekLast());
    }

    @Test
    public void testClaimBatchEmptyWhenHeadIneligible() {
        Deque<String> buf = new ArrayDeque<String>(Collections.singletonList("deny"));
        assertTrue(TestJob.claimBatch(buf, 4, s -> false).isEmpty());
        assertEquals(1, buf.size());
    }

    @Test
    public void testClaimBatchEmptyBuffer() {
        assertTrue(TestJob.claimBatch(new ArrayDeque<String>(), 4, s -> true).isEmpty());
    }

    @Test
    public void testClaimBatchRemovesEligibleDropCandidates() {
        // eligible means "remove from the buffer": a stale drop-candidate is
        // claimed and discarded by the caller, then the retry-later head halts
        Deque<String> buf = new ArrayDeque<String>(Arrays.asList("stale", "live"));
        List<String> claimed = TestJob.claimBatch(buf, 4, s -> s.equals("stale"));
        assertEquals(Collections.singletonList("stale"), claimed);
        assertEquals(Collections.singletonList("live"), new ArrayList<String>(buf));
    }

    @Test
    public void testClaimBatchNeverReturnsNull() {
        assertNotNull(TestJob.claimBatch(new ArrayDeque<String>(), 0, s -> true));
    }

    @Test
    public void testClaimBatchOverloadPopsFifoWithoutGate() {
        Deque<String> buf = new ArrayDeque<String>(Arrays.asList("a", "b", "c"));
        assertEquals(Arrays.asList("a", "b"), TestJob.claimBatch(buf, 2));
        assertEquals(Collections.singletonList("c"), new ArrayList<String>(buf));
        assertTrue(TestJob.claimBatch(new ArrayDeque<String>(), 4).isEmpty());
    }

    // ---------------- pool test-slot claims ----------------

    @Test
    public void testClaimPoolTestSlotCountsSuccessiveClaims() {
        String poolId = "client-slotcount-inbound";
        assertEquals(1, TestJob.claimPoolTestSlot(poolId));
        assertEquals(2, TestJob.claimPoolTestSlot(poolId));
    }

    @Test
    public void testReleasePoolTestSlotDecrementsClaim() {
        String poolId = "client-slotrelease-inbound";
        assertEquals(1, TestJob.claimPoolTestSlot(poolId));
        assertEquals(2, TestJob.claimPoolTestSlot(poolId));
        TestJob.releasePoolTestSlot(poolId);
        assertEquals(2, TestJob.claimPoolTestSlot(poolId));
    }

    @Test
    public void testReleasePoolTestSlotWithoutClaimIsNoOp() {
        String poolId = "client-slotsolo-inbound";
        TestJob.releasePoolTestSlot(poolId);
        TestJob.releasePoolTestSlot(poolId);
        assertEquals(1, TestJob.claimPoolTestSlot(poolId));
    }

    @Test
    public void testReleasePoolTestSlotAfterFullReleaseIsNoOp() {
        String poolId = "client-slotzero-inbound";
        assertEquals(1, TestJob.claimPoolTestSlot(poolId));
        TestJob.releasePoolTestSlot(poolId);
        // A second release must not drive the counter negative: the next
        // claim on a clean pool starts at 1 again.
        TestJob.releasePoolTestSlot(poolId);
        assertEquals(1, TestJob.claimPoolTestSlot(poolId));
    }

    // ---------------- offerBounded ----------------

    @Test
    public void testOfferBoundedNoEvictionUnderBound() {
        Deque<String> buf = new ArrayDeque<String>();
        assertTrue(TestJob.offerBounded(buf, "a", 3).isEmpty());
        assertTrue(TestJob.offerBounded(buf, "b", 3).isEmpty());
        assertEquals(2, buf.size());
    }

    @Test
    public void testOfferBoundedEvictsOldestAtBound() {
        Deque<String> buf = new ArrayDeque<String>(Arrays.asList("x", "y"));
        List<String> evicted = TestJob.offerBounded(buf, "z", 2);
        assertEquals(Collections.singletonList("x"), evicted);
        assertEquals(Arrays.asList("y", "z"), new ArrayList<String>(buf));
    }

    @Test
    public void testOfferBoundedSingleSlotAlwaysEvicts() {
        Deque<String> buf = new ArrayDeque<String>(Collections.singletonList("old"));
        List<String> evicted = TestJob.offerBounded(buf, "new", 1);
        assertEquals(Collections.singletonList("old"), evicted);
        assertEquals(Collections.singletonList("new"), new ArrayList<String>(buf));
    }

    @Test
    public void testOfferBoundedUnboundedWhenBoundNonPositive() {
        Deque<String> buf = new ArrayDeque<String>();
        assertTrue(TestJob.offerBounded(buf, "a", 0).isEmpty());
        assertTrue(TestJob.offerBounded(buf, "b", -1).isEmpty());
        assertEquals(2, buf.size());
    }

    // ---------------- pumpDelayMs ----------------

    @Test
    public void testPumpDelayHealthyWhileQuiet() {
        assertEquals(TestJob.PUMP_DELAY_HEALTHY_MS, TestJob.pumpDelayMs(0));
        assertEquals(TestJob.PUMP_DELAY_HEALTHY_MS,
                     TestJob.pumpDelayMs(TestJob.PUMP_BUSY_LAG_MS));
    }

    @Test
    public void testPumpDelayBusyAboveLimit() {
        assertEquals(TestJob.PUMP_DELAY_BUSY_MS,
                     TestJob.pumpDelayMs(TestJob.PUMP_BUSY_LAG_MS + 1));
        assertEquals(TestJob.PUMP_DELAY_BUSY_MS,
                     TestJob.pumpDelayMs(TestJob.PUMP_LAGGED_LAG_MS));
    }

    @Test
    public void testPumpDelayLaggedWhenBadlyLagging() {
        assertEquals(TestJob.PUMP_DELAY_LAGGED_MS,
                     TestJob.pumpDelayMs(TestJob.PUMP_LAGGED_LAG_MS + 1));
        assertEquals(TestJob.PUMP_DELAY_LAGGED_MS, TestJob.pumpDelayMs(600_000L));
    }

    // ---------------- batchDenialReason (pure gates) ----------------

    @Test
    public void testBatchAdmittedWhenAllGatesOpen() {
        assertNull(TestJob.batchDenialReason(0, 64, 0, 4, 0, 512));
        // one below each threshold is still admitted
        assertNull(TestJob.batchDenialReason(63, 64, 3, 4, 511, 512));
    }

    @Test
    public void testBatchDeniedAtInFlightCap() {
        String reason = TestJob.batchDenialReason(64, 64, 0, 4, 0, 512);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("in-flight cap"));
    }

    @Test
    public void testBatchDeniedAtPoolBudget() {
        String reason = TestJob.batchDenialReason(1, 64, 4, 4, 1, 512);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("pool budget"));
    }

    @Test
    public void testBatchPoolBudgetSkippedWhenNegative() {
        // -1 marks an ungated pool (collapsed client pool, exploratory budget bypass)
        assertNull(TestJob.batchDenialReason(0, 64, 99, -1, 0, 512));
    }

    @Test
    public void testBatchDeniedAtHardLimit() {
        String reason = TestJob.batchDenialReason(0, 64, 0, 4, 512, 512);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("hard limit"));
    }

    @Test
    public void testInFlightCapCheckedBeforeOtherGates() {
        String reason = TestJob.batchDenialReason(64, 64, 4, 4, 512, 512);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("in-flight cap"));
    }

    @Test
    public void testPoolBudgetCheckedBeforeHardLimit() {
        String reason = TestJob.batchDenialReason(1, 64, 4, 4, 512, 512);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("pool budget"));
    }

    // ---------------- shouldDropPending ----------------

    private static RouterContext ctxAt(long now) {
        RouterContext ctx = mock(RouterContext.class);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(now);
        when(ctx.clock()).thenReturn(clock);
        return ctx;
    }

    private static TunnelPool livePool(String nickname) {
        TunnelPool pool = mock(TunnelPool.class);
        TunnelPoolSettings settings = mock(TunnelPoolSettings.class);
        when(settings.getDestinationNickname()).thenReturn(nickname);
        when(pool.getSettings()).thenReturn(settings);
        when(pool.isAlive()).thenReturn(true);
        return pool;
    }

    private static PooledTunnelCreatorConfig pendingCfg(TunnelPool pool, long expiration) {
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.getTunnelPool()).thenReturn(pool);
        when(cfg.isInbound()).thenReturn(true);
        TunnelId tid = mock(TunnelId.class);
        when(tid.getTunnelId()).thenReturn(42L);
        when(cfg.getReceiveTunnelId(0)).thenReturn(tid);
        when(cfg.getExpiration()).thenReturn(expiration);
        when(cfg.getTestStatus()).thenReturn(TunnelTestStatus.UNTESTED);
        return cfg;
    }

    @Test
    public void testNullCfgDropped() {
        assertTrue(TestJob.shouldDropPending(ctxAt(NOW), null, livePool("dest")));
    }

    @Test
    public void testHealthyPendingKept() {
        assertFalse(TestJob.shouldDropPending(ctxAt(NOW),
                pendingCfg(livePool("dest"), NOW + 10 * 60_000L), null));
    }

    @Test
    public void testMissingPoolDropped() {
        assertTrue(TestJob.shouldDropPending(ctxAt(NOW),
                pendingCfg(null, NOW + 10 * 60_000L), null));
    }

    @Test
    public void testDeadPoolDropped() {
        TunnelPool pool = livePool("dest");
        when(pool.isAlive()).thenReturn(false);
        assertTrue(TestJob.shouldDropPending(ctxAt(NOW),
                pendingCfg(pool, NOW + 10 * 60_000L), pool));
    }

    @Test
    public void testMissingIdsDropped() {
        PooledTunnelCreatorConfig cfg = pendingCfg(livePool("dest"), NOW + 10 * 60_000L);
        when(cfg.getReceiveTunnelId(0)).thenReturn(null);
        assertTrue(TestJob.shouldDropPending(ctxAt(NOW), cfg, null));
    }

    @Test
    public void testPingTunnelDropped() {
        assertTrue(TestJob.shouldDropPending(ctxAt(NOW),
                pendingCfg(livePool("I2Ping"), NOW + 10 * 60_000L), null));
    }

    @Test
    public void testEarlyExpiryDropped() {
        // expires now: inside the prune window
        assertTrue(TestJob.shouldDropPending(ctxAt(NOW),
                pendingCfg(livePool("dest"), NOW - 1), null));
    }

    @Test
    public void testNonUntestedStatusDropped() {
        PooledTunnelCreatorConfig cfg = pendingCfg(livePool("dest"), NOW + 10 * 60_000L);
        when(cfg.getTestStatus()).thenReturn(TunnelTestStatus.GOOD);
        assertTrue(TestJob.shouldDropPending(ctxAt(NOW), cfg, null));
    }

    // ---------------- shouldDeferActiveGoodTunnel ----------------

    @Test
    public void testActiveGoodDeferredWhileBusy() {
        // traffic 30s ago, in-flight at the cap -> wait for the ebb
        assertTrue(TestJob.shouldDeferActiveGoodTunnel(NOW - 30_000L, NOW,
                TunnelTestStatus.GOOD, 64, 64));
        // at the ebb line (1/4 of the cap) the defer still holds
        assertTrue(TestJob.shouldDeferActiveGoodTunnel(NOW - 30_000L, NOW,
                TunnelTestStatus.GOOD, 16, 64));
    }

    @Test
    public void testActiveGoodTestedDuringEbb() {
        // free test slots -> run now, refresh the latency sample
        assertFalse(TestJob.shouldDeferActiveGoodTunnel(NOW - 30_000L, NOW,
                TunnelTestStatus.GOOD, 15, 64));
        assertFalse(TestJob.shouldDeferActiveGoodTunnel(NOW - 30_000L, NOW,
                TunnelTestStatus.GOOD, 0, 64));
    }

    @Test
    public void testNoDeferWithoutRecentTraffic() {
        assertFalse(TestJob.shouldDeferActiveGoodTunnel(0, NOW,
                TunnelTestStatus.GOOD, 64, 64));
        assertFalse(TestJob.shouldDeferActiveGoodTunnel(-1, NOW,
                TunnelTestStatus.GOOD, 64, 64));
        // traffic exactly at the defer window is no longer "recent"
        assertFalse(TestJob.shouldDeferActiveGoodTunnel(NOW - TestJob.TRAFFIC_DEFER_MS, NOW,
                TunnelTestStatus.GOOD, 64, 64));
        assertFalse(TestJob.shouldDeferActiveGoodTunnel(NOW - TestJob.TRAFFIC_DEFER_MS - 1, NOW,
                TunnelTestStatus.GOOD, 64, 64));
    }

    @Test
    public void testNoDeferForNonGoodStatus() {
        // UNTESTED must never be traffic-deferred; FAILING is tested promptly
        assertFalse(TestJob.shouldDeferActiveGoodTunnel(NOW - 30_000L, NOW,
                TunnelTestStatus.UNTESTED, 64, 64));
        assertFalse(TestJob.shouldDeferActiveGoodTunnel(NOW - 30_000L, NOW,
                TunnelTestStatus.FAILING, 64, 64));
        assertFalse(TestJob.shouldDeferActiveGoodTunnel(NOW - 30_000L, NOW,
                TunnelTestStatus.TESTING, 64, 64));
    }

    @Test
    public void testEbbScalesWithCap() {
        // slow router: cap 32 -> ebb line at 8
        assertTrue(TestJob.shouldDeferActiveGoodTunnel(NOW - 30_000L, NOW,
                TunnelTestStatus.GOOD, 8, 32));
        assertFalse(TestJob.shouldDeferActiveGoodTunnel(NOW - 30_000L, NOW,
                TunnelTestStatus.GOOD, 7, 32));
    }

    @Test
    public void testZeroCapTreatedAsBusy() {
        // no capacity information -> keep the conservative defer
        assertTrue(TestJob.shouldDeferActiveGoodTunnel(NOW - 30_000L, NOW,
                TunnelTestStatus.GOOD, 0, 0));
    }
}
