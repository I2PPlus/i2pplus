package net.i2p.router.transport;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;

import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.util.OrderedProperties;

/**
 * Tests for {@link TransportManager#peerIPFromRI}, the lazy host:port builder
 * extracted when the per-message IP extraction in {@code getNextBid} was moved
 * off the hot path onto the rare ban/ghost branch. It picks the first usable
 * address, brackets bare IPv6 literals, and returns "" when nothing is usable.
 *
 * @since 0.9.71+
 */
public class TransportManagerPeerIPTest {

    private static RouterAddress addr(String host, String port) {
        OrderedProperties props = new OrderedProperties();
        if (host != null) {
            props.setProperty("host", host);
        }
        if (port != null) {
            props.setProperty("port", port);
        }
        return new RouterAddress("SSU", props, 40);
    }

    private static RouterInfo riWith(RouterAddress... addrs) {
        RouterInfo info = new RouterInfo();
        Collection<RouterAddress> coll = Arrays.asList(addrs);
        info.setAddresses(coll);
        return info;
    }

    @Test
    public void ipv4HostPort() {
        RouterInfo ri = riWith(addr("203.0.113.7", "31337"));
        assertEquals("203.0.113.7:31337", TransportManager.peerIPFromRI(ri));
    }

    @Test
    public void bareIpv6IsBracketed() {
        RouterInfo ri = riWith(addr("2001:db8::1", "8888"));
        assertEquals("[2001:db8::1]:8888", TransportManager.peerIPFromRI(ri));
    }

    @Test
    public void alreadyBracketedIpv6IsNotDoubleBracketed() {
        RouterInfo ri = riWith(addr("[2001:db8::1]", "8888"));
        assertEquals("[2001:db8::1]:8888", TransportManager.peerIPFromRI(ri));
    }

    @Test
    public void skipsAddressWithNoHost() {
        // First address has no host, the second is usable — the first usable wins.
        RouterInfo ri = riWith(addr(null, "1111"), addr("198.51.100.4", "2222"));
        assertEquals("198.51.100.4:2222", TransportManager.peerIPFromRI(ri));
    }

    @Test
    public void skipsAddressWithNoPort() {
        RouterInfo ri = riWith(addr("192.0.2.9", null), addr("192.0.2.10", "5555"));
        assertEquals("192.0.2.10:5555", TransportManager.peerIPFromRI(ri));
    }

    @Test
    public void emptyWhenNoAddresses() {
        assertEquals("", TransportManager.peerIPFromRI(riWith()));
    }

    @Test
    public void emptyWhenNoUsableAddress() {
        RouterInfo ri = riWith(addr(null, null), addr("192.0.2.1", null));
        assertEquals("", TransportManager.peerIPFromRI(ri));
    }

    @Test
    public void firstUsableWinsOverLaterOnes() {
        RouterInfo ri = riWith(addr("10.0.0.1", "1"), addr("10.0.0.2", "2"));
        assertEquals("10.0.0.1:1", TransportManager.peerIPFromRI(ri));
    }

    // Sanity: an empty RouterInfo (no setAddresses call) also yields "".
    @Test
    public void emptyRouterInfoYieldsEmpty() {
        assertEquals("", TransportManager.peerIPFromRI(new RouterInfo()));
    }

    @Test
    public void nullAddressEntryIsSkipped() {
        RouterInfo info = new RouterInfo();
        info.setAddresses(Arrays.asList(null, addr("10.1.2.3", "7")));
        assertEquals("10.1.2.3:7", TransportManager.peerIPFromRI(info));
    }

    // Guard the Collections import is exercised (no-op compile-time use).
    @Test
    public void singleUsableAmongManyUnusable() {
        RouterInfo ri = riWith(addr(null, "1"), addr("host", "0"), addr("9.9.9.9", "9"));
        // port "0" parses to 0 (not > 0) so it is skipped; the last is usable.
        assertEquals("9.9.9.9:9", TransportManager.peerIPFromRI(ri));
    }
}
