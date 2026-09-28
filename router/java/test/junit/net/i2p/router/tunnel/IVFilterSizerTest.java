package net.i2p.router.tunnel;

import static org.junit.Assert.*;

import net.i2p.router.util.DecayingBloomFilter;

import org.junit.Test;

/**
 *  Tests for the filter sizing decision.
 *
 *  <p>Exercises {@link BloomFilterIVValidator.IVFilterSizer#computeTargetM}
 *  directly, so the policy is tested without a router. The sizing helpers it
 *  relies on are checked against the table in
 *  {@code DecayingBloomFilter.DEFAULT_M}, which is the model this is meant to
 *  reproduce.
 *
 *  @since 0.9.71+
 */
public class IVFilterSizerTest {

    private static final long GB = 1024L * 1024L * 1024L;
    /** Budget a 128 MB heap gives the filter at a 10% share. */
    private static final long BUDGET_128M = (long) (128L * 1024 * 1024 * 0.10);

    // ==================== sizing helpers ====================

    /**
     *  mForEntries must reproduce the shipped table. At n=614400 the table
     *  records 1.48E-3 for m=23, so mForEntries(614400, 1.48E-3) has to land
     *  on 24 (the exponent that achieves it) and mForEntries must never
     *  under-size, which is what would cost good tunnel data.
     */
    @Test
    public void testSizingMatchesShippedTable() {
        assertEquals(24, DecayingBloomFilter.mForEntries(614400, 1.48E-3));
        // the table's m=23 row is 1.49E-3 measured, so a 1.5E-3 target at this
        // load must not ask for less than 23
        assertTrue(DecayingBloomFilter.mForEntries(614400, 1.5E-3) >= 23);
    }

    /** A larger target rate must never demand a larger filter. */
    @Test
    public void testTighterTargetNeedsMoreBits() {
        int loose = DecayingBloomFilter.mForEntries(600000, 1E-2);
        int tight = DecayingBloomFilter.mForEntries(600000, 1E-5);
        assertTrue("tighter target must need more bits", tight > loose);
    }

    /** More entries at the same target must never need fewer bits. */
    @Test
    public void testMoreEntriesNeedsMoreBits() {
        int small = DecayingBloomFilter.mForEntries(100000, 1E-3);
        int big = DecayingBloomFilter.mForEntries(1000000, 1E-3);
        assertTrue(big > small);
    }

    /** Degenerate inputs must not throw or return nonsense. */
    @Test
    public void testSizingDegenerateInputs() {
        assertEquals(0, DecayingBloomFilter.mForEntries(0, 1E-3));
        assertEquals(0, DecayingBloomFilter.mForEntries(-5, 1E-3));
        assertEquals(0, DecayingBloomFilter.mForEntries(1000, 0d));
        assertEquals(0, DecayingBloomFilter.mForEntries(1000, -1d));
        assertEquals(0, DecayingBloomFilter.mForEntries(1000, 1d));
        assertEquals(0, DecayingBloomFilter.mForEntries(1000, Double.NaN));
    }

    /** mForBudget must respect the key-width ceiling and the empty case. */
    @Test
    public void testBudgetSizing() {
        assertEquals(0, DecayingBloomFilter.mForBudget(0));
        assertEquals(0, DecayingBloomFilter.mForBudget(-1));
        // 2^(m-2) bytes must fit the budget
        for (long budget : new long[] { 1L << 20, 1L << 22, 1L << 25, 1L << 27, GB }) {
            int m = DecayingBloomFilter.mForBudget(budget);
            assertTrue("m " + m + " must fit " + budget, (1L << (m - 2)) <= budget);
            assertTrue("m " + m + " must be the largest that fits",
                       m == DecayingBloomFilter.MAX_M || (1L << (m - 1)) > budget);
        }
    }

    /** A budget large enough for anything must still respect MAX_M. */
    @Test
    public void testBudgetSizingCapped() {
        assertEquals(DecayingBloomFilter.MAX_M, DecayingBloomFilter.mForBudget(Long.MAX_VALUE / 2));
    }

    /** Memory accounting must match the documented 2^(m-2) for two buffers. */
    @Test
    public void testMemoryBytes() {
        assertEquals(1L << 20, memFor(22));
        assertEquals(1L << 21, memFor(23));
        assertEquals(1L << 25, memFor(27));
    }

    private static long memFor(int m) {
        return (1L << (m - 2));
    }

    // ==================== the decision ====================

