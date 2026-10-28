package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the SYN-phase decision to rotate a connect onto a fresh outbound tunnel.
 *
 * <p>A connect picks one outbound tunnel, and the SYN-ACK can go missing because
 * that tunnel is dropping the reply. The established path escapes a sick tunnel
 * via a retransmit stall, but {@link Connection#isRetransmitStalledNow} returns
 * false until the first ACK, so a handshake never gets that recovery. Measured
 * against one remote eepsite, a handshake retransmitted a SYN every ~916 ms for
 * 10,446 ms with every copy on the same outbound tunnel, while handshakes to the
 * same peer opened in 647-1704 ms.
 *
 * @since 0.9.72+
 */
public class ConnectionSynRotationDecisionTest {

    /** The production threshold, used by the cases that pin the constant. */
    private static final int ROTATE_AFTER = Connection.SYN_RESEND_ROTATION_SENDS;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state, only the measured send counts the
     * pure decision is called with.
     *
     * @since 0.9.72+
     */
    public ConnectionSynRotationDecisionTest() {}

    // ---------- only the SYN phase rotates ----------

    /** A data packet of a live connection is never rotated by this rule. */
    @Test
    public void testNonSynPacketNeverRotates() {
        assertFalse(Connection.synResendNeedsFreshTunnel(false, true, 10, 0, ROTATE_AFTER));
        assertFalse(Connection.synResendNeedsFreshTunnel(false, false, 10, 0, ROTATE_AFTER));
    }

    /** Once the connection is established, the retransmit-stall path owns rotation. */
    @Test
    public void testEstablishedConnectionNeverRotatesHere() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, false, 10, 0, ROTATE_AFTER));
    }

    /** Both conditions are needed: a SYN on an established connection is a late duplicate. */
    @Test
    public void testRequiresBothSynAndUnacked() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, false, 100, 0, ROTATE_AFTER));
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 100, 0, ROTATE_AFTER));
    }

    // ---------- the original tunnel gets its attempts first ----------

    /** The initial send is one hard send: the first resend stays on the same tunnel. */
    @Test
    public void testFirstResendKeepsTheOriginalTunnel() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 0, 0, ROTATE_AFTER));
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 1, 0, ROTATE_AFTER));
    }

    /** The boundary is inclusive: two hard sends unacked, so the third rotates. */
    @Test
    public void testRotatesAtTheThreshold() {
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 2, 0, ROTATE_AFTER));
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 3, 0, ROTATE_AFTER));
    }

    /** The measured stall rotates from its third send onward, not at its tenth. */
    @Test
    public void testMeasuredStallRotatesEarly() {
        // The stall retransmitted 10 times; the rotation had to be available
        // well before the ladder ran out, at the 2-send threshold.
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 2, 0, ROTATE_AFTER));
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 10, 0, ROTATE_AFTER));
        assertEquals(2, Connection.SYN_RESEND_ROTATION_SENDS);
    }

    // ---------- soft-failure resends do not consume the budget ----------

    /**
     * Soft-failure resends never reached the tunnel fabric, and
     * scheduleSoftFailureRetransmit() rotates for them already, so they must not
     * be counted as evidence that this tunnel is dropping the SYN-ACK.
     */
    @Test
    public void testSoftResendsAreExcluded() {
        // 5 sends, 3 of them soft -> 2 hard -> rotates.
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 5, 3, ROTATE_AFTER));
        // 3 sends, 2 of them soft -> 1 hard -> stays.
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 3, 2, ROTATE_AFTER));
        // All soft -> 0 hard -> stays.
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 9, 9, ROTATE_AFTER));
    }

    /** A soft count above the send count cannot undercount below zero hard sends. */
    @Test
    public void testSoftCountIsClamped() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 1, 5, ROTATE_AFTER));
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 0, 9, ROTATE_AFTER));
    }

    // ---------- degenerate thresholds ----------

    /** A threshold of 0 rotates every SYN-phase send, which is what it says. */
    @Test
    public void testZeroThresholdRotatesImmediately() {
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 0, 0, 0));
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 1, 0, 0));
    }

    /** A threshold above the send count never rotates. */
    @Test
    public void testHighThresholdNeverRotates() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 10, 0, 11));
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 10, 0, Integer.MAX_VALUE));
    }

    /** Negative send counts are treated as no sends, so nothing rotates. */
    @Test
    public void testNegativeCountsStayPut() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, -1, 0, ROTATE_AFTER));
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, -5, -5, ROTATE_AFTER));
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, -9, -10, ROTATE_AFTER));
    }
}
