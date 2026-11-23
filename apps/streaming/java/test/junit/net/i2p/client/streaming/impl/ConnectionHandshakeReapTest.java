package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for closing a hosted handshake the peer never completes.
 * <p>A peer that retransmits its SYN gets its SYN-ACK answered until the re-send cap
 * derived from its own connect window is spent, the cap
 * {@link Connection#computeSynAckResendCap(long, long)} derives from that window and
 * the re-send spacing. After that the
 * duplicate SYN was simply dropped and the connection kept, so the handshake sat in
 * the dispatch table - a stream id, a table entry and the connection's buffers -
 * until the peer sent a RESET, or until the inactivity action sent a keepalive five
 * minutes on and re-armed the timer. On a router taking 10 such handshakes a second
 * that is a standing population in the thousands.
 * <p>The two no-answer reasons must not be conflated: a peer retransmitting faster
 * than the spacing is impatient and keeps its connection, while a peer that has
 * outlived its own connect window without ever sending anything but SYNs is done. The
 * silence test is what keeps an established connection safe - the duplicate-SYN path
 * never calls {@link Connection#packetReceived()}, so a stream whose only traffic is
 * SYNs keeps the timestamp of the original one.
 *
 * @since 0.9.72+
 */
public class ConnectionHandshakeReapTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public ConnectionHandshakeReapTest() {}

    /** A 30s connect window answered 2s apart: a cap of 15 re-sends. */
    private static final long WINDOW_MS = 30 * 1000L;

    /** The inter-SYN-ACK spacing that window implies. */
    private static final long SPACING_MS = 2000L;

    /** Now, as an absolute timestamp the tests subtract from. */
    private static final long NOW = 1_700_000_000_000L;

    /**
     * Below the cap, silence is not evidence: the peer may still acknowledge, so the
     * connection is kept even when it has said nothing but SYNs for a full window.
     */
    @Test
    public void testHandshakeSurvivesBelowTheCap() {
        int cap = Connection.computeSynAckResendCap(WINDOW_MS, SPACING_MS);
        assertEquals("the cap is the window divided by the spacing", 15, cap);
        for (int resends = 0; resends < cap; resends++) {
            assertFalse("re-send " + resends + " of " + cap + " must not end the handshake",
                        Connection.handshakeAbandoned(resends, WINDOW_MS, SPACING_MS,
                                                      NOW - WINDOW_MS - 1, NOW));
        }
    }

    /**
     * At the cap, with the peer silent for the whole window, the handshake is over:
     * we have answered as many times as the peer's own connect timeout allows and it
     * has never sent anything but SYNs.
     */
    @Test
    public void testHandshakeEndsAtTheCapWhenThePeerStaysSilent() {
        int cap = Connection.computeSynAckResendCap(WINDOW_MS, SPACING_MS);
        assertTrue(Connection.handshakeAbandoned(cap, WINDOW_MS, SPACING_MS,
                                                NOW - WINDOW_MS, NOW));
        assertTrue(Connection.handshakeAbandoned(cap + 5, WINDOW_MS, SPACING_MS,
                                                 NOW - 60 * 1000L, NOW));
    }

    /**
     * At the cap but the peer is still talking: a live stream that merely lost an ACK
     * keeps retransmitting its SYN, and closing it would tear down a working
     * connection. The last packet it sent is recent, so the handshake is not over.
     */
    @Test
    public void testAnEstablishedConnectionIsNotAbandoned() {
        int cap = Connection.computeSynAckResendCap(WINDOW_MS, SPACING_MS);
        assertFalse(Connection.handshakeAbandoned(cap, WINDOW_MS, SPACING_MS, NOW - 1, NOW));
        assertFalse(Connection.handshakeAbandoned(cap, WINDOW_MS, SPACING_MS,
                                                  NOW - WINDOW_MS + 1, NOW));
    }

    /**
     * The boundary is the window itself, inclusive: a peer silent for exactly its
     * connect window has run out of time, and one millisecond short has not.
     */
    @Test
    public void testTheSilenceWindowIsInclusive() {
        int cap = Connection.computeSynAckResendCap(WINDOW_MS, SPACING_MS);
        assertTrue(Connection.handshakeAbandoned(cap, WINDOW_MS, SPACING_MS,
                                                 NOW - WINDOW_MS, NOW));
        assertFalse(Connection.handshakeAbandoned(cap, WINDOW_MS, SPACING_MS,
                                                 NOW - WINDOW_MS + 1, NOW));
    }

    /**
     * A connection that never received a packet at all is the most abandoned case -
     * there is no handshake to complete. The timestamp is negative until the first
     * packet is processed, so it must not read as "recently active".
     */
    @Test
    public void testAConnectionThatNeverReceivedAPacketIsAbandoned() {
        int cap = Connection.computeSynAckResendCap(WINDOW_MS, SPACING_MS);
        assertTrue(Connection.handshakeAbandoned(cap, WINDOW_MS, SPACING_MS, -1, NOW));
        assertTrue(Connection.handshakeAbandoned(cap, WINDOW_MS, SPACING_MS, 0, NOW));
    }

    /**
     * No window configured means no cap and no reap: the decision must never end a
     * handshake the stack has no deadline for. A zero or negative spacing derives no
     * cap either, and a context clock skewed into the future puts the last packet
     * ahead of now, which reads as recent activity, not as silence.
     */
    @Test
    public void testNoWindowOrNoSpacingMeansNoReap() {
        int many = 1000;
        assertFalse(Connection.handshakeAbandoned(many, 0, SPACING_MS, NOW - 60 * 1000L, NOW));
        assertFalse(Connection.handshakeAbandoned(many, -1, SPACING_MS, NOW - 60 * 1000L, NOW));
        assertFalse(Connection.handshakeAbandoned(many, WINDOW_MS, 0, NOW - 60 * 1000L, NOW));
        assertFalse(Connection.handshakeAbandoned(many, WINDOW_MS, -5, NOW - 60 * 1000L, NOW));
        int cap = Connection.computeSynAckResendCap(WINDOW_MS, SPACING_MS);
        assertFalse("a future timestamp is activity, not silence",
                    Connection.handshakeAbandoned(cap, WINDOW_MS, SPACING_MS, NOW + 1, NOW));
    }

    /**
     * A window shorter than the spacing still earns exactly one answer, and the
     * handshake ends after it - the cap is never zero, so a peer is never met with
     * silence from the first SYN.
     */
    @Test
    public void testAShortWindowStillAllowsOneAnswer() {
        assertEquals(1, Connection.computeSynAckResendCap(1000, SPACING_MS));
        assertFalse(Connection.handshakeAbandoned(0, 1000, SPACING_MS, NOW - 60 * 1000L, NOW));
        assertTrue(Connection.handshakeAbandoned(1, 1000, SPACING_MS, NOW - 60 * 1000L, NOW));
    }

    /**
     * A window so wide that the derived cap saturates is treated as no cap at all:
     * the re-send count can never reach {@link Integer#MAX_VALUE}, so the handshake is
     * ended by the window alone, not by an overflowed count.
     */
    @Test
    public void testAnUnreachableCapNeverTriggersTheReap() {
        long wide = Long.MAX_VALUE / 4;
        assertEquals(Integer.MAX_VALUE, Connection.computeSynAckResendCap(wide, 1));
        assertFalse(Connection.handshakeAbandoned(Integer.MAX_VALUE - 1, wide, 1,
                                                 NOW - WINDOW_MS, NOW));
    }

    /**
     * The two decisions must agree about the cap, because the caller only reaches
     * this one after {@link Connection#shouldResendSynAck(long, int, long, long, int)}
     * said no. Spacing alone says no while the cap is still ahead, and the handshake
     * is kept; the cap says no, and the silence test is what decides.
     */
    @Test
    public void testTheThrottleAndTheReapDisagreeOnlyAboutSpacing() {
        int cap = Connection.computeSynAckResendCap(WINDOW_MS, SPACING_MS);
        // throttled by spacing, cap still ahead: kept
        assertFalse(Connection.shouldResendSynAck(NOW, cap - 1, NOW, SPACING_MS, cap));
        assertFalse(Connection.handshakeAbandoned(cap - 1, WINDOW_MS, SPACING_MS,
                                                 NOW - WINDOW_MS - 1, NOW));
        // cap spent: the throttle says no for good, and silence ends it
        assertFalse(Connection.shouldResendSynAck(NOW, cap, NOW, SPACING_MS, cap));
        assertTrue(Connection.handshakeAbandoned(cap, WINDOW_MS, SPACING_MS,
                                                 NOW - WINDOW_MS - 1, NOW));
    }
}
