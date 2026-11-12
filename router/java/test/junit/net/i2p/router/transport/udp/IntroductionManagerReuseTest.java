package net.i2p.router.transport.udp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.util.OrderedProperties;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Reusing an introducer we already published has to check that the peer behind
 * the tag is still the peer that tag was issued to, and renew that peer's
 * introducer keepalive window. The fresh-pick path renews it; the reuse path did
 * not, so a peer we keep publishing as an introducer can be expired out from
 * under the routers that read our address.
 *
 * @since 0.9.72+
 */
public class IntroductionManagerReuseTest {

    /** Context for the manager's clock and netdb. */
    private static RouterContext _ctx;

    /** The manager under test. */
    private IntroductionManager _im;

    @BeforeClass
    public static void initContext() {
        _ctx = RouterTestHelper.newContext();
        assertNotNull(_ctx);
    }

    @Before
    public void setUp() {
        _im = new IntroductionManager(_ctx, mock(UDPTransport.class));
    }

    /**
     * A published introducer whose peer is still connected, of the right family,
     * and matches the published hash is reused - and reusing it renews the
     * keepalive window the expiry loop honours.
     */
    @Test
    public void testReusedIntroducerKeepsItsKeepalive() {
        Hash h = hash(1);
        PeerState peer = peer(h, false, 0x1234L);
        _im.add(peer);
        OrderedProperties opts = new OrderedProperties();
        assertEquals(1, _im.pickInbound(publishing(0x1234L, h, future()), false, opts, 1));
        verify(peer).setIntroducerTime();
    }

    /**
     * A tag issued to an IPv6 peer does not cover an IPv4 address we are
     * publishing introducers for.
     */
    @Test
    public void testWrongFamilyTagIsNotReused() {
        Hash h = hash(2);
        PeerState peer = peer(h, true, 0x2345L);
        _im.add(peer);
        OrderedProperties opts = new OrderedProperties();
        assertEquals(0, _im.pickInbound(publishing(0x2345L, h, future()), false, opts, 1));
        verify(peer, never()).setIntroducerTime();
    }

    /**
     * The tag alone is not proof: if the published hash is another router, the
     * tag belongs to a peer we are no longer holding.
     */
    @Test
    public void testTagIssuedToAnotherPeerIsNotReused() {
        PeerState peer = peer(hash(3), false, 0x3456L);
        _im.add(peer);
        OrderedProperties opts = new OrderedProperties();
        assertEquals(0, _im.pickInbound(publishing(0x3456L, hash(4), future()), false, opts, 1));
        verify(peer, never()).setIntroducerTime();
    }

    /**
     * A tag left in the published address after its peer disconnected must not be
     * reused, and must not renew any keepalive.
     */
    @Test
    public void testStaleTagIsNotReused() {
        PeerState other = peer(hash(6), false, 0x6789L);
        _im.add(other);
        OrderedProperties opts = new OrderedProperties();
        assertEquals(0, _im.pickInbound(publishing(0x9999L, hash(5), future()), false, opts, 1));
        verify(other, never()).setIntroducerTime();
    }

    /**
     * A connected peer registered as an inbound introducer.
     *
     * @param h the peer's router hash
     * @param ipv6 the peer's address family
     * @param tag the relay tag it introduced us as
     * @return the mocked peer
     */
    private PeerState peer(Hash h, boolean ipv6, long tag) {
        PeerState peer = mock(PeerState.class);
        when(peer.getRemotePeer()).thenReturn(h);
        when(peer.isIPv6()).thenReturn(ipv6);
        when(peer.getTheyRelayToUsAs()).thenReturn(tag);
        when(peer.getRemotePort()).thenReturn(12345);
        return peer;
    }

    /**
     * An SSU2 router address publishing one introducer.
     *
     * @param tag the introducer tag
     * @param h the introducer's hash
     * @param expSeconds the introduction expiry in seconds since the epoch
     * @return a UDP router address publishing exactly that one introducer
     */
    private static RouterAddress publishing(long tag, Hash h, long expSeconds) {
        OrderedProperties props = new OrderedProperties();
        props.setProperty("v", "2");
        props.setProperty(UDPAddress.PROP_INTRO_TAG_PREFIX + '0', Long.toString(tag));
        props.setProperty(UDPAddress.PROP_INTRO_HASH_PREFIX + '0', h.toBase64());
        props.setProperty(UDPAddress.PROP_INTRO_EXP_PREFIX + '0', Long.toString(expSeconds));
        return new RouterAddress(UDPTransport.STYLE, props, 0);
    }

    /**
     * One hour from now, comfortably past the reuse margin.
     *
     * @return the expiration in seconds since the epoch
     */
    private long future() {
        return (_ctx.clock().now() + 3600 * 1000L) / 1000L;
    }

    /**
     * A distinct router hash.
     *
     * @param b the first byte, so the hashes differ
     * @return a hash differing from the others only in its first byte
     */
    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        return new Hash(data);
    }
}
