package net.i2p.router.transport.udp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.i2p.data.DataHelper;
import net.i2p.router.transport.udp.SSU2Payload.Block;
import org.junit.Test;

/**
 * Pins packet-building decision logic extracted from {@link net.i2p.router.transport.udp.PacketBuilder2#buildPacket}.
 * All helpers are pure static functions; only the non-empty otherBlocks-size sum
 * needs mocked Block lengths. No router context is required.
 *
 * @since 0.9.71+
 */
public class PacketBuilder2DecisionTest {

    private static final int ACK_BLOCK_MIN_ROOM = SSU2Payload.BLOCK_HEADER_SIZE + 5;

    @Test
    public void testFragmentDataSizeFirstFragment() {
        assertEquals(100 + SSU2Payload.BLOCK_HEADER_SIZE, PacketBuilder2.fragmentDataSize(100, 0));
        assertEquals(0 + SSU2Payload.BLOCK_HEADER_SIZE, PacketBuilder2.fragmentDataSize(0, 0));
    }

    @Test
    public void testFragmentDataSizeFollowonAddsOverhead() {
        assertEquals(100 + SSU2Payload.BLOCK_HEADER_SIZE + 5, PacketBuilder2.fragmentDataSize(100, 1));
        assertEquals(100 + SSU2Payload.BLOCK_HEADER_SIZE + 5, PacketBuilder2.fragmentDataSize(100, 2));
    }

    @Test
    public void testDataPacketHeaderSize() {
        assertEquals(PacketBuilder2.IP_HEADER_SIZE + PacketBuilder2.UDP_HEADER_SIZE, PacketBuilder2.dataPacketHeaderSize(false));
        assertEquals(PacketBuilder2.IPV6_HEADER_SIZE + PacketBuilder2.UDP_HEADER_SIZE, PacketBuilder2.dataPacketHeaderSize(true));
    }

    @Test
    public void testDataPacketOverhead() {
        assertEquals(PacketBuilder2.MIN_DATA_PACKET_OVERHEAD, PacketBuilder2.dataPacketOverhead(false));
        assertEquals(PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD, PacketBuilder2.dataPacketOverhead(true));
    }

    @Test
    public void testOtherBlocksSizeNullAndEmpty() {
        assertEquals(0, PacketBuilder2.otherBlocksSize(null));
        assertEquals(0, PacketBuilder2.otherBlocksSize(Collections.<Block>emptyList()));
    }

    @Test
    public void testOtherBlocksSizeSumsLengths() {
        Block a = mock(Block.class);
        when(a.getTotalLength()).thenReturn(10);
        Block b = mock(Block.class);
        when(b.getTotalLength()).thenReturn(22);
        List<Block> blocks = new ArrayList<Block>(Arrays.asList(a, b));
        assertEquals(32, PacketBuilder2.otherBlocksSize(blocks));
    }

    @Test
    public void testHasRoomForAckBlockBoundary() {
        assertFalse(PacketBuilder2.hasRoomForAckBlock(ACK_BLOCK_MIN_ROOM - 1));
        assertTrue(PacketBuilder2.hasRoomForAckBlock(ACK_BLOCK_MIN_ROOM));
        assertTrue(PacketBuilder2.hasRoomForAckBlock(ACK_BLOCK_MIN_ROOM + 100));
        assertFalse(PacketBuilder2.hasRoomForAckBlock(0));
    }

    @Test
    public void testMaxAckRanges() {
        assertEquals(0, PacketBuilder2.maxAckRanges(ACK_BLOCK_MIN_ROOM));
        assertEquals(1, PacketBuilder2.maxAckRanges(ACK_BLOCK_MIN_ROOM + 2));
        assertEquals(2, PacketBuilder2.maxAckRanges(ACK_BLOCK_MIN_ROOM + 4));
        assertEquals((1000 - ACK_BLOCK_MIN_ROOM) / 2, PacketBuilder2.maxAckRanges(1000));
    }

    @Test
    public void testMaxAckRangesCap() {
        // (huge - 13) / 2 far exceeds ABSOLUTE_MAX_ACK_RANGES (512)
        assertEquals(512, PacketBuilder2.maxAckRanges(1000000));
    }

    @Test
    public void testIsSingleFragment() {
        assertTrue(PacketBuilder2.isSingleFragment(0, 1));
        assertFalse(PacketBuilder2.isSingleFragment(0, 2));
        assertFalse(PacketBuilder2.isSingleFragment(1, 1));
        assertFalse(PacketBuilder2.isSingleFragment(1, 2));
    }

    @Test
    public void testIsDateTimeSendPeriodBoundary() {
        // DATETIME_SEND_FREQUENCY = 256; boundary when pktNum % 256 == 255
        assertTrue(PacketBuilder2.isDateTimeSendPeriod(255));
        assertFalse(PacketBuilder2.isDateTimeSendPeriod(256));
        assertFalse(PacketBuilder2.isDateTimeSendPeriod(0));
        assertTrue(PacketBuilder2.isDateTimeSendPeriod(511));
        assertFalse(PacketBuilder2.isDateTimeSendPeriod(254));
    }

