package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The auto-tuned bounds on the tunnel test failure window
 * ({@code i2p.tunnel.testJob.minTestPeriod} / {@code maxTestPeriod}).
 *
 * <p>Measured on a live router: 84 outbound test rounds a minute failed against
 * 29 that passed, the window in use sat at 1.4s, and every failure was recorded
 * at that deadline. Both bounds were being driven down by
 * {@code tunnel.testSuccessTime}, which only ever samples the rounds that
 * finished inside the window, so the loop had no way to notice the rounds it
 * was excluding. The ceiling had fallen from its 15s default to 3821ms and the
 * floor from 3000ms to 1000ms.
 *
 * <p>The ceiling's old growth condition was dead code: it asked for
 * {@code tunnel.testFailedTime > 2x current}, but a failed round is timed out
 * at the deadline, so its recorded duration sits at the window and can never
 * reach twice it. The ceiling could therefore only ever fall. Growth is keyed
 * on the pass/fail ratio instead.
 *
 * @since 0.9.72+
 */
public class TunerTestFailureWindowTest {

    /** Lower bound of the maxTestPeriod parameter, its shipped default. */
    private static final int CEIL_MIN = 3000;

    /** Upper bound of the maxTestPeriod parameter, its shipped default. */
    private static final int CEIL_MAX = 15000;

    /** Largest change one tuning pass may make to either bound, ms. */
    private static final int STEP = 1000;

    /** The minTestPeriod floor the ceiling target may not seek below. */
    private static final int FLOOR = 3000;

    /** The ceiling the live router had tuned itself down to. */
    private static final int COLLAPSED_CEILING = 3821;

    /**
     * Runs the auto-tuned tunnel test window bounds over fixed samples.
     *
     * @since 0.9.72+
     */
    public TunerTestFailureWindowTest() {
        super();
    }

    // ---- the floor bound ----

    /**
     * The floor bound equals the shipped default of {@code minTestPeriod}: the
     * parameter may raise the floor on a slow network, never undercut it.
     */
    @Test
    public void floorBoundIsTheShippedDefault() {
        assertEquals(3000, Tuner.TEST_WINDOW_FLOOR_MIN);
    }

    /**
     * A 6-hop round trip (three hops out, three back) cannot complete inside
     * 1s on a loaded router, and the collapsed 1000ms bound is what put the
     * window in use at 1.4s.
     */
    @Test
    public void floorBoundLeavesRoomForAHopRoundTrip() {
        assertTrue("floor must cover a 6-hop round trip at 500ms a hop",
                   Tuner.TEST_WINDOW_FLOOR_MIN >= 6 * 500);
    }

    // ---- the ceiling target ----

    /**
     * The regression this replaces: with the failure duration equal to the
     * window (the only value it can take) the old rule never grew the ceiling,
     * so it only ever fell. A failing cycle must widen the window even though
     * the failures are recorded at the deadline.
     */
    @Test
    public void failingCycleWidensTheCeiling() {
        int next = Tuner.testWindowCeilingTarget(COLLAPSED_CEILING, CEIL_MIN, CEIL_MAX, STEP,
                                                 373, 0.49d, FLOOR);
        assertEquals(COLLAPSED_CEILING + STEP, next);
    }

    /**
     * 100ms successes with half the rounds failing: the success mean must not
     * pull the window down to 200ms while rounds are missing it.
     */
    @Test
    public void failingCycleWidensEvenOnAFastNetwork() {
        int next = Tuner.testWindowCeilingTarget(3000, CEIL_MIN, CEIL_MAX, STEP, 100, 0.5d, FLOOR);
        assertEquals(4000, next);
    }

    /** Growth is bounded by the parameter's own ceiling, one step at a time. */
    @Test
    public void growthStopsAtTheCeiling() {
        assertEquals(CEIL_MAX, Tuner.testWindowCeilingTarget(CEIL_MAX, CEIL_MIN, CEIL_MAX, STEP,
                                                             373, 0.49d, FLOOR));
        assertEquals(CEIL_MAX, Tuner.testWindowCeilingTarget(CEIL_MAX - 100, CEIL_MIN, CEIL_MAX,
                                                             STEP, 373, 0.49d, FLOOR));
    }

