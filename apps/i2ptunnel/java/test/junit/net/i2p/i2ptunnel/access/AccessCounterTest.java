package net.i2p.i2ptunnel.access;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Tests the sliding-window threshold breach detection in AccessCounter, and the
 * bounded retention that replaced the unbounded list.
 *
 * @since 0.9.70+
 */
public class AccessCounterTest {

    /**
     * The retention the counter falls back to for a small rule.
     */
    private static final int MIN_RETAINED = 64;

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

    /**
     * A counter for a small rule keeps the minimum retention, and one for a rule
     * with an enormous count (ALLOW) keeps a bounded retention rather than
     * allocating for the count.
     */
    @Test
    public void testRetentionSizedFromRule() {
        assertEquals(MIN_RETAINED, AccessCounter.retainedFor(new Threshold(5, 10)));
        assertEquals(1, AccessCounter.retainedFor(Threshold.DENY));
        int allow = AccessCounter.retainedFor(Threshold.ALLOW);
        assertTrue(allow >= MIN_RETAINED);
        assertTrue(allow < 100000);
        assertTrue(AccessCounter.retainedFor(new Threshold(500, 60)) >= 500);
    }

    /**
     * A deny rule only needs to know whether anything was recorded, so a flooded
     * destination under a deny rule keeps one timestamp, not a window of them.
     */
    @Test
    public void testDenyRuleKeepsOneTimestamp() {
        AccessCounter c = new AccessCounter(Threshold.DENY);
        for (int i = 0; i < 1000; i++) {
            c.recordAccess(i);
        }
        assertEquals(1, c.retained());
        assertTrue(c.isBreached(Threshold.DENY, 999));
        assertTrue(c.purge(1000));
        assertFalse(c.isBreached(Threshold.DENY, 1000));
    }

    /**
     * A flooded destination must not grow the history without limit: the retention
     * stays at the counter's capacity and the breach is still reported.
     */
    @Test
    public void testFloodKeepsRetentionBounded() {
        Threshold threshold = new Threshold(5, 10);
        AccessCounter c = new AccessCounter(threshold);
        int capacity = AccessCounter.retainedFor(threshold);
        for (int i = 0; i < 1000000; i++) {
            c.recordAccess(i);
        }
        assertEquals(capacity, c.retained());
        assertTrue(c.isBreached(threshold, 999999));
    }

    /**
     * Dropping the oldest attempts when the retention is full cannot hide a
     * breach, because the breach test only looks at the newest attempts.
     */
    @Test
    public void testOverflowStillBreached() {
        Threshold threshold = new Threshold(3, 1);
        AccessCounter c = new AccessCounter(threshold);
        for (long i = 0; i < 1000; i++) {
            c.recordAccess(i * 10);
        }
        assertTrue(c.isBreached(threshold, 9990));
    }

    /**
     * Attempts that have aged out of the window do not count, even when they were
     * close together - the window ends at {@code now}.
     */
    @Test
    public void testAgedOutBurstNotBreached() {
        AccessCounter c = new AccessCounter();
        c.recordAccess(85000);
        c.recordAccess(90000);
        assertFalse(c.isBreached(new Threshold(2, 10), 100000));
        assertTrue(c.isBreached(new Threshold(2, 10), 95000));
    }

    /**
     * The bounded counter must answer exactly what the old unbounded scan
     * answered, for every rule the retention can hold.
     */
    @Test
    public void testEquivalentToReferenceScan() {
        Random rnd = new Random(42);
        for (int round = 0; round < 200; round++) {
            List<Long> history = new ArrayList<>();
            Threshold threshold = new Threshold(1 + rnd.nextInt(8), 1 + rnd.nextInt(5));
            AccessCounter c = new AccessCounter(threshold);
            long t = rnd.nextInt(1000);
            int n = rnd.nextInt(60);
            for (int i = 0; i < n; i++) {
                t += rnd.nextInt(3000);
                history.add(t);
                c.recordAccess(t);
            }
            long now = t + rnd.nextInt(20000);
            assertEquals(referenceBreached(history, threshold, now), c.isBreached(threshold, now));
        }
    }

    /**
     * The pre-2026 implementation: keep every timestamp and scan every window.
     *
     * @param history attempt timestamps, oldest first
     * @param threshold rule to test
     * @param now time the window ends at
     * @return true if the rule is breached
     */
    private static boolean referenceBreached(List<Long> history, Threshold threshold, long now) {
        int count = threshold.getConnections();
        if (count == 0)
            return !history.isEmpty();
        if (history.size() < count)
            return false;
        long window = (long) threshold.getSeconds() * 1000L;
        long ignoreOlder = now - window;
        for (int i = 0; i <= history.size() - count; i++) {
            long start = history.get(i);
            if (start < ignoreOlder)
                continue;
            if (history.get(i + count - 1) <= start + window)
                return true;
        }
        return false;
    }
}
