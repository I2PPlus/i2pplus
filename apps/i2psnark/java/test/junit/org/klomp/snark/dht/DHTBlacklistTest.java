package org.klomp.snark.dht;

import java.util.Map;
import net.i2p.I2PAppContext;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The blacklist is what keeps a deaf DHT node out of the iterative get_peers and find_node
 * walks. Those walks try a bounded number of nodes, one at a time, and each attempt waits
 * for a reply that may never come, so a node that has already failed to answer costs an
 * attempt and the whole wait. These tests pin when a node gets marked - a run of
 * unanswered queries, or one reply carrying data we cannot use - how long each mark runs,
 * that the mark outlives the walk that would otherwise retry it, and that the routing-table
 * eviction stays where it was.
 *
 * <p>Measured on a router running the version that marked on the first unanswered query:
 * over 171 minutes, 867 distinct nodes failed to answer something and 295 of them - 34% -
 * failed exactly once, 135 marked nodes answered again later, and the blacklist sat at its
 * cap on 149 of 160 cleaner passes. Only 110 nodes failed five times inside fifteen minutes.
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
     * One unanswered query is not evidence a node is deaf - it may only be slow to answer -
     * so it is counted and the node is tried again.
     */
    @Test
    public void oneUnansweredQueryDoesNotMarkTheNode() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo slow = KRPCFilterTest.validNode((byte) 42, 7000);
            krpc.timeout(slow);
            assertFalse("one missed reply must not mark the node", krpc.recentlyFailed(slow));
            assertEquals("but it is counted", 1, krpc.blacklistFails(slow.getNID()));
            assertEquals(-1, krpc.blacklistedAt(slow.getNID()));
        } finally {
            krpc.stop();
        }
    }

    /**
     * A run of unanswered queries inside one window marks the node, and the walk's check
     * sees it. The mark used to wait for the routing-table bar - six consecutive timeouts -
     * so every one of those six attempts was spent on a node that had already proved deaf.
     */
    @Test
    public void fiveUnansweredQueriesInOneWindowMarkTheNode() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo deaf = KRPCFilterTest.validNode((byte) 44, 7004);
            for (int i = 1; i < KRPC.BLACKLIST_FAILS; i++) {
                krpc.timeout(deaf);
                assertFalse("below the bar at " + i, krpc.recentlyFailed(deaf));
            }
            krpc.timeout(deaf);
            assertTrue("the bar is " + KRPC.BLACKLIST_FAILS + " failures",
                       krpc.recentlyFailed(deaf));
            assertTrue("the mark must carry an age for the cleaner",
                       krpc.blacklistedAt(deaf.getNID()) > 0);
        } finally {
            krpc.stop();
        }
    }

    /**
     * Failures only count as a run when they are close together. A node that misses one
     * query every half hour has all the time in the world to answer the next one, so a gap
     * past the window starts the count over rather than walking the node into the blacklist.
     */
    @Test
    public void aGapPastTheWindowRestartsTheRun() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo deaf = KRPCFilterTest.validNode((byte) 46, 7006);
            for (int i = 1; i < KRPC.BLACKLIST_FAILS; i++) {
                krpc.timeout(deaf);
            }
            assertEquals(KRPC.BLACKLIST_FAILS - 1, krpc.blacklistFails(deaf.getNID()));
            // wind the run's start back past the window, as a later query would find it
            KRPC.BlacklistMark mark = blacklist(krpc).get(deaf.getNID());
            mark._windowStart = CTX.clock().now() - KRPC.BLACKLIST_FAIL_WINDOW - 1000;
            krpc.timeout(sameId(deaf));
            assertEquals("the count starts over", 1, krpc.blacklistFails(deaf.getNID()));
            assertFalse("and the node is still not marked", krpc.recentlyFailed(deaf));
        } finally {
            krpc.stop();
        }
    }

    /**
     * Any answer at all clears a deaf node's run, so a node that answers one query out of
     * five buys itself the whole bar again.
     */
    @Test
    public void anAnswerClearsTheRun() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo deaf = krpc.heardFrom(KRPCFilterTest.validNode((byte) 48, 7008));
            for (int i = 1; i <= KRPC.BLACKLIST_FAILS; i++) {
                krpc.timeout(deaf);
            }
            assertTrue("the fixture reaches the bar", krpc.recentlyFailed(deaf));
            krpc.heardFrom(deaf);
            assertEquals("the answer wiped the count", 0, krpc.blacklistFails(deaf.getNID()));
            assertFalse("and the mark", krpc.recentlyFailed(deaf));
            for (int i = 1; i < KRPC.BLACKLIST_FAILS; i++) {
                krpc.timeout(deaf);
            }
            assertFalse("the bar has to be paid for again", krpc.recentlyFailed(deaf));
            krpc.timeout(deaf);
            assertTrue("and a fresh bar still applies", krpc.recentlyFailed(deaf));
        } finally {
            krpc.stop();
        }
    }

    /**
     * The mark follows the node id, not the object. A walk is handed a fresh
     * {@code NodeInfo} for the same node by every reply it parses, so a run built against
     * one of them has to be recognised against the next.
     */
    @Test
    public void theMarkFollowsTheNodeIdNotTheInstance() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo deaf = KRPCFilterTest.validNode((byte) 52, 7001);
            for (int i = 0; i < KRPC.BLACKLIST_FAILS; i++) {
                krpc.timeout(sameId(deaf));
            }
            assertTrue("a fresh instance for the same node is still marked",
                       krpc.recentlyFailed(sameId(deaf)));
            krpc.heardFrom(sameId(deaf));
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
                if (i >= KRPC.BLACKLIST_FAILS) {
                    assertTrue("marked from the bar on", krpc.recentlyFailed(deaf));
                } else {
                    assertFalse("not marked below the bar", krpc.recentlyFailed(deaf));
                }
                assertNotNull("still in the routing table below the eviction bar", known.get(nid));
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
     * A later failure for the same node, arriving as a different {@code NID} instance as a
     * parsed reply hands it, has to refresh the expiry the cleaner reads. The blacklist used
     * to be a set, which keeps the key instance inserted first, so the refresh landed on a
     * discarded object and a node we kept failing on was pruned while it was still failing.
     */
    @Test
    public void aLaterFailureRefreshesTheAgeTheCleanerReads() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo deaf = KRPCFilterTest.validNode((byte) 72, 7003);
            for (int i = 0; i < KRPC.BLACKLIST_FAILS; i++) {
                krpc.timeout(deaf);
            }
            // wind the stored expiry back to the past, as the cleaner would see it after a
            // whole mark without a fresh failure
            long stale = CTX.clock().now() - 1000;
            blacklist(krpc).get(deaf.getNID())._expiresAt = stale;
            assertEquals(stale, krpc.blacklistedUntil(deaf.getNID()));

            krpc.timeout(sameId(deaf));
            long refreshed = krpc.blacklistedUntil(sameId(deaf).getNID());
            assertTrue("the re-mark must move the entry the cleaner expires",
                       refreshed > stale);
            assertTrue("and it must be a fresh mark",
                       refreshed > CTX.clock().now() + KRPC.BLACKLIST_MINOR_TIME / 2);
        } finally {
            krpc.stop();
        }
    }

    /**
     * A node that answers with data we cannot use is marked on the spot - no run of
     * offences needed - because a node that talks nonsense costs more than a node that is
     * quiet: the parse, a routing-table entry we cannot trust, and every later query that
     * believes what it said.
     */
    @Test
    public void unusableDataMarksTheNodeOnTheSpot() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo dirty = KRPCFilterTest.validNode((byte) 82, 7009);
            krpc.badData(dirty);
            assertTrue("one bad reply is enough", krpc.recentlyFailed(dirty));
            assertEquals("and it is not charged as an unanswered query",
                         0, krpc.blacklistFails(dirty.getNID()));
        } finally {
            krpc.stop();
        }
    }

    /**
     * The mark runs fifteen minutes for the first offence and doubles with each repeat,
     * stopping at an hour: a one-off malformed reply is a short pause, a node that keeps
     * doing it is left alone for a full hour.
     */
    @Test
    public void eachRepeatOffenceDoublesTheMarkUpToAnHour() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo dirty = KRPCFilterTest.validNode((byte) 84, 7010);
            NID nid = dirty.getNID();
            long[] expected = {KRPC.BLACKLIST_MINOR_TIME, 2 * KRPC.BLACKLIST_MINOR_TIME,
                               KRPC.BLACKLIST_MAJOR_TIME, KRPC.BLACKLIST_MAJOR_TIME,
                               KRPC.BLACKLIST_MAJOR_TIME};
            for (int i = 0; i < expected.length; i++) {
                krpc.badData(sameId(dirty));
                // markedAt and expiresAt are stamped from one clock reading, so the
                // difference is the mark's duration exactly
                long dur = krpc.blacklistedUntil(nid) - krpc.blacklistedAt(nid);
                assertEquals("offence " + (i + 1), expected[i], dur);
            }
            assertTrue("the floor is the shortest mark in the scheme",
                       KRPC.BLACKLIST_MINOR_TIME < KRPC.BLACKLIST_MAJOR_TIME);
        } finally {
            krpc.stop();
        }
    }

    /**
     * Answering is what a node that sends us garbage does, so an answer must not wipe the
     * mark the way it wipes a deaf node's. The mark stands until it expires; only the run
     * of unanswered queries starts over.
     */
    @Test
    public void anAnswerDoesNotShortenABadDataMark() {
        KRPC krpc = newKRPC();
        try {
            NodeInfo dirty = krpc.heardFrom(KRPCFilterTest.validNode((byte) 86, 7011));
            krpc.badData(dirty);
            long until = krpc.blacklistedUntil(dirty.getNID());
            krpc.heardFrom(dirty);
            assertTrue("still stepped over after it answered", krpc.recentlyFailed(dirty));
            assertEquals("and the mark is not restarted or cleared",
                         until, krpc.blacklistedUntil(dirty.getNID()));
            // but a deaf-node mark still clears on an answer
            NodeInfo deaf = krpc.heardFrom(KRPCFilterTest.validNode((byte) 88, 7012));
            for (int i = 0; i < KRPC.BLACKLIST_FAILS; i++) {
                krpc.timeout(deaf);
            }
            krpc.heardFrom(deaf);
            assertFalse("a node that only failed to answer is back in play",
                        krpc.recentlyFailed(deaf));
        } finally {
            krpc.stop();
        }
    }

    /**
     * The shortest mark still has to outlast the walk that would otherwise try the node
     * again: the explorer runs every {@link KRPC#EXPLORE_TIME}, and a mark that expires
     * before the next pass is a mark no walk ever sees - which is why the old five minutes
     * measured empty on every cleaner pass.
     */
    @Test
    public void markOutlastsTheWalkThatWouldRetryTheNode() {
        assertTrue("even the lightest mark covers a whole explore pass",
                   KRPC.BLACKLIST_MINOR_TIME >= KRPC.EXPLORE_TIME);
        assertTrue("and the heaviest mark is the heaviest",
                   KRPC.BLACKLIST_MAJOR_TIME > KRPC.BLACKLIST_MINOR_TIME);
    }

    /**
     * The failure window has to be long enough for a node that is queried continuously and
     * never answers to reach the bar inside one window, or the bar could never be reached at
     * all; and the bar has to come before the routing-table eviction, or the walk spends its
     * attempts on a node it is about to drop from the table anyway.
     */
    @Test
    public void barIsReachableAndComesBeforeTheEviction() {
        assertTrue("one window must hold a whole run of timeouts",
                   KRPC.BLACKLIST_FAIL_WINDOW
                           >= KRPC.BLACKLIST_FAILS * KRPC.DEFAULT_QUERY_TIMEOUT);
        assertTrue("the walks must get the mark before the node leaves the routing table",
                   KRPC.BLACKLIST_FAILS <= NID.MAX_FAILS + 1);
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
     * The blacklist map, reached by reflection so a test can wind a run's window or a
     * mark's expiry to what the cleaner would see.
     *
     * @param krpc the instance to look inside
     * @return its blacklist, keyed by node id
     */
    @SuppressWarnings("unchecked")
    private static Map<NID, KRPC.BlacklistMark> blacklist(KRPC krpc) {
        try {
            return (Map<NID, KRPC.BlacklistMark>) KRPCFilterTest.getField(krpc, "_blacklist");
        } catch (Exception e) {
            throw new AssertionError("no blacklist to wind", e);
        }
    }
}
