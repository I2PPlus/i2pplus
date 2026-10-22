package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests the visit order {@link RefreshRoutersJob} builds its work queue in.
 * <p>
 * The job drains the queue a few routers at a time across many runs, so the
 * queue is drained from the head, not indexed into - it was an {@code ArrayList}
 * with {@code remove(0)}, which copies every remaining element on every visit,
 * over a list as long as the netdb. Converting it to a deque is only safe if the
 * head is still the highest-value entry, which is what is pinned here.
 *
 * @since 0.9.72+
 */
public class RefreshRoutersJobDrainTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    private static Hash hash(int seed) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        b[0] = (byte) seed;
        return new Hash(b);
    }

    /**
     * Build the queue from a stubbed netdb.
     *
     * @param floodfills what getFloodfillPeers() reports, in order
     * @param all what getAllRouters() reports
     * @return the queue the job would drain, head first
     */
    private Deque<Hash> buildQueue(List<Hash> floodfills, java.util.Set<Hash> all) throws Exception {
        FloodfillNetworkDatabaseFacade facade = mock(FloodfillNetworkDatabaseFacade.class);
        when(facade.getFloodfillPeers()).thenReturn(floodfills);
        when(facade.getAllRouters()).thenReturn(all);
        RefreshRoutersJob job = new RefreshRoutersJob(_ctx, facade);
        Method init = RefreshRoutersJob.class.getDeclaredMethod("initializeRouterListIfNeeded");
        init.setAccessible(true);
        init.invoke(job);
        Field field = RefreshRoutersJob.class.getDeclaredField("_routers");
        field.setAccessible(true);
        return (Deque<Hash>) field.get(job);
    }

    /**
     * Floodfills go first, in the order the facade lists them, and every known
     * router appears exactly once - a floodfill counted a second time as an
     * ordinary router would be refreshed twice per cycle.
     */
    @Test
    public void testFloodfillsLeadTheQueueAndNothingIsDuplicated() throws Exception {
        Hash ff1 = hash(1);
        Hash ff2 = hash(2);
        Hash r1 = hash(3);
        Hash r2 = hash(4);
        Hash r3 = hash(5);

        Deque<Hash> queue = buildQueue(Arrays.asList(ff1, ff2),
                                       new LinkedHashSet<>(Arrays.asList(ff1, ff2, r1, r2, r3)));

        assertNotNull(queue);
        assertEquals("every known router queued exactly once", 5, queue.size());
        List<Hash> order = new ArrayList<>(queue);
        assertEquals("floodfills lead the drain", Arrays.asList(ff1, ff2), order.subList(0, 2));
        assertEquals(new HashSet<>(Arrays.asList(r1, r2, r3)),
                     new HashSet<>(order.subList(2, 5)));
        assertSame("the head is what a drain removes", ff1, queue.pollFirst());
    }

    /**
     * With no floodfills known the queue is still the whole netdb, so a router
     * is never skipped just because it is not a floodfill.
     */
    @Test
    public void testQueueHoldsEveryRouterWhenNoFloodfills() throws Exception {
        Hash r1 = hash(11);
        Hash r2 = hash(12);

        Deque<Hash> queue = buildQueue(new ArrayList<Hash>(), new LinkedHashSet<>(Arrays.asList(r1, r2)));

        assertEquals(2, queue.size());
        assertEquals(new HashSet<>(Arrays.asList(r1, r2)), new HashSet<>(queue));
    }

    /**
     * An empty netdb must leave nothing to drain rather than an empty queue the
     * job would then treat as work.
     */
    @Test
    public void testEmptyNetDbLeavesAnEmptyQueue() throws Exception {
        Deque<Hash> queue = buildQueue(new ArrayList<Hash>(), new LinkedHashSet<Hash>());
        assertTrue(queue == null || queue.isEmpty());
    }

    /**
     * The work queue has to drop its head in constant time. Pinned on the
     * representation rather than on timing or on which end a call reads: the
     * cost this replaces was a property of the type - an ArrayList drained with
     * remove(0) copied every remaining entry, up to the whole netdb, on every
     * visit - and no single call shows it.
     */
    @Test
    public void testWorkQueueIsAHeadDrainingDeque() throws Exception {
        Deque<Hash> queue = buildQueue(Arrays.asList(hash(21)),
                                       new LinkedHashSet<>(Arrays.asList(hash(21))));
        assertTrue("work queue must be a deque, not an indexed list: " + queue.getClass(),
                   queue instanceof ArrayDeque);
    }
}
