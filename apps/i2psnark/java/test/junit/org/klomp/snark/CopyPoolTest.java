package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for the bounded staging-copy pool: <code>Storage.copyThreads</code>
 * sizes it, <code>Storage.newCopyExecutor</code> builds it, and
 * <code>Storage.CopyThreadFactory</code> names its threads. A torrent with many
 * files used to start one thread per completed file, and they all competed for
 * the same disk, so the torrent stalled at the point it should have finished
 * fastest. The pool queues the copies instead, and the queue is safe because
 * <code>Storage._copying</code> admits one task per file.
 *
 * @since 0.9.72+
 */
public class CopyPoolTest {

    /**
     * A fixture group for the bounded staging-copy pool; it holds no state.
     */
    public CopyPoolTest() {}

    /** The shipped ceiling on concurrent copies, pinned here as the contract. */
    private static final int CEILING = 3;

    /** A task that does nothing. */
    private static final Runnable NOOP = () -> {};

    /**
     * The pool size follows the file count up to the ceiling: a one-file torrent
     * gets one thread, a many-file torrent gets the ceiling, and an empty file
     * list still gets a thread so a copy is never left with nowhere to go.
     */
    @Test
    public void testCopyThreadsTracksFileCount() {
        assertEquals(1, Storage.copyThreads(0));
        assertEquals(1, Storage.copyThreads(1));
        assertEquals(2, Storage.copyThreads(2));
        assertEquals(CEILING, Storage.copyThreads(CEILING));
        assertEquals(CEILING, Storage.copyThreads(1000));
        assertEquals(1, Storage.copyThreads(-5));
    }

    /**
     * The pool size is always in [1, ceiling], so no torrent configures zero
     * copy threads and none configures an unbounded pool.
     */
    @Test
    public void testCopyThreadsStaysInsideItsBounds() {
        for (int files = -10; files < 5000; files++) {
            int threads = Storage.copyThreads(files);
            assertTrue("pool size " + threads + " for " + files + " files",
                       threads >= 1 && threads <= CEILING);
        }
    }

    /**
     * The pool runs at most its own number of copies at once, which is the whole
     * point of the change: 12 completed files on a 3-thread pool never put more
     * than 3 copies on the disk, and every one of the 12 still runs.
     *
     * @throws Exception if a copy task does not finish in time
     */
    @Test
    public void testPoolRunsAtMostPoolSizeCopiesAtOnce() throws Exception {
        int poolSize = Storage.copyThreads(100);
        ExecutorService pool = Storage.newCopyExecutor(poolSize);
        int files = 4 * poolSize;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger widest = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CountDownLatch poolSaturated = new CountDownLatch(poolSize);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < files; i++) {
            futures.add(pool.submit(() -> {
                widest.accumulateAndGet(active.incrementAndGet(), Math::max);
                poolSaturated.countDown();
                try {
                    gate.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                active.decrementAndGet();
                finished.incrementAndGet();
            }));
        }
        assertTrue("pool never reached " + poolSize + " concurrent copies",
                   poolSaturated.await(20, TimeUnit.SECONDS));
        gate.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        assertEquals(files, finished.get());
        assertEquals(poolSize, widest.get());
        pool.shutdownNow();
    }

    /**
     * Every completed file queued is accepted and run, so a 200-file torrent
     * finishing at once loses no copies to a full queue.
     *
     * @throws Exception if a copy task does not finish in time
     */
    @Test
    public void testQueueAcceptsEveryCompletedFile() throws Exception {
        ExecutorService pool = Storage.newCopyExecutor(1);
        int files = 200;
        AtomicInteger ran = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < files; i++) {
            futures.add(pool.submit(() -> ran.incrementAndGet()));
        }
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        assertEquals(files, ran.get());
        pool.shutdownNow();
    }

    /**
     * A closed pool rejects, which is the contract <code>startCopy</code> relies
     * on to release its claim on the file and keep the exception out of the
     * piece-write path.
     */
    @Test
    public void testClosedPoolRejectsSoTheClaimIsReleased() {
        ExecutorService pool = Storage.newCopyExecutor(1);
        pool.shutdownNow();
        try {
            pool.submit(NOOP);
            fail("a closed pool must reject, so startCopy() releases its claim");
        } catch (RejectedExecutionException expected) {
            // the caller drops the file from _copying on this
        }
    }

    /**
     * The pool's threads are daemons with a unique, greppable name: a daemon
     * never keeps the JVM up on exit, and the name is what shows up in a thread
     * dump while a copy is stuck.
     */
    @Test
    public void testFactoryNamesDaemonThreads() {
        Storage.CopyThreadFactory factory = new Storage.CopyThreadFactory();
        Thread first = factory.newThread(NOOP);
        Thread second = factory.newThread(NOOP);
        assertEquals("SnarkStorageCopy-1", first.getName());
        assertEquals("SnarkStorageCopy-2", second.getName());
        assertTrue(first.isDaemon());
        assertTrue(second.isDaemon());
    }

    /**
     * The pool built for a torrent runs its work on a daemon thread named by the
     * factory, so the threads a real Storage creates are the ones tested above.
     *
     * @throws Exception if the task does not finish in time
     */
    @Test
    public void testExecutorUsesTheNamedDaemonThreads() throws Exception {
        ExecutorService pool = Storage.newCopyExecutor(Storage.copyThreads(1));
        AtomicInteger ran = new AtomicInteger();
        List<String> names = new ArrayList<>();
        Future<?> f = pool.submit(() -> {
            names.add(Thread.currentThread().getName());
            assertTrue(Thread.currentThread().isDaemon());
            ran.incrementAndGet();
        });
        f.get(20, TimeUnit.SECONDS);
        assertEquals(1, ran.get());
        assertEquals("SnarkStorageCopy-1", names.get(0));
        pool.shutdownNow();
    }
}
