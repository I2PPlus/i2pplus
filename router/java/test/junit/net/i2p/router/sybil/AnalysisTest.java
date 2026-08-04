package net.i2p.router.sybil;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.Certificate;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.util.OrderedProperties;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests for the Sybil analyzer's IP-grouping calculations. These
 *  group routers by shared /32, /16 (IPv4) and /64, /48 (IPv6)
 *  prefixes and award points proportional to the number of peers
 *  sharing the prefix.
 *
 *  The Analysis instance is constructed reflectively to avoid
 *  starting the full analysis job; the grouping methods themselves
 *  are pure with respect to the router state.
 *
 *  @since 0.9.38
 */
public class AnalysisTest {

    private static RouterContext _ctx;
    private static Analysis _analysis;

    @BeforeClass
    public static void setUp() throws Exception {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Constructor<Analysis> ctor = Analysis.class.getDeclaredConstructor(
                RouterContext.class, net.i2p.app.ClientAppManager.class, String[].class);
        ctor.setAccessible(true);
        _analysis = ctor.newInstance(_ctx, null, null);
    }

    /** router identity with a unique hash and the given IPv4 address */
    private static RouterInfo routerV4(int seed, String ipv4) {
        RouterInfo info = new RouterInfo();
        RouterIdentity ident = new RouterIdentity();
        byte[] pk = new byte[PublicKey.KEYSIZE_BYTES];
        pk[pk.length - 1] = (byte) seed;
        ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        ident.setPublicKey(new PublicKey(pk));
        byte[] spk = new byte[SigningPublicKey.KEYSIZE_BYTES];
        spk[0] = (byte) seed;
        spk[1] = (byte) (seed >> 8);
        ident.setSigningPublicKey(new SigningPublicKey(spk));
        info.setIdentity(ident);
        info.setAddresses(addresses(ipv4));
        return info;
    }

    /** router with the given IPv6 address */
    private static RouterInfo routerV6(int seed, String ipv6) {
        RouterInfo info = new RouterInfo();
        RouterIdentity ident = new RouterIdentity();
        byte[] pk = new byte[PublicKey.KEYSIZE_BYTES];
        pk[pk.length - 1] = (byte) seed;
        ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        ident.setPublicKey(new PublicKey(pk));
        byte[] spk = new byte[SigningPublicKey.KEYSIZE_BYTES];
        spk[0] = (byte) seed;
        spk[1] = (byte) (seed >> 8);
        ident.setSigningPublicKey(new SigningPublicKey(spk));
        info.setIdentity(ident);
        info.setAddresses(addresses(ipv6));
        return info;
    }

    private static Set<RouterAddress> addresses(String host) {
        OrderedProperties props = new OrderedProperties();
        props.setProperty("host", host);
        props.setProperty("port", "7654");
        Set<RouterAddress> rv = new HashSet<>(1);
        rv.add(new RouterAddress("TCP", props, 10));
        return rv;
    }

    @Test
    public void testGroups32SameIP() {
        List<RouterInfo> ris = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ris.add(routerV4(i, "1.2.3.4"));
        }
        ris.add(routerV4(9, "2.2.2.2"));
        Map<Hash, Points> points = new HashMap<>();
        Map<Integer, List<RouterInfo>> groups = _analysis.calculateIPGroups32(ris, points);
        // 3 routers on the same /32 grouped; the lone one is not
        assertEquals(1, groups.size());
        List<RouterInfo> group = groups.values().iterator().next();
        assertEquals(3, group.size());
        // each member scored POINTS32 * (count - 1) = 5.0 * 2
        for (RouterInfo ri : group) {
            Points p = points.get(ri.getHash());
            assertNotNull(p);
            assertEquals(10.0, p.getPoints(), 0.001);
        }
    }

    @Test
    public void testGroups32LoneRouterNotScored() {
        List<RouterInfo> ris = new ArrayList<>();
        ris.add(routerV4(1, "10.0.0.1"));
        ris.add(routerV4(2, "10.0.0.2"));
        Map<Hash, Points> points = new HashMap<>();
        Map<Integer, List<RouterInfo>> groups = _analysis.calculateIPGroups32(ris, points);
        // two different /32s -> no grouping
        assertEquals(0, groups.size());
        assertTrue(points.isEmpty());
    }

    @Test
    public void testGroups16() {
        List<RouterInfo> ris = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ris.add(routerV4(i, "192.168." + i + ".1"));
        }
        ris.add(routerV4(9, "10.1.1.1"));
        ris.add(routerV4(10, "10.1.1.2"));
        ris.add(routerV4(11, "10.1.1.3"));
        ris.add(routerV4(12, "10.1.1.4"));
        Map<Hash, Points> points = new HashMap<>();
        Map<Integer, List<RouterInfo>> groups = _analysis.calculateIPGroups16(ris, points);
        // 4 routers in 192.168.x.x and 4 in 10.1.x.x -> two /16 groups
        assertEquals(2, groups.size());
        for (List<RouterInfo> group : groups.values()) {
            assertEquals(4, group.size());
            for (RouterInfo ri : group) {
                Points p = points.get(ri.getHash());
                assertNotNull(p);
                // POINTS16 * (count - 1) = 0.25 * 3
                assertEquals(0.75, p.getPoints(), 0.001);
            }
        }
    }

    @Test
    public void testGroups64IPv6() {
        List<RouterInfo> ris = new ArrayList<>();
        // same /64
        ris.add(routerV6(1, "2001:0db8:0000:0000:0000:0000:0000:0001"));
        ris.add(routerV6(2, "2001:0db8:0000:0000:0000:0000:0000:0002"));
        ris.add(routerV6(3, "2001:0db8:0000:0000:0000:0000:0000:0003"));
        // different /64
        ris.add(routerV6(4, "2001:0db8:0000:0001:0000:0000:0000:0001"));
        Map<Hash, Points> points = new HashMap<>();
        Map<Long, List<RouterInfo>> groups = _analysis.calculateIPGroups64(ris, points);
        assertEquals(1, groups.size());
        List<RouterInfo> group = groups.values().iterator().next();
        assertEquals(3, group.size());
        for (RouterInfo ri : group) {
            Points p = points.get(ri.getHash());
            assertNotNull(p);
            // POINTS64 * (count - 1) = 2.0 * 2
            assertEquals(4.0, p.getPoints(), 0.001);
        }
    }

    @Test
    public void testGroups48IPv6() {
        List<RouterInfo> ris = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ris.add(routerV6(i, "2001:0db8:0000:0000:0000:0000:0000:00" + i));
        }
        ris.add(routerV6(9, "2001:0db8:0001:0000:0000:0000:0000:0001"));
        Map<Hash, Points> points = new HashMap<>();
        Map<Long, List<RouterInfo>> groups = _analysis.calculateIPGroups48(ris, points);
        // 4 in one /48, 1 elsewhere
        assertEquals(1, groups.size());
        List<RouterInfo> group = groups.values().iterator().next();
        assertEquals(4, group.size());
        for (RouterInfo ri : group) {
            Points p = points.get(ri.getHash());
            assertNotNull(p);
            // POINTS48 * (count - 1) = 0.5 * 3
            assertEquals(1.5, p.getPoints(), 0.001);
        }
    }

    @Test
    public void testAvgMinDistInRange() {
        List<RouterInfo> ris = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            ris.add(routerV4(i, "10.0.0." + (i + 1)));
        }
        double avg = _analysis.getAvgMinDist(ris);
        // log2 distance over 256-bit space, so a handful of routers yields a large value
        assertTrue(avg > 0);
        assertTrue(avg <= 256);
    }
}
