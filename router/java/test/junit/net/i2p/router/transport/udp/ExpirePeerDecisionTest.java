package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.router.CommSystemFacade.Status;
import org.junit.Test;

/**
 * Pins peer-expiry / firewall-keepalive decision logic extracted from
 * UDPTransport.ExpirePeerEvent.timeReached(). All helpers are pure static
 * functions in UDPTransport; no router context is required.
 *
 * Constants below pin the package settings they were extracted from:
 * PING_FIREWALL_CUTOFF, RI_STORE_INTERVAL, EXPIRE_INCREMENT/DECREMENT and the
 * short/long loop times.
 *
 * @since 0.9.71+
 */
public class ExpirePeerDecisionTest {

    private static final long PING_FIREWALL_CUTOFF = 15*1000L;
    private static final long RI_STORE_INTERVAL = 29*60*1000L;
    private static final long EXPIRE_INCREMENT = 15*1000L;
    private static final long EXPIRE_DECREMENT = 45*1000L;
    private static final long SHORT_INC = EXPIRE_INCREMENT * 3*1000L / (25*1000L);
    private static final long SHORT_DEC = EXPIRE_DECREMENT * 3*1000L / (25*1000L);

    @Test
    public void testFirewalledStatuses() {
        assertTrue(UDPTransport.isFirewalled(Status.REJECT_UNSOLICITED));
        assertTrue(UDPTransport.isFirewalled(Status.IPV4_FIREWALLED_IPV6_OK));
        assertTrue(UDPTransport.isFirewalled(Status.IPV4_FIREWALLED_IPV6_UNKNOWN));
        assertTrue(UDPTransport.isFirewalled(Status.IPV4_OK_IPV6_FIREWALLED));
        assertTrue(UDPTransport.isFirewalled(Status.IPV4_UNKNOWN_IPV6_FIREWALLED));
        assertTrue(UDPTransport.isFirewalled(Status.IPV4_DISABLED_IPV6_FIREWALLED));
    }

    @Test
    public void testNonFirewalledStatuses() {
        assertFalse(UDPTransport.isFirewalled(Status.OK));
        assertFalse(UDPTransport.isFirewalled(Status.DIFFERENT));
        assertFalse(UDPTransport.isFirewalled(Status.UNKNOWN));
        assertFalse(UDPTransport.isFirewalled(Status.IPV4_OK_IPV6_UNKNOWN));
        assertFalse(UDPTransport.isFirewalled(Status.IPV4_SNAT_IPV6_UNKNOWN));
    }

    @Test
    public void testAdjustExpireTimeoutIncreasesWithCapacity() {
        assertEquals(10000 + EXPIRE_INCREMENT, UDPTransport.adjustExpireTimeout(10000, true, false));
    }

    @Test
    public void testAdjustExpireTimeoutDecreasesWithoutCapacity() {
        assertEquals(300000 - EXPIRE_DECREMENT,
                     UDPTransport.adjustExpireTimeout(300000, false, false));
    }

    @Test
    public void testAdjustExpireTimeoutShortLoopScaled() {
        assertEquals(300000 + SHORT_INC, UDPTransport.adjustExpireTimeout(300000, true, true));
        assertEquals(300000 - SHORT_DEC, UDPTransport.adjustExpireTimeout(300000, false, true));
    }

    @Test
    public void testAdjustExpireTimeoutClamped() {
        assertEquals(UDPTransport.EXPIRE_TIMEOUT,
                     UDPTransport.adjustExpireTimeout(UDPTransport.EXPIRE_TIMEOUT - 1, true, false));
        assertEquals(UDPTransport.MIN_EXPIRE_TIMEOUT,
                     UDPTransport.adjustExpireTimeout(UDPTransport.MIN_EXPIRE_TIMEOUT, false, false));
    }

    @Test
    public void testIdleHardCutoff() {
        final long now = 1000000L;
        assertEquals(now - 30*1000L, UDPTransport.idleHardCutoff(now, true));
        assertEquals(Long.MIN_VALUE, UDPTransport.idleHardCutoff(now, false));
    }

    @Test
    public void testInactivityCutoffFirewalledFloored() {
        final long now = 1000000L;
        // non-firewalled: plain now - timeout
        assertEquals(500000L, UDPTransport.inactivityCutoff(now, 500000L, false, 25*60*1000L));
        // firewalled: floored at the firewalled minimum when timeout is below it
        assertEquals(now - 1500000L, UDPTransport.inactivityCutoff(now, 500000L, true, 1500000L));
        // firewalled: timeout above the floor wins
        assertEquals(now - 2000000L, UDPTransport.inactivityCutoff(now, 2000000L, true, 1500000L));
        // firewalled boundary: timeout exactly at the floor
        assertEquals(now - 1500000L, UDPTransport.inactivityCutoff(now, 1500000L, true, 1500000L));
    }

