package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.Test;

import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.OrderedProperties;

/**
 * Tests the pure functions behind routing-table verify-before-add and IP
 * diversity: {@link KademliaNetworkDatabaseFacade#hasDirectContact(PeerProfile)},
 * {@link KademliaNetworkDatabaseFacade#ipGroup(byte[])} and
 * {@link KademliaNetworkDatabaseFacade#ipGroups(RouterInfo)}.
 *
 * @since 0.9.71+
 */
public class PeerVerificationTest {

    // ---- direct contact evidence ----

    @Test
    public void noProfileIsNoContact() {
        assertFalse(KademliaNetworkDatabaseFacade.hasDirectContact((PeerProfile) null));
    }

    @Test
    public void heardAboutAloneIsNotDirect() {
        PeerProfile prof = mock(PeerProfile.class);
        when(prof.getLastHeardAbout()).thenReturn(12345L);
        assertFalse(KademliaNetworkDatabaseFacade.hasDirectContact(prof));
    }

    @Test
    public void heardFromIsDirect() {
        PeerProfile prof = mock(PeerProfile.class);
        when(prof.getLastHeardFrom()).thenReturn(12345L);
        assertTrue(KademliaNetworkDatabaseFacade.hasDirectContact(prof));
    }

    @Test
    public void successfulSendIsDirect() {
        PeerProfile prof = mock(PeerProfile.class);
        when(prof.getLastSendSuccessful()).thenReturn(99L);
        assertTrue(KademliaNetworkDatabaseFacade.hasDirectContact(prof));
    }

    // ---- IP grouping ----

    private static byte[] v4(int a, int b, int c, int d) {
        return new byte[] {(byte) a, (byte) b, (byte) c, (byte) d};
    }

    private static byte[] v6(int first, int second) {
        byte[] ip = new byte[16];
        ip[0] = (byte) first;
        ip[1] = (byte) second;
        return ip;
    }

    @Test
    public void v4GroupsBySlash16() {
        Long a = KademliaNetworkDatabaseFacade.ipGroup(v4(1, 2, 3, 4));
        Long b = KademliaNetworkDatabaseFacade.ipGroup(v4(1, 2, 250, 1));
        Long c = KademliaNetworkDatabaseFacade.ipGroup(v4(1, 3, 3, 4));
        assertEquals(a, b);
        assertFalse(a.equals(c));
    }

    @Test
    public void v6GroupsBySlash48() {
        Long a = KademliaNetworkDatabaseFacade.ipGroup(v6(0x20, 0x01));
        Long b = KademliaNetworkDatabaseFacade.ipGroup(v6(0x20, 0x01));
        assertEquals(a, b);
        // differs past the /48 prefix -> same group (prefix only matters)
        byte[] ip2 = v6(0x20, 0x01);
        ip2[10] = 0x7f;
        assertEquals(a, KademliaNetworkDatabaseFacade.ipGroup(ip2));
        // differs within the /48 -> different group
        assertFalse(a.equals(KademliaNetworkDatabaseFacade.ipGroup(v6(0x20, 0x02))));
    }

    @Test
    public void familiesNeverCollide() {
        // first two bytes identical across families must not group together
        Long four = KademliaNetworkDatabaseFacade.ipGroup(v4(0x20, 0x01, 0, 0));
        Long six = KademliaNetworkDatabaseFacade.ipGroup(v6(0x20, 0x01));
        assertFalse(four.equals(six));
    }

    @Test
    public void nullAndBadLengthHaveNoGroup() {
        assertNull(KademliaNetworkDatabaseFacade.ipGroup(null));
        assertNull(KademliaNetworkDatabaseFacade.ipGroup(new byte[8]));
    }

    @Test
    public void routerInfoGroupsLiteralIPsOnly() {
        RouterInfo ri = new RouterInfo();
        ri.setAddresses(java.util.Arrays.asList(address("1.2.3.4"), address("1.2.9.9"), address("nope.example")));
        Set<Long> groups = KademliaNetworkDatabaseFacade.ipGroups(ri);
        // 1.2.3.4 and 1.2.9.9 share the /16; the hostname has no group
        assertEquals(1, groups.size());
    }

    private static RouterAddress address(String host) {
        OrderedProperties opts = new OrderedProperties();
        opts.setProperty("host", host);
        opts.setProperty("port", "12345");
        return new RouterAddress("UDP", opts, 0);
    }
}
