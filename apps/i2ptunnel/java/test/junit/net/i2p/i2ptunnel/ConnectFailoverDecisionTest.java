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

    /**
     * Holds the tunnel-failover decision cases; carries no per-test state, so
     * the fresh instance JUnit builds per method costs nothing.
     */
    public ConnectFailoverDecisionTest() {}

    /**
     * Streaming's wording for a handshake that ran out of SYN retransmits, used
     * here as the contrast case: a leg that reached the fabric and failed, as
     * opposed to one that was refused before it started.
     */
    private static final String SYN_TIMEOUT = "Connection timed out: SYN not acknowledged";

    /**
     * A refusal exactly as streaming writes one: the wording plus the window that
     * is still to run.
     */
    private static final String REFUSED = I2PTunnelClientBase.CONNECT_COOLDOWN_ERROR +
                                          " * Cooldown left: 40s";

    // ---------- shouldContinueFailover ----------

    /** Legs remain worth trying while the pool is healthy and none is dead. */
    @Test
    public void testContinue_HealthyPoolWithinBudget() {
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 1, 0, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 2, 0, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 3, 1, false));
    }

    /** The walk stops at the configured leg count, never past it. */
    @Test
    public void testContinue_StopsAtTunnelCount() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 4, 0, false));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 5, 0, false));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(1, 1, 0, false));
    }

    /** A pool with no usable tunnel and nothing building cannot be retried. */
    @Test
    public void testContinue_DeadPoolStopsImmediately() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 1, 0, true));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 1, 1, true));
    }

    /** A timeout on an early leg must not strand the legs still worth trying. */
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

    /** A no-route failure walks the configured legs on the tunnel budget alone. */
    @Test
    public void testContinue_NonTimeoutFailuresUseTunnelBudget() {
        // NoRoute (not timeout) still walks configured failover legs
        assertEquals(2, I2PTunnelClientBase.MAX_TIMEOUT_FAILOVER);
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 2, 0, false));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 3, 0, false));
    }

    // ---------- shouldContinueFailover (poolBuilding) ----------

    /** Builds in flight neither extend nor shorten the walk. */
    @Test
    public void testContinue_PoolBuildingStillWalksAllLegs() {
        // poolBuilding no longer extends or shortens the walk — all legs tried
        int max = I2PTunnelClientBase.MAX_TIMEOUT_FAILOVER;
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(8, 1, max, false, true));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(8, 7, max + 5, false, true));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(8, 8, max, false, true));
    }

    /** The poolBuilding argument is compatibility only: same answer either way. */
    @Test
    public void testContinue_PoolNotBuildingSameAsBuilding() {
        // poolBuilding is retained for call-site compatibility only
        int max = I2PTunnelClientBase.MAX_TIMEOUT_FAILOVER;
        assertEquals(
            I2PTunnelClientBase.shouldContinueFailover(4, 1, max, false, false),
            I2PTunnelClientBase.shouldContinueFailover(4, 1, max, false, true));
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 3, max, false, false));
    }

    /** A dead pool outranks builds in flight. */
    @Test
    public void testContinue_PoolBuildingStillStopsOnDeadPool() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(8, 1, 0, true, true));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(8, 1, 1, true, true));
    }

    /** Builds in flight do not buy a leg beyond the configured count. */
    @Test
    public void testContinue_PoolBuildingStillStopsAtTunnelCount() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 4, 0, false, true));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 5, 1, false, true));
    }

    // ---------- shouldOuterRetryConnect ----------

    /** One outer retry is always allowed, so in-flight leg replacements land. */
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

    /** The general connect-retry count bounds the outer loop. */
    @Test
    public void testOuterRetry_StopsAtMaxRetries() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(
                I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES, 1, true, false, false, false));
    }

    /** A provably dead outbound pool ends the outer loop on the first failure. */
    @Test
    public void testOuterRetry_StopsWhenPoolDead() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(
                1, 1, true, true, false, false));
    }

    /** A second timeout wait is bought only while the pool is still building. */
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

    /** A non-timeout failure is bounded by the retry count, not the clock. */
    @Test
    public void testOuterRetry_NonTimeoutUsesGeneralBudget() {
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(
                1, 1, false, false, false, false));
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(
                I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES, 1, false, false, false, false));
    }

    // ---------- isConnectTimeout ----------

    /** Streaming's SYN give-up arrives wrapped, and must read as a timeout. */
    @Test
    public void testTimeout_NoRouteConnectionTimedOut() {
        // the live regression: NoRouteToHostException wrapping streaming SYN give-up
        assertTrue(I2PTunnelClientBase.isConnectTimeout(
                new NoRouteToHostException("Connection timed out")));
    }

    /** Both timeout wordings of an interrupted IO failure classify alike. */
    @Test
    public void testTimeout_InterruptedIOException() {
        assertTrue(I2PTunnelClientBase.isConnectTimeout(
                new InterruptedIOException("connect timed out")));
        assertTrue(I2PTunnelClientBase.isConnectTimeout(
                new InterruptedIOException("Read timed out")));
    }

    /** The timeout wording may sit in the cause rather than the exception. */
    @Test
    public void testTimeout_CauseChain() {
        NoRouteToHostException wrapped = new NoRouteToHostException("unreachable");
        wrapped.initCause(new InterruptedIOException("Connection timed out"));
        assertTrue(I2PTunnelClientBase.isConnectTimeout(wrapped));
    }

    /** A refusal, or a failure the classifier does not know, is not a timeout. */
    @Test
    public void testTimeout_NotATimeout() {
        assertFalse(I2PTunnelClientBase.isConnectTimeout(null));
        assertFalse(I2PTunnelClientBase.isConnectTimeout(
                new NoRouteToHostException("No route to host")));
        assertFalse(I2PTunnelClientBase.isConnectTimeout(
                new java.io.IOException("Connection refused")));
    }

    /** The 30s the per-leg connect windows are sized from by default. */
    @Test
    public void testDefaultConnectTimeoutIsThirtySeconds() {
        assertEquals(30_000L, I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT);
    }

    // ---------- cooldown refusals ----------

    /**
     * The regression: streaming refuses a connect on the destination's cooldown,
     * so every remaining leg of the same walk is refused the same instant. A walk
     * of four configured legs reported "4/4 tunnel legs" having used no outbound
     * tunnel and sent no SYN; 308 legs were refused this way against 49 that ran.
     */
    @Test
    public void testWalkStopsOnACooldownRefusal() {
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 1, 0, false, false, true));
        assertFalse(I2PTunnelClientBase.shouldContinueFailover(4, 2, 1, false, true, true));
        // the same walk with a leg that actually ran keeps walking
        assertTrue(I2PTunnelClientBase.shouldContinueFailover(4, 1, 1, false, false, false));
    }

    /** The refusal wording, pinned against streaming's, and its window suffix. */
    @Test
    public void testRefusalWordingMirrorsStreamings() {
        assertEquals("Connect deferred: destination in connect cooldown",
                     I2PTunnelClientBase.CONNECT_COOLDOWN_ERROR);
        assertEquals(" * Cooldown left: ", I2PTunnelClientBase.CONNECT_COOLDOWN_LEFT);
    }

    /**
     * A refusal is not a path failure: it says nothing about the outbound
     * tunnels the pool holds, and it is not a timeout either, so it must not be
     * counted against the timeout budget.
     */
    @Test
    public void testRefusalIsNeitherATimeoutNorAPathFailure() {
        NoRouteToHostException err = new NoRouteToHostException(REFUSED);
        assertTrue(I2PTunnelClientBase.isConnectCooldown(err));
        assertFalse(I2PTunnelClientBase.isConnectTimeout(err));
        assertFalse(I2PTunnelClientBase.isConnectCooldown(
                new NoRouteToHostException(SYN_TIMEOUT)));
        assertFalse(I2PTunnelClientBase.isConnectCooldown(null));
        // wrapped, as streaming's refusal reaches the proxy
        NoRouteToHostException wrapped = new NoRouteToHostException("connect failed");
        wrapped.initCause(new NoRouteToHostException(REFUSED));
        assertTrue(I2PTunnelClientBase.isConnectCooldown(wrapped));
    }

    /**
     * The window is what makes a refusal actionable: the caller can wait it out
     * against its own deadline instead of sleeping on a ladder that has no
     * relation to it. Anything without a window reads as -1, not as a guess.
     */
    @Test
    public void testRefusalWindowIsReadNotGuessed() {
        assertEquals(40_000L, I2PTunnelClientBase.connectCooldownMs(
                new NoRouteToHostException(REFUSED)));
        assertEquals(-1, I2PTunnelClientBase.connectCooldownMs(
                new NoRouteToHostException(I2PTunnelClientBase.CONNECT_COOLDOWN_ERROR)));
        assertEquals(-1, I2PTunnelClientBase.connectCooldownMs(
                new NoRouteToHostException(REFUSED.replace("40s", "40m"))));
        assertEquals(-1, I2PTunnelClientBase.connectCooldownMs(
                new NoRouteToHostException(SYN_TIMEOUT)));
        assertEquals(-1, I2PTunnelClientBase.connectCooldownMs(null));
    }

    /**
     * The walk's summary line has to distinguish legs that ran from legs that
     * were refused: "4/4 tunnel legs (4 timed out)" described a walk that tried
     * no tunnel at all, and named the refusals as timeouts.
     */
    @Test
    public void testWalkLineSeparatesRefusalsFromLegsThatRan() {
        String allRefused = I2PTunnelClientBase.walkFailureLine(0, 4, 0, 4, REFUSED);
        assertEquals("Connect refused before any SYN was sent -> "
                     + "Destination in connect cooldown * Refused: 4", allRefused);
        String oneRan = I2PTunnelClientBase.walkFailureLine(1, 0, 1, 4, SYN_TIMEOUT);
        assertEquals("Connect failed after 1/4 tunnel legs -> "
                     + SYN_TIMEOUT + " * Timed out: 1", oneRan);
        // a long reason puts the tallies on their own line rather than overrunning it
        String mixed = I2PTunnelClientBase.walkFailureLine(4, 3, 4, 7, SYN_TIMEOUT);
        assertEquals("Connect failed after 4/7 tunnel legs -> "
                     + SYN_TIMEOUT + "\n* Timed out: 4 * Refused: 3", mixed);
        for (String entry : new String[] {allRefused, oneRan, mixed}) {
            for (String emitted : entry.split("\n")) {
                assertTrue("over 100: " + emitted, emitted.length() <= 100);
            }
        }
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
                I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT, start + 9_999L, start, 4));
        assertEquals(10_000L, I2PTunnelClientBase.legConnectTimeoutMs(
                I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT, start + 10_000L, start, 4));
    }

    /**
     * Legs that run their whole budget are still bounded by the attempt cap - and
     * the cap now delivers the two legs it is sized for. Before the share was
     * divided, the first leg took the entire window, ran to streaming's SYN
     * give-up inside it, and this walk came out at one leg of four.
     */
    @Test
    public void testWalkStopsAtTheAttemptCap() {
        long start = 1_000_000L;
        long attempt = start + I2PTunnelClientBase.MAX_CONNECT_ATTEMPT_MS;
        assertEquals(I2PTunnelClientBase.MIN_FAILOVER_LEGS_PER_ATTEMPT,
                     walkLegs(start, attempt, 20_000L, 4));
    }

    /**
     * How many legs one walk starts when each one dies after legDeathMs. Mirrors
     * the loop in createI2PSocketWithFailover: size the leg from the share of the
     * budget left, stop when the walk says the leg is not startable, and charge
     * the leg's real cost against the clock.
     *
     * @param startMs when the walk begins, on an arbitrary clock
     * @param deadlineMs when the whole connect attempt ends, same clock
     * @param legDeathMs how long a leg survives before the walk gives it up
     * @param tunnelCount how many tunnels the client may try, the walk's cap
     * @return the number of legs the walk starts before the deadline
     */
    private static int walkLegs(long startMs, long deadlineMs, long legDeathMs,
                                int tunnelCount) {
        int legs = 0;
        long now = startMs;
        while (legs < tunnelCount) {
            long legMs = I2PTunnelClientBase.legConnectTimeoutMs(
                    I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT, deadlineMs, now,
                    tunnelCount - legs);
            if (legMs < 0) {break;}
            legs++;
            now += Math.min(legMs, legDeathMs);
        }
        return legs;
    }
}
