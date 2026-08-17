package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for DontHaveMemo, the per-peer BEP 54 dont_have remembered state.
 */
public class DontHaveMemoTest {

    /** A dont_have piece is remembered within the retention window. */
    @Test
    public void testRemembered() {
        DontHaveMemo memo = new DontHaveMemo(60000);
        assertFalse(memo.contains(7));
        memo.add(7);
        assertTrue(memo.contains(7));
    }

    /** Re-adding refreshes the expiry, so an expired piece can be re-remembered. */
    @Test
    public void testReAddRefreshes() throws Exception {
        DontHaveMemo memo = new DontHaveMemo(50);
        memo.add(7);
        Thread.sleep(70);
        assertFalse(memo.contains(7));
        memo.add(7);
        assertTrue(memo.contains(7));
    }

    /** Expired entries are dropped, and other pieces are unaffected. */
    @Test
    public void testExpiryDropsOnlyTheEntry() throws Exception {
        DontHaveMemo memo = new DontHaveMemo(50);
        memo.add(7);
        memo.add(9);
        Thread.sleep(70);
        assertFalse(memo.contains(7));
        assertFalse(memo.contains(9));
        assertFalse(memo.contains(8)); // never added
    }
}