package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.router.RouterContext;

/**
 * Tests for the two TTL caches that sit in front of the per-request throttling
 * work - the load score consulted by every throttled participating request,
 * and the two invariant inputs behind the participation limit - plus the limit
 * arithmetic those caches wrap.
 *
 * <p>Caching must not move any policy value, so the limit arithmetic is pinned
 * here against hand-computed numbers. It was extracted into
 * {@link ParticipatingThrottler#computeLimit} and
 * {@link ParticipatingThrottler#baseLimitFor} so the floors, the slow-platform
 * branch and the capacity-relaxation gate can be exercised without a router.
 *
 * @since 0.9.71+
 */
public class ParticipatingThrottlerCacheTest {

    /**
     * Distinct contexts for the context-invalidation checks. Mocked rather than
     * constructed: a real RouterContext registers itself in a static list that
     * outlives this test.
     */
    private static final RouterContext CTX_A = mock(RouterContext.class);
    private static final RouterContext CTX_B = mock(RouterContext.class);

    /** Configured participating tunnel ceiling the assertions assume. */
    private static final int MAX_TUNNELS = 7500;
    /** Bandwidth usage and system load that switch off both relaxations. */
    private static final double BW_BUSY = 1.0;
    private static final int LOAD_BUSY = 100;

    /**
     * The percentage share alone, with min=80, max=300, pct=10, per platform.
     *
     * <p>Fast: {@code min(3 * 80, max(150, n * 10 / 100))}. Slow:
     * {@code min(80, max(30, n * 10 / 300))}. At n=2000 the share is 200 and 66,
     * at n=0 it falls back to the maxLimit divisor (150 and 30), and at n=30000
     * both are capped by the min limit (240 and 80).
     */
    private static final int SHARE_0 = ParticipatingThrottler.IS_SLOW ? 30 : 150;
    private static final int SHARE_2000 = ParticipatingThrottler.IS_SLOW ? 66 : 200;
    private static final int SHARE_30000 = ParticipatingThrottler.IS_SLOW ? 80 : 240;

    private int _savedMin;
    private int _savedMax;
    private int _savedPct;

    @Before
    public void savePolicy() {
        _savedMin = ParticipatingThrottler._minLimit;
        _savedMax = ParticipatingThrottler._maxLimit;
        _savedPct = ParticipatingThrottler._percentLimit;
    }

    @After
    public void restorePolicy() {
        ParticipatingThrottler._minLimit = _savedMin;
        ParticipatingThrottler._maxLimit = _savedMax;
        ParticipatingThrottler._percentLimit = _savedPct;
    }

    // ---------- load score cache staleness ----------

    @Test
    public void testLoadScoreStaleWhenNeverQueried() {
        // A 0 timestamp is the field initializer, not a real sample. The first
        // call has to compute: serving the 0.0 initializer would leave load
        // based rejection shaping switched off until the clock moved.
        assertTrue(ParticipatingThrottler.loadScoreCacheStale(0, 1_000_000L, CTX_A, CTX_A));
        assertTrue(ParticipatingThrottler.loadScoreCacheStale(0, 0L, null, CTX_A));
    }

    @Test
    public void testLoadScoreFreshWithinInterval() {
        long queried = 1_000_000L;
        assertFalse(ParticipatingThrottler.loadScoreCacheStale(queried, queried, CTX_A, CTX_A));
        assertFalse(ParticipatingThrottler.loadScoreCacheStale(queried, queried + 1, CTX_A, CTX_A));
        assertFalse(ParticipatingThrottler.loadScoreCacheStale(queried, queried + 998, CTX_A, CTX_A));
        // one millisecond short of the 1000ms TTL
        assertFalse(ParticipatingThrottler.loadScoreCacheStale(queried, queried + 999, CTX_A, CTX_A));
    }

    @Test
    public void testLoadScoreStaleAtExactBoundary() {
        // the interval is inclusive: a full second old is already stale
        long queried = 1_000_000L;
        assertTrue(ParticipatingThrottler.loadScoreCacheStale(queried, queried + 1000, CTX_A, CTX_A));
        assertTrue(ParticipatingThrottler.loadScoreCacheStale(queried, queried + 1001, CTX_A, CTX_A));
    }

    @Test
    public void testLoadScoreStaleOnBackwardsWallClock() {
        // NTP correction or a manual clock change. An age-only comparison would
        // call this sample fresh and keep serving it until the clock caught up,
        // pinning a stale score for the length of the step.
        long queried = 1_000_000L;
        assertTrue(ParticipatingThrottler.loadScoreCacheStale(queried, queried - 1, CTX_A, CTX_A));
        assertTrue(ParticipatingThrottler.loadScoreCacheStale(queried, 0L, CTX_A, CTX_A));
    }

