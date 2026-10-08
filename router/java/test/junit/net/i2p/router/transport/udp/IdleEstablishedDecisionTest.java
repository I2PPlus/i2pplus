package net.i2p.router.transport.udp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 *  Truth table for the established-peer idle predicate.
 *
 *  <p>The rule this pins is that idleness is decided by elapsed time in both
 *  directions alone. It previously also required the peer to have accumulated
 *  more than two consecutive send failures, which a half-open session never does:
 *  the counter is incremented from {@code UDPTransport.failed()} only after a
 *  message exhausts its retransmits, so a peer that still ACKs some traffic — or
 *  one we have stopped writing to entirely — was never reaped and kept answering
 *  {@code isEstablished()} true indefinitely.
 *
 *  @since 0.9.71+
 */
public class IdleEstablishedDecisionTest {

    /** Same value as {@code UDPTransport.EXPIRE_TIMEOUT}. */
    private static final long IDLE = 20*60*1000L;
    private static final long NOW = 1000000000L;

    /**
     *  The case that motivated the change: idle far past the threshold in both
     *  directions must be reaped on time alone.
     */
    @Test
    public void idleBothWaysIsReaped() {
        assertTrue(UDPTransport.isIdleEstablished(NOW, NOW - IDLE - 1, NOW - IDLE - 1, IDLE));
    }

    /**
     *  A peer that is still answering is never a candidate, however long ago we
     *  last wrote to it. This is the asymmetry that must not erode into "both idle".
     */
    @Test
    public void freshReceiveKeepsTheSession() {
        assertFalse("receive is fresh",
                    UDPTransport.isIdleEstablished(NOW, NOW - IDLE - 1, NOW - 1000, IDLE));
        assertFalse("receive is now",
                    UDPTransport.isIdleEstablished(NOW, NOW - IDLE - 1, NOW, IDLE));
    }

    /** Symmetrically, a peer we are still writing to is not idle. */
    @Test
    public void freshSendKeepsTheSession() {
        assertFalse("send is fresh",
                    UDPTransport.isIdleEstablished(NOW, NOW - 1000, NOW - IDLE - 1, IDLE));
        assertFalse("send is now",
                    UDPTransport.isIdleEstablished(NOW, NOW, NOW - IDLE - 1, IDLE));
    }

    /**
     *  The threshold is strict, matching the &gt; comparison it replaced, so a peer
     *  sitting exactly on the boundary is kept for one more pass.
     */
    @Test
    public void theThresholdIsStrict() {
        assertFalse("exactly at threshold",
                    UDPTransport.isIdleEstablished(NOW, NOW - IDLE, NOW - IDLE, IDLE));
        assertTrue("one ms past",
                   UDPTransport.isIdleEstablished(NOW, NOW - IDLE - 1, NOW - IDLE - 1, IDLE));
    }

    /**
     *  A zero timestamp means never sent or never received, which is silence we
     *  cannot distinguish from a young session. Treating it as idle would reap
     *  freshly established peers.
     */
    @Test
    public void neverSpokenIsNotIdle() {
        assertFalse("never sent", UDPTransport.isIdleEstablished(NOW, 0, NOW - IDLE - 1, IDLE));
        assertFalse("never received", UDPTransport.isIdleEstablished(NOW, NOW - IDLE - 1, 0, IDLE));
        assertFalse("neither", UDPTransport.isIdleEstablished(NOW, 0, 0, IDLE));
    }

    /** A clock that has not reached the last send must not produce a negative idle time. */
    @Test
    public void aTimestampAfterNowIsNotIdle() {
        assertFalse(UDPTransport.isIdleEstablished(NOW, NOW + 5000, NOW + 5000, IDLE));
    }

    /**
     *  Idle in one direction only is the normal state of a healthy one-way-heavy
     *  peer, and must not reap it.
     */
    @Test
    public void oneWayIdleIsNotBothWayIdle() {
        assertFalse(UDPTransport.isIdleEstablished(NOW, NOW - 60*1000, NOW - IDLE - 1, IDLE));
    }
}
