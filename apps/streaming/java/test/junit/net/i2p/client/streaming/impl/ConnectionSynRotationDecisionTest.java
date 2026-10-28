package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the SYN-phase decision to rotate a connect onto a fresh outbound tunnel,
 * and the send count that feeds it.
 *
 * <p>A connect picks one outbound tunnel, and the SYN-ACK can go missing because
 * that tunnel is dropping the reply. The established path escapes a sick tunnel
 * via a retransmit stall, but {@link Connection#isRetransmitStalledNow} returns
 * false until the first ACK, so a handshake never
 * gets that recovery. Measured against one remote eepsite, a handshake
 * retransmitted a SYN every ~916 ms for 10,446 ms with every copy on the same
 * outbound tunnel, while handshakes to the same peer opened in 647-1704 ms.
 *
 * <p>The send count is the part that broke when first shipped: counting
 * {@link net.i2p.client.streaming.impl.PacketLocal#getNumSends()} raw rotated
 * healthy connects at the first tail loss probe, 2,656 rotations after a single
 * real SYN, and re-arming on every resend collapsed the SYN cadence to about
 * one send per 9 s. Hence {@link Connection#synHardSends(int, int, int)} and one
 * rotation per connect.
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

    // ---------- what counts as an unanswered SYN ----------

    /** One SYN send, no probe, no soft failure: one hard send. */
    @Test
    public void testPlainSendCounts() {
        assertEquals(1, Connection.synHardSends(1, 0, 0));
        assertEquals(3, Connection.synHardSends(3, 0, 0));
    }

    /**
     * A tail loss probe is a PTO loss hypothesis, not a retransmit of loss, and
     * it fires routinely on a healthy connect: 17,700 probes landed on the SYN
     * packet in one log rotation. Counting them made every healthy connect look
     * stalled, so the probe must come back out of the count.
     */
    @Test
    public void testTlpProbeIsNotASynResend() {
        // The measured false positive: one real SYN plus one probe.
        assertEquals(1, Connection.synHardSends(2, 0, 1));
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true,
                                                         Connection.synHardSends(2, 0, 1),
                                                         ROTATE_AFTER));
    }

    /** Soft-failure resends never reached the fabric, so they are not loss. */
    @Test
    public void testSoftResendsAreExcluded() {
        assertEquals(2, Connection.synHardSends(5, 3, 0));
        assertEquals(0, Connection.synHardSends(9, 9, 0));
    }

    /** Both exclusions together, and the clamp at zero. */
    @Test
    public void testExclusionsAndClamp() {
        assertEquals(2, Connection.synHardSends(6, 3, 1));
        assertEquals(0, Connection.synHardSends(2, 3, 4));
        assertEquals(0, Connection.synHardSends(0, 0, 0));
    }

    // ---------- only the SYN phase rotates ----------

    /** A data packet of a live connection is never rotated by this rule. */
    @Test
    public void testNonSynPacketNeverRotates() {
        assertFalse(Connection.synResendNeedsFreshTunnel(false, true, 10, ROTATE_AFTER));
        assertFalse(Connection.synResendNeedsFreshTunnel(false, false, 10, ROTATE_AFTER));
    }

    /** Once the connection is established, the retransmit-stall path owns rotation. */
    @Test
    public void testEstablishedConnectionNeverRotatesHere() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, false, 10, ROTATE_AFTER));
    }

    /** Both conditions are needed: a SYN on an established connection is a late duplicate. */
    @Test
    public void testRequiresBothSynAndUnacked() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, false, 100, ROTATE_AFTER));
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 100, ROTATE_AFTER));
    }

    // ---------- the original tunnel gets its attempts first ----------

    /** The initial send is one hard send: the first resend stays on the same tunnel. */
    @Test
    public void testFirstResendKeepsTheOriginalTunnel() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 0, ROTATE_AFTER));
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 1, ROTATE_AFTER));
    }

    /** The boundary is inclusive: two hard sends unacked, so the third rotates. */
    @Test
    public void testRotatesAtTheThreshold() {
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 2, ROTATE_AFTER));
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 3, ROTATE_AFTER));
    }

    /** The measured stall rotates from its third send onward, not at its tenth. */
    @Test
    public void testMeasuredStallRotatesEarly() {
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 2, ROTATE_AFTER));
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 10, ROTATE_AFTER));
        assertEquals(2, Connection.SYN_RESEND_ROTATION_SENDS);
    }

    /** A negative hard count is treated as none, so nothing rotates. */
    @Test
    public void testNegativeCountStaysPut() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, -1, ROTATE_AFTER));
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, Integer.MIN_VALUE,
                                                         ROTATE_AFTER));
    }

    /** A threshold of 0 rotates every SYN-phase send, which is what it says. */
    @Test
    public void testZeroThresholdRotatesImmediately() {
        assertTrue(Connection.synResendNeedsFreshTunnel(true, true, 0, 0));
    }

    /** A threshold above the send count never rotates. */
    @Test
    public void testHighThresholdNeverRotates() {
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 10, 11));
        assertFalse(Connection.synResendNeedsFreshTunnel(true, true, 10, Integer.MAX_VALUE));
    }
}
