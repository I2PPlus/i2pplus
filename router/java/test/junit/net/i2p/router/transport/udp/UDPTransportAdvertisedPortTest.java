package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import net.i2p.data.router.RouterAddress;
import net.i2p.util.OrderedProperties;

import org.junit.Test;

/**
 * The port we advertise to a peer we are introducing, or to the introducer we
 * are asking, has to be the port that peer would reach us on. The local socket
 * port is the wrong answer whenever a NAT translated it, which is the common
 * case for a firewalled router - and a published address carries no port at all,
 * so the fallback has to be the published-or-requested port, not the socket.
 *
 * @since 0.9.72+
 */
public class UDPTransportAdvertisedPortTest {

    /** The stored external address we are advertising. */
    private static final int EXTERNAL = 3456;

    /** The fallback port for the family. */
    private static final int FALLBACK = 7890;

    /**
     * The stored external address carries a port, so that is what the peer must
     * be told, whatever the fallback says.
     */
    @Test
    public void testPublishedPortWins() {
        assertEquals(EXTERNAL, UDPTransport.advertisedPort(address(EXTERNAL), FALLBACK));
    }

    /**
     * A firewalled router publishes no port, so the fallback is the only answer.
     * This is the case that used to advertise the local socket port.
     */
    @Test
    public void testAddressWithoutPortFallsBack() {
        assertEquals(FALLBACK, UDPTransport.advertisedPort(address(0), FALLBACK));
    }

    /**
     * No address at all falls back too, rather than advertising port 0.
     */
    @Test
    public void testNoAddressFallsBack() {
        assertEquals(FALLBACK, UDPTransport.advertisedPort(null, FALLBACK));
    }

    /**
     * Nothing known anywhere: the caller rejects the introduction rather than
     * advertising a zero port, so the helper must not invent one.
     */
    @Test
    public void testNothingKnownIsNotAPort() {
        assertTrue(UDPTransport.advertisedPort(null, 0) <= 0);
        assertTrue(UDPTransport.advertisedPort(address(0), -1) <= 0);
    }

    /**
     * An address carrying the given port, or no port for 0.
     *
     * @param port the port option to set, 0 for none
     * @return a router address carrying that port option
     */
    private static RouterAddress address(int port) {
        OrderedProperties props = new OrderedProperties();
        if (port > 0)
            props.setProperty(RouterAddress.PROP_PORT, Integer.toString(port));
        return new RouterAddress(UDPTransport.STYLE, props, 0);
    }
}
