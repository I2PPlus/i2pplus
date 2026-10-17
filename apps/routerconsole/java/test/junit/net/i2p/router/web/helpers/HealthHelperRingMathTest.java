package net.i2p.router.web.helpers;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Math tests for the pure helpers behind the health rings.
 *
 * <p>The bandwidth ring must read a saturated single direction as saturation,
 * not as half of the summed-both-directions figure it used to show; the cache
 * hit sparkline must plot the same ratio the ring shows, never one of its
 * raw count series.
 *
 * @since 0.9.71+
 */
public class HealthHelperRingMathTest {

    private static final double EPS = 0.0001;

    @Test
    public void testSaturatedOutboundReadsFull() {
        // 8 MB/s out cap fully used, inbound idle: the old summed formula read 50%
        assertEquals(1.0, HealthHelper.bwUtilization(8_000_000, 0, 8_000_000, 8_000_000), EPS);
    }

    @Test
    public void testWorstDirectionWins() {
        assertEquals(0.5, HealthHelper.bwUtilization(4_000_000, 1_000_000, 8_000_000, 8_000_000), EPS);
        assertEquals(0.75, HealthHelper.bwUtilization(1_000_000, 6_000_000, 8_000_000, 8_000_000), EPS);
    }

    @Test
    public void testAsymmetricCapsUseOwnDirection() {
        // small inbound cap saturated, big outbound cap idle
        assertEquals(1.0, HealthHelper.bwUtilization(0, 1_000_000, 1_000_000, 16_000_000), EPS);
    }

    @Test
    public void testOverCapExceedsOne() {
        assertEquals(2.0, HealthHelper.bwUtilization(16_000_000, 0, 8_000_000, 8_000_000), EPS);
    }

    @Test
    public void testUnlimitedDirectionIsZero() {
        assertEquals(0.0, HealthHelper.bwUtilization(5_000_000, 5_000_000, 0, 0), EPS);
        assertEquals(0.25, HealthHelper.bwUtilization(2_000_000, 9_000_000, 0, 8_000_000), EPS);
    }

    @Test
    public void testRatioHistoryMatchesRing() {
        double[] num = {40, 60, Double.NaN, 10};
        double[] den = {100, 120, 50, 0};
        double[] r = HealthHelper.ratioHistory(num, den);
        assertNotNull(r);
        assertEquals(4, r.length);
        assertEquals(0.4, r[0], EPS);
        assertEquals(0.5, r[1], EPS);
        assertTrue(Double.isNaN(r[2]));
        assertTrue(Double.isNaN(r[3]));
    }

    @Test
    public void testRatioHistoryNullInputs() {
        assertNull(HealthHelper.ratioHistory(null, new double[] {1}));
        assertNull(HealthHelper.ratioHistory(new double[] {1}, null));
        assertNull(HealthHelper.ratioHistory(new double[] {1, 2}, new double[] {1}));
    }
}
