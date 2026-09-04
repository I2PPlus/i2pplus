package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the SYN accept-queue expiry log formatting in ConnectionHandler.
 *
 * <p>The historical WARN ("Expired on the SYN queue: " + packet) rendered as a
 * bare trailing-colon line with no peer, no queue pressure, and no timeout,
 * so a burst of expiries was impossible to attribute or rate-assess. These
 * tests pin the richer summary so the flood path can be diagnosed from the
 * log alone: source stream IDs, queue depth, timeout in play, and (when the
 * FROM option is present) the short peer hash the per-dest flood gate keys on.
 *
 * @since 0.9.71+
 */
public class ConnectionHandlerSynExpiryTest {

    @Test
    public void testSummaryNoTrailingEmptyColon() {
        Packet syn = synPacket();
        String msg = ConnectionHandler.synExpiryMessage(syn, 5, 256, 60000);
        assertFalse("must not end in an empty trailing colon",
                    msg.endsWith(":"));
        assertFalse("must not end in a colon then whitespace",
                    msg.matches(".*:\\s+$"));
    }

    @Test
    public void testMessageCarriesTimeoutAndQueuePressure() {
        Packet syn = synPacket();
        String msg = ConnectionHandler.synExpiryMessage(syn, 5, 256, 60000);
        assertTrue("should mention the accept timeout: " + msg,
                   msg.contains("60000ms"));
        assertTrue("should show queue depth / max: " + msg,
                   msg.contains("5/256"));
    }

    @Test
    public void testMessageCarriesStreamIdAndSequence() {
        Packet syn = synPacket();
        String msg = ConnectionHandler.synExpiryMessage(syn, 0, 0, 0);
        // streamIDs pair rendered via Packet.toId()
        assertTrue("should contain the StreamID pair: " + msg,
                   msg.contains("streamIDs ["));
        assertTrue("should flag the SYN: " + msg,
                   msg.startsWith("Expired on SYN queue"));
    }

    @Test
    public void testSynFlagLabeled() {
        Packet syn = synPacket();
        StringBuilder out = new StringBuilder();
        ConnectionHandler.synExpirySummary(syn, out);
        assertTrue("SYN packets should be labeled SYN: " + out,
                   out.toString().startsWith("SYN "));
    }

    @Test
    public void testNonSynFlagLabeledAsPacket() {
        Packet p = new Packet(null);
        p.setSequenceNum(7);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        StringBuilder out = new StringBuilder();
        ConnectionHandler.synExpirySummary(p, out);
        assertTrue("non-SYN packets should be labeled Pkt: " + out,
                   out.toString().startsWith("Pkt "));
    }

    @Test
    public void testNullSynHandled() {
        StringBuilder out = new StringBuilder();
        ConnectionHandler.synExpirySummary(null, out);
        assertEquals("null SYN", out.toString());
    }

    @Test
    public void testZeroMaxQueueSizeStillReadable() {
        Packet syn = synPacket();
        String msg = ConnectionHandler.synExpiryMessage(syn, 7, 0, 60000);
        assertTrue("zero max should still print depth: " + msg,
                   msg.contains("depth 7"));
    }

    private static Packet synPacket() {
        Packet p = new Packet(null);
        p.setFlag(Packet.FLAG_SYNCHRONIZE);
        p.setSequenceNum(3);
        p.setSendStreamId(0x11111111L);
        p.setReceiveStreamId(0x22222222L);
        return p;
    }
}
