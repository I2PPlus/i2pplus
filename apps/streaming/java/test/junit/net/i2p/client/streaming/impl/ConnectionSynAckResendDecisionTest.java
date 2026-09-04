package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the rate-bind decision for SYN-ACK re-sends in
 * {@link Connection#shouldResendSynAck(long, int, long, long, int)}.
 *
 * <p>In response to a retransmitted SYN for an existing (half-open) connection,
 * {@code ConnectionHandler#resendSynAck} mints a fresh signed SYN-ACK into the
 * shared FIFO packet queue.  A latency-bound client — RTO shorter than the I2P
 * round trip — retransmits its SYN faster than SYN-ACKs arrive, so every
 * retransmit would otherwise spawn another SYN-ACK and more egress, closing a
 * self-amplifying loop.  The decision is now:
 *
 * <ul>
 *   <li><b>spacing</b>: no re-send until at least {@code minSpacingMs} has elapsed
 *       since the last SYN-ACK.  Snapshot at read time: a rejected re-send neither
 *       advances nor extends the window.</li>
 *   <li><b>count cap</b>: at most {@code maxResends} re-sends per connection
 *       lifetime, so a connection stuck half-open cannot mint SYN-ACKs forever.</li>
 * </ul>
 *
 * <p>History is authoritative and the method is pure: no state mutation, so the
 * throttle is testable without a running router.
 *
 * @since 0.9.71+
 */
public class ConnectionSynAckResendDecisionTest {

    private static final long MIN_SPACING = Connection.SYN_ACK_RESEND_MIN_SPACING_MS;
    private static final int MAX_RESENDS = Connection.SYN_ACK_RESEND_MAX;

    /** The very first re-send is always allowed: nothing to space against. */
    @Test
    public void testNoPriorSendAlwaysAllowed() {
        assertTrue(Connection.shouldResendSynAck(0, 0, 100000, MIN_SPACING, MAX_RESENDS));
        assertTrue(Connection.shouldResendSynAck(-1, 0, 100000, MIN_SPACING, MAX_RESENDS));
    }

    /** A re-send within the min spacing window is rejected. */
    @Test
    public void testWithinSpacingRejected() {
        long sent = 100000;
        assertFalse(Connection.shouldResendSynAck(sent, 1, sent + MIN_SPACING - 1, MIN_SPACING, MAX_RESENDS));
        assertFalse(Connection.shouldResendSynAck(sent, 1, sent + 1, MIN_SPACING, MAX_RESENDS));
    }

    /** A re-send exactly at the min spacing boundary is allowed (>=). */
    @Test
    public void testSpacingBoundaryAllowed() {
        long sent = 100000;
        assertTrue(Connection.shouldResendSynAck(sent, 1, sent + MIN_SPACING, MIN_SPACING, MAX_RESENDS));
    }

    /** After the spacing has elapsed, re-sends are allowed while under the cap. */
    @Test
    public void testPastSpacingAllowed() {
        long sent = 100000;
        assertTrue(Connection.shouldResendSynAck(sent, 1, sent + MIN_SPACING + 1, MIN_SPACING, MAX_RESENDS));
        assertTrue(Connection.shouldResendSynAck(sent, MAX_RESENDS - 1, sent + MIN_SPACING * 10, MIN_SPACING, MAX_RESENDS));
    }

    /** The count cap vetoes re-sends regardless of elapsed time. */
    @Test
    public void testCountCapRejected() {
        long sent = 100000;
        long farFuture = sent + MIN_SPACING * 1000;
        assertFalse(Connection.shouldResendSynAck(sent, MAX_RESENDS, farFuture, MIN_SPACING, MAX_RESENDS));
        assertFalse(Connection.shouldResendSynAck(sent, MAX_RESENDS + 5, farFuture, MIN_SPACING, MAX_RESENDS));
    }

    /** Rate vs count semantics: spacing filters a burst even when count remains free. */
    @Test
    public void testBurstWithinSpacingOnlyOneSends() {
        long sent = 100000;
        // same wall clock, count still < cap -> all rejected by spacing
        assertFalse(Connection.shouldResendSynAck(sent, 1, 100001, MIN_SPACING, MAX_RESENDS));
        assertFalse(Connection.shouldResendSynAck(sent, 2, 100001, MIN_SPACING, MAX_RESENDS));
        assertFalse(Connection.shouldResendSynAck(sent, 3, 100001, MIN_SPACING, MAX_RESENDS));
    }

    /** The throttle window is a snapshot: a rejected call must not advance lastSent. */
    @Test
    public void testRejectedCallDoesNotAdvanceWindow() {
        long sent = 100000;
        // rejected by spacing
        assertFalse(Connection.shouldResendSynAck(sent, 1, sent + 1, MIN_SPACING, MAX_RESENDS));
        // same inputs again still rejected with the identical window
        assertFalse(Connection.shouldResendSynAck(sent, 1, sent + 1, MIN_SPACING, MAX_RESENDS));
        // and the window is unchanged: the original spacing still gates
        assertTrue(Connection.shouldResendSynAck(sent, 1, sent + MIN_SPACING, MIN_SPACING, MAX_RESENDS));
    }

    /** A zero/negative spacing means every under-cap call is allowed (lint guard). */
    @Test
    public void testZeroSpacing() {
        long sent = 100000;
        assertTrue(Connection.shouldResendSynAck(sent, 1, sent + 1, 0, MAX_RESENDS));
        assertTrue(Connection.shouldResendSynAck(sent, 1, sent + 1, -5, MAX_RESENDS));
    }

    /** A zero/negative cap blocks everything. */
    @Test
    public void testZeroCapBlocksEverything() {
        assertFalse(Connection.shouldResendSynAck(0, 0, 100000, MIN_SPACING, 0));
        assertFalse(Connection.shouldResendSynAck(0, 0, 100000, MIN_SPACING, -1));
    }

    /** Clock skew (now behind lastSent) must not allow an unsynchronized re-send. */
    @Test
    public void testClockSkewRejected() {
        long sent = 100000;
        assertFalse(Connection.shouldResendSynAck(sent, 1, sent - 1000, MIN_SPACING, MAX_RESENDS));
    }

    /** Large timestamps must not overflow the interval subtraction. */
    @Test
    public void testLargeValuesNoOverflow() {
        long sent = Long.MAX_VALUE / 2 - 1000;
        // identical timestamps: interval 0 < MIN_SPACING, rejected
        assertFalse(Connection.shouldResendSynAck(sent, 1, sent, MIN_SPACING, MAX_RESENDS));
        // just under the spacing boundary: interval is positive and < MIN_SPACING
        assertFalse(Connection.shouldResendSynAck(sent, 1, sent + MIN_SPACING - 1, MIN_SPACING, MAX_RESENDS));
        assertTrue(Connection.shouldResendSynAck(sent, 1, sent + MIN_SPACING, MIN_SPACING, MAX_RESENDS));
    }
}
