package net.i2p.router.transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.util.OrderedProperties;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests the announced-address half of {@link TransportImpl#getRouterIPPort}:
 * only a literal IP may name a banned peer. A peer picks its own host string,
 * so a RouterInfo can announce a DNS name, and a name written into
 * sessionbans.txt is both unverified text in the log and an argument to the
 * country and ASN lookups.
 *
 * No router context is needed: the helper is static and reads only the
 * addresses on the RouterInfo it is given.
 *
 * @since 0.9.72+
 */
public class TransportImplIPPortTest {

    /**
     * An address with the given host and port on a throwaway transport.
     *
     * @param host the host property to announce, null to announce no host
     * @param port the port property to announce, 0 to announce no port
     * @return an address carrying those two options
     */
    private static RouterAddress addr(String host, int port) {
        OrderedProperties opts = new OrderedProperties();
        if (host != null) {opts.setProperty(RouterAddress.PROP_HOST, host);}
        if (port > 0) {opts.setProperty("port", Integer.toString(port));}
        return new RouterAddress("NTCP2", opts, 42);
    }

    /**
     * A RouterInfo announcing the given addresses.
     *
     * @param addresses the addresses to announce
     * @return a RouterInfo carrying them
     */
    private static RouterInfo router(RouterAddress... addresses) {
        RouterInfo ri = new RouterInfo();
        ri.setAddresses(Arrays.asList(addresses));
        return ri;
    }

    @Test
    public void literalIPv4WithPort() {
        assertEquals("1.2.3.4:1234", TransportImpl.formatAnnouncedIPPort(router(addr("1.2.3.4", 1234))));
    }

    @Test
    public void literalIPv6IsBracketed() {
        assertEquals("[2001:db8::1]:5222", TransportImpl.formatAnnouncedIPPort(router(addr("2001:db8::1", 5222))));
    }

    @Test
    public void noPortGivesBareAddress() {
        assertEquals("1.2.3.4", TransportImpl.formatAnnouncedIPPort(router(addr("1.2.3.4", 0))));
    }

    @Test
    public void hostNamesAreNeverUsed() {
        assertEquals("UNKNOWN", TransportImpl.formatAnnouncedIPPort(router(addr("peer.example.com", 1234))));
        assertEquals("UNKNOWN", TransportImpl.formatAnnouncedIPPort(router(addr("", 1234))));
        assertEquals("UNKNOWN", TransportImpl.formatAnnouncedIPPort(router(addr(null, 1234))));
    }

    @Test
    public void literalWinsOverANameAnnouncedFirst() {
        RouterInfo ri = router(addr("peer.example.com", 1234), addr("1.2.3.4", 1234));
        assertEquals("1.2.3.4:1234", TransportImpl.formatAnnouncedIPPort(ri));
    }

    @Test
    public void noAddressesGivesUnknown() {
        RouterInfo ri = new RouterInfo();
        ri.setAddresses(Collections.<RouterAddress>emptyList());
        assertEquals("UNKNOWN", TransportImpl.formatAnnouncedIPPort(ri));
        List<RouterAddress> withNull = new ArrayList<>();
        withNull.add(null);
        ri.setAddresses(withNull);
        assertEquals("UNKNOWN", TransportImpl.formatAnnouncedIPPort(ri));
    }
}
