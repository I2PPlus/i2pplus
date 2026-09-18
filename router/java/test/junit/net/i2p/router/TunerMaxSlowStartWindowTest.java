package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure slow-start-window growth/shrink decision
 * {@link Tuner#maxSlowStartWindow}, extracted from
 * {@code MaxSlowStartWindowParam#computeTarget}.
 *
 * <p>Covers the recovery floor (below factoryDefault/2 always increases),
 * the congestion/drop shrink, the growth from recovery floor toward
 * factory default, and the RTT-based decrease above factory default.
 * The dead zone (factoryDefault to factoryDefault * 2) was removed
 * to allow faster convergence to the optimal window.
 *
 * @since 0.9.71+
 */
public class TunerMaxSlowStartWindowTest {

    private static final int MIN = 128;
    private static final int MAX = 8192;
    private static final int STEP = 128;
    private static final int DEFAULT = 2048;
    private static final int RECOVERY_FLOOR = DEFAULT / 2; // 1024
    private static final int BASE = 256;

    /** Neat defaults: everything healthy, no signal. */
    private static int target(int current, double observed) {
        return Tuner.maxSlowStartWindow(current, MIN, MAX, STEP, DEFAULT,
                                           observed, Double.NaN, Double.NaN);
    }

    /** With congestion signal. */
    private static int targetCongested(int current, double observed, double failLifetime) {
        return Tuner.maxSlowStartWindow(current, MIN, MAX, STEP, DEFAULT,
                                           observed, failLifetime, Double.NaN);
    }

    /** With drop signal. */
    private static int targetDropping(int current, double observed, double dupSize) {
        return Tuner.maxSlowStartWindow(current, MIN, MAX, STEP, DEFAULT,
                                           observed, Double.NaN, dupSize);
    }

    // ----- recovery floor tests -----

    @Test
    public void belowRecoveryFloorIncreases() {
        // current < 1024 → increase toward 2048
        assertEquals(Math.min(DEFAULT, BASE + STEP), target(BASE, 500));
    }

    @Test
    public void belowRecoveryFloorStopsAtFactoryDefault() {
        // at 1500 (below 2048 but above 1024) → increase toward 2048
        assertEquals(2048, target(1500, 500));
    }

    @Test
    public void atRecoveryFloorIncreases() {
        // at 1024 → increase toward 2048
        assertEquals(1152, target(RECOVERY_FLOOR, 500));
    }

    // ----- dead zone removed tests -----

    @Test
    public void atFactoryDefaultIncreases() {
        // current == 2048 → increase toward MAX (dead zone removed)
        assertEquals(2176, target(DEFAULT, 500));
    }

    @Test
    public void aboveFactoryDefaultIncreases() {
        // current == 3000 (above factory default) → increase toward MAX
        assertTrue(target(3000, 500) > 3000);
    }

    @Test
    public void atMaxStays() {
        // current == MAX → stays at MAX
        assertEquals(MAX, target(MAX, 500));
    }

    // ----- congestion and drop shrink tests -----

    @Test
    public void congestionShrinksFromDefault() {
        // congested at 2048 → shrink toward recovery floor
        assertEquals(RECOVERY_FLOOR, targetCongested(DEFAULT, 500, 9000));
    }

    @Test
    public void congestionShrinksBelowDefault() {
        // congested at 3000 → shrink toward recovery floor
        assertEquals(Math.max(RECOVERY_FLOOR, 3000 - STEP),
                     targetCongested(3000, 500, 9000));
    }

    @Test
    public void congestionShrinksAtMin() {
        // congested at 128 (min) → stays at min
        assertEquals(MIN, targetCongested(MIN, 500, 9000));
    }

    @Test
    public void droppingShrinksFromDefault() {
        // dropping at 2048 → shrink toward recovery floor
        assertEquals(RECOVERY_FLOOR, targetDropping(DEFAULT, 500, 600));
    }

    @Test
    public void droppingShrinksAboveDefault() {
        // dropping at 3000 → shrink toward recovery floor
        assertEquals(Math.max(RECOVERY_FLOOR, 3000 - STEP),
                     targetDropping(3000, 500, 600));
    }

    @Test
    public void congestionAndDroppingTogetherShrink() {
        // both congested and dropping at 2048 → shrink toward recovery floor
        assertEquals(RECOVERY_FLOOR, targetCongested(DEFAULT, 500, 9000));
    }

    // ----- recovery from congestion tests -----

    @Test
    public void afterCongestionBelowFactoryDefaultIncreases() {
        // congested shrinks to 1024, then healthy → increase toward 2048
        assertEquals(1152, target(RECOVERY_FLOOR, 500));
    }

    // ----- RTT-based decrease tests -----

    @Test
    public void highRTTDecreasesAboveDefault() {
        // current > 2048 and observed > 7000 → decrease toward recovery floor
        assertEquals(RECOVERY_FLOOR, target(3000, 8000));
    }

    @Test
    public void highRTTAtMinStays() {
        // current == min, high RTT → stay at min
        assertEquals(MIN, target(MIN, 8000));
    }

    // ----- edge cases -----

    @Test
    public void atMinBelowRecoveryFloorIncreases() {
        // at 128 (min, below recovery floor) → increase toward 2048
        assertEquals(Math.min(DEFAULT, MIN + STEP), target(MIN, 500));
    }

    @Test
    public void aboveDoubleFactoryDefaultIncreases() {
        // current == 4096 (above factoryDefault * 2) → increase toward MAX
        int result = target(4096, 500);
        assertTrue("Should increase above factoryDefault * 2", result > 4096);
    }

    @Test
    public void observedHighAtMinStays() {
        // current == min, high observed → stay at min
        assertEquals(MIN, target(MIN, 8000));
    }

    @Test
    public void recoveryFloorAtMinStays() {
        // at 128, congested → stays at min
        assertEquals(MIN, targetCongested(MIN, 500, 9000));
    }
}
