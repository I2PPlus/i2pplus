package net.i2p.router.tunnel.pool;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 *  Tests that in-flight permits cannot be orphaned by arming a new round.
 *
 *  Every release path (reply, timeout, abandon, cancel) reaches the permits
 *  only after {@link TestJob#claimRound}, which requires the token to still be
 *  the active round. A round that is still holding permits when the next round
 *  arms therefore becomes unreachable and its permits are never returned. The
 *  gauge drifted upward by one per occurrence until it reached the global cap,
 *  at which point every test was refused and none dispatched while builds
 *  continued — the symptom seen in production on 2026-09-30.
 */
public class TestJobSupersedeLeakTest {

    private static final String POOL = "pool-under-test";
    private static final int CAP = 8;
    private static final long EXPIRATION = 60_000L;
    private static long _gen;

    private static TestJob.RoundToken newToken() {
        return new TestJob.RoundToken(++_gen, POOL, EXPIRATION);
    }

    /**
     *  The regression itself: arm a second round while the first still holds
     *  permits, exactly as {@code reserveInFlightForToken} does. Without the
     *  release the gauge climbs to 1 and stays there forever.
     */
    @Test
    public void armingOverALiveRoundReturnsItsPermits() {
        TestJob.BatchState state = new TestJob.BatchState();
        AtomicReference<TestJob.RoundToken> active = new AtomicReference<>();
        TestJob.RoundToken first = newToken();
        assertEquals(TestJob.InFlightRefusal.NONE,
                     TestJob.reserveInFlightWithReason(state, CAP, POOL, 4, first));
        TestJob.armRound(state, active, first);
        assertEquals("first round holds a permit", 1, state.inFlight.get());

        // Arm a second round over the still-live first one, as the job does.
        TestJob.RoundToken second = newToken();
        assertEquals(TestJob.InFlightRefusal.NONE,
                     TestJob.reserveInFlightWithReason(state, CAP, POOL, 4, second));
        TestJob.armRound(state, active, second);

        assertEquals("superseded round's permit must be returned",
                     1, state.inFlight.get());
    }

    /**
     *  The leak is cumulative. Repeating the arm-over-live-round pattern must
     *  not walk the gauge up; this is the property whose failure wedged the
     *  router at a 100% refusal rate.
     */
    @Test
    public void repeatedSupersedeDoesNotDriftTheGauge() {
        TestJob.BatchState state = new TestJob.BatchState();
        AtomicReference<TestJob.RoundToken> active = new AtomicReference<>();
        for (int i = 0; i < 40; i++) {
            TestJob.RoundToken next = newToken();
            assertEquals(TestJob.InFlightRefusal.NONE,
                         TestJob.reserveInFlightWithReason(state, CAP, POOL, CAP, next));
            TestJob.armRound(state, active, next);
        }
        assertEquals("gauge drifted after 40 supersedes", 1, state.inFlight.get());
    }

    /**
     *  A superseded round can never be claimed again, so nothing else will ever
     *  return its permit. This pins the unreachability that made the leak
     *  permanent rather than merely slow to recover.
     */
    @Test
    public void supersededRoundCannotBeClaimed() {
        TestJob.BatchState state = new TestJob.BatchState();
        AtomicReference<TestJob.RoundToken> active = new AtomicReference<>();
        TestJob.RoundToken first = newToken();
        TestJob.RoundToken second = newToken();
        TestJob.reserveInFlightWithReason(state, CAP, POOL, 4, first);
        TestJob.armRound(state, active, first);
        TestJob.reserveInFlightWithReason(state, CAP, POOL, 4, second);
        TestJob.armRound(state, active, second);
        // claimRoundCompletion(active, token) is the check every release path uses.
        assertTrue("superseded round is unreachable, hence unreleasable",
                   !TestJob.claimRoundCompletion(active.get(), first));
    }

    /**
     *  Releasing the prior round is idempotent: if it already completed and
     *  returned its own permit, the supersede call must not decrement again and
     *  drive the gauge negative.
     */
    @Test
    public void releasingAnAlreadyReleasedRoundIsSafe() {
        TestJob.BatchState state = new TestJob.BatchState();
        AtomicReference<TestJob.RoundToken> active = new AtomicReference<>();
        TestJob.RoundToken first = newToken();
        TestJob.reserveInFlightWithReason(state, CAP, POOL, 4, first);
        TestJob.armRound(state, active, first);
        // Normal terminal path returns the permit.
        TestJob.releaseTokenPermits(state, first);
        assertEquals(0, state.inFlight.get());
        // Superseding afterwards must be a no-op, not a second decrement.
        TestJob.armRound(state, active, newToken());
        assertEquals("gauge must not go negative", 0, state.inFlight.get());
    }

    /**  A null prior round (the first round of a job) must be tolerated. */
    @Test
    public void nullPriorRoundIsTolerated() {
        TestJob.BatchState state = new TestJob.BatchState();
        TestJob.armRound(state, new AtomicReference<>(), null);
        assertEquals(0, state.inFlight.get());
    }

    /**
     *  End-to-end invariant: after a round completes and the next is armed, the
     *  per-pool counter must be unlinked at zero, not left dangling.
     */
    @Test
    public void poolCounterUnlinksAtZeroAfterSupersede() {
        TestJob.BatchState state = new TestJob.BatchState();
        AtomicReference<TestJob.RoundToken> active = new AtomicReference<>();
        TestJob.RoundToken first = newToken();
        TestJob.reserveInFlightWithReason(state, CAP, POOL, 2, first);
        TestJob.armRound(state, active, first);
        assertTrue("pool counter registered", state.poolInFlight.containsKey(POOL));
        TestJob.armRound(state, active, newToken());
        assertTrue("per-pool counter must unlink at zero",
                   !state.poolInFlight.containsKey(POOL));
        assertEquals(0, state.inFlight.get());
    }
}
