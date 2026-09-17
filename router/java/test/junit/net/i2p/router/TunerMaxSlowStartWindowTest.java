package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure slow-start-window growth/shrink decision
 * {@link Tuner#maxSlowStartWindow}, extracted from
 * {@code MaxSlowStartWindowParam#computeTarget}.
 *
 * <p>Covers the recovery floor (below factoryDefault/2 always increases),
 * the dead zone (holds at factory default unless signal is strong),
 * the congestion/drop shrink, the growth from recovery floor toward
 * factory default, and the RTT-based decrease above factory default.
 *
 * @since 0.9.72+
 */
public class TunerMaxSlowStartWindowTest {

    private static final int MIN = 128;
    private static final int MAX = 4096;
    private static final int STEP = 128;
    private static final int DEFAULT = 1024;
    private static final int RECOVERY_FLOOR = DEFAULT / 2; // 512
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
        // current < 512 → increase toward 1024
        assertEquals(Math.min(DEFAULT, BASE + STEP), target(BASE, 500));
    }

    @Test
    public void belowRecoveryFloorStopsAtFactoryDefault() {
        // at 900 (below 1024 but above 512) → increase toward 1024
        assertEquals(1024, target(900, 500));
    }

    @Test
    public void atRecoveryFloorIncreases() {
        // at 512 → increase toward 1024
        assertEquals(640, target(RECOVERY_FLOOR, 500));
    }

    // ----- dead zone tests -----

    @Test
    public void atFactoryDefaultHolds() {
        // current == 1024 → hold (dead zone)
        assertEquals(DEFAULT, target(DEFAULT, 500));
    }

    @Test
    public void aboveFactoryDefaultHolds() {
        // current == 1500 (within dead zone) → hold
        assertEquals(1500, target(1500, 500));
    }

    @Test
    public void deadZoneUpperBound() {
        // current == 2048 (factoryDefault * 2) → hold
        assertEquals(DEFAULT * 2, target(DEFAULT * 2, 500));
    }

    @Test
    public void aboveDeadZoneIncreases() {
        // current == 2049 (above dead zone) → increase toward MAX
        assertEquals(2177, target(2049, 500));
    }

    // ----- congestion and drop shrink tests -----

    @Test
    public void congestionShrinksFromDefault() {
        // congested at 1024 → shrink toward recovery floor
        assertEquals(RECOVERY_FLOOR, targetCongested(DEFAULT, 500, 9000));
    }

    @Test
    public void congestionShrinksBelowDefault() {
        // congested at 1500 → shrink toward recovery floor
        assertEquals(Math.max(RECOVERY_FLOOR, 1500 - STEP),
                     targetCongested(1500, 500, 9000));
    }

    @Test
    public void congestionShrinksAtMin() {
        // congested at 128 (min) → stays at min
        assertEquals(MIN, targetCongested(MIN, 500, 9000));
    }

    @Test
    public void droppingShrinksFromDefault() {
        // dropping at 1024 → shrink toward recovery floor
        assertEquals(RECOVERY_FLOOR, targetDropping(DEFAULT, 500, 600));
    }

    @Test
    public void droppingShrinksAboveDefault() {
        // dropping at 1500 → shrink toward recovery floor
        assertEquals(Math.max(RECOVERY_FLOOR, 1500 - STEP),
                     targetDropping(1500, 500, 600));
    }

    @Test
    public void congestionAndDroppingTogetherShrink() {
        // both congested and dropping at 1024 → shrink toward recovery floor
        assertEquals(RECOVERY_FLOOR, targetCongested(DEFAULT, 500, 9000));
    }

    // ----- recovery from congestion tests -----

    @Test
    public void afterCongestionBelowFactoryDefaultIncreases() {
        // congested shrinks to 512, then healthy → increase toward 1024
        assertEquals(640, target(RECOVERY_FLOOR, 500));
    }

    @Test
    public void afterCongestionRecoveryFloorStays() {
        // at recovery floor, healthy → increase toward factory default
        assertEquals(640, target(RECOVERY_FLOOR, 500));
    }

    // ----- RTT-based decrease tests -----

    @Test
    public void highRTTDecreasesAboveDefault() {
        // current > 1024 and observed > 7000 → decrease toward recovery floor
        assertEquals(RECOVERY_FLOOR, target(1500, 8000));
    }

    @Test
    public void highRTTAtMinStays() {
        // current == min, high RTT → stay at min
        assertEquals(MIN, target(MIN, 8000));
    }

    @Test
    public void normalRTTAboveDefaultHolds() {
        // current > 1024, observed < 7000 → hold (dead zone)
        assertEquals(1500, target(1500, 500));
    }

    // ----- edge cases -----

    @Test
    public void atMinBelowRecoveryFloorIncreases() {
        // at 128 (min, below recovery floor) → increase toward 1024
        assertEquals(Math.min(DEFAULT, MIN + STEP), target(MIN, 500));
    }

    @Test
    public void atMaxAboveDeadZoneStays() {
        // at MAX (4096), healthy → stays at MAX (no growth beyond max)
        assertEquals(MAX, target(MAX, 500));
    }

    @Test
    public void congestionBelowRecoveryFloorStaysAtMin() {
        // congested at min → stays at min
        assertEquals(MIN, targetCongested(MIN, 500, 9000));
    }

    @Test
    public void aboveDoubleFactoryDefaultIncreases() {
        // current == 2049 (above factoryDefault * 2) → increase toward MAX
        int result = target(2049, 500);
        assertTrue("Should increase above dead zone", result > 2049);
    }
}
