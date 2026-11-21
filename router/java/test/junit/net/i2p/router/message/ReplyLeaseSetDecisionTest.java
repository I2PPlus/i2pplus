package net.i2p.router.message;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;

/**
 * The pure decisions behind the reply-LeaseSet bundle gate,
 * {@link OutboundClientMessageOneShotJob#replyLeaseSetPending} and
 * {@link OutboundClientMessageOneShotJob#replyLeaseSetRecentlySent}.
 * <p>
 * Every outbound client message offers our own LeaseSet to the peer as a reply, and the
 * offer is declined whenever the peer already holds a copy at least as new as ours. That
 * decline is the ordinary outcome of a transfer, not an event worth an INFO line: one
 * 49 MB download through this router declined it 30,315 times in three minutes, one line
 * per 1,812-byte packet, which was 9.5% of everything the router logged. The decision
 * itself is unchanged by that - only where it is reported - so these tests pin the
 * boundary the decision turns on, including the equality case that keeps a re-offer of an
 * unchanged LeaseSet from being sent again.
 * <p>
 * The second decision covers the other half: a peer that never acknowledges holds nothing
 * in the cache, so every message to it carried a fresh copy - 7,606 copies across 1,115
 * destinations in one live router's 133-minute window, 2,142 of them for one destination.
 * These tests pin the resend interval that turns that back into one copy a minute.
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

    /**
     * Nothing has gone out to this pair yet, so the first message has to carry
     * the LeaseSet: the peer cannot reply to a message it has no reply path for.
     *
     * @since 0.9.72+
     */
    @Test
    public void testFirstContactAlwaysSends() {
        assertFalse(OutboundClientMessageOneShotJob.replyLeaseSetRecentlySent(
                        null, 1_700_000_000_000L,
                        OutboundClientMessageOneShotJob.REPLY_LEASESET_RESEND_MS));
    }

    /**
     * A copy that went out half an interval ago is either still on its way or in
     * the peer's hands and simply unacknowledged. Either way a second copy now
     * is the payload cost the interval exists to remove.
     *
     * @since 0.9.72+
     */
    @Test
    public void testWithinTheIntervalHoldsOff() {
        long now = 1_700_000_000_000L;
        assertTrue(OutboundClientMessageOneShotJob.replyLeaseSetRecentlySent(
                       Long.valueOf(now - 30 * 1000L), now,
                       OutboundClientMessageOneShotJob.REPLY_LEASESET_RESEND_MS));
    }

    /**
     * The boundary is the interval itself: one ms short of it holds off, at it
     * the next copy goes out, so a lost first send is repaired within a minute.
     *
     * @since 0.9.72+
     */
    @Test
    public void testBoundaryIsTheInterval() {
        long now = 1_700_000_000_000L;
        int interval = OutboundClientMessageOneShotJob.REPLY_LEASESET_RESEND_MS;
        assertTrue(OutboundClientMessageOneShotJob.replyLeaseSetRecentlySent(
                       Long.valueOf(now - interval + 1), now, interval));
        assertFalse(OutboundClientMessageOneShotJob.replyLeaseSetRecentlySent(
                        Long.valueOf(now - interval), now, interval));
    }

    /**
     * The measured flood: 2,142 messages to one destination that never
     * acknowledged, across the 133-minute window, replayed in order. The answer
     * that sends a copy also starts the next interval, so the copies fall to at
     * most one a minute while the destination is still re-offered throughout.
     *
     * @since 0.9.72+
     */
    @Test
    public void testMeasuredUnacknowledgedFloodSendsOnePerMinute() {
        long start = 1_700_000_000_000L;
        int messages = 2142;
        long windowMs = 133 * 60 * 1000L;
        long spacing = windowMs / messages;
        int interval = OutboundClientMessageOneShotJob.REPLY_LEASESET_RESEND_MS;
        Long sentOn = null;
        int sends = 0;
        long now = start;
        for (int i = 0; i < messages; i++) {
            now += spacing;
            if (!OutboundClientMessageOneShotJob.replyLeaseSetRecentlySent(
                    sentOn, now, interval)) {
                sends++;
                sentOn = Long.valueOf(now);
            }
        }
        assertTrue(messages + " messages became " + sends + " copies",
                   sends <= windowMs / interval + 1);
        assertTrue("still re-offered throughout: " + sends, sends >= 100);
    }
}
