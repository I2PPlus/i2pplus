package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Tests for the per-torrent piece write queue in
 * <code>PeerCoordinator.queuePieceWrite</code> and <code>drainWriteQueue</code>.
 * Verifying a piece is a full SHA-1 over it, and storing it is another pass over the
 * data plus the write to the torrent file; both used to run on the connection thread of
 * the peer that finished the piece, where they stalled its inbound stream for the whole
 * duration. They now run on a shared, bounded writer pool, with one draining task at a
 * time per torrent so that torrent's file writes stay in order.
 *
 * <p>The coordinator is built in magnet mode (no MetaInfo, no Storage) and
 * {@link PeerCoordinator#writePieceNow} is overridden, so the queue is exercised
 * without touching disk.
 *
 * @since 0.9.72+
 */
public class PieceWriteQueueTest {

    /** Torrent id and infohash for the fixture coordinators. */
    private static final byte[] ID = new byte[20];
    private static final byte[] IH = new byte[32];

    /** Piece length used for the queued pieces; small enough to stay in memory. */
    private static final int LEN = 16 * 1024;

    /** How long a fixture waits for a write to land before calling it lost. */
    private static final long WAIT_MS = 5000;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public PieceWriteQueueTest() {}

    /**
     * Coordinator that records the pieces handed to it instead of writing them, and
     * can hold the writer thread inside a write.
     */
    private static class Recorder extends PeerCoordinator {

        /** Piece numbers written, in the order they were written. */
        final List<Integer> _pieces = new CopyOnWriteArrayList<>();

        /** Name of the thread each write ran on, same order as _pieces. */
        final List<String> _threads = new CopyOnWriteArrayList<>();

        /** Set once a write has started, before waiting on the fixtures below. */
        volatile boolean _parked;

        /** Tripped when this many writers are inside a write at once, or null. */
        final CyclicBarrier _barrier;

        /** Writers wait here until the test counts it down, or null. */
        final CountDownLatch _gate;

        /**
         * Magnet-mode coordinator with no storage.
         *
         * @param util the snark util
         * @param barrier barrier writers wait on until it trips, or null
         * @param gate latch writers wait on until the test releases it, or null
         */
        Recorder(I2PSnarkUtil util, CyclicBarrier barrier, CountDownLatch gate) {
            super(util, ID, IH, null, null, null, null, null);
            _barrier = barrier;
            _gate = gate;
        }

        @Override
        void writePieceNow(Peer peer, PartialPiece pp, Piece wanted) {
            _parked = true;
            try {
                if (_barrier != null) _barrier.await(5, TimeUnit.SECONDS);
                if (_gate != null) _gate.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // timeout or broken barrier: record anyway, the assertions decide
            }
            _pieces.add(Integer.valueOf(pp.getPiece()));
            _threads.add(Thread.currentThread().getName());
        }
    }

    /**
     * A fresh magnet-mode util for each fixture; magnet mode needs no session.
     *
     * @return a util with the global context
     */
    private static I2PSnarkUtil newUtil() {
        return new I2PSnarkUtil(net.i2p.I2PAppContext.getGlobalContext());
    }

    /**
     * A completed in-memory partial piece for the given number.
     *
     * @param piece the piece number the fake data stands for
     * @return the piece, with one sub-block marked so it holds data
     */
    private static PartialPiece piece(int piece) {
        PartialPiece pp = new PartialPiece(new Piece(piece), LEN, new File("/tmp"));
        pp.markSubBlock(0);
        return pp;
    }

    /**
     * Waits for a write to have started on this coordinator.
     *
     * @param rec the coordinator to watch for a write starting
     * @return true if a write started within 5 seconds
     */
    private static boolean awaitParked(Recorder rec) {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (!rec._parked && System.currentTimeMillis() < end) {
            sleep(10);
        }
        return rec._parked;
    }

    /**
     * Waits for the coordinator to have written n pieces.
     *
     * @param rec the coordinator whose writes are counted
     * @param n how many writes to wait for
     * @return true if the count was reached within 5 seconds
     */
    private static boolean awaitWrites(Recorder rec, int n) {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (rec._pieces.size() < n && System.currentTimeMillis() < end) {
            sleep(10);
        }
        return rec._pieces.size() >= n;
    }

    /**
     * Sleep, ignoring the interrupt.
     *
     * @param ms how long
     */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) { /* ignored */ }
    }

    /**
     * Queued pieces are written in the order they were completed, and off the calling
     * thread, on one of the pool's SnarkWr threads.
     */
    @Test
    public void testWritesRunOnWriterThreadInOrder() {
        Recorder rec = new Recorder(newUtil(), null, null);
        try {
            for (int i = 0; i < 3; i++) {
                assertTrue(rec.queuePieceWrite(null, piece(i), null));
            }
            assertTrue("only " + rec._pieces.size() + " writes ran", awaitWrites(rec, 3));
            assertEquals(0, rec.queuedWrites());
            for (int i = 0; i < 3; i++) {
                assertEquals(Integer.valueOf(i), rec._pieces.get(i));
                assertTrue(
                        "write " + i + " ran on " + rec._threads.get(i),
                        rec._threads.get(i).startsWith("SnarkWr"));
            }
        } finally {
            rec.halt();
        }
    }

    /**
     * A torrent may not queue more than MAX_QUEUED_WRITES pieces behind the one being
     * written; past that the caller is told to write the piece itself, which is the
     * backpressure that keeps the queued piece data bounded.
     */
    @Test
    public void testQueueFullFallsBackToInlineWrite() {
        CountDownLatch gate = new CountDownLatch(1);
        Recorder rec = new Recorder(newUtil(), null, gate);
        try {
            assertTrue(rec.queuePieceWrite(null, piece(0), null));
            assertTrue("writer never started", awaitParked(rec));
            for (int i = 1; i <= PeerCoordinator.MAX_QUEUED_WRITES; i++) {
                assertTrue("piece " + i + " rejected", rec.queuePieceWrite(null, piece(i), null));
            }
            assertEquals(PeerCoordinator.MAX_QUEUED_WRITES, rec.queuedWrites());
            assertFalse(rec.queuePieceWrite(null, piece(99), null));
            assertEquals(PeerCoordinator.MAX_QUEUED_WRITES, rec.queuedWrites());
        } finally {
            gate.countDown();
            rec.halt();
        }
    }

    /**
     * Halting the torrent drops the pieces that were still waiting for the writer
     * thread; they are never written after the torrent is gone.
     */
    @Test
    public void testHaltDropsQueuedWrites() {
        CountDownLatch gate = new CountDownLatch(1);
        Recorder rec = new Recorder(newUtil(), null, gate);
        try {
            assertTrue(rec.queuePieceWrite(null, piece(0), null));
            assertTrue("writer never started", awaitParked(rec));
            for (int i = 1; i < PeerCoordinator.MAX_QUEUED_WRITES; i++) {
                assertTrue(rec.queuePieceWrite(null, piece(i), null));
            }
            assertTrue(rec.queuedWrites() > 0);
            rec.halt();
            gate.countDown();
            // let the parked write finish and the drain task notice it is halted
            assertTrue("queued writes not dropped", waitQueueEmpty(rec));
            sleep(100);
            assertTrue("wrote " + rec._pieces.size() + " pieces after halt", rec._pieces.size() <= 1);
        } finally {
            gate.countDown();
            rec.halt();
        }
    }

    /**
     * Waits for this coordinator's write queue to empty.
     *
     * @param rec the coordinator whose queue is watched
     * @return true if it emptied within 5 seconds
     */
    private static boolean waitQueueEmpty(Recorder rec) {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (rec.queuedWrites() > 0 && System.currentTimeMillis() < end) {
            sleep(10);
        }
        return rec.queuedWrites() == 0;
    }

    /**
     * Two torrents write at the same time, so one torrent's disk I/O cannot hold up
     * another's. That is what more than one writer thread buys: with a single writer thread the
     * barrier below never trips, both writes time out, and the two pieces land on the same
     * thread instead of two.
     */
    @Test
    public void testTwoTorrentsWriteConcurrently() {
        CyclicBarrier both = new CyclicBarrier(2);
        Recorder one = new Recorder(newUtil(), both, null);
        Recorder two = new Recorder(newUtil(), both, null);
        try {
            assertTrue(one.queuePieceWrite(null, piece(1), null));
            assertTrue(two.queuePieceWrite(null, piece(2), null));
            assertTrue("torrent one did not write", awaitWrites(one, 1));
            assertTrue("torrent two did not write", awaitWrites(two, 1));
            assertEquals(Integer.valueOf(1), one._pieces.get(0));
            assertEquals(Integer.valueOf(2), two._pieces.get(0));
            assertNotEquals(one._threads.get(0), two._threads.get(0));
        } finally {
            one.halt();
            two.halt();
        }
    }

    /**
     * A piece queued once the pool has neither a free thread nor a free waiting slot is still
     * written, on the thread that queued it, rather than being dropped. Everything before that is
     * absorbed: one thread per torrent while they last, then the pool's own queue.
     */
    @Test
    public void testWriteHappensWhenPoolSaturated() {
        int threads = PeerCoordinator.MAX_WRITER_THREADS;
        // hold every writer thread for the whole test
        CountDownLatch gate = new CountDownLatch(1);
        List<Recorder> parked = new ArrayList<>(threads);
        List<Recorder> waiting = new ArrayList<>(PeerCoordinator.WRITER_QUEUE_SLOTS);
        Recorder inline = new Recorder(newUtil(), null, null);
        try {
            for (int i = 0; i < threads; i++) {
                Recorder rec = new Recorder(newUtil(), null, gate);
                parked.add(rec);
                assertTrue("write " + i + " not queued", rec.queuePieceWrite(null, piece(i), null));
                assertTrue("writer " + i + " never started", awaitParked(rec));
            }
            // every thread is busy, so these drain tasks wait in the pool's own queue rather
            // than being run by the peer's connection thread
            for (int i = 0; i < PeerCoordinator.WRITER_QUEUE_SLOTS; i++) {
                Recorder rec = new Recorder(newUtil(), null, null);
                waiting.add(rec);
                assertTrue("write " + i + " rejected",
                           rec.queuePieceWrite(null, piece(threads + i), null));
                assertEquals("write " + i + " ran before a thread freed", 1, rec.queuedWrites());
            }
            // no thread and no waiting slot left, so this one must be drained inline
            assertTrue(inline.queuePieceWrite(null, piece(99), null));
            assertEquals(0, inline.queuedWrites());
            assertEquals(Integer.valueOf(99), inline._pieces.get(0));
        } finally {
            gate.countDown();
            for (Recorder rec : parked) {
                rec.halt();
            }
            for (Recorder rec : waiting) {
                rec.halt();
            }
            inline.halt();
        }
    }

    /**
     * The writer thread ceiling follows the machine: never below two, so one torrent's write does
     * not wait behind another torrent's recheck, and never so high that the SHA-1 verify passes
     * compete with the router's own threads.
     */
    @Test
    public void testWriterThreadsFollowTheCores() {
        assertEquals(2, PeerCoordinator.writerThreads(1));
        assertEquals(2, PeerCoordinator.writerThreads(2));
        assertEquals(4, PeerCoordinator.writerThreads(4));
        assertEquals(8, PeerCoordinator.writerThreads(8));
        assertEquals(8, PeerCoordinator.writerThreads(64));
        assertTrue(PeerCoordinator.MAX_WRITER_THREADS >= 2);
        assertTrue(PeerCoordinator.MAX_WRITER_THREADS <= 8);
    }

    /**
     * As many torrents as there are writer threads write at the same instant, which is what
     * sizing the pool from the machine buys: with the old fixed two, a third torrent's piece was
     * verified and written on its own peer's connection thread, stalling that peer's download.
     */
    @Test
    public void testEveryWriterThreadTakesATorrent() {
        int n = PeerCoordinator.MAX_WRITER_THREADS;
        CyclicBarrier all = new CyclicBarrier(n);
        List<Recorder> recs = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                Recorder rec = new Recorder(newUtil(), all, null);
                recs.add(rec);
                assertTrue("torrent " + i + " not queued", rec.queuePieceWrite(null, piece(i), null));
            }
            for (int i = 0; i < n; i++) {
                assertTrue("torrent " + i + " did not write", awaitWrites(recs.get(i), 1));
            }
            // every write ran inside the barrier at once, each on its own thread
            Set<String> threads = new HashSet<>();
            for (Recorder rec : recs) {
                threads.add(rec._threads.get(0));
            }
            assertEquals(n, threads.size());
        } finally {
            for (Recorder rec : recs) {
                rec.halt();
            }
        }
    }

    /**
     * A piece queued after the torrent is halted is dropped, not written: the draining
     * task checks halted() before every write.
     */
    @Test
    public void testNoWriteAfterHalt() {
        Recorder rec = new Recorder(newUtil(), null, null);
        try {
            rec.halt();
            assertTrue(rec.queuePieceWrite(null, piece(7), null));
            assertTrue("queued write not dropped", waitQueueEmpty(rec));
            sleep(100);
            assertEquals(0, rec._pieces.size());
        } finally {
            rec.halt();
        }
    }
}
