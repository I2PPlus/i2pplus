package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.data.ByteArray;

import org.junit.Test;

/**
 * Tests the payload-release contract that the retransmit timer relies on to
 * avoid re-enqueueing a packet whose buffer was already returned to the shared
 * outbound cache.
 *
 * <p>Background: {@code PacketLocal.releasePayload()} (called from both
 * {@code ackReceived()} and {@code cancelled()}) returns a full-size packet's
 * buffer to the shared payload cache and clears its {@code _payload}. A
 * retransmit task that built its snapshot before that release but enqueues
 * after it can therefore re-send a packet with a {@code null} payload, crashing
 * the I2CP write with an NPE at {@code Packet.writePacket}. The fix has two
 * parts that this class pins:
 *
 * <ul>
 *   <li><b>{@link PacketLocal#writeReleased()}</b> — the discriminator the
 *       retransmit loop uses to skip ACKed/cancelled packets (mirroring the
 *       paced-path guard); {@code isCancelled()} alone misses ACKed packets.</li>
 *   <li><b>{@link Packet#writePacket} local-capture defense-in-depth</b> — a
 *       concurrent release may null {@code _payload} mid-write, so the method
 *       snapshots the payload into a local and tolerates a {@code null}.</li>
 * </ul>
 *
 * @since 0.9.71+
 */
public class PacketPayloadReleaseTest {

    /**
     * Build a connection-unbound PacketLocal. The trailing cast disambiguates
     * between the {@code (Destination, I2PSession)} and
     * {@code (Destination, Connection)} constructors.
     */
    private static PacketLocal newPacket() {
        return new PacketLocal(I2PAppContext.getGlobalContext(), null, (I2PSession) null);
    }

    /**
     * A freshly created, transmitted-but-unacked packet may still be resent:
     * {@code writeReleased()} must be false until an ack or cancel lands.
     */
    @Test
    public void testNotReleasedBeforeAckOrCancel() {
        PacketLocal p = newPacket();
        p.acquirePayload();
        assertFalse("unacked/uncancelled packet is still resendable",
                    p.writeReleased());
    }

    /**
     * Acking a full-size packet releases its buffer back to the pool and nulls
     * its payload; the packet must then report {@code writeReleased()} and must
     * no longer be writable (would have been an NPE before the fix).
     */
    @Test
    public void testAckedPacketReleasesPayloadAndIsReleased() {
        PacketLocal p = newPacket();
        ByteArray buf = p.acquirePayload();
        assertNotNull(buf);
        assertEquals(Packet.MAX_PAYLOAD_SIZE, buf.getData().length);

        p.ackReceived();

        assertTrue("acked packet must be writeReleased()", p.writeReleased());
        assertNull("payload must be returned to the pool and nulled", p.getPayload());
        // The retransmit guard skips it, so nothing is written; but even if a
        // stale reference reached writePacket, it must not NPE (defense-in-depth).
        byte[] out = new byte[4096];
        int n = p.writePacket(out, 0);
        assertTrue("released packet must still serialize to its header, not throw", n >= 22);
    }

    /**
     * Cancelling a full-size packet likewise releases its payload; the packet
     * must report {@code writeReleased()}.
     */
    @Test
    public void testCancelledPacketIsReleased() {
        PacketLocal p = newPacket();
        p.acquirePayload();

        p.cancelled();

        assertTrue("cancelled packet must be writeReleased()", p.writeReleased());
        assertNull(p.getPayload());
    }

    /**
     * ACKed-but-not-cancelled packets must be caught by {@code writeReleased()},
     * because {@code isCancelled()} (i.e. {@code _cancelledOn > 0}) is false for
     * them — the exact gap that left the retransmit path with a stale reference.
     */
    @Test
    public void testAckReleasedEvenWhenNotCancelled() {
        PacketLocal p = newPacket();
        p.acquirePayload();

        p.ackReceived();

        assertTrue(p.writeReleased());
        // _cancelledOn stays at its initial -1, so the old isCancelled() check
        // alone could NOT have detected this release.
    }

    /**
     * A non-full-size packet does not go through the buffer pool, so its payload
     * is not nulled on release; {@code writeReleased()} still reports true
     * because {@code _ackOn} is set (the retransmit guard must skip it whether
     * or not the pool was involved).
     */
    @Test
    public void testAckSmallPayloadStillReleased() {
        PacketLocal p = newPacket();
        p.setPayload(new ByteArray(new byte[512]));

        p.ackReceived();

        assertTrue(p.writeReleased());
        // small payload is not pooled, so it is retained — but the packet is
        // still logically released and must not be resent.
        assertNotNull(p.getPayload());
    }

    /**
     * Defense-in-depth directly at the NPE site: even when {@code _payload} is
     * cleared between scheduling and writing, {@code writePacket} must serialize
     * the header instead of throwing.
     */
    @Test
    public void testWritePacketNullPayloadIsSafe() {
        Packet p = new Packet(null);
        p.setSendStreamId(1);
        p.setReceiveStreamId(2);
        p.setSequenceNum(1);
        p.setPayload(new ByteArray(new byte[512]));
        p.setPayload(null); // simulate a concurrent releasePayload() landing mid-write

        byte[] buf = new byte[4096];
        int written = p.writePacket(buf, 0);
        assertTrue("null-payload packet must still write its header", written >= 22);
    }
}