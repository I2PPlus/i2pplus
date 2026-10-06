package net.i2p.router.transport;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

/**
 * Ordering-contract tests for {@link PrioritySendPool}.
 *
 * The pool's dequeue order is a priority queue, so its semantics are load
 * bearing: client data must overtake transit, equal priorities must stay FIFO,
 * and the eviction victim must be the lowest-priority / newest message. The
 * backing store is a sorted array with a head index rather than an ArrayList
 * that is shifted down on every poll, so these tests pin the order that the
 * head index must preserve.
 *
 * @since 0.9.71+
 */
public class PrioritySendPoolTest {

    /** Shared: nothing here touches router state, only OutNetMessage construction. */
    private static RouterContext _ctx;

    @BeforeClass
    public static void setUpClass() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    @AfterClass
    public static void tearDownClass() {
        // JobQueue.shutdown() is package-private, so a test outside
        // net.i2p.router cannot stop the pumper thread initAll() started.
        _ctx = null;
    }

    /**
     * @param priority the message priority
     * @return a payload-free message, identified by object identity
     */
    private OutNetMessage msg(int priority) {
        return new OutNetMessage(_ctx, null, 0, priority, null);
    }

    /**
     * Poll the pool empty and collect the priorities in dequeue order.
     *
     * @param pool the pool to drain
     * @return priorities in the order they were dequeued
     */
    private ArrayList<Integer> drainPriorities(PrioritySendPool pool) {
        ArrayList<Integer> rv = new ArrayList<>();
        OutNetMessage m;
        while ((m = pool.poll()) != null) {
            rv.add(Integer.valueOf(m.getPriority()));
        }
        return rv;
    }

    /**
     * Highest priority is dequeued first regardless of arrival order.
     */
    @Test
    public void testHighestPriorityFirst() {
        PrioritySendPool pool = new PrioritySendPool(16);
        assertTrue(pool.offer(msg(OutNetMessage.PRIORITY_PARTICIPATING)));
        assertTrue(pool.offer(msg(OutNetMessage.PRIORITY_MY_DATA)));
        assertTrue(pool.offer(msg(OutNetMessage.PRIORITY_NETDB_REPLY)));

        assertEquals("client data, then build reply, then transit",
                     Arrays.asList(Integer.valueOf(1000), Integer.valueOf(300), Integer.valueOf(200)),
                     drainPriorities(pool));
    }

    /**
     * Equal priorities are dequeued in arrival order. The tie-break is the
     * sequence number that offer() stamps, so this is what makes a burst of
     * same-priority messages leave in the order it arrived.
     */
    @Test
    public void testFifoWithinSamePriority() {
        PrioritySendPool pool = new PrioritySendPool(16);
        OutNetMessage first = msg(400);
        OutNetMessage second = msg(400);
        OutNetMessage third = msg(400);
        assertTrue(pool.offer(first));
        assertTrue(pool.offer(second));
        assertTrue(pool.offer(third));

        assertSame(first, pool.poll());
        assertSame(second, pool.poll());
        assertSame(third, pool.poll());
        assertNull(pool.poll());
    }

    /**
     * A full pool admits a strictly higher priority by evicting the current
     * lowest, and the pool stays full afterwards.
     */
    @Test
    public void testEvictionTakesLowestPriority() {
        PrioritySendPool pool = new PrioritySendPool(3);
        OutNetMessage transit = msg(OutNetMessage.PRIORITY_PARTICIPATING);
        OutNetMessage reply = msg(OutNetMessage.PRIORITY_NETDB_REPLY);
        OutNetMessage data = msg(OutNetMessage.PRIORITY_MY_DATA);
        assertTrue(pool.offer(transit));
        assertTrue(pool.offer(reply));
        assertTrue(pool.offer(data));
        assertEquals(3, pool.size());

        assertTrue("400 outranks the 200 in the pool",
                   pool.offer(msg(OutNetMessage.PRIORITY_MEDIUM)));
        assertEquals("eviction replaced rather than grew the pool", 3, pool.size());
        assertEquals(1, pool.getEvictedCount());
        assertEquals(0, pool.getDroppedCount());

        assertEquals("transit was evicted",
                     Arrays.asList(Integer.valueOf(1000), Integer.valueOf(400), Integer.valueOf(300)),
                     drainPriorities(pool));
    }

    /**
     * Equal priority is not strictly higher, so the incoming message is
     * dropped and nothing already queued is evicted.
     */
    @Test
    public void testEqualPriorityIsDroppedNotEvicted() {
        PrioritySendPool pool = new PrioritySendPool(2);
        OutNetMessage older = msg(OutNetMessage.PRIORITY_PARTICIPATING);
        OutNetMessage newer = msg(OutNetMessage.PRIORITY_PARTICIPATING);
        assertTrue(pool.offer(older));
        assertTrue(pool.offer(newer));

        assertFalse("equal priority must not evict", pool.offer(msg(OutNetMessage.PRIORITY_PARTICIPATING)));
        assertEquals(1, pool.getDroppedCount());
        assertEquals(0, pool.getEvictedCount());
        assertSame(older, pool.poll());
        assertSame(newer, pool.poll());
    }

