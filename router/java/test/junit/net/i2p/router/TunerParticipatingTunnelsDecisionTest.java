package net.i2p.router;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The decision behind the cap on how many tunnels this router offers for
 * transit, {@code router.maxParticipatingTunnels}.
 *
 * <p>The cap is raised while the transit tunnels in use approach it, which is a
 * measurement about what peers are asking for. It used to be lowered only under
 * heap pressure above 90%, so a router that carried one busy stretch kept the
 * raised cap for the rest of the session however quiet it became, and the raised
 * cap went into autotune.config for the next session as well. It now gives the
 * headroom back while transit usage stays well under it, down to the value the
 * operator configured and no further.
 *
 * @since 0.9.72+
 */
public class TunerParticipatingTunnelsDecisionTest {

    /** Runs the decisions; it holds no state. */
    public TunerParticipatingTunnelsDecisionTest() {}

    /** Range and step of the cap as shipped. */
    private static final int MIN = 500;
    private static final int MAX = 20000;
    private static final int STEP = 500;
    /** The cap the operator configured. */
    private static final int CONFIGURED = 1000;

    /**
     * Runs one pass with a healthy, uncongested, idle system and no heap
     * pressure, so the transit usage alone decides.
     *
     * @param current the cap as it stands
     * @param tunnelCount transit tunnels in use over the ten-minute average
     * @return the value the pass moves the cap to
     */
    private static int quiet(int current, double tunnelCount) {
        return Tuner.participatingTunnelsTarget(current, MIN, CONFIGURED, MAX, STEP, tunnelCount,
                                                true, false, false, 0.5);
    }

    /** Approaching the cap at 70% raises it by twice the step. */
    @Test
    public void nearingTheCapRaisesItAtDoubleStep() {
        assertEquals(2000, quiet(1000, 800));
        assertEquals(2000, quiet(1000, 701));
    }

    /** Past half the cap there is less urgency, and the step is a single one. */
    @Test
    public void overHalfTheCapRaisesItOneStep() {
        assertEquals(1500, quiet(1000, 600));
        assertEquals(1500, quiet(1000, 501));
    }

    /** Between a quarter and a half of the cap the pass has nothing to say. */
    @Test
    public void midRangeUsageLeavesTheCapAlone() {
        assertEquals(1000, quiet(1000, 350));
        assertEquals(1000, quiet(1000, 251));
    }

    /**
     * Well under the cap gives the headroom back, one step at a time, and stops
     * at the configured value.
     */
    @Test
    public void quietTransitWalksBackToTheConfiguredCap() {
        assertEquals(4500, quiet(5000, 100));
        assertEquals(1500, quiet(2000, 100));
        assertEquals(1000, quiet(1200, 100));
        assertEquals(1000, quiet(1000, 0));
    }

    /**
     * A cap already at or below the configured value is not raised by quiet
     * transit: only the transit peers approaching it, or heap pressure easing,
     * take it back up.
     */
    @Test
    public void quietTransitNeverUndercutsOrRebuildsTheConfiguredCap() {
        assertEquals(800, quiet(800, 0));
        assertEquals(500, quiet(500, 0));
    }

    /**
     * Heap pressure is the one thing that takes the cap under the configured
     * value, and it goes as low as the range minimum.
     */
    @Test
    public void heapPressureGoesBelowTheConfiguredCap() {
        assertEquals(500, Tuner.participatingTunnelsTarget(1000, MIN, CONFIGURED, MAX, STEP, 100,
                                                           true, false, false, 0.95));
        assertEquals(500, Tuner.participatingTunnelsTarget(600, MIN, CONFIGURED, MAX, STEP, 100,
                                                           true, false, false, 0.95));
    }

    /** Heap pressure at the bound itself is not yet pressure. */
    @Test
    public void heapPressureAtTheBoundDoesNotLowerTheCap() {
        assertEquals(1000, Tuner.participatingTunnelsTarget(1000, MIN, CONFIGURED, MAX, STEP, 400,
                                                            true, false, false, 0.9));
    }

    /**
     * A busy stretch that is not healthy does not raise the cap, and usage in
     * the middle of the range does not lower it either.
     */
    @Test
    public void anUnhealthyNetworkDoesNotRaiseTheCap() {
        assertEquals(1000, Tuner.participatingTunnelsTarget(1000, MIN, CONFIGURED, MAX, STEP, 900,
                                                            false, false, false, 0.5));
        assertEquals(1000, Tuner.participatingTunnelsTarget(1000, MIN, CONFIGURED, MAX, STEP, 900,
                                                            true, true, false, 0.5));
        assertEquals(1000, Tuner.participatingTunnelsTarget(1000, MIN, CONFIGURED, MAX, STEP, 900,
                                                            true, false, true, 0.5));
    }

    /** A cap of zero cannot be divided into, and is not moved by usage. */
    @Test
    public void aZeroCapIsLeftAlone() {
        assertEquals(0, quiet(0, 0));
    }

    /** The raise is held to the range maximum. */
    @Test
    public void theRaiseStopsAtTheRangeMaximum() {
        assertEquals(MAX, quiet(MAX, MAX));
        assertEquals(MAX, quiet(19600, 19000));
    }
}
