package net.i2p.router.transport;

import static org.junit.Assert.*;

import net.i2p.data.router.RouterAddress;
import net.i2p.util.AddressType;

import org.junit.Test;

public class TransportUtilTest {

    private RouterAddress makeAddress(String host) {
        net.i2p.util.OrderedProperties props = new net.i2p.util.OrderedProperties();
        if (host != null) {
            props.setProperty("host", host);
        }
        return new RouterAddress("test", props, 10);
    }

    private RouterAddress makeAddressWithCaps(String caps, String host) {
        net.i2p.util.OrderedProperties props = new net.i2p.util.OrderedProperties();
        if (host != null) props.setProperty("host", host);
        if (caps != null) props.setProperty("caps", caps);
        return new RouterAddress("test", props, 10);
    }

    @Test
    public void testIsValidPortInRange() {
        assertTrue(TransportUtil.isValidPort(9151));
        assertTrue(TransportUtil.isValidPort(12345));
        assertTrue(TransportUtil.isValidPort(30777));
    }

    @Test
    public void testIsValidPortLowRange() {
        assertTrue(TransportUtil.isValidPort(1024));
        assertTrue(TransportUtil.isValidPort(2000));
    }

    @Test
    public void testIsValidPortPrivileged() {
        assertFalse(TransportUtil.isValidPort(0));
        assertFalse(TransportUtil.isValidPort(80));
        assertFalse(TransportUtil.isValidPort(1023));
    }

    @Test
    public void testIsValidPortAboveMax() {
        assertFalse(TransportUtil.isValidPort(65536));
    }

    @Test
    public void testIsValidPortReservedPorts() {
        assertFalse(TransportUtil.isValidPort(1900));
        assertFalse(TransportUtil.isValidPort(1719));
        assertFalse(TransportUtil.isValidPort(1720));
        assertFalse(TransportUtil.isValidPort(2049));
        assertFalse(TransportUtil.isValidPort(4444));
        assertFalse(TransportUtil.isValidPort(4445));
        assertFalse(TransportUtil.isValidPort(5060));
        assertFalse(TransportUtil.isValidPort(5061));
        assertFalse(TransportUtil.isValidPort(9001));
        assertFalse(TransportUtil.isValidPort(9030));
        assertFalse(TransportUtil.isValidPort(9050));
        assertFalse(TransportUtil.isValidPort(9100));
        assertFalse(TransportUtil.isValidPort(9150));
        assertFalse(TransportUtil.isValidPort(31000));
        assertFalse(TransportUtil.isValidPort(32000));
    }

    @Test
    public void testIsValidPortI2PRange() {
        for (int p = 7650; p <= 7668; p++) {
            assertFalse("Port " + p + " should be invalid", TransportUtil.isValidPort(p));
        }
    }

    @Test
    public void testIsValidPortIRCRange() {
        for (int p = 6665; p <= 6669; p++) {
            assertFalse("Port " + p + " should be invalid", TransportUtil.isValidPort(p));
        }
        assertFalse(TransportUtil.isValidPort(6697));
    }

    @Test
    public void testIsValidPortOtherReserved() {
        assertFalse(TransportUtil.isValidPort(2827));
        assertFalse(TransportUtil.isValidPort(3659));
        assertFalse(TransportUtil.isValidPort(4045));
        assertFalse(TransportUtil.isValidPort(6000));
        assertFalse(TransportUtil.isValidPort(7070));
        assertFalse(TransportUtil.isValidPort(8080));
    }

    @Test
    public void testIsIPv6WithColonHost() {
        RouterAddress addr = makeAddress("2001:db8::1");
        assertTrue(TransportUtil.isIPv6(addr));
    }

    @Test
    public void testIsIPv6WithIPv4Host() {
        RouterAddress addr = makeAddress("192.168.1.1");
        assertFalse(TransportUtil.isIPv6(addr));
    }

    @Test
    public void testIsIPv6WithNullHostAndIPv6Caps() {
        String caps = "6";
        RouterAddress addr = makeAddressWithCaps(caps, null);
        assertTrue(TransportUtil.isIPv6(addr));
    }

