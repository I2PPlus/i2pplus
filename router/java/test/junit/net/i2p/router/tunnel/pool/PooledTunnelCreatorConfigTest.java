package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;

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
        assertFalse(cfg.tunnelFailed());
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
}
