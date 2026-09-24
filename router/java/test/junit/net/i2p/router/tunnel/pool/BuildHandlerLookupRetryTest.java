package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the next-hop lookup retry gate
 * ({@link BuildHandler#shouldRetryLookup}): a timed-out local netdb miss may
 * be retried only while a full second attempt still fits inside the
 * originator's build-request budget.
 *
 * @since 0.9.71+
 */
public class BuildHandlerLookupRetryTest {

    private static final long START = 1_000_000L;
    private static final int LOOKUP_MS = 5_000;
    private static final int REQUEST_MS = 15_000;

    @Test
    public void testFreshLookupRetries() {
        assertTrue(BuildHandler.shouldRetryLookup(1, START, START, LOOKUP_MS, REQUEST_MS));
        assertTrue(BuildHandler.shouldRetryLookup(1, START, START + 1_000, LOOKUP_MS, REQUEST_MS));
    }

    @Test
    public void testJustFitsRetries() {
        // elapsed + lookupTimeout must stay strictly inside the budget
        assertTrue(BuildHandler.shouldRetryLookup(1, START, START + 9_999, LOOKUP_MS, REQUEST_MS));
    }

    @Test
    public void testExactBudgetNoRetry() {
        assertFalse(BuildHandler.shouldRetryLookup(1, START, START + 10_000, LOOKUP_MS, REQUEST_MS));
        assertFalse(BuildHandler.shouldRetryLookup(1, START, START + 12_000, LOOKUP_MS, REQUEST_MS));
    }

    @Test
    public void testNoRetriesLeft() {
        assertFalse(BuildHandler.shouldRetryLookup(0, START, START, LOOKUP_MS, REQUEST_MS));
        assertFalse(BuildHandler.shouldRetryLookup(-1, START, START, LOOKUP_MS, REQUEST_MS));
    }

    @Test
    public void testUnknownStartTimeNoRetry() {
        assertFalse(BuildHandler.shouldRetryLookup(1, 0, START, LOOKUP_MS, REQUEST_MS));
    }

    @Test
    public void testBackwardsClockNoRetry() {
        assertFalse(BuildHandler.shouldRetryLookup(1, START, START - 1, LOOKUP_MS, REQUEST_MS));
    }

    @Test
    public void testInvalidBudgetsNoRetry() {
        assertFalse(BuildHandler.shouldRetryLookup(1, START, START, 0, REQUEST_MS));
        assertFalse(BuildHandler.shouldRetryLookup(1, START, START, -1, REQUEST_MS));
        assertFalse(BuildHandler.shouldRetryLookup(1, START, START, LOOKUP_MS, 0));
        assertFalse(BuildHandler.shouldRetryLookup(1, START, START, LOOKUP_MS, -1));
    }

    @Test
    public void testPendingQueueWaitConsumesBudget() {
        // a pending entry may wait up to requestTimeout - lookupTimeout
        // before its lookup starts; past that the originator is gone
        long queuedWait = REQUEST_MS - LOOKUP_MS;
        assertFalse(BuildHandler.shouldRetryLookup(1, START, START + queuedWait, LOOKUP_MS, REQUEST_MS));
        assertTrue(BuildHandler.shouldRetryLookup(1, START, START + queuedWait - 1, LOOKUP_MS, REQUEST_MS));
    }

    @Test
    public void testRetryCountConstantPositive() {
        assertTrue(BuildHandler.MAX_NEXT_HOP_LOOKUP_RETRIES >= 1);
    }
}
