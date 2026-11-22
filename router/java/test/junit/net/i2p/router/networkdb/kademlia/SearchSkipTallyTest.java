package net.i2p.router.networkdb.kademlia;

import static net.i2p.router.networkdb.kademlia.IterativeSearchJob.SKIP_NO_AEAD;
import static net.i2p.router.networkdb.kademlia.IterativeSearchJob.SKIP_NO_CLIENT_KEYS;
import static net.i2p.router.networkdb.kademlia.IterativeSearchJob.SKIP_NO_REPLY_TUNNEL;
import static net.i2p.router.networkdb.kademlia.IterativeSearchJob.SKIP_NO_TUNNEL;
import static net.i2p.router.networkdb.kademlia.IterativeSearchJob.SKIP_REASON_COUNT;
import static net.i2p.router.networkdb.kademlia.IterativeSearchJob.SKIP_TOO_OLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * The pure decisions behind a failed NetDb search's failure line: which reason
 * words a passed-over candidate gets, and how the per-reason tally is laid out.
 * <p>
 * A live router wrote 89 LeaseSet lookup failures in 40 minutes, 28 of them dead
 * in under 5 ms, and every one read as "search failed" with nothing to say about
 * why no peer was ever asked. The search also counted those lookups among the
 * failures that had queried peers, so the console's "zero peers queried" counter
 * reported no events at all beside them. The tally below is what makes the
 * mechanism greppable instead of inferred from a duration.
 *
 * @since 0.9.72+
 */
public class SearchSkipTallyTest {

    /**
     * Declares the no-arg constructor the class would otherwise inherit, so
     * the test class itself is documented.
     */
    public SearchSkipTallyTest() {}

    /**
     * The words for one tallyable reason, spelled once per call site.
     *
     * @param reason one of the {@code SKIP_*} constants
     * @return the prose reason the failure line will carry
     */
    private static String why(int reason) {
        return IterativeSearchJob.skipReasonText(reason);
    }

    /**
     * A search that queried every candidate it considered writes no tally at all,
     * so its failure line is byte-identical to the one written before the tally
     * existed.
     */
    @Test
    public void testEmptyTallyWhenNothingSkipped() {
        assertEquals("", IterativeSearchJob.skipTally(new int[SKIP_REASON_COUNT]));
    }

    /**
     * One reason emits a flat total and one reason segment, with the count after
     * the words rather than the words nested under the total's label.
     */
    @Test
    public void testSingleReasonEmitsFlatTotalAndSegment() {
        int[] reasons = new int[SKIP_REASON_COUNT];
        reasons[SKIP_NO_TUNNEL] = 2;
        assertEquals(" * Skipped: 2 * " + why(SKIP_NO_TUNNEL) + ": 2",
                     IterativeSearchJob.skipTally(reasons));
    }

    /**
     * Several reasons keep the structure flat and parallel: one {@code Skipped}
     * total, then one {@code key: value} segment per reason at the same level, in
     * constant-index order, and the total is the sum.
     */
    @Test
    public void testSeveralReasonsStayFlatAndOrdered() {
        int[] reasons = new int[SKIP_REASON_COUNT];
        reasons[SKIP_TOO_OLD] = 3;
        reasons[SKIP_NO_REPLY_TUNNEL] = 1;
        reasons[SKIP_NO_AEAD] = 4;
        String tally = IterativeSearchJob.skipTally(reasons);
        assertTrue(tally, tally.startsWith(" * Skipped: 8 * "));
        // the total is a bare count, never a label for the reasons after it
        assertEquals(1, tally.split("Skipped: ", -1).length - 1);
        assertEquals('8', tally.charAt(tally.indexOf("Skipped: ") + "Skipped: ".length()));
        int old = tally.indexOf(why(SKIP_TOO_OLD));
        int noReply = tally.indexOf(why(SKIP_NO_REPLY_TUNNEL));
        int noAead = tally.indexOf(why(SKIP_NO_AEAD));
        assertTrue(tally, old < noReply && noReply < noAead);
        // a reason with no candidates is not mentioned
        assertFalse(tally, tally.contains(why(SKIP_NO_CLIENT_KEYS)));
    }

    /**
     * Every tallyable reason has its own words, capitalized like the rest of the
     * house log format, so a finding can be grepped for the reason and not for a
     * method name or an index.
     */
    @Test
    public void testEveryReasonHasDistinctCapitalizedWords() {
        Set<String> seen = new HashSet<>(SKIP_REASON_COUNT);
        for (int i = 0; i < SKIP_REASON_COUNT; i++) {
            String reason = why(i);
            assertFalse(reason, reason.isEmpty());
            assertTrue(reason, Character.isUpperCase(reason.charAt(0)));
            assertTrue("duplicate words for reason " + i + ": " + reason, seen.add(reason));
            // no reason is a bare code or an identifier-shaped token
            assertFalse(reason, reason.matches("[a-z][A-Za-z0-9_]*"));
        }
    }

    /**
     * An index outside the tallyable set names no reason rather than throwing, so
     * a tally array that outgrows its constants cannot take the failure line - and
     * with it the search's whole report - down with it.
     */
    @Test
    public void testUnknownIndexFallsBackToWords() {
        assertEquals("Reason unknown", why(-1));
        assertEquals("Reason unknown", why(SKIP_REASON_COUNT));
    }
}
