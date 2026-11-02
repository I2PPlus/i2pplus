package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The thread-name suffixes: fixed width, so the log column does not move and
 * a counter that has run for a month does not push the stem out of the
 * 12-character column.
 *
 * @since 0.9.72+
 */
public class ThreadNamesTest {

    /**
     * A fresh test instance per method, as JUnit builds one per test; the
     * class carries no state.
     *
     * @since 0.9.72+
     */
    public ThreadNamesTest() {}

    /**
     * A single-digit sequence number is padded to two digits.
     */
    @Test
    public void testSeqPads() {
        assertEquals(".07", ThreadNames.seq(7));
        assertEquals(".00", ThreadNames.seq(0));
    }

    /**
     * The suffix is always three characters, which is the budget a stem is
     * sized against: 9 + 3 fills the column exactly.
     */
    @Test
    public void testSeqWidthIsFixed() {
        for (int n : new int[]{0, 9, 10, 99, 100, 1234, 1000000}) {
            String s = ThreadNames.seq(n);
            assertEquals(n + " -> " + s, ThreadNames.seqWidth(), s.length());
        }
    }

    /**
     * A monotonic counter wraps at 100 rather than growing the name.
     */
    @Test
    public void testSeqWraps() {
        assertEquals(".00", ThreadNames.seq(100));
        assertEquals(".37", ThreadNames.seq(137));
        assertEquals(".04", ThreadNames.seq(10004));
    }

    /**
     * A negative counter, from a subtraction of two ids, stays in width
     * instead of gaining a sign character.
     */
    @Test
    public void testSeqOfNegativeStaysInWidth() {
        assertEquals(".99", ThreadNames.seq(-1));
        assertEquals(3, ThreadNames.seq(Integer.MIN_VALUE).length());
    }

    /**
     * An index from a bounded pool is padded, and shows the real number when
     * the pool outgrows two digits.
     */
    @Test
    public void testIdxPadsWithoutWrapping() {
        assertEquals(".03", ThreadNames.idx(3));
        assertEquals(".120", ThreadNames.idx(120));
    }
}
