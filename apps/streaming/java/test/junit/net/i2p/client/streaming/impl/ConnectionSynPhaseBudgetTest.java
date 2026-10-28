package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the SYN-phase wall-clock budget that bounds an unacknowledged handshake,
 * and the clock it is measured on.
 *
 * <p>The send-count budget ({@link Connection#computeSynResendBudget(int, int, long)})
 * is deliberately window-scaled so a slow-but-alive peer keeps getting SYN
 * retransmits for the whole connect window. That is the wrong shape for a path
 * that is dropping SYN-ACKs: measured against one remote eepsite, five of six
 * handshakes to a peer completed in 647-1704 ms while the sixth retransmitted a
 * SYN every ~916 ms for 10,446 ms, and the send-count budget alone allowed up
 * to the caller's full 39,650 ms window. The wall-clock bound is what lets the
 * caller's retry loop — which opens a fresh connection, and so a fresh outbound
 * tunnel — get to run.
 *
 * <p>The first shipped bound was 4 s measured from connection creation, and it
 * tore down 5,444 handshakes against 1,046 successful opens: the give-ups landed
 * on connects that had sent one or two SYNs and had not been answered, because
 * the clock included route lookup, LeaseSet fetch and the delayed-SYN wait. Both
 * halves are pinned here.
 *
 * @since 0.9.72+
 */
public class ConnectionSynPhaseBudgetTest {

    /** The measured stall's inputs: no peer RTT evidence, 916 ms interval. */
    private static final int NO_RTT = -1;

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

    // ---------- the floor outlasts a working handshake ----------

    /**
     * The floor is 8 s, not the 4 s that produced 5,444 give-ups against 1,046
     * opens. It has to cover two of the widest observed inter-SYN intervals, 4 s
     * from a 2 s peer RTT at the 200% headroom, and stay well above the slowest
     * healthy handshake measured on a warm router, 1,704 ms.
     */
    @Test
    public void testFloorIsTwoWideIntervals() {
        assertEquals(8_000, Connection.SYN_PHASE_BUDGET_MIN_MS);
        assertEquals(Connection.SYN_PHASE_BUDGET_MIN_MS,
                     Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, NO_RTT, 2_000));
        assertTrue("the floor must outlast a healthy handshake",
                   Connection.SYN_PHASE_BUDGET_MIN_MS > 1_704);
    }

    /** A narrow interval earns only its probes, so the floor governs the fast path. */
    @Test
    public void testMeasuredStallTakesTheFloor() {
        long budget = Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, NO_RTT,
                                                         MEASURED_INTERVAL_MS);
        assertEquals(Connection.SYN_PHASE_BUDGET_MIN_MS, budget);
        assertEquals(8_000, budget);
        assertTrue("must fire well before the measured 10,446ms stall", budget < 10_446);
    }

    /** A wide interval earns its probes: 4 x 4,000 ms is above the floor. */
    @Test
    public void testWideIntervalEarnsProbes() {
        assertEquals(16_000, Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, NO_RTT, 4_000));
    }

    /** A zero or negative interval cannot earn probes, so the floor is the budget. */
    @Test
    public void testZeroIntervalFallsToFloor() {
        assertEquals(Connection.SYN_PHASE_BUDGET_MIN_MS,
                     Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, NO_RTT, 0));
        assertEquals(Connection.SYN_PHASE_BUDGET_MIN_MS,
                     Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, NO_RTT, -500));
    }

    // ---------- sized from the peer's RTT evidence ----------

    /**
     * Ten round trips on a 2 s path: 20,000 ms. This is the case the shipped
     * budget got backwards — a peer with a 2 s RTT sample was cut to the floor,
     * because the RTT branch was gated on the connection's own ACK and a
     * connection in the SYN phase has by definition never been acknowledged.
     */
    @Test
    public void testPeerRttSizesTheBudget() {
        assertEquals(20_000, Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, 2_000, 4_000));
    }

    /** A fast peer keeps the floor, so it is not cut off before one healthy handshake. */
    @Test
    public void testFastPeerKeepsTheFloor() {
        // 10 x 50 ms = 500 ms, far under the 8 s floor.
        assertEquals(Connection.SYN_PHASE_BUDGET_MIN_MS,
                     Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, 50, 750));
    }

    /** A mid-range peer below the floor's reach still gets the floor. */
    @Test
    public void testMidRttTakesTheFloor() {
        assertEquals(8_000, Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, 458, 916));
    }

    /** The budget never runs past the window it lives inside. */
    @Test
    public void testCappedAtWindow() {
        assertEquals(3_000, Connection.computeSynPhaseBudgetMs(3_000, 1_200, 1_832));
        assertEquals(1, Connection.computeSynPhaseBudgetMs(1, 1_200, 1_832));
    }

    // ---------- the clock is the SYN packet, not the connection ----------

    /** The SYN packet's own age is what the budget is charged against. */
    @Test
    public void testAgeFromSynPacket() {
        assertEquals(5_000, Connection.synPhaseAgeMs(10_000, 5_000));
        assertEquals(0, Connection.synPhaseAgeMs(5_000, 5_000));
    }

    /**
     * No packet clock means no age, so the budget never fires. A connect that
     * spent 30 s on route lookup and the delayed-SYN path must not be charged
     * for it: that is what made 473 give-ups land on a connection that had sent
     * exactly one SYN.
     */
    @Test
    public void testNoPacketClockMeansNoAge() {
        assertEquals(0, Connection.synPhaseAgeMs(10_000, 0));
        assertEquals(0, Connection.synPhaseAgeMs(10_000, -1));
    }

    /** A clock in the future cannot produce a negative age. */
    @Test
    public void testAgeIsClampedAtZero() {
        assertEquals(0, Connection.synPhaseAgeMs(1_000, 5_000));
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
        assertFalse(Connection.synPhaseBudgetExceeded(7_999, 8_000));
        assertTrue(Connection.synPhaseBudgetExceeded(8_000, 8_000));
        assertTrue(Connection.synPhaseBudgetExceeded(8_001, 8_000));
    }

    /** A fresh connect is never expired. */
    @Test
    public void testFreshConnectionIsNotExpired() {
        assertFalse(Connection.synPhaseBudgetExceeded(0, 8_000));
        assertFalse(Connection.synPhaseBudgetExceeded(-1, 8_000));
    }

    /** The constants the budget arithmetic is read from. */
    @Test
    public void testConstants() {
        assertEquals(4, Connection.SYN_PHASE_BUDGET_MIN_PROBES);
        assertEquals(8_000, Connection.SYN_PHASE_BUDGET_MIN_MS);
        assertEquals(1_000, Connection.SYN_PHASE_BUDGET_RTT_PCT);
    }
}
