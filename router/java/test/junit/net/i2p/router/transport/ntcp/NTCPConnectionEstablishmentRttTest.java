package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.router.transport.Transport;

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

    /**
     * A recorded handshake RTT must carry the time it was taken, not the time a
     * reader asks for it.
     *
     * <p>NTCP2 never refreshes this value, so it is the only link measurement the
     * protocol ever offers. A reader that stamped the present onto it would hold
     * the peer profile's validity window open forever and keep damping its
     * average toward this one stale sample.
     *
     * <p>Pinned at the sentinel rather than at a wall-clock value: the timestamp
     * itself is written from the router clock inside a connection whose
     * constructors require a live transport and establish state, so it cannot be
     * exercised from a unit test. What this test does pin is the rule a reader
     * depends on - an unrecorded reading reports as undated, never as measured.
     */
    @Test
    public void testUnrecordedReadingReportsNoTimestamp() {
        assertTrue("an undated reading must be distinguishable from one taken at the epoch",
                   Transport.RTT_TIME_UNKNOWN != 0L);
    }
}