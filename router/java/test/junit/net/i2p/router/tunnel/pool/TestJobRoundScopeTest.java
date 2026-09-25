package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for round scoping: one {@link TestJob.RoundToken} per dispatch, and
 * completion claimed exactly once for exactly that round.  A TestJob instance
 * is reused for retests, so a delayed reply or timeout from an earlier round
 * must not complete, release, or clear a later one.
 *
 * @since 0.9.71+
 */
public class TestJobRoundScopeTest {

    private static final long EXPIRATION = 2_000_000L;

    private static TestJob.RoundToken token(long generation) {
        return new TestJob.RoundToken(generation, "client-dest-inbound", EXPIRATION);
    }

    @Test
    public void testActiveRoundClaimedOnce() {
        TestJob.RoundToken round = token(1);
        assertTrue(TestJob.claimRoundCompletion(round, round));
        assertFalse("second terminal must be a no-op", TestJob.claimRoundCompletion(round, round));
        assertTrue(round.completed.get());
    }

    @Test
    public void testStaleRoundCannotCompleteActiveOne() {
        TestJob.RoundToken stale = token(1);
        TestJob.RoundToken active = token(2);
        assertFalse(TestJob.claimRoundCompletion(active, stale));
        assertFalse("stale claim must not complete anything", stale.completed.get());
        // the active round is still claimable by its own callbacks
        assertTrue(TestJob.claimRoundCompletion(active, active));
    }

    @Test
    public void testCompletedRoundStaysCompletedForLateCallbacks() {
        TestJob.RoundToken round = token(3);
        assertTrue(TestJob.claimRoundCompletion(round, round));
        assertFalse(TestJob.claimRoundCompletion(round, round));
    }

    @Test
    public void testNullTokenNeverClaims() {
        TestJob.RoundToken active = token(1);
        assertFalse(TestJob.claimRoundCompletion(active, null));
        assertFalse(TestJob.claimRoundCompletion(null, null));
        assertFalse(TestJob.claimRoundCompletion(null, active));
    }

    @Test
    public void testTokenCarriesPoolAndExpiration() {
        TestJob.RoundToken round = token(7);
        assertEquals("client-dest-inbound", round.poolId);
        assertEquals(EXPIRATION, round.expiration);
        assertEquals(7, round.generation);
        assertFalse("a fresh round holds no permits", round.permitsHeld.get());
    }

    @Test
    public void testNullPoolTokenStillScoped() {
        TestJob.RoundToken round = new TestJob.RoundToken(1, null, EXPIRATION);
        assertNull(round.poolId);
        assertTrue(TestJob.claimRoundCompletion(round, round));
    }

    // ---------------- in-flight permits (dispatch gating) ----------------

    private static final String POOL = "client-dest-inbound";

    @Test
    public void testReserveTakesGlobalAndPoolPermits() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken round = token(1);

        assertTrue(TestJob.tryReserveInFlight(state, 2, POOL, 1, round));

