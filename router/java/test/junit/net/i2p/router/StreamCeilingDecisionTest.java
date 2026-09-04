package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Decision tests for {@link Tuner#streamCeilingTarget}, the demand-following
 * stream-cap ceiling. These exercise the pure static decision method directly and
 * need no live RouterContext (unlike the class-level setup in {@link TunerTest}).
 *
 * <p>The cap is a headroom knob, not a DoS throttle: it raises on legitimate demand
 * and cap-refusals (so locally hosted services and the HTTP proxy keep flowing) and
 * only steps DOWN under genuine router starvation (distress). The floor stays
 * generous because abuse is shed at the refusal layer by the SYN-rate gate and the
 * 24h auto-ban, so a healthy tracker converges to the user's configured ceiling.
 *
 * @since 0.9.71+
 */
public class StreamCeilingDecisionTest {

    /** Baseline Tuner config for MAX_STREAMS. */
    private static final int MIN = 16;
    private static final int MAX = 512;
    private static final int STEP = 32;

    // ---- refuse-driven raise (minimize rejections) ----

    @Test
    public void testRefusingRaisesTwoSteps() {
        assertEquals(320, Tuner.streamCeilingTarget(Double.NaN, 256, MIN, MAX, STEP, true, false));
    }

    @Test
    public void testRefusingClampedAtCeiling() {
        assertEquals(MAX, Tuner.streamCeilingTarget(Double.NaN, 480, MIN, MAX, STEP, true, false));
    }

    /** Refusals never raise a box already in distress. */
    @Test
    public void testRefusingUnderDistressDoesNotRaise() {
        int target = Tuner.streamCeilingTarget(Double.NaN, 256, MIN, MAX, STEP, true, true);
        assertTrue("refusing under distress must not raise, got " + target, target <= 256);
        assertEquals(224, target); // distress branch: one step down
    }

    // ---- demand-following headroom ----

    @Test
    public void testDemandClimbingAddsHeadroom() {
        // active 256 within 2 steps of current 288 -> +STEP
        assertEquals(320, Tuner.streamCeilingTarget(256, 288, MIN, MAX, STEP, false, false));
    }

    @Test
    public void testDemandAtCapAddsHeadroom() {
        // active == current -> +STEP
        assertEquals(288 + STEP, Tuner.streamCeilingTarget(288, 288, MIN, MAX, STEP, false, false));
    }

    /** Demand well under the ceiling falls to the relax-up path. */
    @Test
    public void testDemandLowRelaxesUp() {
        assertEquals(320, Tuner.streamCeilingTarget(16, 288, MIN, MAX, STEP, false, false));
    }

    // ---- deny ceiling shrink on healthy / idle / demand ----

    @Test
    public void testIdleRelaxesUpTowardUserCeiling() {
        assertEquals(320, Tuner.streamCeilingTarget(Double.NaN, 288, MIN, MAX, STEP, false, false));
    }

    @Test
    public void testHealthyRelaxesToMax() {
        assertEquals(MAX, Tuner.streamCeilingTarget(Double.NaN, 480, MIN, MAX, STEP, false, false));
    }

    // ---- distress shrink (only super-high latency), floored ----

    @Test
    public void testDistressStepsDownOneStep() {
        assertEquals(256, Tuner.streamCeilingTarget(Double.NaN, 288, MIN, MAX, STEP, false, true));
    }

    @Test
    public void testDistressFloorEnforcedNeverWedgeLocalService() {
        assertEquals(MIN, Tuner.streamCeilingTarget(Double.NaN, 32, MIN, MAX, STEP, false, true));
    }

    // ---- step-size generality ----

    @Test
    public void testNonstandardStepRefusing() {
        // step 10, refuse -> +20
        assertEquals(130, Tuner.streamCeilingTarget(Double.NaN, 110, 10, 512, 10, true, false));
    }

    @Test
    public void testNonstandardStepDistress() {
        // step 10, distress -> -10
        assertEquals(100, Tuner.streamCeilingTarget(Double.NaN, 110, 10, 512, 10, false, true));
    }
}