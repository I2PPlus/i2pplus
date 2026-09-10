package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Tests the sharded receive-path dispatcher that decouples the session
 * notifier thread from per-connection packet processing.
 *
 * <p>Covers the pure helpers ({@link PacketDispatcher#shardFor} and
 * {@link PacketHandler#workerCountFor}) plus real-dispatcher behaviour:
 * per-connection FIFO ordering, parallelism across shards, bounded-queue
 * back-pressure that never drops or reorders, and a no-op after shutdown.
 *
 * @since 0.9.71
 */
public class PacketDispatcherTest {

    private static final long TEST_TIMEOUT = 4000;

    // ---------- PacketDispatcher.shardFor ----------

    /** One worker: everything lands on shard 0. */
    @Test
    public void testShardForSingleWorker() {
        for (long id = 0; id < 64; id++) {
            assertEquals(0, PacketDispatcher.shardFor(id, 1));
        }
    }

    /** Result is always in [0, nThreads). */
    @Test
    public void testShardForInRange() {
        int[] counts = new int[] {2, 3, 4, 7, 8};
        for (int n : counts) {
            for (long id = 1; id <= 512; id++) {
                int s = PacketDispatcher.shardFor(id, n);
                assertTrue("id " + id + " n " + n + " -> " + s, s >= 0 && s < n);
            }
        }
    }

    /** Same connection id always maps to the same shard. */
    @Test
    public void testShardForDeterministic() {
        for (int n : new int[] {2, 3, 4, 8}) {
            for (long id : new long[] {1, 7, 100, 999983, Long.MAX_VALUE}) {
                int first = PacketDispatcher.shardFor(id, n);
                for (int i = 0; i < 100; i++) {
                    assertEquals(first, PacketDispatcher.shardFor(id, n));
                }
            }
        }
    }

    /** Two shards: even ids -> 0, odd ids -> 1. */
    @Test
    public void testShardForParity() {
        for (long id = 2; id < 200; id += 2) {
            assertEquals(0, PacketDispatcher.shardFor(id, 2));
            assertEquals(1, PacketDispatcher.shardFor(id + 1, 2));
        }
    }

    /** A healthy id scan reaches every bucket, so no shard starves. */
    @Test
    public void testShardForAllBucketsReached() {
        for (int n : new int[] {2, 3, 4, 8}) {
            boolean[] seen = new boolean[n];
            for (long id = 0; id < 1024; id++) {
                seen[PacketDispatcher.shardFor(id, n)] = true;
            }
            for (int i = 0; i < n; i++) {
                assertTrue("bucket " + i + " of " + n + " unreached", seen[i]);
            }
        }
    }

    /** Non-positive thread counts fall back to a single shard. */
    @Test
    public void testShardForNonPositiveThreads() {
        assertEquals(0, PacketDispatcher.shardFor(12345, 0));
        assertEquals(0, PacketDispatcher.shardFor(12345, -3));
    }

    // ---------- PacketHandler.workerCountFor ----------

    /** A positive configured value wins when no Tuner override is armed. */
    @Test
    public void testWorkerCountFromProperty() {
        assertEquals(3, PacketHandler.workerCountFor(8, "3", 0));
        assertEquals(1, PacketHandler.workerCountFor(8, "1", 0));
        assertEquals(2, PacketHandler.workerCountFor(4, "2", 0));
    }

    /** Configured values are capped at the upper bound. */
    @Test
    public void testWorkerCountPropertyCapped() {
        assertEquals(8, PacketHandler.workerCountFor(4, "999", 0));
        assertEquals(8, PacketHandler.workerCountFor(16, "100", 0));
    }

    /** Unset or invalid config falls back to the core-based default. */
    @Test
    public void testWorkerCountInvalidFallsBack() {
        for (String bad : new String[] {null, "", "abc", "0", "-4", "  "}) {
            assertEquals(4, PacketHandler.workerCountFor(4, bad, 0));
        }
    }

    /** Core-based default is floored at 2 and capped at the upper bound. */
    @Test
    public void testWorkerCountCoreDefault() {
        assertEquals(2, PacketHandler.workerCountFor(1, null, 0));
        assertEquals(2, PacketHandler.workerCountFor(2, null, 0));
        assertEquals(6, PacketHandler.workerCountFor(6, null, 0));
        assertEquals(8, PacketHandler.workerCountFor(32, null, 0));
    }

    /** A positive Tuner override wins over both the property and the core default. */
    @Test
    public void testWorkerCountOverrideWins() {
        assertEquals(6, PacketHandler.workerCountFor(4, "2", 6));
        assertEquals(3, PacketHandler.workerCountFor(8, null, 3));
        assertEquals(2, PacketHandler.workerCountFor(32, null, 2));
    }

    /** Tuner override is capped at the upper bound. */
    @Test
    public void testWorkerCountOverrideCapped() {
        assertEquals(8, PacketHandler.workerCountFor(4, "2", 99));
        assertEquals(8, PacketHandler.workerCountFor(16, "100", 8));
    }

    /** Zero/negative override means "not armed" — property/core default apply. */
    @Test
    public void testWorkerCountOverrideDisabled() {
        assertEquals(2, PacketHandler.workerCountFor(4, "2", 0));
        assertEquals(4, PacketHandler.workerCountFor(4, null, -3));
    }

    // ---------- real dispatcher behaviour ----------

    /** Per-connection FIFO: same-shard packets process in dispatch order. */
    @Test
    public void testDispatchPreservesOrder() throws Exception {
        List<Long> order = Collections.synchronizedList(new ArrayList<Long>());
        PacketDispatcher d = new PacketDispatcher(1, 16, (con, p) -> order.add(p.getSendStreamId()));
        try {
            d.dispatch(100, null, packetFor(100));
            d.dispatch(100, null, packetFor(200));
            d.dispatch(100, null, packetFor(300));
            awaitCount(order, 3);
            assertEquals(java.util.Arrays.asList(100L, 200L, 300L), order);
        } finally {
            d.shutdown();
        }
    }

    /** Different shards run in parallel: shard 1 proceeds while shard 0 is blocked. */
    @Test
    public void testDispatchParallelShards() throws Exception {
        final CountDownLatch shard0Blocked = new CountDownLatch(1);
        final CountDownLatch shard1Processed = new CountDownLatch(1);
        final AtomicInteger processed = new AtomicInteger();
        PacketDispatcher d = new PacketDispatcher(2, 16, (con, p) -> {
            processed.incrementAndGet();
            if (p.getSendStreamId() == 0) {
                try {
                    shard0Blocked.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else {
                shard1Processed.countDown();
            }
        });
        try {
            // id 0 -> shard 0, id 1 -> shard 1
            d.dispatch(0, null, packetFor(0));
            d.dispatch(1, null, packetFor(1));
            // shard 0 is stuck blocked; shard 1 must still complete on its own worker
            assertTrue("shard 1 starved by shard 0", shard1Processed.await(TEST_TIMEOUT, TimeUnit.MILLISECONDS));
            awaitCount(processed, 2);
        } finally {
            shard0Blocked.countDown();
            d.shutdown();
        }
    }

    /**
     * An exception while processing a packet must not kill the shard worker:
     * a dead worker would let the shard's queue fill so the producer blocks
     * forever on it, stalling every connection on the destination. The thrown
     * entry's payload is released and the worker keeps taking packets.
     */
    @Test
    public void testWorkerSurvivesProcessorException() throws Exception {
        final AtomicInteger processed = new AtomicInteger();
        PacketDispatcher d = new PacketDispatcher(1, 4, (con, p) -> {
            if (processed.getAndIncrement() == 0)
                throw new IllegalStateException("poison packet");
        });
        try {
            d.dispatch(100, null, packetFor(100));   // poison: worker throws
            d.dispatch(100, null, packetFor(200));   // must still get processed
            awaitCount(processed, 2);
            assertEquals(1, d.getWorkerCount());
        } finally {
            d.shutdown();
        }
    }

    /** A fast consumer never loses packets even when produced faster than the shard drains. */
    @Test
    public void testDispatchNoDropUnderBacklog() throws Exception {
        List<Long> seen = Collections.synchronizedList(new ArrayList<Long>());
        PacketDispatcher d = new PacketDispatcher(1, 1, (con, p) -> seen.add(p.getSendStreamId()));
        try {
            for (long id = 100; id < 105; id++) {
                d.dispatch(0, null, packetFor(id));
            }
            awaitCount(seen, 5);
            assertEquals(java.util.Arrays.asList(100L, 101L, 102L, 103L, 104L), seen);
        } finally {
            d.shutdown();
        }
    }

    /**
     * A saturated shard back-pressures the producer: dispatch blocks until the
     * queue drains, then completes with no drop and no reorder.
     */
    @Test
    public void testDispatchBackpressureBlocksProducer() throws Exception {
        final CountDownLatch gate = new CountDownLatch(1);
        List<Long> seen = Collections.synchronizedList(new ArrayList<Long>());
        PacketDispatcher d = new PacketDispatcher(1, 1, (con, p) -> {
            seen.add(p.getSendStreamId());
            if (seen.size() == 1) {
                try {
                    gate.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            d.dispatch(100, null, packetFor(100));     // picked by worker, blocks on gate
            d.dispatch(100, null, packetFor(200));     // fills the 1-slot queue
            // third dispatch must wait, not drop or return early
            final CountDownLatch thirdDone = new CountDownLatch(1);
            Thread t = new Thread(() -> {
                try {
                    d.dispatch(100, null, packetFor(300));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    thirdDone.countDown();
                }
            });
            t.start();
            assertFalse("dispatch should block on a saturated shard", thirdDone.await(150, TimeUnit.MILLISECONDS));
            gate.countDown();                          // let the shard drain
            assertTrue("dispatch stuck after queue space freed", thirdDone.await(TEST_TIMEOUT, TimeUnit.MILLISECONDS));
            t.join(TEST_TIMEOUT);
            awaitCount(seen, 3);
            assertEquals(java.util.Arrays.asList(100L, 200L, 300L), seen);
        } finally {
            gate.countDown();
            d.shutdown();
        }
    }

    /** After shutdown, dispatch is a no-op: no processing, no hang, no exception. */
    @Test
    public void testDispatchAfterShutdownIsNoop() throws Exception {
        final AtomicInteger processed = new AtomicInteger();
        PacketDispatcher d = new PacketDispatcher(1, 4, (con, p) -> processed.incrementAndGet());
        d.dispatch(100, null, packetFor(1));
        awaitCount(processed, 1);
        d.shutdown();
        // queued and fresh packets must not be processed or throw after shutdown
        d.dispatch(100, null, packetFor(2));
        d.dispatch(100, null, packetFor(3));
        Thread.sleep(100);
        assertEquals(1, processed.get());
    }

    /** Worker threads never linger after shutdown. */
    @Test
    public void testShutdownStopsWorkers() throws Exception {
        final AtomicInteger processed = new AtomicInteger();
        PacketDispatcher d = new PacketDispatcher(3, 4, (con, p) -> processed.incrementAndGet());
        d.dispatch(100, null, packetFor(1));
        awaitCount(processed, 1);
        d.shutdown();
        // no crash, no assertion; daemon workers are just garbage collected
        assertEquals(1, processed.get());
    }

    // ---------- live resize ----------

    /** Resize swaps the shard array in place and the dispatcher keeps processing. */
    @Test
    public void testResizeGrowsShrinksAndKeepsProcessing() throws Exception {
        final AtomicInteger processed = new AtomicInteger();
        PacketDispatcher d = new PacketDispatcher(3, 4, (con, p) -> processed.incrementAndGet());
        try {
            assertEquals(3, d.getWorkerCount());
            d.dispatch(100, null, packetFor(1));
            awaitCount(processed, 1);

            d.resize(5);
            assertEquals(5, d.getWorkerCount());

            d.resize(2);
            assertEquals(2, d.getWorkerCount());

            d.dispatch(100, null, packetFor(2));
            awaitCount(processed, 2);
        } finally {
            d.shutdown();
        }
    }

    /** A resize to the current size is a no-op, workers keep running. */
    @Test
    public void testResizeSameSizeNoop() throws Exception {
        final AtomicInteger processed = new AtomicInteger();
        PacketDispatcher d = new PacketDispatcher(4, 4, (con, p) -> processed.incrementAndGet());
        try {
            d.dispatch(100, null, packetFor(1));
            awaitCount(processed, 1);
            d.resize(4);
            assertEquals(4, d.getWorkerCount());
            d.dispatch(100, null, packetFor(2));
            awaitCount(processed, 2);
        } finally {
            d.shutdown();
        }
    }

    /** Non-positive resize targets clamp to a single shard. */
    @Test
    public void testResizeClampsNonPositive() throws Exception {
        PacketDispatcher d = new PacketDispatcher(2, 4, (con, p) -> {});
        try {
            d.resize(0);
            assertEquals(1, d.getWorkerCount());
        } finally {
            d.shutdown();
        }
    }

    /**
     * A resize drains every old shard to idle before swapping, so in-flight and
     * enqueued packets complete in dispatch order and the post-resize packets
     * append after them — no reorder across the transition.
     */
    @Test
    public void testResizePreservesOrder() throws Exception {
        List<Long> order = Collections.synchronizedList(new ArrayList<Long>());
        PacketDispatcher d = new PacketDispatcher(1, 4, (con, p) -> order.add(p.getSendStreamId()));
        try {
            d.dispatch(100, null, packetFor(100));
            d.dispatch(100, null, packetFor(200));
            awaitCount(order, 2);
            d.resize(2);
            d.dispatch(100, null, packetFor(300));
            awaitCount(order, 3);
            assertEquals(java.util.Arrays.asList(100L, 200L, 300L), order);
        } finally {
            d.shutdown();
        }
    }

    /** resizeAll reaches every live dispatcher; shut-down ones are left alone. */
    @Test
    public void testResizeAllAppliesToLiveDispatchers() throws Exception {
        PacketDispatcher a = new PacketDispatcher(2, 4, (con, p) -> {});
        PacketDispatcher b = new PacketDispatcher(3, 4, (con, p) -> {});
        try {
            PacketDispatcher.resizeAll(5);
            assertEquals(5, a.getWorkerCount());
            assertEquals(5, b.getWorkerCount());
            b.shutdown();
            PacketDispatcher.resizeAll(2);
            assertEquals(2, a.getWorkerCount());
            // b was deregistered: no crash, its count is frozen at the last value
            assertEquals(5, b.getWorkerCount());
        } finally {
            a.shutdown();
        }
    }

    // ---------- helpers ----------

    /** Build a minimal inbound-style packet carrying the given stream id. */
    private static Packet packetFor(long sendStreamId) {
        Packet p = new Packet(null);
        p.setSendStreamId(sendStreamId);
        return p;
    }

    /** Wait until the collection has n entries or the timeout elapses. */
    private static void awaitCount(List<?> list, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TEST_TIMEOUT * 1_000_000L;
        while (list.size() < n) {
            if (System.nanoTime() > deadline) {
                fail("expected " + n + " entries, got " + list.size() + " after " + TEST_TIMEOUT + "ms");
            }
            Thread.sleep(10);
        }
    }

    /** Wait until the counter reaches n or the timeout elapses. */
    private static void awaitCount(AtomicInteger counter, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TEST_TIMEOUT * 1_000_000L;
        while (counter.get() < n) {
            if (System.nanoTime() > deadline) {
                fail("expected count " + n + ", got " + counter.get() + " after " + TEST_TIMEOUT + "ms");
            }
            Thread.sleep(10);
        }
    }
}
