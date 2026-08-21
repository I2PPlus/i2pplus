package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.i2p.data.ByteArray;

import org.junit.Test;

/**
 * Tests for the deferred-load state machine in {@link Message}: exactly-once loader execution,
 * release-once buffer ownership, discard racing with prefetch, and unchanged wire format.
 *
 * @since 0.9.71+
 */
public class MessagePrefetchTest {

    private static final int PIECE = 3;
    private static final int BEGIN = 4096;
    private static final int SHORT_LEN = 1024;

    /** Simple holder for an output stream pair so written bytes are retrievable. */
    private static final class Sink {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStream dos = new DataOutputStream(bos);

        byte[] bytes() throws IOException {
            dos.flush();
            return bos.toByteArray();
        }
    }

    /** Loader that counts invocations; returns the configured payload (may simulate failure). */
    private static final class CountingLoader implements DataLoader {
        final AtomicInteger calls = new AtomicInteger();
        final ByteArray payload;
        final long delayMs;

        CountingLoader(byte[] payload) {
            this(payload, 0);
        }

        CountingLoader(byte[] payload, long delayMs) {
            this.payload = payload != null ? new ByteArray(payload) : null;
            this.delayMs = delayMs;
        }

        @Override
        public ByteArray loadData(int piece, int begin, int length) {
            calls.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                }
            }
            return payload;
        }
    }

    /** Simulates PeerConnectionOut's prefetch task: claim, load, publish. */
    private static void runPrefetch(Message m) {
        if (!m.claimLoad()) return;
        if (m.abortIfDiscarded()) return;
        m.completeLoad(m.runLoader());
    }

    private static byte[] payload(int len) {
        byte[] rv = new byte[len];
        for (int i = 0; i < len; i++) rv[i] = (byte) i;
        return rv;
    }

    private static byte[] expectedPieceWire(int piece, int begin, byte[] data) throws IOException {
        Sink s = new Sink();
        s.dos.writeInt(9 + data.length);
        s.dos.writeByte(Message.PIECE & 0xFF);
        s.dos.writeInt(piece);
        s.dos.writeInt(begin);
        s.dos.write(data);
        return s.bytes();
    }

    // ---- happy paths ------------------------------------------------------

    @Test
    public void testLazySendWritesWireFormat() throws Exception {
        byte[] pay = payload(SHORT_LEN);
        CountingLoader cl = new CountingLoader(pay);
        Message m = new Message(PIECE, BEGIN, pay.length, cl);
        Sink s = new Sink();

        assertTrue(m.sendMessage(s.dos));
        assertArrayEquals(expectedPieceWire(PIECE, BEGIN, pay), s.bytes());
        assertEquals("inline fallback must load exactly once", 1, cl.calls.get());
        assertFalse("buffer must be released after send", m.isDataReady());
    }

    @Test
    public void testPrefetchedSendSkipsSecondLoad() throws Exception {
        byte[] pay = payload(SHORT_LEN);
        CountingLoader cl = new CountingLoader(pay);
        Message m = new Message(PIECE, BEGIN, pay.length, cl);
        runPrefetch(m);
        assertTrue(m.isDataReady());

        Sink s = new Sink();
        assertTrue(m.sendMessage(s.dos));
        assertArrayEquals(expectedPieceWire(PIECE, BEGIN, pay), s.bytes());
        assertEquals("prefetch result must be reused, no re-read", 1, cl.calls.get());
    }

    @Test
    public void testAwaitConsumesInFlightPrefetch() throws Exception {
        byte[] pay = payload(SHORT_LEN);
        CountingLoader cl = new CountingLoader(pay);
        final Message m = new Message(PIECE, BEGIN, pay.length, cl);

        assertTrue("task must win the claim", m.claimLoad());
        final AtomicReference<byte[]> sent = new AtomicReference<>();
        Thread sender =
                new Thread(
                        () -> {
                            try {
                                Sink s = new Sink();
                                if (m.sendMessage(s.dos)) sent.set(s.bytes());
                            } catch (IOException ioe) {
                                throw new RuntimeException(ioe);
                            }
                        });
        sender.start();
        // give the sender time to block in awaitLoad(), then complete the prefetch
        Thread.sleep(100);
        m.completeLoad(m.runLoader());
        sender.join(5000);
        assertFalse(sender.isAlive());
        assertArrayEquals(expectedPieceWire(PIECE, BEGIN, pay), sent.get());
        assertEquals(1, cl.calls.get());
    }

    @Test
    public void testFullSizeBlockSendReleasesToCache() throws Exception {
        // PARTSIZE-length buffer exercises the ByteCache release guard branch
        byte[] pay = payload(PeerState.PARTSIZE);
        Message m = new Message(PIECE, BEGIN, pay.length, new CountingLoader(pay));
        Sink s = new Sink();
        assertTrue(m.sendMessage(s.dos));
        assertArrayEquals(expectedPieceWire(PIECE, BEGIN, pay), s.bytes());
        assertFalse(m.isDataReady());
    }

    // ---- failure and discard paths ----------------------------------------

    @Test
    public void testFailedLoadDropsMessage() throws Exception {
        Message m = new Message(PIECE, BEGIN, SHORT_LEN, new CountingLoader(null));
        Sink s = new Sink();
        assertFalse(m.sendMessage(s.dos));
        assertEquals(0, s.bytes().length);
    }

    @Test
    public void testDiscardAfterLoadReleasesExactlyOnce() {
        Message m = new Message(PIECE, BEGIN, SHORT_LEN, new CountingLoader(payload(SHORT_LEN)));
        runPrefetch(m);
        assertTrue(m.isDataReady());
        m.discard();
        m.discard(); // must be a safe no-op
        assertTrue(m.isDiscarded());
        assertFalse(m.isDataReady());
    }

    @Test
    public void testDiscardBeforeCompletionSkipsLoadAndRejectsLateResult() {
        byte[] pay = payload(SHORT_LEN);
        // purge lands between claim and task start: abort check must let the task skip loading
        Message m = new Message(PIECE, BEGIN, pay.length, new CountingLoader(pay));
        assertTrue(m.claimLoad());
        m.discard();
        assertTrue("purged before task ran", m.abortIfDiscarded());
        assertTrue(m.isDiscarded());

        // late-arriving result from a task past its abort check must not be stored
        Message m2 = new Message(PIECE, BEGIN, pay.length, new CountingLoader(pay));
        assertTrue(m2.claimLoad());
        m2.discard();
        m2.completeLoad(new ByteArray(pay));
        assertFalse(m2.isDataReady());
        assertFalse(m2.claimLoad());
    }

    @Test
    public void testClaimIsExclusive() {
        CountingLoader cl = new CountingLoader(payload(SHORT_LEN));
        Message m = new Message(PIECE, BEGIN, SHORT_LEN, cl);
        assertTrue(m.claimLoad());
        assertFalse(m.claimLoad());
        m.completeLoad(m.runLoader());
        assertFalse(m.claimLoad()); // already complete
        m.discard();
        assertFalse(m.claimLoad()); // discarded
    }

    // ---- concurrency -------------------------------------------------------

    /**
     * Races send, discard, and prefetch across threads; whatever interleaving occurs, the loader
     * must never run more than once per message.
     */
    @Test(timeout = 60000)
    public void testConcurrentSendDiscardExactlyOneLoad() throws Exception {
        for (int iter = 0; iter < 25; iter++) {
            final byte[] pay = payload(SHORT_LEN);
            final CountingLoader cl = new CountingLoader(pay, 2);
            final Message m = new Message(PIECE, BEGIN, pay.length, cl);
            final CyclicBarrier barrier = new CyclicBarrier(4);
            final AtomicInteger failures = new AtomicInteger();
            final AtomicReference<byte[]> bytes =
                    new AtomicReference<>(new byte[0]);

            Runnable sendTask =
                    () -> {
                        try {
                            barrier.await();
                            Sink s = new Sink();
                            if (m.sendMessage(s.dos)) bytes.set(s.bytes());
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    };
            Runnable discardTask =
                    () -> {
                        try {
                            barrier.await();
                            m.discard();
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    };
            Runnable prefetchTask =
                    () -> {
                        try {
                            barrier.await();
                            runPrefetch(m);
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    };

            Thread t1 = new Thread(sendTask);
            Thread t2 = new Thread(discardTask);
            Thread t3 = new Thread(prefetchTask);
            Thread t4 = new Thread(discardTask);
            t1.start();
            t2.start();
            t3.start();
            t4.start();
            t1.join(10000);
            t2.join(10000);
            t3.join(10000);
            t4.join(10000);

            assertEquals(0, failures.get());
            assertTrue("loader ran " + cl.calls.get() + " times", cl.calls.get() <= 1);
            if (!m.isDiscarded()) {
                assertArrayEquals(expectedPieceWire(PIECE, BEGIN, pay), bytes.get());
            }
        }
    }

    /** Deterministic variant: pre-claimed message; discard must wake an awaiting sender. */
    @Test(timeout = 15000)
    public void testDiscardWakesAwaitingSender() throws Exception {
        final Message m = new Message(PIECE, BEGIN, SHORT_LEN, new CountingLoader(null));
        final CountDownLatch started = new CountDownLatch(1);
        assertTrue(m.claimLoad());

        final AtomicReference<Boolean> result = new AtomicReference<>();
        Thread sender =
                new Thread(
                        () -> {
                            started.countDown();
                            try {
                                result.set(
                                        Boolean.valueOf(
                                                m.sendMessage(new Sink().dos)));
                            } catch (IOException ioe) {
                                throw new RuntimeException(ioe);
                            }
                        });
        sender.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(50); // let it enter wait()
        m.discard();
        sender.join(5000);
        assertFalse(sender.isAlive());
        assertEquals(Boolean.FALSE, result.get());
    }

    // ---- control messages untouched ----------------------------------------

    @Test
    public void testKeepAliveUnchanged() throws Exception {
        Message m = new Message(Message.KEEP_ALIVE);
        Sink s = new Sink();
        assertTrue(m.sendMessage(s.dos));
        Sink exp = new Sink();
        exp.dos.writeInt(0);
        assertArrayEquals(exp.bytes(), s.bytes());
    }

    @Test
    public void testRequestFormatUnchanged() throws Exception {
        Message m = new Message(Message.REQUEST, 7, 16384, 16384);
        Sink s = new Sink();
        assertTrue(m.sendMessage(s.dos));

        Sink exp = new Sink();
        exp.dos.writeInt(13);
        exp.dos.writeByte(Message.REQUEST & 0xFF);
        exp.dos.writeInt(7);
        exp.dos.writeInt(16384);
        exp.dos.writeInt(16384);
        assertArrayEquals(exp.bytes(), s.bytes());
    }
}
