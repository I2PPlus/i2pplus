package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import org.junit.Test;

public class IPThrottlerTest {

    @Test
    public void testIPv4BelowMax() {
        IPThrottler throttler = new IPThrottler(5, 60000);
        byte[] ip = {10, 0, 0, 1};
        for (int i = 0; i < 5; i++) {
            assertFalse(throttler.shouldThrottle(ip));
        }
    }

    @Test
    public void testIPv4ThrottleAfterMax() {
        IPThrottler throttler = new IPThrottler(3, 60000);
        byte[] ip = {(byte) 192, (byte) 168, 1, 1};
        assertFalse(throttler.shouldThrottle(ip));
        assertFalse(throttler.shouldThrottle(ip));
        assertFalse(throttler.shouldThrottle(ip));
        assertTrue(throttler.shouldThrottle(ip));
    }

    @Test
    public void testIPv4DifferentIPsIndependent() {
        IPThrottler throttler = new IPThrottler(2, 60000);
        byte[] ip1 = {10, 0, 0, 1};
        byte[] ip2 = {10, 0, 0, 2};

        assertFalse(throttler.shouldThrottle(ip1));
        assertFalse(throttler.shouldThrottle(ip2));
        assertFalse(throttler.shouldThrottle(ip1));
        assertTrue(throttler.shouldThrottle(ip1));
        assertFalse(throttler.shouldThrottle(ip2));
        assertTrue(throttler.shouldThrottle(ip2));
    }

    @Test
    public void testIPv6BelowMax() {
        IPThrottler throttler = new IPThrottler(5, 60000);
        byte[] ip = {0x20, 0x01, 0x0d, (byte) 0xb8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
        for (int i = 0; i < 5; i++) {
            assertFalse(throttler.shouldThrottle(ip));
        }
    }

    @Test
    public void testIPv6ThrottleAfterMax() {
        IPThrottler throttler = new IPThrottler(2, 60000);
        byte[] ip = {0x20, 0x01, 0x0d, (byte) 0xb8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
        assertFalse(throttler.shouldThrottle(ip));
        assertFalse(throttler.shouldThrottle(ip));
        assertTrue(throttler.shouldThrottle(ip));
    }

    @Test
    public void testIPv6DifferentFirst8BytesIndependent() {
        IPThrottler throttler = new IPThrottler(2, 60000);
        byte[] ip1 = {0x20, 0x01, 0x0d, (byte) 0xb8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};
        byte[] ip2 = {0x20, 0x01, 0x0d, (byte) 0xb8, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01};

        assertFalse(throttler.shouldThrottle(ip1));
        assertFalse(throttler.shouldThrottle(ip2));
        assertFalse(throttler.shouldThrottle(ip1));
        assertTrue(throttler.shouldThrottle(ip1));
        assertFalse(throttler.shouldThrottle(ip2));
        assertTrue(throttler.shouldThrottle(ip2));
    }

    @Test
    public void testMaxOneAllowsExactlyOne() {
        IPThrottler throttler = new IPThrottler(1, 60000);
        byte[] ip = {127, 0, 0, 1};
        assertFalse(throttler.shouldThrottle(ip));
        assertTrue(throttler.shouldThrottle(ip));
    }

    @Test(expected = Exception.class)
    public void testInvalidLengthThrows() {
        IPThrottler throttler = new IPThrottler(5, 60000);
        byte[] badIp = {10, 0, 0};
        throttler.shouldThrottle(badIp);
    }
}
