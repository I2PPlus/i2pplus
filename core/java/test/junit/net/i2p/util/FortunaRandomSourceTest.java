package net.i2p.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Tests for the rejection-sampling nextInt(int) and nextLong(long) added to
 * remove the modulo bias of the old nextBits() % n: boundary behavior,
 * range over many samples, rough uniformity, and the documented
 * return-0-for-n-0 and throw-for-negative-n contracts.
 */
public class FortunaRandomSourceTest {

    private final FortunaRandomSource rnd = (FortunaRandomSource) I2PAppContext.getGlobalContext().random();

    /** Documented: n of 0 or 1 returns 0, negative n throws. */
    @Test
    public void testBoundaries() {
        assertEquals(0, rnd.nextInt(0));
        assertEquals(0, rnd.nextInt(1));
        assertEquals(0L, rnd.nextLong(0));
        assertEquals(0L, rnd.nextLong(1));
        try {
            rnd.nextInt(-5);
            fail("negative n must throw");
        } catch (IllegalArgumentException e) { /* expected */ }
        try {
            rnd.nextLong(-5);
            fail("negative n must throw");
        } catch (IllegalArgumentException e) { /* expected */ }
    }

    /** All values in [0, n) across awkward n, including powers of two and MAX_VALUE. */
    @Test
    public void testIntRange() {
        int[] ns = {2, 3, 7, 8, 100, 255, 256, 1000, 65536, 65537, 1000000, Integer.MAX_VALUE};
        for (int n : ns) {
            for (int i = 0; i < 1000; i++) {
                int v = rnd.nextInt(n);
                assertTrue("nextInt(" + n + ") out of range: " + v, v >= 0 && v < n);
            }
        }
    }

    /** All values in [0, n) for long n. */
    @Test
    public void testLongRange() {
        long[] ns = {2, 3, 1000, 1L << 32, Long.MAX_VALUE};
        for (long n : ns) {
            for (int i = 0; i < 1000; i++) {
                long v = rnd.nextLong(n);
                assertTrue("nextLong(" + n + ") out of range: " + v, v >= 0 && v < n);
            }
        }
    }

    /**
     * Rough uniformity: with 30000 samples over 3 buckets the standard
     * deviation is about 81, so a 500 tolerance cannot fail by chance
     * on an unbiased generator but catches a badly skewed one.
     */
    @Test
    public void testUniformity() {
        int[] buckets = new int[3];
        int samples = 30000;
        for (int i = 0; i < samples; i++)
            buckets[rnd.nextInt(3)]++;
        int expected = samples / buckets.length;
        for (int b : buckets)
            assertTrue("bucket out of tolerance: " + b, Math.abs(b - expected) < 500);
    }

    /** signedNextInt() still spans both signs. */
    @Test
    public void testSignedNextIntSpansSigns() {
        boolean sawPos = false, sawNeg = false;
        for (int i = 0; i < 1000 && !(sawPos && sawNeg); i++) {
            int v = rnd.signedNextInt();
            if (v > 0) sawPos = true;
            if (v < 0) sawNeg = true;
        }
        assertTrue(sawPos);
        assertTrue(sawNeg);
    }

    /** nextInt(n) never returns a value &gt;= n even at the power-of-two edge. */
    @Test
    public void testPowerOfTwoEdge() {
        for (int i = 0; i < 10000; i++) {
            int v = rnd.nextInt(2);
            assertFalse(v < 0 || v > 1);
        }
    }
}
