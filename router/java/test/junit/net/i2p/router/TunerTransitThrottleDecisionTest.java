package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The decision behind the three per-participant transit limits in
 * {@link net.i2p.router.tunnel.pool.ParticipatingThrottler} - the minimum, the
 * maximum and the target percentage.
 *
 * <p>All three are tuned from measurements that remote peers take part in: the
 * throttle's accept, reject and drop counts are the decisions the throttle made
 * about the peers asking to use it. A tight throttle was read as a reason to
 * raise the allowance with no bound on how far that could go, and it was tested
 * before the router's own stress signals, so a flood of transit requests raised
 * the limit while the router was already overloaded and kept the raised value in
 * autotune.config for every later session. The lab left one router with a
 * per-peer allowance about 3.3 times the shipped one this way.
 *
 * @since 0.9.72+
 */
public class TunerTransitThrottleDecisionTest {

    /** Runs the decisions; it holds no state. */
    public TunerTransitThrottleDecisionTest() {}

    /** Range and step of the max limit as shipped. */
    private static final int MIN = 50;
    private static final int MAX = 1000;
    private static final int STEP = 8;
    /** The value the operator configured, the middle of the shipped range. */
    private static final int CONFIGURED = 100;

    // ---- peerDrivenCeiling -------------------------------------------------

    /** A raise resting only on peer demand may go twice the configured value. */
    @Test
    public void peerDemandReachesTwiceTheConfiguredValue() {
        assertEquals(200, Tuner.peerDrivenCeiling(CONFIGURED, MAX));
        assertEquals(500, Tuner.peerDrivenCeiling(250, MAX));
    }

    /** The param's own range maximum still binds a configured value near it. */
    @Test
    public void theRangeMaximumBindsBeforeTheMultiplier() {
        assertEquals(150, Tuner.peerDrivenCeiling(100, 150));
        assertEquals(MAX, Tuner.peerDrivenCeiling(900, MAX));
    }

    // ---- transitThrottleIsTight --------------------------------------------

    /** Rejecting more than 70% of what it is asked is a tight throttle. */
    @Test
    public void aThrottleRejectingMostRequestsIsTight() {
        assertTrue(Tuner.transitThrottleIsTight(29, 71, 0));
        assertTrue(Tuner.transitThrottleIsTight(0, 0, 100));
    }

    /** The bound is exclusive: exactly 30% accepts is a throttle doing its job. */
    @Test
    public void exactlyThirtyPercentAcceptsIsNotTight() {
        assertFalse(Tuner.transitThrottleIsTight(30, 70, 0));
    }

    /** A window with no throttle decisions says nothing about how tight it is. */
    @Test
    public void noThrottleActivityIsNotTight() {
        assertFalse(Tuner.transitThrottleIsTight(0, 0, 0));
    }

    /** A stat that was never written is not a measurement, in either direction. */
    @Test
    public void missingCountsAreNotTight() {
        double nan = Double.NaN;
        assertFalse(Tuner.transitThrottleIsTight(nan, 70, 0));
        assertFalse(Tuner.transitThrottleIsTight(29, nan, 0));
        assertFalse(Tuner.transitThrottleIsTight(29, 70, nan));
    }

    // ---- transitThrottleTarget ---------------------------------------------

    /**
     * The case the ratchet ran on: peers flooding transit requests make the
     * throttle tight, and the router is stressed by the same flood. Stress wins,
     * so the allowance walks down instead of up.
     */
    @Test
    public void stressBeatsATightThrottle() {
        assertEquals(CONFIGURED - STEP,
                     Tuner.transitThrottleTarget(CONFIGURED, MIN, MAX, STEP, 200, false, true, true));
    }

    /** Stress alone lowers, and stops at the range minimum. */
    @Test
    public void stressLowersToTheRangeMinimum() {
        assertEquals(92, Tuner.transitThrottleTarget(100, MIN, MAX, STEP, 200, false, true, false));
        assertEquals(MIN, Tuner.transitThrottleTarget(52, MIN, MAX, STEP, 200, false, true, false));
    }

    /** A tight throttle raises, but only one step. */
    @Test
    public void aTightThrottleRaisesOneStep() {
        assertEquals(108, Tuner.transitThrottleTarget(100, MIN, MAX, STEP, 200, false, false, true));
    }

    /** The peer-driven ceiling stops the walk up, whatever the step would do. */
    @Test
    public void aTightThrottleCannotPassThePeerCeiling() {
        assertEquals(200, Tuner.transitThrottleTarget(195, MIN, MAX, STEP, 200, false, false, true));
        assertEquals(200, Tuner.transitThrottleTarget(200, MIN, MAX, STEP, 200, false, false, true));
        assertEquals(200, Tuner.transitThrottleTarget(300, MIN, MAX, STEP, 200, false, false, true));
    }

    /**
     * Our own headroom - builds succeeding with nothing queueing - is not peer
     * demand and may take the limit to the range maximum.
     */
    @Test
    public void ourOwnHeadroomReachesTheRangeMaximum() {
        assertEquals(108, Tuner.transitThrottleTarget(100, MIN, MAX, STEP, 200, true, false, false));
        assertEquals(1000, Tuner.transitThrottleTarget(995, MIN, MAX, STEP, 200, true, false, false));
    }

    /** Nothing to act on leaves the limit where it is. */
    @Test
    public void noSignalLeavesTheLimitAlone() {
        assertEquals(400, Tuner.transitThrottleTarget(400, MIN, MAX, STEP, 200, false, false, false));
    }
}
