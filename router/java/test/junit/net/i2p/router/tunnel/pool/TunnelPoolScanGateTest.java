package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.router.TunnelInfo;

import org.junit.Test;

/**
 * Unit tests for the pure scan eligibility gates extracted from
 * TunnelPool.scanPoolForTunnel().
 *
 * @since 0.9.71+
 */
public class TunnelPoolScanGateTest {

    private static final long NOW = 1_000_000L;

    private static TunnelInfo info(boolean failed, int consecutiveFailures, long expiration, int length) {
        TunnelInfo info = mock(TunnelInfo.class);
        when(info.getTunnelFailed()).thenReturn(failed);
        when(info.getConsecutiveFailures()).thenReturn(consecutiveFailures);
        when(info.getExpiration()).thenReturn(expiration);
        when(info.getLength()).thenReturn(length);
        return info;
    }

    @Test
    public void testCleanTunnelPasses() {
        assertTrue(TunnelPool.passesScanGates(info(false, 0, NOW + 1000, 3), NOW, false));
    }

    @Test
    public void testFailedTunnelRejected() {
        assertFalse(TunnelPool.passesScanGates(info(true, 0, NOW + 1000, 3), NOW, false));
    }

    @Test
    public void testAtMaxConsecutiveFailuresPasses() {
        // strict >: the cap itself is still tolerated
        assertTrue(TunnelPool.passesScanGates(info(false, 3, NOW + 1000, 3), NOW, false));
    }

    @Test
    public void testOverMaxConsecutiveFailuresRejected() {
        assertFalse(TunnelPool.passesScanGates(info(false, 4, NOW + 1000, 3), NOW, false));
    }

    @Test
    public void testExpiredTunnelRejected() {
        assertFalse(TunnelPool.passesScanGates(info(false, 0, NOW, 3), NOW, false));
    }

    @Test
    public void testExpiringTunnelPasses() {
        assertTrue(TunnelPool.passesScanGates(info(false, 0, NOW + 1, 3), NOW, false));
    }

    @Test
    public void testSingleHopRejectedOnFirstPass() {
        assertFalse(TunnelPool.passesScanGates(info(false, 0, NOW + 1000, 1), NOW, true));
    }

    @Test
    public void testSingleHopAllowedOnSecondPass() {
        assertTrue(TunnelPool.passesScanGates(info(false, 0, NOW + 1000, 1), NOW, false));
    }

    @Test
    public void testMultiHopAllowedOnFirstPass() {
        assertTrue(TunnelPool.passesScanGates(info(false, 0, NOW + 1000, 2), NOW, true));
    }

    @Test
    public void testGateOrderShortCircuits() {
        // failed + expired: the failed check wins, no exception from stale fields
        assertFalse(TunnelPool.passesScanGates(info(true, 0, NOW - 1, 3), NOW, false));
    }
}