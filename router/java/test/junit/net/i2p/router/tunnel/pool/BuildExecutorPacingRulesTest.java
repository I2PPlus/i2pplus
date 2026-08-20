package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Build-pass spacing floor and pool-failure counting rules.
 *
 * @since 0.9.71+
 */
public class BuildExecutorPacingRulesTest {

    private static final long SPACING_MS = 2000;

    @Test
    public void testNoSpacingLeftWhenFloorMet() {
        assertEquals(0, BuildExecutor.spacingDelay(0, 2000));
        assertEquals(0, BuildExecutor.spacingDelay(0, 2001));
        assertEquals(0, BuildExecutor.spacingDelay(0, 60000));
    }

    @Test
    public void testSpacingLeftWithinFloor() {
        assertEquals(2000, BuildExecutor.spacingDelay(0, 0));
        assertEquals(1000, BuildExecutor.spacingDelay(0, 1000));
        assertEquals(1, BuildExecutor.spacingDelay(0, 1999));
    }

    @Test
    public void testLastPassInFutureKeepsFloor() {
        // clock moved backward: delay is the floor plus the skew, never below
        // the floor
        assertEquals(6000, BuildExecutor.spacingDelay(5000, 1000));
    }

    @Test
    public void testCountsAsPoolFailure() {
        assertFalse(BuildExecutor.countsAsPoolFailure(BuildExecutor.Result.SUCCESS));
        assertFalse(BuildExecutor.countsAsPoolFailure(BuildExecutor.Result.DUP_ID));
        assertFalse(BuildExecutor.countsAsPoolFailure(BuildExecutor.Result.REJECT));
        assertFalse("no paired tunnel is not a peer failure", BuildExecutor.countsAsPoolFailure(BuildExecutor.Result.NO_TUNNELS));
    }

    @Test
    public void testRealPeerFailuresCount() {
        assertTrue(BuildExecutor.countsAsPoolFailure(BuildExecutor.Result.TIMEOUT));
        assertTrue(BuildExecutor.countsAsPoolFailure(BuildExecutor.Result.BAD_RESPONSE));
        assertTrue(BuildExecutor.countsAsPoolFailure(BuildExecutor.Result.OTHER_FAILURE));
    }
}