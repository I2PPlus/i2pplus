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
 * A timed-out build may only penalize the hop the build request was
 * dispatched to: the profile penalty and ghost mark go to peer 0 for
 * inbound / peer 1 for outbound builds, never to middle or end hops
 * that may not even have received the request.
 *
 * @since 0.9.71+
 */
public class BuildExecutorTimeoutBlameTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    private static BuildExecutor newExecutor(GhostPeerManager ghost) {
        return new BuildExecutor(_ctx, mock(TunnelPoolManager.class), ghost);
    }

    private static PooledTunnelCreatorConfig buildConfig(boolean inbound, Hash... peers) {
        TunnelPoolSettings settings = new TunnelPoolSettings(inbound);
        TunnelPool pool = new TunnelPool(_ctx, mock(TunnelPoolManager.class), settings, mock(TunnelPeerSelector.class));
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, peers.length, inbound, null, pool);
        for (int i = 0; i < peers.length; i++) {
            cfg.setPeer(i, peers[i]);
        }
        return cfg;
    }

    @Test
    public void testInboundTimeoutBlamesOnlyGateway() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        GhostPeerManager ghost = mock(GhostPeerManager.class);
        BuildExecutor exec = newExecutor(ghost);
        Hash ibgw = hash(1), mid = hash(2), end = hash(3);
        PooledTunnelCreatorConfig cfg = buildConfig(true, ibgw, mid, end);

        exec.penalizeTimeout(cfg);

        verify(ghost).recordTimeout(ibgw);
        verify(ghost, never()).recordTimeout(mid);
        verify(ghost, never()).recordTimeout(end);
    }

    @Test
    public void testOutboundTimeoutBlamesOnlyNextHop() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        GhostPeerManager ghost = mock(GhostPeerManager.class);
        BuildExecutor exec = newExecutor(ghost);
        Hash start = hash(1), obep = hash(2), end = hash(3);
        PooledTunnelCreatorConfig cfg = buildConfig(false, start, obep, end);

        exec.penalizeTimeout(cfg);

        verify(ghost, never()).recordTimeout(start);
        verify(ghost).recordTimeout(obep);
        verify(ghost, never()).recordTimeout(end);
    }

    @Test
    public void testSelfGatewayNeverPenalized() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        GhostPeerManager ghost = mock(GhostPeerManager.class);
        BuildExecutor exec = newExecutor(ghost);
        Hash self = _ctx.routerHash(), mid = hash(2), end = hash(3);
        PooledTunnelCreatorConfig cfg = buildConfig(true, self, mid, end);

        exec.penalizeTimeout(cfg);

        verify(ghost, never()).recordTimeout(any(Hash.class));
    }
}