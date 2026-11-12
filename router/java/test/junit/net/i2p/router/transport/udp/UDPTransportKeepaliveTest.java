package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import net.i2p.data.router.RouterAddress;
import net.i2p.util.OrderedProperties;

import org.junit.Test;

/**
 * The firewall keepalive pass decides once per loop whether a single ping
 * refreshes every peer's path, and per peer whether it still needs one. A
 * symmetric NAT keeps a mapping per destination and IPv6 firewall state is per
 * session, so assuming one ping covers the whole peer set lets idle sessions -
 * and the peers on them - die. Both decisions are static helpers, pinned here
 * without a socket or a running transport.
 *
 * @since 0.9.72+
 */
public class UDPTransportKeepaliveTest {

    /** The local IPv4 listen port used by these cases. */
    private static final int LISTEN = 3456;

    /**
     * A published address carrying the given port, or carrying no port at all
     * for 0, which is what a firewalled router publishes.
     *
     * @param port the port option to set, 0 for none
     * @return a published address carrying that port option
     */
    private static RouterAddress published(int port) {
        OrderedProperties props = new OrderedProperties();
        if (port > 0)
            props.setProperty(RouterAddress.PROP_PORT, Integer.toString(port));
        return new RouterAddress(UDPTransport.STYLE, props, 0);
    }

    /**
     * The one case where a single ping is enough: a full-cone mapping that
     * preserves our local port, published as such.
     */
    @Test
    public void testSharedMappingAllowsOnePing() {
        assertTrue(UDPTransport.pingOneIPv4Only(true, false, LISTEN, published(LISTEN)));
    }

    /**
     * A symmetric NAT rewrites the source port per destination, so it needs a
     * ping per peer even when one of those mappings happens to match our port.
     */
    @Test
    public void testSymmetricNatPingsEveryPeerEvenWithAPreservedPort() {
        assertFalse(UDPTransport.pingOneIPv4Only(true, true, LISTEN, published(LISTEN)));
    }

    /**
     * A firewalled router publishes no port, so we cannot tell the mapping is
     * preserved. This is the case the old code got wrong by reading the local
     * port back out of getExternalPort().
     */
    @Test
    public void testPublishedAddressWithoutPortPingsEveryPeer() {
        assertFalse(UDPTransport.pingOneIPv4Only(true, false, LISTEN, published(0)));
    }

    /**
     * No published IPv4 address at all means we do not know what is mapped.
     */
    @Test
    public void testNoPublishedAddressPingsEveryPeer() {
        assertFalse(UDPTransport.pingOneIPv4Only(true, false, LISTEN, null));
    }

    /**
     * A published port that is not the one we listen on is a translated mapping.
     */
    @Test
    public void testDifferentPublishedPortPingsEveryPeer() {
        assertFalse(UDPTransport.pingOneIPv4Only(true, false, LISTEN, published(LISTEN + 1)));
    }

    /**
     * Not listening (port -1 or 0) means there is no mapping to refresh.
     */
    @Test
    public void testNotListeningPingsEveryPeer() {
        assertFalse(UDPTransport.pingOneIPv4Only(true, false, -1, published(-1)));
        assertFalse(UDPTransport.pingOneIPv4Only(true, false, 0, published(0)));
    }

    /**
     * With ping mode off, nothing is one-only.
     */
    @Test
    public void testPingModeOffIsNeverOneOnly() {
        assertFalse(UDPTransport.pingOneIPv4Only(false, false, LISTEN, published(LISTEN)));
    }

    /**
     * Once an IPv4 peer has been pinged under a shared mapping, the other IPv4
     * peers are covered by that same ping.
     */
    @Test
    public void testIPv4PingCoversTheOtherIPv4Peers() {
        assertFalse(UDPTransport.needsFirewallPing(true, true, false));
    }

    /**
     * An IPv4 ping says nothing about an IPv6 session's firewall state, so the
     * IPv6 peers still need their own.
     */
    @Test
    public void testIPv4PingDoesNotCoverAnIPv6Peer() {
        assertTrue(UDPTransport.needsFirewallPing(true, true, true));
    }

    /**
     * Without a shared mapping every peer needs its own ping, and with ping mode
     * off none needs one.
     */
    @Test
    public void testUnsharedMappingNeedsAPingPerPeer() {
        assertTrue(UDPTransport.needsFirewallPing(true, false, false));
        assertTrue(UDPTransport.needsFirewallPing(true, false, true));
        assertFalse(UDPTransport.needsFirewallPing(false, false, false));
        assertFalse(UDPTransport.needsFirewallPing(false, true, true));
    }
}
