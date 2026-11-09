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
}
