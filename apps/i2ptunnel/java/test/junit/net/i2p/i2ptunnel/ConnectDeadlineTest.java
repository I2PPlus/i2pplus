package net.i2p.i2ptunnel;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 *  Unit tests for the shared request connect deadline (budget, expiry,
 *  clamping, per-leg connect timeouts, and the pool-building grace), plus
 *  the single pool-snapshot outer-retry decision it gates.
 */
public class ConnectDeadlineTest {

    // ---------- budget and expiry ----------

    @Test
    public void testDeadlineBudget() {
        assertEquals(120 * 1000, I2PTunnelClientBase.REQUEST_CONNECT_DEADLINE_MS);
        assertEquals(1_000 + I2PTunnelClientBase.REQUEST_CONNECT_DEADLINE_MS,
                     I2PTunnelClientBase.connectDeadlineFrom(1_000));
    }

    @Test
    public void testExpiredAtExactBoundary() {
        long deadline = 10_000;
        assertFalse(I2PTunnelClientBase.isDeadlineExpired(deadline, 9_999));
        assertTrue(I2PTunnelClientBase.isDeadlineExpired(deadline, 10_000));
        assertTrue(I2PTunnelClientBase.isDeadlineExpired(deadline, 10_001));
    }

    @Test
    public void testNoDeadlineNeverExpires() {
        assertFalse(I2PTunnelClientBase.isDeadlineExpired(I2PTunnelClientBase.NO_DEADLINE,
                                                          System.currentTimeMillis()));
        assertFalse(I2PTunnelClientBase.isDeadlineExpired(I2PTunnelClientBase.NO_DEADLINE,
                                                          Long.MAX_VALUE - 1));
    }

    // ---------- clamping ----------

    @Test
    public void testClampWithinBudgetReturnsRequested() {
        assertEquals(5_000, I2PTunnelClientBase.clampToDeadlineMs(5_000, 10_000, 2_000));
    }

    @Test
    public void testClampShortensToRemainingBudget() {
        assertEquals(3_000, I2PTunnelClientBase.clampToDeadlineMs(5_000, 10_000, 7_000));
    }

    @Test
    public void testClampAtOrPastBoundaryIsZero() {
        assertEquals(0, I2PTunnelClientBase.clampToDeadlineMs(5_000, 10_000, 10_000));
        assertEquals(0, I2PTunnelClientBase.clampToDeadlineMs(5_000, 10_000, 12_000));
    }

    @Test
    public void testClampNoDeadlineReturnsRequested() {
        assertEquals(30_000, I2PTunnelClientBase.clampToDeadlineMs(30_000,
                     I2PTunnelClientBase.NO_DEADLINE, System.currentTimeMillis()));
    }

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

    @Test
    public void testLegTimeoutNoDeadlinePassesThrough() {
        long now = System.currentTimeMillis();
        assertEquals(30_000, I2PTunnelClientBase.legConnectTimeoutMs(
                30_000, I2PTunnelClientBase.NO_DEADLINE, now));
        // legacy: no timeout requested and no shared budget keeps 0 (unlimited)
        assertEquals(0, I2PTunnelClientBase.legConnectTimeoutMs(
                0, I2PTunnelClientBase.NO_DEADLINE, now));
    }

    @Test
    public void testLegTimeoutSubstitutesDefaultWhenNoneRequested() {
        // streaming can carry a 0 (not-yet-established) connect timeout;
        // against a real deadline that must not mean "wait forever"
        long now = 1_000;
        long deadline = now + 120_000;
        assertEquals(I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT,
                     I2PTunnelClientBase.legConnectTimeoutMs(0, deadline, now));
        assertEquals(I2PTunnelClientBase.DEFAULT_CONNECT_TIMEOUT,
                     I2PTunnelClientBase.legConnectTimeoutMs(-1, deadline, now));
    }

    @Test
    public void testLegTimeoutClampedToRemainingBudget() {
        // 40s remaining shrinks a 60s request
        assertEquals(40_000, I2PTunnelClientBase.legConnectTimeoutMs(60_000, 50_000, 10_000));
        // budget larger than the request: the request is honored
        assertEquals(30_000, I2PTunnelClientBase.legConnectTimeoutMs(30_000, 130_000, 10_000));
        // exactly the 10s floor still runs
        assertEquals(10_000, I2PTunnelClientBase.legConnectTimeoutMs(30_000, 20_000, 10_000));
    }

    @Test
    public void testLegTimeoutSkipsBelowFloor() {
        // under streaming's 10s connect floor the leg is doomed — skip it
        assertEquals(-1, I2PTunnelClientBase.legConnectTimeoutMs(30_000, 19_999, 10_000));
        assertEquals(-1, I2PTunnelClientBase.legConnectTimeoutMs(30_000, 10_000, 10_000));
        assertEquals(-1, I2PTunnelClientBase.legConnectTimeoutMs(0, 10_000, 12_000));
    }

    // ---------- pool-building grace ----------

    @Test
    public void testGraceExtendsExpiredDeadlineWhileBuilding() {
        assertEquals(10_000 + I2PTunnelClientBase.POOL_BUILD_DEADLINE_GRACE_MS,
                     I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 10_000, 0, false));
        assertEquals(10_000 + I2PTunnelClientBase.POOL_BUILD_DEADLINE_GRACE_MS,
                     I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 12_000, 0, false));
    }

    @Test
    public void testGraceNotGrantedWhileDeadlineAlive() {
        assertEquals(10_000, I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 9_999, 0, false));
    }

    @Test
    public void testGraceNotGrantedForHealthyOrUnknownPool() {
        assertEquals(10_000, I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 10_000, 1, false));
        assertEquals(10_000, I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 10_000, -1, false));
        assertEquals(10_000, I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 10_000, -2, false));
    }

    @Test
    public void testGraceOnlyOncePerRequest() {
        // a second expiry with the pool still building must not re-extend,
        // keeping the worst case at budget + grace
        assertEquals(10_000, I2PTunnelClientBase.extendDeadlineForBuildingPool(10_000, 10_000, 0, true));
    }

    // ---------- outer retry ----------

    @Test
    public void testDeadlineExpiredStopsRetry() {
        // Even with a fresh timeout budget and a building pool, an expired
        // deadline ends the request immediately.
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, 0, true));
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 0, false, 1, true));
    }

    @Test
    public void testSingleSnapshotDerivesBothPoolFlags() {
        // One poolState() reading must decide exactly what the legacy pair of
        // queries (poolState + poolIsDefinitivelyDown) decided.
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

    @Test
    public void testDownPoolStopsRetryFromSnapshot() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 0, false, -1, false));
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, -2, false));
    }

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

    @Test
    public void testMaxRetriesStopsEvenWithDeadlineRemaining() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(
                I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES, 0, false, 1, false));
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(
                I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES - 1, 0, false, 1, false));
    }
}