    @Test
    public void testIsIPv6WithNullHostAndIPv4Caps() {
        String caps = "4";
        RouterAddress addr = makeAddressWithCaps(caps, null);
        assertFalse(TransportUtil.isIPv6(addr));
    }

    @Test
    public void testIsIPv6WithNullHostAndBothCaps() {
        String caps = "46";
        RouterAddress addr = makeAddressWithCaps(caps, null);
        assertFalse(TransportUtil.isIPv6(addr));
    }

    @Test
    public void testIsIPv6WithNullHostNoCaps() {
        RouterAddress addr = makeAddressWithCaps(null, null);
        assertFalse(TransportUtil.isIPv6(addr));
    }

    @Test
    public void testIsYggdrasilValid() {
        RouterAddress addr = makeAddress("200:abcd::1");
        assertTrue(TransportUtil.isYggdrasil(addr));
    }

    @Test
    public void testIsYggdrasil3xx() {
        RouterAddress addr = makeAddress("300:abcd::1");
        assertTrue(TransportUtil.isYggdrasil(addr));
    }

    @Test
    public void testIsYggdrasilInvalid() {
        RouterAddress addr = makeAddress("2001:db8::1");
        assertFalse(TransportUtil.isYggdrasil(addr));
    }

    @Test
    public void testIsYggdrasilNullHost() {
        RouterAddress addr = makeAddress(null);
        assertFalse(TransportUtil.isYggdrasil(addr));
    }

    @Test
    public void testIsYggdrasilIPv4() {
        RouterAddress addr = makeAddress("1.2.3.4");
        assertFalse(TransportUtil.isYggdrasil(addr));
    }

    @Test
    public void testGetTypeFromStringIPv4() {
        assertEquals(AddressType.IPV4, TransportUtil.getType("192.168.1.1"));
    }

    @Test
    public void testGetTypeFromStringIPv6() {
        assertEquals(AddressType.IPV6, TransportUtil.getType("2001:db8::1"));
    }

    @Test
    public void testGetTypeFromStringYggdrasil() {
        assertEquals(AddressType.YGG, TransportUtil.getType("200:abcd::1"));
    }

    @Test
    public void testGetTypeFromStringNull() {
        assertNull(TransportUtil.getType((String) null));
    }

    @Test
    public void testGetTypeFromStringHostname() {
        assertNull(TransportUtil.getType("example.com"));
    }

    @Test
    public void testGetTypeFromBytesIPv4() {
        byte[] addr = {10, 0, 0, 1};
        assertEquals(AddressType.IPV4, TransportUtil.getType(addr));
    }

