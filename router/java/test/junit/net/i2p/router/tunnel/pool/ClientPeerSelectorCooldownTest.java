package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * Tests for the checkTunnel-failure path in ClientPeerSelector.finalizeSelection:
 * the selection must fail as an empty list (never null), and only the peer
 * adjacent to us (IBGW for inbound, OBEP for outbound) may be cooled down —
 * the peers of the failing edge are blamed via profileManager().tunnelTimedOut(),
 * but innocent middle hops must not pay a blanket cooldown.
 */
public class ClientPeerSelectorCooldownTest {

    private static final long NOW = 1_000_000_000L;

    private RouterContext _ctx;
    private CommSystemFacade _commSystem;
    private ClientPeerSelector _selector;
    private File _tmpDir;
    private Hash _self;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-cpscool-test-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);

        _self = hash(9);
        Router router = mock(Router.class);
        when(router.getRouterHash()).thenReturn(_self);
        when(_ctx.router()).thenReturn(router);
        when(_ctx.routerHash()).thenReturn(_self);

        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);

        when(_ctx.profileManager()).thenReturn(mock(net.i2p.router.ProfileManager.class));
        when(_ctx.profileOrganizer()).thenReturn(mock(net.i2p.router.peermanager.ProfileOrganizer.class));
        TunnelManagerFacade tmf = mock(TunnelManagerFacade.class);
        when(tmf.getGhostPeerManager()).thenReturn(null);
        when(_ctx.tunnelManager()).thenReturn(tmf);

        // ConnectChecker.canConnect resolves RIs via netDb; an unknown pair
        // (null lookup) is treated as "can't connect", which fails checkTunnel.
        when(_ctx.netDb()).thenReturn(mock(NetworkDatabaseFacade.class));

        _commSystem = mock(CommSystemFacade.class);
        when(_ctx.commSystem()).thenReturn(_commSystem);

        TunnelPeerSelector._peerCooldowns.clear();
        _selector = new ClientPeerSelector(_ctx);
    }

    @After
    public void tearDown() {
        TunnelPeerSelector._peerCooldowns.clear();
        File[] children = _tmpDir.listFiles();
        if (children != null) {
            for (File c : children) {c.delete();}
        }
        _tmpDir.delete();
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    @Test
    public void testInboundFailureCooldownsOnlyAdjacentPeer() {
        Hash ibgw = hash(1), middle = hash(2);
        TunnelPoolSettings settings = new TunnelPoolSettings(true);
        List<Hash> rv = _selector.finalizeSelection(settings,
                                                    new ArrayList<>(Arrays.asList(ibgw, middle)), true);
        assertNotNull(rv);
        assertTrue(rv.isEmpty());
        assertEquals(Collections.singleton(ibgw), TunnelPeerSelector._peerCooldowns.keySet());
    }

    @Test
    public void testOutboundFailureCooldownsOnlyAdjacentPeer() {
        Hash obep = hash(1), middle = hash(2);
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        List<Hash> rv = _selector.finalizeSelection(settings,
                                                    new ArrayList<>(Arrays.asList(middle, obep)), false);
        assertNotNull(rv);
        assertTrue(rv.isEmpty());
        assertEquals(Collections.singleton(obep), TunnelPeerSelector._peerCooldowns.keySet());
    }
}