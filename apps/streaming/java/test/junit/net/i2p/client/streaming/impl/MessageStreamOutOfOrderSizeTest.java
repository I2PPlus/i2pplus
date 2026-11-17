package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests that the blocks held for a gap are counted against the receive buffer at
 * the size they actually cost.
 * <p>
 * canAccept() priced every block at the connection's negotiated message size,
 * 1,730 bytes by default, while a data packet may carry up to
 * {@link Packet#MAX_PAYLOAD_SIZE} whatever that negotiation said - the wire only
 * caps the payload at 32 KB. Withholding one early block and sending the rest at
 * the wire's maximum therefore filled the count estimate at a nineteenth of the
 * memory it really took: the default buffer admitted 2,048 blocks and pinned
 * 64 MB, and the growth step took it to 151 MB. Upstream has the same estimate
 * with a 224,900-byte buffer, so it pins 4 MB.
 * <p>
 * The other half of the change is that the out-of-order half counts at all: the
 * byte limit used to look only at the readable blocks, so every block waiting on
 * a gap was free.
 *
 * @since 0.9.72+
 */
public class MessageStreamOutOfOrderSizeTest {

    /** The message size the connection negotiated. */
    private static final int MSG = 1024;

    /** The largest payload the wire carries, whatever the negotiation said. */
    private static final int WIRE = Packet.MAX_PAYLOAD_SIZE;

    /** Most messages in the receive window. */
    private static final int WINDOW = 8;

    /**
     * The configured receive buffer: room for exactly four wire-sized blocks, so
     * the fifth is the one that has to be refused.
     */
    private static final int BUFFER = 4 * WIRE;

    /** The context every stream in these cases is built on. */
    private I2PAppContext _context;

    /** Builds the context the streams are created from. */
    @Before
    public void setUp() {
        _context = I2PAppContext.getGlobalContext();
    }

    /** Holds no state of its own beyond the context built for each case. */
    public MessageStreamOutOfOrderSizeTest() {}

    /**
     * A stream with the sizes above.
     *
     * @return a fresh stream
     */
    private MessageInputStream stream() {
        return new MessageInputStream(_context, MSG, WINDOW, BUFFER);
    }

    /**
     * A block of size bytes.
     *
     * @param size bytes of payload
     * @return a block the stream will hold
     */
    private static ByteArray block(int size) {
        return new ByteArray(new byte[size]);
    }

    /**
     * Hold blocks 1 through count out of order, leaving block 0 as the gap.
     *
     * @param in the stream to feed
     * @param count last block id to hand over
     * @param size bytes per block
     */
    private static void feedOutOfOrder(MessageInputStream in, int count, int size) {
        for (int id = 1; id <= count; id++) {
            assertTrue("block " + id + " was rejected", in.messageReceived(id, block(size)));
        }
    }

    /**
     * The regression: four wire-sized blocks fill the buffer, and the fifth is
     * refused. The count estimate priced them at 4 KB of a 128 KB buffer and
     * would have taken the fifth, the sixth, and 124 more.
     */
    @Test
    public void outOfOrderBlocksAreCountedAtTheirRealSize() {
        MessageInputStream in = stream();
        try {
            feedOutOfOrder(in, 4, WIRE);
            assertFalse("a fifth wire-sized block was accepted on the message-size estimate",
                        in.canAccept(5, WIRE));
            // Still refused at an id the estimate had plenty of room for, so what
            // stops it is the bytes, not the sequence distance.
            assertFalse(in.canAccept(120, WIRE));
        } finally {
            in.close();
        }
    }

    /**
     * A retransmit of a block already held is still accepted once the buffer is
     * full - refusing it would make the gap unfillable.
     */
    @Test
    public void retransmitOfAHeldBlockIsAcceptedWhenTheBufferIsFull() {
        MessageInputStream in = stream();
        try {
            feedOutOfOrder(in, 4, WIRE);
            assertFalse(in.canAccept(5, WIRE));
            assertTrue("a retransmit of a held block was refused", in.canAccept(2, WIRE));
        } finally {
            in.close();
        }
    }

    /**
     * Filling the gap moves the held bytes into the readable half, and reading
     * them out gives the room back. If the promotion left the out-of-order total
     * in place, the bytes would be counted twice and the stream would choke with
     * an empty buffer.
     *
     * @throws IOException if draining the stream fails
     */
    @Test
    public void fillingTheGapAndDrainingGivesTheRoomBack() throws IOException {
        MessageInputStream in = stream();
        try {
            feedOutOfOrder(in, 4, WIRE);
            assertFalse(in.canAccept(5, WIRE));

            in.messageReceived(0, block(0));
            // 0 promoted 1-4 into the readable half; the buffer is still full
            assertFalse("the promoted blocks were not counted as buffered", in.canAccept(5, WIRE));

            byte[] target = new byte[MSG];
            int total = 0;
            while (total < 4 * WIRE) {
                int r = in.read(target, 0, target.length);
                if (r <= 0) {
                    fail("read stalled after " + total + " bytes");
                }
                total += r;
            }
            assertTrue("the drained buffer still refused a block", in.canAccept(5, WIRE));
        } finally {
            in.close();
        }
    }

    /**
     * Blocks waiting on a gap hold the grown cap: the decay runs when the reader
     * empties the buffer, and a gap still being waited on means it is not empty.
     *
     * @throws IOException if draining the stream fails
     */
    @Test
    public void outOfOrderBlocksHoldTheGrownCap() throws IOException {
        MessageInputStream in = stream();
        try {
            assertTrue(in.growBuffer());
            int grown = in.getMaxBufferSize();

            in.messageReceived(0, block(MSG));
            in.messageReceived(2, block(MSG));
            byte[] target = new byte[MSG];
            assertEquals(MSG, in.read(target, 0, MSG));
            assertEquals("the cap decayed while a block still waited on the gap",
                         grown, in.getMaxBufferSize());

            in.messageReceived(1, block(MSG));
            assertEquals(MSG, in.read(target, 0, MSG));
            assertEquals(MSG, in.read(target, 0, MSG));
            assertEquals("a fully drained buffer kept its grown cap",
                         BUFFER, in.getMaxBufferSize());
        } finally {
            in.close();
        }
    }

    /**
     * Honest traffic is untouched: a peer that sends at the negotiated size still
     * fills the buffer to the same block count as before, so the tighter
     * accounting costs throughput on no connection that behaves.
     */
    @Test
    public void mssSizedBlocksStillFillTheBuffer() {
        MessageInputStream in = stream();
        try {
            int count = BUFFER / MSG;
            for (int id = 0; id < count; id++) {
                assertTrue("block " + id + " was rejected", in.messageReceived(id, block(MSG)));
            }
            assertFalse(in.canAccept(count, 1));
            assertTrue(in.canAccept(count - 1, 1));
        } finally {
            in.close();
        }
    }
}
