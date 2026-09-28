package net.i2p.router.util;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *  Tests for the entry/lookup semantics of DecayingHashSet.
 *
 *  Replaces the benchmark scaffolding that was previously commented out in
 *  DecayingHashSet: the old main()/testByLong()/testByBytes() only timed
 *  insertions and printed false positive counts, so nothing asserted that
 *  add()/isKnown() actually worked.
 *
 *  Note the inverted return value - add() returns true when the entry was
 *  already a duplicate, not when it was newly added.
 */
public class DecayingHashSetTest {

    private I2PAppContext ctx;
    private DecayingHashSet set;

    @Before
    public void setUp() {
        ctx = I2PAppContext.getGlobalContext();
        set = new DecayingHashSet(ctx, 60 * 1000, 8);
    }

    @After
    public void tearDown() {
        if (set != null)
            set.stopDecaying();
    }

    /** A new entry is not reported as a duplicate, and is then known. */
    @Test
    public void testAddIsKnown() {
        assertFalse("first add is not a duplicate", set.add(12345L));
        assertTrue(set.isKnown(12345L));
    }

    /** An entry never added is not known. */
    @Test
    public void testUnaddedIsNotKnown() {
        set.add(12345L);
        assertFalse(set.isKnown(999L));
    }

    /** A freshly built set knows nothing. */
    @Test
    public void testEmptySetKnowsNothing() {
        assertFalse(set.isKnown(1L));
    }

    /** Re-adding an existing entry reports a duplicate, and keeps it known. */
    @Test
    public void testDuplicateAdd() {
        assertFalse(set.add(777L));
        assertTrue("second add is a duplicate", set.add(777L));
        assertTrue(set.isKnown(777L));
    }

    /** isKnown() must not add the entry as a side effect. */
    @Test
    public void testIsKnownDoesNotAdd() {
        assertFalse(set.isKnown(31337L));
        assertFalse("still not present, so adding is not a duplicate", set.add(31337L));
    }

    /** Many distinct entries are all remembered. */
    @Test
    public void testManyEntries() {
        int count = 500;
        for (int i = 0; i < count; i++)
            assertFalse("entry " + i + " should be new", set.add((long) i));
        assertEquals(count, set.getInsertedCount());
        for (int i = 0; i < count; i++)
            assertTrue("entry " + i + " should be known", set.isKnown((long) i));
    }

    /** Negative and extreme long values round-trip too. */
    @Test
    public void testEdgeValues() {
        long[] vals = { 0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 40 };
        for (long v : vals) {
            set.add(v);
            assertTrue("value " + v + " should be known", set.isKnown(v));
        }
    }

    /** clear() forgets everything. */
    @Test
    public void testClear() {
        set.add(4242L);
        assertTrue(set.isKnown(4242L));
        set.clear();
        assertFalse(set.isKnown(4242L));
    }

    /** stopDecaying() is safe to call more than once. */
    @Test
    public void testStopDecayingIdempotent() {
        set.add(1L);
        set.stopDecaying();
        set.stopDecaying();
    }
}
