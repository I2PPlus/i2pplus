package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * What the two SSU2 ACK descriptions say.
 * <p>
 * The static form renders an ACK block as it arrived on the wire, and the
 * instance form renders a bitfield. Both are printed from the per-ACK-block
 * DEBUG sites, so a peer that is fully caught up has to cost a few characters,
 * not one number per bit: a 512-bit window that is entirely acked used to emit
 * 512 numbers, and a block at {@link SSU2Bitfield#MAX_ACK_SPAN} would emit
 * 8192. Upstream collapsed consecutive bits into ranges in b042381240.
 *
 * @since 0.9.72+
 */
public class SSU2BitfieldDescriptionTest {

    /**
     * The description cases. Each one calls a formatter directly, so the
     * instance holds no state and needs no peer or router.
     *
     * @since 0.9.72+
     */
    public SSU2BitfieldDescriptionTest() {}

    /**
     * Consecutive set bits are one range, and a gap starts a new one. The
     * single-bit case stays a single number, with no dangling dash.
     */
    @Test
    public void writesConsecutiveBitsAsOneRange() {
        SSU2Bitfield bf = new SSU2Bitfield(256, 50);
        for (long b = 100; b <= 104; b++) {bf.set(b);}
        bf.set(106);
        for (long b = 108; b <= 109; b++) {bf.set(b);}
        assertEquals("SSU2Bitfield(256) offset: 50 highest set: 109 [100-104 106 108-109]", bf.toString());
    }

    /**
     * A window that is entirely acked - the normal state of a busy peer - costs
     * one range, whatever its size.
     */
    @Test
    public void writesAFullySetWindowAsOneRange() {
        SSU2Bitfield bf = new SSU2Bitfield(512, 1000);
        for (long b = 1000; b < 1512; b++) {bf.set(b);}
        assertEquals("SSU2Bitfield(512) offset: 1000 highest set: 1511 [1000-1511]", bf.toString());
    }

    /**
     * A run that reaches the top of the window is closed off, and a window with
     * nothing set is an empty pair of brackets, not a stray separator.
     */
    @Test
    public void closesTheLastRunAndLeavesAnEmptyFieldEmpty() {
        SSU2Bitfield open = new SSU2Bitfield(256, 0);
        open.set(0);
        for (long b = 2; b <= 3; b++) {open.set(b);}
        assertEquals("SSU2Bitfield(256) offset: 0 highest set: 3 [0 2-3]", open.toString());
        SSU2Bitfield empty = new SSU2Bitfield(256, 7);
        assertEquals("SSU2Bitfield(256) offset: 7 highest set: -1 []", empty.toString());
    }

    /**
     * The description of a bitfield at the ACK-block span cap is a fraction of
     * the field's size, which is the point of the ranges: one number per bit is
     * tens of kilobytes in a DEBUG line.
     */
    @Test
    public void staysShortAtTheSpanCap() {
        int span = SSU2Bitfield.MAX_ACK_SPAN;
        SSU2Bitfield bf = new SSU2Bitfield(span, 10_000L);
        for (long b = 10_000L; b < 10_000L + span; b++) {bf.set(b);}
        String desc = bf.toString();
        assertTrue("a fully set field at the cap described itself as " + desc.length() + " characters",
                   desc.length() < 100);
    }

    /**
     * The wire form of an ACK block names both halves of every range: the acked
     * run and the gap below it, each as a from-to pair.
     */
    @Test
    public void writesTheWireBlockAsRanges() {
        int thru = 1000;
        int acnt = 3;
        byte[] ranges = { 2, 2, 1, 5 };
        String desc = SSU2Bitfield.toString(thru, acnt, ranges, ranges.length / 2);
        assertEquals("ACK 1000-997 NACK 996-995 ACK 994-993 NACK 992 ACK 991-987", desc);
    }

    /**
     * A block with no ranges is just the contiguous run, and a zero ack count is
     * a single number rather than a range that repeats it.
     */
    @Test
    public void writesABlockWithNoRangesAsOneRun() {
        assertEquals("ACK 500-498", SSU2Bitfield.toString(500, 2, null, 0));
        assertEquals("ACK 500", SSU2Bitfield.toString(500, 0, null, 0));
    }
}
