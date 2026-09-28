package net.i2p.router.util;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import java.util.Arrays;
import java.util.Random;

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

    /**
     *  A read-only probe must not be counted as a duplicate. isKnown() used to
     *  increment the duplicate counter, which then got logged at decay as a
     *  false positive count.
     */
    @Test
    public void testIsKnownDoesNotCountAsDuplicate() {
        set.add(0xCAFEL);
        long afterAdd = set.getCurrentDuplicateCount();
        for (int i = 0; i < 5; i++)
            assertTrue(set.isKnown(0xCAFEL));
        assertEquals("probes must not inflate the duplicate count",
                     afterAdd, set.getCurrentDuplicateCount());
        assertTrue(set.add(0xCAFEL));
        assertEquals(afterAdd + 1, set.getCurrentDuplicateCount());
    }

    /**
     *  DecayingHashSet hashes entries directly rather than stretching them to
     *  32 bytes, so unlike DecayingBloomFilter it accepts the whole 1-32 range
     *  including 17-31, and only refuses sizes outside it.
     */
    @Test
    public void testBadEntrySizeRejected() {
        int[] bad = { 0, -1, 33, 64 };
        for (int eb : bad) {
            try {
                new DecayingHashSet(ctx, 60 * 1000, eb);
                fail("expected IllegalArgumentException for entryBytes " + eb);
            } catch (IllegalArgumentException expected) {
                // as above
            }
        }
        // 17-31 are valid here even though DecayingBloomFilter refuses them
        for (int eb : new int[]{ 17, 24, 31 }) {
            DecayingHashSet s = new DecayingHashSet(ctx, 60 * 1000, eb);
            s.stopDecaying();
        }
    }

    /**
     *  Exceeding the soft cap must not forget entries that have not expired.
     *  The forced decay used to run as soon as the cap was crossed, and its
     *  swap dropped the previous buffer, so a flood of unique values could
     *  evict entries that were still well inside their lifetime.
     *
     *  Uses a small injected cap rather than {@link DecayingHashSet#MAX_ENTRIES},
     *  so the test crosses the threshold in ~4k inserts instead of 151k. The
     *  production default is exercised by the same code path.
     */
    @Test
    public void testCapDoesNotEvictUnexpiredEntries() {
        floodPastCapAndCheckMarkerSurvives(2048);
    }

    /**
     *  The byte[] overload is the one {@code BloomFilterIVValidator} uses, and
     *  it reaches {@code forceDecayIfOverCap()} through a different branch than
     *  the long overload, so pin the same guarantee for it.
     *
     *  There is no isKnown(byte[],int,int), so survival is shown the only way a
     *  retained entry can be observed: re-adding it is then a duplicate.
     */
    @Test
    public void testCapDoesNotEvictUnexpiredByteEntries() {
        final int cap = 2048;
        DecayingHashSet s = new DecayingHashSet(ctx, 60 * 1000, 16, "DHS-cap", cap);
        try {
            byte[] marker = new byte[16];
            marker[0] = 0x5A;
            marker[15] = (byte) 0xA5;
            assertFalse("marker is new", s.add(marker, 0, marker.length));
            Random rnd = new Random(3);
            for (int i = 0; i < cap + 2000; i++) {
                byte[] v = new byte[16];
                rnd.nextBytes(v);
                if (!Arrays.equals(v, marker))
                    s.add(v, 0, v.length);
            }
            assertTrue("over the soft cap", s.getInsertedCount() > cap);
            assertTrue("entry added seconds ago must survive a cap-crossing flood",
                       s.add(marker, 0, marker.length));
        } finally {
            s.stopDecaying();
        }
    }

    private void floodPastCapAndCheckMarkerSurvives(int cap) {
        DecayingHashSet s = new DecayingHashSet(ctx, 60 * 1000, 8, "DHS-cap", cap);
        try {
            long marker = 0x0123456789ABCDEFL;
            s.add(marker);
            assertTrue("marker must be known before the flood", s.isKnown(marker));
            Random rnd = new Random(3);
            for (int i = 0; i < cap + 2000; i++)
                s.add(rnd.nextLong());
            assertTrue("over the soft cap", s.getInsertedCount() > cap);
            assertTrue("entry added seconds ago must survive a cap-crossing flood",
                       s.isKnown(marker));
        } finally {
            s.stopDecaying();
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
