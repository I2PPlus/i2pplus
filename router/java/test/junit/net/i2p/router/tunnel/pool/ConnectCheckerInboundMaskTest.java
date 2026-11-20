package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.LogManager;
import net.i2p.util.OrderedProperties;

/**
 * Which of our own published routes count as inbound, i.e. as a route someone
 * outside can dial us on.
 *
 * <p>The defect: the inbound mask was chosen from our reachability status and,
 * for a hostless address, from its {@code caps} option. Neither is evidence that
 * anyone can connect to us. An outbound-only router publishes an NTCP2 or SSU2
 * address with {@code caps} and no host or port at all, and at the UNKNOWN,
 * DISCONNECTED and HOSED statuses nothing published was consulted, so the mask
 * reported IPv4 routes the router never had. The tunnel validator then accepted
 * an inbound tunnel whose adjacent hop we had no session with, the build failed,
 * and that hop was penalized for a route we invented.
 *
 * @since 0.9.72+
 */
public class ConnectCheckerInboundMaskTest {

    /** A bind port the transports publish, high enough to be a valid one. */
    private static final int PORT = 37772;

    /** The port value an address carries when it has none. */
    private static final int NO_PORT = 0;

    /** Prefix of the scratch config directory the logger writes into. */
    private static final String SCRATCH = System.getProperty("java.io.tmpdir") + "/i2p-ccim-test-";

    private RouterContext _ctx;
    private CommSystemFacade _cs;
    private Router _router;
    private ConnectChecker _checker;
    private File _tmpDir;

    /**
     * A fixture group for the inbound mask; it holds no state.
     *
     * @since 0.9.72+
     */
    public ConnectCheckerInboundMaskTest() {}

    /**
     * A checker over a mocked context: the router, the comm system and a working
     * log are all it touches.
     */
    @Before
    public void setUp() {
        _tmpDir = new File(SCRATCH + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        String loggerConfig = new File(_tmpDir, "logger.config").getAbsolutePath();
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(loggerConfig);
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);
        _cs = mock(CommSystemFacade.class);
        when(_ctx.commSystem()).thenReturn(_cs);
        _router = mock(Router.class);
        when(_ctx.router()).thenReturn(_router);
        _checker = new ConnectChecker(_ctx);
    }

    /**
     * The logger writes into the scratch config directory, so take it away again.
     */
    @After
    public void tearDown() {
        deleteRecursively(_tmpDir);
    }

