package org.klomp.snark.dht;

import java.util.Map;
import net.i2p.I2PAppContext;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The blacklist is what keeps a deaf DHT node out of the iterative get_peers and find_node
 * walks. Those walks try a bounded number of nodes, one at a time, and each attempt waits
 * {@link KRPC#DEFAULT_QUERY_TIMEOUT} for a reply that may never come, so a node that has
 * already failed to answer costs an attempt and the whole wait. Measured on a router that
 * had this code as it was: the blacklist was empty on every cleaner pass, no walk ever
 * skipped a node, and 96 attempts in 37 minutes waited out the full timeout on 60 distinct
 * deaf nodes. These tests pin when a node gets marked, that the mark outlives the walk that
 * would otherwise retry it, and that the routing-table eviction stays where it was.
 *
 * @since 0.9.72+
 */
public class DHTBlacklistTest {

    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();

    /**
     * Fixture group for the blacklist; it holds no state.
     */
    public DHTBlacklistTest() {}

    /**
     * One unanswered query marks the node, and the walk's check sees it. The mark used to
     * wait for the routing-table bar - six consecutive timeouts - so every one of those six
     * attempts was spent on a node that had already proved deaf.
     */
    @Test
    public void firstUnansweredQueryMarksTheNode() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo deaf = KRPCFilterTest.validNode((byte) 42, 7000);
            assertFalse(krpc.recentlyFailed(deaf));
            krpc.timeout(deaf);
            assertTrue("one unanswered query must be enough", krpc.recentlyFailed(deaf));
            assertTrue("the mark must carry an age for the cleaner",
                       krpc.blacklistedAt(deaf.getNID()) > 0);
        } finally {
            krpc.stop();
        }
    }

    /**
     * The mark follows the node id, not the object. A walk is handed a fresh
     * {@code NodeInfo} for the same node by every reply it parses, so a mark made against
     * one of them has to be recognised against the next.
     */
    @Test
    public void theMarkFollowsTheNodeIdNotTheInstance() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo deaf = KRPCFilterTest.validNode((byte) 52, 7001);
            krpc.timeout(deaf);
            NodeInfo again = sameId(deaf);
            assertTrue("a fresh instance for the same node is still marked",
                       krpc.recentlyFailed(again));
            krpc.heardFrom(again);
            assertFalse("any answer at all clears the mark", krpc.recentlyFailed(deaf));
            assertEquals(-1, krpc.blacklistedAt(deaf.getNID()));
        } finally {
            krpc.stop();
        }
    }

    /**
     * Marking a node must not restart its run of unanswered queries. The blacklist used to
     * stamp the mark's age with {@link NID#setLastSeen()}, which zeroes the failure count,
     * so a node had to fail six more times than the routing-table bar before it was marked
     * at all. Here the run reaches the bar and evicts on schedule while the mark is held.
     */
    @Test
    public void markingKeepsTheFailureRunCounting() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo deaf = krpc.heardFrom(KRPCFilterTest.validNode((byte) 62, 7002));
            DHTNodes known = knownNodes(krpc);
            assertSame("the fixture node is in the routing table", deaf, known.get(deaf.getNID()));
            NID nid = deaf.getNID();
            for (int i = 1; i <= NID.MAX_FAILS; i++) {
                krpc.timeout(deaf);
                assertEquals("the run counts up, mark or no mark", i, nid.getFailCount());
                assertTrue("marked from the first timeout", krpc.recentlyFailed(deaf));
                assertNotNull("still in the routing table below the bar", known.get(nid));
            }
            krpc.timeout(deaf);
            assertEquals(NID.MAX_FAILS + 1, nid.getFailCount());
            assertNull("the routing-table bar is unchanged", known.get(nid));
            assertTrue("and it stays marked for the walks", krpc.recentlyFailed(deaf));
        } finally {
            krpc.stop();
        }
    }

    /**
     * A later mark for the same node, arriving as a different {@code NID} instance as a
     * parsed reply hands it, has to refresh the age the cleaner reads. The blacklist used to
     * be a set, which keeps the key instance inserted first, so the refresh landed on a
     * discarded object and a node we kept failing on was pruned while it was still failing.
     */
    @Test
    public void aLaterMarkRefreshesTheAgeTheCleanerReads() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo deaf = KRPCFilterTest.validNode((byte) 72, 7003);
            krpc.timeout(deaf);
            // wind the stored age back to just past expiry, as the cleaner would see it
            // after a whole window without a fresh mark
            long stale = CTX.clock().now() - KRPC.BLACKLIST_CLEAN_TIME - 1000;
            blacklist(krpc).put(deaf.getNID(), Long.valueOf(stale));
            assertEquals(stale, krpc.blacklistedAt(deaf.getNID()));

            krpc.timeout(sameId(deaf));
            long refreshed = krpc.blacklistedAt(sameId(deaf).getNID());
            assertTrue("the re-mark must move the entry the cleaner expires",
                       refreshed > stale);
            assertTrue("and it must be a fresh age",
                       refreshed > CTX.clock().now() - KRPC.BLACKLIST_CLEAN_TIME);
        } finally {
            krpc.stop();
        }
    }

    /**
     * The window has to outlast the walk that would otherwise try the node again: the
     * explorer runs every {@link KRPC#EXPLORE_TIME} and one attempt costs
     * {@link KRPC#DEFAULT_QUERY_TIMEOUT}. The old five minutes expired before the next pass
     * ever ran, which is why the blacklist measured empty on every cleaner pass.
     */
    @Test
    public void windowOutlastsTheWalkThatWouldRetryTheNode() {
        assertTrue("the mark must still be held three explore passes on",
                   KRPC.BLACKLIST_CLEAN_TIME >= 3 * KRPC.EXPLORE_TIME);
        // and it has to cover the run of timeouts that takes a node out of the routing
        // table, or a node that has gone away is queried again while it is still failing
        assertTrue(KRPC.BLACKLIST_CLEAN_TIME > (NID.MAX_FAILS + 1) * KRPC.DEFAULT_QUERY_TIMEOUT);
    }

    /**
     * One exploration step must not give up before its own query times out, or the reply,
     * if it arrives at all, lands on a waiter nobody is waiting on and the step reports a
     * node that never answered. The 60 seconds used here expired 15 seconds early on every
     * deaf node - 93 times in 37 minutes.
     */
    @Test
    public void exploreStepWaitsAtLeastAsLongAsTheQuery() {
        assertTrue(KRPC.EXPLORE_NODE_WAIT > KRPC.DEFAULT_QUERY_TIMEOUT);
    }

    /**
     * A second {@code NodeInfo} for the same node, as a parsed reply hands it to a walk.
     *
     * @param n the node to copy
     * @return a distinct instance holding the same node id, hash and port
     */
    private static NodeInfo sameId(NodeInfo n) {
        return new NodeInfo(new NID(n.getNID().getData()), n.getHash(), n.getPort());
    }

    /**
     * A KRPC over a fake session, with its own blacklist - a KRPC built without a shared
     * one does not get the shared map.
     *
     * @return a started-enough instance for these calls, stopped by the caller
     */
    private static KRPC newKRPC() {
        return new KRPC(CTX, "i2psnark", new KRPCFilterTest.FakeSession().session);
    }

    /**
     * The routing table, reached by reflection because nothing outside the DHT should hold
     * it.
     *
     * @param krpc the instance to look inside
     * @return its routing table
     */
    private static DHTNodes knownNodes(KRPC krpc) {
        try {
            return (DHTNodes) KRPCFilterTest.getField(krpc, "_knownNodes");
        } catch (Exception e) {
            throw new AssertionError("no routing table to check", e);
        }
    }

    /**
     * The blacklist map, reached by reflection so a test can set an age the cleaner would
     * expire.
     *
     * @param krpc the instance to look inside
     * @return its blacklist, keyed by node id, valued by when the mark was last refreshed
     */
    @SuppressWarnings("unchecked")
    private static Map<NID, Long> blacklist(KRPC krpc) {
        try {
            return (Map<NID, Long>) KRPCFilterTest.getField(krpc, "_blacklist");
        } catch (Exception e) {
            throw new AssertionError("no blacklist to wind", e);
        }
    }
}
