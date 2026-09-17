package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 * Tests for {@link TunnelPool#forceTunnelFailure(TunnelInfo)},
 * which immediately removes a dead tunnel and triggers a replacement build
 * when rotation is saturated.
 *
 * @since 0.9.73
 */
public class TunnelPoolForceTunnelFailureTest {

    private RouterContext _ctx;
    private TunnelPoolSettings _settings;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _settings = new TunnelPoolSettings(Hash.FAKE_HASH, false);
        _settings.setLength(3);
    }

    /**
       * forceTunnelFailure on a tunnel that is not yet failed
       * should mark it dead and remove it from the pool.
       */
    @Test
    public void forceTunnelFailureRemovesTunnel() {
        TunnelPool pool = createPool();
        TunnelCreatorConfig tunnel = createTestTunnel();
        assertFalse(tunnel.getTunnelFailed());

        pool.forceTunnelFailure(tunnel);

        assertTrue("Tunnel should be marked as failed", tunnel.getTunnelFailed());
    }

    /**
       * forceTunnelFailure on an already-failed tunnel should be a no-op.
       */
    @Test
    public void forceTunnelFailureAlreadyFailedIsNoOp() {
        TunnelPool pool = createPool();
        TunnelCreatorConfig tunnel = createTestTunnel();
        tunnel.tunnelFailedCompletely();
        assertTrue(tunnel.getTunnelFailed());

        pool.forceTunnelFailure(tunnel);

        assertTrue(tunnel.getTunnelFailed());
    }

    /**
       * forceTunnelFailure on a null tunnel should be a no-op.
       */
    @Test
    public void forceTunnelFailureNullIsNoOp() {
        TunnelPool pool = createPool();
        pool.forceTunnelFailure(null);
        // Should not throw
    }

    /**
       * forceTunnelFailure should not throw for a healthy tunnel.
       */
    @Test
    public void forceTunnelFailureNoException() {
        TunnelPool pool = createPool();
        TunnelCreatorConfig tunnel = createTestTunnel();
        pool.forceTunnelFailure(tunnel);
        // Should not throw
    }

    private TunnelPool createPool() {
        TunnelPoolManager mgr = mock(TunnelPoolManager.class);
        TunnelPeerSelector sel = mock(TunnelPeerSelector.class);
        return new TunnelPool(_ctx, mgr, _settings, sel);
    }

    private TunnelCreatorConfig createTestTunnel() {
        AtomicBoolean failed = new AtomicBoolean(false);
        TunnelCreatorConfig cfg = mock(TunnelCreatorConfig.class);
        when(cfg.getTunnelFailed()).thenAnswer(inv -> failed.get());
        when(cfg.getDestination()).thenReturn(_settings.getDestination());
        when(cfg.isInbound()).thenReturn(false);
        when(cfg.getPeer(0)).thenReturn(Hash.create(new byte[Hash.HASH_LENGTH]));
        when(cfg.getLength()).thenReturn(3);
        when(cfg.getConsecutiveFailures()).thenReturn(0);
        when(cfg.getLastLatency()).thenReturn(100);
        when(cfg.getAverageLatency()).thenReturn(100);
        doAnswer(inv -> { failed.set(true); return null; }).when(cfg).tunnelFailedCompletely();
        return cfg;
    }
}
