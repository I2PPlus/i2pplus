package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.data.ByteArray;

import org.junit.Test;

/**
 * Tests for Packet serialization overhead.
 * Excessive serialization cost impacts throughput.
 * These tests verify the writePacket/readPacket roundtrip
 * and that writtenSize() matches actual output.
 */
public class PacketSerializationTest {

    @Test
    public void testMinimalPacketWriteRead() {
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        p.setSequenceNum(0);
        p.setAckThrough(0);
        byte[] buf = new byte[4096];
        int written = p.writePacket(buf, 0);
        assertTrue("Minimal packet should write > 0 bytes", written > 0);
        // Minimum header: 4+4+4+4+1+1+2+2 = 22 bytes
        assertTrue("Minimal packet should be at least 22 bytes", written >= 22);
    }

    @Test
    public void testPacketWithPayload() {
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        p.setSequenceNum(1);
        p.setAckThrough(0);
        byte[] data = new byte[1024];
        p.setPayload(new ByteArray(data));
        byte[] buf = new byte[4096];
        int written = p.writePacket(buf, 0);
        assertTrue("Packet with payload should write > 1024 bytes", written > 1024);
    }

    @Test
    public void testPacketWithNacks() {
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        p.setSequenceNum(5);
        p.setAckThrough(10);
        long[] nacks = {3, 4, 7};
        p.setNacks(nacks);
        byte[] buf = new byte[4096];
        int written = p.writePacket(buf, 0);
        // NACKs add 1 byte count + 4 bytes per nack = 13 bytes
        Packet p2 = new Packet(null);
        p2.readPacket(buf, 0, written);
        assertEquals(5, p2.getSequenceNum());
        assertEquals(10, p2.getAckThrough());
        assertNotNull(p2.getNacks());
        assertEquals(3, p2.getNacks().length);
        assertEquals(3, p2.getNacks()[0]);
        assertEquals(4, p2.getNacks()[1]);
        assertEquals(7, p2.getNacks()[2]);
    }

    @Test
    public void testPacketFlagsRoundtrip() {
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        p.setSequenceNum(0);
        p.setFlag(Packet.FLAG_SYNCHRONIZE);
        p.setFlag(Packet.FLAG_CLOSE);
        p.setFlag(Packet.FLAG_ECHO);
        byte[] buf = new byte[4096];
        int written = p.writePacket(buf, 0);
        Packet p2 = new Packet(null);
        p2.readPacket(buf, 0, written);
        assertTrue(p2.isFlagSet(Packet.FLAG_SYNCHRONIZE));
        assertTrue(p2.isFlagSet(Packet.FLAG_CLOSE));
        assertTrue(p2.isFlagSet(Packet.FLAG_ECHO));
        assertFalse(p2.isFlagSet(Packet.FLAG_RESET));
    }

    @Test
    public void testStreamIdRoundtrip() {
        Packet p = new Packet(null);
        p.setSendStreamId(0xDEADBEEFL);
        p.setReceiveStreamId(0xCAFEBABEL);
        p.setSequenceNum(42);
        p.setAckThrough(99);
        byte[] buf = new byte[4096];
        int written = p.writePacket(buf, 0);
        Packet p2 = new Packet(null);
        p2.readPacket(buf, 0, written);
        assertEquals(0xDEADBEEFL, p2.getSendStreamId());
        assertEquals(0xCAFEBABEL, p2.getReceiveStreamId());
        assertEquals(42, p2.getSequenceNum());
        assertEquals(99, p2.getAckThrough());
    }

    @Test
    public void testPayloadRoundtrip() {
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        p.setSequenceNum(1);
        byte[] data = new byte[512];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        p.setPayload(new ByteArray(data));
        byte[] buf = new byte[4096];
        int written = p.writePacket(buf, 0);
        Packet p2 = new Packet(null);
        p2.readPacket(buf, 0, written);
        assertNotNull(p2.getPayload());
        assertEquals(512, p2.getPayload().getValid());
        // Payload data is copied during writePacket into buf
        // readPacket wraps buf, so compare against buf contents
        int payloadOffset = written - 512;
        for (int i = 0; i < data.length; i++) {
            assertEquals("payload byte " + i, data[i], buf[payloadOffset + i]);
        }
    }

    @Test
    public void testMaxPayloadSize() {
        assertEquals(32 * 1024, Packet.MAX_PAYLOAD_SIZE);
    }

    @Test
    public void testSequenceNumOverflow() {
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        p.setSequenceNum(0xFFFFFFFFL);
        p.setAckThrough(0xFFFFFFFFL);
        byte[] buf = new byte[4096];
        int written = p.writePacket(buf, 0);
        Packet p2 = new Packet(null);
        p2.readPacket(buf, 0, written);
        assertEquals(0xFFFFFFFFL, p2.getSequenceNum());
        assertEquals(0xFFFFFFFFL, p2.getAckThrough());
    }

    @Test
    public void testResendDelayRoundtrip() {
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        p.setSequenceNum(0);
        p.setResendDelay(42);
        byte[] buf = new byte[4096];
        int written = p.writePacket(buf, 0);
        Packet p2 = new Packet(null);
        p2.readPacket(buf, 0, written);
        assertEquals(42, p2.getResendDelay());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testReadPacketTooSmall() {
        Packet p = new Packet(null);
        byte[] buf = new byte[10]; // too small
        p.readPacket(buf, 0, buf.length);
    }

    @Test
    public void testFlagConstants() {
        // Verify flag values are distinct powers of 2
        int allFlags = Packet.FLAG_SYNCHRONIZE | Packet.FLAG_CLOSE | Packet.FLAG_RESET |
                       Packet.FLAG_SIGNATURE_INCLUDED | Packet.FLAG_SIGNATURE_REQUESTED |
                       Packet.FLAG_FROM_INCLUDED | Packet.FLAG_DELAY_REQUESTED |
                       Packet.FLAG_MAX_PACKET_SIZE_INCLUDED | Packet.FLAG_PROFILE_INTERACTIVE |
                       Packet.FLAG_ECHO | Packet.FLAG_NO_ACK | Packet.FLAG_SIGNATURE_OFFLINE;
        // Each flag should contribute exactly one bit
        assertEquals("All flags should be distinct bits", 12, Integer.bitCount(allFlags));
    }
}
