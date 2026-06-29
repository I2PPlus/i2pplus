package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import net.i2p.data.Hash;

import org.junit.Test;

public class FloodThrottlerTest {

    @Test
    public void testFirstCallsNotThrottled() {
        FloodThrottler throttler = new FloodThrottler();
        Hash h = new Hash();
        h.setData(new byte[Hash.HASH_LENGTH]);

        assertFalse(throttler.shouldThrottle(h));
        assertFalse(throttler.shouldThrottle(h));
        assertFalse(throttler.shouldThrottle(h));
    }

    @Test
    public void testThrottleAfterMaxFloods() {
        FloodThrottler throttler = new FloodThrottler();
        Hash h = new Hash();
        h.setData(new byte[Hash.HASH_LENGTH]);

        // MAX_FLOODS = 3, so first 3 should not throttle
        assertFalse(throttler.shouldThrottle(h));
        assertFalse(throttler.shouldThrottle(h));
        assertFalse(throttler.shouldThrottle(h));
        // 4th should throttle
        assertTrue(throttler.shouldThrottle(h));
        assertTrue(throttler.shouldThrottle(h));
    }

    @Test
    public void testDifferentHashesCountedSeparately() {
        FloodThrottler throttler = new FloodThrottler();
        Hash h1 = new Hash();
        h1.setData(new byte[Hash.HASH_LENGTH]);
        Hash h2 = new Hash();
        byte[] d2 = new byte[Hash.HASH_LENGTH];
        d2[0] = 1;
        h2.setData(d2);

        assertFalse(throttler.shouldThrottle(h1));
        assertFalse(throttler.shouldThrottle(h2));
        assertFalse(throttler.shouldThrottle(h1));
        assertFalse(throttler.shouldThrottle(h2));
        assertFalse(throttler.shouldThrottle(h1));
        assertTrue(throttler.shouldThrottle(h1));
        assertFalse(throttler.shouldThrottle(h2));
        assertTrue(throttler.shouldThrottle(h2));
    }

    @Test
    public void testNullHashHandledGracefully() {
        FloodThrottler throttler = new FloodThrottler();
        try {
            throttler.shouldThrottle(null);
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void testMultipleHashesAllThrottled() {
        FloodThrottler throttler = new FloodThrottler();
        Hash h1 = new Hash();
        h1.setData(new byte[Hash.HASH_LENGTH]);
        Hash h2 = new Hash();
        byte[] d2 = new byte[Hash.HASH_LENGTH];
        d2[0] = 1;
        h2.setData(d2);
        Hash h3 = new Hash();
        byte[] d3 = new byte[Hash.HASH_LENGTH];
        d3[0] = 2;
        h3.setData(d3);

        // Fill up all three to the limit
        for (int i = 0; i < 3; i++) {
            assertFalse(throttler.shouldThrottle(h1));
            assertFalse(throttler.shouldThrottle(h2));
            assertFalse(throttler.shouldThrottle(h3));
        }
        // All three should be throttled now
        assertTrue(throttler.shouldThrottle(h1));
        assertTrue(throttler.shouldThrottle(h2));
        assertTrue(throttler.shouldThrottle(h3));
    }

    @Test
    public void testCountAfterMaxFloods() {
        FloodThrottler throttler = new FloodThrottler();
        Hash h = new Hash();
        h.setData(new byte[Hash.HASH_LENGTH]);

        // First 3 should return false (not throttled)
        assertFalse(throttler.shouldThrottle(h));
        assertFalse(throttler.shouldThrottle(h));
        assertFalse(throttler.shouldThrottle(h));
        // 4th and 5th should return true (throttled)
        assertTrue(throttler.shouldThrottle(h));
        assertTrue(throttler.shouldThrottle(h));
        assertTrue(throttler.shouldThrottle(h));
    }
}
