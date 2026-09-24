package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the difficulty-spare decision ({@link TunnelPool#computeDifficultySpare(int)}):
 * how many spare tunnels a pool holds above target while its builds are
 * silently timing out.  Config-free replacement for a static backupQuantity.
 *
 * @since 0.9.71+
 */
public class DifficultySpareTest {

    @Test
    public void testNoSpareWithoutDifficulty() {
        assertEquals(0, TunnelPool.computeDifficultySpare(0));
        assertEquals(0, TunnelPool.computeDifficultySpare(1));
        assertEquals(0, TunnelPool.computeDifficultySpare(TunnelPool.SPARE_LOW_THRESHOLD - 1));
    }

    @Test
    public void testOneSpareAtLowThreshold() {
        assertEquals(1, TunnelPool.computeDifficultySpare(TunnelPool.SPARE_LOW_THRESHOLD));
        assertEquals(1, TunnelPool.computeDifficultySpare(TunnelPool.SPARE_LOW_THRESHOLD + 1));
        assertEquals(1, TunnelPool.computeDifficultySpare(TunnelPool.SPARE_HIGH_THRESHOLD - 1));
    }

    @Test
    public void testTwoSparesAtHighThreshold() {
        assertEquals(2, TunnelPool.computeDifficultySpare(TunnelPool.SPARE_HIGH_THRESHOLD));
        assertEquals(2, TunnelPool.computeDifficultySpare(TunnelPool.SPARE_HIGH_THRESHOLD + 10));
        assertEquals(2, TunnelPool.computeDifficultySpare(Integer.MAX_VALUE));
    }

    @Test
    public void testNegativeCountsYieldNoSpare() {
        assertEquals(0, TunnelPool.computeDifficultySpare(-1));
        assertEquals(0, TunnelPool.computeDifficultySpare(Integer.MIN_VALUE));
    }

    @Test
    public void testThresholdsAreOrdered() {
        org.junit.Assert.assertTrue(TunnelPool.SPARE_LOW_THRESHOLD < TunnelPool.SPARE_HIGH_THRESHOLD);
    }
}
