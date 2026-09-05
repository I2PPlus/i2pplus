package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.router.RouterAddress;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.OrderedProperties;

import org.junit.Test;

/**
 *  Unit tests for the address-selection decisions extracted from
 *  {@link NTCPTransport#externalAddressReceived(byte[], boolean, int)} and
 *  {@link NTCPTransport#getConfiguredIP()}, plus the pure decisions reused by
 *  {@link NTCPTransport#startListening()} and {@link NTCPTransport#bindAddress(int)}.
 *
 *  <p>All helpers are static/package-visible so the externally triggered and
 *  restart-heavy flows they came from are testable without a router: auto-host
 *  preference parsing, host override, firewalled-family skipping, port-change
 *  classification, and listener rebinding checks.
 *
 *  @since 0.9.71+
 */
public class NTCPTransportAddressDecisionTest {

    @Test
    public void testIsBlank() {
        assertTrue(NTCPTransport.isBlank(null));
        assertTrue(NTCPTransport.isBlank(""));
        assertFalse(NTCPTransport.isBlank(" "));
        assertFalse(NTCPTransport.isBlank("x"));
    }

    @Test
    public void testIsBlankName() {
        assertTrue(NTCPTransport.isBlankName(null));
        assertTrue(NTCPTransport.isBlankName(""));
        assertTrue(NTCPTransport.isBlankName("   "));
        assertTrue(NTCPTransport.isBlankName("null"));
        assertFalse(NTCPTransport.isBlankName("192.0.2.1"));
    }

    @Test
    public void testWantsAutoHostAlways() {
        assertTrue(NTCPTransport.wantsAutoHost("always", false));
        assertTrue(NTCPTransport.wantsAutoHost("always", true));
    }

    @Test
    public void testWantsAutoHostEnabledRequiresSsuOK() {
        assertTrue(NTCPTransport.wantsAutoHost("true", true));
        assertFalse(NTCPTransport.wantsAutoHost("true", false));
    }

    @Test
    public void testWantsAutoHostDisabled() {
        assertFalse(NTCPTransport.wantsAutoHost("false", true));
        assertFalse(NTCPTransport.wantsAutoHost("false", false));
    }

    @Test
    public void testWantsConfiguredHostWhenOffAndDifferent() {
        assertTrue(NTCPTransport.wantsConfiguredHost("false", "myhost.example", "old.example"));
        assertTrue(NTCPTransport.wantsConfiguredHost("false", "myhost.example", null));
    }

    @Test
    public void testWantsConfiguredHostWhenSameOrAbsent() {
        assertFalse(NTCPTransport.wantsConfiguredHost("false", "myhost.example", "myhost.example"));
        assertFalse(NTCPTransport.wantsConfiguredHost("false", null, null));
        assertFalse(NTCPTransport.wantsConfiguredHost("false", "", "old.example"));
        assertFalse(NTCPTransport.wantsConfiguredHost("true", "myhost.example", null));
    }

    @Test
    public void testWantsRemoveAutoHost() {
        assertTrue(NTCPTransport.wantsRemoveAutoHost("true", false));
        assertFalse(NTCPTransport.wantsRemoveAutoHost("true", true));
        assertFalse(NTCPTransport.wantsRemoveAutoHost("false", false));
        assertFalse(NTCPTransport.wantsRemoveAutoHost("always", false));
    }

    @Test
    public void testWantsFullOptions() {
        OrderedProperties empty = new OrderedProperties();
        OrderedProperties withHost = new OrderedProperties();
        withHost.setProperty(RouterAddress.PROP_HOST, "192.0.2.1");
        assertTrue(NTCPTransport.wantsFullOptions(false, empty, TransportUtil.IPv6Config.IPV6_PREFERRED));
        assertTrue(NTCPTransport.wantsFullOptions(true, withHost, TransportUtil.IPv6Config.IPV6_PREFERRED));
        assertTrue(NTCPTransport.wantsFullOptions(true, empty, TransportUtil.IPv6Config.IPV6_ONLY));
        assertFalse(NTCPTransport.wantsFullOptions(true, empty, TransportUtil.IPv6Config.IPV6_PREFERRED));
    }

    @Test
    public void testAlreadyHaveFamily() {
        assertTrue(NTCPTransport.alreadyHaveFamily(true, false, 4));
        assertTrue(NTCPTransport.alreadyHaveFamily(false, true, 16));
        assertFalse(NTCPTransport.alreadyHaveFamily(true, false, 16));
        assertFalse(NTCPTransport.alreadyHaveFamily(false, true, 4));
        assertFalse(NTCPTransport.alreadyHaveFamily(true, true, 6));
        assertFalse(NTCPTransport.alreadyHaveFamily(false, false, 4));
    }

