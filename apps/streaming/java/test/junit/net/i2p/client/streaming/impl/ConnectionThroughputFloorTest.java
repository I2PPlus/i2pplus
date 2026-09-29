package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the adaptive throughput-floor decision used to detect a connection that
 * is still delivering bytes but far below its own established rate.
 *
 * <p>{@link Connection#stallVerdict(long, long, double, double, int, long, long, long)}
 * is pure so the arming rules are testable without a router. The thresholds
 * exist because a fixed bytes-per-second floor is wrong in both directions: it
 * never fires on a slow-but-healthy link and thrashes on a fast one.
 *
 * <p>The failure mode these tests guard against is a silently inert detector —
 * a connection that starts slow never builds a baseline, so a fixed floor would
 * never trip and a self-referential floor would arm against its own low start.
 *
 * @since 0.9.71+
 */
public class ConnectionThroughputFloorTest {

    /** Not enough history to judge: a slow start must never be a stall. */
    @Test
    public void testUnarmedBelowMinSamples() {
        // No baseline at all, but bytes are flowing and progress is recent:
        // nothing to compare against, so no verdict.
        assertEquals(Connection.StallState.NONE,
                     Connection.stallVerdict(30000, 25000, 100.0, 0.35, 99, 0, 8192, 30000));
    }

    /**
     * A cold-pool connection starts slow. If the floor armed off its own first
     * observations it would judge the slow start against itself and never
     * fire, which is the inert-detector failure this guards against.
     */
    @Test
    public void testSlowStartNeverSelfArms() {
        for (int samples = 0; samples < 3; samples++) {
            assertEquals("sample " + samples + " must stay unarmed",
                         Connection.StallState.NONE,
                         Connection.stallVerdict(30000, 25000, 100.0, 0.35,
                                                 3, samples > 0 ? 100.0 : 0.0, 8192, 30000));
        }
    }

    /** No bytes at all for longer than the grace is a hard stall. */
    @Test
    public void testHardStallWhenNoBytes() {
        assertEquals(Connection.StallState.STALLED,
                     Connection.stallVerdict(50000, 10000, 0.0, 0.35, 0, 0, 8192, 30000));
    }

    /**
     * A hard stall is reported even with no baseline at all: no bytes is
     * unambiguous on its own, whereas a throttle verdict needs a reference.
     */
    @Test
    public void testHardStallDoesNotNeedBaseline() {
        assertEquals(Connection.StallState.STALLED,
                     Connection.stallVerdict(40000, 5000, 0.0, 0.35, 0, 0, 8192, 30000));
    }

    /**
     * A window that saw no bytes but is inside the stall grace is neither a
     * stall nor a throttle. Latency-bound streams produce quiet windows
     * routinely, so scoring them as throttled would fault a healthy path.
     */
    @Test
    public void testQuietInsideGraceIsNotStall() {
        assertEquals(Connection.StallState.NONE,
                     Connection.stallVerdict(30000, 25000, 0.0, 0.35, 99, 5000, 8192, 30000));
    }

    /** The grace boundary is inclusive at the threshold. */
    @Test
    public void testStallGraceBoundary() {
        assertEquals(Connection.StallState.NONE,
                     Connection.stallVerdict(30000, 1000, 0.0, 0.35, 0, 0, 8192, 30000));
        assertEquals(Connection.StallState.STALLED,
                     Connection.stallVerdict(31000, 1000, 0.0, 0.35, 0, 0, 8192, 30000));
    }

    /**
     * The case that motivated this: bytes keep arriving, but far below the
     * established baseline. A no-progress detector would never see this.
     */
    @Test
    public void testThrottledWhenBelowAdaptiveFloor() {
        // 2000 B/s against a 5000 B/s baseline = 40%, above the 35% ratio.
        assertEquals(Connection.StallState.NONE,
                     Connection.stallVerdict(30000, 25000, 2000.0, 0.35, 99, 5000, 8192, 30000));
        // 1000 B/s against 5000 B/s baseline = 20%, below the floor.
        assertEquals(Connection.StallState.THROTTLED,
                     Connection.stallVerdict(30000, 25000, 1000.0, 0.35, 99, 5000, 8192, 30000));
    }

    /** At or above the floor, however modest, is healthy. */
    @Test
    public void testAtOrAboveFloorIsHealthy() {
        assertEquals(Connection.StallState.NONE,
                     Connection.stallVerdict(30000, 25000, 1750.0, 0.35, 99, 5000, 8192, 30000));
        assertEquals(Connection.StallState.NONE,
                     Connection.stallVerdict(30000, 25000, 5000.0, 0.35, 99, 5000, 8192, 30000));
    }

    /**
     * The absolute floor caps the proportional floor, so it only ever relaxes
     * the bar for a high baseline. A small baseline keeps its (tiny)
     * proportional floor.
     */
    @Test
    public void testAbsoluteFloorCapsProportionalFloor() {
        // 4 B/s against a 10 B/s baseline: proportional floor 3.5, below the
        // absolute floor of 8, so the proportional value applies and 4 clears it.
        assertEquals(Connection.StallState.NONE,
                     Connection.stallVerdict(30000, 25000, 4.0, 0.35, 99, 10, 8, 30000));
        // 3 B/s against a 10 B/s baseline falls just under the 3.5 floor.
        assertEquals(Connection.StallState.THROTTLED,
                     Connection.stallVerdict(30000, 25000, 3.0, 0.35, 99, 10, 8, 30000));
    }

    /** A zero or negative baseline cannot produce a proportional floor. */
    @Test
    public void testNoBaselineNeverThrottles() {
        assertEquals(Connection.StallState.NONE,
                     Connection.stallVerdict(30000, 25000, 100.0, 0.35, 99, 0, 8192, 30000));
        assertEquals(Connection.StallState.NONE,
                     Connection.stallVerdict(30000, 25000, 100.0, 0.35, 99, -5, 8192, 30000));
    }

    /**
     * A high baseline with a strict ratio would demand more than the absolute
     * floor allows, so the cap applies and the bar is the absolute one.
     */
    @Test
    public void testProportionalFloorCappedByAbsoluteFloor() {
        // 20000 B/s baseline at ratio 1.0 would demand 20000 B/s, capped to 8192.
        assertEquals(Connection.StallState.NONE,
                     Connection.stallVerdict(30000, 25000, 8192.0, 1.0, 99, 20000, 8192, 30000));
        assertEquals(Connection.StallState.THROTTLED,
                     Connection.stallVerdict(30000, 25000, 8000.0, 1.0, 99, 20000, 8192, 30000));
    }

    /**
     * The baseline must not collapse after a quiet window: if it did, the
     * next slow-but-healthy sample would trivially clear the floor and the
     * detector would be disarmed by the very event it is meant to catch. The
     * guard is in the caller, which skips the update for a zero sample; this
     * pins that a zero sample measures as 0 so the skip is testable.
     */
    @Test
    public void testQuietWindowMeasuresAsZeroSoCallerCanSkipIt() {
        assertEquals(0.0, Connection.sampleRateBps(0, 10000), 0.0);
        // If the caller did feed that 0 in, the baseline would decay hard —
        // which is exactly why noteProgress() skips it.
        assertEquals(3500.0, Connection.updatedBaseline(5000, 0.0, 30), 0.001);
    }

    /** EWMA moves toward a new sample but not all the way in one step. */
    @Test
    public void testBaselineMovesTowardNewSample() {
        double first = Connection.updatedBaseline(0, 5000, 30);
        assertEquals("first sample seeds the baseline", 5000.0, first, 0.001);
        double second = Connection.updatedBaseline(first, 1000, 30);
        assertTrue("must fall toward the lower sample", second < first);
        assertTrue("must not overshoot the sample", second > 1000.0);
        assertEquals(5000 + (1000 - 5000) * 0.30, second, 0.001);
    }

    /** A rising sample is tracked too, so capacity recovery is not ignored. */
    @Test
    public void testBaselineRisesOnFasterSample() {
        double second = Connection.updatedBaseline(5000, 9000, 30);
        assertTrue(second > 5000);
        assertTrue(second < 9000);
    }

    /** Degenerate weights behave predictably rather than dividing by zero. */
    @Test
    public void testDegenerateEwmaWeights() {
        // No baseline yet: the first sample seeds it whatever the weight.
        assertEquals(1234.0, Connection.updatedBaseline(0, 1234, 0), 0.001);
        // Zero weight freezes the existing baseline.
        assertEquals(5000.0, Connection.updatedBaseline(5000, 1234, 0), 0.001);
        // Full weight adopts the sample outright.
        assertEquals(1234.0, Connection.updatedBaseline(5000, 1234, 100), 0.001);
    }

    /** A window too short to measure reports 0 rather than a wild rate. */
    @Test
    public void testSampleRateGuards() {
        assertEquals(0.0, Connection.sampleRateBps(0, 10000), 0.0);
        assertEquals(0.0, Connection.sampleRateBps(1000, 0), 0.0);
        assertEquals(0.0, Connection.sampleRateBps(1000, -5), 0.0);
        // 1000 bytes in 10s is 100 B/s, not 100 KB/s.
        assertEquals(100.0, Connection.sampleRateBps(1000, 10000), 0.001);
    }
}
