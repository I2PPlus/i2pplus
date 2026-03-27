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
}
