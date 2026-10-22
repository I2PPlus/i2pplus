package net.i2p.router.crypto;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the memory budget that the inbound tag cap stands for.
 * <p>
 * MAX_INBOUND_SESSION_TAGS is a count, but it exists to bound memory, and the
 * comment on it used to guess "24 MB" when the map actually holds roughly four
 * times that. The count and the cost are far apart enough that nobody reading
 * the number can tell whether raising it is free, so the relationship is
 * written down as arithmetic instead of as prose someone has to trust.
 *
 * @since 0.9.72+
 */
public class TransientSessionKeyManagerCapTest {

    /**
     * Measured cost of one _inboundTagSets entry: the SessionTag wrapper and its
     * 32-byte array, the HashMap node, and the table slot. Filling a
     * HashMap&lt;SessionTag, Object&gt; with 800 000 entries on a 64-bit JVM with
     * compressed oops grew the heap by 100 699 584 bytes, 125.9 per entry.
     */
    private static final int BYTES_PER_TAG_ENTRY = 126;

    /** The memory the cap was chosen to stay inside. */
    private static final long TAG_MEMORY_BUDGET = 100L * 1024 * 1024;

    /**
     * The cap, at the measured per-entry cost, has to stay inside the budget its
     * javadoc claims. Raising the cap without re-measuring what an entry costs
     * is what left the comment four times wrong in the first place.
     */
    @Test
    public void testCapStaysInsideTheDocumentedBudget() {
        long cost = (long) TransientSessionKeyManager.MAX_INBOUND_SESSION_TAGS * BYTES_PER_TAG_ENTRY;
        assertTrue("MAX_INBOUND_SESSION_TAGS costs " + (cost / (1024 * 1024)) +
                   " MB, over the " + (TAG_MEMORY_BUDGET / (1024 * 1024)) + " MB budget",
                   cost <= TAG_MEMORY_BUDGET);
    }

    /**
     * The budget has to actually bind. A ceiling the cap could never reach makes
     * the check above pass no matter how large the cap grows, which is the same
     * as having no ceiling at all.
     */
    @Test
    public void testTheBudgetIsNotVacuous() {
        long cost = (long) TransientSessionKeyManager.MAX_INBOUND_SESSION_TAGS * BYTES_PER_TAG_ENTRY;
        assertTrue("budget is not binding: " + cost + " bytes against " + TAG_MEMORY_BUDGET,
                   cost > TAG_MEMORY_BUDGET / 2);
    }
}
