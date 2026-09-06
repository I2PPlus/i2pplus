package net.i2p.router.transport.udp;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for the pure decision helpers extracted from
 * {@link PeerState#finishAndAllocate}: per-message outcome classification and
 * the retransmit-timer firing policy.
 *
 * @since 0.9.71+
 */
public class PeerStateDecisionTest {

    // ----- classifyOutcome -----

    @Test
    public void completeDominatesExpired() {
        assertEquals(PeerState.Outcome.COMPLETE, PeerState.classifyOutcome(true, true, false));
    }

    @Test
    public void completeDominatesOverSent() {
        assertEquals(PeerState.Outcome.COMPLETE, PeerState.classifyOutcome(true, false, true));
    }

    @Test
    public void completeIsComplete() {
        assertEquals(PeerState.Outcome.COMPLETE, PeerState.classifyOutcome(true, false, false));
    }

    @Test
    public void expiredDominatesOverSent() {
        assertEquals(PeerState.Outcome.EXPIRED, PeerState.classifyOutcome(false, true, true));
    }

    @Test
    public void expiredIsExpired() {
        assertEquals(PeerState.Outcome.EXPIRED, PeerState.classifyOutcome(false, true, false));
    }

    @Test
    public void overSentIsOverSent() {
        assertEquals(PeerState.Outcome.OVER_SENT, PeerState.classifyOutcome(false, false, true));
    }

    @Test
    public void noneIsSendable() {
        assertEquals(PeerState.Outcome.SENDABLE, PeerState.classifyOutcome(false, false, false));
    }

    // ----- retransmitFireTime -----

    @Test
    public void armsOnFirstArming() {
        assertEquals(1500L, PeerState.retransmitFireTime(true, false, 1000L, 500L));
    }

    @Test
    public void armsDuringFastRetransmit() {
        assertEquals(1500L, PeerState.retransmitFireTime(false, true, 1000L, 500L));
    }

    @Test
    public void armsWhenBoth() {
        assertEquals(1500L, PeerState.retransmitFireTime(true, true, 1000L, 500L));
    }

    @Test
    public void leavesExistingTimerAlone() {
        assertEquals(-1L, PeerState.retransmitFireTime(false, false, 1000L, 500L));
    }
}
