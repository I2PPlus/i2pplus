package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the sliding window build-result tracker and proportional
 * per-iteration cap that replaced the counter-halving approach.
 *
 * @since 0.9.71+
 */
public class BuildExecutorSlidingWindowTest {

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    // ---- Sliding window basics ----

    @Test
    public void testWindowInitiallyEmpty() {
        BuildExecutor exec = newBuildExecutor();
        assertEquals(0, exec.getWindowCount());
    }

    // ---- Proportional per-iteration cap (calls real static method) ----

    @Test
    public void testCapFullSpeedWhenLowTimeoutRate() {
        assertEquals(4, BuildExecutor.calculatePerIterationCap(0.0));
    }

    @Test
    public void testCapModerateAtThresholdBoundary() {
        assertEquals(3, BuildExecutor.calculatePerIterationCap(0.20));
    }

    @Test
    public void testCapCautiousAtHighRate() {
        assertEquals(2, BuildExecutor.calculatePerIterationCap(0.40));
    }

    @Test
    public void testCapMinimumAtExtremeRate() {
        assertEquals(1, BuildExecutor.calculatePerIterationCap(0.60));
    }

    @Test
    public void testCapTransitionAtRestoreThreshold() {
        assertEquals(4, BuildExecutor.calculatePerIterationCap(0.15));
    }

    @Test
    public void testCapTransitionAtThrottleThreshold() {
        assertEquals(3, BuildExecutor.calculatePerIterationCap(0.30));
    }

    @Test
    public void testCapTransitionAt50Percent() {
        assertEquals(2, BuildExecutor.calculatePerIterationCap(0.50));
    }

    // ---- Adaptive concurrency is wired into allowed() ----

    @Test
    public void testAdaptiveConcurrencyInitialValue() {
        BuildExecutor exec = newBuildExecutor();
        assertEquals(BuildExecutor.getMaxConcurrentBuilds(),
                     exec.getAdaptiveMaxConcurrentBuilds());
    }

    // ---- Helpers ----

    private BuildExecutor newBuildExecutor() {
        return new BuildExecutor(_ctx,
            new TunnelPoolManager(_ctx), new GhostPeerManager(_ctx));
    }
}