    @Test
    public void testGetTypeFromBytesIPv6() {
        byte[] addr = {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertEquals(AddressType.IPV6, TransportUtil.getType(addr));
    }

    @Test
    public void testGetTypeFromBytesYggdrasil() {
        byte[] addr = {0x02, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertEquals(AddressType.YGG, TransportUtil.getType(addr));
    }

    @Test
    public void testGetTypeFromBytesYggdrasil3() {
        byte[] addr = {0x03, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertEquals(AddressType.YGG, TransportUtil.getType(addr));
    }

    @Test
    public void testGetTypeFromBytesNull() {
        assertNull(TransportUtil.getType((byte[]) null));
    }

    @Test
    public void testGetTypeFromBytesInvalidLength() {
        byte[] addr = {10, 0, 0};
        assertNull(TransportUtil.getType(addr));
    }

    @Test
    public void testGetIPv6ConfigFromString() {
        assertEquals(TransportUtil.IPv6Config.IPV6_DISABLED, TransportUtil.getIPv6Config("false"));
        assertEquals(TransportUtil.IPv6Config.IPV6_ENABLED, TransportUtil.getIPv6Config("enable"));
        assertEquals(TransportUtil.IPv6Config.IPV6_PREFERRED, TransportUtil.getIPv6Config("preferIPv6"));
        assertEquals(TransportUtil.IPv6Config.IPV6_NOT_PREFERRED, TransportUtil.getIPv6Config("preferIPv4"));
        assertEquals(TransportUtil.IPv6Config.IPV6_ONLY, TransportUtil.getIPv6Config("only"));
    }

    @Test
    public void testGetIPv6ConfigNullString() {
        assertEquals(TransportUtil.DEFAULT_IPV6_CONFIG, TransportUtil.getIPv6Config((String) null));
    }

    @Test
    public void testGetIPv6ConfigUnknownString() {
        assertEquals(TransportUtil.DEFAULT_IPV6_CONFIG, TransportUtil.getIPv6Config("bogus"));
    }

    @Test
    public void testGetIPv6ConfigTrueAlias() {
        assertEquals(TransportUtil.IPv6Config.IPV6_ENABLED, TransportUtil.getIPv6Config("true"));
    }

    @Test
    public void testGetIPv6ConfigDisableAlias() {
        assertEquals(TransportUtil.IPv6Config.IPV6_DISABLED, TransportUtil.getIPv6Config("disable"));
    }

    @Test
    public void testIPv6ConfigToConfigString() {
        assertEquals("false", TransportUtil.IPv6Config.IPV6_DISABLED.toConfigString());
        assertEquals("enable", TransportUtil.IPv6Config.IPV6_ENABLED.toConfigString());
        assertEquals("preferIPv4", TransportUtil.IPv6Config.IPV6_NOT_PREFERRED.toConfigString());
        assertEquals("preferIPv6", TransportUtil.IPv6Config.IPV6_PREFERRED.toConfigString());
        assertEquals("only", TransportUtil.IPv6Config.IPV6_ONLY.toConfigString());
    }

    @Test
    public void testDefaultIPv6Config() {
        assertEquals(TransportUtil.IPv6Config.IPV6_PREFERRED, TransportUtil.DEFAULT_IPV6_CONFIG);
    }

    @Test
    public void testIsPubliclyRoutableIPv4Public() {
        byte[] addr = {(byte) 8, (byte) 8, (byte) 8, (byte) 8};
        assertTrue(TransportUtil.isPubliclyRoutable(addr, true));
    }

    @Test
    public void testIsPubliclyRoutableIPv4Private10() {
        byte[] addr = {10, 0, 0, 1};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, true));
    }

    @Test
    public void testIsPubliclyRoutableIPv4Loopback() {
        byte[] addr = {127, 0, 0, 1};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, true));
    }

    @Test
    public void testIsPubliclyRoutableIPv4Multicast() {
        byte[] addr = {(byte) 224, 0, 0, 1};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, true));
    }

    @Test
    public void testIsPubliclyRoutableIPv4Private192() {
        byte[] addr = {(byte) 192, (byte) 168, 1, 1};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, true));
    }

    @Test
    public void testIsPubliclyRoutableIPv4Private172() {
        byte[] addr = {(byte) 172, 16, 0, 1};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, true));
    }

    @Test
    public void testIsPubliclyRoutableIPv4Zero() {
        byte[] addr = {0, 0, 0, 0};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, true));
    }

    @Test
    public void testIsPubliclyRoutableIPv6Global() {
        // 2001:db8::/32 is RFC 3849 documentation prefix, not routable
        byte[] addr = {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, true));
    }

    @Test
    public void testIsPubliclyRoutableIPv6NotPermitted() {
        byte[] addr = {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, false));
    }

    @Test
    public void testIsPubliclyRoutableIPv6Loopback() {
        byte[] addr = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, true));
    }

    @Test
    public void testIsPubliclyRoutableIPv6ULA() {
        byte[] addr = {(byte) 0xfc, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, true));
    }

    @Test
    public void testIsPubliclyRoutableIPv4DisallowIPv4() {
        byte[] addr = {(byte) 8, (byte) 8, (byte) 8, (byte) 8};
        assertFalse(TransportUtil.isPubliclyRoutable(addr, false, true));
    }

    @Test
    public void testSelectRandomPortInRange() {
        // Can't easily mock RouterContext here, but verify the constants
        assertTrue(TransportUtil.isValidPort(9151));
        assertTrue(TransportUtil.isValidPort(30777));
    }
}
