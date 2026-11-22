package net.i2p.client.streaming.impl;

import junit.framework.TestCase;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;

/**
 * Tests how a packet is described in the log: a packet that acknowledges nothing
 * must not print an empty "ACK 0", and a packet with nothing to say must not open
 * a continuation line that renders as an empty bullet.
 */
public class PacketFlagFormatTest extends TestCase {

    /**
     * Declares the no-arg constructor the class would otherwise inherit, so
     * the test class itself is documented.
     */
    public PacketFlagFormatTest() {
    }

    /**
     * Builds a packet carrying one flag and the given ACK-through value.
     *
     * @param flag the flag to set, 0 for none
     * @param ackThrough the ACK-through value
     * @return the packet, non-null
     */
    private static PacketLocal packet(int flag, long ackThrough) {
        PacketLocal pkt = new PacketLocal(I2PAppContext.getGlobalContext(), null, (I2PSession) null);
        if (flag != 0) {pkt.setFlag(flag);}
        pkt.setAckThrough(ackThrough);
        return pkt;
    }

    /**
     * A RESET against a closed session acknowledges nothing, so the description
     * says RESET and leaves the zero out.
     */
    public void testZeroAckIsNotPrinted() {
        String desc = packet(Packet.FLAG_RESET, 0).formatInline().toString();
        assertTrue("[" + desc + "]", desc.startsWith(" * RESET"));
        assertFalse("[" + desc + "]", desc.contains("ACK"));
        assertFalse("[" + desc + "]", desc.startsWith("\n"));
    }

    /**
     * A real acknowledgement is still printed.
     */
    public void testNonZeroAckIsPrinted() {
        String desc = packet(Packet.FLAG_RESET, 5).formatInline().toString();
        assertTrue("[" + desc + "]", desc.contains("ACK 5"));
    }

    /**
     * A packet with no flags and no acknowledgement contributes nothing, rather
     * than the bare continuation marker the old code appended up front.
     */
    public void testEmptyDescriptionOpensNothing() {
        String desc = packet(0, 0).formatAsString(false).toString();
        assertEquals("", desc);
    }

    /**
     * The multi-line form still opens its own continuation line when it has
     * something to put on it.
     */
    public void testMultiLineFormStillOpensALine() {
        String desc = packet(Packet.FLAG_CLOSE, 7).formatAsString(false).toString();
        assertTrue("[" + desc + "]", desc.startsWith("\n*"));
        assertTrue("[" + desc + "]", desc.contains("CLOSE"));
        assertTrue("[" + desc + "]", desc.contains("ACK 7"));
    }

    /**
     * Builds a packet with a stream pair, a sequence number and an ACK.
     *
     * @param flag the flag to set, 0 for none
     * @param ackThrough the ACK-through value
     * @param sequence the sequence number to carry
     * @return the packet, non-null
     */
    private static PacketLocal addressed(int flag, long ackThrough, long sequence) {
        PacketLocal pkt = packet(flag, ackThrough);
        pkt.setReceiveStreamId(0x12345678L);
        pkt.setSendStreamId(0x9ABCDEF0L);
        pkt.setSequenceNum(sequence);
        return pkt;
    }

    /**
     * The one-line identity tag names both stream IDs and labels the sequence
     * number: the multi-line header prints it as a bare <code>[7]</code>, which
     * beside a stream pair reads as a second ID, and a caller that drops the
     * header to stay on one line would otherwise drop the number with it.
     */
    public void testStreamTagLabelsTheSequence() {
        String tag = addressed(0, 0, 7).streamTag();
        assertTrue("[" + tag + "]", tag.startsWith("[Stream "));
        assertTrue("[" + tag + "]", tag.contains(" * Sequence: 7"));
        assertFalse("[" + tag + "]", tag.contains("\n"));
    }

    /**
     * Identity tag plus inline flags stay on one line even for a packet with
     * flags and an acknowledgement - the whole point of the pair, since these
     * lines are emitted per packet and a wrapped copy doubles the log's line
     * count.
     */
    public void testTagAndFlagsStayOnOneLine() {
        PacketLocal pkt = addressed(Packet.FLAG_CLOSE, 2, 5);
        String line = pkt.streamTag() + pkt.formatInline();
        assertFalse("[" + line + "]", line.contains("\n"));
        assertTrue("[" + line + "]", line.contains(" * Sequence: 5"));
        assertTrue("[" + line + "]", line.contains("CLOSE"));
        assertTrue("[" + line + "]", line.contains("ACK 2"));
    }
}
