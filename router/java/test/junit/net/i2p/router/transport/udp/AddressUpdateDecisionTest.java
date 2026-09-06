package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.util.Addresses;
import org.junit.Test;

/**
 * Pins address-update decision logic extracted from UDPTransport.changeAddress().
 * All helpers are pure static functions in UDPTransport; no router context is
 * required. isLocalAddress() is intentionally not tested here: it depends on
 * real local network interfaces.
 *
 * @since 0.9.71+
 */
public class AddressUpdateDecisionTest {

    private static final byte[] V4_A = Addresses.getIP("1.2.3.4");
    private static final byte[] V4_B = Addresses.getIP("5.6.7.8");

    @Test
    public void testOverrideWhenFixedPortAndKnownListenPort() {
        assertTrue(UDPTransport.shouldOverrideReportedPort(true, 12345, 8888));
        assertTrue(UDPTransport.shouldOverrideReportedPort(true, 12345, 0));
    }

    @Test
    public void testOverrideWhenNoReportedPort() {
        assertTrue(UDPTransport.shouldOverrideReportedPort(false, 12345, 0));
        assertTrue(UDPTransport.shouldOverrideReportedPort(false, 0, 0));
        assertTrue(UDPTransport.shouldOverrideReportedPort(true, 0, 0));
    }

    @Test
    public void testNoOverrideForDynamicPortReport() {
        assertFalse(UDPTransport.shouldOverrideReportedPort(false, 12345, 8888));
        // fixed but no listen port yet: report drives the port
        assertFalse(UDPTransport.shouldOverrideReportedPort(true, 0, 8888));
    }

    @Test
    public void testZeroReportedPortIsNeverChange() {
        assertFalse(UDPTransport.isAddressChange(V4_A, 12345, V4_B, 0));
        assertFalse(UDPTransport.isAddressChange(null, 12345, V4_A, 0));
        assertFalse(UDPTransport.isAddressChange(V4_A, 12345, V4_A, 0));
    }

    @Test
    public void testDifferentPortIsChange() {
        assertTrue(UDPTransport.isAddressChange(V4_A, 12345, V4_A, 8888));
        assertTrue(UDPTransport.isAddressChange(null, 12345, V4_A, 8888));
    }

    @Test
    public void testDifferentHostIsChange() {
        assertTrue(UDPTransport.isAddressChange(V4_A, 12345, V4_B, 12345));
        assertTrue(UDPTransport.isAddressChange(null, 12345, V4_A, 12345));
    }

    @Test
    public void testMatchingAddressIsNotChange() {
        assertFalse(UDPTransport.isAddressChange(V4_A, 12345, V4_A, 12345));
        assertFalse(UDPTransport.isAddressChange(null, 12345, null, 12345));
    }

    @Test
    public void testIPChange() {
        assertTrue(UDPTransport.isIPChange(V4_A, V4_B));
        assertFalse(UDPTransport.isIPChange(V4_A, V4_A));
        assertFalse(UDPTransport.isIPChange(null, V4_B));
    }

    @Test
    public void testSaveExternalPortWhenChangedAndUnconfigured() {
        assertTrue(UDPTransport.shouldSaveExternalPort(12345, 8888, 0));
        assertTrue(UDPTransport.shouldSaveExternalPort(12345, 8888, 9999));
    }

    @Test
    public void testDoNotSaveExternalPort() {
        // operator-configured port takes precedence
        assertFalse(UDPTransport.shouldSaveExternalPort(12345, 8888, 8888));
        // unchanged port
        assertFalse(UDPTransport.shouldSaveExternalPort(12345, 12345, 0));
        // no current or reported port
        assertFalse(UDPTransport.shouldSaveExternalPort(0, 8888, 0));
        assertFalse(UDPTransport.shouldSaveExternalPort(12345, 0, 0));
    }

    @Test
    public void testParseLastAddressChange() {
        assertEquals(0, UDPTransport.parseLastAddressChange(null));
        assertEquals(0, UDPTransport.parseLastAddressChange(""));
        assertEquals(0, UDPTransport.parseLastAddressChange("not-a-number"));
        assertEquals(1234567890L, UDPTransport.parseLastAddressChange("1234567890"));
    }

    @Test
    public void testRestartForLaptopModeWhenAllConditionsHold() {
        long now = 1000000L;
        assertTrue(UDPTransport.shouldRestartForLaptopMode("1.2.3.4", true, true,
                                                           now - 11*60*1000L, now, 5*60*1000L));
        // gap just over 10 minutes and uptime just under 10 minutes qualify
        assertTrue(UDPTransport.shouldRestartForLaptopMode("1.2.3.4", true, true,
                                                           now - 10*60*1000L - 1, now, 10*60*1000L - 1));
    }

    @Test
    public void testNoRestartForLaptopMode() {
        long now = 1000000L;
        // no prior IP on record
        assertFalse(UDPTransport.shouldRestartForLaptopMode(null, true, true,
                                                            now - 11*60*1000L, now, 5*60*1000L));
        // not under the wrapper
        assertFalse(UDPTransport.shouldRestartForLaptopMode("1.2.3.4", false, true,
                                                            now - 11*60*1000L, now, 5*60*1000L));
        // laptop mode off
        assertFalse(UDPTransport.shouldRestartForLaptopMode("1.2.3.4", true, false,
                                                            now - 11*60*1000L, now, 5*60*1000L));
        // IP changed too recently
        assertFalse(UDPTransport.shouldRestartForLaptopMode("1.2.3.4", true, true,
                                                            now - 9*60*1000L, now, 5*60*1000L));
        // router already past the startup window
        assertFalse(UDPTransport.shouldRestartForLaptopMode("1.2.3.4", true, true,
                                                            now - 11*60*1000L, now, 11*60*1000L));
    }
}