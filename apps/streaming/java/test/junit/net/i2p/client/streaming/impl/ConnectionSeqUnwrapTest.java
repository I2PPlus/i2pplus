package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the 32-bit sequence-number wrap handling.
 *
 * <p>The on-wire sequence field is 4 bytes unsigned while the in-memory
 * counter is an unbounded long. Past 2^32 packets on one stream, a raw
 * comparison sees the peer's ACK land "behind" our counter and the send
 * window wedges permanently, so every wire-supplied value must be resolved
 * back into local space ({@link Connection#unwrapSeq(long, long)}). A data
 * packet whose id is congruent to 0 mod 2^32 would also go out with wire
 * value 0, which the format reserves for "ack-only, no data" — the counter
 * therefore skips those values ({@link Connection#getNextOutboundPacketNum()}).
 *
 * @since 0.9.72+
 */
public class ConnectionSeqUnwrapTest {

    // ---- unwrapSeq ----

    /** Nothing seen yet (negative reference): the wire value is taken as-is. */
    @Test
    public void testNegativeRefPassesThrough() {
        assertEquals(0, Connection.unwrapSeq(0, -1));
        assertEquals(42, Connection.unwrapSeq(42, -1));
        assertEquals(0xffffffffL, Connection.unwrapSeq(0xffffffffL, -1));
    }

    /** Before the wrap point the mapping is the identity. */
    @Test
    public void testIdentityBeforeWrap() {
        assertEquals(1, Connection.unwrapSeq(1, 5));
        assertEquals(0xffffffffL - 10, Connection.unwrapSeq(0xffffffffL - 10, 0xffffffffL - 2));
    }

    /** A small wire value with a large reference resolves to the next epoch. */
    @Test
    public void testWrapForward() {
        long ref = Connection.SEQ_MOD + 3;
        assertEquals(Connection.SEQ_MOD + 1, Connection.unwrapSeq(1, ref));
        assertEquals(Connection.SEQ_MOD, Connection.unwrapSeq(0, ref));
    }

    /** A large wire value with a small reference resolves to the previous epoch. */
    @Test
    public void testWrapBackward() {
        long ref = 2;
        long wire = 0xffffffffL - 1;
        assertEquals(wire - Connection.SEQ_MOD, Connection.unwrapSeq(wire, ref));
    }

    /** The half-modulus window is half-open: exactly SEQ_HALF ahead stays. */
    @Test
    public void testHalfWindowBoundary() {
        long ref = Connection.SEQ_MOD + 1000;
        long ahead = ref + Connection.SEQ_HALF;
        assertEquals(ahead, Connection.unwrapSeq(ahead & 0xffffffffL, ref));
        // one further ahead wraps to the previous epoch
        long further = ahead + 1;
        assertEquals(further - Connection.SEQ_MOD, Connection.unwrapSeq(further & 0xffffffffL, ref));
        // symmetric on the low side: exactly SEQ_HALF behind wraps forward
        long behind = ref - Connection.SEQ_HALF;
        assertEquals(behind + Connection.SEQ_MOD, Connection.unwrapSeq(behind & 0xffffffffL, ref));
        // one less behind stays
        long nearer = behind + 1;
        assertEquals(nearer, Connection.unwrapSeq(nearer & 0xffffffffL, ref));
    }

    /** Round trip: unwrap(wire(x), ref) == x for in-window values. */
    @Test
    public void testRoundTripAcrossWrap() {
        for (long x = Connection.SEQ_MOD - 5; x <= Connection.SEQ_MOD + 5; x++) {
            long ref = Connection.SEQ_MOD;
            assertEquals(x, Connection.unwrapSeq(x & 0xffffffffL, ref));
        }
    }

    // ---- the wire-zero skip rule ----

    /**
     * The stream starts at block 0: the SYN/SYN-ACK consumes it, and the
     * receiver only promotes the first data block when the SYN registered 0
     * (MessageInputStream's in-order gate is _highestReadyBlockId + 1 ==
     * messageId, with the ready id starting at -1). The skip rule must not
     * fire on the first step - skipping 0 there shifts every block by one and
     * strands all stream data as out-of-order.
     */
    @Test
    public void testFirstBlockIsZero() {
        assertEquals(0, Connection.nextSeqNumAfter(-1));
        assertEquals(1, Connection.nextSeqNumAfter(0));
        assertTrue(Connection.isUsableSeqNum(0));
    }

    /**
     * A wrapped raw counter value congruent to 0 mod 2^32 must never be
     * handed out: wire sequence 0 means "ack-only" and the payload would be
     * stripped. getNextOutboundPacketNum() skips such values atomically; the
     * pure predicate and the pure step pin the rule.
     */
    @Test
    public void testWireZeroRejected() {
        assertFalse(Connection.isUsableSeqNum(Connection.SEQ_MOD));
        assertFalse(Connection.isUsableSeqNum(2 * Connection.SEQ_MOD));
        assertTrue(Connection.isUsableSeqNum(Connection.SEQ_MOD - 1));
        assertTrue(Connection.isUsableSeqNum(Connection.SEQ_MOD + 1));
        // the wrap step-over: the value just below 2^32 is usable and its
        // wire-zero successor is skipped in one atomic step
        assertEquals(Connection.SEQ_MOD + 1, Connection.nextSeqNumAfter(Connection.SEQ_MOD - 1));
        assertEquals(2 * Connection.SEQ_MOD + 1, Connection.nextSeqNumAfter(2 * Connection.SEQ_MOD));
    }
}
