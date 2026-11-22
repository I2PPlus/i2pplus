package net.i2p.router.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;

import org.junit.Test;

/**
 * Whether a send to a destination this router hosts waits for its own LeaseSet
 * to be republished instead of failing.
 * <p>
 * publish() mirrors a locally hosted LeaseSet into the main NetDb, so a send
 * that lands between the old copy expiring and the new one being stored finds
 * nothing in either NetDb, and no search can answer a key this router holds.
 * The lookup fast-fails with that fact, and the send used to report NO_LEASESET
 * to the client there - costing the peer a streaming retransmit cycle for a gap
 * the republish job closes by itself. The send now waits, and asks our own client
 * to re-sign once per gap, because a resumed destination has no reason to run its
 * republish cycle early and its pool signs nothing until asked.
 *
 * @since 0.9.72+
 */
public class OutboundClientMessageMintGapRetryTest {

    /** A fixed instant for every deadline in these tests. */
    private static final long NOW = 1_700_000_000_000L;

    /**
     * Declares the no-arg constructor the class would otherwise inherit, so
     * the test class itself is documented.
     *
     * @since 0.9.72+
     */
    public OutboundClientMessageMintGapRetryTest() {
    }

    /**
     * Only the mint gap waits. A lookup that searched the network, one held by
     * the negative cache, one rejected by store validation and one on a NetDb
     * that never started each have their own answer and no pending republish.
     *
     * @since 0.9.72+
     */
    @Test
    public void onlyTheMintGapWaits() {
        int gap = KademliaNetworkDatabaseFacade.LOOKUP_FAILED_LOCAL_NOT_STORED;
        long expiry = NOW + 60 * 1000L;
        assertTrue(OutboundClientMessageOneShotJob.mintGapRetryPending(
                       gap, 0, NOW, expiry));
        assertFalse(OutboundClientMessageOneShotJob.mintGapRetryPending(
                        KademliaNetworkDatabaseFacade.LOOKUP_SEARCH_STARTED,
                        0, NOW, expiry));
        assertFalse(OutboundClientMessageOneShotJob.mintGapRetryPending(
                        KademliaNetworkDatabaseFacade.LOOKUP_FAILED_NEGATIVE_CACHE,
                        0, NOW, expiry));
        assertFalse(OutboundClientMessageOneShotJob.mintGapRetryPending(
                        KademliaNetworkDatabaseFacade.LOOKUP_FAILED_STORE_REJECTED,
                        0, NOW, expiry));
        assertFalse(OutboundClientMessageOneShotJob.mintGapRetryPending(
                        KademliaNetworkDatabaseFacade.LOOKUP_FAILED_NOT_INITIALIZED,
                        0, NOW, expiry));
    }

    /**
     * The budget is finite: the last allowed wait is still taken, the next one
     * is refused, so a destination whose republish never lands is failed after
     * the budget rather than held until the message expires.
     *
     * @since 0.9.72+
     */
    @Test
    public void theBudgetIsFinite() {
        int gap = KademliaNetworkDatabaseFacade.LOOKUP_FAILED_LOCAL_NOT_STORED;
        long expiry = NOW + 60 * 1000L;
        int retries = OutboundClientMessageOneShotJob.MINT_GAP_RETRIES;
        assertTrue(OutboundClientMessageOneShotJob.mintGapRetryPending(
                       gap, retries - 1, NOW, expiry));
        assertFalse(OutboundClientMessageOneShotJob.mintGapRetryPending(
                        gap, retries, NOW, expiry));
        assertFalse(OutboundClientMessageOneShotJob.mintGapRetryPending(
                        gap, retries + 1, NOW, expiry));
    }

    /**
     * A retry that lands after the message has expired is only a delay, so the
     * deadline is tested with the wait added, not against the moment the lookup
     * failed.
     *
     * @since 0.9.72+
     */
    @Test
    public void aRetryAfterTheExpiryIsRefused() {
        int gap = KademliaNetworkDatabaseFacade.LOOKUP_FAILED_LOCAL_NOT_STORED;
        int wait = OutboundClientMessageOneShotJob.MINT_GAP_RETRY_MS;
        assertTrue(OutboundClientMessageOneShotJob.mintGapRetryPending(
                       gap, 0, NOW, NOW + wait + 1));
        assertFalse(OutboundClientMessageOneShotJob.mintGapRetryPending(
                        gap, 0, NOW, NOW + wait));
        assertFalse(OutboundClientMessageOneShotJob.mintGapRetryPending(
                        gap, 0, NOW, NOW));
    }

    /**
     * The whole loop, replayed: a send keeps waiting while the budget and the
     * deadline allow and then fails, so the added delay before the client is
     * told is bounded by the budget rather than by the message's lifetime.
     *
     * @since 0.9.72+
     */
    @Test
    public void theLoopEndsAtTheBudget() {
        int gap = KademliaNetworkDatabaseFacade.LOOKUP_FAILED_LOCAL_NOT_STORED;
        int retries = 0;
        long now = NOW;
        long expiry = NOW + 10 * 60 * 1000L;
        while (OutboundClientMessageOneShotJob.mintGapRetryPending(
                   gap, retries, now, expiry)) {
            retries++;
            now += OutboundClientMessageOneShotJob.MINT_GAP_RETRY_MS;
        }
        assertEquals(OutboundClientMessageOneShotJob.MINT_GAP_RETRIES, retries);
        assertEquals("added delay before the failure is reported",
                     NOW + OutboundClientMessageOneShotJob.MINT_GAP_RETRIES *
                     (long) OutboundClientMessageOneShotJob.MINT_GAP_RETRY_MS,
                     now);
    }

    /**
     * A send with little life left is not extended past it: the budget is the
     * ceiling only while the message still has a future to be sent into, so a
     * short-lived send stops waiting at its own expiry instead.
     *
     * @since 0.9.72+
     */
    @Test
    public void theLoopEndsAtTheExpiryForAShortLivedSend() {
        int gap = KademliaNetworkDatabaseFacade.LOOKUP_FAILED_LOCAL_NOT_STORED;
        int wait = OutboundClientMessageOneShotJob.MINT_GAP_RETRY_MS;
        int retries = 0;
        long now = NOW;
        long expiry = NOW + 3 * wait;
        while (OutboundClientMessageOneShotJob.mintGapRetryPending(
                   gap, retries, now, expiry)) {
            retries++;
            now += wait;
        }
        assertEquals("a send with 3 waits of life left takes 2", 2, retries);
    }

    /**
     * One re-sign request per gap: never asked before gets asked, a request that
     * lands exactly on the interval is due, and anything inside it is not - so a
     * destination whose LeaseSet is missing under a burst of sends is asked once,
     * not once per message.
     *
     * @since 0.9.72+
     */
    @Test
    public void oneReSignRequestPerGap() {
        long interval = OutboundClientMessageOneShotJob.MINT_GAP_NUDGE_MS;
        assertTrue(OutboundClientMessageOneShotJob.mintGapNudgeDue(
                       null, NOW, interval));
        assertFalse(OutboundClientMessageOneShotJob.mintGapNudgeDue(
                        NOW, NOW, interval));
        assertFalse(OutboundClientMessageOneShotJob.mintGapNudgeDue(
                        NOW, NOW + interval - 1, interval));
        assertTrue(OutboundClientMessageOneShotJob.mintGapNudgeDue(
                       NOW, NOW + interval, interval));
        // a clock that moves backwards does not buy an extra request
        assertFalse(OutboundClientMessageOneShotJob.mintGapNudgeDue(
                        NOW, NOW - 1000L, interval));
    }
}
