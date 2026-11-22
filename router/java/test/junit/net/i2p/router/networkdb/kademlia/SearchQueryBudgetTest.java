package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The pure decisions behind how a NetDb search accounts for the candidates it
 * never asked: whether they spend its query budget, whether they satisfy beta
 * confirmation, and whether a failure with nobody asked may record a definitive
 * "no such destination" against the key.
 * <p>
 * Every candidate rejected inside {@code sendQuery()} - too old to hold the key
 * type, no key to encrypt a reply to, no AEAD support - used to be recorded in
 * the same set as a peer that was asked and answered no, and all three gates
 * read that set. A search whose closest candidates were all unqueryable
 * therefore spent its budget, believed it had confirmed against beta peers, and
 * poisoned the negative cache, all without a packet leaving. A live router
 * reported 26.7 LeaseSet lookups per minute failing having asked nobody,
 * against 7 per minute failing having asked somebody, and one rejected
 * destination was fetched 1,639 times because each attempt re-cached the same
 * wrong verdict.
 * <p>
 * All three helpers are static and stateless: testable without a running router.
 * The {@code old*} models below restate the gates as they stood before this
 * change, so each case shows the two disagreeing about the same search.
 *
 * @since 0.9.72+
 */
public class SearchQueryBudgetTest {

    /**
     * Beta as the widening gate has always spelled it: the number of peers that
     * must be asked before a search may declare the key absent. Mirrors
     * {@code IterativeSearchJob.BETA_CONFIRM}, which is private.
     */
    private static final int BETA = 3;

    /**
     * Test case carrying the query-accounting cases for one NetDb search. Every
     * case calls a static helper, so the instance holds no state and needs no
     * router.
     */
    public SearchQueryBudgetTest() {
    }

    /**
     * The budget gate as it stood before this change: it read the set of peers
     * recorded as failed, which held every candidate passed over without a
     * query.
     *
     * @param recordedFailed peers in the failed set, asked or not
     * @param limit the search's query allowance
     * @return true if the old gate stopped the search
     */
    private static boolean oldBudgetSpent(int recordedFailed, int limit) {
        return recordedFailed >= limit;
    }

    /**
     * The beta-confirmation gate as it stood before this change, satisfied by
     * peers we never asked.
     *
     * @param recordedFailed peers in the failed set, asked or not
     * @return true if the old gate refused to widen the candidate set
     */
    private static boolean oldConfirmsBeta(int recordedFailed) {
        return recordedFailed >= BETA;
    }

    /**
     * The live shape: a search given 24 floodfill candidates, every one of them
     * a stale peer that cannot answer a modern LeaseSet lookup. None of them is
     * a query, so none of them spends the budget - while the old gate cancelled
     * the search with nothing asked.
     */
    @Test
    public void testPassedOverCandidatesNeverSpendTheBudget() {
        int limit = 24;
        int passedOver = 24;
        assertFalse(IterativeSearchJob.queryBudgetSpent(0, limit));
        assertTrue(oldBudgetSpent(passedOver, limit));
    }

    /** The budget is spent at the limit, not before it. */
    @Test
    public void testBudgetSpentAtTheLimitNotBefore() {
        assertFalse(IterativeSearchJob.queryBudgetSpent(23, 24));
        assertTrue(IterativeSearchJob.queryBudgetSpent(24, 24));
        assertTrue(IterativeSearchJob.queryBudgetSpent(25, 24));
    }

    /**
     * A limit of zero - reachable from {@code netdb.searchLimit} or the Tuner,
     * neither of which clamps it - is spent before the first query.
     */
    @Test
    public void testZeroLimitIsSpentImmediately() {
        assertTrue(IterativeSearchJob.queryBudgetSpent(0, 0));
    }

    /**
     * A starved search still widens its candidate set. Three passed-over
     * candidates used to satisfy the beta test, and that test is the gate which
     * switches widening off - so the one response to a starved candidate list
     * was disabled by the very condition that called for it.
     */
    @Test
    public void testStarvedSearchStillWidensForBeta() {
        int passedOver = 3;
        assertTrue(IterativeSearchJob.mayWidenForBeta(false, false, 0));
        assertTrue(oldConfirmsBeta(passedOver));
    }

    /** Once beta peers have really been asked, the failure is confirmed. */
    @Test
    public void testWideningStopsOnceBetaIsAsked() {
        assertFalse(IterativeSearchJob.mayWidenForBeta(false, false, BETA));
        assertFalse(IterativeSearchJob.mayWidenForBeta(false, false, BETA + 1));
    }

    /** Widening is one-shot, and a finished search does not widen at all. */
    @Test
    public void testWideningIsOneShotAndStopsWhenDead() {
        assertFalse(IterativeSearchJob.mayWidenForBeta(true, false, 0));
        assertFalse(IterativeSearchJob.mayWidenForBeta(false, true, 0));
        assertFalse(IterativeSearchJob.mayWidenForBeta(true, true, 0));
    }

    /**
     * A failure that never asked anybody says nothing about the key and must not
     * reach the negative lookup cache. The guard's own comment has always said
     * so, and it was fed a count that included every candidate we skipped.
     */
    @Test
    public void testZeroQueryFailureDoesNotPoisonTheCache() {
        int passedOver = 24;
        assertFalse(IterativeSearchJob.poisonsNegativeCache(0, false));
        assertTrue("the old guard cleared itself on peers we never asked",
                   oldBudgetSpent(passedOver, 1));
    }

    /** A failure after a real query is a definitive answer about the key. */
    @Test
    public void testQueriedFailurePoisonsTheCache() {
        assertTrue(IterativeSearchJob.poisonsNegativeCache(1, false));
        assertTrue(IterativeSearchJob.poisonsNegativeCache(24, false));
    }

    /** With no connections at all, a failure is about us, not about the key. */
    @Test
    public void testDisconnectedFailureNeverPoisonsTheCache() {
        assertFalse(IterativeSearchJob.poisonsNegativeCache(24, true));
        assertFalse(IterativeSearchJob.poisonsNegativeCache(0, true));
    }

    /**
     * The regression end to end. The starved search asked nobody, so under the
     * honest counter it neither spends its budget, nor confirms beta, nor caches
     * a verdict - while under the old counter it did all three at once, which is
     * how one rejected destination was fetched 1,639 times.
     */
    @Test
    public void testStarvedSearchFailsThreeWaysUnderTheOldCounter() {
        int limit = 24;
        int passedOver = 24;
        int queriesSent = 0;
        assertFalse(IterativeSearchJob.queryBudgetSpent(queriesSent, limit));
        assertTrue(IterativeSearchJob.mayWidenForBeta(false, false, queriesSent));
        assertFalse(IterativeSearchJob.poisonsNegativeCache(queriesSent, false));

        assertTrue(oldBudgetSpent(passedOver, limit));
        assertTrue(oldConfirmsBeta(passedOver));
    }
}