    @Test
    public void testLoadScoreStaleOnContextSwap() {
        // unit tests swap contexts, and a router restart replaces it wholesale:
        // a score taken against the old context must never be served to the new
        // one, however young the sample is
        assertNotSame(CTX_A, CTX_B);
        long queried = 1_000_000L;
        assertTrue(ParticipatingThrottler.loadScoreCacheStale(queried, queried + 1, CTX_A, CTX_B));
        assertTrue(ParticipatingThrottler.loadScoreCacheStale(queried, queried + 1, null, CTX_A));
        assertTrue(ParticipatingThrottler.loadScoreCacheStale(queried, queried + 1, CTX_A, null));
    }

    // ---------- limit invariant cache staleness ----------

    @Test
    public void testLimitInvariantCacheFollowsTheSameRules() {
        long queried = 2_000_000L;
        assertTrue(ParticipatingThrottler.limitInvariantCacheStale(0, queried, CTX_A, CTX_A));
        assertFalse(ParticipatingThrottler.limitInvariantCacheStale(queried, queried + 999, CTX_A, CTX_A));
        assertTrue(ParticipatingThrottler.limitInvariantCacheStale(queried, queried + 1000, CTX_A, CTX_A));
        assertTrue(ParticipatingThrottler.limitInvariantCacheStale(queried, queried - 1, CTX_A, CTX_A));
        assertTrue(ParticipatingThrottler.limitInvariantCacheStale(queried, queried, CTX_A, CTX_B));
    }

    // ---------- percentage share ----------

    @Test
    public void testNormalPeersGetThePercentageShare() {
        withPolicy(80, 300, 10);
        assertEquals(SHARE_2000, limit(2000, false, false));
        assertEquals(SHARE_30000, limit(30000, false, false));
    }

    @Test
    public void testZeroTunnelsFallsBackToTheMaxLimitDivisor() {
        // 0 * 10 / anything is 0, so the share bottoms out at the maxLimit
        // divisor (150 on a fast router, 30 on a slow one) rather than at 0
        withPolicy(80, 300, 10);
        assertEquals(SHARE_0, limit(0, false, false));
    }

    @Test
    public void testUnreachableAndLowShareShareTheSameBase() {
        withPolicy(80, 300, 10);
        // 30000 * 10 / 500 = 600, floored at maxLimit / 20 = 15 and capped at
        // minLimit - identical on both platforms, unlike the normal branch
        assertEquals(80, limit(30000, true, false));
        assertEquals(80, limit(30000, false, true));
        assertEquals(80, limit(30000, true, true));
        // 0 * 10 / 500 = 0, floored at 15
        assertEquals(15, limit(0, true, false));
        assertEquals(15, limit(0, false, true));
    }

    // ---------- participation floor ----------

    @Test
    public void testFloorBeatsIntegerDivisionTruncationToZero() {
        // Deliberately below the minimums the constructor and the setters
        // enforce, so the percentage share really does truncate to 0 and the
        // floor is the only thing standing between the caller and a zero limit.
        // A limit of 0 makes evaluateThrottleConditions return ACCEPT
        // unconditionally (limit <= 0), i.e. no throttling at all.
        withPolicy(1, 4, 1);
        for (int numTunnels = 0; numTunnels <= 30; numTunnels++) {
            assertTrue("normal numTunnels=" + numTunnels, limit(numTunnels, false, false) >= 5);
            assertTrue("unreachable numTunnels=" + numTunnels, limit(numTunnels, true, false) >= 2);
            assertTrue("lowShare numTunnels=" + numTunnels, limit(numTunnels, false, true) >= 2);
        }
        // the exact floor, with the share truncated to 0 underneath
        assertEquals(5, limit(0, false, false, BW_BUSY, LOAD_BUSY));
        assertEquals(2, limit(0, true, false, BW_BUSY, LOAD_BUSY));
        assertEquals(2, limit(0, false, true, BW_BUSY, LOAD_BUSY));
    }

    @Test
    public void testFloorHoldsWhenBothRelaxationsAlsoRun() {
        withPolicy(1, 4, 1);
        // spare capacity and an idle CPU can only raise the limit, so the floor
        // assertions above are about the floor and not about the relaxations
        assertEquals(5, limit(0, false, false, 0.0, 0));
        assertEquals(2, limit(0, true, false, 0.0, 0));
    }

    // ---------- capacity relaxation gate ----------

    @Test
    public void testRelaxationNeedsSlotAndBandwidthHeadroom() {
        withPolicy(80, 300, 10);
        // 2000 / 7500 = 0.27 slot usage, under the 0.5 gate
        assertEquals(SHARE_2000, limit(2000, false, false, 0.6, LOAD_BUSY));
        assertTrue(limit(2000, false, false, 0.0, LOAD_BUSY) > SHARE_2000);
    }

    @Test
    public void testRelaxationExcludedAtExactlySixTenthsBandwidth() {
        withPolicy(80, 300, 10);
        // the bandwidth gate is strict, so 0.6 is not "plenty of capacity"
        assertEquals(SHARE_2000, limit(2000, false, false, 0.6, LOAD_BUSY));
        assertEquals(SHARE_2000, limit(2000, false, false, BW_BUSY, LOAD_BUSY));
    }

