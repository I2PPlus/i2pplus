package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the established-stream per-packet retransmit budget: how the ceiling is
 * derived and how it is applied.
 *
 * <p>{@link Connection#budgetExhaustionClosesConnection(boolean, boolean)} lets a
 * stream that has acknowledged data keep resending past the connect-phase budget,
 * because a head-of-line hole is a temporary path failure and not a dead path.
 * "Past" had no upper bound: the only thing that ended it was the wall-clock
 * liveness backstop, by which point a live router had retransmitted one packet
 * 181 times in 90s. The established budget bounds that at twice the connect-phase
 * budget by default, so one knob still controls both.
 *
 * @since 0.9.72+
 */
public class ConnectionEstablishedResendBudgetTest {

    /** Tuner-default connect-phase budget, the value the established cap derives from. */
    private static final int CONNECT_MAX = 30;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state, only the send counts the pure
     * decisions are called with.
     *
     * @since 0.9.72+
     */
    public ConnectionEstablishedResendBudgetTest() {}

    // ---- effectiveEstablishedMaxResends ----

    /** The default derives twice the connect-phase budget. */
    @Test
    public void testDerivedFromConnectBudget() {
        assertEquals(60, Connection.effectiveEstablishedMaxResends(0, CONNECT_MAX));
        assertEquals(2, Connection.effectiveEstablishedMaxResends(0, 1));
    }

    /** A configured value replaces the derived one, including when it is smaller. */
    @Test
    public void testConfiguredOverride() {
        assertEquals(100, Connection.effectiveEstablishedMaxResends(100, CONNECT_MAX));
        assertEquals(5, Connection.effectiveEstablishedMaxResends(5, CONNECT_MAX));
    }

    /**
     * A budget of at least one send is always returned: a 0 maxResends (an app
     * that wants no resends at all) must not make every established resend fatal.
     */
    @Test
    public void testNeverBelowOneSend() {
        assertEquals(1, Connection.effectiveEstablishedMaxResends(0, 0));
        assertEquals(1, Connection.effectiveEstablishedMaxResends(0, -5));
        assertEquals(1, Connection.effectiveEstablishedMaxResends(-1, 0));
    }

    /** A negative override is not a way to disable the ceiling - it derives. */
    @Test
    public void testNegativeOverrideDerives() {
        assertEquals(60, Connection.effectiveEstablishedMaxResends(-1, CONNECT_MAX));
    }

    // ---- the per-connection option ----

    /**
     * A copy carries the configured budget: the option is set per socket and
     * copied onto the connection, so losing it in the copy would silently fall
     * back to the derived cap.
     */
    @Test
    public void testCopyPreservesTheBudget() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.setEstablishedMaxResends(45);
        assertEquals(45, opts.getEstablishedMaxResends());
        assertEquals(45, new ConnectionOptions(opts).getEstablishedMaxResends());
    }

    /** Unset means derive, and a negative set clamps to unset rather than -N. */
    @Test
    public void testUnsetAndClampedValues() {
        ConnectionOptions opts = new ConnectionOptions();
        assertEquals(0, opts.getEstablishedMaxResends());
        opts.setEstablishedMaxResends(-7);
        assertEquals(0, opts.getEstablishedMaxResends());
    }

    // ---- establishedResendBudgetExceeded ----

    /** Exactly the derived cap is not exceeded; one send past it is. */
    @Test
    public void testBoundaryAtTheCap() {
        assertFalse(Connection.establishedResendBudgetExceeded(60, 0, 0, CONNECT_MAX));
        assertTrue(Connection.establishedResendBudgetExceeded(61, 0, 0, CONNECT_MAX));
    }

    /** The measured live case: 181 sends of one packet is far past the cap. */
    @Test
    public void testMeasuredStallIsExceeded() {
        assertTrue(Connection.establishedResendBudgetExceeded(181, 0, 0, CONNECT_MAX));
    }

    /** Soft-failure resends are excluded, exactly as in the connect-phase budget. */
    @Test
    public void testSoftResendsDoNotCount() {
        assertFalse(Connection.establishedResendBudgetExceeded(70, 10, 0, CONNECT_MAX));
        assertTrue(Connection.establishedResendBudgetExceeded(71, 10, 0, CONNECT_MAX));
        assertFalse(Connection.establishedResendBudgetExceeded(100, 100, 0, CONNECT_MAX));
    }

    /** More soft resends than sends clamp to zero rather than going negative. */
    @Test
    public void testSoftResendsAboveTotal() {
        assertFalse(Connection.establishedResendBudgetExceeded(5, 20, 0, CONNECT_MAX));
        assertFalse(Connection.establishedResendBudgetExceeded(-5, 0, 0, CONNECT_MAX));
    }

    /** A configured cap binds instead of the derived one. */
    @Test
    public void testConfiguredCapBounds() {
        assertFalse(Connection.establishedResendBudgetExceeded(40, 0, 40, CONNECT_MAX));
        assertTrue(Connection.establishedResendBudgetExceeded(41, 0, 40, CONNECT_MAX));
        // smaller than the derived cap, so it cuts the resume short
        assertTrue(Connection.establishedResendBudgetExceeded(6, 0, 5, CONNECT_MAX));
    }

    /**
     * The established cap is never below the connect-phase one, so a stream that
     * was allowed to resume past the connect budget is not closed by the very
     * next send.
     */
    @Test
    public void testEstablishedCapAboveConnectCap() {
        for (int max = 1; max <= 60; max++) {
            int est = Connection.effectiveEstablishedMaxResends(0, max);
            assertTrue("max=" + max + " est=" + est, est >= max);
            assertFalse(Connection.establishedResendBudgetExceeded(max + 1, 0, 0, max));
        }
    }
}
