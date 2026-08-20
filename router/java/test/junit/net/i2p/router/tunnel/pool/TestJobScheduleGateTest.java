package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.data.TunnelId;
import net.i2p.router.TunnelPoolSettings;

import org.junit.Test;

/**
 * Unit tests for the pure scheduling gates extracted from
 * TestJob.shouldSchedule().
 *
 * @since 0.9.71+
 */
public class TestJobScheduleGateTest {

    private static final long NOW = 1_000_000L;

    // ---------------- hasValidTunnelIds ----------------

    private static PooledTunnelCreatorConfig cfgWithId(boolean inbound, long id) {
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.isInbound()).thenReturn(inbound);
        TunnelId tid = mock(TunnelId.class);
        when(tid.getTunnelId()).thenReturn(id);
        if (inbound) {
            when(cfg.getReceiveTunnelId(0)).thenReturn(tid);
        } else {
            when(cfg.getSendTunnelId(0)).thenReturn(tid);
        }
        return cfg;
    }

    @Test
    public void testInboundValidId() {
        assertTrue(TestJob.hasValidTunnelIds(cfgWithId(true, 42)));
    }

    @Test
    public void testInboundZeroIdRejected() {
        assertFalse(TestJob.hasValidTunnelIds(cfgWithId(true, 0)));
    }

    @Test
    public void testOutboundValidId() {
        assertTrue(TestJob.hasValidTunnelIds(cfgWithId(false, 42)));
    }

    @Test
    public void testOutboundZeroIdRejected() {
        assertFalse(TestJob.hasValidTunnelIds(cfgWithId(false, 0)));
    }

    @Test
    public void testIdReadFailureRejected() {
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.isInbound()).thenReturn(true);
        when(cfg.getReceiveTunnelId(0)).thenThrow(new IllegalStateException());
        assertFalse(TestJob.hasValidTunnelIds(cfg));
    }

    // ---------------- isPingTunnel ----------------

    private static TunnelPool poolWithNickname(String nickname) {
        TunnelPool pool = mock(TunnelPool.class);
        TunnelPoolSettings s = mock(TunnelPoolSettings.class);
        when(s.getDestinationNickname()).thenReturn(nickname);
        when(pool.getSettings()).thenReturn(s);
        return pool;
    }

    private static PooledTunnelCreatorConfig cfgWithPool(TunnelPool pool) {
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.getTunnelPool()).thenReturn(pool);
        return cfg;
    }

    @Test
    public void testNullPoolNotPing() {
        assertFalse(TestJob.isPingTunnel(cfgWithPool(null)));
    }

    @Test
    public void testI2PingNickname() {
        assertTrue(TestJob.isPingTunnel(cfgWithPool(poolWithNickname("I2Ping"))));
    }

    @Test
    public void testBracketedPingNickname() {
        assertTrue(TestJob.isPingTunnel(cfgWithPool(poolWithNickname("Ping[1]"))));
    }

    @Test
    public void testPingWithoutBracketNotPing() {
        assertFalse(TestJob.isPingTunnel(cfgWithPool(poolWithNickname("Ping"))));
    }

    @Test
    public void testNullNicknameNotPing() {
        assertFalse(TestJob.isPingTunnel(cfgWithPool(poolWithNickname(null))));
    }

    @Test
    public void testUnrelatedNicknameNotPing() {
        assertFalse(TestJob.isPingTunnel(cfgWithPool(poolWithNickname("I2PSnark"))));
    }

    // ---------------- isEarlyExpiry ----------------

    private static PooledTunnelCreatorConfig cfgExpiring(long expiration) {
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.getExpiration()).thenReturn(expiration);
        return cfg;
    }

    @Test
    public void testWithinEarlyExpiryWindow() {
        assertTrue(TestJob.isEarlyExpiry(cfgExpiring(NOW + 60 * 1000), NOW));
    }

    @Test
    public void testAtEarlyExpiryBoundaryNotEarly() {
        // strict <: exactly the window is still testable
        assertFalse(TestJob.isEarlyExpiry(cfgExpiring(NOW + TunnelPool.DEFAULT_PRUNE_EARLY_EXPIRY), NOW));
    }

    @Test
    public void testFarFutureNotEarly() {
        assertFalse(TestJob.isEarlyExpiry(cfgExpiring(NOW + 10 * 60 * 1000), NOW));
    }

    // ---------------- isPoolCritical ----------------

    @Test
    public void testZeroActiveCritical() {
        assertTrue(TestJob.isPoolCritical(0, 4));
    }

    @Test
    public void testOneOrTwoActiveBelowTargetCritical() {
        assertTrue(TestJob.isPoolCritical(1, 4));
        assertTrue(TestJob.isPoolCritical(2, 4));
    }

    @Test
    public void testThreeActiveBelowTargetNotCritical() {
        assertFalse(TestJob.isPoolCritical(3, 4));
    }

    @Test
    public void testAtTargetNotCritical() {
        assertFalse(TestJob.isPoolCritical(4, 4));
        assertFalse(TestJob.isPoolCritical(2, 2));
    }

    // ---------------- isZeroActivePool ----------------

    private static TunnelPool poolWithActive(boolean exploratory, int activeCount) {
        TunnelPool pool = mock(TunnelPool.class);
        TunnelPoolSettings s = mock(TunnelPoolSettings.class);
        when(s.isExploratory()).thenReturn(exploratory);
        when(pool.getSettings()).thenReturn(s);
        when(pool.getActiveTunnelCount()).thenReturn(activeCount);
        return pool;
    }

    @Test
    public void testNullPoolNotZeroActive() {
        assertFalse(TestJob.isZeroActivePool(null));
    }

    @Test
    public void testExploratoryNeverZeroActiveCritical() {
        assertFalse(TestJob.isZeroActivePool(poolWithActive(true, 0)));
    }

    @Test
    public void testClientZeroActive() {
        assertTrue(TestJob.isZeroActivePool(poolWithActive(false, 0)));
    }

    @Test
    public void testClientWithActiveTunnels() {
        assertFalse(TestJob.isZeroActivePool(poolWithActive(false, 1)));
    }

    // ---------------- computeMaxTestJobs ----------------

    @Test
    public void testFlooredAtTwelveJobs() {
        assertEquals(12, TestJob.computeMaxTestJobs(40, 3, 2));
    }

    @Test
    public void testScalesWithPoolCount() {
        assertEquals(30, TestJob.computeMaxTestJobs(40, 3, 10));
    }

    @Test
    public void testScalesWithActiveRunners() {
        assertEquals(50, TestJob.computeMaxTestJobs(60, 50, 2));
    }

    @Test
    public void testCappedByQueuedLimit() {
        assertEquals(5, TestJob.computeMaxTestJobs(5, 3, 10));
    }

    // ---------------- isTestQueueOverloaded ----------------

    @Test
    public void testCriticalPoolBypassesOverload() {
        assertFalse(TestJob.isTestQueueOverloaded(true, 100, 3, 99999, 150, 999, 999));
    }

    @Test
    public void testReadyCountOutrunsRunners() {
        assertTrue(TestJob.isTestQueueOverloaded(false, 4, 3, 10, 150, 5, 10));
    }

    @Test
    public void testReadyCountAtRunnersNotOverloaded() {
        assertFalse(TestJob.isTestQueueOverloaded(false, 3, 3, 10, 150, 5, 10));
    }

    @Test
    public void testLagAboveLimitOverloaded() {
        assertTrue(TestJob.isTestQueueOverloaded(false, 0, 3, 151, 150, 5, 10));
    }

    @Test
    public void testLagAtLimitNotOverloaded() {
        assertFalse(TestJob.isTestQueueOverloaded(false, 0, 3, 150, 150, 5, 10));
    }

    @Test
    public void testJobsAtLimitOverloaded() {
        assertTrue(TestJob.isTestQueueOverloaded(false, 0, 3, 10, 150, 10, 10));
    }

    @Test
    public void testJobsBelowLimitNotOverloaded() {
        assertFalse(TestJob.isTestQueueOverloaded(false, 0, 3, 10, 150, 9, 10));
    }
}
