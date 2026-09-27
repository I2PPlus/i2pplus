package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelTestStatus;

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
        return info(failed, consecutiveFailures, expiration, length, TunnelTestStatus.GOOD);
    }

    private static TunnelInfo info(boolean failed, int consecutiveFailures, long expiration, int length,
                                   TunnelTestStatus status) {
        TunnelInfo info = mock(TunnelInfo.class);
        when(info.getTunnelFailed()).thenReturn(failed);
        when(info.getConsecutiveFailures()).thenReturn(consecutiveFailures);
        when(info.getExpiration()).thenReturn(expiration);
        when(info.getLength()).thenReturn(length);
        // must be stubbed: the gate dereferences it, and an unstubbed mock
        // returns null rather than a default enum constant
        when(info.getTestStatus()).thenReturn(status);
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

    /**
     * A tunnel marked by setTestFailing() can have _failures == 0, so the
     * status has to be consulted directly. Both marked states are ineligible.
     */
    @Test
    public void testMarkedTunnelsRejectedRegardlessOfFailureCount() {
        assertFalse(TunnelPool.passesScanGates(
                info(false, 0, NOW + 1000, 3, TunnelTestStatus.FAILING), NOW, false));
        assertFalse(TunnelPool.passesScanGates(
                info(false, 0, NOW + 1000, 3, TunnelTestStatus.FAILED), NOW, false));
    }

    /** A dead tunnel must stay rejected even at the tolerated failure count. */
    @Test
    public void testDeadTunnelRejectedAtToleranceBoundary() {
        // 3 consecutive failures is the cap the count check still tolerates
        assertTrue(TunnelPool.passesScanGates(info(false, 3, NOW + 1000, 3), NOW, false));
        // but a FAILED status is terminal regardless of that count
        assertFalse(TunnelPool.passesScanGates(
                info(false, 3, NOW + 1000, 3, TunnelTestStatus.FAILED), NOW, false));
    }

    /** Only the marked states are excluded; the rest remain scannable. */
    @Test
    public void testUnmarkedStatusesRemainEligible() {
        for (TunnelTestStatus ts : new TunnelTestStatus[] {
                TunnelTestStatus.GOOD, TunnelTestStatus.UNTESTED,
                TunnelTestStatus.TESTING, TunnelTestStatus.TOO_SLOW,
                TunnelTestStatus.OVER_BUDGET}) {
            assertTrue(ts.name(), TunnelPool.passesScanGates(
                    info(false, 0, NOW + 1000, 3, ts), NOW, false));
        }
    }
}
