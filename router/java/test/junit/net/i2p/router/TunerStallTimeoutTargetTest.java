package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure I/O stall window policy {@link Tuner#nextStallTimeoutMs},
 * extracted from {@code I2PTunnelServerIOStallTimeoutParam#computeTarget}.
 *
 * <p>The regression this pins: the window defaulted to 5s, which is shorter
 * than any pause in a bulk transfer, and the old policy tightened it whenever
 * a cycle saw no stalls. That drove a limit cycle that aborted 45MB downloads
 * partway through. The window must now relax to the floor and never below it.
 *
 * @since 0.9.71+
 */
public class TunerStallTimeoutTargetTest {

    private static final int MIN = Tuner.STALL_TIMEOUT_FLOOR_MS;
    private static final int MAX = 300_000;

    @Test
    public void floorIsSixtySeconds() {
        assertEquals(60_000, Tuner.STALL_TIMEOUT_FLOOR_MS);
    }

    @Test
    public void frequentStallsLoosenTheWindow() {
        assertEquals(MIN + 10_000, Tuner.nextStallTimeoutMs(MIN, 3.0, MIN, MAX));
    }

    @Test
    public void quietCycleSettlesBackToFloorNotBelow() {
        // Previously this subtracted 5s from an already-at-floor value, which
        // pinned the window at the 5s minimum and killed bulk transfers.
        assertEquals(MIN, Tuner.nextStallTimeoutMs(MIN, 0.0, MIN, MAX));
    }

    @Test
    public void quietCycleDecaysOneStepTowardFloor() {
        assertEquals(MIN + 20_000, Tuner.nextStallTimeoutMs(MIN + 30_000, 0.0, MIN, MAX));
    }

    @Test
    public void repeatedQuietCyclesReachTheFloor() {
        int window = MIN + 30_000;
        for (int cycle = 0; cycle < 3; cycle++) {
            window = Tuner.nextStallTimeoutMs(window, 0.0, MIN, MAX);
        }
        assertEquals(MIN, window);
    }

    @Test
    public void singleStallHoldsSteady() {
        // observed == 1 is the dead zone: not enough signal to move either way.
        assertEquals(MIN + 20_000, Tuner.nextStallTimeoutMs(MIN + 20_000, 1.0, MIN, MAX));
    }

    @Test
    public void neverExceedsCeiling() {
        assertEquals(MAX, Tuner.nextStallTimeoutMs(MAX, 99.0, MIN, MAX));
    }

    @Test
    public void neverGoesBelowFloorFromAnyInput() {
        // A current value below the floor (a stale value from a prior build or
        // an override) must be pulled up, never honoured.
        assertEquals(MIN, Tuner.nextStallTimeoutMs(5_000, 0.0, MIN, MAX));
        assertEquals(MIN, Tuner.nextStallTimeoutMs(1, 0.0, MIN, MAX));
    }

    @Test
    public void noLimitCycleBelowFloor() {
        // The exact oscillation that broke the transfer: quiet -> stall ->
        // quiet, with the window never once dropping under the floor.
        int window = MIN;
        int previous = window;
        double[] observed = { 0.0, 3.0, 0.0, 3.0, 0.0, 3.0 };
        for (double obs : observed) {
            window = Tuner.nextStallTimeoutMs(window, obs, MIN, MAX);
            assertTrue("window dipped below floor: " + window, window >= MIN);
            assertTrue("window exceeded ceiling: " + window, window <= MAX);
            previous = window;
        }
        assertTrue(previous >= MIN);
    }

    @Test
    public void negativeFloorIsTreatedAsZero() {
        // A negative floor is clamped to 0, so decay bottoms out at 0 and is
        // then re-raised by the final clamp to max(floor, ...). The Tuner never
        // passes one; this pins that the helper does not throw or invert.
        assertEquals(0, Tuner.nextStallTimeoutMs(10_000, 0.0, -5, MAX));
    }
}
