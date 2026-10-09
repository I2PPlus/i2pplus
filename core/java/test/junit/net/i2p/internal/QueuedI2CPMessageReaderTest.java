package net.i2p.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.data.i2cp.SessionId;
import org.junit.Test;

/**
 * Tests for the shared I2CP dispatch pool.
 *
 * <p>The production symptom these pin down was a client whose I2CP queue filled and then
 * never drained again: the router logged "I2CP write to queue failed" for every message it
 * tried to hand over, LeaseSet requests went unanswered until they timed out, and nothing
 * said why. The pool could strand a registered reader with no worker at all, and could also
 * let one backlogged client hold the only worker indefinitely.
 *
 * <p>Every test uses its own queue and counts deliveries, so nothing depends on a router,
 * a real session, or another test's dispatcher state.
 */
public class QueuedI2CPMessageReaderTest {

    /** How long to wait for the pool to do something it is supposed to do promptly. */
    private static final long TIMEOUT = 10000L;

    /**
     * A drain must stop at its budget even with a full queue behind it.
     *
     * <p>Returning only when the queue is empty is what let one client hold a worker while
     * every other client's queue backed up.
     */
    @Test
    public void testDrainOnceStopsAtBudget() {
        StubQueue queue = new StubQueue();
        queue.fill(100, 0);
        CountingListener lsnr = new CountingListener();
        QueuedI2CPMessageReader reader = new QueuedI2CPMessageReader(queue, lsnr);
        assertEquals(10, reader.drainOnce(10));
        assertEquals(10, lsnr.received.get());
        assertEquals(90, queue.pending());
    }

    /** An empty queue is not a full pass: the caller must not requeue on this result. */
    @Test
    public void testDrainOnceOnEmptyQueueReportsNothing() {
        CountingListener lsnr = new CountingListener();
        QueuedI2CPMessageReader reader = new QueuedI2CPMessageReader(new StubQueue(), lsnr);
        assertEquals(0, reader.drainOnce(10));
        assertEquals(0, lsnr.received.get());
    }

    /** The last message is drained rather than left behind when the queue fits the budget. */
    @Test
    public void testDrainOnceTakesEverythingWithinBudget() {
        StubQueue queue = new StubQueue();
        queue.fill(4, 0);
        CountingListener lsnr = new CountingListener();
        QueuedI2CPMessageReader reader = new QueuedI2CPMessageReader(queue, lsnr);
        assertEquals(4, reader.drainOnce(10));
        assertEquals(0, queue.pending());
    }

    /** A registered reader's queue is drained by the pool, which is the whole point of it. */
    @Test
    public void testRegisteredReaderIsServed() {
        StubQueue queue = new StubQueue();
        CountingListener lsnr = new CountingListener();
        QueuedI2CPMessageReader reader = new QueuedI2CPMessageReader(queue, lsnr);
        reader.startReading();
        try {
            queue.fill(25, 0);
            assertTrue("pool never drained a registered reader", lsnr.await(25, TIMEOUT));
        } finally {
            reader.stopReading();
        }
    }

    /**
     * Registering repeatedly must not let two workers serve one reader at once.
     *
     * <p>I2CP messages for a session are order-dependent, so a duplicate ready-queue entry
     * would deliver them concurrently and out of order.
     */
    @Test
    public void testRepeatedStartReadingKeepsMessagesInOrder() {
        StubQueue queue = new StubQueue();
        OrderingListener lsnr = new OrderingListener();
        QueuedI2CPMessageReader reader = new QueuedI2CPMessageReader(queue, lsnr);
        reader.startReading();
        reader.startReading();
        reader.startReading();
        try {
            queue.fill(60, 0);
            assertTrue("pool never drained the queue", lsnr.await(60, TIMEOUT));
            // give any duplicate ready-queue entry a chance to show itself
            sleep(200);
            assertNull("messages delivered out of order: " + lsnr.error(), lsnr.error());
        } finally {
            reader.stopReading();
        }
    }

