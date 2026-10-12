package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The NTCP2 handshake round trip time has exactly one source.
 *
 * <p>{@code EstablishBase.changeState()} is the single funnel through which both
 * establishment directions reach VERIFIED, and it is the only caller of
 * {@code NTCPConnection.noteEstablishmentRtt()}. That matters because the
 * transport publishes the result as {@code getEstimatedRTT()}: if two paths
 * could each derive an RTT for the same session, the value a peer was judged on
 * would depend on which ran last, and {@code PeerProfile.setFirstHopRtt} would
 * be smoothing two different quantities rather than successive samples of one.
 *
 * @since 0.9.71+
 */
public class NTCPConnectionEstablishmentRttTest {

    // ---------- shouldRecordEstablishmentRtt ----------

    @Test
    public void testFirstSampleIsRecorded() {
        assertTrue(NTCPConnection.shouldRecordEstablishmentRtt(0, 1));
        assertTrue(NTCPConnection.shouldRecordEstablishmentRtt(0, 250));
    }

    @Test
    public void testSecondSampleIsRejected() {
        assertFalse("an already-recorded latency must not be overwritten",
                    NTCPConnection.shouldRecordEstablishmentRtt(120, 900));
    }

    @Test
    public void testNonPositiveIntervalsAreRejected() {
        assertFalse(NTCPConnection.shouldRecordEstablishmentRtt(0, 0));
        assertFalse(NTCPConnection.shouldRecordEstablishmentRtt(0, -1));
    }

    @Test
    public void testOverflowIntervalsAreRejected() {
        // a clock that went backwards, or an interval that cannot be an int;
        // recording it would poison the value for the life of the session
        assertFalse(NTCPConnection.shouldRecordEstablishmentRtt(0, Integer.MAX_VALUE));
        assertFalse(NTCPConnection.shouldRecordEstablishmentRtt(0, Integer.MAX_VALUE + 5000L));
    }

    @Test
    public void testEveryRejectionStillHoldsWhenAlreadyRecorded() {
        // both conditions at once: the guard must not depend on which fires first
        assertFalse(NTCPConnection.shouldRecordEstablishmentRtt(50, 0));
        assertFalse(NTCPConnection.shouldRecordEstablishmentRtt(50, -10));
        assertFalse(NTCPConnection.shouldRecordEstablishmentRtt(50, Integer.MAX_VALUE));
    }

    @Test
    public void testBoundaryAtMaxIntIsAcceptedOneBelow() {
        assertTrue(NTCPConnection.shouldRecordEstablishmentRtt(0, Integer.MAX_VALUE - 1L));
    }
}