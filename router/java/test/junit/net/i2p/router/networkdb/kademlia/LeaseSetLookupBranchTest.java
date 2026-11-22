package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static net.i2p.router.NetworkDatabaseFacade.LOOKUP_FAILED_LOCAL_NOT_STORED;
import static net.i2p.router.NetworkDatabaseFacade.LOOKUP_FAILED_NEGATIVE_CACHE;
import static net.i2p.router.NetworkDatabaseFacade.LOOKUP_FAILED_NOT_INITIALIZED;
import static net.i2p.router.NetworkDatabaseFacade.LOOKUP_FAILED_STORE_REJECTED;
import static net.i2p.router.NetworkDatabaseFacade.LOOKUP_SEARCH_STARTED;
import static net.i2p.router.NetworkDatabaseFacade.lookupFastFailText;

import org.junit.Test;

/**
 * Which gate ends a LeaseSet lookup, and the words the failure line uses
 * for it.
 * <p>
 * A live router wrote 7,576 "LeaseSet lookup failed for [x] after Nms"
 * lines in four hours, and the duration was the only clue to what had
 * happened: 894 of them returned in 2 ms, where no peer had been asked at
 * all, beside 787 that ran the full 15 s search. Both arrive through the
 * same failure callback, so the line has to name the gate that stopped
 * the lookup.
 *
 * @since 0.9.72+
 */
public class LeaseSetLookupBranchTest {

    /**
     * Declares the no-arg constructor the class would otherwise inherit, so
     * the test class itself is documented.
     *
     * @since 0.9.72+
     */
    public LeaseSetLookupBranchTest() {
    }

    /**
     * A client sub-NetDb miss for a destination this router hosts is
     * answered by the main NetDb, not by the network, so it is the gate
     * that applies even when the key is also negatively cached. Pinning the
     * order keeps the reason the sender reports the one the facade took.
     *
     * @since 0.9.72+
     */
    @Test
    public void localMissIsCheckedBeforeTheNegativeCache() {
        assertEquals(LOOKUP_FAILED_LOCAL_NOT_STORED, gate(true, true, true));
        assertEquals(LOOKUP_FAILED_LOCAL_NOT_STORED, gate(true, true, false));
    }

    /**
     * A key the NetDb has already given up on is abandoned without asking a
     * peer, whether the lookup runs on the main NetDb or a client
     * sub-NetDb.
     *
     * @since 0.9.72+
     */
    @Test
    public void aNegativelyCachedKeyFailsWithoutASearch() {
        assertEquals(LOOKUP_FAILED_NEGATIVE_CACHE, gate(true, false, true));
        assertEquals(LOOKUP_FAILED_NEGATIVE_CACHE, gate(false, false, true));
    }

    /**
     * Nothing gated means a search runs, and the failure, if one comes, is
     * the search's own. A destination this router hosts is only fast-failed
     * on a client sub-NetDb; the main NetDb holds its LeaseSet and is
     * searched for it.
     *
     * @since 0.9.72+
     */
    @Test
    public void nothingGatedStartsASearch() {
        assertEquals(LOOKUP_SEARCH_STARTED, gate(false, false, false));
        assertEquals(LOOKUP_SEARCH_STARTED, gate(false, true, false));
        assertEquals(LOOKUP_SEARCH_STARTED, gate(true, false, false));
    }

    /**
     * Every gate has words, and none of them is the number itself: a reader
     * skimming the log at 3 am has no source open to decode a bare code.
     *
     * @since 0.9.72+
     */
    @Test
    public void everyGateIsNamedInWords() {
        int[] codes = {LOOKUP_SEARCH_STARTED, LOOKUP_FAILED_NOT_INITIALIZED,
                       LOOKUP_FAILED_LOCAL_NOT_STORED,
                       LOOKUP_FAILED_NEGATIVE_CACHE,
                       LOOKUP_FAILED_STORE_REJECTED};
        for (int code : codes) {
            String text = lookupFastFailText(code);
            assertTrue("no words for code " + code,
                       text != null && !text.isEmpty());
            assertFalse("the code stands in for words: " + text,
                        text.contains(String.valueOf(code)));
            assertTrue("the phrase follows an arrow, so it starts " +
                       "capitalized: " + text,
                       Character.isUpperCase(text.charAt(0)));
        }
    }

    /**
     * The ordinary case, a search that ran and failed, is also the fallback
     * for a code no facade returns, so a line never reads as an unknown
     * outcome.
     *
     * @since 0.9.72+
     */
    @Test
    public void anUnknownCodeReportsTheSearch() {
        assertEquals("Search failed", lookupFastFailText(99));
        assertEquals("Search failed", lookupFastFailText(LOOKUP_SEARCH_STARTED));
    }

    /**
     * The facade's gate decision, reached through one short name so each
     * assertion reads as the three conditions and nothing else.
     *
     * @param clientDb true when the facade is a client sub-NetDb
     * @param localDest true when the key is a destination hosted here
     * @param negativeCached true when the key is negatively cached
     * @return the code for the first gate that stops the lookup
     * @since 0.9.72+
     */
    private static int gate(boolean clientDb, boolean localDest,
                            boolean negativeCached) {
        return KademliaNetworkDatabaseFacade
                .lookupFastFail(clientDb, localDest, negativeCached);
    }
}
