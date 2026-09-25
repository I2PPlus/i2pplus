package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;

import org.junit.Test;

/**
 * Tests for the per-destination stream reservation protocol behind the
 * incoming/outgoing stream budget gate: the atomic check-and-take, the
 * release clamps, the reservation token consumption, the connect waiter
 * release, and the shrink-only reconciliation sweep.
 *
 * <p>All of these are static and context-free so the flood-loop and
 * teardown accounting can be pinned without a running router.
 *
 * @since 0.9.71+
 */
public class StreamReservationTest {

    /** Deterministic distinct 32-byte dest hash. */
    private static Hash hash(int seed) {
        byte[] b = new byte[32];
        b[0] = (byte) seed;
        b[31] = (byte) (seed >> 8);
        return new Hash(b);
    }

    /* canReserveStream */

    @Test
    public void testCanReserveStreamBoundary() {
        assertTrue("empty ledger admits", ConnectionManager.canReserveStream(0, 1));
        assertFalse("ceiling reached refuses", ConnectionManager.canReserveStream(1, 1));
        assertTrue("below ceiling admits", ConnectionManager.canReserveStream(2, 3));
        assertFalse("above ceiling refuses", ConnectionManager.canReserveStream(4, 3));
    }

    @Test
    public void testCanReserveStreamZeroOrNegativeCeilingDisablesGate() {
        assertTrue("ceiling 0 disables the gate", ConnectionManager.canReserveStream(5, 0));
        assertTrue("negative ceiling disables the gate", ConnectionManager.canReserveStream(5, -1));
        assertTrue(ConnectionManager.canReserveStream(Integer.MAX_VALUE, 0));
    }

    /* tooManyStreamsForDest (unchanged contract, still used for decisions) */

    @Test
    public void testTooManyStreamsForDestBoundary() {
        assertTrue(ConnectionManager.tooManyStreamsForDest(3, 3));
        assertFalse(ConnectionManager.tooManyStreamsForDest(2, 3));
        assertFalse(ConnectionManager.tooManyStreamsForDest(0, 0));
        assertFalse("ceiling 0 disables the gate", ConnectionManager.tooManyStreamsForDest(99, 0));
    }

    /* reserveStreamSlot */

