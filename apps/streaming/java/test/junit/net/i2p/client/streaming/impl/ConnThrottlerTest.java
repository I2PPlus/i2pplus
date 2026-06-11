package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.util.SimpleTimer2;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for ConnThrottler behavior.
 * Misconfigured throttling leads to either poor connection
 * acceptance rate or vulnerability to DoS.
 */
public class ConnThrottlerTest {

    private SimpleTimer2 _timer;

    @Before
    public void setUp() {
        _timer = new SimpleTimer2(I2PAppContext.getGlobalContext());
    }

    @Test
    public void testShouldThrottleIndividual() {
        ConnThrottler ct = new ConnThrottler(3, 0, 60000, _timer);
        Hash h = new Hash(new byte[32]);
        assertFalse(ct.shouldThrottle(h));
        assertFalse(ct.shouldThrottle(h));
        assertFalse(ct.shouldThrottle(h));
        assertTrue("4th connection should be throttled", ct.shouldThrottle(h));
    }

    @Test
    public void testShouldThrottleDifferentPeers() {
        ConnThrottler ct = new ConnThrottler(2, 0, 60000, _timer);
        Hash h1 = new Hash(new byte[32]);
        Hash h2 = new Hash(new byte[32]);
        h2.getData()[0] = 1;
        assertFalse(ct.shouldThrottle(h1));
        assertFalse(ct.shouldThrottle(h1));
        assertTrue(ct.shouldThrottle(h1));
        // Different peer should not be throttled by individual limit
        assertFalse(ct.shouldThrottle(h2));
        assertFalse(ct.shouldThrottle(h2));
        assertTrue(ct.shouldThrottle(h2));
    }

    @Test
    public void testIsThrottled() {
        ConnThrottler ct = new ConnThrottler(2, 0, 60000, _timer);
        Hash h = new Hash(new byte[32]);
        assertFalse(ct.isThrottled(h));
        ct.shouldThrottle(h);
        assertFalse(ct.isThrottled(h));
        ct.shouldThrottle(h);
        assertFalse(ct.isThrottled(h));
        ct.shouldThrottle(h);
        assertTrue(ct.isThrottled(h));
    }

    @Test
    public void testIsOverBy() {
        ConnThrottler ct = new ConnThrottler(2, 0, 60000, _timer);
        Hash h = new Hash(new byte[32]);
        assertFalse(ct.isOverBy(h, 0));
        ct.shouldThrottle(h);
        ct.shouldThrottle(h);
        ct.shouldThrottle(h);
        assertFalse(ct.isOverBy(h, 1));
        assertTrue(ct.isOverBy(h, 0));
        ct.shouldThrottle(h);
        assertTrue(ct.isOverBy(h, 1));
    }

    @Test
    public void testUnlimitedPerPeer() {
        ConnThrottler ct = new ConnThrottler(0, 0, 60000, _timer);
        Hash h = new Hash(new byte[32]);
        for (int i = 0; i < 100; i++) {
            assertFalse(ct.shouldThrottle(h));
        }
    }

    @Test
    public void testUpdateLimits() {
        ConnThrottler ct = new ConnThrottler(2, 0, 60000, _timer);
        Hash h = new Hash(new byte[32]);
        ct.shouldThrottle(h);
        ct.shouldThrottle(h);
        assertTrue(ct.shouldThrottle(h));
        // Increase limit
        ct.updateLimits(5, 0);
        assertFalse(ct.shouldThrottle(h));
    }
}
