package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for the adaptive build-throttle helpers and tunable thresholds
 * added in the cascade-mitigation pass: concurrency throttle, stale build
 * pruning, first-hop failure history, and IB congestion gating.
 *
 * Tests exercise only the static/package-visible helpers and setters so
 * they run without a RouterContext.
 *
 * @since 0.9.71+
 */
public class BuildExecutorAdaptiveThrottleTest {

    /** Restore defaults after each test to avoid cross-test pollution. */
    @After
    public void restoreDefaults() {
        BuildExecutor.setStaleBuildThresholdPct(60);
        BuildExecutor.setFirstHopFailureCooldownMs(5 * 60 * 1000L);
        BuildExecutor.setFirstHopFailureThreshold(3);
        BuildExecutor.setConcurrencyThrottleThresholdPct(30);
    }

    // ---- Stale build pruning threshold ----

    @Test
    public void testStaleBuildDefault() {
        assertEquals(60, BuildExecutor.getStaleBuildThresholdPct());
    }

    @Test
    public void testStaleBuildClampedLow() {
        BuildExecutor.setStaleBuildThresholdPct(20);
        assertEquals(30, BuildExecutor.getStaleBuildThresholdPct());
    }

    @Test
    public void testStaleBuildClampedHigh() {
        BuildExecutor.setStaleBuildThresholdPct(90);
        assertEquals(80, BuildExecutor.getStaleBuildThresholdPct());
    }

    @Test
    public void testStaleBuildValidRange() {
        for (int pct = 30; pct <= 80; pct += 5) {
            BuildExecutor.setStaleBuildThresholdPct(pct);
            assertEquals(pct, BuildExecutor.getStaleBuildThresholdPct());
        }
    }

    // ---- First-hop failure cooldown ----

    @Test
    public void testFirstHopFailureCooldownDefault() {
        assertEquals(5 * 60 * 1000L, BuildExecutor.getFirstHopFailureCooldownMs());
    }

    @Test
    public void testFirstHopFailureCooldownClampedLow() {
        BuildExecutor.setFirstHopFailureCooldownMs(10_000);
        assertEquals(60_000, BuildExecutor.getFirstHopFailureCooldownMs());
    }

    @Test
    public void testFirstHopFailureCooldownClampedHigh() {
        BuildExecutor.setFirstHopFailureCooldownMs(900_000);
        assertEquals(600_000, BuildExecutor.getFirstHopFailureCooldownMs());
    }

    @Test
    public void testFirstHopFailureCooldownValidRange() {
        long[] validValues = {60_000, 120_000, 300_000, 600_000};
        for (long ms : validValues) {
            BuildExecutor.setFirstHopFailureCooldownMs(ms);
            assertEquals(ms, BuildExecutor.getFirstHopFailureCooldownMs());
        }
    }

    // ---- First-hop failure threshold ----

    @Test
    public void testFirstHopFailureThresholdDefault() {
        assertEquals(3, BuildExecutor.getFirstHopFailureThreshold());
    }

    @Test
    public void testFirstHopFailureThresholdClampedLow() {
        BuildExecutor.setFirstHopFailureThreshold(0);
        assertEquals(1, BuildExecutor.getFirstHopFailureThreshold());
    }

    @Test
    public void testFirstHopFailureThresholdClampedHigh() {
        BuildExecutor.setFirstHopFailureThreshold(11);
        assertEquals(10, BuildExecutor.getFirstHopFailureThreshold());
    }

    @Test
    public void testFirstHopFailureThresholdValidRange() {
        for (int i = 1; i <= 10; i++) {
            BuildExecutor.setFirstHopFailureThreshold(i);
            assertEquals(i, BuildExecutor.getFirstHopFailureThreshold());
        }
    }

    // ---- Concurrency throttle threshold ----

    @Test
    public void testConcurrencyThrottleDefault() {
        assertEquals(30, BuildExecutor.getConcurrencyThrottleThresholdPct());
    }

    @Test
    public void testConcurrencyThrottleClampedLow() {
        BuildExecutor.setConcurrencyThrottleThresholdPct(10);
        assertEquals(15, BuildExecutor.getConcurrencyThrottleThresholdPct());
    }

    @Test
    public void testConcurrencyThrottleClampedHigh() {
        BuildExecutor.setConcurrencyThrottleThresholdPct(60);
        assertEquals(50, BuildExecutor.getConcurrencyThrottleThresholdPct());
    }

    @Test
    public void testConcurrencyThrottleValidRange() {
        int[] validValues = {15, 20, 25, 30, 35, 40, 45, 50};
        for (int pct : validValues) {
            BuildExecutor.setConcurrencyThrottleThresholdPct(pct);
            assertEquals(pct, BuildExecutor.getConcurrencyThrottleThresholdPct());
        }
    }

    // ---- countsAsPoolFailure (supplements BuildExecutorPacingRulesTest) ----

    @Test
    public void testCountsAsPoolFailureAllResults() {
        // Exhaustive: verify every Result enum value
        for (BuildExecutor.Result r : BuildExecutor.Result.values()) {
            switch (r) {
                case SUCCESS:
                case DUP_ID:
                case REJECT:
                case NO_TUNNELS:
                case NO_NETDB:
                case SKIPPED:
                    assertFalse(r + " should not count as pool failure",
                                BuildExecutor.countsAsPoolFailure(r));
                    break;
                default:
                    assertTrue(r + " should count as pool failure",
                               BuildExecutor.countsAsPoolFailure(r));
                    break;
            }
        }
    }

    // ---- Effective target ----

    @Test
    public void testEffectiveTargetKeepsConfiguredWhenFlagged() {
        net.i2p.router.TunnelPoolSettings s = new net.i2p.router.TunnelPoolSettings(false);
        s.setQuantity(5);
        // keepConfiguredQuantity = true returns the configured value directly
        assertEquals(5, BuildExecutor.effectiveTarget(null, s, true));
    }

    @Test
    public void testEffectiveTargetFloorWhenNotFlagged() {
        net.i2p.router.TunnelPoolSettings s = new net.i2p.router.TunnelPoolSettings(false);
        s.setQuantity(1);
        // Without context (null), getTunnelTargetMin returns default 2,
        // getTunnelTargetBuffer returns 0
        int result = BuildExecutor.effectiveTarget(null, s, false);
        // max(2, max(2, 1+0)) = 2
        assertTrue("floor should be at least 2", result >= 2);
    }

    @Test
    public void testEffectiveTargetAddsBufferWhenNotFlagged() {
        net.i2p.router.TunnelPoolSettings s = new net.i2p.router.TunnelPoolSettings(false);
        s.setQuantity(4);
        // With null context, buffer is 0, so target = max(2, max(2, 4+0)) = 4
        int result = BuildExecutor.effectiveTarget(null, s, false);
        assertTrue("target should be at least configured qty", result >= 4);
    }
}
