package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure streaming pacing-floor policy
 * {@link Tuner#nextMinPacingRateKBps(int, double, boolean, boolean, int, int, int, int)},
 * extracted from {@code MinPacingRateParam#computeTarget}.
 *
 * <p>The regression this pins: the policy raised the floor whenever the router was
 * both busy and lossy - exactly the state a bulk download creates - and live it
 * reached 192 KB/s against the 16 KB/s default, climbing toward the 256 KB/s
 * bracket ceiling. Because the pacing rate was
 * {@code max(window-derived pace, floor)}, that floor replaced congestion control
 * on every stream, so the pacer released each window faster than the path could
 * acknowledge it and the resulting loss raised the floor again.
 *
 * @since 0.9.72+
 */
public class TunerMinPacingRateTargetTest {

    /** The param's bracket and step: 1-256 KB/s, 4 KB/s per cycle. */
    private static final int MIN = 1;
    private static final int MAX = 256;
    private static final int STEP = 4;
    private static final int DEFAULT = Tuner.DEFAULT_MIN_PACING_KBPS;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one. The policy under test is static and holds no state.
     *
     * @since 0.9.72+
     */
    public TunerMinPacingRateTargetTest() {}

    /** No send events is no signal; the cycle holds rather than guessing. */
    @Test
    public void noSendTrafficHoldsTheValue() {
        assertEquals(192, Tuner.nextMinPacingRateKBps(192, Double.NaN, false, false,
                                                      MIN, MAX, DEFAULT, STEP));
    }

    /**
     * The live case: 150 KB/s of sends with streams retransmitting used to raise
     * the floor, which is the loop this param was stuck in. It now lowers.
     */
    @Test
    public void busyAndLossyLowersTheFloor() {
        assertEquals(188, Tuner.nextMinPacingRateKBps(192, 150, false, true,
                                                      MIN, MAX, DEFAULT, STEP));
        // congestion alone, on a slow link, lowered before too - and still does
        assertEquals(188, Tuner.nextMinPacingRateKBps(192, 10, true, false,
                                                      MIN, MAX, DEFAULT, STEP));
    }

    /** A healthy busy router has no use for the floor either. */
    @Test
    public void busyAndHealthyLowersTheFloor() {
        assertEquals(188, Tuner.nextMinPacingRateKBps(192, 150, false, false,
                                                      MIN, MAX, DEFAULT, STEP));
    }

    /**
     * Repeated cycles converge on the shipped default and stop there: the bracket
     * floor of 1 KB/s is not a target, and nothing observed argues for going past
     * the value the streaming module ships.
     */
    @Test
    public void convergesToTheShippedDefaultAndStops() {
        int value = 192;
        for (int cycle = 0; cycle < 100; cycle++)
            value = Tuner.nextMinPacingRateKBps(value, 150, true, true, MIN, MAX, DEFAULT, STEP);
        assertEquals(DEFAULT, value);
    }

    /** A healthy router whose streams barely move raises the floor one step. */
    @Test
    public void quietRouterRaisesTheFloor() {
        assertEquals(DEFAULT + STEP, Tuner.nextMinPacingRateKBps(DEFAULT, 10, false, false,
                                                                 MIN, MAX, DEFAULT, STEP));
    }

    /**
     * The raise is capped well under the bracket ceiling: past a few times the
     * pace the floor engages at, it is a burst rather than a rescue.
     */
    @Test
    public void raiseIsCappedBelowTheBracketCeiling() {
        int value = DEFAULT;
        for (int cycle = 0; cycle < 100; cycle++)
            value = Tuner.nextMinPacingRateKBps(value, 10, false, false, MIN, MAX, DEFAULT, STEP);
        assertEquals(Tuner.PACING_RATE_TUNER_MAX_KBPS, value);
        assertTrue(Tuner.PACING_RATE_TUNER_MAX_KBPS < MAX);
    }

    /** A value an operator set above the cap is left alone, never pulled to it. */
    @Test
    public void operatorValueAboveTheCapIsLeftAlone() {
        assertEquals(192, Tuner.nextMinPacingRateKBps(192, 10, false, false,
                                                      MIN, MAX, DEFAULT, STEP));
    }

    /** The dead band between the two bandwidth signals moves nothing. */
    @Test
    public void middleBandwidthHolds() {
        double mid = (Tuner.PACING_LOW_BANDWIDTH_KBPS + Tuner.PACING_HIGH_BANDWIDTH_KBPS) / 2;
        assertEquals(64, Tuner.nextMinPacingRateKBps(64, mid, false, false,
                                                     MIN, MAX, DEFAULT, STEP));
    }

    /** One cycle never moves more than one step, and never leaves the bracket. */
    @Test
    public void movementIsBoundedByOneStep() {
        for (double bw = 0; bw <= 400; bw += 10) {
            for (boolean congested : new boolean[] {false, true}) {
                for (boolean dropping : new boolean[] {false, true}) {
                    int next = Tuner.nextMinPacingRateKBps(128, bw, congested, dropping,
                                                           MIN, MAX, DEFAULT, STEP);
                    assertTrue("bw " + bw + " gave " + next, Math.abs(next - 128) <= STEP);
                    assertTrue("bw " + bw + " out of bracket: " + next, next >= MIN && next <= MAX);
                }
            }
        }
    }
}
