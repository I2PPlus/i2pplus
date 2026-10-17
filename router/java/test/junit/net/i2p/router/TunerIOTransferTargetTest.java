package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure I/O transfer budget growth policy
 * {@link Tuner#computeIOTransferTarget}, extracted from
 * {@code I2PTunnelServerIOTransferParam#computeTarget}.
 *
 * <p>Observed is a utilization ratio (0.0 - 1.0+), as returned by
 * {@code getIOMaxPoolUtilization()}, not an absolute count.
 *
 * <p>Covers reactive growth under saturation (double per cycle), aggressive
 * growth under partial load, idle shrink, and clamping to [min,max].
 *
 * @since 0.9.71+
 */
public class TunerIOTransferTargetTest {

    private static final int MIN = 8;
    private static final int MAX = 256;
    private static final int CURRENT = 32;

    @Test
    public void idleShrinksTowardFloor() {
        // 2 busy of 32 -> ratio 0.0625, well under 0.2
        assertEquals(31, Tuner.computeIOTransferTarget(CURRENT, 2.0 / CURRENT, MIN, MAX));
    }

    @Test
    public void idleNeverBelowFloor() {
        assertEquals(MIN, Tuner.computeIOTransferTarget(MIN, 0, MIN, MAX));
    }

    @Test
    public void moderateLoadHolds() {
        // 50% utilization -> no change
        assertEquals(CURRENT, Tuner.computeIOTransferTarget(CURRENT, 0.5, MIN, MAX));
    }

    @Test
    public void highUtilizationGrowsAggressively() {
        // 0.7 < ratio <= 0.9 -> step = max(8, current/4) = 8
        int target = Tuner.computeIOTransferTarget(CURRENT, 0.75, MIN, MAX);
        assertTrue("expected growth, got " + target, target > CURRENT);
        assertEquals(CURRENT + 8, target);
    }

    @Test
    public void saturationDoublesBudget() {
        // ratio > 0.9 -> step = max(16, current) = current (true double)
        int target = Tuner.computeIOTransferTarget(CURRENT, 0.95, MIN, MAX);
        assertEquals(CURRENT * 2, target);
        // larger current doubles
        assertEquals(128, Tuner.computeIOTransferTarget(64, 0.95, MIN, MAX));
    }

    @Test
    public void largeCurrentTakesBigStep() {
        // step = max(16, 128) = 128 -> 256
        assertEquals(256, Tuner.computeIOTransferTarget(128, 1.0, MIN, MAX));
    }

    @Test
    public void clampsAtMax() {
        assertEquals(MAX, Tuner.computeIOTransferTarget(MAX, 1.0, MIN, MAX));
        assertEquals(MAX, Tuner.computeIOTransferTarget(MAX - 4, 1.0, MIN, MAX));
    }

    @Test
    public void clampsCurrentBelowMinUpToMin() {
        assertEquals(MIN, Tuner.computeIOTransferTarget(2, 0, MIN, MAX));
    }

    @Test
    public void nanAndNegativeLeaveValueUnchanged() {
        assertEquals(CURRENT, Tuner.computeIOTransferTarget(CURRENT, Double.NaN, MIN, MAX));
        assertEquals(CURRENT, Tuner.computeIOTransferTarget(CURRENT, -1, MIN, MAX));
    }

    @Test
    public void queueBacklogTriggersGrowth() {
        // queued transfers push utilization above 1.0 (active + queued over budget)
        int target = Tuner.computeIOTransferTarget(32, 40.0 / 32, MIN, MAX);
        assertTrue("backlog must grow budget, got " + target, target > 32);
    }
}
