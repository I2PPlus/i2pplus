package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.i2p.data.Hash;

import org.junit.Test;

/**
 * Tests {@link IterativeSearchJob#fallbackRouters(Set, Set)}, the peers a
 * search asks when it has no floodfill it is able to ask.
 *
 * <p>A client LeaseSet lookup runs on a client sub-DB facade, and
 * {@code getAllRouters()} is empty for every client DB by definition, so the
 * fallback read the routers off a database that can never answer them. On a
 * live router holding 6,094 routers in the main NetDb, every floodfill it knew
 * being below the 0.9.62 store gate, that made the fallback dead: 270 searches
 * in 12 minutes logged "No peers in NetDb - reseed required" and failed in 3ms
 * having asked nobody, and 386 logged "Cannot query remote floodfills".
 *
 * @since 0.9.72+
 */
public class SearchFallbackRoutersTest {

    /**
     * The fallback-source cases. Every case calls the static decision, so the
     * instance holds no state and needs no router or facade.
     */
    public SearchFallbackRoutersTest() {
    }

    /**
     * The defect. A client sub-DB names no routers, and the main NetDb's
     * routers have to be used instead - they are the only ones the router has.
     */
    @Test
    public void clientSubDbFallsBackToTheMainNetDb() {
        Set<Hash> main = routers(64);
        Set<Hash> rv = IterativeSearchJob.fallbackRouters(Collections.<Hash>emptySet(), main);
        assertEquals("a search on a client sub-DB asked nobody from a NetDb of 64",
                     64, rv.size());
        assertSame("the main NetDb's set is used, not a copy of it", main, rv);
    }

    /**
     * A search running on the main facade keeps asking the main NetDb it was
     * already reading - the fallback is not a second database.
     */
    @Test
    public void mainFacadeKeepsItsOwnRouters() {
        Set<Hash> main = routers(64);
        Set<Hash> rv = IterativeSearchJob.fallbackRouters(main, routers(128));
        assertEquals(64, rv.size());
        assertTrue("the search's own database wins", rv.containsAll(main));
    }

    /**
     * The one case the "reseed required" line is true for: the router knows no
     * routers anywhere, so there is nobody to ask and the search fails.
     */
    @Test
    public void emptyNetDbHasNobodyToAsk() {
        assertTrue(IterativeSearchJob.fallbackRouters(Collections.<Hash>emptySet(),
                                                      Collections.<Hash>emptySet()).isEmpty());
    }

    /**
     * A sub-DB that does hold entries is not widened to the whole NetDb: the
     * fallback exists for an empty candidate set, not to change which database
     * a search that already has one reads.
     */
    @Test
    public void nonEmptySubDbIsNotWidened() {
        Set<Hash> sub = routers(3);
        Set<Hash> main = routers(90);
        Set<Hash> rv = IterativeSearchJob.fallbackRouters(sub, main);
        assertEquals(3, rv.size());
        assertTrue(rv.containsAll(sub));
    }

    /**
     * @param howMany distinct hashes to build
     * @return a set of that many hashes, each a different 32-byte value
     */
    private static Set<Hash> routers(int howMany) {
        Set<Hash> rv = new HashSet<>(howMany + 1);
        for (int i = 0; i < howMany; i++) {
            byte[] b = new byte[Hash.HASH_LENGTH];
            b[0] = (byte) i;
            b[1] = (byte) (i >>> 8);
            b[2] = 0x7f;
            rv.add(new Hash(b));
        }
        return rv;
    }
}