    @Test
    public void testRelaxationExcludedAtExactlyHalfSlotUsage() {
        withPolicy(80, 300, 10);
        // 3750 / 7500 is exactly 0.5 and the slot gate is strict, so bandwidth
        // usage stops mattering
        int atHalf = limit(3750, false, false, 0.0, LOAD_BUSY);
        assertEquals(atHalf, limit(3750, false, false, BW_BUSY, LOAD_BUSY));
        assertEquals(SHARE_30000, atHalf);
        // and above the halfway mark
        assertEquals(limit(3751, false, false, 0.0, LOAD_BUSY),
                     limit(3751, false, false, BW_BUSY, LOAD_BUSY));
    }

    // ---------- idle CPU bonus ----------

    @Test
    public void testIdleCpuBonusAppliesOnlyBelowThirty() {
        withPolicy(80, 300, 10);
        int atThirty = limit(2000, false, false, 0.9, 30);
        // 30 is already out of the idle band
        assertEquals(SHARE_2000, atThirty);
        assertEquals(atThirty, limit(2000, false, false, 0.9, LOAD_BUSY));
        // a fully idle system earns the whole 25% bonus
        assertTrue(limit(2000, false, false, 0.9, 0) > atThirty);
    }

    // ---------- slow platform branch ----------

    @Test
    public void testSlowBranchClaimsLessThanNormalBranch() {
        int numTunnels = 30000, min = 80, max = 300, pct = 10;
        // 30000 * 10 / 300 = 1000, capped at minLimit
        int slow = ParticipatingThrottler.baseLimitFor(numTunnels, false, false, min, max, pct, true);
        // 30000 * 10 / 100 = 3000, capped at 3 * minLimit
        int fast = ParticipatingThrottler.baseLimitFor(numTunnels, false, false, min, max, pct, false);
        assertEquals(80, slow);
        assertEquals(240, fast);
        assertTrue("a slow platform must not out-claim a fast one", slow < fast);
    }

    @Test
    public void testUnreachableBranchIgnoresTheSlowFlag() {
        int min = 80, max = 300, pct = 10;
        assertEquals(ParticipatingThrottler.baseLimitFor(30000, true, false, min, max, pct, true),
                     ParticipatingThrottler.baseLimitFor(30000, true, false, min, max, pct, false));
        assertEquals(ParticipatingThrottler.baseLimitFor(30000, false, true, min, max, pct, true),
                     ParticipatingThrottler.baseLimitFor(30000, false, true, min, max, pct, false));
    }

    @Test
    public void testShareIsPinnedForBothPlatformsAtEveryAssertedCount() {
        int min = 80, max = 300, pct = 10;
        for (boolean isSlow : new boolean[] { true, false }) {
            // every count the assertions above compare against, so the golden
            // values hold whichever branch this host actually takes
            assertEquals("zero tunnels", isSlow ? 30 : 150,
                         ParticipatingThrottler.baseLimitFor(0, false, false, min, max, pct, isSlow));
            assertEquals("2000 tunnels", isSlow ? 66 : 200,
                         ParticipatingThrottler.baseLimitFor(2000, false, false, min, max, pct, isSlow));
            assertEquals("30000 tunnels", isSlow ? 80 : 240,
                         ParticipatingThrottler.baseLimitFor(30000, false, false, min, max, pct, isSlow));
            assertEquals("3750 tunnels", isSlow ? 80 : 240,
                         ParticipatingThrottler.baseLimitFor(3750, false, false, min, max, pct, isSlow));
            // the low-share branch has no platform divisor: 30000 * 10 / 500
            // is 600 either way, and 0 tunnels fall back to maxLimit / 20
            assertEquals(80, ParticipatingThrottler.baseLimitFor(30000, true, false, min, max, pct, isSlow));
            assertEquals(15, ParticipatingThrottler.baseLimitFor(0, true, false, min, max, pct, isSlow));
        }
    }

    // ---------- helpers ----------

    private static void withPolicy(int min, int max, int pct) {
        ParticipatingThrottler._minLimit = min;
        ParticipatingThrottler._maxLimit = max;
        ParticipatingThrottler._percentLimit = pct;
    }

    /**
     * The limit for a peer with both relaxations switched off, so the
     * assertions can be compared against a hand-computed share.
     */
    private static int limit(int numTunnels, boolean isUnreachable, boolean isLowShare) {
        return limit(numTunnels, isUnreachable, isLowShare, BW_BUSY, LOAD_BUSY);
    }

    private static int limit(int numTunnels, boolean isUnreachable, boolean isLowShare, double bwUsage, int sysLoad) {
        return ParticipatingThrottler.computeLimit(numTunnels, isUnreachable, isLowShare, false,
                                                    MAX_TUNNELS, bwUsage, sysLoad);
    }
}