    @Test
    public void testReserveStopsAtCeiling() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        Hash h = hash(1);
        assertTrue(ConnectionManager.reserveStreamSlot(ledger, h, 2));
        assertTrue(ConnectionManager.reserveStreamSlot(ledger, h, 2));
        assertFalse("third stream must be refused", ConnectionManager.reserveStreamSlot(ledger, h, 2));
        assertEquals(2, ConnectionManager.streamSlotCount(ledger, h));
        // A refusal is free: it must not consume or shift the count.
        assertFalse(ConnectionManager.reserveStreamSlot(ledger, h, 2));
        assertEquals(2, ConnectionManager.streamSlotCount(ledger, h));
    }

    @Test
    public void testReserveWithGateDisabledIsUnlimited() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        Hash h = hash(2);
        for (int i = 0; i < 5; i++)
            assertTrue(ConnectionManager.reserveStreamSlot(ledger, h, 0));
        assertEquals(5, ConnectionManager.streamSlotCount(ledger, h));
    }

    @Test
    public void testReserveUnknownDestIsAdmittedWithoutAccounting() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        assertTrue("dest with no hash still gets in", ConnectionManager.reserveStreamSlot(ledger, null, 1));
        assertTrue("a second one does too", ConnectionManager.reserveStreamSlot(ledger, null, 1));
        assertTrue(ledger.isEmpty());
        assertEquals(0, ConnectionManager.streamSlotCount(ledger, null));
    }

    @Test
    public void testReserveSeparateDestsWithIndependentBudgets() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        Hash a = hash(3);
        Hash b = hash(4);
        assertTrue(ConnectionManager.reserveStreamSlot(ledger, a, 1));
        assertFalse("a's ceiling does not apply to b", ConnectionManager.reserveStreamSlot(ledger, a, 1));
        assertTrue(ConnectionManager.reserveStreamSlot(ledger, b, 1));
        assertEquals(1, ConnectionManager.streamSlotCount(ledger, a));
        assertEquals(1, ConnectionManager.streamSlotCount(ledger, b));
    }

    /* releaseStreamSlot */

    @Test
    public void testReleaseDrainsAndRemovesEntry() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        Hash h = hash(5);
        ConnectionManager.reserveStreamSlot(ledger, h, 4);
        ConnectionManager.reserveStreamSlot(ledger, h, 4);
        ConnectionManager.releaseStreamSlot(ledger, h);
        assertEquals(1, ConnectionManager.streamSlotCount(ledger, h));
        ConnectionManager.releaseStreamSlot(ledger, h);
        assertFalse("drained dest must leave no entry", ledger.containsKey(h));
    }

    @Test
    public void testReleaseNeverGoesNegative() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        Hash h = hash(6);
        ConnectionManager.reserveStreamSlot(ledger, h, 1);
        ConnectionManager.releaseStreamSlot(ledger, h);
        // Mismatched teardown (release without reserve) must be a no-op, not a
        // negative count that would hand out free slots afterwards.
        ConnectionManager.releaseStreamSlot(ledger, h);
        ConnectionManager.releaseStreamSlot(ledger, h);
        assertFalse(ledger.containsKey(h));
        assertEquals(0, ConnectionManager.streamSlotCount(ledger, h));
        assertTrue("a negative count would have widened the budget",
                   ConnectionManager.reserveStreamSlot(ledger, h, 1));
        assertFalse("and the widened budget must not persist",
                    ConnectionManager.reserveStreamSlot(ledger, h, 1));
    }

    @Test
    public void testReleaseNullDestIsNoOp() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        ConnectionManager.releaseStreamSlot(ledger, null);
        assertTrue(ledger.isEmpty());
    }

    /* consumeReservation */

    @Test
    public void testConsumeReservationReleasesExactlyOnce() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        ConcurrentHashMap<Long, Hash> tokens = new ConcurrentHashMap<Long, Hash>();
        Hash h = hash(7);
        ConnectionManager.reserveStreamSlot(ledger, h, 4);
        tokens.put(Long.valueOf(42L), h);

        assertEquals(h, ConnectionManager.consumeReservation(tokens, ledger, 42L));
        assertEquals("slot returned to the budget", 0, ConnectionManager.streamSlotCount(ledger, h));
        assertTrue(tokens.isEmpty());

        // A second teardown of the same stream ID must not double-release.
        assertNull(ConnectionManager.consumeReservation(tokens, ledger, 42L));
        assertEquals(0, ConnectionManager.streamSlotCount(ledger, h));
    }

    @Test
    public void testConsumeUnknownReservationIsNoOp() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        ConcurrentHashMap<Long, Hash> tokens = new ConcurrentHashMap<Long, Hash>();
        Hash h = hash(8);
        ConnectionManager.reserveStreamSlot(ledger, h, 4);
        assertNull(ConnectionManager.consumeReservation(tokens, ledger, 99L));
        assertEquals("untouched budget", 1, ConnectionManager.streamSlotCount(ledger, h));
    }

    /* releaseWaiting */

    @Test
    public void testReleaseWaitingNeverGoesNegative() {
        AtomicInteger waiting = new AtomicInteger(0);
        assertEquals(0, ConnectionManager.releaseWaiting(waiting));
        waiting.set(3);
        assertEquals(2, ConnectionManager.releaseWaiting(waiting));
        assertEquals(1, ConnectionManager.releaseWaiting(waiting));
        assertEquals(0, ConnectionManager.releaseWaiting(waiting));
        assertEquals("double release must clamp at zero",
                     0, ConnectionManager.releaseWaiting(waiting));
        assertEquals(0, waiting.get());
    }

    /* reconcileStreamCount */

    @Test
    public void testReconcileKeepsCountWhenTableAgreesOrIsLarger() {
        assertEquals(5, ConnectionManager.reconcileStreamCount(5, 5, null, 0));
        // observed > held: the ledger only ever shrinks, never grows to match
        assertEquals("must never grow", 5, ConnectionManager.reconcileStreamCount(5, 7, null, 0));
    }

    @Test
    public void testReconcileFirstObservationOfExcessHolds() {
        // A reservation can land moments before the sweep; one observation is
        // not enough evidence to claw the slot back.
        assertEquals(5, ConnectionManager.reconcileStreamCount(5, 2, null, 0));
        assertEquals("a different prior excess is not a repeat",
                     5, ConnectionManager.reconcileStreamCount(5, 2, Integer.valueOf(3), 0));
        assertEquals("an excess that grew is not a repeat",
                     5, ConnectionManager.reconcileStreamCount(5, 2, Integer.valueOf(1), 0));
    }

    @Test
    public void testReconcileShrinksToObservationWhenRepeated() {
        assertEquals("same excess twice in a row is a leak",
                     2, ConnectionManager.reconcileStreamCount(5, 2, Integer.valueOf(2), 0));
        assertEquals(0, ConnectionManager.reconcileStreamCount(5, 0, Integer.valueOf(0), 0));
    }

    /**
     * Bound reservation tokens floor the shrink (STR-08/ABA): a stale shrink
     * candidate from a previous sweep must never claw back slots that a
     * concurrent admission already owns a token for. Observed lags (the new
     * stream is bound but not yet in the table the sweeper iterates), so
     * without the token floor the repeated-shrink path would decrement below
     * what is genuinely held.
     */
    @Test
    public void testReconcileNeverShrinksBelowBoundTokens() {
        // stale candidate matches the lagging observation, but tokens show the
        // slots are genuinely held: count stays
        assertEquals("bound tokens floor the shrink",
                     5, ConnectionManager.reconcileStreamCount(5, 2, Integer.valueOf(2), 5));
        // tokens exactly equal to what remains held after the shrink
        assertEquals(2, ConnectionManager.reconcileStreamCount(5, 2, Integer.valueOf(2), 2));
        // observed is the floor when it exceeds the token count (id reuse
        // churn can leave a token behind briefly)
        assertEquals("observed wins when higher than tokens",
                     2, ConnectionManager.reconcileStreamCount(5, 2, Integer.valueOf(2), 1));
        // no candidate: the shrink never applies regardless of tokens
        assertEquals(2, ConnectionManager.reconcileStreamCount(2, 5, null, 7));
    }

    /* reconcileStreamSlots */

    @Test
    public void testReconcileNeverGrowsOrClearsActiveConnections() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        ConcurrentHashMap<Hash, Integer> candidates = new ConcurrentHashMap<Hash, Integer>();
        Hash active = hash(10);
        Hash unobserved = hash(11);
        ConnectionManager.reserveStreamSlot(ledger, active, 8);
        ConnectionManager.reserveStreamSlot(ledger, active, 8);
        ConnectionManager.reserveStreamSlot(ledger, unobserved, 8);
        ConnectionManager.reserveStreamSlot(ledger, unobserved, 8);

        Map<Hash, Integer> observed = new HashMap<Hash, Integer>();
        observed.put(active, Integer.valueOf(2));

        ConnectionManager.reconcileStreamSlots(ledger, observed, candidates, new HashMap<Hash, Integer>());
        assertEquals("live connections must survive the sweep",
                     2, ConnectionManager.streamSlotCount(ledger, active));
        assertEquals("in-flight slots are kept on the first sighting",
                     2, ConnectionManager.streamSlotCount(ledger, unobserved));
        assertTrue(candidates.containsKey(unobserved));
        assertFalse(candidates.containsKey(active));
    }

    @Test
    public void testReconcileShrinksOnlyAfterRepeatAndNeverGrows() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        ConcurrentHashMap<Hash, Integer> candidates = new ConcurrentHashMap<Hash, Integer>();
        Hash h = hash(12);
        ConnectionManager.reserveStreamSlot(ledger, h, 8);
        ConnectionManager.reserveStreamSlot(ledger, h, 8);
        ConnectionManager.reserveStreamSlot(ledger, h, 8);
        ConnectionManager.reserveStreamSlot(ledger, h, 8);

        Map<Hash, Integer> observed = new HashMap<Hash, Integer>();
        observed.put(h, Integer.valueOf(1));

        ConnectionManager.reconcileStreamSlots(ledger, observed, candidates, new HashMap<Hash, Integer>());
        assertEquals("first sighting only records a candidate",
                     4, ConnectionManager.streamSlotCount(ledger, h));
        assertEquals(Integer.valueOf(1), candidates.get(h));

        ConnectionManager.reconcileStreamSlots(ledger, observed, candidates, new HashMap<Hash, Integer>());
        assertEquals("repeated sighting releases the leak",
                     1, ConnectionManager.streamSlotCount(ledger, h));
        assertFalse(candidates.containsKey(h));

        // A dest whose connections reappear is never inflated to match them.
        observed.put(h, Integer.valueOf(3));
        ConnectionManager.reconcileStreamSlots(ledger, observed, candidates, new HashMap<Hash, Integer>());
        assertEquals("must never grow", 1, ConnectionManager.streamSlotCount(ledger, h));
    }

    @Test
    public void testReconcileDropsDrainedEntriesAndPrunesStaleCandidates() {
        ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        ConcurrentHashMap<Hash, Integer> candidates = new ConcurrentHashMap<Hash, Integer>();
        Hash drained = hash(13);
        ConnectionManager.reserveStreamSlot(ledger, drained, 8);
        // Candidate left over from a dest that has since left the ledger.
        candidates.put(hash(14), Integer.valueOf(1));

        // First sweep only records the repeated-excess candidate...
        ConnectionManager.reconcileStreamSlots(ledger, new HashMap<Hash, Integer>(), candidates,
                                               new HashMap<Hash, Integer>());
        assertEquals("first sighting must hold", 1, ConnectionManager.streamSlotCount(ledger, drained));
        // ...the second one applies it and retires the drained entry.
        ConnectionManager.reconcileStreamSlots(ledger, new HashMap<Hash, Integer>(), candidates,
                                               new HashMap<Hash, Integer>());
        assertEquals(0, ConnectionManager.streamSlotCount(ledger, drained));
        assertFalse("drained dest must not leave an entry", ledger.containsKey(drained));
        assertTrue("stale candidate for a departed dest must be pruned", candidates.isEmpty());
    }

    /* concurrency */

    @Test
    public void testConcurrentReservesNeverExceedCeiling() throws Exception {
        final ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        final Hash h = hash(20);
        final int ceiling = 8;
        final int threads = 16;
        final int attempts = 500;
        final AtomicInteger admitted = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        start.await();
                        for (int j = 0; j < attempts; j++) {
                            if (ConnectionManager.reserveStreamSlot(ledger, h, ceiling))
                                admitted.incrementAndGet();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        done.await();

        int held = ConnectionManager.streamSlotCount(ledger, h);
        assertEquals("exactly the ceiling may be admitted", ceiling, admitted.get());
        assertEquals("and the ledger must agree", ceiling, held);
        assertTrue("count must never go negative", held >= 0);
    }

    @Test
    public void testConcurrentReserveAndReleaseBalances() throws Exception {
        final ConcurrentHashMap<Hash, AtomicInteger> ledger = new ConcurrentHashMap<Hash, AtomicInteger>();
        final Hash h = hash(21);
        final int ceiling = 4;
        final int threads = 8;
        final int iterations = 2000;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        start.await();
                        for (int j = 0; j < iterations; j++) {
                            // A release must never happen without the matching
                            // reserve succeeding, so the net must be zero even
                            // when the ceiling throttles admissions.
                            if (ConnectionManager.reserveStreamSlot(ledger, h, ceiling))
                                ConnectionManager.releaseStreamSlot(ledger, h);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        done.await();

        assertEquals("every taken slot must come back", 0, ConnectionManager.streamSlotCount(ledger, h));
        assertFalse("no drained entry may remain", ledger.containsKey(h));
    }
}
