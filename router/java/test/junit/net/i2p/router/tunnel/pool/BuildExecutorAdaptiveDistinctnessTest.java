package net.i2p.router.tunnel.pool;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that the adaptive build ladder stays genuinely adaptive.
 *
 * <p>The defect these pin: the base (Tuner range 10s-30s) and the delta table
 * ({@code adaptiveTimeoutDelta}, spanning 8s from -3s to +5s) were both clamped to
 * the same endpoints, so the combined value collapsed at both ends. At base 30s the
 * GOOD, MODERATE and LOW branches all clamped back to 30s; at base 10s the FAST
 * branch clamped up to 10s. Logged samples sat pinned at the 30s ceiling 75% of the
 * time, which is what a degenerate ladder looks like from the outside.
 *
 * <p>These assert the property, not specific numbers, so widening a constant later
 * does not require rewriting them. One branch is exempt by design: GOOD returns a
 * delta of 0 ("hold steady"), so it is not required to move the value.
 *
 * @since 0.9.71+
 */
public class BuildExecutorAdaptiveDistinctnessTest {

    /** The Tuner's own range for the build request timeout, in ms. */
    private static final int[] BASE_RANGE_MS = {
        10000, 11000, 12000, 13000, 14000, 15000, 16000, 17000, 18000, 19000,
        20000, 21000, 22000, 23000, 24000, 25000, 26000, 27000, 28000, 29000, 30000
    };

    /** One success rate per branch, chosen to sit inside each band. */
    private static final double[] SUCCESS_RATES = {0.95, 0.75, 0.60, 0.20};

    private static final String[] BAND_NAMES = {"FAST", "GOOD", "MODERATE", "LOW"};

    /**
     *  Every branch must produce a distinct value at some base in the Tuner's range.
     *
     *  <p>This is the property that was broken. Asserting it per-base would be too
     *  strict -- some branch pairs legitimately coincide at a given base -- but
     *  requiring each band to be reachable somewhere is exactly what a clamp at the
     *  base's own endpoints destroys.
     */
    @Test
    public void everyNonSteadyBranchIsReachableSomewhere() {
        int unreachable = 0;
        for (int i = 0; i < SUCCESS_RATES.length; i++) {
            long delta = BuildExecutor.adaptiveTimeoutDelta(SUCCESS_RATES[i]);
            // GOOD returns 0 by design -- it means "hold steady", not "move". Only
            // the branches that intend to shift the deadline are required to.
            if (delta == 0) {continue;}
            boolean seen = false;
            for (int base : BASE_RANGE_MS) {
                if (BuildExecutor.combineAdaptiveTimeout(base, SUCCESS_RATES[i]) != (long)base) {seen = true; break;}
            }
            if (!seen) {
                unreachable++;
                System.out.println("  branch " + BAND_NAMES[i] + " (delta=" + delta
                                   + ") is clamped away across the whole base range");
            }
        }
        assertEquals("every branch that intends to move the deadline must be able to",
                     0, unreachable);
    }

    /**
     *  The FAST branch must actually shorten the budget somewhere.
     *
     *  <p>Guards the specific symptom: with the floor at the base's own value, the
     *  -3s reduction was clamped straight back and the router could never shorten a
     *  build deadline on a healthy network.
     */
    @Test
    public void fastBranchCanShortenTheBudget() {
        long delta = BuildExecutor.adaptiveTimeoutDelta(0.95);
        assertTrue("FAST branch should reduce", delta < 0);
        boolean shortened = false;
        for (int base : BASE_RANGE_MS) {
            if (BuildExecutor.combineAdaptiveTimeout(base, 0.95) < base) {shortened = true; break;}
        }
        assertTrue("no base in the Tuner's range lets FAST_NETWORK_REDUCTION_MS take effect",
                   shortened);
    }

    /**
     *  At the top of the Tuner range, the failure branches must stay distinguishable.
     *
     *  <p>This is the case that was pinned 75% of the time: base 30s with GOOD,
     *  MODERATE and LOW all clamping to 30s.
     */
    @Test
    public void failureBranchesDistinctAtTopOfRange() {
        int top = BASE_RANGE_MS[BASE_RANGE_MS.length - 1];
        Set<Long> seen = new HashSet<Long>();
        for (double rate : SUCCESS_RATES) {
            seen.add(BuildExecutor.combineAdaptiveTimeout(top, rate));
        }
        assertEquals("GOOD/MODERATE/LOW must not all collapse at base=" + top
                     + "s; observed " + seen, SUCCESS_RATES.length, seen.size());
    }

    /**
     *  At the bottom of the Tuner range, the reduction must stay distinguishable.
     *
     *  <p>The mirror image: base 10s with FAST clamping up to 10s.
     */
    @Test
    public void fastBranchDistinctAtBottomOfRange() {
        int floor = BASE_RANGE_MS[0];
        long fast = BuildExecutor.combineAdaptiveTimeout(floor, 0.95);
        long good = BuildExecutor.combineAdaptiveTimeout(floor, 0.75);
        assertNotEquals("FAST must not clamp up onto the floor at base=" + floor
                        + "s; observed " + fast, good, fast);
    }

    /**
     *  Four distinct success rates must be able to yield four distinct budgets.
     *
     *  <p>Existence rather than per-base: somewhere in the range the ladder must
     *  discriminate all four bands at once, which a degenerate clamp makes impossible
     *  at every base.
     */
    @Test
    public void allFourBandsAreSimultaneouslyDistinct() {
        int best = 0;
        for (int base : BASE_RANGE_MS) {
            Set<Long> seen = new HashSet<Long>();
            for (double rate : SUCCESS_RATES) {
                seen.add(BuildExecutor.combineAdaptiveTimeout(base, rate));
            }
            best = Math.max(best, seen.size());
        }
        assertEquals("the ladder must discriminate all four bands somewhere in the "
                     + "base range", SUCCESS_RATES.length, best);
    }

    /**
     *  The ladder must be monotone non-decreasing as success falls.
     *
     *  <p>A worse network should never get a shorter deadline. This held before and
     *  must survive the unclamp; it is the one property a naive fix would break.
     */
    @Test
    public void shorterDeadlinesForWorseNetworks() {
        int base = 15000;
        long previous = Long.MIN_VALUE;
        for (double rate : SUCCESS_RATES) {          // descending: best to worst
            long value = BuildExecutor.combineAdaptiveTimeout(base, rate);
            assertTrue("value must not decrease as success falls (rate=" + rate
                       + "): " + value + " < " + previous, value >= previous);
            previous = value;
        }
    }
}
