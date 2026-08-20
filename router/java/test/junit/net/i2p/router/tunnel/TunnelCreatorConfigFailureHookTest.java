package net.i2p.router.tunnel;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * The tunnelFailedFirstHop() hook lives on the base TunnelCreatorConfig
 * (TUN-001: breaks the tunnel -> pool package cycle); PooledTunnelCreatorConfig
 * only adds the pool callback.  Verifies the base hook semantics and that the
 * data plane (OutboundReceiver) accepts a non-pooled config.
 */
public class TunnelCreatorConfigFailureHookTest {

    private RouterContext _ctx;
    private File _tmpDir;
    private Hash _peer;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-tccfg-test-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);

        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1_000_000_000L);
        when(_ctx.clock()).thenReturn(clock);

        when(_ctx.netDb()).thenReturn(mock(NetworkDatabaseFacade.class));

        _peer = Hash.create(new byte[Hash.HASH_LENGTH]);
    }

    @After
    public void tearDown() {
        File[] children = _tmpDir.listFiles();
        if (children != null) {
            for (File c : children) {c.delete();}
        }
        _tmpDir.delete();
    }

    @Test
    public void testOutboundFailureMarksConfigFailed() {
        TCConfig cfg = new TCConfig(_ctx, 2, false);
        cfg.tunnelFailedFirstHop();
        assertTrue(cfg.getTunnelFailed());
    }

    @Test
    public void testInboundFailureIsIgnored() {
        TCConfig cfg = new TCConfig(_ctx, 2, true);
        cfg.tunnelFailedFirstHop();
        assertFalse(cfg.getTunnelFailed());
    }

    @Test
    public void testZeroHopFailureIsIgnored() {
        TCConfig cfg = new TCConfig(_ctx, 1, false);
        cfg.tunnelFailedFirstHop();
        assertFalse(cfg.getTunnelFailed());
    }

    @Test
    public void testOutboundReceiverAcceptsBaseConfig() {
        // Would not compile while OutboundReceiver was typed on the pool
        // subclass; proves the data plane no longer depends on pool.
        TCConfig cfg = new TCConfig(_ctx, 2, false);
        cfg.setPeer(1, _peer);
        OutboundReceiver receiver = new OutboundReceiver(_ctx, cfg);
        assertSame(_peer, receiver.getSendTo());
    }
}