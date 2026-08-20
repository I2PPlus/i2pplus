package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;

/**
 * Tests the selection orchestration extracted into
 * {@link TunnelPool#selectFromPool(long, long, boolean)} (TUN-022):
 * empty pool -> null, pool with a usable tunnel -> that tunnel.
 *
 * @since 0.9.71+
 */
public class TunnelPoolSelectTunnelTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    /** Real pool over a real context; manager and peer selector are mocks. */
    private static TunnelPool createPool(TunnelPoolSettings settings) {
        TunnelPoolManager mgr = mock(TunnelPoolManager.class);
        TunnelPeerSelector sel = mock(TunnelPeerSelector.class);
        return new TunnelPool(_ctx, mgr, settings, sel);
    }

    /**
     * An empty client pool with zero-hop disabled must return null
     * without building a fallback (zero-hop is refused) and without recursing.
     */
    @Test
    public void testEmptyPoolReturnsNull() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolSettings settings = new TunnelPoolSettings(dest(), false);
        settings.setLength(2);
        assertFalse(settings.isExploratory());
        TunnelPool pool = createPool(settings);
        assertNull(pool.selectTunnel());
    }

    /**
     * A pool holding one fresh inbound tunnel must return that tunnel.
     */
    @Test
    public void testPoolWithUsableTunnelReturnsIt() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolSettings settings = new TunnelPoolSettings(true);
        settings.setLength(2);
        TunnelPool pool = createPool(settings);
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        cfg.getConfig(0).setReceiveTunnelId(12345L);
        cfg.setExpiration(_ctx.clock().now() + 60L * 60 * 1000);
        pool.addTunnel(cfg);
        assertSame(cfg, pool.selectTunnel());
    }

    private static Hash dest() {
        return Hash.create(new byte[Hash.HASH_LENGTH]);
    }
}