    @Test
    public void testFitsDateTimeBlockBoundary() {
        int ip = PacketBuilder2.dataPacketHeaderSize(false); // 28
        int sizeWritten = 200;
        int mtu = ip + SSU2Util.SHORT_HEADER_SIZE + sizeWritten + 7 + SSU2Util.MAC_LEN;
        assertTrue(PacketBuilder2.fitsDateTimeBlock(ip, sizeWritten, mtu));
        assertFalse(PacketBuilder2.fitsDateTimeBlock(ip, sizeWritten, mtu - 1));
        assertTrue(PacketBuilder2.fitsDateTimeBlock(ip, sizeWritten, mtu + 1));
    }

    /**
     * The short header's 3-byte flags field (offsets 13-15) must be zero when
     * the packet carries no flags byte, even in a recycled buffer. An
     * ack-only data packet writes no flags byte at all, so stale bytes there
     * would be transmitted as flags.
     */
    @Test
    public void testWriteShortHeaderZeroesFlagsField() {
        byte[] data = new byte[SSU2Util.SHORT_HEADER_SIZE + 64];
        Arrays.fill(data, (byte) 0xFF);
        PacketBuilder2.writeShortHeader(data, 0x1122334455667788L, 0xAB, (byte) 0x03);
        assertEquals(0x1122334455667788L, DataHelper.fromLong8(data, SSU2Util.DEST_CONN_ID_OFFSET));
        assertEquals(0xAB, DataHelper.fromLong(data, SSU2Util.PKT_NUM_OFFSET, SSU2Util.PKT_NUM_LEN));
        assertEquals(0x03, data[SSU2Util.TYPE_OFFSET] & 0xff);
        assertEquals(0, data[SSU2Util.SHORT_HEADER_FLAGS_OFFSET] & 0xff);
        assertEquals(0, data[SSU2Util.NETID_OFFSET] & 0xff);
        assertEquals(0, data[SSU2Util.LONG_HEADER_FLAGS_OFFSET] & 0xff);
    }

    /**
     * Bytes past the short header are left untouched: every payload producer
     * writes its own range and the {@code DatagramPacket} length limits its
     * transmission, so clearing the range is pure cost. This pins that the
     * helper does not spend a buffer-wide fill in the packet-builder hot
     * path.
     */
    @Test
    public void testWriteShortHeaderLeavesPayloadAreaUntouched() {
        byte[] data = new byte[SSU2Util.SHORT_HEADER_SIZE + 64];
        Arrays.fill(data, (byte) 0xFF);
        PacketBuilder2.writeShortHeader(data, 1L, 2L, (byte) 0x01);
        for (int i = SSU2Util.SHORT_HEADER_SIZE; i < data.length; i++) {
            assertEquals("byte " + i + " is past the header and must keep the recycled contents",
                         0xFF, data[i] & 0xff);
        }
    }

    /**
     * A long header built on a short header must be byte-identical whether the
     * buffer was zeroed first or recycled with stale bytes. The four
     * {@code writeLongHeader} writes cover offsets 13-14 and 16-31 exactly,
     * and offset 15 is the zeroed flags byte, which the handshake mixes into
     * the Noise hash and payload MAC.
     */
    @Test
    public void testShortThenLongHeaderMatchesZeroedBuild() {
        byte[] fresh = new byte[SSU2Util.LONG_HEADER_SIZE];
        byte[] recycled = new byte[SSU2Util.LONG_HEADER_SIZE];
        Arrays.fill(recycled, (byte) 0xFF);
        long dest = 0x1122334455667788L;
        long pktNum = 0x12345678L;
        byte type = (byte) 0x21;
        long src = 0x99AABBCCDDEEFF00L;
        long token = 0x0102030405060708L;

        PacketBuilder2.writeShortHeader(fresh, dest, pktNum, type);
        PacketBuilder2.writeLongHeader(fresh, src, token, 2, 11);
        PacketBuilder2.writeShortHeader(recycled, dest, pktNum, type);
        PacketBuilder2.writeLongHeader(recycled, src, token, 2, 11);

        assertArrayEquals("the long header must not depend on stale buffer bytes", fresh, recycled);
        assertEquals(2, recycled[SSU2Util.VERSION_OFFSET] & 0xff);
        assertEquals(11, recycled[SSU2Util.NETID_OFFSET] & 0xff);
        assertEquals(0, recycled[SSU2Util.LONG_HEADER_FLAGS_OFFSET] & 0xff);
        assertEquals(src, DataHelper.fromLong8(recycled, SSU2Util.SRC_CONN_ID_OFFSET));
        assertEquals(token, DataHelper.fromLong8(recycled, SSU2Util.TOKEN_OFFSET));
    }
}
