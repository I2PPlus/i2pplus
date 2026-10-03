package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Decision tests for {@link Tuner#congestionAvoidanceGrowthTarget} and
 * {@link Tuner#slowStartGrowthTarget} — the two streaming window-growth factors.
 *
 * <p>Both are Tuner values where higher means faster (the code factor is
 * inverted, {@code 5 - value}). Range [1,4] with a step of 1 means each step
 * changes real window regrowth by 2x, so where a factor settles decides how fast
 * a window recovers from a loss cut.
 *
 * <p>The regression these cover: the dead zone spans {@code [default-1, default+1]},
 * which in a [1,4] range is three of the four values, so a factor that decayed one
 * step below the default was pinned there. Both increase branches were gated on a
 * <i>low</i> retransmit ratio, so the loss that pushed the factor down also
 * prevented it from climbing back — the slower growth rate became permanent and
 * window cuts (multiplicative) outran regrowth (additive).
 *
 * @since 0.9.71+
 */
public class TunerStreamingGrowthFactorTest {

    private static final int MIN = 1;
    private static final int MAX = 4;
    private static final int STEP = 1;
    /** Factory default as persisted in autotune.config. */
    private static final int DEFAULT = 4;

    private static final double NaN = Double.NaN;

    /** Healthy, loss-free, no congestion, no choke pressure. */
    private static final double HEALTHY_BUILD = 0.9;
    private static final double NO_FAIL = 1000.0;
    private static final double NO_LOSS = 0.0;
    private static final double FAST_RTT = 1000.0;
    private static final double BIG_WINDOW = 100.0;
    private static final double NO_CHOKE = 0.0;
    private static final double HEALTHY_CWIN = 100.0;
    /** Well under the 50/40 per-mille "retransmitLow" thresholds. */
    private static final double LOW_RTX = 10.0;
    /** Well over the 200/160 per-mille "retransmitting" thresholds. */
    private static final double HIGH_RTX = 400.0;

    private int caTarget(int current) {
        return caTarget(current, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                        BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, LOW_RTX, LOW_RTX);
    }

    private int caTarget(int current, int defaultValue, double buildSuccess, double failLifetime,
                         double lossRate, double lifetimeRTT, double lifetimeWindowSize,
                         double chokeSize, double congWindowSize, double rtxRatio, double rtxRatio5) {
        return Tuner.congestionAvoidanceGrowthTarget(current, MIN, MAX, STEP, defaultValue,
                                                     buildSuccess, failLifetime, lossRate, lifetimeRTT,
                                                     lifetimeWindowSize, chokeSize, congWindowSize,
                                                     rtxRatio, rtxRatio5);
    }

    private int ssTarget(int current) {
        return ssTarget(current, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                        BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, LOW_RTX, LOW_RTX);
    }

    private int ssTarget(int current, int defaultValue, double buildSuccess, double failLifetime,
                         double lossRate, double observed, double lifetimeWindowSize,
                         double chokeSize, double congWindowSize, double rtxRatio, double rtxRatio5) {
        return Tuner.slowStartGrowthTarget(current, MIN, MAX, STEP, defaultValue, observed,
                                          buildSuccess, failLifetime, lossRate, lifetimeWindowSize,
                                          chokeSize, congWindowSize, rtxRatio, rtxRatio5);
    }

    // ---- the core regression: escape the dead zone from one step below default ----

    /**
     * The reported failure: CA growth sitting at {@code default-1} on a merely
     * noisy path (retransmit ratio between the "low" and "high" thresholds) must
     * climb back toward the default rather than hold.
     */
    @Test
    public void testCaGrowthClimbsFromOneStepBelowDefaultWhenNoisy() {
        // 90 per-mille: above retransmitLow (<50) so the narrow increase branches
        // cannot fire, but below retransmitting (>200) so it is not hard loss.
        int target = caTarget(DEFAULT - 1, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0);
        assertEquals(DEFAULT, target);
    }

    /** Same escape for slow start. */
    @Test
    public void testSlowStartClimbsFromOneStepBelowDefaultWhenNoisy() {
        int target = ssTarget(DEFAULT - 1, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0);
        assertEquals(DEFAULT, target);
    }

    /**
     * The escape must hold when the path has no retransmit data at all, which is
     * the common case early in a connection's life.
     */
    @Test
    public void testCaGrowthClimbsWithNoRetransmitData() {
        int target = caTarget(DEFAULT - 1, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, NaN, NaN);
        assertEquals(DEFAULT, target);
    }

    /**
     * From the recovery floor the factor must climb one step per healthy cycle
     * all the way back to the default, instead of stalling at the floor.
     */
    @Test
    public void testCaGrowthRecoversFromRecoveryFloor() {
        int recoveryFloor = Math.max(MIN, DEFAULT / 2);
        int atFloor = caTarget(recoveryFloor - 1, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                               BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0);
        assertEquals(recoveryFloor, atFloor);
        // recoveryFloor + 1, then + 2, then the default: one step per cycle.
        assertEquals(recoveryFloor + STEP,
                     caTarget(atFloor, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0));
        assertEquals(recoveryFloor + 2 * STEP,
                     caTarget(recoveryFloor + STEP, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0));
        assertEquals(DEFAULT,
                     caTarget(recoveryFloor + 2 * STEP, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0));
        // Once at the default the dead zone holds it there.
        assertEquals(DEFAULT,
                     caTarget(DEFAULT, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0));
    }

    /** Slow start recovers from its recovery floor the same way, one step per cycle. */
    @Test
    public void testSlowStartRecoversFromRecoveryFloor() {
        int recoveryFloor = Math.max(MIN, DEFAULT / 2);
        assertEquals(recoveryFloor + STEP,
                     ssTarget(recoveryFloor, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0));
        assertEquals(DEFAULT,
                     ssTarget(recoveryFloor + STEP, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0));
    }

    // ---- hard negatives must still block the climb ----

    /** Heavy loss keeps the factor down: this is not a licence to grow into congestion. */
    @Test
    public void testCaGrowthHoldsDownUnderHeavyRetransmit() {
        int target = caTarget(DEFAULT - 1, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, HIGH_RTX, HIGH_RTX);
        assertEquals(DEFAULT - 1 - STEP, target);
    }

    /** Loss on the 5-minute window alone is enough to block. */
    @Test
    public void testCaGrowthHoldsDownOnFiveMinuteRetransmitAlone() {
        int target = caTarget(DEFAULT - 1, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, NaN, HIGH_RTX);
        assertEquals(DEFAULT - 1 - STEP, target);
    }

    /** Congestion blocks the climb. */
    @Test
    public void testCaGrowthHoldsDownUnderCongestion() {
        int target = caTarget(DEFAULT - 1, DEFAULT, HEALTHY_BUILD, 9000.0, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0);
        assertEquals(DEFAULT - 1 - STEP, target);
    }

    /** A lossy path (streaming loss rate at/above threshold) blocks the climb. */
    @Test
    public void testCaGrowthHoldsDownWhenLossy() {
        int target = caTarget(DEFAULT - 1, DEFAULT, HEALTHY_BUILD, NO_FAIL, 0.30, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0);
        assertEquals(DEFAULT - 1 - STEP, target);
    }

    /** A collapsed window at congestion blocks the climb. */
    @Test
    public void testCaGrowthHoldsDownWhenCwinCollapsed() {
        int target = caTarget(DEFAULT - 1, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, 2.0, 90.0, 90.0);
        assertEquals(DEFAULT - 1 - STEP, target);
    }

    /** An unhealthy network blocks the climb. */
    @Test
    public void testCaGrowthHoldsDownOnUnhealthyNetwork() {
        int target = caTarget(DEFAULT - 1, DEFAULT, 0.5, NO_FAIL, NO_LOSS, FAST_RTT,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, 90.0, 90.0);
        assertEquals(DEFAULT - 1 - STEP, target);
    }

    // ---- the dead zone still holds at the default itself ----

    /**
     * The stated purpose of the zone is unchanged: a wobbling signal must not
     * ping-pong the factor while it sits exactly at the default.
     */
    @Test
    public void testDeadZoneStillHoldsAtDefault() {
        assertEquals(DEFAULT, caTarget(DEFAULT));
        assertEquals(DEFAULT, ssTarget(DEFAULT));
    }

    /** Nothing is clamped above {@code max}. */
    @Test
    public void testNeverExceedsMax() {
        for (int cur = MIN; cur <= MAX; cur++) {
            assertTrue("CA target out of range at " + cur, caTarget(cur) <= MAX);
            assertTrue("CA target out of range at " + cur, caTarget(cur) >= MIN);
            assertTrue("SS target out of range at " + cur, ssTarget(cur) <= MAX);
            assertTrue("SS target out of range at " + cur, ssTarget(cur) >= MIN);
        }
    }

    // ---- the pre-existing strong-signal bypass still works ----

    /**
     * A large completed window with a low retransmit ratio remains the strongest
     * growth signal and must still be able to push above the default.
     */
    @Test
    public void testStrongGrowthSignalStillRaises() {
        int target = caTarget(DEFAULT, DEFAULT, HEALTHY_BUILD, NO_FAIL, NO_LOSS, FAST_RTT,
                              50.0, NO_CHOKE, HEALTHY_CWIN, LOW_RTX, LOW_RTX);
        assertEquals(MAX, target);
    }

    /** Slow start's above-default RTT escape is unchanged. */
    @Test
    public void testSlowStartAboveDefaultDropsOnHighRtt() {
        // default 3 puts the current value above it; a 9s RTT walks it back
        int target = ssTarget(DEFAULT, 3, HEALTHY_BUILD, NO_FAIL, NO_LOSS, 9000.0,
                              BIG_WINDOW, NO_CHOKE, HEALTHY_CWIN, LOW_RTX, LOW_RTX);
        assertEquals(DEFAULT - STEP, target);
    }

    // ---- missing stats must not read as a negative signal ----

    /**
     * An absent build-success rate is treated as healthy
     * ({@code Double.isNaN(buildSuccess) || buildSuccess > 0.7}), so a fresh
     * router still climbs rather than pinning at the floor.
     */
    @Test
    public void testUnknownBuildSuccessStillClimbs() {
        int target = caTarget(DEFAULT - 1, DEFAULT, NaN, NaN, NaN, NaN,
                              NaN, NaN, NaN, NaN, NaN);
        assertEquals(DEFAULT, target);
    }
}
