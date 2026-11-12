package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Random;

import net.i2p.data.DataHelper;

import org.junit.Test;

import net.i2p.router.transport.udp.SSU2Payload.AckBlock;

/**
 * SSU2 packet numbers are unsigned 32-bit values, so an ACK block at an offset
 * above 0x7fffffff has to stay in long arithmetic end to end. Truncating the
 * block's thru value to an int makes it negative, and every bit of a high
 * window then misses.
 *
 * @since 0.9.72+
 */
public class SSU2UnsignedAckTest {

    /** Offsets straddling the sign bit of a 32-bit packet number. */
    private static final long[] OFFSETS = {0, 0x7fffff00L, 0x80000000L, 0xffffff00L};

    /**
     * A block with no ranges, at every offset: the bits it names are set, the
     * one below the run is not.
     */
    @Test
    public void testUnsignedPacketNumbersWithoutRanges() {
        for (long thru : new long[] {0x7fffffffL, 0x80000000L, 0xffffffffL}) {
            SSU2Bitfield bits = SSU2Bitfield.fromACKBlock(thru, 2, null, 0);
            assertEquals(thru, bits.getHighestSet());
            assertTrue(bits.get(thru));
            assertTrue(bits.get(thru - 1));
            assertTrue(bits.get(thru - 2));
            assertFalse(bits.get(thru - 3));
        }
    }

    /**
     * The same across a range pair, where the nack and ack counts are walked
     * down from thru - the loop counter is where the truncation bit first bit us.
     */
    @Test
    public void testUnsignedPacketNumbersWithRanges() {
        for (long thru : new long[] {0x7fffffffL, 0x80000000L, 0xffffffffL}) {
            SSU2Bitfield bits = SSU2Bitfield.fromACKBlock(thru, 1, new byte[] {2, 2}, 1);
            assertEquals(thru, bits.getHighestSet());
            assertTrue(bits.get(thru));
            assertTrue(bits.get(thru - 1));
            assertFalse(bits.get(thru - 2));
            assertFalse(bits.get(thru - 3));
            assertTrue(bits.get(thru - 4));
            assertTrue(bits.get(thru - 5));
        }
    }

    /**
     * The diagnostic text prints the unsigned values, not their signed
     * reinterpretation.
     */
    @Test
    public void testUnsignedPacketNumbersInDiagnostics() {
        assertEquals("ACK 4294967295-4294967294 NACK 4294967293-4294967292 ACK 4294967291-4294967290",
                     SSU2Bitfield.toString(0xffffffffL, 1, new byte[] {2, 2}, 1));
    }

    /**
     * A bitfield at each offset, serialised as an ACK block and rebuilt from
     * those bytes, must read back identically.
     */
    @Test
    public void testRoundTripAckBlocks() {
        Random random = new Random(42);
        for (long offset : OFFSETS) {
            SSU2Bitfield sent = new SSU2Bitfield(256, offset);
            for (int i = 0; i < 256; i++) {
                if (random.nextBoolean())
                    sent.set(offset + i);
            }
            AckBlock ack = sent.toAckBlock(256);
            assertNotNull(ack);
            byte[] data = new byte[ack.getDataLength()];
            ack.writeData(data, 0);
            long thru = DataHelper.fromLong(data, 0, 4);
            int acnt = data[4] & 0xff;
            byte[] ranges = Arrays.copyOfRange(data, 5, data.length);
            SSU2Bitfield received = SSU2Bitfield.fromACKBlock(thru, acnt, ranges, ranges.length / 2);
            for (int i = 0; i < 256; i++)
                assertEquals("Packet " + (offset + i), sent.get(offset + i), received.get(offset + i));
        }
    }

    /**
     * The span guard is arithmetic on the same numbers, so it has to keep
     * working at the top of the range: a block claiming a 64 KiB span near
     * 0xffffffff must be refused, not wrapped into a small size.
     */
    @Test
    public void testOversizedSpanStillRefusedAtTheTopOfTheRange() {
        // 255 nacks in each of 40 ranges is a span of 10200, over MAX_ACK_SPAN
        byte[] ranges = new byte[80];
        Arrays.fill(ranges, (byte) 0xff);
        for (int i = 1; i < ranges.length; i += 2)
            ranges[i] = 0;
        try {
            SSU2Bitfield.fromACKBlock(0xffffff00L, 0, ranges, ranges.length / 2);
            fail("A span over MAX_ACK_SPAN must be refused at any offset");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
}