    /**
     * @param f the file or directory to remove, along with anything under it
     */
    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {deleteRecursively(c);}
        }
        f.delete();
    }

    /**
     * The defect: the outbound-only NTCP2 address our own transport publishes -
     * {@code caps}, a static key and a version, deliberately no host or port -
     * was read as an IPv4 inbound route, so a peer with a v4 address was told it
     * could reach us.
     */
    @Test
    public void testAnOutboundOnlyNTCP2AddressIsNotAnInboundRoute() {
        OrderedProperties props = new OrderedProperties();
        props.setProperty("caps", "4");
        props.setProperty("s", "some-static-key");
        props.setProperty("v", "2");
        List<RouterAddress> addrs = Collections.singletonList(new RouterAddress("NTCP2", props, 5));
        assertEquals("an outbound-only NTCP2 address counted as dialable",
                     0, ConnectChecker.getConnectMask(addrs, true));
        assertEquals("the outbound mask lost the family the caps option describes",
                     ConnectChecker.NTCP_V4, ConnectChecker.getConnectMask(addrs, false));
    }

    /**
     * The same for the outbound-only SSU2 address: {@code caps} and the SSU2
     * options, no host and no port.
     */
    @Test
    public void testAnOutboundOnlySSU2AddressIsNotAnInboundRoute() {
        List<RouterAddress> addrs = Collections.singletonList(ssu2("4", null, NO_PORT, null));
        assertEquals("an outbound-only SSU2 address counted as dialable",
                     0, ConnectChecker.getConnectMask(addrs, true));
        assertEquals("the outbound mask lost the family the caps option describes",
                     ConnectChecker.SSU2_V4, ConnectChecker.getConnectMask(addrs, false));
    }

    /**
     * An address with a host but no usable port cannot be dialed either - the
     * transports write no port when they have no bind port.
     */
    @Test
    public void testAnAddressWithoutAValidPortIsNotAnInboundRoute() {
        List<RouterAddress> addrs = Collections.singletonList(ntcp2("1.2.3.4", NO_PORT));
        assertEquals(0, ConnectChecker.getConnectMask(addrs, true));
        assertEquals(ConnectChecker.NTCP_V4, ConnectChecker.getConnectMask(addrs, false));
    }

    /**
     * A host and a port are a route: the mask keeps counting what has always
     * counted, and the SSU2 family bit the fork added is still there.
     */
    @Test
    public void testADialableAddressIsStillAnInboundRoute() {
        Collection<RouterAddress> addrs = Arrays.asList(ntcp2("1.2.3.4", PORT),
                                                        ssu2("4", "1.2.3.4", PORT, null));
        assertEquals(ConnectChecker.NTCP_V4 | ConnectChecker.SSU2_V4,
                     ConnectChecker.getConnectMask(addrs, true));
    }

    /**
     * Our own published port is not judged by the heuristic for other routers.
     * TransportUtil.isValidPort() rejects a list of ports a remote peer should
     * not be using - Tor's 9030, the standard I2P range 7650-7668, 9100 - and
     * the transports validate only the bind port, so a NAT that maps our socket
     * into one of those ranges published a route we then refused to count. The
     * inbound mask went to 0, every peer we were not already connected to was
     * excluded as the closest hop of an inbound tunnel, and inbound tunnels were
     * built out of the few peers we had sessions with.
     */
    @Test
    public void ourOwnPortIsNotJudgedByTheRemotePeerHeuristic() {
        List<RouterAddress> addrs = Collections.singletonList(ssu2("4", "1.2.3.4", 9030, null));
        assertEquals("a NAT mapping onto Tor's port hid our inbound route",
                     ConnectChecker.SSU2_V4, ConnectChecker.getConnectMask(addrs, true));

        addrs = Collections.singletonList(ntcp2("1.2.3.4", 7650));
        assertEquals("a NAT mapping inside the standard I2P range hid our inbound route",
                     ConnectChecker.NTCP_V4, ConnectChecker.getConnectMask(addrs, true));
    }

    /**
     * An SSU address with no host but an introducer tag is reachable - the
     * introducer dials us on the peer's behalf - so it stays an inbound route.
     * An SSU v1 introducer host counts the same way.
     */
    @Test
    public void testAnIntroducerAddressIsStillAnInboundRoute() {
        List<RouterAddress> tagged = Collections.singletonList(ssu2("4", null, NO_PORT, "intag"));
        assertEquals(ConnectChecker.SSU2_V4, ConnectChecker.getConnectMask(tagged, true));

        OrderedProperties props = new OrderedProperties();
        props.setProperty("caps", "4");
        props.setProperty("ihost0", "5.6.7.8");
        List<RouterAddress> v1 = Collections.singletonList(new RouterAddress("SSU", props, 7));
        assertEquals(ConnectChecker.SSU_V4, ConnectChecker.getConnectMask(v1, true));
    }

    /**
     * Nothing published that can be dialed means the adjacent hop of an inbound
     * tunnel has to be a peer we already have a session with.
     */
    @Test
    public void testNoDialableRouteRequiresAnEstablishedPeer() {
        RouterInfo us = routerInfo(ssu2("4", null, NO_PORT, null));
        assertEquals(0, _checker.getInboundMask(us));
        assertTrue(_checker.requiresConnectedInboundPeer(us));
    }

    /**
     * Under a symmetric NAT the port we published is a mapping only the peer we
     * sent a packet to can use, so IPv4 UDP routes alone do not make us dialable
     * by the peer about to build through us.
     */
    @Test
    public void testSymmetricNATWithOnlyIPv4UDPRoutesRequiresAnEstablishedPeer() {
        when(_cs.getStatus()).thenReturn(Status.IPV4_SNAT_IPV6_UNKNOWN);
        RouterInfo us = routerInfo(ssu2("4", "1.2.3.4", PORT, null));
        assertTrue("an SSU2 v4 route under a symmetric NAT was treated as dialable by any peer",
                   _checker.requiresConnectedInboundPeer(us));

        // A v6 route is not behind the v4 NAT, so it is enough on its own.
        RouterInfo v6 = routerInfo(ssu2("6", "2001:db8::1", PORT, null));
        assertFalse(_checker.requiresConnectedInboundPeer(v6));
    }

    /**
     * A dialable v4 route under a status that is not a symmetric NAT needs no
     * established adjacent hop - the fix must not push every router onto the
     * established-peer path.
     */
    @Test
    public void testADialableRouteUnderOKNeedsNoEstablishedPeer() {
        when(_cs.getStatus()).thenReturn(Status.OK);
        RouterInfo us = routerInfo(ntcp2("1.2.3.4", PORT));
        assertEquals(ConnectChecker.NTCP_V4, _checker.getInboundMask(us));
        assertFalse(_checker.requiresConnectedInboundPeer(us));
    }

    /**
     * The defect as the peer selectors see it: an outbound-only router publishes
     * addresses, and its transport has a bind address under its inbound
     * connection limit, so the old {@code hidden} test was false and the closest
     * hop of an inbound tunnel was picked from peers we had no session with.
     */
    @Test
    public void testAnOutboundOnlyRouterNeedsAnEstablishedAdjacentHop() {
        when(_router.isHidden()).thenReturn(false);
        when(_router.getRouterInfo()).thenReturn(routerInfo(ssu2("4", null, NO_PORT, null)));
        when(_cs.haveInboundCapacity(anyInt())).thenReturn(true);
        assertTrue("an outbound-only router let an unconnected peer be the closest hop",
                   _checker.inboundHopMustBeConnected());
    }

    /**
     * The other side of that decision: a router with a dialable route, a hidden
     * flag of false and spare inbound capacity keeps its current behaviour.
     */
    @Test
    public void testADialableRouterNeedsNoEstablishedAdjacentHop() {
        when(_cs.getStatus()).thenReturn(Status.OK);
        when(_router.isHidden()).thenReturn(false);
        when(_router.getRouterInfo()).thenReturn(routerInfo(ntcp2("1.2.3.4", PORT)));
        when(_cs.haveInboundCapacity(anyInt())).thenReturn(true);
        assertFalse(_checker.inboundHopMustBeConnected());
    }

    /**
     * @param host the address's host, null for the outbound-only form
     * @param port the bind port, 0 for none
     * @return an NTCP2 address in the form our transport publishes
     */
    private static RouterAddress ntcp2(String host, int port) {
        OrderedProperties props = new OrderedProperties();
        if (host != null) {
            props.setProperty(RouterAddress.PROP_HOST, host);
            props.setProperty(RouterAddress.PROP_PORT, Integer.toString(port));
            props.setProperty("i", "some-static-iv");
        } else {
            props.setProperty("caps", "4");
        }
        props.setProperty("s", "some-static-key");
        props.setProperty("v", "2");
        return new RouterAddress("NTCP2", props, 5);
    }

    /**
     * @param caps the family the address advertises, "4" or "6"
     * @param host the address's host, null for the outbound-only form
     * @param port the bind port, 0 for none
     * @param itag an introducer tag, null if there is none
     * @return an SSU2 address in the form our transport publishes
     */
    private static RouterAddress ssu2(String caps, String host, int port, String itag) {
        OrderedProperties props = new OrderedProperties();
        if (host != null) {
            props.setProperty(RouterAddress.PROP_HOST, host);
            props.setProperty(RouterAddress.PROP_PORT, Integer.toString(port));
        } else {
            props.setProperty("caps", caps);
        }
        if (itag != null)
            props.setProperty("itag0", itag);
        props.setProperty("v", "2");
        props.setProperty("s", "some-static-key");
        return new RouterAddress("SSU2", props, 7);
    }

    /**
     * @param addrs the addresses we published
     * @return a router info carrying them, as our own would
     */
    private static RouterInfo routerInfo(RouterAddress... addrs) {
        RouterInfo ri = new RouterInfo();
        ri.setAddresses(Arrays.asList(addrs));
        return ri;
    }
}
