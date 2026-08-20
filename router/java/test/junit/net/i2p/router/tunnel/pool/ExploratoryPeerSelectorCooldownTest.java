package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * Tests for the checkTunnel-failure path in ExploratoryPeerSelector:
 * the selection must fail as an empty list (never null — the TUN-009
 * contract), and the cooldown maps must not be swept during selection
 * (TUN-010): expired entries are ignored at read time and only pruned
 * when a map exceeds its size cap, so a selection never mutates the
 * shared cooldown map.
 */
public class ExploratoryPeerSelectorCooldownTest {

    private static final long NOW = 1_000_000_000L;

    private RouterContext _ctx;
    private CommSystemFacade _commSystem;
    private ProfileOrganizer _po;
    private ExploratoryPeerSelector _selector;
    private File _tmpDir;
    private Hash _self;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-eps-cool-test-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);

        _self = hash(9);
        Router router = mock(Router.class);
        when(router.getRouterHash()).thenReturn(_self);
        when(router.isHidden()).thenReturn(false);
        RouterInfo ri = mock(RouterInfo.class);
        when(ri.getAddressCount()).thenReturn(2);
        when(router.getRouterInfo()).thenReturn(ri);
        when(_ctx.router()).thenReturn(router);
        when(_ctx.routerHash()).thenReturn(_self);

        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);

        when(_ctx.profileManager()).thenReturn(mock(net.i2p.router.ProfileManager.class));
        _po = mock(ProfileOrganizer.class);
        when(_ctx.profileOrganizer()).thenReturn(_po);
        // Not-failing selection yields two peers; the mock ignores the exclude set.
        doAnswer(inv -> {
            Set<Hash> matches = inv.getArgument(2);
            matches.add(hash(1));
            matches.add(hash(2));
            return null;
        }).when(_po).selectNotFailingPeers(anyInt(), any(Set.class), any(Set.class), anyBoolean(), anyInt(), any());

        TunnelManagerFacade tmf = mock(TunnelManagerFacade.class);
        when(tmf.getGhostPeerManager()).thenReturn(null);
        when(tmf.getInboundExploratoryPool()).thenReturn(null);
        when(tmf.getOutboundExploratoryPool()).thenReturn(null);
        when(_ctx.tunnelManager()).thenReturn(tmf);

        // ConnectChecker.canConnect resolves RIs via netDb; an unknown pair
        // (null lookup) is treated as "can't connect", which fails checkTunnel.
        when(_ctx.netDb()).thenReturn(mock(NetworkDatabaseFacade.class));

        _commSystem = mock(CommSystemFacade.class);
        when(_commSystem.countActivePeers()).thenReturn(0);
        when(_commSystem.haveInboundCapacity(anyInt())).thenReturn(true);
        when(_commSystem.haveHighOutboundCapacity()).thenReturn(true);
        when(_commSystem.isEstablished(any(Hash.class))).thenReturn(false);
        when(_commSystem.wasUnreachable(any(Hash.class))).thenReturn(false);
        when(_commSystem.isConnecting(any(Hash.class))).thenReturn(false);
        when(_ctx.commSystem()).thenReturn(_commSystem);

        // Defaults for the exploratory selector tuning properties
        // (min active peers etc.) keep the selection on the not-failing path.
        when(_ctx.getProperty(anyString(), anyInt())).thenReturn(6);

        TunnelPeerSelector._peerCooldowns.clear();
        _selector = new ExploratoryPeerSelector(_ctx);
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

    private static TunnelPoolSettings settings(boolean isInbound) {
        TunnelPoolSettings s = new TunnelPoolSettings(isInbound);
        s.setLength(2);
        return s;
    }

    @Test
    public void testInboundCheckTunnelFailureReturnsEmptyNotNull() {
        List<Hash> rv = _selector.selectPeers(settings(true));
        assertNotNull(rv);
        assertTrue(rv.isEmpty());
    }

    @Test
    public void testOutboundCheckTunnelFailureReturnsEmptyNotNull() {
        List<Hash> rv = _selector.selectPeers(settings(false));
        assertNotNull(rv);
        assertTrue(rv.isEmpty());
    }

    @Test
    public void testSelectionDoesNotSweepSharedCooldowns() {
        // Fresh entry stays in the exclusion window; expired entry must be
        // ignored at read time, not removed from the map by the selection.
        Hash fresh = hash(8);
        Hash expired = hash(7);
        TunnelPeerSelector._peerCooldowns.put(fresh, NOW);
        TunnelPeerSelector._peerCooldowns.put(expired, NOW - TunnelPeerSelector.PEER_SELECTION_COOLDOWN_MS - 1);
        assertEquals(2, TunnelPeerSelector._peerCooldowns.size());

        List<Hash> rv = _selector.selectPeers(settings(true));
        assertNotNull(rv);
        assertTrue(rv.isEmpty());
        assertEquals(2, TunnelPeerSelector._peerCooldowns.size());
    }

    @Test
    public void testNoPeersAvailableNeverReturnsNull() {
        // Empty profileOrganizer selection must not return null either:
        // with no candidates the selection degrades to a self-only list
        // (zero-hop), which is a valid non-null result.
        doNothing().when(_po).selectNotFailingPeers(anyInt(), any(Set.class), any(Set.class), anyBoolean(), anyInt(), any());
        List<Hash> rv = _selector.selectPeers(settings(true));
        assertNotNull(rv);
        assertEquals(Collections.singletonList(_self), rv);
    }
}