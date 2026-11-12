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
        long thru = 100000;
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
     * Same hostile block, but with a negative thru - what a signed-int misread
     * of the wire produces. The span math must not wrap, or the size check would
     * be bypassed.
     */
    @Test
    public void testHostileNegativeAckIDRejected() {
        byte[] ranges = new byte[MAX_RANGES_IN_MTU * 2];
        Arrays.fill(ranges, (byte) 0xff);
        int rangeCount = ranges.length / 2;
        long thru = Integer.MIN_VALUE;
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
        long thru = 100000;
        int acnt = 255;
        // span = acnt + 1 + 256 per (255 nack, 1 ack) range
        int rangesAtCap = (SSU2Bitfield.MAX_ACK_SPAN / 256) - 1;
        assertEquals(SSU2Bitfield.MAX_ACK_SPAN, spanOf(thru, acnt, rangesAtCap));
        SSU2Bitfield rv = SSU2Bitfield.fromACKBlock(thru, acnt, ranges(thru, acnt, rangesAtCap), rangesAtCap);
        assertEquals(SSU2Bitfield.MAX_ACK_SPAN, rv.size());
        assertEquals(thru, rv.getHighestSet());
        assertEquals(thru + 1 - SSU2Bitfield.MAX_ACK_SPAN, rv.getOffset());

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

    /**
     * A range array of rangeCount pairs of (255 nacked, 1 acked).
     *
     * @param thru the highest acked message number, unused - the array does not
     *             depend on it, the parameter only mirrors the call sites
     * @param acnt the acked message count, likewise unused here
     * @param rangeCount the number of nack/ack pairs to emit, 1 or more
     * @return a two-byte-per-pair ACK block body, each pair being 255 nacked
     *         followed by 1 acked
     */
    private static byte[] ranges(long thru, int acnt, int rangeCount) {
        byte[] rv = new byte[rangeCount * 2];
        for (int i = 0; i < rv.length; i += 2) {
            rv[i] = (byte) 0xff;
            rv[i + 1] = 1;
        }
        return rv;
    }

    private static long spanOf(long thru, int acnt, int rangeCount) {
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

    /**
     * toAckBlock() must render exactly the runs the bitfield holds, and a
     * decode of the block must restore them — including across word
     * boundaries and the 255-bit caps. This pins the word-walking rewrite
     * against the per-bit walk it replaced.
     */
    @Test
    public void testAckBlockRoundTripsAcrossWords() {
        SSU2Bitfield bf = new SSU2Bitfield(1024, 1000);
        // contiguous top run 2000..2024 (crosses word and 255-bit edges below)
        for (long b = 1900; b <= 2024; b++) {
            if (b % 3 != 0) {bf.set(b);}
        }
        for (long b = 2000; b <= 2024; b++) {bf.set(b);}
        SSU2Payload.AckBlock block = bf.toAckBlock(512);
        byte[] serialized = new byte[block.getDataLength()];
        assertEquals(serialized.length, block.writeData(serialized, 0));
        long thru = net.i2p.data.DataHelper.fromLong(serialized, 0, 4);
        assertEquals(2024, thru);
        int acnt = serialized[4] & 0xff;
        int rangeCount = (serialized.length - 5) / 2;
        byte[] ranges = Arrays.copyOfRange(serialized, 5, serialized.length);
        SSU2Bitfield back = SSU2Bitfield.fromACKBlock(thru, acnt, ranges, rangeCount);
        for (long b = 1000; b <= 2024; b++) {
            assertEquals("bit " + b, bf.get(b), back.get(b));
        }
    }

    /**
     * A single top bit with a zero top run: acnt must be 0 and the ranges must
     * start with the nack gap below thru.
     */
    @Test
    public void testSingleBitRenders() {
        SSU2Bitfield bf = new SSU2Bitfield(256, 50);
        bf.set(100);
        SSU2Payload.AckBlock block = bf.toAckBlock(4);
        byte[] serialized = new byte[block.getDataLength()];
        block.writeData(serialized, 0);
        long thru = net.i2p.data.DataHelper.fromLong(serialized, 0, 4);
        assertEquals(100, thru);
        assertEquals(0, serialized[4] & 0xff);
        assertEquals(1, (serialized.length - 5) / 2);
    }

    /**
     * toAckBlock() hands AckBlock an array of exactly two bytes per range it
     * produced, not the maxRanges-sized worst case. Only rangeCount * 2 bytes
     * reach the wire, so the slack was invisible in the packet and visible
     * only as a retained allocation - up to 1024 bytes per ACK block.
     *
     * @throws Exception if the ranges field is not where {@link SSU2Payload.AckBlock}
     *                   keeps it
     */
    @Test
    public void testAckBlockRangeArrayIsExactlySized() throws Exception {
        SSU2Bitfield bf = new SSU2Bitfield(1024, 1000);
        for (long b = 1500; b <= 1600; b += 5) {bf.set(b);}
        // maxRanges is the absolute cap of 512 pairs, the case that used to
        // allocate 1024 bytes regardless of how few ranges exist
        SSU2Payload.AckBlock block = bf.toAckBlock(512);
        java.lang.reflect.Field rangesField = SSU2Payload.AckBlock.class.getDeclaredField("r");
        rangesField.setAccessible(true);
        byte[] ranges = (byte[]) rangesField.get(block);
        assertEquals(block.getDataLength() - 5, ranges.length);
        assertTrue("range array is " + ranges.length + " bytes, too big to be exact",
                   ranges.length < 64);
    }

    /**
     * Seeded random patterns - long runs past the 255-bit caps, gaps past
     * word boundaries, alternation - must round-trip through the ACK block.
     * This is the property the per-bit walk had, and the word walk must keep.
     */
    @Test
    public void testAckBlockRoundTripsRandomPatterns() {
        java.util.Random rnd = new java.util.Random(42);
        for (int trial = 0; trial < 20; trial++) {
            long base = 5000 + trial * 4000L;
            SSU2Bitfield bf = new SSU2Bitfield(4096, base);
            long bit = base;
            while (bit < base + 3000) {
                // a run of set bits, sometimes longer than the 255 cap
                int run = 1 + rnd.nextInt(300);
                for (int k = 0; k < run && bit < base + 3000; k++) {
                    bf.set(bit++);
                }
                // then a gap, sometimes longer than a word
                bit += 1 + rnd.nextInt(200);
            }
            SSU2Payload.AckBlock block = bf.toAckBlock(512);
            if (block == null) {continue;}
            byte[] serialized = new byte[block.getDataLength()];
            block.writeData(serialized, 0);
            long thru = net.i2p.data.DataHelper.fromLong(serialized, 0, 4);
            int acnt = serialized[4] & 0xff;
            int rangeCount = (serialized.length - 5) / 2;
            byte[] ranges = Arrays.copyOfRange(serialized, 5, serialized.length);
            SSU2Bitfield back = SSU2Bitfield.fromACKBlock(thru, acnt, ranges, rangeCount);
            for (long b = base; b < base + 3000; b++) {
                if (bf.get(b) != back.get(b)) {
                    fail("trial " + trial + " bit " + b + " differs: " + bf.get(b) + " vs " + back.get(b));
                }
            }
        }
    }

    /**
     * forEachAndNot must call back exactly the newly acked bits, in ascending
     * order, which value-to-callback the word-walking diff preserves.
     */
    @Test
    public void testForEachAndNotCallsBackOnlyNewlySet() {
        SSU2Bitfield bf = new SSU2Bitfield(256, 0);
        bf.set(1);
        bf.set(3);
        bf.set(5);
        SSU2Bitfield prev = new SSU2Bitfield(256, 0);
        prev.set(1);
        prev.set(2);
        java.util.List<Long> called = new java.util.ArrayList<>();
        bf.forEachAndNot(prev, called::add);
        assertEquals(java.util.Arrays.asList(3L, 5L), called);
        assertTrue(prev.get(3));
        assertTrue(prev.get(5));
    }

    /**
     * forEachAndNot over seeded, word-crossing patterns: the callback list is
     * exactly the bits this bitfield holds and bf2 lacked, in ascending order,
     * and bf2 ends up holding the union. The expected set is computed bit by
     * bit, so it pins the word-walking diff to the per-bit version it
     * replaced.
     */
    @Test
    public void testForEachAndNotDiffsRandomPatterns() {
        java.util.Random rnd = new java.util.Random(7);
        int span = 900;
        for (int trial = 0; trial < 20; trial++) {
            long base = 10_000L + trial * 5000L;
            SSU2Bitfield bf = new SSU2Bitfield(1024, base);
            SSU2Bitfield prev = new SSU2Bitfield(1024, base);
            fillRuns(rnd, bf, base, span);
            fillRuns(rnd, prev, base, span);
            boolean[] had = new boolean[span];
            java.util.List<Long> expected = new java.util.ArrayList<>();
            for (int i = 0; i < span; i++) {
                had[i] = prev.get(base + i);
                if (bf.get(base + i) && !had[i]) {expected.add(base + i);}
            }
            java.util.List<Long> called = new java.util.ArrayList<>();
            bf.forEachAndNot(prev, called::add);
            assertEquals("trial " + trial, expected, called);
            for (int i = 0; i < span; i++) {
                assertEquals("trial " + trial + " bit " + (base + i),
                             had[i] || bf.get(base + i), prev.get(base + i));
            }
        }
    }

    /**
     * Fill a bitfield with random runs and gaps inside [base, base + span),
     * so the patterns cross 64-bit word edges and the 255-bit range caps.
     *
     * @param rnd the source of run and gap lengths
     * @param bf the bitfield to fill
     * @param base the lowest message number of the window
     * @param span the number of message numbers to fill
     */
    private static void fillRuns(java.util.Random rnd, SSU2Bitfield bf, long base, int span) {
        long bit = base + rnd.nextInt(8);
        while (bit < base + span) {
            int run = 1 + rnd.nextInt(300);
            for (int k = 0; k < run && bit < base + span; k++) {bf.set(bit++);}
            bit += 1 + rnd.nextInt(200);
        }
    }
}
