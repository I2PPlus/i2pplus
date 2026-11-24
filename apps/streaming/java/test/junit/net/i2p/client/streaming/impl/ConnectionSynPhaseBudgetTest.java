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
     * opens. It stays well above the slowest healthy handshake measured on a warm
     * router, 1,704 ms. It is a floor only: the budget is
     * {@code SYN_PHASE_BUDGET_MIN_PROBES} probes at the live interval, so on a
     * slow path the probes govern and the floor never binds.
     */
    @Test
    public void testFloorIsTwoWideIntervals() {
        assertEquals(8_000, Connection.SYN_PHASE_BUDGET_MIN_MS);
        assertEquals(Connection.SYN_PHASE_BUDGET_MIN_MS,
                     Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, NO_RTT, 1_000));
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

    /** A wide interval earns its probes: 8 x 4,000 ms is above the floor. */
    @Test
    public void testWideIntervalEarnsProbes() {
        assertEquals(32_000, Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, NO_RTT, 4_000));
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
        // probes at this interval are 8 x 2,000 = 16,000, below the RTT-sized term
        assertEquals(20_000, Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, 2_000, 2_000));
    }

    /**
     * The probe floor: on a path whose interval the Tuner has walked out to 12s,
     * ten RTTs of a 2s sample is 20,000 ms - under two probes - so the phase would
     * end before the third SYN reached the peer. Eight probes at 12s is 96,000 ms
     * and that governs. This is the case that made a torrent client's five-minute
     * connect window die at twenty seconds.
     */
    @Test
    public void testProbeFloorGovernsAWalkedInterval() {
        assertEquals(96_000, Connection.computeSynPhaseBudgetMs(300_000, 2_000, 12_000));
        assertEquals(144_000, Connection.computeSynPhaseBudgetMs(300_000, 2_000, 18_000));
        // Still capped by the window it lives inside.
        assertEquals(39_650, Connection.computeSynPhaseBudgetMs(MEASURED_WINDOW_MS, 2_000, 12_000));
    }

    /** With no evidence the RTT term cannot shorten anything: only probes count. */
    @Test
    public void testNoEvidenceIgnoresTheRttTerm() {
        assertEquals(Connection.computeSynPhaseBudgetMs(300_000, -1, 12_000),
                     Connection.computeSynPhaseBudgetMs(300_000, 0, 12_000));
        assertEquals(96_000, Connection.computeSynPhaseBudgetMs(300_000, -1, 12_000));
    }

    /**
     * The probe floor is what binds on a path with no RTT sample, where the
     * interval is the configured initial RTO. Measured live on 2026-11-24 the
     * outbound give-ups sat at exactly four sends, at ages of 24s and 26s, while
     * the peer on the traced handshake re-sent the SYN-ACK in reply to a sixth SYN
     * about a second after our client stopped sending. The server side gives up on
     * its own SYN-ACK after four sends too, so a client that also stops at four
     * stops on the very probe the peer is still answering.
     */
    @Test
    public void testProbeFloorOutlastsThePeersOwnPatience() {
        assertEquals(8, Connection.SYN_PHASE_BUDGET_MIN_PROBES);
        // the live shape: no RTT sample, initial RTO 6s, a 20s leg - the window
        // governs, so the handshake gets everything its caller offered it
        assertEquals(20_000, Connection.computeSynPhaseBudgetMs(20_000, NO_RTT, 6_000));
        // the traced handshake: RTT 894ms, interval 1,341ms. The old budget was
        // ten RTTs, 8,940ms, and the SYN-ACK needed about 9.4s to land.
        assertEquals(10_728, Connection.computeSynPhaseBudgetMs(20_000, 894, 1_341));
    }

    // ---------- evidence, not the seed ----------

    /**
     * An unacknowledged connection's {@code getRTT()} is the
     * DEFAULT_INITIAL_RTT seed. Passing it as evidence gave every cold connect a
     * 10 x 2s = 20s handshake deadline; the seed must be reported as "no
     * evidence" so the probe floor sizes the budget instead.
     */
    @Test
    public void testSeedIsNotEvidence() {
        assertEquals(-1, Connection.synPhaseEvidenceRttMs(ConnectionOptions.DEFAULT_INITIAL_RTT,
                                                         false));
        assertEquals(-1, Connection.synPhaseEvidenceRttMs(4_500, false));
    }

    /** A real ACK, or a TCB-cache value from an earlier connection, is evidence. */
    @Test
    public void testAckedPathKeepsItsRtt() {
        assertEquals(2_000, Connection.synPhaseEvidenceRttMs(2_000, true));
        assertEquals(9_500, Connection.synPhaseEvidenceRttMs(9_500, true));
        assertEquals(-1, Connection.synPhaseEvidenceRttMs(-1, true));
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

    // ---------- the wall clock discounts soft failures the same way the count does ----------

    /**
     * A SYN the router never put on the fabric, for no outbound tunnel or an
     * expired message, is not the peer being silent, so the clock must not end the
     * handshake while every send was a soft failure.
     */
    @Test
    public void testWallClockWaitsForOneFabricSend() {
        assertFalse(Connection.synPhaseBudgetExceeded(20_000, 8_000, 1, 1));
        assertFalse(Connection.synPhaseBudgetExceeded(20_000, 8_000, 3, 3));
        assertFalse(Connection.synPhaseBudgetExceeded(20_000, 8_000, 0, 0));
    }

    /** Once one SYN reached the fabric the budget binds again. */
    @Test
    public void testWallClockFiresAfterAFabricSend() {
        assertTrue(Connection.synPhaseBudgetExceeded(8_000, 8_000, 1, 0));
        assertTrue(Connection.synPhaseBudgetExceeded(20_000, 8_000, 4, 3));
    }

    /**
     * The discount cannot go negative: more soft resends than sends leaves the
     * packet one fabric send short, not excused.
     */
    @Test
    public void testSoftDiscountIsClampedAtZero() {
        assertFalse(Connection.synPhaseBudgetExceeded(20_000, 8_000, 1, 5));
    }

    /** A budget of 0 still never fires, however many sends landed. */
    @Test
    public void testZeroBudgetNeverFiresWithSends() {
        assertFalse(Connection.synPhaseBudgetExceeded(Long.MAX_VALUE, 0, 9, 0));
    }

    /** The constant the fabric-send guard reads. */
    @Test
    public void testFabricSendGuardConstant() {
        assertEquals(1, Connection.SYN_PHASE_MIN_FABRIC_SENDS);
    }

    /** The constants the budget arithmetic is read from. */
    @Test
    public void testConstants() {
        assertEquals(8, Connection.SYN_PHASE_BUDGET_MIN_PROBES);
        assertEquals(8_000, Connection.SYN_PHASE_BUDGET_MIN_MS);
        assertEquals(1_000, Connection.SYN_PHASE_BUDGET_RTT_PCT);
    }
}