    /**
     * The pool must never drop to zero workers while a reader is registered.
     *
     * <p>This is the stranding defect. The pool used to exit when it saw no readers and
     * clear its thread handle only afterwards, so a reader registering in between was left
     * holding a queue nobody drained. Repeating the cycle drives that transition
     * repeatedly; under the old logic the tail of these cycles stalled forever.
     */
    @Test
    public void testReaderIsNeverStrandedWithoutAWorker() {
        for (int cycle = 0; cycle < 40; cycle++) {
            StubQueue queue = new StubQueue();
            CountingListener lsnr = new CountingListener();
            QueuedI2CPMessageReader reader = new QueuedI2CPMessageReader(queue, lsnr);
            reader.startReading();
            try {
                queue.fill(5, 0);
                assertTrue("reader stranded with no worker on cycle " + cycle,
                           lsnr.await(5, TIMEOUT));
            } finally {
                reader.stopReading();
            }
        }
    }

    /**
     * A stopped reader stops being served.
     *
     * <p>Measured after the reader has demonstrably been served once, so the count being
     * compared is not racing a worker that was already in a drain when stopReading ran.
     */
    @Test
    public void testStoppedReaderIsNotServed() {
        StubQueue queue = new StubQueue();
        CountingListener lsnr = new CountingListener();
        QueuedI2CPMessageReader reader = new QueuedI2CPMessageReader(queue, lsnr);
        reader.startReading();
        queue.fill(1, 0);
        assertTrue("reader was never served, so the stop is not being measured",
                   lsnr.await(1, TIMEOUT));
        reader.stopReading();
        int servedAtStop = lsnr.received.get();
        sleep(300);
        assertEquals(servedAtStop, lsnr.received.get());
    }

    /**
     * One backed-up client must not starve another client.
     *
     * <p>The starvation path: a single worker draining a backlogged reader to exhaustion
     * leaves every other client unserved until its queue fills and the router starts
     * refusing to send to it at all.
     */
    @Test
    public void testBackedUpClientDoesNotStarveOthers() {
        StubQueue slowQueue = new StubQueue();
        CountingListener slow = new CountingListener();
        QueuedI2CPMessageReader slowReader = new QueuedI2CPMessageReader(slowQueue, slow);
        slowQueue.fill(50000, 0);

        StubQueue fastQueue = new StubQueue();
        CountingListener fast = new CountingListener();
        QueuedI2CPMessageReader fastReader = new QueuedI2CPMessageReader(fastQueue, fast);

        slowReader.startReading();
        fastReader.startReading();
        try {
            fastQueue.fill(10, 0);
            assertTrue("a backed-up client starved another client", fast.await(10, TIMEOUT));
        } finally {
            slowReader.stopReading();
            fastReader.stopReading();
        }
    }

    /**
     * A message that arrives after the reader's queue was drained must still be delivered.
     *
     * <p>This is the case the requeue policy has to keep working. A worker that drained a
     * reader's queue finds nothing left to serve, so the reader leaves the ready queue and
     * only comes back when the idle tick sees that a message arrived for it. If that path
     * were missing, the message would sit on the queue until it filled for good, which is the
     * original failure this pool was written to prevent.
     *
     * <p>The wait before filling is longer than the poll interval, so the reader has been
     * through at least one idle tick with an empty queue before the message appears.
     */
    @Test
    public void testMessageArrivingAfterADrainIsStillDelivered() {
        StubQueue queue = new StubQueue();
        CountingListener lsnr = new CountingListener();
        QueuedI2CPMessageReader reader = new QueuedI2CPMessageReader(queue, lsnr);
        reader.startReading();
        try {
            // Registered with an empty queue, so the first passes drain nothing and the
            // reader drops out of the ready queue.
            sleep(200);
            assertEquals("nothing should have been delivered from an empty queue",
                         0, lsnr.received.get());
            queue.fill(3, 0);
            assertTrue("a message arriving after a drain was never delivered",
                       lsnr.await(3, TIMEOUT));
        } finally {
            reader.stopReading();
        }
    }

