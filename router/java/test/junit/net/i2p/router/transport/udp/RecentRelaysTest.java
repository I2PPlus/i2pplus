package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for <code>IntroductionManager.RecentRelays</code>, the anti-replay
 * window that decides whether a relay request or response has been handled
 * already. It is bookkeeping, not a cache, so the two properties that matter are
 * pinned here: a nonce we recorded is reported as a repeat, and the window keeps
 * a fixed number of nonces no matter how many introductions arrive.
 *
 * <p>The window replaced a 64-entry LRU, so the count it remembers is the
 * protocol-visible part: too small and a retransmit that used to be dropped gets
 * relayed to Charlie a second time, too large and memory grows with the
 * introduction rate.
 *
 * @since 0.9.72+
 */
public class RecentRelaysTest {

    /** Nonces the window keeps, the value the assertions are written against. */
    private static final int CAPACITY = 64;

    /**
     * A fixture group for the relay window; it holds no state.
     */
    public RecentRelaysTest() {}

    /**
     * A relay request we have already handled is dropped the second time, and
     * every time after that, which is the entire job of the window: i2pd
     * retransmits relay requests, and a retransmit relayed twice introduces
     * Alice to Charlie twice.
     */
    @Test
    public void testRepeatedNonceIsRejected() {
        IntroductionManager.RecentRelays relays = new IntroductionManager.RecentRelays();
        long nonce = 0x12345678L;
        assertFalse("first sight is not a duplicate", relays.testAndSet(nonce));
        assertTrue("a repeat is a duplicate", relays.testAndSet(nonce));
        assertTrue("and stays one", relays.testAndSet(nonce));
        assertTrue(relays.contains(nonce));
    }

    /**
     * The response path asks the question without recording an answer, so a
     * nonce the request path recorded is visible to it, and asking never makes a
     * later duplicate test answer differently.
     */
    @Test
    public void testContainsIsReadOnly() {
        IntroductionManager.RecentRelays relays = new IntroductionManager.RecentRelays();
        long nonce = 0xdeadbeeL;
        assertFalse(relays.contains(nonce));
        assertFalse(relays.testAndSet(nonce));
        assertTrue(relays.contains(nonce));
        assertTrue(relays.contains(nonce));
        assertTrue(relays.testAndSet(nonce));
    }

    /**
     * Nonce 0 is a legitimate relay nonce - it comes out of a 4 byte unsigned
     * field - so it must not be swallowed by whatever marks a slot unused.
     */
    @Test
    public void testZeroNonceIsRemembered() {
        IntroductionManager.RecentRelays relays = new IntroductionManager.RecentRelays();
        assertFalse(relays.testAndSet(0));
        assertTrue(relays.contains(0));
        assertTrue(relays.testAndSet(0));
    }

    /**
     * A nonce never recorded is never called a duplicate, whatever else is in
     * the window. A false duplicate here drops a fresh introduction, which the
     * requester would not retry inside the clock-skew window.
     */
    @Test
    public void testDistinctNoncesAreNeverRejected() {
        IntroductionManager.RecentRelays relays = new IntroductionManager.RecentRelays();
        for (int i = 0; i < 1000; i++) {
            long nonce = 0x10000L + i;
            assertFalse("nonce " + i + " seen for the first time", relays.testAndSet(nonce));
        }
    }

    /**
     * The window holds exactly {@code CAPACITY} nonces: the full window still
     * contains the first one, one nonce more pushes it out, and the newcomer is
     * kept in its place.
     */
    @Test
    public void testWindowHoldsExactlySixtyFour() {
        IntroductionManager.RecentRelays relays = new IntroductionManager.RecentRelays();
        for (int i = 0; i < CAPACITY; i++) {
            relays.testAndSet(0x20000L + i);
        }
        assertTrue("the oldest of a full window is still remembered", relays.contains(0x20000L));
        relays.testAndSet(0x20000L + CAPACITY);
        assertFalse("one too many drops the oldest", relays.contains(0x20000L));
        assertTrue(relays.contains(0x20000L + CAPACITY));
        // and the evicted nonce is a fresh request again, not rejected
        assertFalse(relays.testAndSet(0x20000L));
    }

    /**
     * Bounded under a flood of introductions: after 4096 distinct nonces, the
     * window holds the last {@code CAPACITY} of them and nothing older. Memory
     * here cannot be allowed to follow the introduction rate.
     */
    @Test
    public void testWindowStaysBoundedUnderAFlood() {
        int n = 4096;
        IntroductionManager.RecentRelays relays = new IntroductionManager.RecentRelays();
        for (int i = 0; i < n; i++) {
            relays.testAndSet(0x30000L + i);
        }
        for (int i = 0; i < n; i++) {
            long nonce = 0x30000L + i;
            assertEquals("nonce " + i + " of " + n, i >= n - CAPACITY, relays.contains(nonce));
        }
    }

    /**
     * A repeat does not consume a slot, so a retransmitting requester cannot
     * push the other introductions out of the window and get them relayed
     * twice. Filling the window, testing one of its nonces as a duplicate, and
     * then adding one more nonce must still leave the whole window intact until
     * that one more nonce is written.
     */
    @Test
    public void testRepeatDoesNotEvictOtherNonces() {
        IntroductionManager.RecentRelays relays = new IntroductionManager.RecentRelays();
        relays.testAndSet(1);
        for (int i = 2; i < CAPACITY; i++) {
            relays.testAndSet(i);
        }
        assertTrue(relays.testAndSet(1));
        relays.testAndSet(CAPACITY);
        for (int i = 1; i <= CAPACITY; i++) {
            assertTrue("nonce " + i + " still in the window", relays.contains(i));
        }
        // the window is full now, so the next one out is the first in
        relays.testAndSet(CAPACITY + 1);
        assertFalse(relays.contains(1));
    }

    /**
     * Concurrent introducers, as several SSU2 receive paths hand in relay
     * requests at once. Nothing may be lost or invented, and the window may not
     * grow: after 8 threads record 1000 distinct nonces each, at most
     * {@code CAPACITY} of them are still remembered.
     */
    @Test
    public void testConcurrentWritersKeepTheWindowBounded() throws Exception {
        final int threads = 8;
        final int perThread = 1000;
        final IntroductionManager.RecentRelays relays = new IntroductionManager.RecentRelays();
        final CountDownLatch go = new CountDownLatch(1);
        final List<Thread> th = new ArrayList<>(threads);
        final AtomicInteger failures = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            Thread worker = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException ie) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    long nonce = 0x40000L + base + i;
                    if (relays.testAndSet(nonce)) {
                        failures.incrementAndGet();
                    }
                }
            });
            th.add(worker);
            worker.start();
        }
        go.countDown();
        for (Thread worker : th) {
            worker.join();
        }
        assertEquals("a first-sight nonce was called a duplicate", 0, failures.get());
        int remembered = 0;
        for (int i = 0; i < threads * perThread; i++) {
            if (relays.contains(0x40000L + i)) {
                remembered++;
            }
        }
        assertTrue(remembered + " nonces remembered, window must hold at most " + CAPACITY,
                   remembered <= CAPACITY);
        assertTrue("the window still holds something", remembered > 0);
    }
}
