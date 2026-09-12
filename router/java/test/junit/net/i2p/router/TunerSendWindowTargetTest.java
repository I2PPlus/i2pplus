package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure CWIN growth/shrink decision {@link Tuner#sendWindowTarget},
 * extracted from {@code MaxSendWindowParam#computeTarget}.
 *
 * <p>Covers priority ordering (memory-critical shrink beats growth; death
 * spiral beats idle shrink; failsafe/MTU shrink donors), the congestion-growth
 * branch that replaced the old collapse-shrink death spiral, the
 * stale-ratchet recovery that climbs a parked-below-floor window back up, the
 * clean-path pre-ramp that climbs a healthy, non-idle window toward the
 * ceiling, and the anti-ratchet idle floor that keeps idle shrink at the
 * stable default rather than the 64KB min.
 *
 * @since 0.9.72+
 */
public class TunerSendWindowTargetTest {

    private static final int MIN = 64 * 1024, MAX = 4 * 1024 * 1024, STEP = 64 * 1024;
    private static final int DEFAULT = 128 * 1024;
    private static final int IDLE_FLOOR = Math.max(DEFAULT, MIN);
    private static final int BASE = 512 * 1024;

    /** Neat defaults: everything healthy, no signal. */
    private static int target(int current, double observed) {
        return Tuner.sendWindowTarget(current, MIN, MAX, STEP, IDLE_FLOOR,
                                      observed, 0.0, 0.2,
                                      0.0, 0.0, observed,
                                      0.0, 0.0, 0.0);
    }

    // ----- memory critical -----

    @Test
    public void memoryPressureShrinksFast() {
        assertEquals(Math.max(MIN, BASE - STEP * 4),
                     Tuner.sendWindowTarget(BASE, MIN, MAX, STEP, IDLE_FLOOR,
                                            0.0, 0.0, 0.9,
                                            0.0, 0.0, 0.0,
                                            0.0, 0.0, 0.0));
    }

    // ----- death spiral -----

    @Test
    public void deathSpiralGrowsWindow() {
        assertEquals(Math.min(MAX, BASE + STEP * 4),
                     Tuner.sendWindowTarget(BASE, MIN, MAX, STEP, IDLE_FLOOR,
                                            0.0, 0.0, 0.2,
                                            0.0, 0.0, 0.0,
                                            1500.0, 0.0, 0.0));
    }

    @Test
    public void deathSpiralSuppressedByCpuPressure() {
        assertEquals(BASE, Tuner.sendWindowTarget(BASE, MIN, MAX, STEP, IDLE_FLOOR,
                                                  0.0, 15.0, 0.2,
                                                  0.0, 0.0, 0.0,
                                                  1500.0, 0.0, 0.0));
    }

    @Test
    public void deathSpiralSuppressedByMemPressure() {
        assertEquals(BASE, Tuner.sendWindowTarget(BASE, MIN, MAX, STEP, IDLE_FLOOR,
                                                  0.0, 0.0, 0.8,
                                                  0.0, 0.0, 0.0,
                                                  1500.0, 0.0, 0.0));
    }

    // ----- failsafe / MTU shrink -----

    @Test
    public void failsafeActiveShrinks() {
        assertEquals(Math.max(MIN, BASE - STEP * 2),
                     Tuner.sendWindowTarget(BASE, MIN, MAX, STEP, IDLE_FLOOR,
                                            0.0, 0.0, 0.2,
                                            0.0, 0.0, 0.0,
                                            0.0, 1.0, 0.0));
    }

    @Test
    public void mtuShrinkingShrinks() {
        assertEquals(Math.max(MIN, BASE - STEP * 2),
                     Tuner.sendWindowTarget(BASE, MIN, MAX, STEP, IDLE_FLOOR,
                                            0.0, 0.0, 0.2,
                                            0.0, 0.0, 0.0,
                                            0.0, 0.0, 1.0));
    }

    // ----- congestion growth -----

    @Test
    public void congestionGrowsWindow() {
        assertEquals(Math.min(MAX, BASE + STEP * 4),
                     Tuner.sendWindowTarget(BASE, MIN, MAX, STEP, IDLE_FLOOR,
                                            0.0, 0.0, 0.2,
                                            1.0, 0.0, 0.0,
                                            0.0, 0.0, 0.0));
    }

    @Test
    public void collapsedCwinDuringCongestionStillGrows() {
        // the old logic shrank on low usage even during collapse; that is a
        // death spiral — the window must grow to provide headroom
        assertEquals(Math.min(MAX, BASE + STEP * 4),
                     Tuner.sendWindowTarget(BASE, MIN, MAX, STEP, IDLE_FLOOR,
                                            10000.0, 0.0, 0.2,
                                            1.0, 0.0, 10000.0,
                                            0.0, 0.0, 0.0));
    }

    // ----- high usage growth -----

    @Test
    public void highUsageGrowsWindow() {
        double observed = (int) (BASE * 0.8);
        assertEquals(Math.min(MAX, BASE + STEP * 4),
                     Tuner.sendWindowTarget(BASE, MIN, MAX, STEP, IDLE_FLOOR,
                                            observed, 0.0, 0.2,
                                            0.0, 0.0, observed,
                                            0.0, 0.0, 0.0));
    }

    // ----- anti-ratchet idle shrink -----

    @Test
    public void idleShrinkStopsAtDefaultFloor() {
        // even though low usage would shrink, the anti-ratchet floor holds the
        // window at the stable default rather than the 64KB min
        double observed = (int) (DEFAULT * 0.2);
        assertEquals(IDLE_FLOOR,
                     Tuner.sendWindowTarget(IDLE_FLOOR, MIN, MAX, STEP, IDLE_FLOOR,
                                            observed, 0.0, 0.2,
                                            0.0, 0.0, observed,
                                            0.0, 0.0, 0.0));
    }

    @Test
    public void idleShrinkBelowFloorOnlyByFullSteps() {
        double observed = (int) (IDLE_FLOOR * 2 * 0.1); // low but window above floor
        int current = IDLE_FLOOR * 2;
        assertEquals(IDLE_FLOOR + STEP,
                     Tuner.sendWindowTarget(current, MIN, MAX, STEP, IDLE_FLOOR,
                                            observed, 0.0, 0.2,
                                            0.0, 0.0, observed,
                                            0.0, 0.0, 0.0));
    }

    @Test
    public void collapsedCwinDoesNotIdleShrink() {
        double observed = 10000.0; // collapsed window
        assertEquals(BASE,
                     Tuner.sendWindowTarget(BASE, MIN, MAX, STEP, IDLE_FLOOR,
                                            (int) (BASE * 0.1), 0.0, 0.2,
                                            0.0, 0.0, observed,
                                            0.0, 0.0, 0.0));
    }

    // ----- clean-path pre-ramp -----

    @Test
    public void cleanPathClimbsOnModerateUsage() {
        // a healthy, non-idle window climbs at 3x step toward the ceiling so a
        // fast pipe is never capped by the transport window
        assertEquals(BASE + STEP * 3, target(BASE, BASE * 0.5));
    }

    @Test
    public void noDataPreRamps() {
        // NaN observed = no usage stats yet; not provably low usage, so the
        // window pre-ramps at 3x step toward the ceiling while quiet
        assertEquals(BASE + STEP * 3, target(BASE, Double.NaN));
    }

    @Test
    public void cleanPathClimbsClampsAtMax() {
        assertEquals(MAX, target(MAX - STEP, (MAX - STEP) * 0.5));
    }

    @Test
    public void lowUsageShrinksNotClimbs() {
        // low usage with live stats gates the clean-path pre-ramp; the shrink
        // branch governs instead (one step down, floored at idleFloor)
        assertEquals(Math.max(IDLE_FLOOR, BASE - STEP),
                     target(BASE, BASE * 0.1));
    }

    // ----- stale-ratchet recovery -----

    @Test
    public void belowFloorRecoversToIdleFloor() {
        // the frozen 64KB ratchet (or any persisted value below the floor)
        // climbs back up even while idle — it is not a legitimate steady state
        assertEquals(IDLE_FLOOR,
                     Tuner.sendWindowTarget(MIN, MIN, MAX, STEP, IDLE_FLOOR,
                                            MIN * 0.2, 0.0, 0.2,
                                            0.0, 0.0, MIN * 0.2,
                                            0.0, 0.0, 0.0));
    }

    @Test
    public void belowFloorRecoverySuppressedByMemPressure() {
        // recovery needs CPU and memory headroom; under memory pressure the
        // window stays put (the memory-critical shrink handles it)
        assertEquals(MIN,
                     Tuner.sendWindowTarget(MIN, MIN, MAX, STEP, IDLE_FLOOR,
                                            MIN * 0.2, 0.0, 0.8,
                                            0.0, 0.0, MIN * 0.2,
                                            0.0, 0.0, 0.0));
    }

    @Test
    public void belowFloorRecoverySuppressedByCpuPressure() {
        assertEquals(MIN,
                     Tuner.sendWindowTarget(MIN, MIN, MAX, STEP, IDLE_FLOOR,
                                            MIN * 0.2, 15.0, 0.2,
                                            0.0, 0.0, MIN * 0.2,
                                            0.0, 0.0, 0.0));
    }
}
