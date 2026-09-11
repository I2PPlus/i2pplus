package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the soft-failure reschedule rate limit and the SYN give-up budget
 * exemption.
 *
 * <p>{@link Connection#shouldRateLimitSoftRetransmit(long, long, long)} gates
 * {@link Connection#scheduleSoftFailureRetransmit()} so back-to-back router
 * soft failures (NO_LEASESET while the LeaseSet fetch is in flight) defer to
 * the retransmit timer instead of firing a new immediate pass at the I2CP
 * round-trip rate.  Without the gate, a ~10ms soft-fail burst exhausted the
 * SYN give-up budget in ~200ms and killed the connect with a spurious
 * "SYN not acknowledged" (the 20-send / 208ms kill observed on live routers).
 *
 * <p>{@link Connection#synGiveUpBudgetExceeded(int, int, int)} mirrors
 * {@link Connection#hardResendBudgetExceeded(int, int, int)} for the SYN
 * phase: soft-failure-triggered resends never reached the tunnel fabric, so
 * they should not count as evidence of a dead path.
 *
 * @since 0.9.71+
 */
public class ConnectionSoftFailureRescheduleTest {

    // ---- shouldRateLimitSoftRetransmit ----

    /** No prior immediate retransmit: never rate-limited. */
    @Test
    public void testNoPriorRetransmitAllowed() {
        assertFalse(Connection.shouldRateLimitSoftRetransmit(1000, 0, 5000));
        assertFalse(Connection.shouldRateLimitSoftRetransmit(1000, -1, 5000));
    }

    /** A report exactly at the spacing boundary is allowed (strict < gate). */
    @Test
    public void testAtBoundaryAllowed() {
        assertFalse(Connection.shouldRateLimitSoftRetransmit(6000, 1000, 5000));
        assertFalse(Connection.shouldRateLimitSoftRetransmit(1750, 1000, 750));
    }

    /** A report inside the spacing window defers to the timer. */
    @Test
    public void testInsideWindowRateLimited() {
        assertTrue(Connection.shouldRateLimitSoftRetransmit(5999, 1000, 5000));
        assertTrue(Connection.shouldRateLimitSoftRetransmit(1100, 1000, 750));
        assertTrue(Connection.shouldRateLimitSoftRetransmit(750, 1000, 5000));
    }

    /** Long gaps pass regardless of magnitude. */
    @Test
    public void testLongGapAllowed() {
        assertFalse(Connection.shouldRateLimitSoftRetransmit(100000, 1000, 5000));
        assertFalse(Connection.shouldRateLimitSoftRetransmit(100000, 90000, 750));
    }

    /**
     * Simulated 20-send / ~200ms soft-fail storm (the live-router failure):
     * with a 5000ms SYN interval at most one immediate retransmit fires in the
     * whole burst; the rest defer to the timer.
     */
    @Test
    public void testSynStormOnlyFirstRetransmitFires() {
        long now = 100000, last = 0;
        int fires = 0;
        for (int i = 1; i <= 20; i++) {
            now += 10;
            if (!Connection.shouldRateLimitSoftRetransmit(now, last, 5000)) {
                fires++;
                last = now;
            }
        }
        assertEquals(1, fires);
    }

    // ---- synGiveUpBudgetExceeded ----

    /** Give-up is strict >= (SYN give-up after the budget is covered). */
    @Test
    public void testAtBudgetBoundaryNotExceeded() {
        assertFalse(Connection.synGiveUpBudgetExceeded(11, 0, 12));
        assertFalse(Connection.synGiveUpBudgetExceeded(0, 0, 12));
    }

    /** At exactly the budget sends equal the budget: give up. */
    @Test
    public void testAtBudgetExceeded() {
        assertTrue(Connection.synGiveUpBudgetExceeded(12, 0, 12));
        assertTrue(Connection.synGiveUpBudgetExceeded(1, 0, 1));
    }

    /** Soft-failure resends do not consume the SYN give-up budget. */
    @Test
    public void testSoftResendsOffsetBudget() {
        // a pure soft storm (zero hard sends) never gives up regardless of total sends
        assertFalse(Connection.synGiveUpBudgetExceeded(20, 20, 12));
        assertFalse(Connection.synGiveUpBudgetExceeded(100, 100, 12));
        // 12 hard sends among 20 total (8 soft) hits the budget
        assertTrue(Connection.synGiveUpBudgetExceeded(20, 8, 12));
    }

    /** Hard sends past the budget still give up even with many soft resends. */
    @Test
    public void testHardSendsBeyondBudgetExceeded() {
        assertTrue(Connection.synGiveUpBudgetExceeded(21, 8, 12));
        assertTrue(Connection.synGiveUpBudgetExceeded(20, 7, 12));
    }

    /** Soft accounting clamped at zero: soft > total cannot undercount below 0. */
    @Test
    public void testSoftOvercountClampedToZero() {
        assertFalse(Connection.synGiveUpBudgetExceeded(5, 6, 12));
        assertFalse(Connection.synGiveUpBudgetExceeded(5, 500, 12));
    }

    /** Degenerate budgets: a budget of zero gives up on any send (>= semantics
     *  mirror the original {@code numSends >= maxSynSends}). */
    @Test
    public void testDegenerateBudget() {
        assertTrue(Connection.synGiveUpBudgetExceeded(1, 0, 0));
        assertTrue(Connection.synGiveUpBudgetExceeded(1, 0, -1));
        assertTrue(Connection.synGiveUpBudgetExceeded(0, 0, 0));
        assertTrue(Connection.synGiveUpBudgetExceeded(0, 1, 0));
    }

    /**
     * The live-router reproduction: 20 sends in ~208ms, all soft-failure
     * resends while the LeaseSet fetch was in flight.  With a budget of 12,
     * the old counting (total sends) triggered give-up; the soft-exempt count
     * (20 hard, 20 soft) does not, so the connect survives the burst.
     */
    @Test
    public void testReproTwentySendSoftStormDoesNotGiveUp() {
        assertTrue("old behavior: 20 total sends trip the budget",
                   Connection.synGiveUpBudgetExceeded(20, 0, 12));
        assertFalse("new behavior: 20 sends all soft, zero hard on-wire sends",
                    Connection.synGiveUpBudgetExceeded(20, 20, 12));
    }
}
