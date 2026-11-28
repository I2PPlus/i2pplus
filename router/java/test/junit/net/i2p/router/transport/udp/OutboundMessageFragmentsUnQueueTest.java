package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * The one window where {@code PeerPool} had two views that could disagree: a
 * peer un-queueing itself while a batched removal was part way through applying
 * itself.
 *
 * <p>{@code flushRemovals()} reads the queued set twice - once to decide there
 * is work to do, once to apply it - and applies it in two steps under one
 * monitor: first to the ordered pool the pusher walks, then to the membership
 * set. The un-queue in {@code add()} sat before that monitor, so it could cancel
 * the queued removal after the first step had already dropped the peer and
 * before the second step read the set. The second step then no longer saw the
 * peer, so it stayed in the membership set while the ordered pool had lost it.
 * {@code add()} consults that set, so from then on every message for the peer is
 * answered "already in the pool" and nothing puts it back in the ordered one -
 * the peer is never pushed to again.
 *
 * <p>That window is a few instructions wide and nothing in it calls into the
 * pooled objects, so a contending-threads test does not reliably hit it:
 * measured, a 2.5 s two-thread stress run passed against the unfixed pool, and
 * so did a test that only watched whether {@code add()} blocked, because the
 * unfixed {@code add()} still takes the monitor for its join. These tests stop
 * the flush in that window through its documented seam instead, and let the
 * un-queue try to land there.
 *
 * @since 0.9.72+
 */
public class OutboundMessageFragmentsUnQueueTest {

    /** How long a test waits for the flush to reach, or leave, its window, in ms. */
    private static final int ARRIVE_MS = 2000;

    /**
     * Test case carrying the interleaving cases. None needs a router context -
     * the pool is a stand-alone class.
     */
    public OutboundMessageFragmentsUnQueueTest() {
    }

    /**
     * An un-queue that starts while a flush is between its two steps must not be
     * applied in that gap: it has to wait for the monitor the flush holds.
     *
     * <p>When the un-queue sat outside that monitor it cancelled the queued
     * removal mid-flush, the flush's second step no longer saw the peer, and the
     * peer was left in the membership set but out of the ordered pool - the state
     * in which every later {@code add()} answers "already in the pool" and the
     * peer is never pushed to again.
     *
     * @throws InterruptedException if this thread is interrupted while it starts,
     *                              joins or paces the two pool threads
     */
    @Test
    public void unQueueCannotLandBetweenTheFlushSteps() throws InterruptedException {
        final Object peer = new Object();
        final OutboundMessageFragments.PeerPool<Object> pool =
                new OutboundMessageFragments.PeerPool<Object>();
        pool.add(peer);
        pool.queueForRemoval(peer);

        // The flush parks here once it has dropped the peer from the ordered pool
        // and is about to apply the same set to the membership set.
        final CountDownLatch betweenSteps = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        OutboundMessageFragments.PeerPool._betweenFlushStepsForTest = new Runnable() {
            public void run() {
                betweenSteps.countDown();
                try {
                    release.await(ARRIVE_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try {
            Thread flusher = new Thread(new Runnable() {
                public void run() {
                    pool.flushRemovals();
                }
            }, "pool-flusher");
            flusher.start();
            assertTrue("the flush never reached the window between its two steps",
                       betweenSteps.await(ARRIVE_MS, TimeUnit.MILLISECONDS));

            final boolean[] unQueueDone = new boolean[1];
            Thread unQueue = new Thread(new Runnable() {
                public void run() {
                    pool.add(peer);
                    unQueueDone[0] = true;
                }
            }, "pool-unqueue");
            unQueue.start();
            for (int i = 0; i < 12 && !unQueueDone[0]; i++) {
                Thread.sleep(25);
            }
            release.countDown();
            flusher.join(ARRIVE_MS);
            unQueue.join(ARRIVE_MS);
            assertFalse("a pool thread never finished", flusher.isAlive() || unQueue.isAlive());

            // Both views must still agree. An un-queue applied inside the window
            // leaves the peer in the membership set with no slot in the ordered
            // pool the pusher walks - the stranded state this fixes.
            assertEquals("the ordered pool and the membership set disagree on the peer count",
                         pool.size(), pool.peers().size());
            assertEquals("the two views hold different peers -> the peer is stranded in the " +
                         "membership set and will never be pushed to again",
                         pool.peers().contains(peer), pool.contains(peer));
        } finally {
            OutboundMessageFragments.PeerPool._betweenFlushStepsForTest = null;
        }
    }

    /**
     * A peer that gets work after being drained survives the next batched
     * removal, so the fix is not simply a flush that stopped removing anything.
     */
    @Test
    public void reactivatedPeerSurvivesTheNextFlush() {
        Object peer = new Object();
        OutboundMessageFragments.PeerPool<Object> pool =
                new OutboundMessageFragments.PeerPool<Object>();
        pool.add(peer);
        pool.queueForRemoval(peer);
        pool.add(peer);
        pool.flushRemovals();
        assertTrue("a peer with fresh work was flushed out of the ordered pool",
                   pool.peers().contains(peer));
        assertTrue("the membership set dropped a peer the pool still holds", pool.contains(peer));
        assertEquals("the two views disagree on how many peers are pooled", 1, pool.size());
        assertEquals("the queued removal survived the un-queue", 0, pool.pendingRemovals());
    }

    /**
     * A removal the pusher queued and never un-queued is still applied to both
     * views by the next flush.
     */
    @Test
    public void queuedRemovalIsAppliedToBothViews() {
        Object peer = new Object();
        OutboundMessageFragments.PeerPool<Object> pool =
                new OutboundMessageFragments.PeerPool<Object>();
        pool.add(peer);
        pool.queueForRemoval(peer);
        assertEquals(1, pool.pendingRemovals());
        pool.flushRemovals();
        assertFalse("the flush left the peer in the ordered pool", pool.peers().contains(peer));
        assertFalse("the flush left the peer in the membership set", pool.contains(peer));
        assertEquals(0, pool.size());
        assertEquals(0, pool.pendingRemovals());
        assertTrue("an empty flush must be a no-op", pool.isEmpty());
    }
}
