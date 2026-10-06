package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

/**
 * Tests the ACK-block to bitfield conversion, in particular that the span derived
 * from unvalidated range bytes is bounded before any bitfield is allocated.
 *
 * The span is attacker-controlled: a ~1.5KB ACK block with 767 ranges of 0xff
 * implies a span of ~391,000 bits, which is a ~48KB long[] allocation per
 * ~1.5KB datagram. {@link SSU2Bitfield#MAX_ACK_SPAN} caps it.
 *
 * @since 0.9.71+
 */
public class SSU2BitfieldTest {

    /** Largest range array a 1.5KB datagram can carry: (1500 - 5) / 2. */
    private static final int MAX_RANGES_IN_MTU = 767;

    /**
     * A hostile ACK block: every range byte is 0xff, so the implied span is
     * ~391,000 bits. Must be rejected, not turned into a 48KB bitfield.
     */
    @Test
    public void testHostileMaxRangesRejected() {
        byte[] ranges = new byte[MAX_RANGES_IN_MTU * 2];
        Arrays.fill(ranges, (byte) 0xff);
        int rangeCount = ranges.length / 2;
        int thru = 100000;
        int acnt = 255;
        long span = SSU2Bitfield.calculateAckSpan(thru, acnt, ranges, rangeCount);
        // the whole point: the unchecked span is enormous
        assertTrue("hostile span should exceed the cap, was " + span, span > SSU2Bitfield.MAX_ACK_SPAN);
        try {
            SSU2Bitfield.fromACKBlock(thru, acnt, ranges, rangeCount);
            fail("should have rejected an ACK block spanning " + span + " bits");
        } catch (IllegalArgumentException iae) {
            // expected
        }
    }

    /**
     * Same hostile block, but with an ACK ID that reads back as a negative int.
     * The span math must not wrap, or the size check would be bypassed.
     */
    @Test
    public void testHostileNegativeAckIDRejected() {
        byte[] ranges = new byte[MAX_RANGES_IN_MTU * 2];
        Arrays.fill(ranges, (byte) 0xff);
        int rangeCount = ranges.length / 2;
        int thru = Integer.MIN_VALUE;
        int acnt = 255;
        long span = SSU2Bitfield.calculateAckSpan(thru, acnt, ranges, rangeCount);
        assertTrue("span should exceed the cap, was " + span, span > SSU2Bitfield.MAX_ACK_SPAN);
        try {
            SSU2Bitfield.fromACKBlock(thru, acnt, ranges, rangeCount);
            fail("should have rejected an ACK block spanning " + span + " bits");
        } catch (IllegalArgumentException iae) {
            // expected
        }
    }

    /**
     * A block whose span is exactly the cap is still accepted; the next range up
     * (spans are multiples of 256) is rejected. Guards the comparison itself.
     */
    @Test
    public void testSpanBoundary() {
        int thru = 100000;
        int acnt = 255;
        // span = acnt + 1 + 256 per (255 nack, 1 ack) range
        int rangesAtCap = (SSU2Bitfield.MAX_ACK_SPAN / 256) - 1;
        assertEquals(SSU2Bitfield.MAX_ACK_SPAN, spanOf(thru, acnt, rangesAtCap));
        SSU2Bitfield rv = SSU2Bitfield.fromACKBlock(thru, acnt, ranges(thru, acnt, rangesAtCap), rangesAtCap);
        assertEquals(SSU2Bitfield.MAX_ACK_SPAN, rv.size());
        assertEquals(thru, rv.getHighestSet());
        assertEquals((long) thru + 1 - SSU2Bitfield.MAX_ACK_SPAN, rv.getOffset());

        byte[] overCap = ranges(thru, acnt, rangesAtCap + 1);
        int rcOverCap = rangesAtCap + 1;
        assertTrue(spanOf(thru, acnt, rcOverCap) > SSU2Bitfield.MAX_ACK_SPAN);
        try {
            SSU2Bitfield.fromACKBlock(thru, acnt, overCap, rcOverCap);
            fail("should have rejected a span one step over the cap");
        } catch (IllegalArgumentException iae) {
            // expected
        }
    }

    /** A range array of rangeCount pairs of (255 nacked, 1 acked). */
    private static byte[] ranges(int thru, int acnt, int rangeCount) {
        byte[] rv = new byte[rangeCount * 2];
        for (int i = 0; i < rv.length; i += 2) {
            rv[i] = (byte) 0xff;
            rv[i + 1] = 1;
        }
        return rv;
    }

    private static long spanOf(int thru, int acnt, int rangeCount) {
        return SSU2Bitfield.calculateAckSpan(thru, acnt, ranges(thru, acnt, rangeCount), rangeCount);
    }

    /**
     * A legitimate multi-range ACK still decodes to exactly the acked bits.
     * Ranges are built the way toAckBlock() builds them: from the highest
     * message number down, alternating gaps (nack count) and runs (ack count).
     */
    @Test
    public void testMultiRangeDecode() {
        int thru = 1000;
        int acnt = 3;      // 1000, 999, 998, 997 acked
        // below that: 2 nacked (996, 995), then 2 acked (994, 993),
        // then 1 nacked (992), then 5 acked (991..987)
        byte[] ranges = { 2, 2, 1, 5 };
        SSU2Bitfield rv = SSU2Bitfield.fromACKBlock(thru, acnt, ranges, ranges.length / 2);
        long[] expected = { 1000, 999, 998, 997, 994, 993, 991, 990, 989, 988, 987 };
        for (long bit : expected) {
            assertTrue("bit " + bit + " should be acked", rv.get(bit));
        }
        long[] notExpected = { 996, 995, 992, 986, 985 };
        for (long bit : notExpected) {
            assertFalse("bit " + bit + " should not be acked", rv.get(bit));
        }
        assertEquals(thru, rv.getHighestSet());
        assertEquals(987, rv.getOffset());
    }

    /**
     * The no-ranges case (contiguous acks only) must be unaffected.
     */
    @Test
    public void testNoRanges() {
        int thru = 500;
        int acnt = 255;
        SSU2Bitfield rv = SSU2Bitfield.fromACKBlock(thru, acnt, null, 0);
        assertEquals(thru, rv.getHighestSet());
        assertEquals(thru - acnt, rv.getOffset());
        assertTrue(rv.get(thru - acnt));
        assertFalse(rv.get(thru - acnt - 1));
    }

    /**
     * A range claiming more acked bits than the span covers must not write
     * below the bitfield offset (the ACK block is internally inconsistent).
     * The span covers thru-205 through thru, so the ack run stops at the offset
     * instead of running away to j--.
     */
    @Test
    public void testAckRunCannotWriteBelowOffset() {
        int thru = 1000;
        int acnt = 0;
        byte[] ranges = { 5, (byte) 200 };
        SSU2Bitfield rv = SSU2Bitfield.fromACKBlock(thru, acnt, ranges, 1);
        // span = 1 + 5 + 200, so the offset is the lowest message number covered
        assertEquals(thru - 205, rv.getOffset());
        assertEquals(thru, rv.getHighestSet());
        assertTrue(rv.get(thru));
        assertTrue(rv.get(thru - 205));
    }
}
