package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the per-protocol latency baseline estimator.
 *
 * <p>The properties that matter are that the baseline only appears once there
 * is enough evidence for it, that it tracks the achievable floor rather than
 * the population mean, and that the clamp keeps it from ever producing a bar
 * stricter than the absolute rule it sits beside.
 *
 * @since 0.9.71+
 */
public class ProtocolRttBaselineTest {

    private static final int FLOOR = 100;
    private static final int MULTIPLE = 3;

    // ---------------- unknown below the sample floor ----------------

    @Test
    public void testEmptyIsUnknown() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        assertEquals(0, b.getSampleCount());
        assertFalse(b.isKnown());
        assertEquals(-1, b.getBaselineMs());
        assertEquals(-1, b.getClampedBaselineMs(FLOOR, MULTIPLE));
    }

    @Test
    public void testUnknownJustBelowThreshold() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 0; i < ProtocolRttBaseline.SAMPLES_FOR_BASELINE - 1; i++) {
            b.add(50);
        }
        assertFalse(b.isKnown());
        assertEquals("must not publish a baseline below the sample floor",
                     -1, b.getBaselineMs());
    }

    @Test
    public void testKnownExactlyAtThreshold() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 0; i < ProtocolRttBaseline.SAMPLES_FOR_BASELINE; i++) {
            b.add(50);
        }
        assertTrue(b.isKnown());
        assertEquals(50, b.getBaselineMs());
    }

    @Test
    public void testUnknownOneOverThreshold() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 0; i < ProtocolRttBaseline.SAMPLES_FOR_BASELINE + 1; i++) {
            b.add(50);
        }
        assertTrue(b.isKnown());
        assertEquals(50, b.getBaselineMs());
    }

    // ---------------- non-positive samples ----------------

    @Test
    public void testNonPositiveSamplesAreIgnored() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        b.add(0);
        b.add(-5);
        b.add(Integer.MIN_VALUE);
        assertEquals(0, b.getSampleCount());
        assertFalse(b.isKnown());
    }

    @Test
    public void testNonPositiveSamplesDoNotCountTowardThreshold() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 0; i < ProtocolRttBaseline.SAMPLES_FOR_BASELINE; i++) {
            b.add(60);
            b.add(0);
        }
        assertEquals("only real measurements count",
                     ProtocolRttBaseline.SAMPLES_FOR_BASELINE, b.getSampleCount());
        assertTrue(b.isKnown());
    }

    // ---------------- floor, not mean ----------------

    @Test
    public void testBaselineTracksTheFastestSamples() {
        // 50 fast samples among many slow ones: the baseline must reflect the
        // fast ones, so that degradation shows up rather than being absorbed
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 0; i < 50; i++) {
            b.add(40);
        }
        for (int i = 0; i < 100; i++) {
            b.add(900);
        }
        assertEquals("baseline must be the mean of the fastest 50",
                     40, b.getBaselineMs());
    }

    @Test
    public void testBaselineIgnoresTheSlowTail() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 0; i < 50; i++) {
            b.add(30);
        }
        for (int i = 0; i < 50; i++) {
            b.add(800);
        }
        // a mean over all 100 would be ~415; the floor must not move
        assertEquals(30, b.getBaselineMs());
    }

    @Test
    public void testBaselineIsMeanOfExactly50Fastest() {
        // 60 samples spanning a range: the mean of the fastest 50 excludes the
        // slowest 10, so it is strictly below the mean of all 60
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 1; i <= 60; i++) {
            b.add(i);
        }
        // fastest 50 are 1..50, mean = 25.5 -> 25 by integer division
        assertEquals(25, b.getBaselineMs());
    }

    // ---------------- ring behaviour ----------------

    @Test
    public void testRingEvictsOldestBeyondCapacity() {
        ProtocolRttBaseline b = new ProtocolRttBaseline(ProtocolRttBaseline.MAX_SAMPLES);
        // fill with slow samples, then push a full ring of fast ones
        for (int i = 0; i < ProtocolRttBaseline.MAX_SAMPLES; i++) {
            b.add(900);
        }
        for (int i = 0; i < ProtocolRttBaseline.MAX_SAMPLES; i++) {
            b.add(20);
        }
        assertEquals("count must be capped at the ring size",
                     ProtocolRttBaseline.MAX_SAMPLES, b.getSampleCount());
        assertEquals("the old slow samples must have rotated out",
                     20, b.getBaselineMs());
    }

    @Test
    public void testCapacityBelowThresholdIsRejected() {
        try {
            new ProtocolRttBaseline(ProtocolRttBaseline.SAMPLES_FOR_BASELINE - 1);
            fail("a ring too small to ever reach the threshold must be rejected");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    // ---------------- the clamp ----------------

    @Test
    public void testClampRaisesAnAbsurdlyLowBaseline() {
        // 50 one-millisecond samples would otherwise put the bar at 3ms, far
        // stricter than the absolute 100ms floor it replaces
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 0; i < 50; i++) {
            b.add(1);
        }
        assertEquals(1, b.getBaselineMs());
        assertEquals("clamp must lift the baseline to floor/multiple",
                     FLOOR / MULTIPLE, b.getClampedBaselineMs(FLOOR, MULTIPLE));
    }

    @Test
    public void testClampLeavesARealisticBaselineAlone() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 0; i < 50; i++) {
            b.add(200);
        }
        assertEquals(200, b.getBaselineMs());
        assertEquals(200, b.getClampedBaselineMs(FLOOR, MULTIPLE));
    }

    @Test
    public void testClampIsANoOpForNonPositiveArguments() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 0; i < 50; i++) {
            b.add(200);
        }
        assertEquals(200, b.getClampedBaselineMs(0, MULTIPLE));
        assertEquals(200, b.getClampedBaselineMs(FLOOR, 0));
    }

    @Test
    public void testClampStaysUnknownWhenBaselineIsUnknown() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        assertEquals(-1, b.getClampedBaselineMs(FLOOR, MULTIPLE));
    }

    // ---------------- qualification ----------------

    @Test
    public void testWithinBaselineAcceptsUnderTheMultiple() {
        assertTrue(ProtocolRttBaseline.withinBaseline(200, 200, 3));
        assertTrue(ProtocolRttBaseline.withinBaseline(600, 200, 3));
    }

    @Test
    public void testWithinBaselineRejectsOverTheMultiple() {
        assertFalse(ProtocolRttBaseline.withinBaseline(601, 200, 3));
    }

    @Test
    public void testWithinBaselineRejectsWhenBaselineUnknown() {
        // the guard that matters most: no baseline must never read as "fine"
        assertFalse(ProtocolRttBaseline.withinBaseline(5, -1, 3));
        assertFalse(ProtocolRttBaseline.withinBaseline(5, 0, 3));
    }

    @Test
    public void testWithinBaselineRejectsBadArguments() {
        assertFalse(ProtocolRttBaseline.withinBaseline(0, 200, 3));
        assertFalse(ProtocolRttBaseline.withinBaseline(200, 200, 0));
        assertFalse(ProtocolRttBaseline.withinBaseline(200, 200, -1));
    }

    // ---------------- ties and identical samples ----------------

    @Test
    public void testIdenticalSamplesGiveThatExactBaseline() {
        ProtocolRttBaseline b = new ProtocolRttBaseline();
        for (int i = 0; i < 60; i++) {
            b.add(77);
        }
        assertEquals(77, b.getBaselineMs());
    }

    @Test
    public void testBaselineMsPureHelperBoundaries() {
        int[] empty = new int[0];
        assertEquals(-1, ProtocolRttBaseline.baselineMs(empty, 0));
        assertEquals(-1, ProtocolRttBaseline.baselineMs(null, 10));
        int[] short50 = new int[64];
        java.util.Arrays.fill(short50, 40);
        assertEquals("count below the threshold yields no baseline",
                     -1, ProtocolRttBaseline.baselineMs(short50, 49));
        assertEquals(40, ProtocolRttBaseline.baselineMs(short50, 50));
    }
}