    @Test
    public void testChoosePrimaryIP() {
        assertEquals("192.0.2.1", NTCPTransport.choosePrimaryIP(Arrays.asList("2001:db8::1", "192.0.2.1")));
        assertEquals("2001:db8::1", NTCPTransport.choosePrimaryIP(Arrays.asList("2001:db8::1", "2001:db8::2")));
        assertEquals("192.0.2.1", NTCPTransport.choosePrimaryIP(Arrays.asList("192.0.2.1")));
    }

    @Test
    public void testChoosePrimaryIPFirstIpv4Wins() {
        assertEquals("192.0.2.2", NTCPTransport.choosePrimaryIP(Arrays.asList("192.0.2.2", "2001:db8::1")));
    }

    @Test
    public void testChoosePrimaryIPEmptyListThrows() {
        try {
            NTCPTransport.choosePrimaryIP(new ArrayList<String>());
            fail("empty list must fail fast");
        } catch (IndexOutOfBoundsException expected) {}
    }

    @Test
    public void testShouldSkipSavedAddress() {
        // IPv6 firewalled always skips IPv6.
        assertTrue(NTCPTransport.shouldSkipSavedAddress(true, true, false, true, false));
        // IPv6 preference with prop firewalled only counts when UDP is enabled.
        assertTrue(NTCPTransport.shouldSkipSavedAddress(true, false, true, false, false));
        assertFalse(NTCPTransport.shouldSkipSavedAddress(true, false, true, true, false));
        assertFalse(NTCPTransport.shouldSkipSavedAddress(true, false, false, false, false));
        // IPv4 skip solely on IPv4 firewall.
        assertTrue(NTCPTransport.shouldSkipSavedAddress(false, false, false, false, true));
        assertFalse(NTCPTransport.shouldSkipSavedAddress(false, false, false, false, false));
    }

    @Test
    public void testIsAlreadyListeningExactMatch() throws Exception {
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 8888);
        Set<InetSocketAddress> endpoints = new HashSet<InetSocketAddress>(Arrays.asList(addr));
        assertTrue(NTCPTransport.isAlreadyListening(endpoints, addr, InetAddress.getLoopbackAddress(), 8888));
    }

    @Test
    public void testIsAlreadyListeningWildcardPortMatch() throws Exception {
        InetSocketAddress wildcard = new InetSocketAddress(8888);
        InetSocketAddress specific = new InetSocketAddress(InetAddress.getLoopbackAddress(), 8888);
        Set<InetSocketAddress> endpoints = new HashSet<InetSocketAddress>(Arrays.asList(wildcard));
        // Hostname bind on a port already bound to the wildcard needs no restart.
        assertTrue(NTCPTransport.isAlreadyListening(endpoints, specific, InetAddress.getLoopbackAddress(), 8888));
    }

    @Test
    public void testIsAlreadyListeningNoMatch() throws Exception {
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 8888);
        Set<InetSocketAddress> endpoints = new HashSet<InetSocketAddress>();
        assertFalse(NTCPTransport.isAlreadyListening(endpoints, addr, InetAddress.getLoopbackAddress(), 8888));
    }

    @Test
    public void testIsAlreadyListeningWildcardBindNoSpecific() throws Exception {
        // A wildcard bind does not match a specific-address endpoint.
        InetSocketAddress specific = new InetSocketAddress(InetAddress.getLoopbackAddress(), 8888);
        Set<InetSocketAddress> endpoints = new HashSet<InetSocketAddress>(Arrays.asList(specific));
        assertFalse(NTCPTransport.isAlreadyListening(endpoints, new InetSocketAddress(8888), null, 8888));
    }

    @Test
    public void testIsExternalOnlyPortChange() {
        assertTrue(NTCPTransport.isExternalOnlyPortChange(8888, 8888, 4444));
        // Same internal and external ports is no change.
        assertFalse(NTCPTransport.isExternalOnlyPortChange(8888, 8888, 8888));
        // No configured internal port means no mapping to preserve.
        assertFalse(NTCPTransport.isExternalOnlyPortChange(8888, 8888, 0));
        // Port mismatch means a real rebind is needed.
        assertFalse(NTCPTransport.isExternalOnlyPortChange(8888, 4444, 4444));
    }
}