    /**
     * The same late arrival, repeated, to catch a reader that is served once and then
     * silently stops being picked up.
     *
     * <p>One cycle can pass by luck: the worker may still be holding the reader from the
     * initial registration when the message arrives. Repeating it drives the reader through
     * the drain, the idle tick and a fresh arrival many times over.
     */
    @Test
    public void testRepeatedLateArrivalsKeepBeingDelivered() {
        StubQueue queue = new StubQueue();
        CountingListener lsnr = new CountingListener();
        QueuedI2CPMessageReader reader = new QueuedI2CPMessageReader(queue, lsnr);
        reader.startReading();
        try {
            for (int cycle = 0; cycle < 25; cycle++) {
                sleep(40);
                queue.fill(2, 0);
                assertTrue("late arrival missed on cycle " + cycle, lsnr.await(2, TIMEOUT));
            }
        } finally {
            reader.stopReading();
        }
    }

    /**
     * One idle reader must not hide another that has work.
     *
     * <p>The idle tick walks every registered reader, so a reader whose queue stays empty
     * cannot crowd out one that has just been given something to do.
     */
    @Test
    public void testIdleReaderDoesNotMaskAReaderWithWork() {
        StubQueue idleQueue = new StubQueue();
        QueuedI2CPMessageReader idleReader =
            new QueuedI2CPMessageReader(idleQueue, new CountingListener());
        StubQueue busyQueue = new StubQueue();
        CountingListener busy = new CountingListener();
        QueuedI2CPMessageReader busyReader = new QueuedI2CPMessageReader(busyQueue, busy);

        idleReader.startReading();
        busyReader.startReading();
        try {
            sleep(200);
            busyQueue.fill(5, 0);
            assertTrue("an idle reader masked a reader with work", busy.await(5, TIMEOUT));
        } finally {
            idleReader.stopReading();
            busyReader.stopReading();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stands in for the router-to-client side of one in-JVM session. */
    private static class StubQueue extends I2CPMessageQueue {
        private final LinkedBlockingQueue<I2CPMessage> _out = new LinkedBlockingQueue<>();

        /** @param count messages to add @param firstSeq sequence number of the first */
        void fill(int count, int firstSeq) {
            for (int i = 0; i < count; i++) {_out.offer(new SeqMessage(firstSeq + i));}
        }

        @Override
        public boolean offer(I2CPMessage msg) {return _out.offer(msg);}

        @Override
        public boolean offer(I2CPMessage msg, long timeout) {return _out.offer(msg);}

        @Override
        public I2CPMessage poll() {return _out.poll();}

        @Override
        public int pending() {return _out.size();}

        @Override
        public int remainingCapacity() {return Integer.MAX_VALUE - _out.size();}

        @Override
        public void put(I2CPMessage msg) throws InterruptedException {_out.put(msg);}

        @Override
        public I2CPMessage take() throws InterruptedException {return _out.take();}
    }

    /** A message carrying its position in the session's stream. */
    private static class SeqMessage implements I2CPMessage {
        private final int _seq;

        SeqMessage(int seq) {_seq = seq;}

        int seq() {return _seq;}

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

    /** Counts what it was handed. */
    private static class CountingListener implements I2CPMessageReader.I2CPMessageEventListener {
        final AtomicInteger received = new AtomicInteger();

        @Override
        public void messageReceived(I2CPMessageReader reader, I2CPMessage message) {
            received.incrementAndGet();
        }

        /** @return true once count messages have arrived, false on timeout */
        boolean await(int count, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (received.get() < count) {
                if (System.currentTimeMillis() > deadline) {return false;}
                sleep(5);
            }
            return true;
        }

        @Override
        public void readError(I2CPMessageReader reader, Exception error) {}

        @Override
        public void disconnected(I2CPMessageReader reader) {}
    }

    /** Detects the overlap that two workers sharing one reader would cause. */
    private static class OrderingListener extends CountingListener {
        private final AtomicInteger _expected = new AtomicInteger();
        private volatile String _error;

        @Override
        public void messageReceived(I2CPMessageReader reader, I2CPMessage message) {
            int want = _expected.getAndIncrement();
            int got = ((SeqMessage) message).seq();
            if (got != want && _error == null) {_error = "expected " + want + ", got " + got;}
            received.incrementAndGet();
        }

        String error() {return _error;}
    }
}
