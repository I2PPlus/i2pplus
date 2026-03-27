package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for MTU.rectify().
 */
public class MTUTest {

    @Test
    public void testRectifyIPv4Standard() {
        // 1500 % 16 = 4, should become 1484 (mod 12)
        assertEquals(1484, MTU.rectify(false, 1500));
    }

    @Test
    public void testRectifyIPv4AlreadyAligned() {
        // 1484 % 16 = 12, already aligned
        assertEquals(1484, MTU.rectify(false, 1484));
    }

    @Test
    public void testRectifyIPv4ClampedToMin() {
        int result = MTU.rectify(false, 100);
        assertTrue(result >= PeerState.MIN_MTU);
    }

    @Test
    public void testRectifyIPv4ClampedToMax() {
        int result = MTU.rectify(false, 10000);
        assertTrue(result <= PeerState.LARGE_MTU);
    }

    @Test
    public void testRectifyIPv4Mod12() {
        for (int mtu = 620; mtu <= 1500; mtu += 7) {
            int result = MTU.rectify(false, mtu);
            assertTrue("MTU " + mtu + " -> " + result + " mod 16 = " + (result % 16), result % 16 == 12);
        }
    }

    @Test
    public void testRectifyIPv6Standard() {
        // 1500 % 16 = 4, should become 1496 (mod 0)
        // But clamped to MAX_IPV6_MTU = 1488
        int result = MTU.rectify(true, 1500);
        assertTrue(result % 16 == 0);
    }

    @Test
    public void testRectifyIPv6ClampedToMin() {
        int result = MTU.rectify(true, 100);
        assertEquals(PeerState.MIN_IPV6_MTU, result);
    }

    @Test
    public void testRectifyIPv6ClampedToMax() {
        int result = MTU.rectify(true, 10000);
        assertTrue(result <= PeerState.MAX_IPV6_MTU);
    }

    @Test
    public void testRectifyIPv6ModZero() {
        for (int mtu = 1280; mtu <= 1500; mtu += 7) {
            int result = MTU.rectify(true, mtu);
            assertTrue("MTU " + mtu + " -> " + result + " mod 16 = " + (result % 16), result % 16 == 0);
        }
    }

    @Test
    public void testRectifyIPv4Negative() {
        int result = MTU.rectify(false, -100);
        assertTrue(result >= PeerState.MIN_MTU);
    }

    @Test
    public void testRectifyIPv6Negative() {
        int result = MTU.rectify(true, -100);
        assertTrue(result >= PeerState.MIN_IPV6_MTU);
    }

    @Test
    public void testRectifyIPv4Zero() {
        int result = MTU.rectify(false, 0);
        assertTrue(result >= PeerState.MIN_MTU);
    }

    @Test
    public void testRectifyBoundsIPv4() {
        int result = MTU.rectify(false, PeerState.MIN_MTU);
        assertEquals(PeerState.MIN_MTU, result);
    }

    @Test
    public void testRectifyBoundsIPv6() {
        int result = MTU.rectify(true, PeerState.MIN_IPV6_MTU);
        assertEquals(PeerState.MIN_IPV6_MTU, result);
    }
}