    /**
     *  With no measurement yet the size must hold, because resizing on nothing
     *  would throw away a window of duplicate detection for no reason.
     */
    @Test
    public void testHoldsWithoutMeasurement() {
        assertEquals(23, target(23, 0, 0d, BUDGET_128M, 0d));
    }

    /**
     *  Too many entries for the current size must climb, one step at a time so
     *  a resize is never more than one exponent per cycle.
     */
    @Test
    public void testClimbsOnHeavyLoad() {
        int m = 23;
        long budget = GB;                 // room to grow
        int t = target(m, 5000000, 1.5E-3, budget, 0d);
        assertEquals("must climb exactly one step", m + 1, t);
    }

    /** A comfortably oversized filter may shrink, but never below the floor. */
    @Test
    public void testShrinksOnLightLoad() {
        assertEquals(22, target(23, 10, 0d, GB, 0d));
        assertEquals("never below the floor", BloomFilterIVValidator.MIN_FILTER_M,
                     target(BloomFilterIVValidator.MIN_FILTER_M, 1, 0d, GB, 0d));
    }

    /**
     *  Memory pressure is the one signal that must give capacity back, and it
     *  has to win over the load signal.
     */
    @Test
    public void testMemoryPressureShrinks() {
        assertEquals(22, target(23, 5000000, 1.5E-3, GB, 0.80d));
    }

    /**
     *  Mild pressure must not shrink by itself. A light load legitimately makes
     *  the filter oversized, so this uses a right-sized load to isolate the
     *  pressure signal.
     */
    @Test
    public void testMildPressureDoesNotShrink() {
        int sized = 23;
        assertEquals("mild pressure must not shrink a right-sized filter",
                     sized, target(sized, entriesFor(sized), 1E-3, GB, 0.5d));
    }

    /**
     *  A false positive rate far over target is evidence the filter is genuinely
     *  too small, so it must climb even when the entry count looks comfortable.
     */
    @Test
    public void testHighFalsePositiveRateClimbs() {
        int m = 23;
        // A load that is right-sized for m, so the entry count does not move the
        // answer on its own and the rate is the only signal under test.
        int entries = entriesFor(m);
        // target 1.5E-3, so 1E-2 is well past the 4x trigger
        assertEquals(m + 1, target(m, entries, 1E-2, GB, 0d));
        // just under the trigger must leave a right-sized filter alone
        assertEquals(m, target(m, entries, 3E-3, GB, 0d));
    }

    /**
     *  The ceiling is the budget, so a load that would need an enormous filter
     *  on a small heap must stop at what the heap can hold.
     */
    @Test
    public void testCeilingIsTheBudget() {
        int ceiling = DecayingBloomFilter.mForBudget(BUDGET_128M);
        int t = target(BloomFilterIVValidator.MIN_FILTER_M, 100000000, 1.5E-3, BUDGET_128M, 0d);
        assertTrue("must not exceed the budget's ceiling", t <= ceiling);
        assertTrue("must not sit below the floor", t >= BloomFilterIVValidator.MIN_FILTER_M);
    }

    /**
     *  A budget too small to move anything must leave the size alone rather
     *  than clamp it somewhere the operator did not ask for.
     */
    @Test
    public void testTinyBudgetHolds() {
        int m = 23;
        assertEquals(m, target(m, 5000000, 1.5E-3, 1024, 0d));
    }

    /**
     *  A share-bandwidth reading must never drive a shrink. There is no share
     *  argument at all, which is the point: capacity is given back on memory
     *  pressure and on measured load only.
     */
    @Test
    public void testNeverShrinksOnThroughputReading() {
        int m = 23;
        // empty load and a healthy rate still allows the normal shrink path,
        // but with no measurement the size is held
        assertEquals(m, target(m, 0, 0d, GB, 0d));
    }

    /**
     *  Roughly the entry count that m is sized for at the target rate, found by
     *  walking the sizing helper back down from m.
     */
    private static int entriesFor(int m) {
        int lo = 1, hi = 1 << 24;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (DecayingBloomFilter.mForEntries(mid,
                    BloomFilterIVValidator.IVFilterSizer.TARGET_FPR) <= m)
                lo = mid;
            else
                hi = mid - 1;
        }
        return lo;
    }

    private static int target(int currentM, int windowEntries, double fpr,
                              long budgetBytes, double heapPressure) {
        return BloomFilterIVValidator.IVFilterSizer.computeTargetM(
            currentM, windowEntries, fpr, budgetBytes, heapPressure);
    }
}
