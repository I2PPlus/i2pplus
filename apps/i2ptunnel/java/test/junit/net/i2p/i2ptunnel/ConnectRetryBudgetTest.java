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
     * request deadline left. The deadline alone would buy the full ceiling of
     * four, which is what let one image take 25 s; the per-request spend cap
     * brings that to three attempts.
     */
    @Test
    public void testFastGiveUpBuysThreeAttempts() {
        long remaining = DEADLINE_MS - 4_000;
        assertEquals(3, I2PTunnelClientBase.maxTimeoutConnectRetries(remaining, 4_000, false));
        // Without the cap, the deadline alone buys the full ceiling.
        assertEquals(I2PTunnelClientBase.MAX_TIMEOUT_CONNECT_RETRIES,
                     I2PTunnelClientBase.maxTimeoutConnectRetries(remaining, 4_000, false, 0));
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

    // ---------- the per-request connect-spend cap ----------

    /**
     * The measured regression the cap exists for: a 120s deadline with 4s
     * give-ups bought four attempts, and one image took 25s end to end. Against
     * the 15s cap the same inputs buy three.
     */
    @Test
    public void testSpendCapBoundsTheCeiling() {
        long remaining = DEADLINE_MS - 4_000;
        assertEquals(3, I2PTunnelClientBase.maxTimeoutConnectRetries(remaining, 4_000,
                                                                    false, 15_000));
        // Uncapped, the same inputs buy the full ceiling.
        assertEquals(I2PTunnelClientBase.MAX_TIMEOUT_CONNECT_RETRIES,
                     I2PTunnelClientBase.maxTimeoutConnectRetries(remaining, 4_000, false, 0));
    }

    /** A cap of 0 or less means "no cap beyond the deadline", not "no budget". */
    @Test
    public void testNonPositiveCapMeansNoCap() {
        assertEquals(I2PTunnelClientBase.MAX_TIMEOUT_CONNECT_RETRIES,
                     I2PTunnelClientBase.maxTimeoutConnectRetries(DEADLINE_MS, 4_000, false, 0));
        assertEquals(I2PTunnelClientBase.MAX_TIMEOUT_CONNECT_RETRIES,
                     I2PTunnelClientBase.maxTimeoutConnectRetries(DEADLINE_MS, 4_000, false, -1));
    }

    /** The 3-arg overload applies the production cap. */
    @Test
    public void testLegacyOverloadAppliesTheCap() {
        assertEquals(3, I2PTunnelClientBase.maxTimeoutConnectRetries(DEADLINE_MS - 4_000, 4_000, false));
        assertEquals(15_000, I2PTunnelClientBase.MAX_CONNECT_SPEND_MS);
    }

    /** A cap tighter than one attempt still allows the historical floor. */
    @Test
    public void testTightCapKeepsTheFloor() {
        assertEquals(1, I2PTunnelClientBase.maxTimeoutConnectRetries(DEADLINE_MS, 4_000, false, 2_000));
        assertEquals(2, I2PTunnelClientBase.maxTimeoutConnectRetries(DEADLINE_MS, 4_000, true, 2_000));
    }

    /** A spent deadline outranks the cap. */
    @Test
    public void testSpentDeadlineBeatsTheCap() {
        assertEquals(1, I2PTunnelClientBase.maxTimeoutConnectRetries(0, 4_000, false, 15_000));
    }

    /**
     * The cap is what bounds the request, given the ladder the retries use:
     * three 4s attempts plus the 0s and 1s delays stay inside it, and the
     * fourth attempt is what the cap is refusing.
     */
    @Test
    public void testAggregateStaysInsideTheCap() {
        int ceiling = I2PTunnelClientBase.maxTimeoutConnectRetries(DEADLINE_MS, 4_000, false);
        long spend = 0;
        for (int attempt = 1; attempt <= ceiling; attempt++) {
            spend += 4_000 + I2PTunnelHTTPClient.getConnectAttemptDelayMs(attempt);
        }
        assertTrue("aggregate connect spend " + spend + "ms must fit the cap",
                   spend <= I2PTunnelClientBase.MAX_CONNECT_SPEND_MS + 4_000);
    }

    // ---------- the first retry is immediate ----------

    /**
     * The first retry waits for nothing: a fresh handshake to the same peer is
     * measured to open in 596ms, so a 1s pause costs more than the attempt it
     * precedes. The ladder then resumes at 1s, 2s, 4s, 8s.
     */
    @Test
    public void testFirstConnectRetryIsImmediate() {
        assertEquals(0, I2PTunnelHTTPClient.getConnectAttemptDelayMs(0));
        assertEquals(0, I2PTunnelHTTPClient.getConnectAttemptDelayMs(1));
        assertEquals(1_000, I2PTunnelHTTPClient.getConnectAttemptDelayMs(2));
        assertEquals(2_000, I2PTunnelHTTPClient.getConnectAttemptDelayMs(3));
        assertEquals(4_000, I2PTunnelHTTPClient.getConnectAttemptDelayMs(4));
        assertEquals(8_000, I2PTunnelHTTPClient.getConnectAttemptDelayMs(5));
        assertEquals(8_000, I2PTunnelHTTPClient.getConnectAttemptDelayMs(9));
    }

    /** The shared ladder is untouched, so the empty-response loop keeps its 1s start. */
    @Test
    public void testSharedLadderUnchanged() {
        assertEquals(1_000, I2PTunnelHTTPClient.getConnectRetryDelayMs(1));
        assertEquals(2_000, I2PTunnelHTTPClient.getConnectRetryDelayMs(2));
        assertEquals(0, I2PTunnelHTTPClient.getConnectRetryDelayMs(0));
    }
}
