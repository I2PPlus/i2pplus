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
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;
import net.i2p.util.OrderedProperties;

/**
 * Tests for the inbound-only quality sort in ClientPeerSelector.finalizeSelection:
 * inbound selections get best-first hops, outbound selections preserve the
 * key-distance ordering from orderPeers() so the peer adjacent to us stays the
 * vetted first hop (compareAcceptance sorts the strongest peer to the front,
 * which for outbound would exile it to the OBEP and put the worst-ranked peer
 * adjacent to us).
 *
 * @since 0.9.71+
 */
public class ClientPeerSelectorOrderTest {

    private static final long NOW = 1_000_000_000L;

    private RouterContext _ctx;
    private ClientPeerSelector _selector;
    private File _tmpDir;
    private Hash _self;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-cpsorder-test-" + System.nanoTime());
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
        // Peer hash 2 is good (0.8), hash 3 is dead (0.0): quality sort must
        // pull hash 2 to the front.
        net.i2p.router.peermanager.ProfileOrganizer po =
            mock(net.i2p.router.peermanager.ProfileOrganizer.class);
        when(po.getProfile(any(Hash.class))).thenAnswer(inv -> {
            Hash h = inv.getArgument(0);
            PeerProfile p = mock(PeerProfile.class);
            when(p.getTunnelAcceptanceRatio()).thenReturn(h.getData()[0] == 2 ? 0.8 : 0.0);
            when(p.getLastHeardFrom()).thenReturn(NOW - 1000L);
            when(p.getTunnelTestTimeAverage()).thenReturn(1000f);
            return p;
        });
        when(_ctx.profileOrganizer()).thenReturn(po);
        TunnelManagerFacade tmf = mock(TunnelManagerFacade.class);
        when(tmf.getGhostPeerManager()).thenReturn(null);
        when(_ctx.tunnelManager()).thenReturn(tmf);

        // ConnectChecker resolves RIs via netDb; return a publishable SSU IPv4
        // RouterInfo for every hash (including our own) so checkTunnel passes
        // and the selection survives finalizeSelection.
        NetworkDatabaseFacade ndb = mock(NetworkDatabaseFacade.class);
        when(ndb.lookupRouterInfoLocally(any(Hash.class))).thenReturn(ssuRouterInfo());
        when(ndb.lookupLocallyWithoutValidation(any(Hash.class))).thenReturn(ssuRouterInfo());
        when(_ctx.netDb()).thenReturn(ndb);

        CommSystemFacade cs = mock(CommSystemFacade.class);
        when(cs.getStatus()).thenReturn(CommSystemFacade.Status.OK);
        when(cs.isEstablished(any(Hash.class))).thenReturn(false);
        when(cs.wasUnreachable(any(Hash.class))).thenReturn(false);
        when(_ctx.commSystem()).thenReturn(cs);

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

    /** A RouterInfo carrying a public SSU IPv4 address: connectable both ways. */
    private static RouterInfo ssuRouterInfo() {
        OrderedProperties opts = new OrderedProperties();
        opts.setProperty("host", "1.2.3.4");
        opts.setProperty("port", "1234");
        RouterAddress ra = new RouterAddress("SSU", opts, 42);
        RouterInfo ri = new RouterInfo();
        ri.setAddresses(Collections.singletonList(ra));
        return ri;
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    @Test
    public void testOutboundPreservesSelectionOrder() {
        Hash first = hash(3), second = hash(2);
        List<Hash> rv = _selector.finalizeSelection(new TunnelPoolSettings(false),
                                                    new ArrayList<>(Arrays.asList(first, second)), false);
        assertNotNull(rv);
        assertEquals(Arrays.asList(first, second, _self), rv);
    }

    @Test
    public void testInboundSortsBestFirst() {
        Hash weak = hash(3), strong = hash(2);
        List<Hash> rv = _selector.finalizeSelection(new TunnelPoolSettings(true),
                                                    new ArrayList<>(Arrays.asList(weak, strong)), true);
        assertNotNull(rv);
        assertEquals(Arrays.asList(_self, strong, weak), rv);
    }
}