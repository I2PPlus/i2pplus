package net.i2p.i2ptunnel;

import org.junit.Test;

import java.lang.reflect.Proxy;

import net.i2p.client.streaming.I2PSocketOptions;

import static org.junit.Assert.*;

/**
 * Unit tests for the shared request connect deadline - the budget, its expiry,
 * clamping each step to the remaining budget, the per-leg connect timeouts, and
 * the pool-building grace - plus the single pool-snapshot outer-retry decision
 * it gates.
 */
public class ConnectDeadlineTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one. The class holds no per-test state; every case calls a static decision
     * with its own clock readings.
     *
     * @since 0.9.72+
     */
    public ConnectDeadlineTest() {}

    // ---------- budget and expiry ----------

    /**
     * The request budget is the documented 120s, and a deadline derived from a
     * start time is that budget added to it.
     */
    @Test
    public void testDeadlineBudget() {
        assertEquals(120 * 1000, I2PTunnelClientBase.REQUEST_CONNECT_DEADLINE_MS);
        assertEquals(1_000 + I2PTunnelClientBase.REQUEST_CONNECT_DEADLINE_MS,
                     I2PTunnelClientBase.connectDeadlineFrom(1_000));
    }

    /**
     * A deadline is spent at the moment it reads equal to the clock, not one
     * millisecond later, so the boundary is inclusive.
     */
    @Test
    public void testExpiredAtExactBoundary() {
        long deadline = 10_000;
        assertFalse(I2PTunnelClientBase.isDeadlineExpired(deadline, 9_999));
        assertTrue(I2PTunnelClientBase.isDeadlineExpired(deadline, 10_000));
        assertTrue(I2PTunnelClientBase.isDeadlineExpired(deadline, 10_001));
    }

    /**
     * The sentinel for "no shared budget" never expires, whatever the clock
     * reads, so a caller that has not adopted the budget keeps the old
     * unbounded behaviour.
     */
    @Test
    public void testNoDeadlineNeverExpires() {
        assertFalse(I2PTunnelClientBase.isDeadlineExpired(I2PTunnelClientBase.NO_DEADLINE,
                                                          System.currentTimeMillis()));
        assertFalse(I2PTunnelClientBase.isDeadlineExpired(I2PTunnelClientBase.NO_DEADLINE,
                                                          Long.MAX_VALUE - 1));
    }

    // ---------- clamping ----------

    /**
     * Well inside the budget, a step is given the timeout it asked for - the
     * clamp must not shrink a step that fits.
     */
    @Test
    public void testClampWithinBudgetReturnsRequested() {
        assertEquals(5_000, I2PTunnelClientBase.clampToDeadlineMs(5_000, 10_000, 2_000));
    }

    /**
     * Near the end of the budget, the step takes only what is left, so a step
     * cannot outlive the request that started it.
     */
    @Test
    public void testClampShortensToRemainingBudget() {
        assertEquals(3_000, I2PTunnelClientBase.clampToDeadlineMs(5_000, 10_000, 7_000));
    }

    /**
     * At or past the deadline the clamp returns zero, the value callers read as
     * "skip this step" rather than a timeout of their own.
     */
    @Test
    public void testClampAtOrPastBoundaryIsZero() {
        assertEquals(0, I2PTunnelClientBase.clampToDeadlineMs(5_000, 10_000, 10_000));
        assertEquals(0, I2PTunnelClientBase.clampToDeadlineMs(5_000, 10_000, 12_000));
    }

    /**
     * With no shared deadline the requested timeout passes through untouched,
     * which is the legacy behaviour for a caller outside the budget.
     */
    @Test
    public void testClampNoDeadlineReturnsRequested() {
        assertEquals(30_000, I2PTunnelClientBase.clampToDeadlineMs(30_000,
                     I2PTunnelClientBase.NO_DEADLINE, System.currentTimeMillis()));
    }

    /**
     * Every step a request can spend - naming, the retry sleep, and a leg's
     * connect timeout - is clamped to what the request has left, and both naming
     * budgets are small enough to fit inside the request budget at its start.
     * This is the invariant that stops naming from consuming the whole connect
     * budget before a SYN is sent.
     */
    @Test
    public void testNamingAndSleepClampToRemainingBudget() {
        long now = 1_000;
        long deadline = I2PTunnelClientBase.connectDeadlineFrom(now);
        // At request start the full naming budget is available.
        assertEquals(I2PTunnelHTTPClient.NAMING_SERVICE_TIMEOUT_MS,
                     I2PTunnelClientBase.clampToDeadlineMs(
                             I2PTunnelHTTPClient.NAMING_SERVICE_TIMEOUT_MS, deadline, now));
        assertEquals(I2PTunnelHTTPClient.NAMING_IN_SESSION_TIMEOUT_MS,
                     I2PTunnelClientBase.clampToDeadlineMs(
                             I2PTunnelHTTPClient.NAMING_IN_SESSION_TIMEOUT_MS, deadline, now));
        // Naming budgets always fit inside the request budget.
        assertTrue(I2PTunnelHTTPClient.NAMING_IN_SESSION_TIMEOUT_MS <=
                   I2PTunnelClientBase.REQUEST_CONNECT_DEADLINE_MS);
        assertTrue(I2PTunnelHTTPClient.NAMING_SERVICE_TIMEOUT_MS <=
                   I2PTunnelClientBase.REQUEST_CONNECT_DEADLINE_MS);
        // Retry sleep shrinks once the deadline nears.
        long delay = I2PTunnelHTTPClient.getConnectRetryDelayMs(1);
        assertEquals(250, I2PTunnelClientBase.clampToDeadlineMs(delay, 10_000, 9_750));
        assertEquals(0, I2PTunnelClientBase.clampToDeadlineMs(delay, 10_000, 10_000));
        // Per-leg connect timeout shrinks too.
        assertEquals(4_000, I2PTunnelClientBase.clampToDeadlineMs(30_000, 10_000, 6_000));
    }

    // ---------- per-leg connect timeout ----------

    /**
     * With no shared deadline the leg keeps the timeout it was given, and a
     * caller that asked for none keeps zero, meaning unlimited - the legacy
     * pairing of "no budget" and "no timeout".
     */
    @Test
    public void testLegTimeoutNoDeadlinePassesThrough() {
        long now = System.currentTimeMillis();
        assertEquals(30_000, I2PTunnelClientBase.legConnectTimeoutMs(
                30_000, I2PTunnelClientBase.NO_DEADLINE, now));
        // legacy: no timeout requested and no shared budget keeps 0 (unlimited)
        assertEquals(0, I2PTunnelClientBase.legConnectTimeoutMs(
                0, I2PTunnelClientBase.NO_DEADLINE, now));
    }

    /**
     * A leg options object can carry a zero connect timeout when streaming has
     * not established an effective window yet. Against a real deadline that must
     * not read as "wait forever", so the default window is substituted.
     */
    @Test
    public void testLegTimeoutSubstitutesDefaultWhenNoneRequested() {
        long now = 1_000;
        long deadline = now + 120_000;
        assertEquals(I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT,
                     I2PTunnelClientBase.legConnectTimeoutMs(0, deadline, now));
        assertEquals(I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT,
                     I2PTunnelClientBase.legConnectTimeoutMs(-1, deadline, now));
    }

    /**
     * A leg takes the remaining budget when that is shorter than it asked for,
     * keeps its own value when the budget is larger, and still runs when what is
     * left is exactly the 10s floor streaming enforces.
     */
    @Test
    public void testLegTimeoutClampedToRemainingBudget() {
        // 40s remaining shrinks a 60s request
        assertEquals(40_000, I2PTunnelClientBase.legConnectTimeoutMs(60_000, 50_000, 10_000));
        // budget larger than the request: the request is honored
        assertEquals(30_000, I2PTunnelClientBase.legConnectTimeoutMs(30_000, 130_000, 10_000));
        // exactly the 10s floor still runs
        assertEquals(10_000, I2PTunnelClientBase.legConnectTimeoutMs(30_000, 20_000, 10_000));
    }

    /**
     * Below the 10s floor the leg is doomed - streaming would cut it short
     * anyway - so the decision returns -1 and the walk skips the leg instead of
     * starting an attempt that cannot finish.
     */
    @Test
    public void testLegTimeoutSkipsBelowFloor() {
        assertEquals(-1, I2PTunnelClientBase.legConnectTimeoutMs(30_000, 19_999, 10_000));
        assertEquals(-1, I2PTunnelClientBase.legConnectTimeoutMs(30_000, 10_000, 10_000));
        assertEquals(-1, I2PTunnelClientBase.legConnectTimeoutMs(0, 10_000, 12_000));
    }

    // ---------- per-leg cap and per-attempt budget ----------

    /**
     * The leg budget only becomes the real connect window if it is also the
     * cap: streaming scales the timeout by the Tuner's multiplier (up to 200%)
     * and applies the cap afterwards. Measured on a live router, the 30s this
     * class asks for ran as a 57,950ms wait.
     */
    @Test
    public void testLegCapPinsTheWindowToTheBudget() {
        assertEquals(15_000, I2PTunnelClientBase.legConnectCapMs(15_000, 0));
        // a router-wide-style default cap is pulled down to the leg budget
        assertEquals(15_000, I2PTunnelClientBase.legConnectCapMs(15_000, 75_000));
        // an explicit tighter cap is never loosened
        assertEquals(8_000, I2PTunnelClientBase.legConnectCapMs(15_000, 8_000));
        // no leg budget: leave whatever the caller carries, including 0 = "use the default"
        assertEquals(75_000, I2PTunnelClientBase.legConnectCapMs(0, 75_000));
        assertEquals(0, I2PTunnelClientBase.legConnectCapMs(-1, 0));
    }

    /**
     * One failover walk may not spend the whole request budget, or the retry
     * loop that follows it has nothing to spend and the request dies on a single
     * long handshake.
     */
    @Test
    public void testAttemptDeadlineCapsOneWalk() {
        long start = 100_000;
        long request = start + 120_000;
        assertEquals(start + I2PTunnelClientBase.MAX_CONNECT_ATTEMPT_MS,
                     I2PTunnelClientBase.attemptDeadlineMs(request, start));
        // a request already nearer its end is not extended by the attempt cap
        assertEquals(start + 5_000, I2PTunnelClientBase.attemptDeadlineMs(start + 5_000, start));
        assertEquals(I2PTunnelClientBase.NO_DEADLINE,
                     I2PTunnelClientBase.attemptDeadlineMs(I2PTunnelClientBase.NO_DEADLINE, start));
    }

    /**
     * The empty-reconnect loop runs under its own wall-clock deadline, and every
     * attempt inside it gets the same walk the first connect gets. Passing the
     * loop deadline through unmetered left streaming sizing each leg from its own
     * 30s default, so one request asked the same destination for 15,000ms and
     * then 30,000ms, and the longer leg bought half as many retries.
     */
    @Test
    public void testReconnectAttemptsShareTheFirstConnectWindow() {
        long start = 1_000_000;
        long loop = start + I2PTunnelHTTPClient.EMPTY_RECONNECT_DEADLINE_MS;
        long attempt = I2PTunnelClientBase.attemptDeadlineMs(loop, start);
        assertEquals(start + I2PTunnelClientBase.MAX_CONNECT_ATTEMPT_MS, attempt);
        assertEquals(I2PTunnelClientBase.MAX_CONNECT_ATTEMPT_MS, attempt - start);
        // The loop still outlives the attempt, so room is left for the retries.
        assertTrue(loop - attempt >= I2PTunnelClientBase.MAX_CONNECT_ATTEMPT_MS);
    }

    /**
     * The attempt budget has to leave a startable leg: streaming floors a
     * connect window at 10s and the walk skips a leg with less than that left.
     */
    @Test
    public void testAttemptBudgetStillBuysALeg() {
        assertTrue(I2PTunnelClientBase.MAX_CONNECT_ATTEMPT_MS >= I2PTunnelClientBase.MIN_CONNECT_LEG_MS);
        assertTrue(I2PTunnelClientBase.MAX_CONNECT_ATTEMPT_MS <= I2PTunnelClientBase.MAX_CONNECT_SPEND_MS);
        // 30s of spend, 15s per attempt: the sizing must pay for the second try
        assertEquals(2, I2PTunnelClientBase.maxTimeoutConnectRetries(105_000, 15_000, false));
    }

    // ---------- pool-building grace ----------

    /**
     * A deadline that has just expired while the client pool has builds in
     * flight is extended by the grace window, because those builds may still
     * land and deliver the connection the request is waiting for.
     */
    @Test
    public void testGraceExtendsExpiredDeadlineWhileBuilding() {
        assertEquals(10_000 + I2PTunnelClientBase.POOL_BUILD_DEADLINE_GRACE_MS,
                     I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 10_000, 0, false));
        assertEquals(10_000 + I2PTunnelClientBase.POOL_BUILD_DEADLINE_GRACE_MS,
                     I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 12_000, 0, false));
    }

    /**
     * While the deadline is still alive there is nothing to extend - the grace
     * answers an expiry, not a slow start.
     */
    @Test
    public void testGraceNotGrantedWhileDeadlineAlive() {
        assertEquals(10_000, I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 9_999, 0, false));
    }

    /**
     * The grace is for a pool that is rebuilding. A pool holding tunnels, one
     * that is dead, and one whose state cannot be read all keep the original
     * deadline, so the extension cannot become a general timeout extension.
     */
    @Test
    public void testGraceNotGrantedForHealthyOrUnknownPool() {
        assertEquals(10_000, I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 10_000, 1, false));
        assertEquals(10_000, I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 10_000, -1, false));
        assertEquals(10_000, I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 10_000, -2, false));
    }

    /**
     * The grace is granted once per request. A second expiry with the pool still
     * building does not re-extend, which keeps the worst case at budget plus one
     * grace instead of an unbounded sequence of extensions.
     */
    @Test
    public void testGraceOnlyOncePerRequest() {
        assertEquals(10_000, I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 10_000, 0, true));
    }

    // ---------- outer retry ----------

    /**
     * An expired deadline ends the request at once, even with timeout budget
     * left and the pool building - the budget is what bounds the browser's wait,
     * and the grace above is the only exception to it.
     */
    @Test
    public void testDeadlineExpiredStopsRetry() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, 0, true));
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 0, false, 1, true));
    }

    /**
     * One {@code poolState()} reading must decide exactly what the older pair of
     * queries decided, so collapsing the two calls into one snapshot cannot
     * change behaviour. Exhaustive over every pool state, attempt count,
     * timeout count and timeout flag the caller can present.
     */
    @Test
    public void testSingleSnapshotDerivesBothPoolFlags() {
        for (int state = -2; state <= 1; state++) {
            for (int attempts = 1; attempts <= 8; attempts++) {
                for (int timeouts = 0; timeouts <= 4; timeouts++) {
                    for (boolean timedOut : new boolean[]{true, false}) {
                        boolean legacy = I2PTunnelClientBase.shouldOuterRetryConnect(
                                attempts, timeouts, timedOut, state <= -1, state == 0, false);
                        boolean snapshotted = I2PTunnelClientBase.shouldOuterRetryConnect(
                                attempts, timeouts, timedOut, state, false);
                        assertEquals("state=" + state + " attempts=" + attempts +
                                     " timeouts=" + timeouts + " timedOut=" + timedOut,
                                     legacy, snapshotted);
                    }
                }
            }
        }
    }

    /**
     * A pool that is dead, or whose state cannot be read, stops the outer retry
     * from the snapshot alone - no further attempt can reach the destination.
     */
    @Test
    public void testDownPoolStopsRetryFromSnapshot() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 0, false, -1, false));
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, -2, false));
    }

    /**
     * How the timeout budget is spent depends on the pool. A healthy pool that
     * just timed out once is not re-entered, because every leg already failed
     * fast as a timeout; a rebuilding pool is given one more attempt, since its
     * in-flight builds may still land, and stops at the second timeout; a
     * non-timeout failure on a healthy pool keeps retrying inside its budget.
     */
    @Test
    public void testTimeoutBudgetFromSnapshot() {
        // Healthy pool: the first timeout already ends the retry loop — the
        // walk is not re-entered while every leg failed fast as a timeout.
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, 1, false));
        // Building pool: the in-flight builds may still complete, so the
        // first timeout waits once more; the second timeout stops.
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, 0, false));
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 2, true, 0, false));
        // Non-timeout failure on a healthy pool keeps retrying within budget.
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(2, 0, false, 1, false));
    }

    /**
     * The attempt cap is absolute: reaching it ends the retry loop even with
     * deadline left and a healthy pool, so a destination that keeps refusing
     * cannot consume the whole request budget.
     */
    @Test
    public void testMaxRetriesStopsEvenWithDeadlineRemaining() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(
                I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES, 0, false, 1, false));
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(
                I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES - 1, 0, false, 1, false));
    }

    /**
     * The per-leg timeout is carried on the caller's options object, so the
     * failover walk must hand the caller's own value back on every exit path.
     * A shrunken value left behind would silently shorten any later connect
     * that reuses those options. A failure that no pure function test can see.
     *
     * <p>I2PSocketOptions has 12 methods, so a proxy stands in for it and
     * records just the two the walk uses.
     */
    @Test
    public void testCallerOptionsAreRestoredAfterTheWalk() {
        long[] held = {60_000};
        I2PSocketOptions opt = (I2PSocketOptions) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {I2PSocketOptions.class},
                (proxy, method, argv) -> {
                    if ("getConnectTimeout".equals(method.getName())) {return held[0];}
                    if ("setConnectTimeout".equals(method.getName())) {
                        held[0] = (Long) argv[0];
                        return null;
                    }
                    return null;
                });
        long caller = opt.getConnectTimeout();
        try {
            // one leg: 40s of a 50s deadline is left, shrinking 60s to 40s
            long leg = I2PTunnelClientBase.legConnectTimeoutMs(opt.getConnectTimeout(), 50_000, 10_000);
            assertEquals(40_000, leg);
            opt.setConnectTimeout(leg);
            assertEquals(40_000, opt.getConnectTimeout());
        } finally {
            opt.setConnectTimeout(caller);
        }
        assertEquals("caller's value must survive the walk",
                60_000, opt.getConnectTimeout());
    }
}
