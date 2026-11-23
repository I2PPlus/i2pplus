package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure streaming inactivity-timeout policy
 * {@link Tuner#nextInactivityTimeoutMs(int, double, boolean, int, int, int)},
 * extracted from {@code InactivityTimeoutParam#computeTarget}.
 *
 * <p>The regression this pins: the policy read the transport RTT
 * ({@code udp.sendConfirmTime}, 80-107ms live) as the signal for how long a
 * stream may stay silent, and tightened whenever that figure sat below 5,000ms.
 * It always did, so the timeout walked to its 120s floor and stayed there on a
 * router whose completed streams ran at 1,407ms. The policy now targets a number
 * of round trips of the completed-stream RTT, and holds when there is no
 * completed stream to measure instead of substituting the transport figure.
 *
 * @since 0.9.72+
 */
public class TunerInactivityTimeoutTargetTest {

    /** The param's own bracket: 120s floor, 600s ceiling, 30s per cycle. */
    private static final int MIN = 120_000;
    private static final int MAX = 600_000;
    private static final int STEP = 30_000;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one. The policy under test is static and holds no state.
     *
     * @since 0.9.72+
     */
    public TunerInactivityTimeoutTargetTest() {}

    /**
     * The shipped bracket is the multiple: the floor, the default and the ceiling
     * are {@value Tuner#INACTIVITY_RTT_ROUNDS} round trips at 1.2s, 3s and 6s, the
     * range of stream RTTs this router reports.
     */
    @Test
    public void multipleMatchesTheShippedBracket() {
        assertEquals(1_200, MIN / Tuner.INACTIVITY_RTT_ROUNDS);
        assertEquals(3_000, 300_000 / Tuner.INACTIVITY_RTT_ROUNDS);
        assertEquals(6_000, MAX / Tuner.INACTIVITY_RTT_ROUNDS);
    }

    /**
     * No completed stream is no signal. The old code fell back to the transport
     * send-confirm time here, and that fallback is what pinned the timeout.
     */
    @Test
    public void noCompletedStreamHoldsTheValue() {
        assertEquals(300_000, Tuner.nextInactivityTimeoutMs(300_000, Double.NaN, false,
                                                             MIN, MAX, STEP));
        // and a zero or negative sample is the same absence, not "very fast"
        assertEquals(300_000, Tuner.nextInactivityTimeoutMs(300_000, 0, false, MIN, MAX, STEP));
        assertEquals(300_000, Tuner.nextInactivityTimeoutMs(300_000, -1, false, MIN, MAX, STEP));
    }

    /**
     * The live case: 1,407ms streams imply 140,700ms, one step away from the floor
     * it had been pinned at, so the cycle holds instead of pressing it down.
     */
    @Test
    public void liveStreamRttHoldsInsteadOfPinningTheFloor() {
        assertEquals(MIN, Tuner.nextInactivityTimeoutMs(MIN, 1_407, false, MIN, MAX, STEP));
        // and it is not pushed below the floor even from the floor itself
        assertEquals(MIN, Tuner.nextInactivityTimeoutMs(MIN, 1_000, false, MIN, MAX, STEP));
    }

    /** A slow stream relaxes the timeout one step per cycle. */
    @Test
    public void slowStreamsRelaxTheTimeoutOneStep() {
        // 3s streams imply the 300s default
        assertEquals(MIN + STEP, Tuner.nextInactivityTimeoutMs(MIN, 3_000, false, MIN, MAX, STEP));
    }

    /**
     * Repeated cycles land on the implied target rather than walking past it or
     * stopping short of it.
     */
    @Test
    public void repeatedCyclesLandOnTheTarget() {
        int value = MIN;
        for (int cycle = 0; cycle < 20; cycle++)
            value = Tuner.nextInactivityTimeoutMs(value, 3_000, false, MIN, MAX, STEP);
        assertEquals(300_000, value);
    }

    /** A fast stream tightens, but never below the floor the setter enforces. */
    @Test
    public void fastStreamsTightenToTheFloorAndStop() {
        int value = 300_000;
        for (int cycle = 0; cycle < 20; cycle++)
            value = Tuner.nextInactivityTimeoutMs(value, 500, false, MIN, MAX, STEP);
        assertEquals(MIN, value);
        assertEquals(MIN, Tuner.nextInactivityTimeoutMs(MIN, 500, false, MIN, MAX, STEP));
    }

    /**
     * The dead zone is strictly under one step, so a target exactly one step away
     * is landed on: without that, the bracket's own floor would be unreachable
     * from above.
     */
    @Test
    public void oneStepAwayMovesAndLands() {
        assertEquals(MIN, Tuner.nextInactivityTimeoutMs(MIN + STEP, 500, false, MIN, MAX, STEP));
        // and strictly inside the dead zone it holds
        assertEquals(MIN + 10_000, Tuner.nextInactivityTimeoutMs(MIN + 10_000, 500, false,
                                                                 MIN, MAX, STEP));
    }

    /** A stream RTT past the bracket's ceiling still cannot exceed the ceiling. */
    @Test
    public void targetIsClampedToTheCeiling() {
        assertEquals(MAX, Tuner.nextInactivityTimeoutMs(MAX, 60_000, false, MIN, MAX, STEP));
        assertEquals(MAX, Tuner.nextInactivityTimeoutMs(MAX - STEP, 60_000, false, MIN, MAX, STEP));
        assertEquals(MAX, Tuner.nextInactivityTimeoutMs(MAX, 120_000, false, MIN, MAX, STEP));
    }

    /**
     * Retransmitting streams hold the cycle: the RTT sample is inflated by the
     * retransmit waits themselves, so it cannot say which way to move.
     */
    @Test
    public void lossyCycleHolds() {
        assertEquals(MIN, Tuner.nextInactivityTimeoutMs(MIN, 3_000, true, MIN, MAX, STEP));
        assertEquals(300_000, Tuner.nextInactivityTimeoutMs(300_000, 500, true, MIN, MAX, STEP));
    }

    /** One cycle never moves more than one step, whatever the RTT. */
    @Test
    public void movementIsBoundedByOneStep() {
        for (int rtt = 100; rtt <= 30_000; rtt += 100) {
            int next = Tuner.nextInactivityTimeoutMs(300_000, rtt, false, MIN, MAX, STEP);
            assertTrue("rtt " + rtt + " moved to " + next, Math.abs(next - 300_000) <= STEP);
            assertTrue("rtt " + rtt + " out of bracket: " + next, next >= MIN && next <= MAX);
        }
    }

    /**
     * The old policy's shape, for contrast: on the transport RTT it had one
     * direction, so any value at the floor stayed there and any value above it
     * walked down. The new policy moves up on the same router.
     */
    @Test
    public void transportRttNoLongerDrivesTheDecision() {
        // 107ms of transport RTT used to mean "tighten"; as a stream RTT it means
        // "the floor is already right", so nothing walks below it
        assertEquals(MIN, Tuner.nextInactivityTimeoutMs(MIN, 107, false, MIN, MAX, STEP));
        // and a genuinely slow stream now has a way to be heard
        assertTrue(Tuner.nextInactivityTimeoutMs(MIN, 5_000, false, MIN, MAX, STEP) > MIN);
    }
}