    @Test
    public void testReliedOnRecently() {
        assertTrue(UDPTransport.reliedOnRecently(1, 0L, 1000L));
        assertTrue(UDPTransport.reliedOnRecently(0, 2000L, 1000L));
        assertFalse(UDPTransport.reliedOnRecently(0, 1000L, 1000L));
        assertFalse(UDPTransport.reliedOnRecently(0, 0L, 1000L));
    }

    @Test
    public void testMayDisconnectLowTraffic() {
        assertTrue(UDPTransport.mayDisconnectLowTraffic(false, false, true, 2, 2));
        // no capacity + any inbound is fine
        assertTrue(UDPTransport.mayDisconnectLowTraffic(false, true, true, 2, 2));
        // capacity + inbound peer disqualifies
        assertFalse(UDPTransport.mayDisconnectLowTraffic(true, true, true, 2, 2));
        // mayDisconnect required
        assertFalse(UDPTransport.mayDisconnectLowTraffic(false, false, false, 2, 2));
        // traffic above the <=2 budget
        assertFalse(UDPTransport.mayDisconnectLowTraffic(false, false, true, 3, 2));
        assertFalse(UDPTransport.mayDisconnectLowTraffic(false, false, true, 2, 3));
    }

    @Test
    public void testPickInactivityCutoffPrecedence() {
        assertEquals(100L, UDPTransport.pickInactivityCutoff(true, false, 100L, 200L, 300L));
        assertEquals(200L, UDPTransport.pickInactivityCutoff(false, true, 100L, 200L, 300L));
        assertEquals(300L, UDPTransport.pickInactivityCutoff(false, false, 100L, 200L, 300L));
        // relied-on wins over stale low-traffic
        assertEquals(100L, UDPTransport.pickInactivityCutoff(true, true, 100L, 200L, 300L));
    }

    @Test
    public void testIdleSince() {
        assertTrue(UDPTransport.idleSince(50L, 60L, 100L));
        assertFalse(UDPTransport.idleSince(150L, 60L, 100L));
        assertFalse(UDPTransport.idleSince(50L, 150L, 100L));
        // equality at the cutoff does not count as idle-before
        assertFalse(UDPTransport.idleSince(100L, 50L, 100L));
        assertFalse(UDPTransport.idleSince(50L, 100L, 100L));
    }

    @Test
    public void testFirewallPingSliceMatched() {
        assertTrue(UDPTransport.firewallPingSliceMatched(7, 7));
        assertTrue(UDPTransport.firewallPingSliceMatched(0, 4));
        assertFalse(UDPTransport.firewallPingSliceMatched(0, 1));
        assertFalse(UDPTransport.firewallPingSliceMatched(3, 6));
    }

    @Test
    public void testShouldFirewallPing() {
        assertTrue(UDPTransport.shouldFirewallPing(true, true, 1000L, 1000L, PING_FIREWALL_CUTOFF));
        assertFalse(UDPTransport.shouldFirewallPing(false, true, 1000L, 1000L, PING_FIREWALL_CUTOFF));
        assertFalse(UDPTransport.shouldFirewallPing(true, false, 1000L, 1000L, PING_FIREWALL_CUTOFF));
        assertFalse(UDPTransport.shouldFirewallPing(true, true, 16000L, 1000L, PING_FIREWALL_CUTOFF));
        assertFalse(UDPTransport.shouldFirewallPing(true, true, 1000L, 16000L, PING_FIREWALL_CUTOFF));
        // strict < at the cutoff boundary
        assertFalse(UDPTransport.shouldFirewallPing(true, true, PING_FIREWALL_CUTOFF, 1000L, PING_FIREWALL_CUTOFF));
    }

    @Test
    public void testShouldStoreRI() {
        assertFalse(UDPTransport.shouldStoreRI(0L, 3000L));
        assertFalse(UDPTransport.shouldStoreRI(RI_STORE_INTERVAL - 1, 3000L));
        assertTrue(UDPTransport.shouldStoreRI(RI_STORE_INTERVAL, 3000L));
        assertTrue(UDPTransport.shouldStoreRI(RI_STORE_INTERVAL + 2999L, 3000L));
        assertFalse(UDPTransport.shouldStoreRI(RI_STORE_INTERVAL + 3000L, 3000L));
    }
}