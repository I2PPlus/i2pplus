package net.i2p.router.util;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the entry/lookup semantics of DecayingBloomFilter.
 *
 * Replaces the benchmark scaffolding that was previously commented out in
 * DecayingBloomFilter: the old main()/testByLong()/testByBytes() only timed
 * insertions and printed false positive rates, so nothing asserted that
 * add()/isKnown() actually worked.
 *
 * Two things to keep in mind:
 * - add() returns true when the entry was already present, not when it was
 * newly added.
 * - this is a bloom filter, so a positive isKnown() may be a false positive,
 * but a negative is authoritative. These tests therefore assert that
 * inserted entries are never reported absent.
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
     * Negative and extreme long values are remembered.
     *
     * Long.MIN_VALUE used to throw: the encoder computed 0 - entry, which
     * overflows back to Long.MIN_VALUE, and DataHelper.toLong() rejects
     * negatives. It is now folded onto Long.MAX_VALUE.
     */
    @Test
    public void testEdgeValues() {
        long[] vals = { 0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 40 };
        for (long v : vals) {
            filter.add(v);
            assertTrue("value " + v + " should be known", filter.isKnown(v));
        }
    }

    /**
     * A long and its negation must stay distinct. The sign is carried in the
     * top bit of the first byte, which is spare only because toLong() is big
     * endian and so the magnitude's high bit is always clear; the magnitudes
     * below bracket the bit-55 boundary where that would stop holding.
     */
    @Test
    public void testSignIsSignificant() {
        long[] mags = { 1234L, 12345L, 1L << 54, 1L << 55, (1L << 55) + 1234,
                        1L << 56, Long.MAX_VALUE - 1 };
        for (long m : mags) {
            DecayingBloomFilter f = new DecayingBloomFilter(ctx, 60 * 1000, 8);
            try {
                f.add(m);
                assertFalse("-" + m + " must not collide with " + m, f.isKnown(-m));
            } finally {
                f.clear();
                f.stopDecaying();
            }
        }
    }

    /**
     * Every supported entryBytes must accept an entry. The extender count
     * used to be computed with ceiling division, so any size that did not
     * divide 32 overran the 32 byte hash buffer and threw "Result is too short".
     */
    @Test
    public void testAllEntrySizesWork() {
        for (int eb = 1; eb <= 16; eb++)
            assertEntrySizeWorks(eb);
        assertEntrySizeWorks(32);
    }

    private void assertEntrySizeWorks(int eb) {
        DecayingBloomFilter f = new DecayingBloomFilter(ctx, 60 * 1000, eb);
        try {
            // Distinct values, so a stale tail left in the thread local buffer
            // from an earlier insert would show up as a false positive.
            byte[] a = new byte[eb];
            byte[] b = new byte[eb];
            byte[] c = new byte[eb];
            a[0] = (byte) 0x11; a[eb - 1] = (byte) 0xA1;
            b[0] = (byte) 0x22; b[eb - 1] = (byte) 0xB2;
            c[0] = (byte) 0x33; c[eb - 1] = (byte) 0xC3;
            assertFalse("entryBytes " + eb + ": A is new", f.add(a, 0, eb));
            assertFalse("entryBytes " + eb + ": B is new", f.add(b, 0, eb));
            assertTrue("entryBytes " + eb + ": A is a duplicate", f.add(a, 0, eb));
            assertFalse("entryBytes " + eb + ": C must not alias A or B", f.add(c, 0, eb));
        } finally {
            f.clear();
            f.stopDecaying();
        }
    }

    /**
     * The accept boundary is part of the contract: 16 and 32 must work, 17
     * cannot be stretched to the 32 hashed bytes and must be refused up front.
     */
    @Test
    public void testEntrySizeBoundary() {
        assertEntrySizeWorks(16);
        assertEntrySizeWorks(32);
        try {
            new DecayingBloomFilter(ctx, 60 * 1000, 17);
            fail("17 must be refused, it cannot be stretched to 32 bytes");
        } catch (IllegalArgumentException expected) {
            // as above
        }
    }

    /**
     * Sizes that cannot be stretched to the 32 hashed bytes are rejected up
     * front: 17-31 would need a partial trailing extender block, and hashing
     * them unextended overruns KeySelector's word selectors.
     */
    @Test
    public void testBadEntrySizeRejected() {
        int[] bad = { 0, -1, 17, 24, 31, 33, 64 };
        for (int eb : bad) {
            try {
                new DecayingBloomFilter(ctx, 60 * 1000, eb);
                fail("expected IllegalArgumentException for entryBytes " + eb);
            } catch (IllegalArgumentException expected) {
                // as above
            }
        }
    }

    /**
     * A read-only probe must not be counted as a duplicate. isKnown() used to
     * increment the duplicate counter, inflating the statistic and the
     * "false positives" figure logged at decay.
     */
    @Test
    public void testIsKnownDoesNotCountAsDuplicate() {
        filter.add(0xCAFEL);
        long afterAdd = filter.getCurrentDuplicateCount();
        for (int i = 0; i < 5; i++)
            assertTrue(filter.isKnown(0xCAFEL));
        assertEquals("probes must not inflate the duplicate count",
                     afterAdd, filter.getCurrentDuplicateCount());
        // a real duplicate insertion still counts
        assertTrue(filter.add(0xCAFEL));
        assertEquals(afterAdd + 1, filter.getCurrentDuplicateCount());
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
