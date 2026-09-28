package net.i2p.router.util;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *  Tests for the sizing accessors and the run-time cap setter.
 *
 *  @since 0.9.71+
 */
public class DecayingFilterSizingTest {

    private I2PAppContext ctx;
    private DecayingBloomFilter filter;
    private DecayingHashSet set;

    @Before
    public void setUp() {
        ctx = I2PAppContext.getGlobalContext();
        filter = new DecayingBloomFilter(ctx, 60 * 1000, 16, "Sizing", 23);
        set = new DecayingHashSet(ctx, 60 * 1000, 16, "SizingDHS");
    }

    @After
    public void tearDown() {
        if (filter != null)
            filter.stopDecaying();
        if (set != null)
            set.stopDecaying();
    }

    /** The filter must report the exponent and memory it was built with. */
    @Test
    public void testFilterReportsItsSize() {
        assertEquals(23, filter.getM());
        assertEquals(1L << 21, filter.getMemoryBytes());
    }

    /** A larger exponent must report proportionally more memory. */
    @Test
    public void testMemoryScalesWithM() {
        DecayingBloomFilter big = new DecayingBloomFilter(ctx, 60 * 1000, 16, "Big", 25);
        try {
            assertEquals(25, big.getM());
            assertEquals(1L << 23, big.getMemoryBytes());
        } finally {
            big.stopDecaying();
        }
    }

    /** A set keeps no bit array, so it reports no exponent and no memory. */
    @Test
    public void testSetHasNoBitArray() {
        assertEquals(0, set.getM());
        assertEquals(0L, set.getMemoryBytes());
    }

    /** The window count starts at zero and has no meaning before a decay. */
    @Test
    public void testWindowCountStartsEmpty() {
        assertEquals(0, filter.getLastWindowCount());
        filter.add(new byte[16], 0, 16);
        assertEquals("an insert alone must not report a completed window",
                     0, filter.getLastWindowCount());
    }

    /** The cap must be settable and readable while running. */
    @Test
    public void testSetMaxEntries() {
        assertEquals(DecayingHashSet.DEFAULT_MAX_ENTRIES, set.getMaxEntries());
        set.setMaxEntries(5000);
        assertEquals(5000, set.getMaxEntries());
        set.setMaxEntries(7000);
        assertEquals(7000, set.getMaxEntries());
    }

    /** A non-positive cap is refused rather than silently disabling the bound. */
    @Test
    public void testSetMaxEntriesRejectsNonPositive() {
        for (int bad : new int[]{ 0, -1, Integer.MIN_VALUE }) {
            try {
                set.setMaxEntries(bad);
                fail("expected IllegalArgumentException for cap " + bad);
            } catch (IllegalArgumentException expected) {
                // as above
            }
        }
        assertEquals("a refused cap must not change the one in force",
                     DecayingHashSet.DEFAULT_MAX_ENTRIES, set.getMaxEntries());
    }

    /** The constructor must also refuse a non-positive cap. */
    @Test
    public void testConstructorRejectsNonPositiveCap() {
        try {
            new DecayingHashSet(ctx, 60 * 1000, 16, "Bad", 0);
            fail("expected IllegalArgumentException for a zero cap");
        } catch (IllegalArgumentException expected) {
            // as above
        }
    }

    /**
     *  A lowered cap must bound growth immediately. The default cap is only a
     *  backstop and waits for the scheduled decay, so it cannot bound anything
     *  within one interval; a cap set through {@link #setMaxEntries} is
     *  enforced at once because the caller has declared memory to be the harder
     *  constraint.
     */
    @Test
    public void testLoweredCapBoundsGrowthImmediately() {
        set.setMaxEntries(2000);
        java.util.Random rnd = new java.util.Random(11);
        byte[] v = new byte[16];
        for (int i = 0; i < 20000; i++) {
            rnd.nextBytes(v);
            set.add(v, 0, 16);
        }
        assertTrue("occupancy must stay near the lowered cap, was "
                   + set.getInsertedCount(), set.getInsertedCount() <= 4000);
    }

    /**
     *  The default cap must stay soft, so a fresh set is allowed to grow past
     *  it within the first interval rather than retiring live entries.
     */
    @Test
    public void testDefaultCapIsSoft() {
        java.util.Random rnd = new java.util.Random(13);
        byte[] v = new byte[16];
        DecayingHashSet small = new DecayingHashSet(ctx, 60 * 1000, 16, "Soft", 2000);
        try {
            for (int i = 0; i < 20000; i++) {
                rnd.nextBytes(v);
                small.add(v, 0, 16);
            }
            assertTrue("the default cap must not retire live entries within an interval, was "
                       + small.getInsertedCount(), small.getInsertedCount() > 2000);
        } finally {
            small.stopDecaying();
        }
    }

    /** Reported memory must track occupancy at the measured per-entry cost. */
    @Test
    public void testEstimatedMemoryTracksOccupancy() {
        assertEquals(0L, set.getEstimatedMemoryBytes());
        java.util.Random rnd = new java.util.Random(5);
        byte[] v = new byte[16];
        for (int i = 0; i < 100; i++) {
            rnd.nextBytes(v);
            set.add(v, 0, 16);
        }
        assertEquals((long) set.getInsertedCount() * DecayingHashSet.BYTES_PER_ENTRY,
                     set.getEstimatedMemoryBytes());
    }
}
