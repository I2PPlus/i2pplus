package net.i2p.router.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.SessionId;
import net.i2p.internal.PoisonI2CPMessage;
import org.junit.Test;

/**
 * Tests for the in-JVM I2CP queue pair shared with a client.
 *
 * <p>The router reports "the client is not draining its I2CP queue" from these queues, and
 * used to print the depth of the wrong one: the read side it drains itself, not the write side
 * whose overflow made the offer fail. These tests pin which accessor counts which side, so the
 * diagnostic cannot silently point at the router's own backlog again.
 *
 * <p>They also pin the two things the diagnostic now depends on: that a close at one end is
 * visible at the other (a closed queue and a full one fail an offer identically, and only one
 * of them ever recovers), and that queueing a message reaches the reader at the other end
 * instead of waiting for the dispatcher to notice.
 *
 * <p>No router, session or thread is involved - one pair of backing queues and both wrappers.
 */
public class I2CPMessageQueueBacklogTest {

    /** Capacity used for the bounded pairs, small enough to fill in a test. */
    private static final int CAPACITY = 4;

    /**
     * Each end's backlog and pending counts describe opposite sides of the same pair.
     *
     * <p>{@code pending()} is what this end has not read; {@code writeBacklog()} is what this
     * end has written and the peer has not taken. A router's failed offer is explained only by
     * the second, and the first stays at zero because the router drains its own read side.
     */
    @Test
    public void testBacklogCountsTheSideOfferWritesTo() {
        Pair pair = new Pair(CAPACITY);
        for (int i = 0; i < 3; i++) {
            assertTrue("offer rejected on a queue with room", pair._toClient.offer(new Msg(i)));
        }
        assertEquals("the writing end must see its own backlog", 3, pair._toClient.writeBacklog());
        assertEquals("the writing end's read side is untouched", 0, pair._toClient.pending());
        assertEquals("the reading end sees the same messages as pending", 3, pair._fromClient.pending());
        assertEquals("the reading end has written nothing", 0, pair._fromClient.writeBacklog());
        assertEquals("free slots must match the same side as the backlog",
                     CAPACITY - 3, pair._toClient.remainingCapacity());
    }

