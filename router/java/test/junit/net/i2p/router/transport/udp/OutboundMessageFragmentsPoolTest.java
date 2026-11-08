package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Tests for {@link OutboundMessageFragments.PeerPool}, the bookkeeping that
 * replaced the two CopyOnWriteArrayLists whose membership scan and array copy
 * ran once per outbound message.
 *
 * <p>The pool is generic because it only ever stores, finds and drops its
 * peers by identity - it never calls into them. Plain {@code Object} tokens
 * therefore exercise exactly what {@code PeerPool<PeerState>} does in
 * production, without dragging a real peer (and the transport behind it)
 * into the unit, and without the flakiness of timing a real pusher.
 *
 * <p>What is pinned here: the per-message path's idempotence; the race the
 * old {@code _peersToRemove.remove(peer)} line existed for, namely that a
 * message arriving after the pusher queued the peer for removal must keep
 * the peer active; every drain path emptying all three structures so no
 * peer reference leaks; multi-thread add / queue / flush / drop / clear
 * hammering with no exception and the ordered view and the membership set
 * left in lockstep; and the round-robin order the pusher walks, including
 * that a batched removal keeps the survivors' relative order.
 */
public class OutboundMessageFragmentsPoolTest {

    /** Peers per fixture, comfortably above the "tens" the production pool holds. */
    private static final int PEERS = 5;

    /** Iterations per hammer thread - large enough to interleave, small enough to stay quick. */
    private static final int HAMMER_ITERATIONS = 20_000;

    /**
     * Peers in the churn test - enough that the pusher's batch size of 32
     * makes the flush run repeatedly while the adds are still arriving.
     */
    private static final int CHURN_PEERS = 64;

    /**
     * Fresh distinct peer tokens. Object identity is the only relation the
     * pool observes, matching the PeerState the production pool holds.
     *
     * @return an array of distinct fresh objects
     */
    private static Object[] freshPeers() {
        Object[] peers = new Object[PEERS];
        for (int i = 0; i < PEERS; i++) {
            peers[i] = new Object();
        }
        return peers;
    }

    /**
     * Walk the pool the way {@code getNextVolley} does - index up, reset to
     * zero at the size - and return the peers served over {@code count}
     * stops.
     *
     * @param pool the pool to walk
     * @param count how many peers to serve
     * @return the served order
     */
    private static List<Object> serve(OutboundMessageFragments.PeerPool<Object> pool, int count) {
        List<Object> served = new ArrayList<>(count);
        int index = 0;
        for (int i = 0; i < count; i++) {
            if (index >= pool.size()) {
                index = 0;
            }
            served.add(pool.get(index++));
        }
        return served;
    }

