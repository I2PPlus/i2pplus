package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure retransmit decision ContinueTimer makes as Alice, extracted
 * into {@link PeerTestManager#decideRetransmit} so it can be exercised without
 * a running peer test.
 *
 * The lock scope around this decision is part of the contract too: the decision
 * reads the current test under the instance lock and the send happens outside
 * it, so only the decision is under test here.
 *
 * @since 0.9.71+
 */
public class PeerTestManagerRetransmitDecisionTest {

    /** Charlie's port when he is reported firewalled (matches PeerTestManager.PENDING_PORT). */
    private static final int PENDING_PORT = 99999;

    /** Neither Bob nor Charlie has answered: retransmit msg 1 to Bob. */
    @Test
    public void testNothingHeardRetransmitToBob() {
        assertEquals(PeerTestManager.ACTION_SEND_BOB,
                     PeerTestManager.decideRetransmit(0, 0, 1234, true, 10000));
    }

    /**
     * Heard from Bob only, and not long enough past msg 4 to probe for Symmetric
     * NAT: nothing to send, the timer just waits.
     */
    @Test
    public void testBobOnlyWithinSymnatGrace() {
        assertEquals(PeerTestManager.ACTION_NONE,
                     PeerTestManager.decideRetransmit(9000, 0, 1234, true, 10000));
    }

    /**
     * Heard from Bob only, more than the grace period, with a usable Charlie:
     * send msg 6 so a Symmetric NAT can be detected.
     */
    @Test
    public void testBobOnlyPastSymnatGraceSendsToCharlie() {
        assertEquals(PeerTestManager.ACTION_SEND_CHARLIE,
                     PeerTestManager.decideRetransmit(1000, 0, 1234, true, 10000));
    }

    /** Exactly at the grace period is not "long enough": the test is strictly greater than. */
    @Test
    public void testBobOnlyExactlyAtGraceIsNone() {
        assertEquals(PeerTestManager.ACTION_NONE,
                     PeerTestManager.decideRetransmit(5000, 0, 1234, true, 10000));
    }

    /** One ms past the grace period does qualify. */
    @Test
    public void testBobOnlyOneMsPastGrace() {
        assertEquals(PeerTestManager.ACTION_SEND_CHARLIE,
                     PeerTestManager.decideRetransmit(4999, 0, 1234, true, 10000));
    }

    /** Charlie reported firewalled: no IP/port to trust, so no msg 6. */
    @Test
    public void testBobOnlyFirewalledCharlieNoSend() {
        assertEquals(PeerTestManager.ACTION_NONE,
                     PeerTestManager.decideRetransmit(1000, 0, PENDING_PORT, true, 10000));
    }

    /** No Charlie intro key yet: SSU2 msg 6 cannot be built. */
    @Test
    public void testBobOnlyNoCharlieIntroKeyNoSend() {
        assertEquals(PeerTestManager.ACTION_NONE,
                     PeerTestManager.decideRetransmit(1000, 0, 1234, false, 10000));
    }

    /**
     * Heard from Charlie only, no reply from Bob: retransmit msg 1 to Bob so he
     * retransmits his reply.
     */
    @Test
    public void testCharlieOnlyRetransmitToBob() {
        assertEquals(PeerTestManager.ACTION_SEND_BOB,
                     PeerTestManager.decideRetransmit(0, 500, PENDING_PORT, false, 10000));
    }

    /** Heard from both, awaiting msg 7: send msg 6 to Charlie. */
    @Test
    public void testBothHeardSendsToCharlie() {
        assertEquals(PeerTestManager.ACTION_SEND_CHARLIE,
                     PeerTestManager.decideRetransmit(1000, 2000, 1234, true, 10000));
    }

    /** Heard from both but Charlie is firewalled: msg 5 was not from a usable address. */
    @Test
    public void testBothHeardFirewalledCharlieNoSend() {
        assertEquals(PeerTestManager.ACTION_NONE,
                     PeerTestManager.decideRetransmit(1000, 2000, PENDING_PORT, true, 10000));
    }
}
