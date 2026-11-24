package net.i2p.i2ptunnel;

import java.io.InterruptedIOException;
import java.net.NoRouteToHostException;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the pure tunnel-failover decision helpers extracted from
 * I2PTunnelClientBase.createI2PSocketWithFailover: dead-pool fail-fast,
 * timeout-failure cap, and timeout classification.
 */
public class ConnectFailoverDecisionTest {

    // ---------- shouldContinueFailover ----------

    @Test
    public void testContinue_HealthyPoolWithinBudget() {
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 1, 0, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 2, 0, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 3, 1, false));
    }

    @Test
    public void testContinue_StopsAtTunnelCount() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 4, 0, false));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 5, 0, false));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(1, 1, 0, false));
    }

    @Test
    public void testContinue_DeadPoolStopsImmediately() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 1, 0, true));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 1, 1, true));
    }

    @Test
    public void testContinue_TimeoutStillWalksAllLegs() {
        // timeout on early legs must not stop the walk — remaining healthy
        // legs are worth trying (previously capped at MAX_TIMEOUT_FAILOVER)
        int max = I2PTunnelClientBase.MAX_TIMEOUT_FAILOVER;
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 1, max, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 2, max + 5, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(8, 3, 10, false));
        // only stops at the configured tunnel count
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 4, max, false));
    }

    @Test
    public void testContinue_NonTimeoutFailuresUseTunnelBudget() {
        // NoRoute (not timeout) still walks configured failover legs
        assertEquals(2, I2PTunnelClientBase.MAX_TIMEOUT_FAILOVER);
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 2, 0, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 3, 0, false));
    }

    // ---------- shouldContinueFailover (poolBuilding) ----------

    @Test
    public void testContinue_PoolBuildingStillWalksAllLegs() {
        // poolBuilding no longer extends or shortens the walk — all legs tried
        int max = I2PTunnelClientBase.MAX_TIMEOUT_FAILOVER;
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(8, 1, max, false, true));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(8, 7, max + 5, false, true));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(8, 8, max, false, true));
    }

    @Test
    public void testContinue_PoolNotBuildingSameAsBuilding() {
        // poolBuilding is retained for call-site compatibility only
        int max = I2PTunnelClientBase.MAX_TIMEOUT_FAILOVER;
        assertEquals(
            I2PTunnelClientBase.shouldContinueFailover(4, 1, max, false, false),
            I2PTunnelClientBase.shouldContinueFailover(4, 1, max, false, true));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 3, max, false, false));
    }

    @Test
    public void testContinue_PoolBuildingStillStopsOnDeadPool() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(8, 1, 0, true, true));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(8, 1, 1, true, true));
    }

    @Test
    public void testContinue_PoolBuildingStillStopsAtTunnelCount() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 4, 0, false, true));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 5, 1, false, true));
    }

    // ---------- shouldOuterRetryConnect ----------

    @Test
    public void testOuterRetry_AlwaysAllowsOneTimeoutRetry() {
        // even a healthy pool gets one outer retry so in-flight leg
        // replacements can complete before the browser request fails
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(
                1, 1, false, false, false, false));
        // poolBuilding does not block a non-timeout retry
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(
                1, 1, false, false, true, false));
    }

    @Test
    public void testOuterRetry_StopsAtMaxRetries() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(
                I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES, 1, true, false, false, false));
    }

    @Test
    public void testOuterRetry_StopsWhenPoolDead() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(
                1, 1, true, true, false, false));
    }

    @Test
    public void testOuterRetry_TimeoutBudgetDependsOnPoolBuilding() {
        // healthy pool: one timeout wait is enough, no second
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(
                1, 1, true, false, false, false));
        // mid-build: after the first timeout still allow one more wait
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(
                1, 1, true, false, true, false));
        // mid-build: stop after the second timeout wait
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(
                1, 2, true, false, true, false));
    }

    @Test
    public void testOuterRetry_NonTimeoutUsesGeneralBudget() {
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(
                1, 1, false, false, false, false));
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(
                I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES, 1, false, false, false, false));
    }

    // ---------- isConnectTimeout ----------

    @Test
    public void testTimeout_NoRouteConnectionTimedOut() {
        // the live regression: NoRouteToHostException wrapping streaming SYN give-up
        assertTrue(I2PTunnelClientBase.isConnectTimeout(
                new NoRouteToHostException("Connection timed out")));
    }

    @Test
    public void testTimeout_InterruptedIOException() {
        assertTrue(I2PTunnelClientBase.isConnectTimeout(
                new InterruptedIOException("connect timed out")));
        assertTrue(I2PTunnelClientBase.isConnectTimeout(
                new InterruptedIOException("Read timed out")));
    }

    @Test
    public void testTimeout_CauseChain() {
        NoRouteToHostException wrapped = new NoRouteToHostException("unreachable");
        wrapped.initCause(new InterruptedIOException("Connection timed out"));
        assertTrue(I2PTunnelClientBase.isConnectTimeout(wrapped));
    }

    @Test
    public void testTimeout_NotATimeout() {
        assertFalse(I2PTunnelClientBase.isConnectTimeout(null));
        assertFalse(I2PTunnelClientBase.isConnectTimeout(
                new NoRouteToHostException("No route to host")));
        assertFalse(I2PTunnelClientBase.isConnectTimeout(
                new java.io.IOException("Connection refused")));
    }

    @Test
    public void testDefaultConnectTimeoutIsThirtySeconds() {
        assertEquals(30_000L, I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT);
    }

    // ---------- attempt window vs. leg floor ----------

    /**
     * The regression: legs that die on streaming's SYN phase must still leave a
     * second leg startable. Measured live on 2026-11-24 a leg gave up at 8.8s
     * inside a 15s attempt window, 6.2s was left — under the 10s leg floor — and
     * legs 2-4 were skipped for a pool that was holding four outbound tunnels.
     */
    @Test
    public void testWalkReachesASecondLegWhenLegsDieFast() {
        long start = 1_000_000L;
        long request = start + I2PTunnelHTTPClient.REQUEST_CONNECT_DEADLINE_MS;
        long attempt = I2PTunnelClientBase.attemptDeadlineMs(request, start);
        assertEquals(2, walkLegs(start, attempt, 8_800L, 4));
        // the window that caused it, kept as the contrast
        assertEquals(1, walkLegs(start, start + 15_000L, 8_800L, 4));
    }

    /** A walk never starts a leg streaming would cut short. */
    @Test
    public void testWalkSkipsALegWithLessThanTheFloorLeft() {
        long start = 1_000_000L;
        assertEquals(-1, I2PTunnelClientBase.legConnectTimeoutMs(
                I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT, start + 9_999L, start));
        assertEquals(10_000L, I2PTunnelClientBase.legConnectTimeoutMs(
                I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT, start + 10_000L, start));
    }

    /** Legs that run their whole window are still bounded by the attempt cap. */
    @Test
    public void testWalkStopsAtTheAttemptCap() {
        long start = 1_000_000L;
        long attempt = start + I2PTunnelClientBase.MAX_CONNECT_ATTEMPT_MS;
        assertEquals(1, walkLegs(start, attempt, 20_000L, 4));
    }

    /**
     * How many legs one walk starts when each one dies after legDeathMs. Mirrors
     * the loop in createI2PSocketWithFailover: size the leg from the budget left,
     * stop when the walk says the leg is not startable, and charge the leg's real
     * cost against the clock.
     */
    private static int walkLegs(long startMs, long deadlineMs, long legDeathMs,
                                int tunnelCount) {
        int legs = 0;
        long now = startMs;
        while (legs < tunnelCount) {
            long legMs = I2PTunnelClientBase.legConnectTimeoutMs(
                    I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT, deadlineMs, now);
            if (legMs < 0) {break;}
            legs++;
            now += Math.min(legMs, legDeathMs);
        }
        return legs;
    }
}
