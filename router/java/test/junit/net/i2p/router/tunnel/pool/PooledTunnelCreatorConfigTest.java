package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 * Tests for PooledTunnelCreatorConfig state tracking.
 *
 * @since 0.9.70+
 */
public class PooledTunnelCreatorConfigTest {

    private static RouterContext _ctx;
    private static TunnelPool _pool;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.getContext();
        if (_ctx != null) {
            TunnelPool pool = mock(TunnelPool.class);
            TunnelPoolSettings settings = mock(TunnelPoolSettings.class);
            when(pool.getSettings()).thenReturn(settings);
            when(settings.getDestinationNickname()).thenReturn(null);
            _pool = pool;
        }
    }

    private PooledTunnelCreatorConfig createConfig(int length, boolean isInbound) {
        return new PooledTunnelCreatorConfig(_ctx, length, isInbound, null, _pool);
    }

    @Test
    public void testLastResortFlag() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        PooledTunnelCreatorConfig cfg = createConfig(3, false);
        assertFalse(cfg.isLastResort());
        cfg.setLastResort();
        assertTrue(cfg.isLastResort());
    }

    @Test
    public void testActivityTracking() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        PooledTunnelCreatorConfig cfg = createConfig(3, false);
        long before = cfg.getLastActivity();
        assertTrue(before > 0);
        Thread.sleep(5);
        cfg.recordActivity();
        long after = cfg.getLastActivity();
        assertTrue("Activity timestamp should advance", after > before);
    }

    @Test
    public void testIsRecentlyActive() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        PooledTunnelCreatorConfig cfg = createConfig(3, false);
        assertTrue(cfg.isRecentlyActive());
        cfg.recordActivity();
        assertTrue(cfg.isRecentlyActive());
    }

    @Test
    public void testTunnelFailure() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        PooledTunnelCreatorConfig cfg = createConfig(3, false);
        // the first MAX failures are allowed; only after that does the tunnel fail
        for (int i = 0; i < TunnelCreatorConfig.MAX_CONSECUTIVE_TEST_FAILURES; i++) {
            assertTrue("Failure " + (i + 1) + " should still be allowed", cfg.tunnelFailed());
        }
        assertFalse("Exhausted test failures should fail the tunnel", cfg.tunnelFailed());
    }

    @Test
    public void testInboundConfig() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        PooledTunnelCreatorConfig cfg = createConfig(3, true);
        assertTrue(cfg.isInbound());
    }

    @Test
    public void testOutboundConfig() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        PooledTunnelCreatorConfig cfg = createConfig(3, false);
        assertFalse(cfg.isInbound());
    }

    /**
     * The first-hop re-warm must fire on exactly one strike: the last one at
     * which the tunnel is still alive to benefit. Earlier strikes have not
     * committed to blaming the peer, so a DatabaseLookupMessage round-trip
     * would only add load to a path that may merely be congested; the
     * threshold strike retires the tunnel as soon as the hook returns, so
     * re-warming there would query a hop the router has already blamed.
     */
    @Test
    public void testPreConnectGateFiresOnlyOneBelowThreshold() {
        int th = TunnelCreatorConfig.FIRST_HOP_FAILURE_THRESHOLD;
        assertTrue("threshold must leave room for a strike below it", th >= 2);
        // the streak is counted from 1; 0 means "no streak" and must never warm
        assertFalse(PooledTunnelCreatorConfig.shouldPreConnect(0));
        for (int streak = 1; streak < th - 1; streak++) {
            assertFalse("streak " + streak + " is too early to pre-connect",
                        PooledTunnelCreatorConfig.shouldPreConnect(streak));
        }
        assertTrue(PooledTunnelCreatorConfig.shouldPreConnect(th - 1));
        assertFalse("streak " + th + " retires the tunnel anyway",
                    PooledTunnelCreatorConfig.shouldPreConnect(th));
        assertFalse(PooledTunnelCreatorConfig.shouldPreConnect(th + 1));
    }
}
