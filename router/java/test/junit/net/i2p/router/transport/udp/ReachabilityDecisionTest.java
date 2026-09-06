package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.i2p.router.CommSystemFacade.Status;
import org.junit.Test;

/**
 * Pins reachability status-decision logic extracted from
 * UDPTransport.locked_setReachabilityStatus(). All helpers are pure static
 * functions in UDPTransport; no router context is required.
 *
 * @since 0.9.71+
 */
public class ReachabilityDecisionTest {

    @Test
    public void testUnknownStatusRetestsAlways() {
        assertTrue(UDPTransport.shouldRerunStatusTest(Status.UNKNOWN, false));
        assertTrue(UDPTransport.shouldRerunStatusTest(Status.UNKNOWN, true));
    }

    @Test
    public void testUnknownStatusRerunOnlyForIPv6Component() {
        // states with an unknown IPv6 component ->
        assertTrue(UDPTransport.shouldRerunStatusTest(Status.IPV4_OK_IPV6_UNKNOWN, true));
        assertTrue(UDPTransport.shouldRerunStatusTest(Status.IPV4_FIREWALLED_IPV6_UNKNOWN, true));
        assertTrue(UDPTransport.shouldRerunStatusTest(Status.IPV4_SNAT_IPV6_UNKNOWN, true));
        assertTrue(UDPTransport.shouldRerunStatusTest(Status.IPV4_DISABLED_IPV6_UNKNOWN, true));
        assertTrue(UDPTransport.shouldRerunStatusTest(Status.IPV4_UNKNOWN_IPV6_OK, true));
        assertTrue(UDPTransport.shouldRerunStatusTest(Status.IPV4_UNKNOWN_IPV6_FIREWALLED, true));
        // an IPv4-result test must not rerun
        assertFalse(UDPTransport.shouldRerunStatusTest(Status.IPV4_OK_IPV6_UNKNOWN, false));
        assertFalse(UDPTransport.shouldRerunStatusTest(Status.IPV4_UNKNOWN_IPV6_OK, false));
        // settled states never rerun
        assertFalse(UDPTransport.shouldRerunStatusTest(Status.OK, false));
        assertFalse(UDPTransport.shouldRerunStatusTest(Status.DIFFERENT, true));
        assertFalse(UDPTransport.shouldRerunStatusTest(Status.REJECT_UNSOLICITED, false));
    }

    @Test
    public void testApplyIPv6OnlyIdentityWhenNotV6Only() {
        assertEquals(Status.OK, UDPTransport.applyIPv6Only(Status.OK, false));
        assertEquals(Status.IPV4_OK_IPV6_UNKNOWN, UDPTransport.applyIPv6Only(Status.IPV4_OK_IPV6_UNKNOWN, false));
        assertEquals(Status.IPV4_FIREWALLED_IPV6_OK, UDPTransport.applyIPv6Only(Status.IPV4_FIREWALLED_IPV6_OK, false));
    }

    @Test
    public void testApplyIPv6OnlyMapsUnknownIPv4Components() {
        assertEquals(Status.IPV4_DISABLED_IPV6_UNKNOWN, UDPTransport.applyIPv6Only(Status.UNKNOWN, true));
        assertEquals(Status.IPV4_DISABLED_IPV6_OK, UDPTransport.applyIPv6Only(Status.IPV4_UNKNOWN_IPV6_OK, true));
        assertEquals(Status.IPV4_DISABLED_IPV6_FIREWALLED, UDPTransport.applyIPv6Only(Status.IPV4_UNKNOWN_IPV6_FIREWALLED, true));
        // already-known IPv4 components stay put
        assertEquals(Status.OK, UDPTransport.applyIPv6Only(Status.OK, true));
        assertEquals(Status.IPV4_OK_IPV6_FIREWALLED, UDPTransport.applyIPv6Only(Status.IPV4_OK_IPV6_FIREWALLED, true));
    }

    @Test
    public void testApplyNoIPv6AddressIdentityWhenAddressPresent() {
        assertEquals(Status.OK, UDPTransport.applyNoIPv6Address(Status.OK, true));
        assertEquals(Status.IPV4_OK_IPV6_UNKNOWN, UDPTransport.applyNoIPv6Address(Status.IPV4_OK_IPV6_UNKNOWN, true));
        assertEquals(Status.IPV4_FIREWALLED_IPV6_OK, UDPTransport.applyNoIPv6Address(Status.IPV4_FIREWALLED_IPV6_OK, true));
    }