    /**
     * When several messages share the lowest priority the eviction victim is
     * the newest of them, i.e. the tail of the sorted region. This is the
     * tie-break the head index must not disturb.
     */
    @Test
    public void testEvictionAmongEqualLowestPicksNewest() {
        PrioritySendPool pool = new PrioritySendPool(3);
        OutNetMessage low1 = msg(100);
        OutNetMessage low2 = msg(100);
        OutNetMessage high = msg(900);
        assertTrue(pool.offer(low1));
        assertTrue(pool.offer(low2));
        assertTrue(pool.offer(high));
        assertEquals(3, pool.size());

        // Tail before eviction is low2; low1 must survive.
        assertTrue(pool.offer(msg(800)));
        assertSame(1, pool.getEvictedCount());
        assertSame(high, pool.poll());
        assertEquals(800, pool.poll().getPriority());
        assertSame("the older of the two lowest-priority messages survives", low1, pool.poll());
    }

    /**
     * size() and remainingCapacity() must report the live region, not the
     * backing array, which retains a dead prefix between compactions.
     */
    @Test
    public void testSizeTracksLiveRegionAfterPops() {
        PrioritySendPool pool = new PrioritySendPool(10);
        for (int i = 0; i < 4; i++) {
            assertTrue(pool.offer(msg(100 + i)));
        }
        assertEquals(4, pool.size());
        assertEquals(6, pool.remainingCapacity());

        assertEquals(103, pool.poll().getPriority());
        assertEquals(102, pool.poll().getPriority());
        assertEquals("pops must not create phantom capacity", 2, pool.size());
        assertEquals(8, pool.remainingCapacity());
    }

    /**
     * Drive the pool past the compaction threshold repeatedly and confirm the
     * order and the counts survive. Compaction discards the dead prefix of the
     * backing array; a wrong shift here would duplicate or drop messages
     * rather than merely reorder them.
     */
    @Test
    public void testOrderSurvivesHeadCompaction() {
        PrioritySendPool pool = new PrioritySendPool(512);
        for (int round = 0; round < 40; round++) {
            assertTrue(pool.offer(msg(1000)));
            assertTrue(pool.offer(msg(200)));
            assertTrue(pool.offer(msg(600)));
            assertEquals(1000, pool.poll().getPriority());
            assertEquals(600, pool.poll().getPriority());
            assertEquals(200, pool.poll().getPriority());
            assertEquals("pool must be empty between rounds", 0, pool.size());
        }
        assertEquals(120, pool.getAddedCount());
        assertEquals(0, pool.getDroppedCount());
        assertEquals(0, pool.getEvictedCount());
    }

    /**
     * Reusing a pool that has been drained past the compaction threshold must
     * still insert in sorted order - compaction resets the head to zero, and
     * insertion has to binary-search from there.
     */
    @Test
    public void testInsertSortedAfterCompaction() {
        PrioritySendPool pool = new PrioritySendPool(128);
        for (int i = 0; i < 100; i++) {
            assertTrue(pool.offer(msg(300)));
        }
        for (int i = 0; i < 100; i++) {
            assertEquals("drain must not lose or duplicate a message", 300, pool.poll().getPriority());
        }
        assertNull(pool.poll());
        assertTrue(pool.offer(msg(900)));
        assertTrue(pool.offer(msg(100)));
        assertTrue(pool.offer(msg(500)));
        assertEquals(Arrays.asList(Integer.valueOf(900), Integer.valueOf(500), Integer.valueOf(100)),
                     drainPriorities(pool));
    }

    /**
     * drainTo() must emit only the live region, in dequeue order, so that
     * TransportImpl.resizeSendPool() re-offers the pending messages correctly.
     */
    @Test
    public void testDrainToEmitsLiveRegionInOrder() {
        PrioritySendPool pool = new PrioritySendPool(16);
        assertTrue(pool.offer(msg(200)));
        assertTrue(pool.offer(msg(900)));
        assertTrue(pool.offer(msg(500)));
        assertEquals(900, pool.poll().getPriority());

        ArrayList<OutNetMessage> pending = new ArrayList<>();
        pool.drainTo(pending);
        assertEquals(2, pending.size());
        assertEquals(500, pending.get(0).getPriority());
        assertEquals(200, pending.get(1).getPriority());
        assertEquals(0, pool.size());
        assertEquals(16, pool.remainingCapacity());
    }

    /**
     * A shrinking capacity only takes effect for later offers. Messages
     * already queued stay put; each subsequent offer sheds one tail message
     * when the pool is over the new capacity, which is the documented
     * "evicted on the next offer()" behaviour.
     */
    @Test
    public void testShrinkCapacityShedsOnNextOffer() {
        PrioritySendPool pool = new PrioritySendPool(5);
        for (int i = 1; i <= 5; i++) {
            assertTrue(pool.offer(msg(100 * i)));
        }
        pool.setCapacity(3);
        assertEquals(3, pool.getCapacity());

        assertFalse("lower priority than the tail cannot displace it", pool.offer(msg(50)));
        assertTrue(pool.offer(msg(1000)));
        assertEquals(1, pool.getEvictedCount());
        assertEquals(Arrays.asList(Integer.valueOf(1000), Integer.valueOf(500),
                                   Integer.valueOf(400), Integer.valueOf(300),
                                   Integer.valueOf(200)),
                     drainPriorities(pool));
    }
}
