package net.i2p.router.util;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *  Tests for the entry/lookup semantics of DecayingBloomFilter.
 *
 *  Replaces the benchmark scaffolding that was previously commented out in
 *  DecayingBloomFilter: the old main()/testByLong()/testByBytes() only timed
 *  insertions and printed false positive rates, so nothing asserted that
 *  add()/isKnown() actually worked.
 *
 *  Two things to keep in mind:
 *  - add() returns true when the entry was already present, not when it was
 *    newly added.
 *  - this is a bloom filter, so a positive isKnown() may be a false positive,
 *    but a negative is authoritative. These tests therefore assert that
 *    inserted entries are never reported absent.
 */
public class DecayingBloomFilterTest {

    private I2PAppContext ctx;
    private DecayingBloomFilter filter;

    @Before
    public void setUp() {
        ctx = I2PAppContext.getGlobalContext();
        filter = new DecayingBloomFilter(ctx, 60 * 1000, 8);
    }

    @After
    public void tearDown() {
        if (filter != null)
            filter.stopDecaying();
    }

    /** A new entry is not reported as a duplicate, and is then known. */
    @Test
    public void testAddIsKnown() {
        assertFalse("first add is not a duplicate", filter.add(12345L));
        assertTrue(filter.isKnown(12345L));
    }

    /** A freshly built filter knows nothing. */
    @Test
    public void testEmptyFilterKnowsNothing() {
        assertFalse(filter.isKnown(1L));
    }

    /** Re-adding an existing entry reports a duplicate, and keeps it known. */
    @Test
    public void testDuplicateAdd() {
        assertFalse(filter.add(777L));
        assertTrue("second add is a duplicate", filter.add(777L));
        assertTrue(filter.isKnown(777L));
    }

    /** Every inserted entry must be reported known: no false negatives. */
    @Test
    public void testNoFalseNegatives() {
        int count = 500;
        for (int i = 0; i < count; i++)
            filter.add((long) i);
        assertEquals(count, filter.getInsertedCount());
        for (int i = 0; i < count; i++)
            assertTrue("entry " + i + " must not be a false negative", filter.isKnown((long) i));
    }

    /**
     *  Negative and extreme long values are remembered.
     *
     *  Note Long.MIN_VALUE is deliberately absent: add() negates negative
     *  entries before encoding them, and 0 - Long.MIN_VALUE overflows back to
     *  Long.MIN_VALUE, so DataHelper.toLong() rejects it with
     *  IllegalArgumentException. See DecayingBloomFilter.add(long). That is a
     *  pre-existing defect, not intended behaviour, so it is not pinned here.
     */
    @Test
    public void testEdgeValues() {
        long[] vals = { 0L, -1L, Long.MAX_VALUE, 1L << 40 };
        for (long v : vals) {
            filter.add(v);
            assertTrue("value " + v + " should be known", filter.isKnown(v));
        }
    }

    /** clear() forgets everything. */
    @Test
    public void testClear() {
        filter.add(4242L);
        assertTrue(filter.isKnown(4242L));
        filter.clear();
        assertFalse(filter.isKnown(4242L));
    }

    /** Byte-array adds must match the configured entry size. */
    @Test
    public void testByteEntrySizeEnforced() {
        try {
            filter.add(new byte[16], 0, 16);
            fail("expected IllegalArgumentException for wrong entry size");
        } catch (IllegalArgumentException expected) {
            // the filter was built with 8 byte entries
        }
    }

    /** The three argument add() rejects a null entry. */
    @Test
    public void testNullByteEntryRejected() {
        try {
            filter.add(null, 0, 8);
            fail("expected IllegalArgumentException for null entry");
        } catch (IllegalArgumentException expected) {
            // as above
        }
    }

    /** A correctly sized byte array is accepted. */
    @Test
    public void testByteEntryAccepted() {
        byte[] v = new byte[8];
        v[0] = 5;
        assertFalse(filter.add(v, 0, v.length));
    }

    /** stopDecaying() is safe to call more than once. */
    @Test
    public void testStopDecayingIdempotent() {
        filter.add(1L);
        filter.stopDecaying();
        filter.stopDecaying();
    }
}