    /**
     * Ratio unknown (too few rounds sampled) is not evidence of a failing
     * cycle, so the 2x measured RTT target still applies.
     */
    @Test
    public void unknownRatioFallsBackToTheMeasuredTarget() {
        int next = Tuner.testWindowCeilingTarget(15000, CEIL_MIN, CEIL_MAX, STEP, 2258,
                                                 Double.NaN, FLOOR);
        assertEquals(14000, next);
    }

    /** A passing cycle whose window sits far above 2x the RTT shrinks one step. */
    @Test
    public void passingCycleShrinksTowardTwoTimesTheRtt() {
        int next = Tuner.testWindowCeilingTarget(15000, CEIL_MIN, CEIL_MAX, STEP, 2258, 0.1d,
                                                 FLOOR);
        assertEquals(14000, next);
    }

    /** Shrink stops at the parameter's own lower bound. */
    @Test
    public void shrinkStopsAtTheCeilingFloor() {
        assertEquals(CEIL_MIN, Tuner.testWindowCeilingTarget(CEIL_MIN, CEIL_MIN, CEIL_MAX, STEP,
                                                             100, 0.1d, FLOOR));
    }

    /** A value already within one step of the target is left alone. */
    @Test
    public void hysteresisLeavesACloseValueAlone() {
        // target is 2x2258 = 4516, current is inside one step of it
        assertEquals(4516, Tuner.testWindowCeilingTarget(4516, CEIL_MIN, CEIL_MAX, STEP, 2258,
                                                         0.1d, FLOOR));
    }

    /** Nothing measured yet: hold whatever is in effect rather than guess. */
    @Test
    public void noMeasurementsHoldTheCurrentValue() {
        assertEquals(COLLAPSED_CEILING,
                     Tuner.testWindowCeilingTarget(COLLAPSED_CEILING, CEIL_MIN, CEIL_MAX, STEP,
                                                   Double.NaN, 0.49d, FLOOR));
        assertEquals(COLLAPSED_CEILING,
                     Tuner.testWindowCeilingTarget(COLLAPSED_CEILING, CEIL_MIN, CEIL_MAX, STEP,
                                                   0, 0.49d, FLOOR));
    }

    /**
     * The target is never allowed below the floor the paired parameter holds,
     * so a 746ms target against a 3000ms floor is already at target.
     */
    @Test
    public void targetNeverSeeksBelowThePairedFloor() {
        assertEquals(3000, Tuner.testWindowCeilingTarget(3000, CEIL_MIN, CEIL_MAX, STEP, 373,
                                                         0.1d, FLOOR));
    }

    // ---- the floor target ----

    /**
     * The fast-network case that used to collapse the floor: a 373ms success
     * mean asks for a 373ms floor, and the floor may not follow it down.
     */
    @Test
    public void floorNeverFollowsAFastNetworkBelowTheBound() {
        int next = Tuner.testWindowFloorTarget(3000, Tuner.TEST_WINDOW_FLOOR_MIN, 10000, STEP,
                                               373, CEIL_MAX);
        assertEquals(3000, next);
    }

    /** A slow network raises the floor, one step per pass. */
    @Test
    public void floorGrowsOnASlowNetwork() {
        int next = Tuner.testWindowFloorTarget(3000, Tuner.TEST_WINDOW_FLOOR_MIN, 10000, STEP,
                                               5000, CEIL_MAX);
        assertEquals(4000, next);
    }

    /** The floor may never exceed the max window, even on a very slow network. */
    @Test
    public void floorNeverExceedsTheMaxWindow() {
        int next = Tuner.testWindowFloorTarget(3000, Tuner.TEST_WINDOW_FLOOR_MIN, 10000, STEP,
                                               20000, 5000);
        assertEquals(4000, next);
        int at = Tuner.testWindowFloorTarget(5000, Tuner.TEST_WINDOW_FLOOR_MIN, 10000, STEP,
                                             20000, 5000);
        assertEquals(5000, at);
    }

    /** Nothing measured yet: the floor stays where it is. */
    @Test
    public void floorHoldsWithoutMeasurements() {
        assertEquals(3000, Tuner.testWindowFloorTarget(3000, Tuner.TEST_WINDOW_FLOOR_MIN, 10000,
                                                       STEP, Double.NaN, CEIL_MAX));
    }
}
