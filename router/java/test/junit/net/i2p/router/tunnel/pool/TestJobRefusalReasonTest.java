package net.i2p.router.tunnel.pool;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 *  Tests that an in-flight refusal names the gate that refused it.
 *
 *  The reservation path has two independent gates: a router-wide concurrent
 *  test cap and a per-pool budget. They bind for different reasons and need
 *  different tuning, but both used to log the same "budget exhausted" line,
 *  so live logs could not say which knob to turn.
 */
public class TestJobRefusalReasonTest {

    private static final String POOL = "pool-under-test";
    private static final int GLOBAL_CAP = 4;
    private static final long EXPIRATION = 60_000L;
    private static long _gen;

    private static TestJob.RoundToken newToken() {
        return new TestJob.RoundToken(++_gen, POOL, EXPIRATION);
    }

    /** An empty state with room to spare takes both permits. */
    @Test
    public void permitsGrantedWhenBelowBothGates() {
        TestJob.BatchState state = new TestJob.BatchState();
        assertEquals(TestJob.InFlightRefusal.NONE,
                     TestJob.reserveInFlightWithReason(state, GLOBAL_CAP, POOL, 2, newToken()));
    }

    /** The router-wide cap is reported as GLOBAL_CAP, not as a pool budget. */
    @Test
    public void globalCapRefusalIsIdentified() {
        TestJob.BatchState state = new TestJob.BatchState();
        for (int i = 0; i < GLOBAL_CAP; i++) {
            TestJob.reserveInFlightWithReason(state, GLOBAL_CAP, POOL, 100, newToken());
        }
        assertEquals(TestJob.InFlightRefusal.GLOBAL_CAP,
                     TestJob.reserveInFlightWithReason(state, GLOBAL_CAP, POOL, 100, newToken()));
    }

    /** One pool at its own budget is reported as POOL_BUDGET, even with global room. */
    @Test
    public void poolBudgetRefusalIsIdentified() {
        TestJob.BatchState state = new TestJob.BatchState();
        // Budget of 1 with a wide global cap: only the per-pool gate can refuse.
        assertEquals(TestJob.InFlightRefusal.NONE,
                     TestJob.reserveInFlightWithReason(state, 100, POOL, 1, newToken()));
        assertEquals(TestJob.InFlightRefusal.POOL_BUDGET,
                     TestJob.reserveInFlightWithReason(state, 100, POOL, 1, newToken()));
    }

    /** The two refusals must be distinguishable, which is the whole point. */
    @Test
    public void theTwoGatesAreDistinguishable() {
        assertNotEquals(TestJob.refusalReasonText(TestJob.InFlightRefusal.GLOBAL_CAP),
                        TestJob.refusalReasonText(TestJob.InFlightRefusal.POOL_BUDGET));
    }

    /** The log fragment must not be empty, or the split is invisible in the log. */
    @Test
    public void reasonTextIsDistinctAndNonEmpty() {
        for (TestJob.InFlightRefusal r : TestJob.InFlightRefusal.values()) {
            assertNotEquals(r.name(), "", TestJob.refusalReasonText(r));
        }
    }

    /**
     *  A pool-budget refusal must return the global permit it just took, so a
     *  refusal cannot starve unrelated pools by leaking capacity.
     */
    @Test
    public void poolRefusalReleasesGlobalPermit() {
        TestJob.BatchState state = new TestJob.BatchState();
        assertEquals(TestJob.InFlightRefusal.NONE,
                     TestJob.reserveInFlightWithReason(state, 100, POOL, 1, newToken()));
        assertEquals(TestJob.InFlightRefusal.POOL_BUDGET,
                     TestJob.reserveInFlightWithReason(state, 100, POOL, 1, newToken()));
        // Global count should be 1 (only the granted round), not 2.
        assertEquals(1, state.inFlight.get());
    }

    /** A token that already holds permits is refused rather than double counted. */
    @Test
    public void alreadyHeldTokenIsRefused() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.RoundToken token = newToken();
        assertEquals(TestJob.InFlightRefusal.NONE,
                     TestJob.reserveInFlightWithReason(state, 100, POOL, 10, token));
        assertEquals(TestJob.InFlightRefusal.ALREADY_HELD,
                     TestJob.reserveInFlightWithReason(state, 100, POOL, 10, token));
    }

    /** The boolean helper stays consistent with the reason-returning form. */
    @Test
    public void booleanHelperAgreesWithReason() {
        TestJob.BatchState state = new TestJob.BatchState();
        assertEquals(true,
                     TestJob.tryReserveInFlight(state, 100, POOL, 1, newToken()));
        assertEquals(false,
                     TestJob.tryReserveInFlight(state, 100, POOL, 1, newToken()));
    }
}