    @Test
    public void testApplyNoIPv6AddressDropsV6Component() {
        assertEquals(Status.OK, UDPTransport.applyNoIPv6Address(Status.IPV4_OK_IPV6_UNKNOWN, false));
        assertEquals(Status.REJECT_UNSOLICITED, UDPTransport.applyNoIPv6Address(Status.IPV4_FIREWALLED_IPV6_UNKNOWN, false));
        assertEquals(Status.REJECT_UNSOLICITED, UDPTransport.applyNoIPv6Address(Status.IPV4_FIREWALLED_IPV6_OK, false));
        assertEquals(Status.DIFFERENT, UDPTransport.applyNoIPv6Address(Status.IPV4_SNAT_IPV6_UNKNOWN, false));
        assertEquals(Status.DIFFERENT, UDPTransport.applyNoIPv6Address(Status.IPV4_SNAT_IPV6_OK, false));
        // plain v4 statuses untouched
        assertEquals(Status.OK, UDPTransport.applyNoIPv6Address(Status.OK, false));
        assertEquals(Status.DIFFERENT, UDPTransport.applyNoIPv6Address(Status.DIFFERENT, false));
    }

    @Test
    public void testConfirmationRequiredForEnterLeaveFirewalled() {
        // OK -> firewalled
        assertTrue(UDPTransport.requiresConfirmation(Status.OK, Status.DIFFERENT, false));
        // firewalled -> OK
        assertTrue(UDPTransport.requiresConfirmation(Status.DIFFERENT, Status.OK, false));
        // firewalled -> firewalled
        assertTrue(UDPTransport.requiresConfirmation(Status.DIFFERENT, Status.REJECT_UNSOLICITED, false));
        assertTrue(UDPTransport.requiresConfirmation(Status.OK, Status.IPV4_FIREWALLED_IPV6_OK, true));
    }

    @Test
    public void testConfirmationRequiredWhenLeavingIPv4Unknown() {
        assertTrue(UDPTransport.requiresConfirmation(Status.UNKNOWN, Status.OK, false));
        assertTrue(UDPTransport.requiresConfirmation(Status.IPV4_UNKNOWN_IPV6_OK, Status.OK, false));
        // IPv6 test updates do not flip IPv4 flapping
        assertFalse(UDPTransport.requiresConfirmation(Status.UNKNOWN, Status.OK, true));
    }

    @Test
    public void testNoConfirmationForNeutralTransitions() {
        assertFalse(UDPTransport.requiresConfirmation(Status.IPV4_OK_IPV6_UNKNOWN, Status.OK, false));
        assertFalse(UDPTransport.requiresConfirmation(Status.OK, Status.OK, false));
        assertFalse(UDPTransport.requiresConfirmation(Status.IPV4_DISABLED_IPV6_OK, Status.IPV4_DISABLED_IPV6_OK, true));
    }

    @Test
    public void testV6RebuildPublishesWhenFirewalled() {
        assertEquals(UDPTransport.IPv6Rebuild.REBUILD_PUBLISH,
                     UDPTransport.decideIPv6Rebuild(Status.OK, Status.IPV4_OK_IPV6_FIREWALLED, false));
        assertEquals(UDPTransport.IPv6Rebuild.REBUILD_PUBLISH,
                     UDPTransport.decideIPv6Rebuild(Status.OK, Status.DIFFERENT, true));
        assertEquals(UDPTransport.IPv6Rebuild.REBUILD_PUBLISH,
                     UDPTransport.decideIPv6Rebuild(Status.OK, Status.REJECT_UNSOLICITED, false));
    }

    @Test
    public void testV6RebuildAddressWhenLeavingFirewall() {
        // leaving a v6-firewalled state into v6-ok with no explicit operator address
        assertEquals(UDPTransport.IPv6Rebuild.REBUILD_ADDRESS,
                     UDPTransport.decideIPv6Rebuild(Status.REJECT_UNSOLICITED, Status.OK, false));
        assertEquals(UDPTransport.IPv6Rebuild.REBUILD_ADDRESS,
                     UDPTransport.decideIPv6Rebuild(Status.IPV4_OK_IPV6_FIREWALLED, Status.IPV4_UNKNOWN_IPV6_OK, false));
    }

    @Test
    public void testV6RebuildNoneOtherwise() {
        // explicit operator address config suppresses the rebuild
        assertEquals(UDPTransport.IPv6Rebuild.NONE,
                     UDPTransport.decideIPv6Rebuild(Status.REJECT_UNSOLICITED, Status.OK, true));
        // no change
        assertEquals(UDPTransport.IPv6Rebuild.NONE,
                     UDPTransport.decideIPv6Rebuild(Status.OK, Status.OK, false));
        // no v6-firewalled/new or leaving-firewall transition (v4-only change path)
        assertEquals(UDPTransport.IPv6Rebuild.NONE,
                     UDPTransport.decideIPv6Rebuild(Status.OK, Status.IPV4_OK_IPV6_UNKNOWN, false));
    }
}
