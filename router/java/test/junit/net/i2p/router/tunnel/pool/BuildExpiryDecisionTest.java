package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the two pure decisions the build expiry scan and the dispatch budget make.
 *
 * <p>Both were grown from a defect observed in a 4-hour router window: the oldest
 * build in the building map sat 28s against a 15s deadline across consecutive
 * 2s passes while the scan reported nothing expiring, and the budget that
 * subtracts in-flight builds could be driven below zero. These tests pin the
 * corrected behaviour, and in particular the case the old inline test got wrong:
 * a first hop that no caller ever stamped with a creation time used to be
 * retained indefinitely rather than aged and expired.
 *
 * @since 0.9.71+
 */
public class BuildExpiryDecisionTest {

    private static final long NOW = 1_000_000L;

    // ---- isBuildExpired ----

    /** Inside the deadline the build is still owed its remaining seconds. */
    @Test
    public void testInsideDeadlineIsNotExpired() {
        assertFalse(BuildExecutor.isBuildExpired(NOW - 5_000L, NOW, 15_000L));
    }

    /** One millisecond short of the deadline is still inside it. */
    @Test
    public void testOneMillisecondShortIsNotExpired() {
        assertFalse(BuildExecutor.isBuildExpired(NOW, NOW + 14_999L, 15_000L));
    }

    /** Exactly at the deadline the build has run out of its allowance. */
    @Test
    public void testExactlyAtDeadlineIsExpired() {
        assertTrue(BuildExecutor.isBuildExpired(NOW, NOW + 15_000L, 15_000L));
    }

    /** Well past the deadline. */
    @Test
    public void testPastDeadlineIsExpired() {
        assertTrue(BuildExecutor.isBuildExpired(NOW - 28_000L, NOW, 15_000L));
    }

    /**
     * The defect. A HopConfig that no caller stamped with setCreation() keeps the
     * -1 its constructor gave it, and the old `created &gt; 0` guard therefore
     * refused to expire it at all. With nothing to age it against, the only
     * correct answer is to expire it rather than hold the slot forever.
     */
    @Test
    public void testUnstampedCreationIsExpired() {
        assertTrue(BuildExecutor.isBuildExpired(-1L, NOW, 15_000L));
    }

    /** Zero is no timestamp either, and must not be read as "just created". */
    @Test
    public void testZeroCreationIsExpired() {
        assertTrue(BuildExecutor.isBuildExpired(0L, NOW, 15_000L));
    }

    /**
     * A creation time in the future, from clock skew, must not expire on age
     * alone. That is why the comparison is `now &gt;= created + timeout` rather
     * than a difference against zero.
     */
    @Test
    public void testFutureCreationIsNotExpiredUntilDeadlinePasses() {
        long created = NOW + 5_000L;
        assertFalse(BuildExecutor.isBuildExpired(created, NOW, 15_000L));
        assertFalse(BuildExecutor.isBuildExpired(created, created + 14_999L, 15_000L));
        assertTrue(BuildExecutor.isBuildExpired(created, created + 15_000L, 15_000L));
    }

    /**
     * A non-positive deadline disables expiry. Without this guard the unstamped
     * case above would expire the entire building map on the first pass.
     */
    @Test
    public void testNonPositiveDeadlineDisablesExpiry() {
        assertFalse(BuildExecutor.isBuildExpired(-1L, NOW, 0L));
        assertFalse(BuildExecutor.isBuildExpired(NOW - 60_000L, NOW, -1L));
    }

    // ---- applyConcurrentReduction ----

    /** Nothing in flight leaves the budget untouched. */
    @Test
    public void testNoConcurrencyLeavesBudget() {
        assertEquals(10, BuildExecutor.applyConcurrentReduction(10, 0));
        assertEquals(10, BuildExecutor.applyConcurrentReduction(10, -3));
    }

    /** Fewer in flight than budget. */
    @Test
    public void testPartialConcurrencyReduces() {
        assertEquals(6, BuildExecutor.applyConcurrentReduction(10, 4));
    }

    /** Exactly consumed. */
    @Test
    public void testExactlyConsumedLeavesZero() {
        assertEquals(0, BuildExecutor.applyConcurrentReduction(10, 10));
    }

    /**
     * The floor. The caller used this as a build count, so a negative result
     * dispatched nothing at all and the pre-emergency path spun against a budget
     * only timeouts could repay.
     */
    @Test
    public void testOverConcurrencyFloorsAtZero() {
        assertEquals(0, BuildExecutor.applyConcurrentReduction(10, 46));
    }

    /** Already-exhausted budget stays exhausted, never negative. */
    @Test
    public void testZeroBudgetStaysZero() {
        assertEquals(0, BuildExecutor.applyConcurrentReduction(0, 1));
        assertEquals(0, BuildExecutor.applyConcurrentReduction(0, 0));
    }

    /**
     * A negative budget from an operator setting {@code router.tunnelConcurrentBuilds}
     * below zero is floored rather than passed through, because a caller using
     * the result as a build count would otherwise dispatch nothing at all.
     */
    @Test
    public void testNegativeBudgetFloorsAtZero() {
        assertEquals(0, BuildExecutor.applyConcurrentReduction(-5, 0));
        assertEquals(0, BuildExecutor.applyConcurrentReduction(-5, 3));
    }

    /**
     * A build held past its deadline is now subtracted, not carried. With six
     * such builds against a budget of ten the result is four, which is the
     * recovery this fix exists to produce.
     */
    @Test
    public void testBudgetRecoversAsBuildsExpire() {
        int allowed = 10;
        int inFlight = 12;
        // six expire; concurrent is read after the removals
        inFlight -= 6;
        allowed = BuildExecutor.applyConcurrentReduction(allowed, inFlight);
        assertEquals(4, allowed);
    }

    // ---- exempt (pre-emergency) in-flight ----

    /**
     * The case that was starving pools: 34 builds in flight, of which 32 belong to
     * pools holding their own per-pool cap. Only the two that do not are charged
     * against the global budget, so the budget is not exhausted by work it does
     * not govern.
     */
    @Test
    public void testExemptInFlightIsNotChargedToTheBudget() {
        int allowed = 18;
        int concurrent = 34;
        int exempt = 32;
        assertEquals(16, BuildExecutor.applyConcurrentReduction(allowed, concurrent - exempt));
    }

    /**
     * With no exemption the same 34 in flight exhausts the budget, which is the
     * behaviour that produced the starvation this exemption removes.
     */
    @Test
    public void testSameInFlightWithoutExemptionExhausts() {
        assertEquals(0, BuildExecutor.applyConcurrentReduction(18, 34 - 0));
    }

    /**
     * Exemption larger than the in-flight count must not make the argument
     * negative and thereby hand back an inflated budget: the reduction already
     * treats a non-positive count as "nothing to subtract".
     */
    @Test
    public void testExemptionLargerThanInFlightIsNotNegative() {
        int allowed = 18;
        int concurrent = 31;
        int exempt = 32;
        assertEquals(allowed, BuildExecutor.applyConcurrentReduction(allowed, concurrent - exempt));
    }

    /**
     * A partial exemption charges only the difference, so a pool that recovered is
     * charged for its own builds again: 34 in flight with 20 still exempt leaves 14
     * to charge against a budget of 18.
     */
    @Test
    public void testPartialExemptionChargesTheRemainder() {
        assertEquals(4, BuildExecutor.applyConcurrentReduction(18, 34 - 20));
    }
}
