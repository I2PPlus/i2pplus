package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.transport.TransportUtil;

import org.junit.Test;

/**
 *  Unit tests for the reachability-status decisions extracted from
 *  {@link NTCPTransport#getReachabilityStatus()}.
 *
 *  <p>Covers the three-stage cascade that replaces the original 150+ line method:
 *  the header decides the hopeless cases up front, the inbound-activity stage
 *  decides whenever either family recently worked, and the tail handles the
 *  live-connection scan outcome. The null contract (\"null means run the live
 *  connection check\") and the \"never null tail\" contract are pinned explicitly.
 *
 *  @since 0.9.71+
 */
public class NTCPTransportReachabilityDecisionTest {

    @Test
    public void showFirewalledWhenBothFamiliesFirewalled() {
        assertEquals(Status.REJECT_UNSOLICITED, NTCPTransport.decideReachabilityHeader(
                true, true, true, true, true, true));
    }

    @Test
    public void showFirewalledWhenNotAlive() {
        assertEquals(Status.UNKNOWN, NTCPTransport.decideReachabilityHeader(
                false, false, false, true, true, true));
    }

    @Test
    public void showFirewalledWhenNoAddressesPublished() {
        assertEquals(Status.REJECT_UNSOLICITED, NTCPTransport.decideReachabilityHeader(
                false, false, true, false, false, true));
        assertEquals(Status.UNKNOWN, NTCPTransport.decideReachabilityHeader(
                false, false, true, false, false, false));
    }

    @Test
    public void showFirewalledContinuesToInboundActivity() {
        assertNull(NTCPTransport.decideReachabilityHeader(
                false, false, true, true, true, true));
    }

    @Test
    public void bothFamiliesReachable() {
        assertEquals(Status.OK, NTCPTransport.decideReachabilityFromInboundActivity(
                true, true, false, false, false, false, true, true, true, false));
    }

    @Test
    public void v4OkV6Disabled() {
        assertEquals(Status.OK, NTCPTransport.decideReachabilityFromInboundActivity(
                true, false, false, true, false, false, true, false, true, false));
    }

    @Test
    public void v4OkNoIPv6AddressSeen() {
        assertEquals(Status.OK, NTCPTransport.decideReachabilityFromInboundActivity(
                true, false, false, false, false, false, true, false, false, false));
    }

    @Test
    public void v4OkV6Firewalled() {
        assertEquals(Status.IPV4_OK_IPV6_FIREWALLED, NTCPTransport.decideReachabilityFromInboundActivity(
                true, false, false, false, false, true, true, true, true, false));
    }

    @Test
    public void v4OkV6Unpublished() {
        assertEquals(Status.IPV4_OK_IPV6_UNKNOWN, NTCPTransport.decideReachabilityFromInboundActivity(
                true, false, false, false, false, false, true, false, true, false));
    }

    @Test
    public void v6OkV4Disabled() {
        assertEquals(Status.IPV4_DISABLED_IPV6_OK, NTCPTransport.decideReachabilityFromInboundActivity(
                false, true, true, false, false, false, false, true, false, false));
    }

    @Test
    public void v6OkV4Firewalled() {
        assertEquals(Status.IPV4_FIREWALLED_IPV6_OK, NTCPTransport.decideReachabilityFromInboundActivity(
                false, true, false, false, true, false, false, true, false, false));
    }

    @Test
    public void v6OkV4UnpublishedShowFirewalled() {
        assertEquals(Status.IPV4_FIREWALLED_IPV6_OK, NTCPTransport.decideReachabilityFromInboundActivity(
                false, true, false, false, false, false, false, true, true, true));
    }

    @Test
    public void v6OkV4UnpublishedShowReachable() {
        assertEquals(Status.IPV4_UNKNOWN_IPV6_OK, NTCPTransport.decideReachabilityFromInboundActivity(
                false, true, false, false, false, false, false, true, false, false));
    }

    @Test
    public void neitherFamilyIntendsLiveScan() {
        assertNull(NTCPTransport.decideReachabilityFromInboundActivity(
                false, false, false, false, false, false, true, true, false, false));
    }

    @Test
    public void tailV4OkNoIPv6AddressSeen() {
        assertEquals(Status.OK, NTCPTransport.decideReachabilityTail(true, false, false, true, false));
    }

    @Test
    public void tailV4OkIPv6Exists() {
        assertEquals(Status.IPV4_OK_IPV6_UNKNOWN, NTCPTransport.decideReachabilityTail(true, false, false, true, true));
    }

    @Test
    public void tailV6OkAloneShowFirewalled() {
        assertEquals(Status.IPV4_FIREWALLED_IPV6_OK, NTCPTransport.decideReachabilityTail(false, true, false, true, false));
        assertEquals(Status.IPV4_UNKNOWN_IPV6_OK, NTCPTransport.decideReachabilityTail(false, true, false, false, false));
    }

    @Test
    public void tailNothingWorksV4Disabled() {
        assertEquals(Status.IPV4_DISABLED_IPV6_UNKNOWN, NTCPTransport.decideReachabilityTail(false, false, true, true, false));
    }

    @Test
    public void tailNothingWorksShowFirewalled() {
        assertEquals(Status.REJECT_UNSOLICITED, NTCPTransport.decideReachabilityTail(false, false, false, true, false));
        assertSame(Status.REJECT_UNSOLICITED, NTCPTransport.decideReachabilityTail(false, false, false, true, true));
    }

    @Test
    public void tailNothingWorksShowReachable() {
        assertEquals(Status.UNKNOWN, NTCPTransport.decideReachabilityTail(false, false, false, false, false));
        assertSame(Status.UNKNOWN, NTCPTransport.decideReachabilityTail(false, false, false, false, true));
    }

    @Test
    public void trailingDecisionsNeverNull() {
        // Pin the "never null" contract for the full boolean space on the tail.
        for (int mask = 0; mask < 16; mask++) {
            boolean v4OK = (mask & 1) != 0;
            boolean v6OK = (mask & 2) != 0;
            boolean v4Disabled = (mask & 4) != 0;
            boolean showFirewalled = (mask & 8) != 0;
            assertNotNull(NTCPTransport.decideReachabilityTail(v4OK, v6OK, v4Disabled, showFirewalled, mask % 2 == 0));
        }
    }

    @Test
    public void isFamilyDisabled() {
        assertTrue(NTCPTransport.isFamilyDisabled(TransportUtil.IPv6Config.IPV6_DISABLED, true));
        assertFalse(NTCPTransport.isFamilyDisabled(TransportUtil.IPv6Config.IPV6_DISABLED, false));
        assertFalse(NTCPTransport.isFamilyDisabled(TransportUtil.IPv6Config.IPV6_ONLY, true));
        assertTrue(NTCPTransport.isFamilyDisabled(TransportUtil.IPv6Config.IPV6_ONLY, false));
        assertFalse(NTCPTransport.isFamilyDisabled(TransportUtil.IPv6Config.IPV6_PREFERRED, true));
        assertFalse(NTCPTransport.isFamilyDisabled(TransportUtil.IPv6Config.IPV6_PREFERRED, false));
        assertFalse(NTCPTransport.isFamilyDisabled(TransportUtil.IPv6Config.IPV6_NOT_PREFERRED, true));
        assertFalse(NTCPTransport.isFamilyDisabled(TransportUtil.IPv6Config.IPV6_NOT_PREFERRED, false));
    }
}