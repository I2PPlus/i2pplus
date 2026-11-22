package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests how long a packet must have been in flight before a NACK naming it
 * earns a fast retransmit — {@link Connection#nackIsLossEvidence(long, long)}
 * and its floor {@link Connection#nackTrustFlightMs(long)}.
 *
 * <p>The honest floor is one way, half the smoothed RTT, because a NACK is only
 * evidence about a packet that has had time to reach the peer and come back as
 * a complaint. The floor this replaces was {@code max(4000, RTT)}, while a peer
 * NACKs a hole about one RTT after the send, so on I2P's 1-2 s paths the NACK
 * always arrived too early to count. Fast retransmit never fired on a live
 * stream: the duplicate-ACK congestion stat recorded no events in a running
 * router's whole lifetime, and every head-of-line loss escalated to a
 * retransmit-timeout pass that cut the window.
 *
 * <p>Both helpers are static and stateless: testable without a running router.
 *
 * @since 0.9.72+
 */
public class ConnectionNackTrustTest {

    /**
     * Test case carrying the NACK trust cases for one streaming connection.
     * Every case calls a static helper, so the instance holds no state and
     * needs no router.
     */
    public ConnectionNackTrustTest() {
    }

    /** Unmeasured connection: the absolute floor, not a division of a default. */
    @Test
    public void testUnmeasuredRttUsesTheFloor() {
        assertEquals(Connection.NACK_TRUST_FLIGHT_MIN_MS, Connection.nackTrustFlightMs(0));
        assertEquals(Connection.NACK_TRUST_FLIGHT_MIN_MS, Connection.nackTrustFlightMs(-1));
    }

    /** A measured RTT gives one way. */
    @Test
    public void testMeasuredRttIsOneWay() {
        assertEquals(557, Connection.nackTrustFlightMs(1115));
        assertEquals(600, Connection.nackTrustFlightMs(1200));
        assertEquals(1000, Connection.nackTrustFlightMs(2000));
    }

    /** A fast path still cannot be NACKed about a packet that has barely left. */
    @Test
    public void testFastPathStillHoldsTheFloor() {
        assertEquals(Connection.NACK_TRUST_FLIGHT_MIN_MS, Connection.nackTrustFlightMs(400));
        assertEquals(Connection.NACK_TRUST_FLIGHT_MIN_MS, Connection.nackTrustFlightMs(999));
    }

    /**
     * The regression, at a live RTT. A peer NACKs a hole about one RTT after
     * the send, so the flight time when the NACK lands is the RTT itself. The
     * old floor of max(4000, RTT) rejected that — it was the only loss evidence
     * the connection ever got — and the new one accepts it.
     */
    @Test
    public void testNackArrivingAtOneRttIsNowTrusted() {
        long rtt = 1115;
        long flightWhenNackLands = rtt;
        long oldFloor = Math.max(4000, rtt);
        assertTrue("the old floor rejected the only loss evidence we get",
                   flightWhenNackLands < oldFloor);
        assertTrue(Connection.nackIsLossEvidence(flightWhenNackLands, rtt));
    }

    /** The same holds at the unmeasured initial RTT a fresh connection runs on. */
    @Test
    public void testNackAtOneInitialRttIsTrusted() {
        int initial = ConnectionOptions.DEFAULT_INITIAL_RTT;
        assertTrue(Connection.nackIsLossEvidence(initial, initial));
        // And it was refused under the old floor.
        assertTrue(initial < Math.max(4000, initial));
    }

    /**
     * A stale NACK describing an earlier send is still refused: the packet was
     * re-sent too recently for the NACK to describe that re-send.
     */
    @Test
    public void testStaleNackAboutAnEarlierSendIsRefused() {
        long rtt = 1115;
        assertFalse(Connection.nackIsLossEvidence(100, rtt));
        long floor = Connection.nackTrustFlightMs(rtt);
        assertFalse(Connection.nackIsLossEvidence(floor - 1, rtt));
        assertTrue(Connection.nackIsLossEvidence(floor, rtt));
    }

    /** A packet that was never sent has no flight time and is never trusted. */
    @Test
    public void testNeverSentIsNeverTrusted() {
        assertFalse(Connection.nackIsLossEvidence(-1, 1115));
        assertFalse(Connection.nackIsLossEvidence(0, 1115));
    }

    /**
     * The floor must stay below any RTT this network measures, or the gate
     * reverts to the behaviour it replaced: a NACK arriving one RTT after the
     * send would again be too early.
     */
    @Test
    public void testFloorStaysBelowTheSlowestMeasuredPath() {
        long slowestPlausibleRtt = 4000;
        assertTrue(Connection.nackTrustFlightMs(slowestPlausibleRtt) < slowestPlausibleRtt);
        assertTrue(Connection.NACK_TRUST_FLIGHT_MIN_MS <
                   ConnectionOptions.DEFAULT_INITIAL_RTT);
    }
}
