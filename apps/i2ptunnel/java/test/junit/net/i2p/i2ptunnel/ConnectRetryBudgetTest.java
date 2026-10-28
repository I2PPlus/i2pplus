package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the deadline-budget-sized ceiling on timeout-classified connect attempts.
 * <p>The fixed ceiling of one (two while the pool is building) was calibrated for
 * attempts that each burn most of a 30 s connect timeout. Now that a handshake on
 * a SYN-ACK-dropping path tears itself down after a few seconds
 * ({@code Connection.computeSynPhaseBudgetMs()}), that ceiling converts the fix
 * into a hard request failure: the first attempt times out, the ceiling says no
 * retry, and the browser gets an error page. The evidence says the retry is the
 * cheap one — handshakes that completed against one remote eepsite took
 * 647-1704 ms — so the ceiling is sized from the deadline budget that is left.
 *
 * @since 0.9.72+
 */
public class ConnectRetryBudgetTest {

    /** The shared request deadline, for readability below. */
    private static final long DEADLINE_MS = I2PTunnelHTTPClient.REQUEST_CONNECT_DEADLINE_MS;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state, only the deadline budgets the
     * pure retry ceiling is called with.
     *
     * @since 0.9.72+
     */
    public ConnectRetryBudgetTest() {}

    // ---------- ceiling sizing ----------

    /** A spent deadline buys nothing extra: the historical floor applies. */
    @Test
    public void testSpentDeadlineKeepsHistoricalFloor() {
        assertEquals(1, I2PTunnelClientBase.maxTimeoutConnectRetries(0, 4_000, false));
        assertEquals(1, I2PTunnelClientBase.maxTimeoutConnectRetries(-1, 4_000, false));
        assertEquals(2, I2PTunnelClientBase.maxTimeoutConnectRetries(0, 4_000, true));
        assertEquals(2, I2PTunnelClientBase.maxTimeoutConnectRetries(-5, 4_000, true));
    }

    /**
     * The measured case: a 4 s handshake give-up with almost the whole 120 s
     * request deadline left buys the full ceiling, so the retry that historically
     * opens in under a second gets to run.
     */
    @Test
    public void testFastGiveUpBuysTheCeiling() {
        long remaining = DEADLINE_MS - 4_000;
        assertEquals(I2PTunnelClientBase.MAX_TIMEOUT_CONNECT_RETRIES,
                     I2PTunnelClientBase.maxTimeoutConnectRetries(remaining, 4_000, false));
    }

    /** A connect that failed almost instantly is still charged a full step. */
    @Test
    public void testInstantFailureIsChargedAMinimumStep() {
        assertEquals(I2PTunnelClientBase.MAX_TIMEOUT_CONNECT_RETRIES,
                     I2PTunnelClientBase.maxTimeoutConnectRetries(DEADLINE_MS, 0, false));
        // 3 s left, charged the 1 s minimum step, so three attempts fit.
        assertEquals(3, I2PTunnelClientBase.maxTimeoutConnectRetries(3_000, 0, false));
    }

    /** A slow 30 s attempt against 40 s left genuinely cannot fit a second one. */
    @Test
    public void testSlowAttemptIsNotRewarded() {
        assertEquals(1, I2PTunnelClientBase.maxTimeoutConnectRetries(40_000, 30_000, false));
        // Pool building keeps its two-attempt floor even when the budget is tight.
        assertEquals(2, I2PTunnelClientBase.maxTimeoutConnectRetries(40_000, 30_000, true));
    }

    /** Partial budgets scale: 12 s left at 4 s per attempt buys three. */
    @Test
    public void testPartialBudgetScales() {
        assertEquals(3, I2PTunnelClientBase.maxTimeoutConnectRetries(12_000, 4_000, false));
        assertEquals(2, I2PTunnelClientBase.maxTimeoutConnectRetries(8_000, 4_000, false));
        assertEquals(1, I2PTunnelClientBase.maxTimeoutConnectRetries(4_000, 4_000, false));
    }

    /** The ceiling is a hard bound, so an unreachable destination fails in bounded time. */
    @Test
    public void testCeilingIsBounded() {
        assertEquals(I2PTunnelClientBase.MAX_TIMEOUT_CONNECT_RETRIES,
                     I2PTunnelClientBase.maxTimeoutConnectRetries(Long.MAX_VALUE, 1_000, false));
        assertTrue(I2PTunnelClientBase.MAX_TIMEOUT_CONNECT_RETRIES
                   <= I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES);
    }

    // ---------- the retry decision ----------

    /** A fast timeout give-up with budget left is retried. */
    @Test
    public void testFastTimeoutIsRetried() {
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, 1, false, 4));
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(2, 2, true, 1, false, 4));
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(3, 3, true, 1, false, 4));
    }

    /** The ceiling is honoured once reached. */
    @Test
    public void testCeilingStopsRetrying() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 4, true, 1, false, 4));
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(2, 5, true, 1, false, 4));
    }

    /** An expired shared deadline stops everything, ceiling or not. */
    @Test
    public void testExpiredDeadlineStops() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, 1, true, 4));
    }

    /** A provably dead pool cannot be retried into life. */
    @Test
    public void testDeadPoolStops() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, -1, false, 4));
    }

    /** The general attempt budget still caps the loop. */
    @Test
    public void testGeneralAttemptBudgetCaps() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES,
                                                                1, true, 1, false, 4));
    }

    /** A ceiling of 0 is treated as 1, so at least one timeout retry is always allowed. */
    @Test
    public void testZeroCeilingStillAllowsOne() {
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(1, 0, true, 1, false, 0));
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, 1, false, 0));
    }

    /** Non-timeout failures keep their existing "always retry" behaviour. */
    @Test
    public void testNonTimeoutFailureRetries() {
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(1, 0, false, 1, false, 0));
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(1, 0, false, 1, false, 4));
    }

    /**
     * The legacy overloads are unchanged: one timeout retry on a built pool, two
     * while it is building. The new ceiling must not have moved them.
     */
    @Test
    public void testLegacyOverloadsUnchanged() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, 1, false));
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, true, 0, false));
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 2, true, 0, false));
        assertTrue(I2PTunnelClientBase.shouldOuterRetryConnect(1, 1, false, 1, false));
    }

    /** A browser that hung up is never retried, whatever the ceiling. */
    @Test
    public void testBrowserClosedStops() {
        assertFalse(I2PTunnelClientBase.shouldOuterRetryConnect(1, 0, true, false, false, true, 4));
    }
}
