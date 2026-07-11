package net.i2p.i2ptunnel.access;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the sliding-window threshold breach detection in AccessCounter.
 *
 * @since 0.9.70+
 */
public class AccessCounterTest {

    @Test
    public void testNoAccessesNeverBreached() {
        AccessCounter c = new AccessCounter();
        assertFalse(c.isBreached(new Threshold(5, 10), 1000));
    }

    @Test
    public void testZeroThresholdBreachedOnAnyAccess() {
        AccessCounter c = new AccessCounter();
        c.recordAccess(1000);
        assertTrue(c.isBreached(new Threshold(0, 10), 1010));
    }

    @Test
    public void testZeroThresholdNotBreachedOnNoAccess() {
        AccessCounter c = new AccessCounter();
        assertFalse(c.isBreached(new Threshold(0, 10), 1000));
    }

    @Test
    public void testUnderThresholdNotBreached() {
        AccessCounter c = new AccessCounter();
        c.recordAccess(1000);
        c.recordAccess(2000);
        assertFalse(c.isBreached(new Threshold(5, 10), 3000));
    }

    @Test
    public void testExactThresholdBreached() {
        AccessCounter c = new AccessCounter();
        long now = 10000;
        for (int i = 0; i < 3; i++) {
            c.recordAccess(now + i * 1000);
        }
        assertTrue(c.isBreached(new Threshold(3, 10), now + 5000));
    }

    @Test
    public void testBurstWithinWindowBreached() {
        AccessCounter c = new AccessCounter();
        long now = 50000;
        // 5 rapid accesses
        for (int i = 0; i < 5; i++) {
            c.recordAccess(now + i * 100);
        }
        assertTrue(c.isBreached(new Threshold(5, 10), now + 5000));
    }

    @Test
    public void testSpreadBeyondWindowNotBreached() {
        AccessCounter c = new AccessCounter();
        long now = 10000;
        // 5 accesses spread over 15 seconds, threshold is 5 in 10 seconds
        for (int i = 0; i < 5; i++) {
            c.recordAccess(now + i * 4000);
        }
        // Each pair is 4s apart, but span is 16s > 10s, not breached
        assertFalse(c.isBreached(new Threshold(5, 10), now + 20000));
    }

    @Test
    public void testPurgeRemovesOldEntries() {
        AccessCounter c = new AccessCounter();
        c.recordAccess(1000);
        c.recordAccess(2000);
        c.recordAccess(3000);
        assertFalse(c.purge(1500));
        assertFalse(c.purge(2500));
        assertTrue(c.purge(3500));
    }

    @Test
    public void testPurgeThenNoBreach() {
        AccessCounter c = new AccessCounter();
        long now = 100000;
        for (int i = 0; i < 5; i++) {
            c.recordAccess(now + i * 1000);
        }
        // Purge old, leaving 1
        c.purge(now + 4001);
        // Only the last access remains, so 5 in 10s should not breach
        assertFalse(c.isBreached(new Threshold(5, 10), now + 10000));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testThresholdSecondsMustBePositive() {
        new Threshold(5, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testThresholdConnectionsCannotBeNegative() {
        new Threshold(-1, 10);
    }

    @Test
    public void testAllowThreshold() {
        Threshold t = Threshold.ALLOW;
        assertTrue(t.getConnections() > 1000000);
    }

    @Test
    public void testDenyThreshold() {
        Threshold t = Threshold.DENY;
        assertEquals(0, t.getConnections());
    }
}