    /**
     * Prove the ordered view and the membership set agree, and the ordered
     * view has no duplicates - the invariant the monitor is there to keep
     * under concurrent add / flush / drop.
     *
     * @param pool the quiesced pool to check
     * @param peers every peer the hammer touched
     */
    private static void assertViewsInLockstep(OutboundMessageFragments.PeerPool<Object> pool, Object[] peers) {
        List<Object> listed = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            listed.add(pool.get(i));
        }
        Set<Object> distinct = new HashSet<>(listed);
        assertEquals("ordered view holds a peer twice", listed.size(), distinct.size());
        for (Object peer : peers) {
            if (listed.contains(peer)) {
                assertTrue("in the ordered view but not in the membership set", pool.contains(peer));
            } else {
                assertFalse("in the membership set but not in the ordered view", pool.contains(peer));
            }
        }
    }

    /**
     * Adding the same peer twice is one pool entry, and the second add -
     * the common case, one message after another to a busy peer - reports
     * "already there" without touching the ordered view.
     */
    @Test
    public void testAddingTheSamePeerTwiceIsOneEntry() {
        OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        Object peer = new Object();
        assertTrue("first add must report a new peer", pool.add(peer));
        assertFalse("second add must report the peer as already active", pool.add(peer));
        assertEquals(1, pool.size());
        assertSame(peer, pool.get(0));
        assertTrue(pool.contains(peer));
        assertViewsInLockstep(pool, new Object[] {peer});
    }

    /**
     * Dropping an absent peer is not an error, and dropping twice leaves the
     * pool - and the membership set - as empty as a fresh one.
     */
    @Test
    public void testDroppingTwiceIsIdempotent() {
        OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        Object peer = new Object();
        pool.add(peer);
        pool.remove(peer);
        pool.remove(peer);
        pool.remove(new Object());
        assertTrue(pool.isEmpty());
        assertEquals(0, pool.size());
        assertFalse(pool.contains(peer));
    }

    /**
     * The race the old {@code _peersToRemove.remove(peer)} line existed for:
     * the pusher drains the peer and queues it for removal, a new message
     * lands before the flush, and the flush must then leave the peer
     * active.
     */
    @Test
    public void testNewMessageCancelsAQueuedRemoval() {
        Object peer = new Object();
        OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        pool.add(peer);
        pool.queueForRemoval(peer);
        assertTrue("the flush must be about to see this peer", pool.isQueuedForRemoval(peer));
        assertFalse("a peer still in the pool is not 'new' to add()", pool.add(peer));
        assertFalse("the add must have un-queued the removal", pool.isQueuedForRemoval(peer));
        pool.flushRemovals();
        assertEquals("a reactivated peer must survive the flush", 1, pool.size());
        assertSame(peer, pool.get(0));
        assertTrue(pool.contains(peer));
    }

    /**
     * If the flush wins the race and the peer is gone, the next message
     * still rejoins and reports as new, so the pusher gets nudged.
     */
    @Test
    public void testFlushThenNewMessageRejoinsThePool() {
        Object peer = new Object();
        OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        pool.add(peer);
        pool.queueForRemoval(peer);
        pool.flushRemovals();
        assertEquals(0, pool.size());
        assertFalse(pool.contains(peer));
        assertTrue("after the flush removed it, the next add must report it new", pool.add(peer));
        assertEquals(1, pool.size());
        assertSame(peer, pool.get(0));
    }

    /**
     * The batched flush is the only place the ordered array is copied for
     * removals, and it must leave nothing behind in any of the three
     * structures: queued peers gone from the pool, from the membership set,
     * and from the removal queue.
     */
    @Test
    public void testFlushDrainsAllThreeStructures() {
        Object[] peers = freshPeers();
        OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        for (Object peer : peers) {
            pool.add(peer);
        }
        pool.queueForRemoval(peers[1]);
        pool.queueForRemoval(peers[1]);
        pool.queueForRemoval(peers[3]);
        assertEquals("the queue dedupes", 2, pool.pendingRemovals());
        pool.flushRemovals();
        assertEquals(PEERS - 2, pool.size());
        assertEquals(PEERS - 2, pool.peers().size());
        assertFalse(pool.contains(peers[1]));
        assertFalse(pool.contains(peers[3]));
        assertFalse(pool.isQueuedForRemoval(peers[1]));
        assertFalse(pool.isQueuedForRemoval(peers[3]));
        assertEquals(0, pool.pendingRemovals());
        assertTrue(pool.contains(peers[0]));
        assertTrue(pool.contains(peers[2]));
        assertTrue(pool.contains(peers[4]));
        assertViewsInLockstep(pool, peers);
    }

    /**
     * The shutdown path must leak no peer reference at all: after clear(),
     * the ordered view, the membership set and the removal queue are all
     * empty, even for a peer queued for removal but never flushed.
     */
    @Test
    public void testClearDrainsEverything() {
        Object[] peers = freshPeers();
        OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        for (Object peer : peers) {
            pool.add(peer);
        }
        pool.queueForRemoval(peers[0]);
        pool.queueForRemoval(peers[2]);
        pool.clear();
        assertTrue(pool.isEmpty());
        assertEquals(0, pool.size());
        assertTrue(pool.peers().isEmpty());
        assertEquals(0, pool.pendingRemovals());
        for (Object peer : peers) {
            assertFalse("membership set kept " + pool.contains(peer), pool.contains(peer));
            assertFalse("removal queue kept a peer", pool.isQueuedForRemoval(peer));
        }
        assertViewsInLockstep(pool, peers);
    }

    /** Flushing an empty pool changes nothing and throws nothing. */
    @Test
    public void testFlushOnAnEmptyPoolIsANoOp() {
        Object[] peers = freshPeers();
        OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        for (Object peer : peers) {
            pool.add(peer);
        }
        pool.flushRemovals();
        pool.clear();
        pool.flushRemovals();
        assertTrue(pool.isEmpty());
        assertEquals(0, pool.pendingRemovals());
    }

    /**
     * The pusher's round-robin order is the insertion order of the peers,
     * and a batched removal must not disturb the relative order of the
     * survivors - the same behaviour the old CopyOnWriteArrayList gave the
     * index-based walk in {@code getNextVolley}.
     */
    @Test
    public void testRoundRobinServesInsertionOrder() {
        Object[] peers = freshPeers();
        OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        for (Object peer : peers) {
            pool.add(peer);
        }
        List<Object> served = serve(pool, PEERS * 2);
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < PEERS; i++) {
                assertSame("round " + round + " out of order at position " + i,
                           peers[i], served.get(round * PEERS + i));
            }
        }
        pool.queueForRemoval(peers[2]);
        pool.flushRemovals();
        Object[] survivors = {peers[0], peers[1], peers[3], peers[4]};
        List<Object> servedAgain = serve(pool, survivors.length * 2);
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < survivors.length; i++) {
                assertSame("survivor round " + round + " out of order at position " + i,
                           survivors[i], servedAgain.get(round * survivors.length + i));
            }
        }
    }

    /**
     * {@code getNextVolley} restarts its index from zero by catching the
     * exception a concurrent drop leaves it with; the pool must keep
     * throwing it when the index walks past a shrunken pool.
     */
    @Test
    public void testGetPastTheEndStillThrows() {
        OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        Object peer = new Object();
        pool.add(peer);
        pool.remove(peer);
        try {
            pool.get(0);
            fail("expected IndexOutOfBoundsException once the pool shrank");
        } catch (IndexOutOfBoundsException e) {
            // expected - the pusher resets its index on this
        }
    }

    /**
     * Several threads adding, queueing, flushing, dropping and walking at
     * once: no exception anywhere, no CME on a walk and no failure inside
     * the pool, and once they all stop the ordered view and the membership
     * set agree and no peer is held twice.
     *
     * @throws InterruptedException if this thread is interrupted while it
     *                              joins the workers
     */
    @Test
    public void testConcurrentAddQueueFlushAndDropStayConsistent() throws InterruptedException {
        final Object[] peers = freshPeers();
        final OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();

        // Replays the shapes of the real callers: message senders calling
        // add(), the pusher queueing and flushing, and dropPeer().
        Runnable adder = () -> hammer(failure, start, i -> pool.add(peers[i % PEERS]));
        Runnable flusher = () -> hammer(failure, start, i -> {
            Object peer = peers[i % PEERS];
            pool.queueForRemoval(peer);
            if (pool.pendingRemovals() >= 32) {
                pool.flushRemovals();
            }
        });
        Runnable dropper = () -> hammer(failure, start, i -> pool.remove(peers[i % PEERS]));
        for (int i = 0; i < 3; i++) {
            threads.add(new Thread(adder, "pool-adder-" + i));
            threads.add(new Thread(flusher, "pool-flusher-" + i));
        }
        threads.add(new Thread(dropper, "pool-dropper"));

        // A pusher-style walk: index resets at the size, IndexOutOfBoundsException
        // from a concurrent drop is the contract, and an iteration must never
        // see a ConcurrentModificationException.
        Thread walker = new Thread(() -> {
            try {
                start.await();
                int index = 0;
                for (int i = 0; i < HAMMER_ITERATIONS; i++) {
                    if (index >= pool.size()) {
                        index = 0;
                    }
                    try {
                        pool.get(index++);
                    } catch (IndexOutOfBoundsException e) {
                        index = 0;
                    }
                    for (Object peer : pool.peers()) {
                        if (peer == null) {
                            throw new IllegalStateException("null peer in the ordered view");
                        }
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "pool-walker");
        threads.add(walker);

        for (Thread t : threads) {
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        Throwable t = failure.get();
        if (t != null) {
            throw new AssertionError("hammer thread failed", t);
        }
        assertViewsInLockstep(pool, peers);
    }

    /**
     * Shutdown racing a burst of adds must still be able to drain
     * everything: whatever the interleaving, a clear() after the writers
     * stop leaves no peer reference in the ordered view, the membership
     * set or the removal queue.
     *
     * @throws InterruptedException if this thread is interrupted while it
     *                              joins the writers
     */
    @Test
    public void testClearAfterConcurrentAddsLeaksNothing() throws InterruptedException {
        final Object[] peers = freshPeers();
        final OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            threads.add(new Thread(() -> hammer(failure, start, j -> {
                pool.add(peers[j % PEERS]);
                if (j % 4 == 0) {
                    pool.queueForRemoval(peers[j % PEERS]);
                }
                if (j % 16 == 0) {
                    pool.flushRemovals();
                }
            }), "racy-adder-" + i));
        }
        threads.add(new Thread(() -> hammer(failure, start, j -> {
            pool.clear();
            pool.add(peers[j % PEERS]);
        }), "racy-clearer"));
        for (Thread t : threads) {
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        Throwable t = failure.get();
        if (t != null) {
            throw new AssertionError("racy shutdown thread failed", t);
        }
        pool.clear();
        assertViewsInLockstep(pool, peers);
        assertTrue(pool.isEmpty());
        assertEquals(0, pool.size());
        assertEquals(0, pool.pendingRemovals());
        for (Object peer : peers) {
            assertFalse(pool.contains(peer));
            assertFalse(pool.isQueuedForRemoval(peer));
        }
    }

    /**
     * Adds and batched flushes from four threads, then checks the two views
     * still agree. The membership test and the append of the ordered view
     * happen in one monitor-protected step, so a flush running on the pusher
     * thread can never leave a peer in the set but not in the walk the pusher
     * makes - which is what would strand a message the caller queued behind
     * the answer "already in the pool".
     *
     * @throws InterruptedException if this thread is interrupted while it
     *                              joins the writers
     */
    @Test
    public void testConcurrentAddsAndFlushesKeepBothViewsInLockstep() throws InterruptedException {
        final Object[] peers = new Object[CHURN_PEERS];
        for (int i = 0; i < CHURN_PEERS; i++) {
            peers[i] = new Object();
        }
        final OutboundMessageFragments.PeerPool<Object> pool = new OutboundMessageFragments.PeerPool<>();
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            threads.add(new Thread(() -> hammer(failure, start, j -> pool.add(peers[j % CHURN_PEERS])),
                                    "reach-adder-" + i));
        }
        // A pusher round that drains every peer, so the batched flush runs
        // constantly against the adds.
        for (int i = 0; i < 2; i++) {
            threads.add(new Thread(() -> hammer(failure, start, j -> {
                pool.queueForRemoval(peers[j % CHURN_PEERS]);
                if (pool.pendingRemovals() >= 32) {
                    pool.flushRemovals();
                }
            }), "reach-flusher-" + i));
        }
        for (Thread t : threads) {
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        Throwable t = failure.get();
        if (t != null) {
            throw new AssertionError("churn thread failed", t);
        }

        // Quiesced: put every peer back in the pool, then nothing may be
        // missing from either view. A peer stranded in the set but not in
        // the ordered view reports "already there" and is never appended,
        // so the count comes up short.
        for (Object peer : peers) {
            pool.add(peer);
        }
        assertEquals("peers the pool counted but the pusher could not reach",
                     CHURN_PEERS, pool.size());
        assertEquals(CHURN_PEERS, pool.peers().size());
        for (Object peer : peers) {
            assertTrue("peer missing from the ordered view", pool.peers().contains(peer));
        }
        assertViewsInLockstep(pool, peers);
    }

    /**
     * Body of one hammer thread: wait for the start latch, run the op the
     * required number of times, and record the first throwable instead of
     * losing it in an unobserved thread.
     *
     * @param failure where the first throwable from any thread lands
     * @param start released when every thread is up
     * @param op the per-iteration action, called with the iteration number
     */
    private static void hammer(AtomicReference<Throwable> failure, CountDownLatch start,
                               java.util.function.IntConsumer op) {
        try {
            start.await();
            for (int i = 0; i < HAMMER_ITERATIONS; i++) {
                op.accept(i);
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }
}
