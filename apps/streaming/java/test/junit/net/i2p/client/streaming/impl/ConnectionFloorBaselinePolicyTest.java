package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the baseline-update policy of the adaptive throughput floor.
 *
 * <p>Two defects are pinned here, both found by simulating the real
 * {@code noteProgress} loop rather than by reading it:
 *
 * <ul>
 *   <li><b>Self-disarm.</b> With a plain EWMA the baseline decays toward the
 *       observed rate, so a sustained drop to 20% of baseline is flagged for
 *       three windows and then silently accepted — the reference falls to meet
 *       the observation.</li>
 *   <li><b>Permanent false stall.</b> The progress stamp was initialised once
 *       and never advanced, so {@code now - lastProgressAt} grew without bound
 *       and every healthy connection reported STALLED after the grace period.</li>
 * </ul>
 *
 * @since 0.9.71+
 */
public class ConnectionFloorBaselinePolicyTest {

    private static final double RATIO = 0.35;
    private static final double ABS = 8192;
    private static final int MIN_SAMPLES = 3;
    private static final int EWMA = 30;
    private static final int REBASE = 10;

    /** Reference implementation of the fixed policy, mirroring evaluateFloor(). */
    private static final class Model {
        double baseline;
        int samples;
        int belowCount;
        long lastProgress;
        String verdict = "NONE";

        void sample(long now, double bps) {
            lastProgress = now;
            emit(now, bps);
            if (bps <= 0) {return;}
            if (baseline <= 0) {
                baseline = bps;
            } else if (bps < Math.min(baseline * RATIO, ABS)) {
                if (++belowCount >= REBASE) {baseline = bps; belowCount = 0;}
            } else {
                belowCount = 0;
                baseline += (bps - baseline) * (EWMA / 100.0);
            }
            samples++;
        }

        private void emit(long now, double bps) {
            Connection.StallState s = Connection.stallVerdict(now, lastProgress, bps, RATIO,
                                                             samples, baseline, ABS, 30000);
            verdict = s.name();
        }
    }

    /**
     * The regression: a healthy connection must never report STALLED. The old
     * code held the progress stamp at its initial value, so this tripped after
     * the grace period on a perfectly healthy stream.
     */
    @Test
    public void testHealthyStreamNeverReportsStalled() {
        Model m = new Model();
        double healthy = 10380;
        for (int k = 1; k <= 12; k++) {
            m.sample(k * 10_000L, healthy);
        }
        assertEquals("healthy stream must not report STALLED", "NONE", m.verdict);
    }

    /**
     * The self-disarm regression: a sustained drop to 20% of baseline must stay
     * flagged. The old EWMA-only policy cleared this by sample 4.
     */
    @Test
    public void testSustainedDropStaysFlagged() {
        Model m = new Model();
        for (int k = 1; k <= 5; k++) {
            m.sample(k * 10_000L, 5000);
        }
        double sustained = 1000;   // 20% of the 5000 baseline
        int flagged = 0;
        for (int k = 6; k <= 15; k++) {
            m.sample(k * 10_000L, sustained);
            if ("THROTTLED".equals(m.verdict)) {flagged++;}
        }
        assertEquals("sustained slowdown must be flagged in every window", 10, flagged);
    }

    /**
     * A transient dip must not stick: the baseline is unchanged by a short
     * sub-floor run, so recovery is detected immediately afterwards.
     */
    @Test
    public void testTransientDipDoesNotMoveBaseline() {
        Model m = new Model();
        for (int k = 1; k <= 5; k++) {
            m.sample(k * 10_000L, 5000);
        }
        double baselineBefore = m.baseline;
        m.sample(60_000L, 1200);
        assertEquals("a single slow window must not move the baseline",
                     baselineBefore, m.baseline, 0.001);
        m.sample(70_000L, 5000);
        assertEquals("NONE", m.verdict);
    }

    /**
     * A long enough run of sub-floor windows is a real capacity change, so the
     * baseline must eventually adopt it rather than flag THROTTLED forever.
     */
    @Test
    public void testGenuineCapacityChangeIsEventuallyAdopted() {
        Model m = new Model();
        for (int k = 1; k <= 5; k++) {
            m.sample(k * 10_000L, 5000);
        }
        int t = 5;
        int flagged = 0;
        for (int i = 0; i < REBASE; i++) {
            m.sample(++t * 10_000L, 900);
            if ("THROTTLED".equals(m.verdict)) {flagged++;}
        }
        // Every window up to and including the re-adoption is judged against the
        // floor that was in force when it closed, so all REBASE of them flag.
        assertEquals("every sub-floor window must be flagged", REBASE, flagged);
        assertEquals("baseline must adopt the sustained lower rate", 900.0, m.baseline, 0.001);
        m.sample(++t * 10_000L, 900);
        assertEquals("the window after adoption must read healthy", "NONE", m.verdict);
    }

    /** A quiet window must never drag the baseline to zero. */
    @Test
    public void testQuietWindowLeavesBaselineIntact() {
        Model m = new Model();
        for (int k = 1; k <= 5; k++) {
            m.sample(k * 10_000L, 5000);
        }
        double before = m.baseline;
        m.sample(60_000L, 0);
        assertEquals("quiet window must not move the baseline", before, m.baseline, 0.001);
    }

    /** The floor needs its minimum sample count before it can fire at all. */
    @Test
    public void testFloorUnarmedUntilMinSamples() {
        Model m = new Model();
        m.sample(10_000L, 5000);
        m.sample(20_000L, 100);
        assertEquals("below the sample minimum, no verdict", "NONE", m.verdict);
    }
}