        assertEquals(1, state.inFlight.get());
        assertEquals(1, state.poolInFlight.get(POOL).get());
        assertTrue(round.permitsHeld.get());
    }

    @Test
    public void testReserveRefusedAtGlobalCap() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken first = token(1);
        TestJob.RoundToken second = token(2);

        assertTrue(TestJob.tryReserveInFlight(state, 1, POOL, 4, first));
        assertFalse("the second dispatch must not overfill the cap",
                    TestJob.tryReserveInFlight(state, 1, POOL, 4, second));

        assertEquals(1, state.inFlight.get());
        assertFalse("a refused round holds nothing, so it can never release",
                    second.permitsHeld.get());
        assertEquals("a refused round must not reserve a pool slot either",
                     1, state.poolInFlight.get(POOL).get());
    }

    @Test
    public void testReserveRefusedAtPoolBudgetReleasesGlobal() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken first = token(1);
        TestJob.RoundToken second = token(2);

        assertTrue(TestJob.tryReserveInFlight(state, 64, POOL, 1, first));
        assertFalse(TestJob.tryReserveInFlight(state, 64, POOL, 1, second));

        assertEquals("the pool refusal must hand the global slot back",
                     1, state.inFlight.get());
        assertEquals(1, state.poolInFlight.get(POOL).get());
        assertFalse(second.permitsHeld.get());
    }

    @Test
    public void testNegativePoolBudgetSkipsPoolGate() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken first = token(1);
        TestJob.RoundToken second = token(2);

        assertTrue(TestJob.tryReserveInFlight(state, 64, POOL, -1, first));
        assertTrue(TestJob.tryReserveInFlight(state, 64, POOL, -1, second));

        assertEquals(2, state.inFlight.get());
        assertEquals(2, state.poolInFlight.get(POOL).get());
    }

    @Test
    public void testNullPoolIdSkipsPoolGate() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken round = new TestJob.RoundToken(1, null, EXPIRATION);

        assertTrue(TestJob.tryReserveInFlight(state, 1, null, 1, round));

        assertEquals(1, state.inFlight.get());
        assertTrue(state.poolInFlight.isEmpty());
    }

    @Test
    public void testDoubleReserveOfSameTokenRefused() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken round = token(1);

        assertTrue(TestJob.tryReserveInFlight(state, 8, POOL, 8, round));
        assertFalse("a token that already holds its permits must not take more",
                    TestJob.tryReserveInFlight(state, 8, POOL, 8, round));

        assertEquals(1, state.inFlight.get());
        assertEquals(1, state.poolInFlight.get(POOL).get());
    }

    @Test
    public void testReleaseReturnsPermitsExactlyOnce() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken round = token(1);
        assertTrue(TestJob.tryReserveInFlight(state, 8, POOL, 8, round));

        TestJob.releaseTokenPermits(state, round);
        assertEquals(0, state.inFlight.get());
        assertNull("the pool counter is unlinked at zero",
                   state.poolInFlight.get(POOL));
        assertFalse(round.permitsHeld.get());

        TestJob.releaseTokenPermits(state, round);
        assertEquals("a second terminal must not drive the gauge negative",
                     0, state.inFlight.get());
        assertTrue(state.poolInFlight.isEmpty());
    }

    @Test
    public void testReleaseWithoutReserveIsNoOp() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken round = token(1);
        state.inFlight.set(2);

        TestJob.releaseTokenPermits(state, round);
        TestJob.releaseTokenPermits(state, null);

        assertEquals("only a reserved round may return permits", 2, state.inFlight.get());
        assertTrue(state.poolInFlight.isEmpty());
    }

    // ---------------- dispatch-failure and stale-callback simulations ----------------

    @Test
    public void testDispatchRejectionAbandonsRoundAndStragglerIsInert() {
        // sendTest() on a dispatch rejection (false or thrown): the round is
        // claimed and released once, then the reply/timeout that was already
        // registered must find it claimed and leave the gauges alone.
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken round = token(1);
        assertTrue(TestJob.tryReserveInFlight(state, 8, POOL, 8, round));

        assertTrue("the abandoning path owns the round",
                   TestJob.claimRoundCompletion(round, round));
        TestJob.releaseTokenPermits(state, round);

        assertFalse("a straggler callback can no longer complete the round",
                    TestJob.claimRoundCompletion(round, round));
        TestJob.releaseTokenPermits(state, round);
        assertEquals(0, state.inFlight.get());
        assertTrue(state.poolInFlight.isEmpty());
    }

    @Test
    public void testWrapFailureNeverTookPermits() {
        // The wrap/prepare failure returns before reserveInFlight(), so its
        // release path has nothing to return — and returning anyway is a no-op.
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken round = token(1);
        state.inFlight.set(0);

        TestJob.releaseTokenPermits(state, round);

        assertEquals(0, state.inFlight.get());
        assertFalse(round.permitsHeld.get());
        assertTrue(state.poolInFlight.isEmpty());
    }

    @Test
    public void testDelayedTimeoutCannotReleaseLaterRound() {
        // Round 1 completes and returns its permits; round 2 is now active and
        // holds its own.  The delayed timeout for round 1 must neither claim
        // round 2 nor take its permits back.
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken first = token(1);
        assertTrue(TestJob.tryReserveInFlight(state, 8, POOL, 8, first));
        assertTrue(TestJob.claimRoundCompletion(first, first));
        TestJob.releaseTokenPermits(state, first);

        TestJob.RoundToken second = token(2);
        assertTrue(TestJob.tryReserveInFlight(state, 8, POOL, 8, second));

        assertFalse("the stale timeout cannot claim the active round",
                    TestJob.claimRoundCompletion(second, first));
        TestJob.releaseTokenPermits(state, first);

        assertTrue("the active round still holds its permits",
                   second.permitsHeld.get());
        assertEquals(1, state.inFlight.get());
        assertEquals(1, state.poolInFlight.get(POOL).get());
    }
}
