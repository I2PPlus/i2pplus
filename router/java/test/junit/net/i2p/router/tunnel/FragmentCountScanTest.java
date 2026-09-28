package net.i2p.router.tunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import net.i2p.data.ByteArray;

import org.junit.Test;

/**
 * Unit tests for the fragment counting scan in FragmentedMessage.
 *
 * The scan is bounded by the high-water mark, matching the other accessors in
 * the class, so it is exercised here directly on a synthetic slot array
 * instead of through a live message (which would need an I2PAppContext).
 *
 * @since 0.9.71+
 */
public class FragmentCountScanTest {

    /** Mirrors the private FragmentedMessage.MAX_FRAGMENTS slot count. */
    private static final int MAX_FRAGMENTS = 64;

    private static ByteArray[] emptySlots() {
        return new ByteArray[MAX_FRAGMENTS];
    }

    private static ByteArray fragment() {
        return new ByteArray(new byte[16]);
    }

    @Test
    public void testNothingReceivedCountsZero() {
        assertEquals(0, FragmentedMessage.countFragments(emptySlots(), -1));
    }

    @Test
    public void testSingleFragmentAtZero() {
        ByteArray[] slots = emptySlots();
        slots[0] = fragment();
        assertEquals(1, FragmentedMessage.countFragments(slots, 0));
    }

    @Test
    public void testEverySlotUpToMarkIsCounted() {
        for (int high = 0; high < MAX_FRAGMENTS; high++) {
            ByteArray[] slots = emptySlots();
            for (int i = 0; i <= high; i++)
                slots[i] = fragment();
            assertEquals("high-water mark " + high, high + 1,
                         FragmentedMessage.countFragments(slots, high));
        }
    }

    /**
     * Gaps below the mark are holes, not extra fragments, and the bound must
     * not discard the fragments either: this is the case where a bounded scan
     * and an unbounded scan of a well-formed message must agree.
     */
    @Test
    public void testGapsBelowMarkAreNotCounted() {
        ByteArray[] slots = emptySlots();
        slots[0] = fragment();
        slots[2] = fragment();
        slots[5] = fragment();
        assertEquals(3, FragmentedMessage.countFragments(slots, 5));
    }

    /**
     * Regression guard: a slot left non-null above the high-water mark is stale
     * and must be ignored. FragmentedMessage can never produce this state -
     * both receive() overloads raise the mark to at least the index they store
     * into - so the bounded scan is required for the count to stay correct if it
     * ever does, and this pins the intended semantics rather than the
     * current invariant.
     */
    @Test
    public void testStaleSlotAboveMarkIsIgnored() {
        ByteArray[] slots = emptySlots();
        slots[0] = fragment();
        slots[3] = fragment();
        slots[10] = fragment();
        slots[MAX_FRAGMENTS - 1] = fragment();
        assertEquals(2, FragmentedMessage.countFragments(slots, 3));
    }

    @Test
    public void testSparseAcrossFullRange() {
        ByteArray[] slots = emptySlots();
        slots[0] = fragment();
        slots[MAX_FRAGMENTS - 1] = fragment();
        assertEquals(2, FragmentedMessage.countFragments(slots, MAX_FRAGMENTS - 1));
    }

    /**
     * With a negative mark the loop body never runs, so a null array is never
     * dereferenced and the scan reports zero rather than failing.
     */
    @Test
    public void testNullArrayIsNotDereferencedWhenNothingReceived() {
        assertEquals(0, FragmentedMessage.countFragments(null, -1));
    }

    /**
     * Once there is a range to scan the null array is dereferenced and fails
     * naturally, matching the rest of the class, which does no defensive
     * array checking.
     */
    @Test
    public void testNullArrayFailsWhenRangeIsScanned() {
        try {
            FragmentedMessage.countFragments(null, 0);
            fail("expected NullPointerException");
        } catch (NullPointerException npe) {
            // expected
        }
    }
}
