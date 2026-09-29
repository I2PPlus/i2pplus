package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the peer-selection activity-window policy:
 * {@link Tuner#targetWindowMultiplier} and
 * {@link Tuner#nextWindowMultiplier}.
 *
 * <p>The regression: the old policy required
 * {@code degraded && (buildsExpiring || testsFailing)}, where both cross-refs
 * are single-period rate samples that return NaN on an empty period. A build
 * slump producing only intermittent expire or test-failure events left the
 * multiplier pinned at its floor of 1 while every pool sat below target — the
 * one control meant to break the recency-pruning loop was inert during exactly
 * the slump it exists to relieve.
 *
 * @since 0.9.71+
 */
public class TunerActivityWindowTest {

    private static final int MIN = 1;
    private static final int MAX = 4;
    private static final int STEP = 1;

    private static int target(double observed) {
        return Tuner.targetWindowMultiplier(observed, MIN, MAX);
    }

    private static int invokeTarget(double observed, int min, int max) {
        return Tuner.targetWindowMultiplier(observed, min, max);
    }

    private static int next(int current, double observed, int healthyCycles) {
        return Tuner.nextWindowMultiplier(current, observed, healthyCycles, MIN, MAX, STEP);
    }

    // ---- targetWindowMultiplier: direction and bounds ----

    @Test
    public void healthyRateGetsNoWidening() {
        assertEquals(MIN, target(1.0));
        assertEquals(MIN, target(0.90));
        assertEquals(MIN, target(0.80));
    }

    @Test
    public void degradedRateGetsFullWidening() {
        assertEquals("at or below the degraded boundary the window is fully widened",
                     MAX, target(0.65));
        assertEquals(MAX, target(0.649));
    }

    @Test
    public void worseThanDegradedStaysAtCeiling() {
        assertEquals(MAX, target(0.569));
        assertEquals(MAX, target(0.30));
        assertEquals(MAX, target(0.0));
    }

    /**
     * The bug this caught: the span was computed as
     * {@code DEGRADED_RATE - HEALTHY_RATE}, which is negative, so the deficit
     * inverted and a worse build rate produced a <em>smaller</em> multiplier.
     */
    @Test
    public void worseRateNeverMeansLessWidening() {
        double[] rates = { 0.80, 0.75, 0.70, 0.65, 0.60, 0.50, 0.30, 0.0 };
        for (int i = 1; i < rates.length; i++) {
            assertTrue("rate " + rates[i] + " (" + target(rates[i]) + ") must widen at least as much"
                       + " as rate " + rates[i - 1] + " (" + target(rates[i - 1]) + ")",
                       target(rates[i]) >= target(rates[i - 1]));
        }
    }

    @Test
    public void midBandWidensProportionally() {
        // Between the boundaries the mapping is continuous, not stepped.
        int atHigh = target(0.79);
        int atMid = target(0.725);
        int atLow = target(0.66);
        assertTrue("expected a ramp between the boundaries", atHigh < atMid && atMid < atLow);
    }

    @Test
    public void neverOutsideTheConfiguredBounds() {
        for (double r = -1; r <= 2; r += 0.01) {
            int t = target(r);
            assertTrue("rate " + r + " -> " + t, t >= MIN && t <= MAX);
        }
    }

    @Test
    public void nanRateFallsBackToTheFloor() {
        assertEquals(MIN, target(Double.NaN));
    }

    @Test
    public void degenerateBoundsReturnTheFloor() {
        assertEquals(2, invokeTarget(0.3, 2, 2));
        // Inverted bounds (min > max) cannot express a widening, so the floor
        // is returned; the caller clamps anyway, and the point is not to throw.
        assertEquals(4, invokeTarget(0.3, 4, 2));
    }

    // ---- nextWindowMultiplier: asymmetric response ----

    @Test
    public void degradedWidensInOneStep() {
        // The reported case: 57% success with the multiplier stuck at 1.
        assertEquals("a degraded rate must widen immediately, not after a cycle",
                     MAX, next(MIN, 0.569, 0));
    }

    @Test
    public void healthyHoldsUntilTheCycleBudgetIsSpent() {
        // healthyCycles counts PRIOR healthy cycles, so 0 and 1 are the first
        // and second and must hold; 2 means this is the third and tightens.
        assertEquals("one healthy cycle must not withdraw relief",
                     MAX, next(MAX, 1.0, 0));
        assertEquals("two healthy cycles still hold",
                     MAX, next(MAX, 1.0, 1));
    }

    @Test
    public void healthyDecaysAfterThreeCycles() {
        assertEquals("the third consecutive healthy cycle tightens by one step",
                     MAX - STEP, next(MAX, 1.0, 2));
    }

    @Test
    public void decayIsGradualNotASnapBack() {
        int window = MAX;
        window = next(window, 1.0, 0);
        window = next(window, 1.0, 1);
        window = next(window, 1.0, 2);
        assertEquals(MAX - 1, window);
        // Still above the floor, so the pool keeps some relief.
        assertTrue(window > MIN);
    }

    @Test
    public void healthyCyclesResetWhenDegradedAgain() {
        assertEquals(MAX, next(MIN, 0.569, 0));
        // Two healthy cycles do not yet justify tightening...
        assertEquals(MAX, next(MAX, 1.0, 0));
        assertEquals(MAX, next(MAX, 1.0, 1));
        // ...and a degraded reading reopens the window, resetting the budget.
        assertEquals(MAX, next(MAX, 0.60, 0));
    }

    @Test
    public void nanRateHoldsTheWindow() {
        assertEquals(MAX, next(MAX, Double.NaN, 0));
        assertEquals(MIN, next(MIN, Double.NaN, 0));
    }

    @Test
    public void neverExceedsCeilingOrDropsBelowFloor() {
        int window = MIN;
        double[] rates = { 0.0, 0.1, 0.4, 0.569, 0.72, 0.79, 0.85, 1.0, 0.3, 0.95 };
        int cycles = 0;
        for (double r : rates) {
            window = next(window, r, cycles);
            assertTrue("window " + window + " out of bounds after rate " + r,
                       window >= MIN && window <= MAX);
            cycles = r >= 0.80 ? cycles + 1 : 0;
        }
    }

    @Test
    public void aFullHealthyRunReturnsToTheFloor() {
        int window = MAX;
        for (int i = 0; i < 12; i++) {
            window = next(window, 1.0, i);
        }
        assertEquals("sustained health must walk the window back to the floor",
                     MIN, window);
    }
}
