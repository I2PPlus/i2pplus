package net.i2p.router.message;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;

/**
 * The pure decision behind the reply-LeaseSet bundle gate,
 * {@link OutboundClientMessageOneShotJob#replyLeaseSetPending}.
 * <p>
 * Every outbound client message offers our own LeaseSet to the peer as a reply, and the
 * offer is declined whenever the peer already holds a copy at least as new as ours. That
 * decline is the ordinary outcome of a transfer, not an event worth an INFO line: one
 * 49 MB download through this router declined it 30,315 times in three minutes, one line
 * per 1,812-byte packet, which was 9.5% of everything the router logged. The decision
 * itself is unchanged by that - only where it is reported - so these tests pin the
 * boundary the decision turns on, including the equality case that keeps a re-offer of an
 * unchanged LeaseSet from being sent again.
 *
 * @since 0.9.72+
 */
public class ReplyLeaseSetDecisionTest {

    /** Gateway of the single lease every test LeaseSet carries. */
    private static final Hash GATEWAY = new Hash(new byte[32]);

    /**
     * Declares the no-arg constructor the class would otherwise inherit, so the test class
     * itself is documented.
     *
     * @since 0.9.72+
     */
    public ReplyLeaseSetDecisionTest() {
    }

    /**
     * A LeaseSet whose date - the earliest lease expiry it reports - is {@code expiry}, so
     * a test can compare two copies without waiting for a clock to move. An empty LeaseSet
     * would report -1 and every comparison would be about the empty case instead.
     *
     * @param expiry the earliest lease expiry the returned LeaseSet reports, in ms
     * @return a single-lease LeaseSet expiring at {@code expiry}
     * @since 0.9.72+
     */
    private static LeaseSet dated(long expiry) {
        Lease lease = new Lease();
        lease.setGateway(GATEWAY);
        lease.setTunnelId(new TunnelId(1L));
        lease.setEndDate(expiry);
        LeaseSet ls = new LeaseSet();
        ls.addLease(lease);
        return ls;
    }

    /**
     * With nothing acknowledged for this pair yet, the first message has to carry the
     * LeaseSet.
     *
     * @since 0.9.72+
     */
    @Test
    public void testNothingAcknowledgedYetStillSends() {
        assertTrue(OutboundClientMessageOneShotJob.replyLeaseSetPending(null, dated(1000)));
    }

    /**
     * An acknowledged copy of the same date is the same copy - re-sending it buys nothing,
     * and this equality is what held the flood shut for the whole download.
     *
     * @since 0.9.72+
     */
    @Test
    public void testAcknowledgedCopyOfTheSameDateNeedsNothing() {
        assertFalse(OutboundClientMessageOneShotJob.replyLeaseSetPending(dated(1000), dated(1000)));
    }

    /**
     * A newer LeaseSet than the one the peer holds has to go out, which is the only thing
     * that flips the decision during a long-lived session.
     *
     * @since 0.9.72+
     */
    @Test
    public void testOurCopyBeingNewerSends() {
        assertTrue(OutboundClientMessageOneShotJob.replyLeaseSetPending(dated(999), dated(1000)));
    }

    /**
     * An acknowledged copy newer than what the client NetDB holds now - a republish that
     * has not reached this facade yet - still needs nothing; the peer is ahead of us, not
     * behind.
     *
     * @since 0.9.72+
     */
    @Test
    public void testPeerAlreadyAheadNeedsNothing() {
        assertFalse(OutboundClientMessageOneShotJob.replyLeaseSetPending(dated(1001), dated(1000)));
    }

    /**
     * A republish mid-session opens the gate for exactly one message: once the fresh copy
     * is acknowledged, the steady state of declining returns. This is the sequence a
     * download runs through - thousands of declines, one send per republish - and it is
     * why the reporting sits at DEBUG behind the throttler rather than at INFO.
     *
     * @since 0.9.72+
     */
    @Test
    public void testRepublishOpensTheGateOnce() {
        long published = 1700000000000L;
        LeaseSet current = dated(published);
        LeaseSet delivered = dated(published);
        assertFalse(OutboundClientMessageOneShotJob.replyLeaseSetPending(delivered, current));
        // republished a minute later
        LeaseSet fresh = dated(published + 60 * 1000L);
        assertTrue(OutboundClientMessageOneShotJob.replyLeaseSetPending(delivered, fresh));
        // and once that one is acknowledged, the gate closes again
        assertFalse(OutboundClientMessageOneShotJob.replyLeaseSetPending(fresh, fresh));
    }
}
