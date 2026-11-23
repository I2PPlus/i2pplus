package net.i2p.i2ptunnel;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the package-private helpers of {@link I2PTunnelHTTPClient}:
 * the {@code fixupURI} bracket/pipe/brace fix-up extracted from
 * {@code clientConnectionRun()}, the connect-retry backoff ladder, the
 * empty-retry configuration parse, the Content-Length-scaled reconnect budget,
 * and the client-tunnel-pool stop decision.
 */
public class I2PTunnelHTTPClientHelperTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one. The class carries no per-test state, only the ramp unit and the
     * reconnect deadline the decisions under test are called with.
     *
     * @since 0.9.72+
     */
    public I2PTunnelHTTPClientHelperTest() {}

    /**
     * A URI with nothing to escape is returned as parsed, so the fix-up cannot
     * move a plain request to a different target.
     *
     * @throws URISyntaxException if the clean URI fails to parse, which is the
     *                            failure this case is asserting against
     */
    @Test
    public void testFixupURINormal() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path");
        assertEquals("http://example.com/path", result.toASCIIString());
    }

    /**
     * A query string is left alone when it carries no reserved characters.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURINormalWithQuery() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path?foo=bar");
        assertEquals("http://example.com/path?foo=bar", result.toASCIIString());
    }

    /**
     * The host and port are outside the fix-up's reach, so an explicit port
     * survives unchanged.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURINormalWithPort() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com:8080/path");
        assertEquals("http://example.com:8080/path", result.toASCIIString());
    }

    /**
     * Unencoded {@code [} and {@code ]} in the path - what a browser sends for a
     * literal IPv6 or a bracketed file name - are percent-escaped in place.
     *
     * @throws URISyntaxException if the fixed-up URI still fails to parse
     */
    @Test
    public void testFixupURIUnescapedBracketsInPath() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/[path]/file");
        assertEquals("http://example.com/%5Bpath%5D/file", result.toASCIIString());
    }

    /**
     * Brackets already sent percent-escaped in the query are not escaped twice.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURIUnescapedBracketsInQuery() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path?foo=%5Bbar%5D");
        assertEquals("http://example.com/path?foo=%5Bbar%5D", result.toASCIIString());
    }

    /**
     * An escaped pipe in the path is kept as sent, so a path segment that
     * legitimately contains {@code |} is not rewritten.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURIPipeInPath() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path%7Cto/file");
        assertEquals("http://example.com/path%7Cto/file", result.toASCIIString());
    }

    /**
     * An escaped pipe in the query is kept as sent.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURIPipeInQuery() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path?foo=bar%7Cbaz");
        assertEquals("http://example.com/path?foo=bar%7Cbaz", result.toASCIIString());
    }

    /**
     * An escaped brace in the path stays escaped - the fix-up adds escapes, it
     * never removes them.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURIBracesInPath() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/%7Bpath%7D/file");
        assertEquals("http://example.com/%7Bpath%7D/file", result.toASCIIString());
    }

    /**
     * An escaped brace in the query stays escaped.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURIBracesInQuery() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path?foo=%7Bbar%7D");
        assertEquals("http://example.com/path?foo=%7Bbar%7D", result.toASCIIString());
    }

    /**
     * A URI whose path and query carry every character the fix-up escapes -
     * {@code [ ] { } |} - comes back fully percent-encoded and otherwise
     * untouched.
     *
     * @throws URISyntaxException if the fixed-up URI still fails to parse
     */
    @Test
    public void testFixupURIAllSpecialChars() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/%5Bpath%7Cto/%7Bfile%7D%5D");
        assertEquals("http://example.com/%5Bpath%7Cto/%7Bfile%7D%5D", result.toASCIIString());
    }

    /**
     * A string that is not a URI at all still fails the way the caller expects:
     * a {@link URISyntaxException} carrying a message, not a silently rewritten
     * target.
     */
    @Test
    public void testFixupURIInvalidStillInvalid() {
        try {
            I2PTunnelHTTPClient.fixupURI("not a valid uri at all");
            fail("Should have thrown URISyntaxException");
        } catch (URISyntaxException e) {
            assertNotNull(e.getMessage());
        }
    }

    /**
     * An unencoded {@code [} that leaves the URI invalid even after escaping is
     * still repaired, because the fix-up runs before the parse.
     *
     * @throws URISyntaxException if the fixed-up URI still fails to parse
     */
    @Test
    public void testFixupURIInvalidBracketsFixed() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/[invalid");
        assertEquals("http://example.com/%5Binvalid", result.toASCIIString());
    }

    /**
     * An https request with nothing to escape passes through byte for byte - the
     * fix-up must not rewrite a scheme or a clean path.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURIHttpsNormal() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("https://example.com/path");
        assertEquals("https://example.com/path", result.toASCIIString());
    }

    /**
     * User info in the authority is left alone, so a request that carries
     * credentials is not sent to a different destination.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURIWithUserInfo() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://user:pass@example.com/path");
        assertEquals("http://user:pass@example.com/path", result.toASCIIString());
    }

    /**
     * The fragment is preserved - the proxy forwards it rather than dropping it
     * on the way to the URI constructor.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURIWithFragment() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path#fragment");
        assertEquals("http://example.com/path#fragment", result.toASCIIString());
    }

    /**
     * A bare origin with no path at all is returned as sent, so the fix-up does
     * not invent a {@code /}.
     *
     * @throws URISyntaxException if the clean URI fails to parse
     */
    @Test
    public void testFixupURINoPath() throws URISyntaxException {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com");
        assertEquals("http://example.com", result.toASCIIString());
    }

    // ---------- getConnectRetryDelayMs ----------

    /**
     * An attempt count of zero or less has no failure to back off for, so the
     * delay is immediate.
     */
    @Test
    public void testConnectRetryDelay_NonPositiveAttemptIsImmediate() {
        assertEquals(0, I2PTunnelHTTPClient.getConnectRetryDelayMs(0));
        assertEquals(0, I2PTunnelHTTPClient.getConnectRetryDelayMs(-1));
    }

    /**
     * The ladder doubles from the base delay: 1s, then 2s, then 4s.
     */
    @Test
    public void testConnectRetryDelay_ExponentialSequence() {
        assertEquals(1000, I2PTunnelHTTPClient.getConnectRetryDelayMs(1));
        assertEquals(2000, I2PTunnelHTTPClient.getConnectRetryDelayMs(2));
        assertEquals(4000, I2PTunnelHTTPClient.getConnectRetryDelayMs(3));
    }

    /**
     * The ladder is capped at 8s so a long run of retries cannot stall a request
     * indefinitely.
     */
    @Test
    public void testConnectRetryDelay_CappedAt8s() {
        assertEquals(8000, I2PTunnelHTTPClient.getConnectRetryDelayMs(4));
        assertEquals(8000, I2PTunnelHTTPClient.getConnectRetryDelayMs(5));
        assertEquals(8000, I2PTunnelHTTPClient.getConnectRetryDelayMs(6));
        assertEquals(8000, I2PTunnelHTTPClient.getConnectRetryDelayMs(100));
    }

    /**
     * The sequence is driven by the configured base-delay constant, at both ends
     * of the ladder, so retuning the constant moves the whole ladder.
     */
    @Test
    public void testConnectRetryDelay_TracksRetryConstant() {
        long base = I2PTunnelHTTPClient.I2P_CONNECT_RETRY_BASE_DELAY;
        assertEquals(base, I2PTunnelHTTPClient.getConnectRetryDelayMs(1));
        assertEquals(base * 8, I2PTunnelHTTPClient.getConnectRetryDelayMs(100));
    }

    // ---------- parseEmptyRetries ----------

    /**
     * A missing value reads as zero retries rather than the field default, so an
     * unset per-tunnel override disables the retry loop.
     */
    @Test
    public void testParseEmptyRetries_NullDefaultsZero() {
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries(null));
    }

    /**
     * An empty or whitespace-only value also reads as zero.
     */
    @Test
    public void testParseEmptyRetries_EmptyDefaultsZero() {
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries(""));
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("  "));
    }

    /**
     * A plain non-negative count is taken as written, surrounding whitespace
     * ignored.
     */
    @Test
    public void testParseEmptyRetries_Normal() {
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("0"));
        assertEquals(2, I2PTunnelHTTPClient.parseEmptyRetries("2"));
        assertEquals(10, I2PTunnelHTTPClient.parseEmptyRetries("10"));
        assertEquals(2, I2PTunnelHTTPClient.parseEmptyRetries("  2  "));
    }

    /**
     * A negative count is clamped to zero instead of making the retry loop run
     * forever or skip its bounds check.
     */
    @Test
    public void testParseEmptyRetries_NegativeClampedToZero() {
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("-1"));
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("-5"));
    }

    /**
     * Anything that is not an integer reads as zero, so a typo in the tunnel
     * config cannot raise the retry count.
     */
    @Test
    public void testParseEmptyRetries_GarbageDefaultsZero() {
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("abc"));
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("2.5"));
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("--"));
    }

    // ---------- scaledEmptyReconnectBudget ----------

    /** One ramp step of the retry ramp, the same unit the production constant uses. */
    private static final long RAMP_UNIT = 1024L * 1024;

    /**
     * An entity of unknown or sub-step size keeps the configured budget, and a
     * budget of zero stays zero whatever the size - a disabled retry loop is a
     * configuration, not a default to be ramped.
     */
    @Test
    public void testScaledBudgetBaselineWhenSizeUnknownOrSmall() {
        assertEquals(9, I2PTunnelHTTPClient.scaledEmptyReconnectBudget(9, -1));
        assertEquals(9, I2PTunnelHTTPClient.scaledEmptyReconnectBudget(9, 0));
        assertEquals(9, I2PTunnelHTTPClient.scaledEmptyReconnectBudget(9, RAMP_UNIT - 1));
        // a disabled budget must stay disabled regardless of size
        assertEquals(0, I2PTunnelHTTPClient.scaledEmptyReconnectBudget(0, 100L * RAMP_UNIT));
    }

    /**
     * Each megabyte of verified Content-Length buys one more attempt, so a large
     * entity gets proportionally more reconnects than a small one.
     */
    @Test
    public void testScaledBudgetRampsPerUnit() {
        assertEquals(10, I2PTunnelHTTPClient.scaledEmptyReconnectBudget(9, RAMP_UNIT));
        assertEquals(19, I2PTunnelHTTPClient.scaledEmptyReconnectBudget(9, 10L * 1024 * 1024));
        // current installer entity: 45415943 / 1MB == 43
        assertEquals(52, I2PTunnelHTTPClient.scaledEmptyReconnectBudget(9, 45415943L));
    }

    /**
     * The ramp is capped at nine times the base budget, so an upstream that
     * declares an enormous entity cannot buy an unbounded number of connects.
     */
    @Test
    public void testScaledBudgetCapsAtNineTimesBase() {
        assertEquals(81, I2PTunnelHTTPClient.scaledEmptyReconnectBudget(9, 4L * 1024 * 1024 * 1024));
        assertEquals(90, I2PTunnelHTTPClient.scaledEmptyReconnectBudget(10, Long.MAX_VALUE));
        assertEquals(18, I2PTunnelHTTPClient.scaledEmptyReconnectBudget(2, Long.MAX_VALUE));
    }

    // ---------- shouldStopEmptyReconnect ----------

    /**
     * A pool with no valid tunnels and none being built is the one state that
     * ends the retry loop early.
     */
    @Test
    public void testStopEmptyReconnect_DeadPoolFailsFast() {
        assertTrue(I2PTunnelHTTPClient.shouldStopEmptyReconnect(-1));
    }

    /**
     * A pool holding tunnels can serve the next attempt, so it is not a reason
     * to stop.
     */
    @Test
    public void testStopEmptyReconnect_HealthyPoolContinues() {
        assertFalse(I2PTunnelHTTPClient.shouldStopEmptyReconnect(1));
    }

    /**
     * A pool with builds in progress is recovering, which is the opposite of
     * dead.
     */
    @Test
    public void testStopEmptyReconnect_BuildingPoolContinues() {
        assertFalse(I2PTunnelHTTPClient.shouldStopEmptyReconnect(0));
    }

    /**
     * An unknown pool state must not fail fast. A standalone client with no
     * router pool, an outproxy for one, always reads -2, so treating unknown as
     * dead would abort every empty-response retry it attempts.
     */
    @Test
    public void testStopEmptyReconnect_UnknownContinues() {
        assertFalse(I2PTunnelHTTPClient.shouldStopEmptyReconnect(-2));
    }

    // ---------- reconnectRetryDelayMs ----------

    /** The reconnect loop's own wall-clock bound, as the decision sees it. */
    private static final long LOOP_DEADLINE_MS = I2PTunnelHTTPClient.EMPTY_RECONNECT_DEADLINE_MS;

    /**
     * A pool with tunnels, one still building, or an unknown state keeps the
     * plain backoff ladder: nothing here is a reason to stop.
     */
    @Test
    public void testRetryDelay_PoolNotDeadKeepsTheLadder() {
        assertEquals(1000, I2PTunnelHTTPClient.reconnectRetryDelayMs(1, 0, LOOP_DEADLINE_MS, 1000));
        long remaining = LOOP_DEADLINE_MS - 15 * 1000;
        assertEquals(2000, I2PTunnelHTTPClient.reconnectRetryDelayMs(0, 300348, remaining, 2000));
        assertEquals(4000, I2PTunnelHTTPClient.reconnectRetryDelayMs(-2, 300348, remaining, 4000));
    }

    /**
     * A first connect has delivered nothing, so an empty pool still ends the
     * loop - the browser is waiting on a response, not on the rest of a body.
     */
    @Test
    public void testRetryDelay_DeadPoolStillStopsAFirstConnect() {
        assertEquals(-1, I2PTunnelHTTPClient.reconnectRetryDelayMs(-1, 0, LOOP_DEADLINE_MS, 1000));
        assertEquals(-1, I2PTunnelHTTPClient.reconnectRetryDelayMs(-1, -1, LOOP_DEADLINE_MS, 1000));
    }

    /**
     * The measured truncation: 300,348 of 49,684,099 bytes were already in the
     * browser's hands when the client pool read empty, and the resume loop
     * returned null on its first failed attempt. The pool rebuilds its own
     * tunnels on a scheduled pass, so the loop waits for that pass instead.
     */
    @Test
    public void testRetryDelay_DeadPoolWaitsWhenBodyIsUnderway() {
        long remaining = LOOP_DEADLINE_MS - 15 * 1000;
        assertEquals(I2PTunnelHTTPClient.POOL_REBUILD_WAIT_MS,
                     I2PTunnelHTTPClient.reconnectRetryDelayMs(-1, 300348, remaining, 1000));
        assertEquals(I2PTunnelHTTPClient.POOL_REBUILD_WAIT_MS,
                     I2PTunnelHTTPClient.reconnectRetryDelayMs(-1, 1, remaining, 0));
    }

    /**
     * The longer backoff still wins once the ladder has climbed past the rebuild
     * wait, so the pool wait never shortens an existing pause.
     */
    @Test
    public void testRetryDelay_BackoffAboveTheRebuildWaitIsKept() {
        long remaining = LOOP_DEADLINE_MS - 15 * 1000;
        assertEquals(8000, I2PTunnelHTTPClient.reconnectRetryDelayMs(-1, 300348, remaining, 8000));
    }

    /**
     * Waiting only helps if the loop can still fit a rebuild and an attempt
     * inside its own deadline; at the boundary, stopping is the honest answer.
     */
    @Test
    public void testRetryDelay_NoTimeForARebuildStops() {
        long wait = I2PTunnelHTTPClient.POOL_REBUILD_WAIT_MS;
        assertEquals(-1, I2PTunnelHTTPClient.reconnectRetryDelayMs(-1, 300348, wait, 1000));
        assertEquals(-1, I2PTunnelHTTPClient.reconnectRetryDelayMs(-1, 300348, 0, 1000));
        assertEquals(-1, I2PTunnelHTTPClient.reconnectRetryDelayMs(-1, 300348, -1, 1000));
        // One millisecond past the boundary still takes the wait.
        assertEquals(wait, I2PTunnelHTTPClient.reconnectRetryDelayMs(-1, 300348, wait + 1, 1000));
    }
}
