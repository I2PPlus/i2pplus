package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.util.OrderedProperties;

/**
 * Unit tests for the compact masked-IP fingerprint used by
 * {@link FloodfillPeerSelector} on the floodfill selection path: the packed
 * {@code long} key must reproduce {@code MaskedIPSet} semantics (same masked
 * subnet => same key, IPv4 and IPv6 never collide) without allocating
 * per-address Strings, and must reject unsupported masks. The accumulator
 * fingerprint must keep the masked-IP / port / family spaces separate exactly
 * like MaskedIPSet's '.' / 'p' / 'x' prefixes did.
 *
 * @since 0.9.71+
 */
public class FloodfillPeerSelectorMaskedIPKeyTest {

    // ----- maskedIPKey -----

    @Test
    public void sameIpv4PrefixYieldsSameKey() {
        byte[] a = {(byte) 192, (byte) 0, (byte) 2, 5};
        byte[] b = {(byte) 192, (byte) 0, (byte) 2, 99};
        assertEquals(FloodfillPeerSelector.maskedIPKey(a, 2),
                     FloodfillPeerSelector.maskedIPKey(b, 2));
    }

    @Test
    public void differentIpv4PrefixYieldsDifferentKey() {
        byte[] a = {(byte) 192, (byte) 0, (byte) 2, 5};
        byte[] b = {(byte) 192, (byte) 1, (byte) 2, 5};
        assertNotEquals(FloodfillPeerSelector.maskedIPKey(a, 2),
                        FloodfillPeerSelector.maskedIPKey(b, 2));
    }

    @Test
    public void sameIpv6PrefixYieldsSameKey() {
        byte[] a = new byte[16];
        byte[] b = new byte[16];
        a[0] = 0x20; a[1] = 0x01; a[2] = 0x0d; a[3] = (byte) 0xb8;
        b[0] = 0x20; b[1] = 0x01; b[2] = 0x0d; b[3] = (byte) 0xb8;
        b[4] = 0x7f; // differs outside the masked /64
        assertEquals(FloodfillPeerSelector.maskedIPKey(a, 2),
                     FloodfillPeerSelector.maskedIPKey(b, 2));
    }

    @Test
    public void ipv4AndIpv6NeverCollide() {
        byte[] v4 = {(byte) 192, (byte) 0, (byte) 2, 5};
        byte[] v6 = new byte[16];
        v6[0] = (byte) 192; v6[1] = 0; v6[2] = 2; v6[3] = 5;
        long v4k = FloodfillPeerSelector.maskedIPKey(v4, 2);
        long v6k = FloodfillPeerSelector.maskedIPKey(v6, 2);
        assertNotEquals(v4k, v6k);
        // The IPv6 marker bit must be set exactly once.
        assertTrue("v6 key must set the family marker", (v6k & (1L << 63)) != 0);
        assertTrue("v4 key must not set the family marker", (v4k & (1L << 63)) == 0);
    }

    @Test
    public void ipv6MaskTwiceTheWidth() {
        // An IPv4 /32 (mask 2 -> 2 bytes) vs IPv6 with the same leading two
        // bytes masked at the IPv6 width (mask 2 -> 4 bytes): first two bytes
        // equal, but v6 also masks bytes 2-3. Keys must differ per family.
        byte[] v4 = {(byte) 0x20, (byte) 0x01, 0x0d, (byte) 0xb8};
        byte[] v6 = new byte[16];
        v6[0] = 0x20; v6[1] = 0x01; v6[2] = 0x0d; v6[3] = (byte) 0xb8;
        long v4k = FloodfillPeerSelector.maskedIPKey(v4, 2);
        long v6k = FloodfillPeerSelector.maskedIPKey(v6, 2);
        assertNotEquals("family marker must separate identical prefixes", v4k, v6k);
    }

