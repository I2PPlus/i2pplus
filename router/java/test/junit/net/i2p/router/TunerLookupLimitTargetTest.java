package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the starvation-first policy for next-hop lookup concurrency tuning:
 * queue/drop pressure with a responsive netdb raises the limit aggressively,
 * genuinely slow lookups without backlog lower it, mixed signals hold.
 *
 * @since 0.9.71+
 */
public class TunerLookupLimitTargetTest {

    private static final int MIN = 10, MAX = 80, STEP = 5;

    @Test
    public void testStarvationRaisesAggressively() {
        assertEquals(20, Tuner.lookupLimitTarget(10, MIN, MAX, STEP, false, true, false, true));
        assertEquals(20, Tuner.lookupLimitTarget(10, MIN, MAX, STEP, false, false, true, true));
    }

    @Test
    public void testSlowWithBacklogHolds() {
        // slowness plus backlog is ambiguous: don't lower into a starvation
        // spiral, and don't pile onto a possibly-overloaded netdb either
        assertEquals(10, Tuner.lookupLimitTarget(10, MIN, MAX, STEP, true, true, false, false));
    }

    @Test
    public void testGenuineSlownessLowers() {
        // decrease is floored at min; use current above min to see movement
        assertEquals(10, Tuner.lookupLimitTarget(15, MIN, MAX, STEP, true, false, false, false));
        assertEquals(75, Tuner.lookupLimitTarget(80, MIN, MAX, STEP, true, false, false, false));
    }

    @Test
    public void testFastAndQuietRaises() {
        assertEquals(15, Tuner.lookupLimitTarget(10, MIN, MAX, STEP, false, false, false, true));
    }

    @Test
    public void testNeutralHolds() {
        assertEquals(40, Tuner.lookupLimitTarget(40, MIN, MAX, STEP, false, false, false, false));
    }

    @Test
    public void testSlowWithDropsStillLowersWhenQueueEmpty() {
        // netdb slowness dominates when nothing is queued; drops without
        // backlog are stale pressure from an earlier window
        assertEquals(35, Tuner.lookupLimitTarget(40, MIN, MAX, STEP, true, false, true, false));
    }

    @Test
    public void testClampedToRange() {
        assertEquals(MAX, Tuner.lookupLimitTarget(79, MIN, MAX, STEP, false, true, false, true));
        assertEquals(MIN, Tuner.lookupLimitTarget(11, MIN, MAX, STEP, true, false, false, false));
    }
}
