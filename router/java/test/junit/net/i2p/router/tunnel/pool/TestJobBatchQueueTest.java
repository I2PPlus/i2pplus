package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelTestStatus;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;

import org.junit.Test;

/**
 * Unit tests for the pure helpers behind the batched first-test dispatch:
 * buffer claiming ({@link TestJob#claimBatch}), bounded offering
 * ({@link TestJob#offerBounded}), queue-health pacing
 * ({@link TestJob#pumpDelayMs}), the in-flight admission gates
 * ({@link TestJob#batchDenialReason}), the drain-time staleness
 * predicate ({@link TestJob#shouldDropPending}), the per-pool
 * claim/release pairing ({@link TestJob#claimPoolTestSlot} and
 * {@link TestJob.InstanceClaims}), the batch denial scope
 * ({@link TestJob#isPoolScopedDenial}), and the
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
    // Claims are scoped to one router context's BatchState, so each test gets
    // its own state instead of sharing a process-wide counter.

    private static TestJob.BatchState newState() {
        return new TestJob.BatchState();
    }

    @Test
    public void testClaimPoolTestSlotCountsSuccessiveClaims() {
        TestJob.BatchState state = newState();
        String poolId = "client-slotcount-inbound";
        assertEquals(1, TestJob.claimPoolTestSlot(state, poolId));
        assertEquals(2, TestJob.claimPoolTestSlot(state, poolId));
    }

    @Test
    public void testReleasePoolTestSlotDecrementsClaim() {
        TestJob.BatchState state = newState();
        String poolId = "client-slotrelease-inbound";
        assertEquals(1, TestJob.claimPoolTestSlot(state, poolId));
        assertEquals(2, TestJob.claimPoolTestSlot(state, poolId));
        TestJob.releasePoolTestSlot(state, poolId);
        assertEquals(2, TestJob.claimPoolTestSlot(state, poolId));
    }

    @Test
    public void testReleasePoolTestSlotWithoutClaimIsNoOp() {
        TestJob.BatchState state = newState();
        String poolId = "client-slotsolo-inbound";
        TestJob.releasePoolTestSlot(state, poolId);
        TestJob.releasePoolTestSlot(state, poolId);
        assertEquals(1, TestJob.claimPoolTestSlot(state, poolId));
    }

    @Test
    public void testReleasePoolTestSlotAfterFullReleaseIsNoOp() {
        TestJob.BatchState state = newState();
        String poolId = "client-slotzero-inbound";
        assertEquals(1, TestJob.claimPoolTestSlot(state, poolId));
        TestJob.releasePoolTestSlot(state, poolId);
        // A second release must not drive the counter negative: the next
        // claim on a clean pool starts at 1 again.
        TestJob.releasePoolTestSlot(state, poolId);
        assertEquals(1, TestJob.claimPoolTestSlot(state, poolId));
    }

    @Test
    public void testPoolTestSlotsAreScopedToTheirState() {
        TestJob.BatchState a = newState();
        TestJob.BatchState b = newState();
        String poolId = "client-scoped-inbound";
        assertEquals(1, TestJob.claimPoolTestSlot(a, poolId));
        // The same pool id in another context starts from zero: one router's
        // claims can never be observed by another.
        assertEquals(1, TestJob.claimPoolTestSlot(b, poolId));
        TestJob.releasePoolTestSlot(a, poolId);
        // Releasing on a leaves b untouched: b still holds its own claim.
        assertEquals(1, b.poolTestCounts.get(poolId).get());
        TestJob.releasePoolTestSlot(b, poolId);
        assertNull("released counter must be unlinked", b.poolTestCounts.get(poolId));
        assertNull("the other context's counter was unlinked by its own release",
                   a.poolTestCounts.get(poolId));
    }

    // ---------------- InstanceClaims (instance slot pairing) ----------------

    @Test
    public void testInstanceClaimsReleaseTotalOnce() {
        TestJob.BatchState state = newState();
        state.totalTestJobs.set(3);
        TestJob.InstanceClaims claims = new TestJob.InstanceClaims("client-claims-inbound");

        claims.releaseTotal(state);
        claims.releaseTotal(state);

        assertEquals(2, state.totalTestJobs.get());
    }

    @Test
    public void testInstanceClaimsReleaseCapturedPoolIdOnce() {
        TestJob.BatchState state = newState();
        String poolId = "client-claims-pool-inbound";
        assertEquals(1, TestJob.claimPoolTestSlot(state, poolId));
        TestJob.InstanceClaims claims = new TestJob.InstanceClaims(poolId);

        claims.releasePool(state);
        claims.releasePool(state);

        assertNull("the single release unlinks the counter",
                   state.poolTestCounts.get(poolId));
        assertEquals("the next claim starts from one again, never below zero",
                     1, TestJob.claimPoolTestSlot(state, poolId));
    }

    @Test
    public void testInstanceClaimsWithoutPoolNeverTouchPoolGauge() {
        TestJob.BatchState state = newState();
        state.totalTestJobs.set(1);
        TestJob.InstanceClaims claims = new TestJob.InstanceClaims(null);

        assertFalse(claims.poolHeld.get());
        claims.releasePool(state);
        claims.releasePool(state);
        assertTrue(state.poolTestCounts.isEmpty());

        claims.releaseTotal(state);
        claims.releaseTotal(state);
        assertEquals(0, state.totalTestJobs.get());
    }

    @Test
    public void testInstanceClaimsReleaseAllClearsOwnRegistrationOnce() {
        TestJob.BatchState state = newState();
        TestJob instance = mock(TestJob.class);
        state.runningTests.put(Long.valueOf(7), instance);
        state.totalTestJobs.set(1);
        TestJob.InstanceClaims claims = new TestJob.InstanceClaims(null);
        claims.runningHeld.set(true);

        claims.releaseAll(state, instance, Long.valueOf(7));
        claims.releaseAll(state, instance, Long.valueOf(7));

        assertFalse(state.runningTests.containsKey(Long.valueOf(7)));
        assertEquals(0, state.totalTestJobs.get());
    }

    @Test
    public void testReleaseAllNeverEvictsAnotherInstancesRegistration() {
        TestJob.BatchState state = newState();
        TestJob foreign = mock(TestJob.class);
        state.runningTests.put(Long.valueOf(8), foreign);
        TestJob.InstanceClaims claims = new TestJob.InstanceClaims(null);
        claims.runningHeld.set(true);

        claims.releaseAll(state, mock(TestJob.class), Long.valueOf(8));

        assertTrue("only the registering instance may remove the entry",
                   state.runningTests.containsKey(Long.valueOf(8)));
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

    // ---------------- isPoolScopedDenial (rotation scope) ----------------

    @Test
    public void testPoolBudgetDenialIsPoolScoped() {
        assertTrue(TestJob.isPoolScopedDenial(TestJob.batchDenialReason(1, 64, 4, 4, 0, 512)));
        assertTrue(TestJob.isPoolScopedDenial("pool budget (4/4)"));
    }

    @Test
    public void testGlobalDenialsAreNotPoolScoped() {
        assertFalse(TestJob.isPoolScopedDenial(TestJob.batchDenialReason(64, 64, 0, 4, 0, 512)));
        assertFalse(TestJob.isPoolScopedDenial(TestJob.batchDenialReason(0, 64, 0, 4, 512, 512)));
        assertFalse("an admitted batch is not a denial at all",
                    TestJob.isPoolScopedDenial(TestJob.batchDenialReason(0, 64, 0, 4, 0, 512)));
        assertFalse(TestJob.isPoolScopedDenial(null));
        assertFalse(TestJob.isPoolScopedDenial("no config"));
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
        return pendingCfgWithId(pool, expiration, 42L);
    }

    /**
     * A candidate with a caller-chosen tunnel id, so several distinct
     * candidates can share one fixture style without colliding on the
     * running-test key.
     */
    private static PooledTunnelCreatorConfig pendingCfgWithId(TunnelPool pool, long expiration,
                                                               long tunnelId) {
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.getTunnelPool()).thenReturn(pool);
        when(cfg.isInbound()).thenReturn(true);
        TunnelId tid = mock(TunnelId.class);
        when(tid.getTunnelId()).thenReturn(tunnelId);
        when(cfg.getReceiveTunnelId(0)).thenReturn(tid);
        when(cfg.getExpiration()).thenReturn(expiration);
        when(cfg.getTestStatus()).thenReturn(TunnelTestStatus.UNTESTED);
        // Membership: a live pool owns the config it is offering, so the
        // drain-time listTunnels() check keeps it (see TestJob.isPoolMember).
        if (pool != null) {
            when(pool.listTunnels()).thenReturn(Collections.<TunnelInfo>singletonList(cfg));
        }
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

    // ---------------- getPoolId (stable pool identity) ----------------

    private static TunnelPool poolIdentity(boolean exploratory, boolean inbound, Hash dest) {
        TunnelPool pool = mock(TunnelPool.class);
        TunnelPoolSettings s = mock(TunnelPoolSettings.class);
        when(s.isExploratory()).thenReturn(exploratory);
        when(s.isInbound()).thenReturn(inbound);
        when(s.getDestination()).thenReturn(dest);
        when(pool.getSettings()).thenReturn(s);
        return pool;
    }

    @Test
    public void testNullPoolIdIsUnknown() {
        assertEquals("unknown", TestJob.getPoolId(null));
    }

    @Test
    public void testExploratoryPoolIdIsDirectionScoped() {
        assertEquals("exploratory-inbound", TestJob.getPoolId(poolIdentity(true, true, null)));
        assertEquals("exploratory-outbound", TestJob.getPoolId(poolIdentity(true, false, null)));
    }

    @Test
    public void testClientPoolIdUsesFullDestinationHash() {
        Hash dest = new Hash(new byte[Hash.HASH_LENGTH]);
        assertEquals("client-" + dest.toBase64() + "-inbound",
                     TestJob.getPoolId(poolIdentity(false, true, dest)));
        assertEquals("client-" + dest.toBase64() + "-outbound",
                     TestJob.getPoolId(poolIdentity(false, false, dest)));
    }

    @Test
    public void testClientPoolWithoutDestinationNeverCollidesWithExploratory() {
        String client = TestJob.getPoolId(poolIdentity(false, false, null));
        assertEquals("client-nodest-outbound", client);
        assertFalse(client.equals(TestJob.getPoolId(poolIdentity(true, false, null))));
    }

    // ---------------- isPoolMember / shouldRejectAtOffer (admission) ----------------

    @Test
    public void testPoolMemberWhenPoolListsConfig() {
        TunnelPool pool = livePool("dest");
        PooledTunnelCreatorConfig cfg = pendingCfg(pool, NOW + 10 * 60_000L);
        assertTrue(TestJob.isPoolMember(pool, cfg));
    }

    @Test
    public void testNotPoolMemberWhenPoolDoesNotListConfig() {
        TunnelPool pool = livePool("dest");
        PooledTunnelCreatorConfig cfg = pendingCfg(pool, NOW + 10 * 60_000L);
        when(pool.listTunnels()).thenReturn(Collections.<TunnelInfo>emptyList());
        assertFalse(TestJob.isPoolMember(pool, cfg));
        assertFalse(TestJob.isPoolMember(null, cfg));
        assertFalse(TestJob.isPoolMember(pool, null));
    }

    @Test
    public void testHealthyCandidateAdmitted() {
        PooledTunnelCreatorConfig cfg = pendingCfg(livePool("dest"), NOW + 10 * 60_000L);
        assertFalse(TestJob.shouldRejectAtOffer(ctxAt(NOW), cfg, cfg.getTunnelPool()));
    }

    @Test
    public void testLastChanceCandidateInsideEarlyExpiryWindowStillAdmitted() {
        // the early-expiry window is drain-time only: last-chance offers are
        // made inside it on purpose
        PooledTunnelCreatorConfig cfg = pendingCfg(livePool("dest"), NOW + 30_000L);
        assertFalse(TestJob.shouldRejectAtOffer(ctxAt(NOW), cfg, cfg.getTunnelPool()));
    }

    @Test
    public void testExpiredCandidateRejectedAtOffer() {
        PooledTunnelCreatorConfig cfg = pendingCfg(livePool("dest"), NOW - 1);
        assertTrue(TestJob.shouldRejectAtOffer(ctxAt(NOW), cfg, cfg.getTunnelPool()));
    }

    @Test
    public void testNoPoolRejectedAtOffer() {
        PooledTunnelCreatorConfig cfg = pendingCfg(null, NOW + 10 * 60_000L);
        assertTrue(TestJob.shouldRejectAtOffer(ctxAt(NOW), cfg, null));
    }

    @Test
    public void testUnbuiltCandidateRejectedAtOffer() {
        PooledTunnelCreatorConfig cfg = pendingCfg(livePool("dest"), NOW + 10 * 60_000L);
        when(cfg.getReceiveTunnelId(0)).thenReturn(null);
        assertTrue(TestJob.shouldRejectAtOffer(ctxAt(NOW), cfg, cfg.getTunnelPool()));
    }

    @Test
    public void testAlreadyRunningCandidateRejectedAtOffer() {
        TunnelPool pool = livePool("dest");
        PooledTunnelCreatorConfig cfg = pendingCfg(pool, NOW + 10 * 60_000L);
        RouterContext ctx = ctxAt(NOW);
        TestJob.BatchState state = TestJob.batchState(ctx);
        state.runningTests.put(42L, mock(TestJob.class));
        assertTrue(TestJob.shouldRejectAtOffer(ctx, cfg, pool));
        state.runningTests.remove(42L);
    }

    @Test
    public void testForeignCandidateRejectedAtOffer() {
        // the build was never accepted by its pool: it cannot evict a valid
        // candidate from the bounded buffer
        TunnelPool pool = livePool("dest");
        PooledTunnelCreatorConfig cfg = pendingCfg(pool, NOW + 10 * 60_000L);
        when(pool.listTunnels()).thenReturn(Collections.<TunnelInfo>emptyList());
        assertTrue(TestJob.shouldRejectAtOffer(ctxAt(NOW), cfg, pool));
    }

    // ---------------- rebufferBounded / rebufferAll (row: buffer bound) ----------------

    @Test
    public void testRebufferBoundedRestoresOrderAtHead() {
        Deque<String> buf = new ArrayDeque<String>(Collections.singletonList("keep"));
        List<String> returned = Arrays.asList("a", "b");
        assertTrue(TestJob.rebufferBounded(buf, returned, 8).isEmpty());
        assertEquals(Arrays.asList("a", "b", "keep"), new ArrayList<String>(buf));
    }

    @Test
    public void testRebufferBoundedEvictsTailWhenOverBound() {
        Deque<String> buf = new ArrayDeque<String>(Arrays.asList("a", "b"));
        List<String> returned = Arrays.asList("x", "y");
        // bound 3 -> [x, y, a, b] overflows by one, oldest tail goes
        List<String> evicted = TestJob.rebufferBounded(buf, returned, 3);
        assertEquals(Collections.singletonList("b"), evicted);
        assertEquals(Arrays.asList("x", "y", "a"), new ArrayList<String>(buf));
    }

    @Test
    public void testRebufferBoundedAtBoundEvictsOldestTail() {
        Deque<String> buf = new ArrayDeque<String>(Arrays.asList("a", "b", "c"));
        List<String> evicted = TestJob.rebufferBounded(buf, Collections.singletonList("z"), 3);
        assertEquals(Collections.singletonList("c"), evicted);
        assertEquals(Arrays.asList("z", "a", "b"), new ArrayList<String>(buf));
    }

    @Test
    public void testRebufferBoundedEmptyPendingIsNoOp() {
        Deque<String> buf = new ArrayDeque<String>(Collections.singletonList("a"));
        assertTrue(TestJob.rebufferBounded(buf, Collections.<String>emptyList(), 1).isEmpty());
        assertEquals(1, buf.size());
    }

    private static List<TestJob.PendingTest> fillBuffer(TestJob.BatchState state, int count) {
        List<TestJob.PendingTest> out = new ArrayList<TestJob.PendingTest>(count);
        synchronized (state.bufferLock) {
            for (int i = 0; i < count; i++) {
                TestJob.PendingTest p = new TestJob.PendingTest(
                        pendingCfgWithId(livePool("dest"), NOW + 10 * 60_000L, 1000 + i), null);
                state.firstTestBuffer.addLast(p);
                if (p.key != null) {
                    state.bufferedKeys.add(p.key);
                }
                out.add(p);
            }
        }
        return out;
    }

    @Test
    public void testRebufferAllIsBoundedAndUnlinksEvictedKeys() {
        TestJob.BatchState state = new TestJob.BatchState();
        RouterContext ctx = ctxAt(NOW);
        when(ctx.statManager()).thenReturn(mock(StatManager.class));
        fillBuffer(state, TestJob.MAX_BUFFERED_FIRST_TESTS);

        TestJob.PendingTest extra = new TestJob.PendingTest(
                pendingCfgWithId(livePool("dest"), NOW + 10 * 60_000L, 7777), null);
        TestJob.rebufferAll(state, ctx, Collections.singletonList(extra));

        synchronized (state.bufferLock) {
            assertEquals(TestJob.MAX_BUFFERED_FIRST_TESTS, state.firstTestBuffer.size());
            // tail eviction drops the newest filled candidate (id 1127), not
            // the returned one, which is back at the head
            assertFalse("evicted key must be unlinked",
                        state.bufferedKeys.contains(Long.valueOf(1127)));
            assertTrue(state.bufferedKeys.contains(Long.valueOf(1000)));
            assertTrue(state.bufferedKeys.contains(extra.key));
            assertEquals(extra.key, state.firstTestBuffer.peekFirst().key);
        }
    }

    @Test
    public void testRebufferAllDedupesCandidateAlreadyBuffered() {
        TestJob.BatchState state = new TestJob.BatchState();
        RouterContext ctx = ctxAt(NOW);
        when(ctx.statManager()).thenReturn(mock(StatManager.class));
        List<TestJob.PendingTest> buffered = fillBuffer(state, 1);

        TestJob.rebufferAll(state, ctx, buffered);

        synchronized (state.bufferLock) {
            assertEquals("duplicate must not be buffered twice", 1, state.firstTestBuffer.size());
            assertEquals(1, state.bufferedKeys.size());
        }
    }

    @Test
    public void testRebufferAllEmptyIsNoOp() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.rebufferAll(state, ctxAt(NOW), Collections.<TestJob.PendingTest>emptyList());
        synchronized (state.bufferLock) {
            assertTrue(state.firstTestBuffer.isEmpty());
            assertTrue(state.bufferedKeys.isEmpty());
        }
    }

    @Test
    public void testRebufferOneAppendsAtTail() {
        TestJob.BatchState state = new TestJob.BatchState();
        List<TestJob.PendingTest> buffered = fillBuffer(state, 1);
        TestJob.PendingTest rotated = new TestJob.PendingTest(
                pendingCfgWithId(livePool("dest"), NOW + 10 * 60_000L, 5555), null);

        TestJob.rebufferOne(state, rotated);

        synchronized (state.bufferLock) {
            assertEquals(2, state.firstTestBuffer.size());
            assertEquals(rotated.key, state.firstTestBuffer.peekLast().key);
            assertEquals(buffered.get(0).key, state.firstTestBuffer.peekFirst().key);
        }
    }

    @Test
    public void testRebufferOneRefusedWhenBufferFull() {
        TestJob.BatchState state = new TestJob.BatchState();
        fillBuffer(state, TestJob.MAX_BUFFERED_FIRST_TESTS);
        TestJob.PendingTest refused = new TestJob.PendingTest(
                pendingCfgWithId(livePool("dest"), NOW + 10 * 60_000L, 8888), null);

        TestJob.rebufferOne(state, refused);

        synchronized (state.bufferLock) {
            assertEquals(TestJob.MAX_BUFFERED_FIRST_TESTS, state.firstTestBuffer.size());
            assertFalse(state.bufferedKeys.contains(refused.key));
        }
    }

    @Test
    public void testConcurrentRebufferAndRefillNeverExceedsBound() throws Exception {
        final TestJob.BatchState state = new TestJob.BatchState();
        final RouterContext ctx = ctxAt(NOW);
        when(ctx.statManager()).thenReturn(mock(StatManager.class));
        final int bound = TestJob.MAX_BUFFERED_FIRST_TESTS;
        fillBuffer(state, bound);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final long baseId = 50_000L + t * 10_000L;
            threads[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        // a drain pops a batch, then a concurrent offer
                        // refills the buffer while the batch is out
                        List<TestJob.PendingTest> popped;
                        synchronized (state.bufferLock) {
                            popped = TestJob.claimBatch(state.firstTestBuffer, 8);
                            for (TestJob.PendingTest p : popped) {
                                if (p.key != null) {
                                    state.bufferedKeys.remove(p.key);
                                }
                            }
                        }
                        if (!popped.isEmpty()) {
                            TestJob.rebufferAll(state, ctx, popped);
                        }
                        TestJob.PendingTest fresh = new TestJob.PendingTest(
                                pendingCfgWithId(livePool("dest"), NOW + 600_000L, baseId + i),
                                null);
                        synchronized (state.bufferLock) {
                            if (state.bufferedKeys.add(fresh.key)) {
                                for (TestJob.PendingTest old :
                                        TestJob.offerBounded(state.firstTestBuffer, fresh, bound)) {
                                    if (old.key != null) {
                                        state.bufferedKeys.remove(old.key);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable th) {
                    failure.set(th);
                }
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertNull("concurrent drain/rebuffer/offer must not throw", failure.get());
        synchronized (state.bufferLock) {
            assertTrue("the buffer must stay within its bound, was "
                       + state.firstTestBuffer.size(),
                       state.firstTestBuffer.size() <= bound);
            assertEquals("bufferedKeys must mirror the buffer exactly",
                         state.firstTestBuffer.size(), state.bufferedKeys.size());
            for (TestJob.PendingTest p : state.firstTestBuffer) {
                assertTrue(state.bufferedKeys.contains(p.key));
            }
        }
    }

    // ---------------- prioritizeBatch (last chance first) ----------------

    private static TestJob.PendingTest pendingWithLife(long expiration, boolean admitted,
                                                        long tunnelId) {
        PooledTunnelCreatorConfig cfg = pendingCfgWithId(livePool("dest"), expiration, tunnelId);
        when(cfg.isLastChanceAdmitted(NOW)).thenReturn(admitted);
        return new TestJob.PendingTest(cfg, null);
    }

    @Test
    public void testLastChanceCandidatesGoFirstOrderedByRemainingLife() {
        TestJob.PendingTest normal = pendingWithLife(NOW + 10 * 60_000L, false, 11);
        TestJob.PendingTest late = pendingWithLife(NOW + 5 * 60_000L, true, 12);
        TestJob.PendingTest soon = pendingWithLife(NOW + 60_000L, true, 13);

        List<TestJob.PendingTest> ordered =
                TestJob.prioritizeBatch(Arrays.asList(normal, late, soon), NOW);

        assertEquals(3, ordered.size());
        assertEquals(soon.key, ordered.get(0).key);
        assertEquals(late.key, ordered.get(1).key);
        assertEquals(normal.key, ordered.get(2).key);
    }

    @Test
    public void testNoLastChanceKeepsScanOrder() {
        TestJob.PendingTest a = pendingWithLife(NOW + 10 * 60_000L, false, 21);
        TestJob.PendingTest b = pendingWithLife(NOW + 11 * 60_000L, false, 22);
        List<TestJob.PendingTest> ordered = TestJob.prioritizeBatch(Arrays.asList(a, b), NOW);
        assertEquals(a.key, ordered.get(0).key);
        assertEquals(b.key, ordered.get(1).key);
    }

    @Test
    public void testPrioritizeBatchSkipsNullsAndNeverReturnsNull() {
        TestJob.PendingTest a = pendingWithLife(NOW + 10 * 60_000L, false, 31);
        List<TestJob.PendingTest> ordered =
                TestJob.prioritizeBatch(Arrays.asList(null, a, null), NOW);
        assertEquals(1, ordered.size());
        assertEquals(a.key, ordered.get(0).key);
        assertNotNull(TestJob.prioritizeBatch(Collections.<TestJob.PendingTest>emptyList(), NOW));
    }

    // ---------------- saturatingDecrement ----------------

    @Test
    public void testSaturatingDecrementCountsDownToZero() {
        AtomicInteger gauge = new AtomicInteger(2);
        TestJob.saturatingDecrement(gauge);
        assertEquals(1, gauge.get());
        TestJob.saturatingDecrement(gauge);
        assertEquals(0, gauge.get());
    }

    @Test
    public void testSaturatingDecrementNeverGoesNegative() {
        AtomicInteger gauge = new AtomicInteger(0);
        TestJob.saturatingDecrement(gauge);
        TestJob.saturatingDecrement(gauge);
        assertEquals(0, gauge.get());
    }

    // ---------------- registerBatchStats ----------------

    @Test
    public void testRegisterBatchStatsCreatesAllFiveRates() {
        StatManager stats = mock(StatManager.class);
        long[] periods = RateConstants.SHORT_TERM_RATES;

        TestJob.registerBatchStats(stats, periods);

        verify(stats).createRequiredRateStat(eq("tunnel.testBufferOffered"),
                anyString(), eq("Tunnels"), same(periods));
        verify(stats).createRequiredRateStat(eq("tunnel.testBufferDropped"),
                anyString(), eq("Tunnels"), same(periods));
        verify(stats).createRequiredRateStat(eq("tunnel.testBufferRejected"),
                anyString(), eq("Tunnels"), same(periods));
        verify(stats).createRequiredRateStat(eq("tunnel.testBatchDenied"),
                anyString(), eq("Tunnels"), same(periods));
        verify(stats).createRequiredRateStat(eq("tunnel.testBatchDispatched"),
                anyString(), eq("Tunnels"), same(periods));
        verifyNoMoreInteractions(stats);
    }
}
