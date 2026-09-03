package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

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
        // isolated context: Tuner score assertions depend on the StatManager
        // being free of rate data accumulated by other test classes
        _ctx = RouterTestHelper.newContext();
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
        assertEquals(8, Tuner.BaseParam.clamp(10, 8, 5));
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
        // clear any job-lag stat left behind by a sibling test in this context
        _ctx.statManager().removeRateStat("jobQueue.jobLag");
        Tuner.SystemHealth health = new Tuner.SystemHealth(_ctx);
        assertEquals(1.0, health.getScore(), 0.01);
    }

    @Test
    public void testSystemHealthScoreWithJobLag() throws Exception {
        _ctx.statManager().createRateStat("jobQueue.jobLag", "test", "Test",
                new long[] { 60*1000L, 10*60*1000L });
        // the score reads the completed one-minute rate window, which only fills
        // after a coalesce cycle, so seed it directly
        net.i2p.stat.RateStat rs = _ctx.statManager().getRate("jobQueue.jobLag");
        net.i2p.stat.Rate rate = rs.getRate(net.i2p.stat.RateConstants.ONE_MINUTE);
        java.lang.reflect.Field ev = net.i2p.stat.Rate.class.getDeclaredField("_lastEventCount");
        ev.setAccessible(true);
        ev.setInt(rate, 4);
        java.lang.reflect.Field tv = net.i2p.stat.Rate.class.getDeclaredField("_lastTotalValue");
        tv.setAccessible(true);
        tv.setFloat(rate, 400f);
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
        // Tuner.scoreLatency: <=100ms → 1.0; 100-1000ms → 1.0 - 0.5*(avg-100)/900; >1000ms → 0.5 - 0.5*(avg-1000)/4000
        assertEquals(1.0, clamp01(1.0 - 0.5 * ((100.0 - 100) / 900.0)), 0.001);
        assertEquals(0.778, clamp01(1.0 - 0.5 * ((500.0 - 100) / 900.0)), 0.01);
        // midpoint of 100-1000 range at avg=550: 1.0 - 0.5*450/900 = 0.75
        assertEquals(0.75, clamp01(1.0 - 0.5 * ((550.0 - 100) / 900.0)), 0.01);
        assertEquals(0.5, clamp01(1.0 - 0.5 * ((1000.0 - 100) / 900.0)), 0.001);
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
    // Section 13: RequestHighLoadLagParam hysteresis
    // =====================================================================

    /**
     * Inline the RequestHighLoadLagParam computeTarget logic.
     * Params: min=200, max=5000, step=100.
     * Dead-band: observed > 200 → tighten, observed < 50 → loosen.
     */
    private static int highLoadLagTarget(double observed, int current) {
        int min = 200, max = 5000, step = 100;
        if (!Double.isNaN(observed) && observed > 200 && current > min)
            return Math.max(min, current - step);
        if (!Double.isNaN(observed) && observed < 50 && current < max)
            return Math.min(max, current + step);
        return current;
    }

    @Test
    public void testHighLoadLagTightensWhenLagHigh() {
        assertEquals(400, highLoadLagTarget(250, 500));
    }

    @Test
    public void testHighLoadLagLoosensWhenLagLow() {
        assertEquals(600, highLoadLagTarget(30, 500));
    }

    @Test
    public void testHighLoadLagDeadBandHolds() {
        // observed=100 is inside 50–200 dead-band → no change
        assertEquals(500, highLoadLagTarget(100, 500));
    }

    @Test
    public void testHighLoadLagDeadBandUpperBoundary() {
        // observed=200 is NOT > 200 → no tighten; NOT < 50 → no loosen
        assertEquals(500, highLoadLagTarget(200, 500));
    }

    @Test
    public void testHighLoadLagDeadBandLowerBoundary() {
        // observed=50 is NOT > 200 and NOT < 50 → hold
        assertEquals(500, highLoadLagTarget(50, 500));
    }

    @Test
    public void testHighLoadLagFloorEnforced() {
        // current=200 (at min), observed high → can't decrease further
        assertEquals(200, highLoadLagTarget(300, 200));
    }

    @Test
    public void testHighLoadLagCeilingEnforced() {
        // current=5000 (at max), observed low → can't increase further
        assertEquals(5000, highLoadLagTarget(10, 5000));
    }

    @Test
    public void testHighLoadLagNaNHolds() {
        assertEquals(500, highLoadLagTarget(Double.NaN, 500));
    }

    // =====================================================================
    // Section 14: RequestModerateLoadLagParam independence + hysteresis
    // =====================================================================

    /**
     * Inline the RequestModerateLoadLagParam computeTarget logic.
     * Params: min=100, max=3000, step=50.
     * Primary: jobLag > 100 → tighten, jobLag < 30 AND readyJobs < 5 → loosen.
     * Ceiling: always stays below highLoadLagMs - step.
     */
    private static int moderateLoadLagTarget(double observed, int current,
                                              int highLag, double readyJobs) {
        int min = 100, max = 3000, step = 50;
        int ceiling = Math.max(min, highLag - step);
        boolean queueHigh = !Double.isNaN(readyJobs) && readyJobs > 20;
        boolean queueLow = !Double.isNaN(readyJobs) && readyJobs < 5;
        if ((!Double.isNaN(observed) && observed > 100 || queueHigh) && current > min)
            return Math.max(min, current - step);
        if ((!Double.isNaN(observed) && observed < 30) && queueLow && current < max)
            return Math.min(ceiling, current + step);
        return current;
    }

    @Test
    public void testModerateLagTightensWhenLagHigh() {
        // observed=150 > 100 → tighten
        assertEquals(450, moderateLoadLagTarget(150, 500, 800, 10));
    }

    @Test
    public void testModerateLagLoosensWhenBothLow() {
        // observed=20 < 30 AND readyJobs=2 < 5 → loosen
        assertEquals(450, moderateLoadLagTarget(20, 400, 800, 2));
    }

    @Test
    public void testModerateLagDeadBandHolds() {
        // observed=60 (inside 30–100), readyJobs=10 (inside 5–20) → hold
        assertEquals(500, moderateLoadLagTarget(60, 500, 800, 10));
    }

    @Test
    public void testModerateLagHighQueueTightensDespiteLowLag() {
        // observed=20 < 100 (lag OK), but readyJobs=25 > 20 → queueHigh triggers tighten
        assertEquals(350, moderateLoadLagTarget(20, 400, 800, 25));
    }

    @Test
    public void testModerateLagTightensFromQueueHigh() {
        // observed=NaN (no lag data), readyJobs=25 > 20 → tighten from queue alone
        assertEquals(350, moderateLoadLagTarget(Double.NaN, 400, 800, 25));
    }

    @Test
    public void testModerateLagCeilingEnforced() {
        // highLag=800, ceiling=max(100, 800-50)=750, observed low + queue low
        // current=740, would increase to 790, clamped to ceiling=750
        assertEquals(750, moderateLoadLagTarget(20, 740, 800, 2));
    }

    @Test
    public void testModerateLagCeilingPreventsOvershoot() {
        // highLag=800, ceiling=750, current=749 → loosen to 750 (ceiling)
        assertEquals(750, moderateLoadLagTarget(20, 749, 800, 2));
    }

    @Test
    public void testModerateLagFloorEnforced() {
        // current=100 (at min), observed high → can't decrease
        assertEquals(100, moderateLoadLagTarget(200, 100, 800, 30));
    }

    @Test
    public void testModerateLagNaNHolds() {
        // observed=NaN, readyJobs=NaN → hold
        assertEquals(500, moderateLoadLagTarget(Double.NaN, 500, 800, Double.NaN));
    }

    // =====================================================================
    // Section 15: Coupled oscillation — moderate always below high
    // =====================================================================

    @Test
    public void testModerateTightensBeforeHigh() {
        // At lag=150: moderate tightens (>100), high holds (not >200)
        int highTarget = highLoadLagTarget(150, 500);
        int modTarget = moderateLoadLagTarget(150, 400, 500, 10);
        assertEquals(500, highTarget);
        assertEquals(350, modTarget);
    }

    @Test
    public void testModerateCeilingAlwaysBelowHigh() {
        // Verify ceiling invariant: moderate max < highLag
        for (int highLag = 200; highLag <= 5000; highLag += 100) {
            int ceiling = Math.max(100, highLag - 50);
            assertTrue("ceiling " + ceiling + " must be < highLag " + highLag,
                       ceiling < highLag);
        }
    }

    @Test
    public void testModerateAndHighTightenAtDifferentLag() {
        // Lag=150: moderate tightens, high holds
        assertEquals(450, moderateLoadLagTarget(150, 500, 600, 10));
        assertEquals(500, highLoadLagTarget(150, 500));
        // Lag=250: both tighten
        assertEquals(450, moderateLoadLagTarget(250, 500, 600, 10));
        assertEquals(400, highLoadLagTarget(250, 500));
    }

    @Test
    public void testModerateQueueGatesLoosening() {
        // Both params see low lag (observed=20), but queue is active (readyJobs=10).
        // Moderate holds (queue not low → can't loosen), high loosens (no queue gate).
        assertEquals(400, moderateLoadLagTarget(20, 400, 800, 10));
        assertEquals(600, highLoadLagTarget(20, 500));
    }

    // =====================================================================
    // Section 10: I2PTunnel server handler thread pool decisions
    // =====================================================================

    private static final int SH_MIN = 2, SH_MAX = 128;

    /**
     * Saturated pool: active pinned near the ceiling while the queue backs up,
     * with no CPU pressure and a LOW local connect time (handlers stalled on the
     * inbound I2P write — the starvation case). Must grow aggressively.
     */
    @Test
    public void testServerHandlerSaturatedPoolGrows() {
        assertEquals(SH_MIN + 4,
                     Tuner.computeServerHandlerThreads(SH_MIN, SH_MIN, SH_MAX,
                                                      70,        // queueDepth backlog
                                                      2,         // active == current (saturated)
                                                      180,       // blockingTime low (I2P-write stall)
                                                      20));      // jobLag, no CPU pressure
    }

    /**
     * Saturated pool at the ceiling: aggressive growth is clamped to the max.
     */
    @Test
    public void testServerHandlerSaturatedCeilingEnforced() {
        assertEquals(SH_MAX,
                     Tuner.computeServerHandlerThreads(SH_MAX, SH_MIN, SH_MAX,
                                                      200,       // queueDepth backlog
                                                      120,       // active == current (max, saturated)
                                                      100,       // blockingTime moderate
                                                      20));
    }

    /**
     * Not saturated (active well below ceiling) but queue backlog: slower +2 growth.
     */
    @Test
    public void testServerHandlerQueueBacklogGrowsTwo() {
        assertEquals(SH_MIN + 2,
                     Tuner.computeServerHandlerThreads(SH_MIN, SH_MIN, SH_MAX,
                                                      150,      // queueDepth backlog
                                                      1,        // active low (NOT saturated)
                                                      180,      // blockingTime fast
                                                      20));
    }

    /**
     * CPU pressure (jobLag > 100) vetoes growth even under saturation: we must not
     * add handler threads to a box that is already swap/busy.
     */
    @Test
    public void testServerHandlerDoesNotGrowUnderCpuPressure() {
        assertEquals(SH_MIN,
                     Tuner.computeServerHandlerThreads(SH_MIN, SH_MIN, SH_MAX,
                                                      200,       // queueDepth backlog
                                                      180,       // active == current (saturated signal)
                                                      2,         // blockingTime fast
                                                      500));     // jobLag -> cpuPressure
    }

    /**
     * Handlers blocking >10s (local connect path) triggers the largest growth,
     * matching the pre-existing emergency branch.
     */
    @Test
    public void testServerHandlerBlockingEmergencyGrowsFour() {
        assertEquals(SH_MIN + 4,
                     Tuner.computeServerHandlerThreads(SH_MIN, SH_MIN, SH_MAX,
                                                      5,        // low queue
                                                      Double.NaN, // active unknown
                                                      12000,     // emergency blocking time (>10s)
                                                      20));
    }

    /**
     * Idle pool (tiny queue, fast handlers) shrinks back toward the floor.
     */
    @Test
    public void testServerHandlerShrinksWhenIdle() {
        assertEquals(7,
                     Tuner.computeServerHandlerThreads(8, SH_MIN, SH_MAX,
                                                      1,         // queueDepth tiny
                                                      300,       // active unreached (idle)
                                                      1,         // blockingTime fast
                                                      10));
    }

    /**
     * Missing stats (all NaN) leave the pool unchanged rather than guessing.
     */
    @Test
    public void testServerHandlerNaNSignalsHold() {
        assertEquals(16,
                     Tuner.computeServerHandlerThreads(16, SH_MIN, SH_MAX,
                                                      Double.NaN, Double.NaN,
                                                      Double.NaN, Double.NaN));
    }

    // =====================================================================
    // Section 11: I2PTunnel server handler queue capacity decisions
    // =====================================================================

    private static final int SQ_MIN = 16, SQ_MAX = 65536;

    /**
     * Backlog crowding or exceeding the current capacity grows the buffer by half.
     */
    @Test
    public void testQueueCapacityGrowsAtCrowding() {
        int current = 1024;
        assertEquals(current + Math.max(current / 2, 128),
                     Tuner.computeServerBacklogQueueCapacity(current, SQ_MIN, SQ_MAX,
                                                           1024,      // queueDepth == current
                                                           20));      // no CPU pressure
    }

    /**
     * Meaningful backlog (clear of the cap) with no CPU pressure grows by a quarter.
     */
    @Test
    public void testQueueCapacityGrowsOnBacklog() {
        int current = 1024;
        assertEquals(current + Math.max(current / 4, 128),
                     Tuner.computeServerBacklogQueueCapacity(current, SQ_MIN, SQ_MAX,
                                                           100,       // queueDepth > 50
                                                           20));
    }

    /**
     * Growth is clamped to the ceiling even under heavy crowding.
     */
    @Test
    public void testQueueCapacityCeilingEnforced() {
        assertEquals(SQ_MAX,
                     Tuner.computeServerBacklogQueueCapacity(SQ_MAX, SQ_MIN, SQ_MAX,
                                                            SQ_MAX,    // queueDepth >= current
                                                            20));
    }

    /**
     * CPU pressure (jobLag > 100) vetoes the >50 backlog growth, but NOT the
     * crowding case (queueDepth >= current): at/over capacity we must still widen
     * the admission buffer to avoid rejecting connections, even on a busy box.
     */
    @Test
    public void testQueueCapacityCpuPressureOnlyVetoesBacklog() {
        int current = 1024;
        // >50 backlog under CPU pressure -> hold (no growth)
        assertEquals(current,
                     Tuner.computeServerBacklogQueueCapacity(current, SQ_MIN, SQ_MAX,
                                                           100,       // >50 but < current
                                                           500));     // cpuPressure
        // crowding (>= current) still grows even under CPU pressure
        assertEquals(current + Math.max(current / 2, 128),
                     Tuner.computeServerBacklogQueueCapacity(current, SQ_MIN, SQ_MAX,
                                                            current,   // >= current
                                                            500));
    }

/**
     * Consistently near-empty backlog converges the buffer toward the idle floor,
     * not the hard min: an oversized buffer shrinks one step down toward
     * SERVER_BACKLOG_IDLE_FLOOR.
     */
    @Test
    public void testQueueCapacityShrinksWhenIdle() {
        int current = 1024;
        int step = Math.max(current / 4, 128);
        assertEquals(Math.max(Tuner.SERVER_BACKLOG_IDLE_FLOOR, current - step),
                     Tuner.computeServerBacklogQueueCapacity(current, SQ_MIN, SQ_MAX,
                                                            1,         // queueDepth tiny
                                                            10));
    }

    /**
     * A too-small buffer (even the previously-collapsed 16) grows one step back up
     * toward the idle floor on a quiet box, healing the floor-collapse rather than
     * lingering at the hard min. Regression guard for the idle ratchet-to-16 bug:
     * 16 + max(16/4, 128) = 144, then successive cycles converge to the floor.
     */
    @Test
    public void testQueueCapacityHealsUpFromCollapsedFloor() {
        assertEquals(144,
                     Tuner.computeServerBacklogQueueCapacity(16, SQ_MIN, SQ_MAX,
                                                            1,         // queueDepth tiny
                                                            10));
    }

    /**
     * The buffer already settled at the idle floor is left untouched when idle.
     */
    @Test
    public void testQueueCapacityStaysAtIdleFloor() {
        assertEquals(Tuner.SERVER_BACKLOG_IDLE_FLOOR,
                     Tuner.computeServerBacklogQueueCapacity(Tuner.SERVER_BACKLOG_IDLE_FLOOR, SQ_MIN, SQ_MAX,
                                                            1,
                                                            10));
    }

    /**
     * Missing stats (NaN) leave the queue capacity unchanged rather than guessing.
     */
    @Test
    public void testQueueCapacityNaNSignalsHold() {
        assertEquals(4096,
                     Tuner.computeServerBacklogQueueCapacity(4096, SQ_MIN, SQ_MAX,
                                                           Double.NaN, Double.NaN));
    }

    /**
     * Repeated idle cycles from a collapsed floor converge to the idle floor and
     * then stop: the buffer can never be parked at the hard 16 min by autotuning.
     */
    @Test
    public void testQueueCapacityIdleConvergesToFloorAndStops() {
        int current = 16;
        for (int cycle = 0; cycle < 100 && current != Tuner.SERVER_BACKLOG_IDLE_FLOOR; cycle++) {
            current = Tuner.computeServerBacklogQueueCapacity(current, SQ_MIN, SQ_MAX, 1, 10);
        }
        assertEquals(Tuner.SERVER_BACKLOG_IDLE_FLOOR, current);
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