    /** The backlog is the number that explains a rejected offer, at the moment it is rejected. */
    @Test
    public void testBacklogEqualsCapacityWhenTheOfferFails() {
        Pair pair = new Pair(CAPACITY);
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue("offer " + i + " rejected early", pair._toClient.offer(new Msg(i)));
        }
        assertFalse("an offer at capacity must fail", pair._toClient.offer(new Msg(CAPACITY)));
        assertEquals(CAPACITY, pair._toClient.writeBacklog());
        assertEquals(0, pair._toClient.remainingCapacity());
        // The read side still says nothing at all.
        assertEquals(0, pair._toClient.pending());
    }

    /**
     * A close at either end has to be visible from both.
     *
     * <p>Each end holds its own wrapper over the shared pair, so without the link a router
     * writing to a client that has gone away sees a queue that merely looks full, and keeps
     * handing it messages forever.
     */
    @Test
    public void testCloseAtEitherEndIsVisibleAtBoth() {
        Pair pair = new Pair(CAPACITY);
        assertFalse(pair._toClient.isClosed());
        pair._fromClient.close();
        assertTrue("a close at the client end is invisible at the router end", pair._toClient.isClosed());
        assertTrue(pair._fromClient.isClosed());
        assertTrue("the poison message must reach the router's reader",
                   pair._toClient.poll() instanceof PoisonI2CPMessage);

        Pair other = new Pair(CAPACITY);
        other._toClient.close();
        assertTrue("a close at the router end is invisible at the client end", other._fromClient.isClosed());
        assertTrue("the poison message must reach the client's reader",
                   other._fromClient.poll() instanceof PoisonI2CPMessage);
    }

    /**
     * A close that cannot queue its poison message is still a close.
     *
     * <p>The usual reason to close is that the peer has already gone and its queue is full, so
     * the poison no longer fits. The offer failing must not turn the close into a no-op: an
     * offer afterwards still fails, and only the closed flag tells the writer that it always
     * will.
     */
    @Test
    public void testCloseOnAFullQueueStillClosesBothEnds() {
        Pair pair = new Pair(CAPACITY);
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(pair._toClient.offer(new Msg(i)));
        }
        pair._toClient.close();
        assertTrue("a close that could not queue the poison was not recorded", pair._toClient.isClosed());
        assertTrue(pair._fromClient.isClosed());
        assertFalse("the queue was not full before the close", pair._toClient.offer(new Msg(99)));
    }

    /**
     * Queueing a message for the peer must reach the reader that serves it.
     *
     * <p>The dispatcher pool leaves a reader that drained its queue out of its ready list, so
     * a message arriving later has to schedule that reader itself. This is the wake-up the
     * reader registers while it is reading.
     */
    @Test
    public void testOfferWakesThePeersReader() {
        Pair pair = new Pair(CAPACITY);
        AtomicInteger wakes = new AtomicInteger();
        pair._fromClient.setWakeHook(() -> wakes.incrementAndGet());
        assertTrue(pair._toClient.offer(new Msg(1)));
        assertEquals("the offer did not reach the reader at the other end", 1, wakes.get());
        assertTrue(pair._toClient.offer(new Msg(2)));
        assertEquals(2, wakes.get());
    }

    /** A wake-up registered for one direction must not fire for the other. */
    @Test
    public void testWakeHookFiresOnlyForItsOwnDirection() {
        Pair pair = new Pair(CAPACITY);
        AtomicInteger toClient = new AtomicInteger();
        AtomicInteger fromClient = new AtomicInteger();
        pair._fromClient.setWakeHook(() -> toClient.incrementAndGet());
        pair._toClient.setWakeHook(() -> fromClient.incrementAndGet());
        pair._toClient.offer(new Msg(1));
        assertEquals(1, toClient.get());
        assertEquals("the wrong end was woken", 0, fromClient.get());
        pair._fromClient.offer(new Msg(2));
        assertEquals("the wrong end was woken", 1, toClient.get());
        assertEquals(1, fromClient.get());
    }

    /** Unregistering the hook stops the wake-ups, which is what a stopped reader does. */
    @Test
    public void testClearingTheWakeHookStopsWakeUps() {
        Pair pair = new Pair(CAPACITY);
        AtomicInteger wakes = new AtomicInteger();
        pair._fromClient.setWakeHook(() -> wakes.incrementAndGet());
        pair._fromClient.setWakeHook(null);
        pair._toClient.offer(new Msg(1));
        assertEquals("a cleared hook still fired", 0, wakes.get());
    }

    /**
     * A wake-up that throws must not lose the message or the offer.
     *
     * <p>The hook runs on the writer's thread, which may be a transport thread, and the message
     * is already queued by the time the hook is reached. Losing the wake-up only delays the
     * reader until the dispatcher's next sweep.
     */
    @Test
    public void testWakeHookFailureKeepsTheMessage() {
        Pair pair = new Pair(CAPACITY);
        pair._fromClient.setWakeHook(() -> {throw new IllegalStateException("boom");});
        assertTrue("a failing wake-up failed the offer", pair._toClient.offer(new Msg(7)));
        assertEquals(1, pair._toClient.writeBacklog());
        assertNotNull("the message was not queued", pair._fromClient.poll());
    }

    /**
     * A queue built without a peer still works.
     *
     * <p>Only {@code internalConnect()} links a pair; a stand-alone queue has nobody to wake
     * and nobody to tell that it closed.
     */
    @Test
    public void testUnlinkedQueueStillOffersAndReports() {
        LinkedBlockingQueue<I2CPMessage> in = new LinkedBlockingQueue<>(CAPACITY);
        LinkedBlockingQueue<I2CPMessage> out = new LinkedBlockingQueue<>(CAPACITY);
        I2CPMessageQueueImpl alone = new I2CPMessageQueueImpl(in, out);
        assertTrue(alone.offer(new Msg(1)));
        assertEquals(1, alone.writeBacklog());
        assertEquals(0, alone.pending());
        assertFalse(alone.isClosed());
        alone.close();
        assertTrue(alone.isClosed());
    }

    /** One pair of backing queues and the two wrappers, as {@code internalConnect()} builds them. */
    private static class Pair {
        final I2CPMessageQueueImpl _toClient;
        final I2CPMessageQueueImpl _fromClient;

        /**
         * Build the pair with the queues swapped for each end.
         *
         * @param capacity capacity of each backing queue (messages)
         */
        Pair(int capacity) {
            LinkedBlockingQueue<I2CPMessage> in = new LinkedBlockingQueue<>(capacity);
            LinkedBlockingQueue<I2CPMessage> out = new LinkedBlockingQueue<>(capacity);
            _toClient = new I2CPMessageQueueImpl(in, out);
            _fromClient = new I2CPMessageQueueImpl(out, in);
            _toClient.link(_fromClient);
        }
    }

    /** A message with no content beyond its identity. */
    private static class Msg implements I2CPMessage {
        private final int _seq;

        Msg(int seq) {_seq = seq;}

        @Override
        public void readMessage(InputStream in, int size, int type) {}

        @Override
        public void readMessage(InputStream in) throws I2CPMessageException, IOException {}

        @Override
        public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {}

        @Override
        public int getType() {return 1;}

        @Override
        public SessionId sessionId() {return null;}
    }
}
