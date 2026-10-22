package net.i2p.router.crypto;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Ordering of tag sets on the session-key-manager debug page (C-4). The
 * comparator used to subtract the two dates and cast the result to int, and
 * subtracted the tie-break hashes as well. A date gap wider than
 * Integer.MAX_VALUE ms - 24.9 days, well inside the range of absolute
 * millisecond timestamps - flips the sign of the truncated subtraction, and a
 * hash is any int, so the tie-break flips routinely.
 */
public class TagSetOrderTest {

    /**
     * Earliest first, the order the page is meant to show.
     */
    @Test
    public void testEarliestFirst() {
        assertTrue("the later set sorted first",
                   TransientSessionKeyManager.compareTagSets(1000L, 2000L, 1, 1) < 0);
        assertTrue("the earlier set sorted last",
                   TransientSessionKeyManager.compareTagSets(2000L, 1000L, 1, 1) > 0);
        assertEquals(0, TransientSessionKeyManager.compareTagSets(1000L, 1000L, 7, 7));
    }

    /**
     * Two sets a month apart. Cast to int, that difference comes back with the
     * opposite sign, and the page sorts the older set last.
     */
    @Test
    public void testDateGapWiderThanAnIntStillOrders() {
        long month = 30L * 24 * 60 * 60 * 1000;
        assertTrue("a month-long date gap sorted backwards",
                   TransientSessionKeyManager.compareTagSets(0L, month, 1, 1) < 0);
        assertTrue("a month-long date gap sorted backwards in reverse",
                   TransientSessionKeyManager.compareTagSets(month, 0L, 1, 1) > 0);
    }

    /**
     * Same date, so the hash decides. Those hashes straddle the sign boundary,
     * and subtracting them wraps to the wrong sign.
     */
    @Test
    public void testTieBreakDoesNotOverflow() {
        assertTrue("the tie-break sorted backwards across the sign boundary",
                   TransientSessionKeyManager.compareTagSets(5000L, 5000L, Integer.MIN_VALUE, 1) < 0);
        assertTrue("the tie-break sorted forwards across the sign boundary",
                   TransientSessionKeyManager.compareTagSets(5000L, 5000L, 1, Integer.MIN_VALUE) > 0);
    }
}
