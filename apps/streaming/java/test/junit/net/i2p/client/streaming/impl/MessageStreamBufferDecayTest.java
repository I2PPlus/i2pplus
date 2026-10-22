package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests that a grown receive buffer comes back down again.
 * <p>
 * The cap ratchets: every canAccept() failure doubles it, up to 8 MB, and it
 * used to never come back. A fast sender with a slow reader - or any transient
 * burst at all - therefore left the connection carrying the largest buffer it
 * ever needed for the rest of its life, and that memory stays committed whether
 * the traffic uses it or not. The decay is deliberately conservative, so both
 * halves matter: an idle buffer shrinks, and a connection that really is using
 * the larger buffer keeps it.
 *
 * @since 0.9.72+
 */
public class MessageStreamBufferDecayTest {

    private static final int MSG = 1024;
    private static final int WINDOW = 8;
    private static final int FLOOR = 4 * 1024;
    private static final int GROWN = 2 * FLOOR;

    private I2PAppContext _context;

    @Before
    public void setUp() {
        _context = I2PAppContext.getGlobalContext();
    }

    /**
     * A stream whose configured buffer is FLOOR.
     *
     * @return a fresh stream
     */
    private MessageInputStream stream() {
        return new MessageInputStream(_context, MSG, WINDOW, FLOOR);
    }

    /**
     * Hand over count in-sequence blocks of size bytes each, starting at id.
     *
     * @param in the stream to feed
     * @param start first message id
     * @param count number of blocks
     * @param size bytes per block
     */
    private static void feed(MessageInputStream in, int start, int count, int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < count; i++) {
            assertTrue("block " + (start + i) + " was rejected",
                       in.messageReceived(start + i, new ByteArray(data)));
        }
    }

    /**
     * Take n bytes back out of the stream.
     *
     * @param in the stream to drain
     * @param n bytes to read
     * @throws IOException from the stream
     */
    private static void drain(MessageInputStream in, int n) throws IOException {
        byte[] target = new byte[MSG];
        int total = 0;
        int stalled = 0;
        while (total < n) {
            int r = in.read(target, 0, Math.min(target.length, n - total));
            if (r > 0) {
                total += r;
                stalled = 0;
            } else if (++stalled > 100) {
                fail("read stalled after " + total + " of " + n + " bytes");
            }
        }
    }

    /**
     * A buffer whose peak usage shows it is needed is left alone. Without this
     * the decay would fight the growth on every burst and the connection would
     * choke again as soon as the traffic picked up.
     */
    @Test
    public void testHelperKeepsABufferThatIsInUse() {
        assertEquals(GROWN, MessageInputStream.decayBufferSize(GROWN, FLOOR, GROWN));
        assertEquals(GROWN, MessageInputStream.decayBufferSize(GROWN, FLOOR, GROWN / 2));
    }

    /**
     * A buffer that has clearly outgrown the traffic is halved.
     */
    @Test
    public void testHelperHalvesAnIdleBuffer() {
        assertEquals(FLOOR, MessageInputStream.decayBufferSize(GROWN, FLOOR, GROWN / 4));
        assertEquals(FLOOR, MessageInputStream.decayBufferSize(GROWN, FLOOR, 0));
    }

    /**
     * The configured size is a floor, never a target to overshoot. It is derived
     * from the window and the message size, so a smaller buffer would drop data
     * the window can still deliver.
     */
    @Test
    public void testHelperNeverCrossesTheConfiguredSize() {
        assertEquals(FLOOR, MessageInputStream.decayBufferSize(FLOOR + 1, FLOOR, 0));
        assertEquals(FLOOR, MessageInputStream.decayBufferSize(FLOOR, FLOOR, 0));
        // And it never raises a cap that is already below the floor.
        assertEquals(FLOOR / 2, MessageInputStream.decayBufferSize(FLOOR / 2, FLOOR, 0));
    }

    /**
     * Repeated draining converges on the working set instead of running away or
     * oscillating: the cap ends up between the peak and twice the peak.
     */
    @Test
    public void testDecayConvergesOnTheWorkingSet() {
        int peak = 100 * 1024;
        int cap = 8 * 1024 * 1024;
        int prev = Integer.MAX_VALUE;
        for (int i = 0; i < 40; i++) {
            int next = MessageInputStream.decayBufferSize(cap, FLOOR, peak);
            assertTrue("cap grew during decay: " + cap + " -> " + next, next <= cap);
            cap = next;
            if (cap == prev) {
                break;
            }
            prev = cap;
        }
        assertTrue("settled too low to be useful: " + cap, cap >= peak);
        assertTrue("settled far above the working set: " + cap, cap <= 2 * peak);
    }

    /**
     * End to end: growth from a rejected packet, then a reader that catches up,
     * has to give the memory back. This is the regression - the cap used to stay
     * at the grown value for the life of the connection.
     */
    @Test
    public void testDrainingDecaysAGrownCap() throws IOException {
        MessageInputStream in = stream();
        assertTrue(in.growBuffer());
        assertEquals(GROWN, in.getMaxBufferSize());

        feed(in, 0, 2, MSG);
        drain(in, 2 * MSG);

        assertEquals("a drained buffer kept its grown cap", FLOOR, in.getMaxBufferSize());
    }

    /**
     * The peak, not the level at the moment of the check, decides. At the instant
     * the buffer empties the level is always zero, so a decay keyed on that would
     * shrink every connection back to the floor and re-choke the fast ones.
     */
    @Test
    public void testABusyConnectionKeepsItsGrownCap() throws IOException {
        MessageInputStream in = stream();
        assertTrue(in.growBuffer());

        feed(in, 0, GROWN / MSG, MSG);
        drain(in, GROWN);

        assertEquals("a connection using the buffer lost it", GROWN, in.getMaxBufferSize());
    }

    /**
     * A stream that never grew is left exactly where it was configured.
     */
    @Test
    public void testAnUngrownStreamIsUntouched() throws IOException {
        MessageInputStream in = stream();

        feed(in, 0, 2, MSG);
        drain(in, 2 * MSG);
        feed(in, 2, 2, MSG);
        drain(in, 2 * MSG);

        assertEquals(FLOOR, in.getMaxBufferSize());
    }

    /**
     * The check runs when the reader empties the buffer, not on every read. A
     * partial read must not start the decay early.
     */
    @Test
    public void testPartialReadsDoNotDecayEarly() throws IOException {
        MessageInputStream in = stream();
        assertTrue(in.growBuffer());

        feed(in, 0, 2, MSG);
        drain(in, MSG);
        assertEquals("decay ran while data was still buffered", GROWN, in.getMaxBufferSize());

        drain(in, MSG);
        assertEquals(FLOOR, in.getMaxBufferSize());
    }
}
