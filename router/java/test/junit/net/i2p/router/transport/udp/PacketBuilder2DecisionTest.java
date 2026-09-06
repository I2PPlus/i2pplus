package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.i2p.router.transport.udp.SSU2Payload.Block;
import org.junit.Test;

/**
 * Pins packet-building decision logic extracted from PacketBuilder2.buildPacket().
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
}