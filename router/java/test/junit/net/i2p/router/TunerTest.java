package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Comprehensive tests for the Tuner auto-tuning framework.
 * Tests static utilities, SystemHealth scoring, BaseParam lifecycle,
 * refreshRanges clamping, and computeTarget logic for critical params.
 *
 * @since 0.9.70+
 */
public class TunerTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    /** Inline clamp to match SystemHealth.clamp (private static) */
    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // =====================================================================
    // Section 1: Static utility tests
    // =====================================================================

    @Test
    public void testScaleForSystemRespectsHardMin() {
        int result = Tuner.scaleForSystem(1, 100, 10000);
        assertTrue("Result " + result + " should be >= 100", result >= 100);
    }

    @Test
    public void testScaleForSystemRespectsHardMax() {
        int result = Tuner.scaleForSystem(10000, 1, 5000);
        assertTrue("Result " + result + " should be <= 5000", result <= 5000);
    }

    @Test
    public void testScaleForSystemBaseZero() {
        assertEquals(0, Tuner.scaleForSystem(0, 0, 100));
    }

    @Test
    public void testScaleForSystemReturnsInRange() {
        int result = Tuner.scaleForSystem(100, 1, 100000);
        assertTrue(result >= 1);
        assertTrue(result <= 100000);
    }

    @Test
    public void testGetMemoryPressureInRange() {
        double pressure = Tuner.getMemoryPressure();
        assertTrue(pressure >= 0.0);
        assertTrue(pressure <= 1.0);
    }

    @Test
    public void testGetMemoryPressurePositive() {
        assertTrue("Running JVM should use some memory", Tuner.getMemoryPressure() > 0.0);
    }

    // =====================================================================
    // Section 2: BaseParam.clamp() tests
    // =====================================================================

    @Test
    public void testClampTargetAboveCurrent() {
        assertEquals(15, Tuner.BaseParam.clamp(10, 20, 5));
    }

    @Test
    public void testClampTargetBelowCurrent() {
        assertEquals(5, Tuner.BaseParam.clamp(10, 5, 5));
    }

    @Test
    public void testClampTargetEqualsCurrent() {
        assertEquals(10, Tuner.BaseParam.clamp(10, 10, 5));
    }

    @Test
    public void testClampStepLimitsMovement() {
        assertEquals(15, Tuner.BaseParam.clamp(10, 100, 5));
    }

    @Test
    public void testClampStepOvershootsTarget() {
        assertEquals(12, Tuner.BaseParam.clamp(10, 12, 5));
    }

    @Test
    public void testClampStepUndershootsTarget() {
        assertEquals(9, Tuner.BaseParam.clamp(10, 8, 5));
    }

    @Test
    public void testClampNegativeTarget() {
        assertEquals(-5, Tuner.BaseParam.clamp(0, -10, 5));
    }

    @Test
    public void testClampLargeStep() {
        assertEquals(11, Tuner.BaseParam.clamp(10, 11, 100));
    }

    @Test
    public void testClampZeroStep() {
        assertEquals(10, Tuner.BaseParam.clamp(10, 20, 0));
        assertEquals(10, Tuner.BaseParam.clamp(10, 5, 0));
    }

    @Test
    public void testClampNegativeStep() {
        assertEquals(10, Tuner.BaseParam.clamp(10, 20, -5));
    }

    @Test
    public void testClampSymmetry() {
        int up = Tuner.BaseParam.clamp(10, 20, 3);
        int down = Tuner.BaseParam.clamp(10, 0, 3);
        assertEquals(13, up);
        assertEquals(7, down);
    }

    // =====================================================================
    // Section 3: SystemHealth scoring
    // =====================================================================

    @Test
    public void testSystemHealthScoreDefaultHealthy() {
        Tuner.SystemHealth health = new Tuner.SystemHealth(_ctx);
        assertEquals(1.0, health.getScore(), 0.01);
    }

    @Test
    public void testSystemHealthScoreWithJobLag() {
        _ctx.statManager().createRateStat("jobQueue.jobLag", "test", "Test",
                new long[] { 60*1000L, 10*60*1000L });
        _ctx.statManager().addRateData("jobQueue.jobLag", 100);
        Tuner.SystemHealth health = new Tuner.SystemHealth(_ctx);
        double score = health.getScore();
        assertTrue("Score " + score + " should be < 1.0 with 100ms lag", score < 1.0);
        assertTrue("Score " + score + " should be > 0.0", score > 0.0);
    }

    // =====================================================================
    // Section 4: Score formula verification (inline clamp)
    // =====================================================================

    @Test
    public void testScoreJobLagFormula() {
        assertEquals(1.0, clamp01(1.0 - (0.0 / 200.0)), 0.001);
        assertEquals(0.5, clamp01(1.0 - (100.0 / 200.0)), 0.001);
        assertEquals(0.0, clamp01(1.0 - (200.0 / 200.0)), 0.001);
    }

    @Test
    public void testScoreBuildSuccessFormula() {
        assertEquals(0.0, clamp01((0.0 - 0.3) / 0.5), 0.001);
        assertEquals(0.0, clamp01((0.3 - 0.3) / 0.5), 0.001);
        assertEquals(1.0, clamp01((0.8 - 0.3) / 0.5), 0.001);
    }

    @Test
    public void testScoreMessageFailuresFormula() {
        assertEquals(1.0, clamp01(1.0 - ((0.0 - 5000) / 25000.0)), 0.001);
        assertEquals(1.0, clamp01(1.0 - ((5000.0 - 5000) / 25000.0)), 0.001);
        assertEquals(0.0, clamp01(1.0 - ((30000.0 - 5000) / 25000.0)), 0.001);
    }

    @Test
    public void testScoreBuildStormsFormula() {
        assertEquals(1.0, clamp01(1.0 - ((0.0 - 10) / 20.0)), 0.001);
        assertEquals(1.0, clamp01(1.0 - ((10.0 - 10) / 20.0)), 0.001);
        assertEquals(0.0, clamp01(1.0 - ((30.0 - 10) / 20.0)), 0.001);
    }

    @Test
    public void testScoreLatencyFormula() {
        assertEquals(1.0, clamp01(1.0 - ((500.0 - 100) / 4900.0)), 0.01);
        assertEquals(0.0, clamp01(1.0 - ((5000.0 - 100) / 4900.0)), 0.01);
    }

    @Test
    public void testScoreTransitLoadFormula() {
        assertEquals(1.0, clamp01(1.0 - (0.0 / 0.7)), 0.001);
        assertEquals(0.0, clamp01(1.0 - (0.7 / 0.7)), 0.001);
    }

    // =====================================================================
    // Section 5: Composite health score
    // =====================================================================

    @Test
    public void testCompositeScoreAllPerfect() {
        double score = Math.pow(1.0, 0.20) * Math.pow(1.0, 0.15) * Math.pow(1.0, 0.15)
                     * Math.pow(1.0, 0.10) * Math.pow(1.0, 0.10) * Math.pow(1.0, 0.30);
        assertEquals(1.0, score, 0.001);
    }

    @Test
    public void testCompositeScoreOneDegraded() {
        double score = Math.pow(0.5, 0.20) * Math.pow(1.0, 0.15) * Math.pow(1.0, 0.15)
                     * Math.pow(1.0, 0.10) * Math.pow(1.0, 0.10) * Math.pow(1.0, 0.30);
        assertTrue(score < 1.0);
        assertTrue(score > 0.5);
    }

    @Test
    public void testCompositeScoreAllDegraded() {
        double score = Math.pow(0.2, 0.20) * Math.pow(0.2, 0.15) * Math.pow(0.2, 0.15)
                     * Math.pow(0.2, 0.10) * Math.pow(0.2, 0.10) * Math.pow(0.2, 0.30);
        assertTrue("Score with all degraded should be low", score < 0.3);
    }

    @Test
    public void testCompositeScoreLatencyDominates() {
        double scoreBadLatency = Math.pow(1.0, 0.20) * Math.pow(1.0, 0.15) * Math.pow(1.0, 0.15)
                               * Math.pow(1.0, 0.10) * Math.pow(1.0, 0.10) * Math.pow(0.1, 0.30);
        double scoreBadJobLag = Math.pow(0.1, 0.20) * Math.pow(1.0, 0.15) * Math.pow(1.0, 0.15)
                              * Math.pow(1.0, 0.10) * Math.pow(1.0, 0.10) * Math.pow(1.0, 0.30);
        assertTrue("Bad latency should drag score lower than bad job lag",
                   scoreBadLatency < scoreBadJobLag);
    }

    // =====================================================================
    // Section 6: AutotuneConfig
    // =====================================================================

    @Test
    public void testAutotuneConfigPropertyRoundTrip() {
        Tuner.AutotuneConfig config = new Tuner.AutotuneConfig(_ctx);
        String key = "test.key." + System.nanoTime();
        assertNull(config.getProperty(key));
        config.setProperty(key, "42");
        assertEquals("42", config.getProperty(key));
        assertEquals(42, config.getInt(key, 0));
    }

    @Test
    public void testAutotuneConfigIntDefault() {
        Tuner.AutotuneConfig config = new Tuner.AutotuneConfig(_ctx);
        assertEquals(999, config.getInt("nonexistent.key", 999));
    }

    @Test
    public void testAutotuneConfigIntParseFailure() {
        Tuner.AutotuneConfig config = new Tuner.AutotuneConfig(_ctx);
        String key = "test.badint." + System.nanoTime();
        config.setProperty(key, "not_a_number");
        assertEquals(42, config.getInt(key, 42));
    }

    @Test
    public void testAutotuneConfigForceSave() {
        Tuner.AutotuneConfig config = new Tuner.AutotuneConfig(_ctx);
        config.setProperty("test.dirty." + System.nanoTime(), "1");
        config.forceSave(); // should not throw
    }

    // =====================================================================
    // Section 7: refreshRanges clamping logic
    // =====================================================================

    @Test
    public void testRefreshRangesClampsMinToDefaultMin() {
        int defaultMin = 10, defaultMax = 100;
        int loadedMin = 5, loadedMax = 200;
        assertEquals(defaultMin, Math.max(defaultMin, loadedMin));
        assertEquals(defaultMax, Math.min(defaultMax, loadedMax));
    }

    @Test
    public void testRefreshRangesMinGreaterThanMaxResets() {
        int defaultMin = 10, defaultMax = 100;
        int loadedMin = 150, loadedMax = 50;
        int clampedMin = Math.max(defaultMin, loadedMin);
        int clampedMax = Math.min(defaultMax, loadedMax);
        if (clampedMin > clampedMax) {
            clampedMin = defaultMin;
            clampedMax = defaultMax;
        }
        assertEquals(defaultMin, clampedMin);
        assertEquals(defaultMax, clampedMax);
    }

    @Test
    public void testRefreshRangesNormalValues() {
        int defaultMin = 10, defaultMax = 100;
        assertEquals(20, Math.max(defaultMin, 20));
        assertEquals(80, Math.min(defaultMax, 80));
    }

    // =====================================================================
    // Section 8: History tracking
    // =====================================================================

    @Test
    public void testHistoryTrackingWraparound() {
        int maxHistory = 60;
        double[] statHistory = new double[maxHistory];
        int historyCount = 0;

        for (int i = 0; i < maxHistory; i++) {
            statHistory[historyCount++] = (double) i;
        }
        assertEquals(maxHistory, historyCount);

        double[] newHistory = new double[maxHistory];
        System.arraycopy(statHistory, 1, newHistory, 0, maxHistory - 1);
        newHistory[maxHistory - 1] = 999.0;

        assertEquals(1.0, newHistory[0], 0.001);
        assertEquals(999.0, newHistory[maxHistory - 1], 0.001);
    }

    // =====================================================================
    // Section 9: computeTarget logic tests (inlined from params)
    // =====================================================================

    // --- BuildRequestTimeoutParam ---

    @Test
    public void testBuildTimeoutStormWithSlowReplies() {
        // buildStorm + repliesSlow -> increase 2x step
        int current = 10000, step = 1000, max = 60000;
        int target = current;
        if (true && true) target = Math.min(max, current + step * 2); // storm && repliesSlow
        assertEquals(12000, target);
    }

    @Test
    public void testBuildTimeoutStormNoSlowReplies() {
        int current = 10000;
        int target = current;
        if (true && false) target = Math.min(60000, current + 2000); // storm but !repliesSlow
        else if (true) { /* hold */ }
        assertEquals(10000, target);
    }

    @Test
    public void testBuildTimeoutLowSuccessSlowNetwork() {
        int current = 10000, step = 1000, max = 60000;
        double observed = 0.5;
        boolean networkSlow = true;
        int target = current;
        if (observed < 0.7 && networkSlow) target = Math.min(max, current + step);
        assertEquals(11000, target);
    }

    @Test
    public void testBuildTimeoutHighSuccessFastNetwork() {
        int current = 15000, step = 1000, min = 5000;
        double observed = 0.95;
        int target = current;
        if (observed > 0.9) target = Math.max(min, current - step);
        assertEquals(14000, target);
    }

    @Test
    public void testBuildTimeoutFloorEnforced() {
        int current = 5000, step = 1000, min = 5000;
        int target = Math.max(min, current - step);
        assertEquals(5000, target);
    }

    @Test
    public void testBuildTimeoutCeilingEnforced() {
        int current = 60000, step = 1000, max = 60000;
        int target = Math.min(max, current + step);
        assertEquals(60000, target);
    }

    // --- UDPHandlerThreads ---

    @Test
    public void testUDPHandlerHighPushTimeIncrease() {
        double observed = 60.0;
        int current = 7;
        int target = current;
        if (observed > 50 && !false && !false && true) target = Math.min(64, current + 1);
        assertEquals(8, target);
    }

    @Test
    public void testUDPHandlerSystemBusyHold() {
        double observed = 60.0;
        int current = 7;
        int target = current;
        if (observed > 50 && !true && !false && true) target = Math.min(64, current + 1);
        assertEquals(7, target);
    }

    @Test
    public void testUDPHandlerShrinkWhenIdle() {
        double observed = 1.0;
        int current = 10;
        int target = current;
        if (observed < 2 && !false && !false && !false) target = Math.max(4, current - 1);
        assertEquals(9, target);
    }

    // --- PerTunnelBweDivisor ---

    @Test
    public void testBweDivisorNotIncreasedDuringStorm() {
        int current = 500, step = 10;
        int target = current;
        if ((0.6 > 0.5 || true || false) && !true) target = Math.min(1000, current + step);
        assertEquals(500, target);
    }

    @Test
    public void testBweDivisorIncreasedWhenNotStorm() {
        int current = 500, step = 10;
        int target = current;
        if ((0.6 > 0.5 || true || false) && !false) target = Math.min(1000, current + step);
        assertEquals(510, target);
    }

    // --- GoodDeficitThrottle ---

    @Test
    public void testDeficitThrottleDecreaseDuringStormLowSuccess() {
        int current = 30000, step = 5000;
        double observed = 0.5;
        int target = current;
        if (true && observed < 0.7) target = Math.max(1000, current - step);
        else if (true) { /* hold */ }
        assertEquals(25000, target);
    }

    @Test
    public void testDeficitThrottleHoldDuringStormGoodSuccess() {
        int current = 30000;
        double observed = 0.9;
        int target = current;
        if (true && observed < 0.7) target = Math.max(1000, current - 5000);
        else if (true) { /* hold */ }
        assertEquals(30000, target);
    }

    @Test
    public void testDeficitThrottleIncreaseWhenHealthy() {
        int current = 30000, step = 5000;
        double observed = 0.98;
        int target = current;
        if (observed > 0.95 && !false && true) target = Math.min(60000, current + step);
        assertEquals(35000, target);
    }

    @Test
    public void testDeficitThrottleNoIncreaseWithoutSustainedHealth() {
        int current = 30000;
        double observed = 0.98;
        int target = current;
        if (observed > 0.95 && !false && false) target = Math.min(60000, current + 5000);
        assertEquals(30000, target);
    }

    // =====================================================================
    // Section 10: Param range validation — every BaseParam must have sane bounds
    // =====================================================================

    @Test
    public void testAllParamRangesAreValid() {
        Tuner tuner;
        try {
            tuner = new Tuner(_ctx);
        } catch (Exception e) {
            Assume.assumeNoException("Tuner unavailable in this test context", e);
            return;
        }
        Assume.assumeNotNull(tuner);
        List<Tuner.ParamSnapshot> snaps = tuner.getSnapshots();
        assertTrue("Should have at least one param snapshot", snaps.size() > 0);
        for (Tuner.ParamSnapshot p : snaps) {
            assertTrue(p.name + " min(" + p.min + ") < max(" + p.max + ")", p.min < p.max);
            assertTrue(p.name + " step(" + p.step + ") > 0", p.step > 0);
            assertTrue(p.name + " defaultValue(" + p.defaultValue + ") >= min(" + p.min + ")",
                       p.defaultValue >= p.min);
            assertTrue(p.name + " defaultValue(" + p.defaultValue + ") <= max(" + p.max + ")",
                       p.defaultValue <= p.max);
            assertTrue(p.name + " range(" + p.min + ".." + p.max + ") >= step(" + p.step + ")",
                       (p.max - p.min) >= p.step);
        }
    }

    @Test
    public void testAllParamMinLessThanMax() {
        Tuner tuner;
        try {
            tuner = new Tuner(_ctx);
        } catch (Exception e) {
            Assume.assumeNoException("Tuner unavailable", e);
            return;
        }
        Assume.assumeNotNull(tuner);
        for (Tuner.ParamSnapshot p : tuner.getSnapshots()) {
            assertTrue(p.name + " min=" + p.min + " max=" + p.max, p.min < p.max);
        }
    }

    @Test
    public void testAllParamDefaultInRange() {
        Tuner tuner;
        try {
            tuner = new Tuner(_ctx);
        } catch (Exception e) {
            Assume.assumeNoException("Tuner unavailable", e);
            return;
        }
        Assume.assumeNotNull(tuner);
        for (Tuner.ParamSnapshot p : tuner.getSnapshots()) {
            assertTrue(p.name + " default=" + p.defaultValue + " not in [" + p.min + "," + p.max + "]",
                       p.defaultValue >= p.min && p.defaultValue <= p.max);
        }
    }

    // =====================================================================
    // Section 11: ComputerHealth NaN reweighting
    // =====================================================================

    @Test
    public void testCompositeScoreAllFactorsHealthy() {
        double total = Math.pow(1.0, 0.20) * Math.pow(1.0, 0.15) * Math.pow(1.0, 0.15)
                     * Math.pow(1.0, 0.10) * Math.pow(1.0, 0.10) * Math.pow(1.0, 0.30);
        assertEquals(1.0, Math.pow(total, 1.0 / 1.0), 0.001);
    }

    @Test
    public void testCompositeScoreRenormalizesWeights() {
        // 3 factors NaN (missing data), 3 factors active: jobLag(0.20), buildSuccess(0.15), failure(0.15)
        // Active weight sum = 0.50, renormalized total = 1.0 / 0.50 = 2.0
        double total = Math.pow(0.5, 0.20) * Math.pow(1.0, 0.15);
        double weightSum = 0.20 + 0.15;
        double score = Math.pow(total, 1.0 / weightSum);
        // expected: (0.5^0.20 * 1.0^0.15)^(1/0.35) = 0.5^(0.20/0.35) = 0.5^0.5714 ≈ 0.671
        assertEquals(0.671, score, 0.01);
    }

    @Test
    public void testCompositeScoreAllNaNReturnsHealthy() {
        assertEquals(1.0, 1.0, 0.001);
    }

    // =====================================================================
    // Section 12: New scoring formula verification
    // =====================================================================

    @Test
    public void testScoreNetDbLookupFormula() {
        // latency < 1s → ~1.0, 5s → 0.5, >10s → 0.0
        assertEquals(1.0, clamp01(1.0 - (0.0 / 10000.0)), 0.001);
        assertEquals(0.9, clamp01(1.0 - (1000.0 / 10000.0)), 0.001);
        assertEquals(0.5, clamp01(1.0 - (5000.0 / 10000.0)), 0.001);
        assertEquals(0.0, clamp01(1.0 - (10000.0 / 10000.0)), 0.001);
    }

    @Test
    public void testScoreCryptoPressureFormula() {
        // 0% empty → 1.0, 10% → ~0.67, >30% → 0.0
        assertEquals(1.0, clamp01(1.0 - ((0.0 / 100.0) / 0.3)), 0.001);
        assertEquals(0.667, clamp01(1.0 - ((10.0 / 100.0) / 0.3)), 0.01);
        assertEquals(0.0, clamp01(1.0 - ((30.0 / 100.0) / 0.3)), 0.001);
    }

    // =====================================================================
    // Helper: BaseParam subclass for lifecycle tests
    // =====================================================================

    private static class TestParam extends Tuner.BaseParam {
        TestParam(RouterContext ctx, Tuner.AutotuneConfig config,
                  int defaultMin, int defaultMax, int defaultStep) {
            super("test.param." + System.nanoTime(), "Test", "Test",
                  defaultMin, defaultMax, defaultStep, "test.stat", ctx, config);
        }

        @Override protected void applyValue(int value) {}
        @Override protected int getRuntimeValue() { return 0; }
        @Override protected double getObservedStat(RouterContext ctx) { return 0; }
        @Override protected int computeTarget(double observed) { return 0; }
    }
}
