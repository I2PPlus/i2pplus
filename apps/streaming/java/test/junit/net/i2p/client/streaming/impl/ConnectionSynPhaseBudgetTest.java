package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the SYN-phase wall-clock budget that bounds an unacknowledged handshake.
 *
 * <p>The send-count budget ({@link Connection#computeSynResendBudget(int, int, long)})
 * is deliberately window-scaled so a slow-but-alive peer keeps getting SYN
 * retransmits for the whole connect window. That is the wrong shape for a path
 * that is dropping SYN-ACKs: measured against one remote eepsite, five of six
 * handshakes to a peer completed in 647-1704 ms while the sixth retransmitted a
 * SYN every ~916 ms for 10,446 ms, and the send-count budget alone allowed up to
 * the caller's full 39,650 ms window. The wall-clock bound is what lets the
 * caller's retry loop — which opens a fresh connection, and so a fresh outbound
 * tunnel — get to run.
 *
 * @since 0.9.72+
 */
public class ConnectionSynPhaseBudgetTest {

    /** The measured stall's inputs: no RTT sample, 916 ms retransmit interval. */
    private static final int MEASURED_RTT = -1;

    private static final int MEASURED_INTERVAL_MS = 916;

    private static final long MEASURED_WINDOW_MS = 39_650;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state, only the measured fixtures the
     * pure budget is called with.
     *
     * @since 0.9.72+
     */
    public ConnectionSynPhaseBudgetTest() {}

    // ---------- no bound when there is no window ----------

    /** No connect timeout configured means no extra bound, so the count budget rules. */
    @Test
    public void testNoWindowMeansNoBudget() {
        assertEquals(0, Connection.computeSynPhaseBudgetMs(0, 458, MEASURED_INTERVAL_MS));
        assertEquals(0, Connection.computeSynPhaseBudgetMs(-1, 458, MEASURED_INTERVAL_MS));
    }

    // ---------- sized from the live interval when there is no RTT sample ----------

    /**
     * The measured case: the peer has never been acknowledged, so there is no RTT
     * sample and the budget is the probe count at the live 916 ms interval —
     * 3,664 ms, under the floor, so the floor wins and the handshake is cut off at
     * 4 s instead of grinding to 10,446 ms.
     */
    @Test
    public void testMeasuredStallIsCutByTheFloor() {
        long budget = Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS,
                                                         MEASURED_RTT, MEASURED_INTERVAL_MS);
        assertEquals(Connection.SYN_PHASE_BUDGET_MIN_MS, budget);
        assertEquals(4_000, budget);
        assertTrue("must fire well before the measured 10,446ms stall", budget < 10_446);
    }

    /** A wide interval earns its probes: 4 x 2,000 ms is above the floor. */
    @Test
    public void testWideIntervalEarnsProbes() {
        assertEquals(8_000, Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, -1, 2_000));
    }

    /** A zero or negative interval cannot earn probes, so the floor is the budget. */
    @Test
    public void testZeroIntervalFallsToFloor() {
        assertEquals(Connection.SYN_PHASE_BUDGET_MIN_MS,
                     Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, -1, 0));
        assertEquals(Connection.SYN_PHASE_BUDGET_MIN_MS,
                     Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, -1, -500));
    }

    // ---------- sized from the measured path once there is an RTT sample ----------

    /** Ten round trips on a 458 ms path: 4,580 ms, above the floor. */
    @Test
    public void testRttSizedBudget() {
        assertEquals(4_580, Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, 458, 916));
    }

    /** A fast path keeps the floor, so it is not cut off before one healthy handshake. */
    @Test
    public void testFastPathKeepsTheFloor() {
        // 10 x 50 ms = 500 ms, far under the 4 s floor.
        assertEquals(Connection.SYN_PHASE_BUDGET_MIN_MS,
                     Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, 50, 750));
    }

    /** A slow path gets proportionally more time. */
    @Test
    public void testSlowPathScales() {
        assertEquals(12_000, Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, 1_200, 1_832));
    }

    /** The budget never runs past the window it lives inside. */
    @Test
    public void testCappedAtWindow() {
        assertEquals(3_000, Connection.computeSynPhaseBudgetMs(3_000, 1_200, 1_832));
        assertEquals(1, Connection.computeSynPhaseBudgetMs(1, 1_200, 1_832));
    }

    /** The floor is below the slowest healthy handshake measured on the same peer. */
    @Test
    public void testFloorExceedsSlowestHealthyHandshake() {
        // Healthy handshakes to that peer: 647, 734, 834, 890, 1704 ms.
        assertTrue("the floor must outlast a healthy handshake",
                   Connection.SYN_PHASE_BUDGET_MIN_MS > 1_704);
    }

    // ---------- the give-up decision ----------

    /** A budget of 0 means "no bound", so the decision never fires. */
    @Test
    public void testZeroBudgetNeverFires() {
        assertFalse(Connection.synPhaseBudgetExceeded(Long.MAX_VALUE, 0));
        assertFalse(Connection.synPhaseBudgetExceeded(Long.MAX_VALUE, -1));
    }

    /** The boundary is inclusive: spending the whole budget is spending it. */
    @Test
    public void testBoundaryIsInclusive() {
        assertFalse(Connection.synPhaseBudgetExceeded(3_999, 4_000));
        assertTrue(Connection.synPhaseBudgetExceeded(4_000, 4_000));
        assertTrue(Connection.synPhaseBudgetExceeded(4_001, 4_000));
    }

    /** A fresh connection is never expired. */
    @Test
    public void testFreshConnectionIsNotExpired() {
        assertFalse(Connection.synPhaseBudgetExceeded(0, 4_000));
        assertFalse(Connection.synPhaseBudgetExceeded(-1, 4_000));
    }

    /** The probe count is a real budget, not a token gesture toward the stall. */
    @Test
    public void testProbeCountIsFour() {
        assertEquals(4, Connection.SYN_PHASE_BUDGET_MIN_PROBES);
        assertEquals(4_000, Connection.SYN_PHASE_BUDGET_MIN_MS);
        assertEquals(1_000, Connection.SYN_PHASE_BUDGET_RTT_PCT);
    }
}
