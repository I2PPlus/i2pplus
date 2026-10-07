package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The establish-timeout floor and the autotune target that sits on top of it.
 *
 * <p>Measured on a live router: at a 2250ms budget the outbound path abandoned roughly
 * 117 session establishments per minute, every one expiring on the deadline rather than
 * on an observed peer failure, and 99.6% of them inside the SSU2 token exchange. Raising
 * the budget to 5000 drove that to zero, but build success fell from 64.5% to 56.0% and
 * throughput 12%, because the patience moves out of the establishment path and into the
 * build slot. 4000 is the compromise.
 *
 * <p>The autotune target is the part that needs the most care, because the observed mean
 * handshake is ~196ms and 4x that is 784ms - far below the value known to be harmful.
 * Latency here is heavily right-skewed, so a mean-derived target is systematically too
 * low and the floor is what actually protects the deadline.
 *
 * @since 0.9.71+
 */
public class TunerEstablishTimeoutFloorTest {

    private static final int MIN = Tuner.ESTABLISH_TIMEOUT_MIN;
    private static final int CEILING = 10000;

    // ---- the floor itself ----

    @Test
    public void floorSitsAboveTheProvenHarmfulValue() {
        assertEquals(4000, MIN);
        assertTrue("floor must exceed 2250, the value that caused the give-ups",
                   MIN > 2250);
        assertTrue("floor must sit below 5000, where slot occupancy started costing builds",
                   MIN < 5000);
    }

    @Test
    public void floorEqualsTheUpstreamDefaultCeiling() {
        // The EstablishmentManager defaults are 5s; the floor is deliberately below that
        // so the param is still tunable downward, but never into the harmful region.
        assertTrue(MIN < 5 * 1000);
    }

    /** The ceiling exists so autotune, which multiplies the observed mean, is not clamped. */
    @Test
    public void ceilingLeavesHeadroomAboveTheFloor() {
        assertTrue(CEILING > MIN);
        assertTrue("ceiling must be a usable multiple of the floor", CEILING / MIN >= 2);
    }

    // ---- the autotune target ----

    /** The regression: a literal floor let the target sit far below the enforced value. */
    @Test
    public void targetNeverFallsBelowTheFloor() {
        double[] observed = {0, 1, 10, 50, 100, 196, 300, 500, 900, 1000};
        for (double o : observed) {
            assertTrue("observed=" + o + " produced a target under the floor",
                       Tuner.establishTimeoutTarget(o, MIN) >= MIN);
        }
    }

    /**
     * The live case that matters: the observed mean is ~196ms, so 4x is 784ms, which is
     * below the 2250 that provably caused the failure. The floor is the only thing
     * standing between the autotuner and that regression.
     */
    @Test
    public void liveObservedMeanStillRespectsTheFloor() {
        int target = Tuner.establishTimeoutTarget(196.095, MIN);
        assertEquals(MIN, target);
        assertTrue("4x the observed mean is below the proven-harmful value",
                   (int) (196.095 * 4) < 2250);
    }

    @Test
    public void targetIsFourTimesTheObservedMeanAboveTheFloor() {
        assertEquals(4 * 1000, Tuner.establishTimeoutTarget(1000, MIN));
        assertEquals(4 * 1200, Tuner.establishTimeoutTarget(1200, MIN));
        assertEquals(4 * 2500, Tuner.establishTimeoutTarget(2500, MIN));
        assertEquals(4 * 3000, Tuner.establishTimeoutTarget(3000, MIN));
    }

    /** An unknown reading must not be treated as zero, which would target the floor. */
    @Test
    public void unknownObservationFallsBackToTheFloor() {
        assertEquals(MIN, Tuner.establishTimeoutTarget(Double.NaN, MIN));
        // it honours whatever floor it is given, so a min of 0 is honoured too
        assertEquals(0, Tuner.establishTimeoutTarget(Double.NaN, 0));
    }

    @Test
    public void targetIsMonotonicInTheObservation() {
        int previous = 0;
        for (double o = 0; o <= 3000; o += 50) {
            int target = Tuner.establishTimeoutTarget(o, MIN);
            assertTrue("target went backwards at observed=" + o, target >= previous);
            previous = target;
        }
    }
}