    @Test
    public void maskFourIpv6Rejected() {
        byte[] v6 = new byte[16];
        try {
            FloodfillPeerSelector.maskedIPKey(v6, 4);
            fail("IPv6 mask 4 packs more than 60 bits and must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void boundariesWithinRangeAccepted() {
        byte[] v4 = new byte[4];
        byte[] v6 = new byte[16];
        // Largest supported widths: IPv4 mask 4 (8 nibbles), IPv6 mask 3 (12 nibbles).
        assertTrue(FloodfillPeerSelector.maskedIPKey(v4, 4) >= 0);
        long v6k = FloodfillPeerSelector.maskedIPKey(v6, 3);
        assertTrue((v6k & (1L << 63)) != 0);
    }

    // ----- addSameIPFingerprint accumulator semantics -----

    // Communicate-IP for the mocked commSystem; kept in 10.255.0.0/16 so it can
    // never collide with the 192.0.2.x test addresses (all fingerprint tests
    // rely on the first addSameIPFingerprint call returning false).
    private static final byte[] DUMB_IP = {10, (byte) 255, (byte) 255, 1};

    private RouterContext mockContext() {
        RouterContext ctx = mock(RouterContext.class);
        CommSystemFacade comm = mock(CommSystemFacade.class);
        when(comm.getIP(new Hash(new byte[Hash.HASH_LENGTH]))).thenReturn(DUMB_IP);
        when(ctx.commSystem()).thenReturn(comm);
        return ctx;
    }

    private static Hash hash() { return new Hash(new byte[Hash.HASH_LENGTH]); }

    private static RouterInfo addressInfo(int a, int b, int c, int d, int port, String family) {
        RouterInfo info = new RouterInfo();
        Set<RouterAddress> addrs = new HashSet<>();
        OrderedProperties props = new OrderedProperties();
        props.setProperty("host", a + "." + b + "." + c + "." + d);
        props.setProperty("port", Integer.toString(port));
        addrs.add(new RouterAddress("SSU", props, 40));
        info.setAddresses(addrs);
        if (family != null) {
            Properties opts = new Properties();
            opts.setProperty("family", family);
            info.setOptions(opts);
        }
        return info;
    }

    @Test
    public void addSameIPFingerprintDetectsSubnetCollision() {
        RouterContext ctx = mockContext();
        Set<Long> ips = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        Set<String> families = new HashSet<>();
        assertFalse("first peer establishes the subnet",
                    FloodfillPeerSelector.addSameIPFingerprint(ips, ports, families, ctx, hash(),
                                                               addressInfo(192, 0, 2, 1, 5000, null), 2));
        assertTrue("a peer in the same /16 must collide",
                   FloodfillPeerSelector.addSameIPFingerprint(ips, ports, families, ctx, hash(),
                                                              addressInfo(192, 0, 2, 99, 5001, null), 2));
    }

    @Test
    public void addSameIPFingerprintDetectsPortCollision() {
        RouterContext ctx = mockContext();
        Set<Long> ips = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        Set<String> families = new HashSet<>();
        assertFalse(FloodfillPeerSelector.addSameIPFingerprint(ips, ports, families, ctx, hash(),
                                                               addressInfo(192, 0, 2, 1, 5000, null), 2));
        assertTrue("a distinct /16 with the same port must collide",
                   FloodfillPeerSelector.addSameIPFingerprint(ips, ports, families, ctx, hash(),
                                                              addressInfo(192, 0, 3, 1, 5000, null), 2));
    }

    @Test
    public void addSameIPFingerprintDetectsFamilyCollision() {
        RouterContext ctx = mockContext();
        Set<Long> ips = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        Set<String> families = new HashSet<>();
        assertFalse(FloodfillPeerSelector.addSameIPFingerprint(ips, ports, families, ctx, hash(),
                                                               addressInfo(192, 0, 2, 1, 5000, "f"), 2));
        assertTrue("a distinct /16 and port but same claimed family must collide",
                   FloodfillPeerSelector.addSameIPFingerprint(ips, ports, families, ctx, hash(),
                                                              addressInfo(192, 0, 3, 1, 5001, "f"), 2));
    }

    @Test
    public void sameIpAndSamePortAreStillCollision() {
        RouterContext ctx = mockContext();
        Set<Long> ips = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        Set<String> families = new HashSet<>();
        assertFalse(FloodfillPeerSelector.addSameIPFingerprint(ips, ports, families, ctx, hash(),
                                                               addressInfo(192, 0, 2, 1, 5000, null), 2));
        assertTrue("same IP and same port must collide",
                   FloodfillPeerSelector.addSameIPFingerprint(ips, ports, families, ctx, hash(),
                                                              addressInfo(192, 0, 2, 1, 5000, null), 2));
    }

    @Test
    public void addSameIPFingerprintNullInfoIsNotCollision() {
        Set<Long> ips = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        Set<String> families = new HashSet<>();
        assertFalse(FloodfillPeerSelector.addSameIPFingerprint(ips, ports, families,
                                                               mock(RouterContext.class), hash(), null, 2));
    }
}