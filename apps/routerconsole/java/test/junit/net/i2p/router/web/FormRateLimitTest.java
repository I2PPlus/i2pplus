package net.i2p.router.web;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the console form rate limiter's window.
 * <p>
 * The limiter exists to slow down nonce guessing on a failed form submission.
 * It used to reset the POST count when a window expired without moving the
 * window's start, so every call after the first minute took the "expired"
 * branch, reset the count, and returned allowed - the limiter stopped limiting
 * entirely until a sweep happened to discard the entry. The multi-window test
 * below is what pins that it keeps limiting.
 * <p>
 * The clock is an argument to checkRateLimit(), so no test sleeps.
 *
 * @since 0.9.72+
 */
public class FormRateLimitTest {

    private static final long WINDOW = FormHandler.RATE_LIMIT_WINDOW_MS;
    private static final int MAX = FormHandler.RATE_LIMIT_MAX_POSTS;
    private static final String KEY = "test-key";

    private long _now;

    @Before
    public void setUp() {
        FormHandler.RATE_LIMIT_MAP.clear();
        _now = 1_700_000_000_000L;
    }

    /**
     * Charge n POSTs in the current window.
     *
     * @param n how many to charge
     * @return how many were allowed
     */
    private int allow(int n) {
        int ok = 0;
        for (int i = 0; i < n; i++) {
            if (FormHandler.checkRateLimit(KEY, _now)) {ok++;}
        }
        return ok;
    }

    /**
     * The first submission opens a window and is allowed.
     */
    @Test
    public void testFirstSubmissionAllowed() {
        assertTrue(FormHandler.checkRateLimit(KEY, _now));
        assertEquals(1, FormHandler.RATE_LIMIT_MAP.size());
    }

    /**
     * A window admits MAX submissions and no more - the whole point.
     */
    @Test
    public void testBudgetExhaustedInsideTheWindow() {
        assertEquals(MAX, allow(MAX));
        assertFalse("one past the budget must be refused",
                    FormHandler.checkRateLimit(KEY, _now));
    }

    /**
     * Once the window passes, submissions are admitted again - and the new
     * window is charged from the first one, not left at the old count.
     */
    @Test
    public void testNewWindowAdmitsAFreshBudget() {
        allow(MAX);
        assertFalse(FormHandler.checkRateLimit(KEY, _now));
        _now += WINDOW;
        assertEquals(MAX, allow(MAX));
        assertFalse(FormHandler.checkRateLimit(KEY, _now));
    }

    /**
     * The regression: the limiter must still be limiting in the second, third
     * and fourth windows. With the window start never moving, every call past
     * the first minute took the expired branch and returned allowed, so an
     * attacker submitting one POST per minute was never counted twice.
     */
    @Test
    public void testStillLimitingManyWindowsIn() {
        for (int window = 0; window < 4; window++) {
            _now += window == 0 ? 0 : WINDOW;
            assertEquals("window " + window + " admits the full budget", MAX, allow(MAX));
            assertFalse("window " + window + " still refuses an over-budget POST",
                        FormHandler.checkRateLimit(KEY, _now));
        }
    }

    /**
     * A POST just before the window boundary is still counted against the old
     * window, so the budget cannot be doubled by straddling the edge.
     */
    @Test
    public void testBoundaryIsNotGenerous() {
        allow(MAX - 1);
        _now += WINDOW - 1;
        assertTrue(FormHandler.checkRateLimit(KEY, _now));   // the MAX-th of the old window
        assertFalse(FormHandler.checkRateLimit(KEY, _now));  // one past it, still the old window
    }

    /**
     * Distinct keys must not share a budget - otherwise one noisy client
     * locks everyone else out of the console.
     */
    @Test
    public void testKeysAreIndependent() {
        for (int i = 0; i < MAX; i++) {
            assertTrue(FormHandler.checkRateLimit("a", _now));
        }
        assertFalse(FormHandler.checkRateLimit("a", _now));
        assertTrue("a different key keeps its own budget",
                   FormHandler.checkRateLimit("b", _now));
    }

    /**
     * With no key to charge, the submission is allowed - the caller has nothing
     * to attribute it to, and refusing every anonymous POST would break the
     * console for its one local user.
     */
    @Test
    public void testNullAndEmptyKeysAlwaysAllowed() {
        for (int i = 0; i < MAX * 3; i++) {
            assertTrue(FormHandler.checkRateLimit(null, _now));
            assertTrue(FormHandler.checkRateLimit("", _now));
        }
        assertTrue(FormHandler.RATE_LIMIT_MAP.isEmpty());
    }

    /**
     * Idle budgets are reclaimed, live ones are not - the map has to stay
     * bounded without dropping a client that is still submitting.
     */
    @Test
    public void testCleanupDropsOnlyIdleBudgets() {
        FormHandler.checkRateLimit("idle", _now);
        _now += WINDOW * 2;
        FormHandler.checkRateLimit("busy", _now);
        FormHandler.cleanupRateLimits(_now);
        assertEquals(1, FormHandler.RATE_LIMIT_MAP.size());
        assertTrue(FormHandler.RATE_LIMIT_MAP.containsKey("busy"));
    }

    /**
     * Exactly two windows of idleness is the reclaim point, not one less.
     */
    @Test
    public void testCleanupKeepsEntriesUnderTheIdleThreshold() {
        FormHandler.checkRateLimit("edge", _now);
        FormHandler.cleanupRateLimits(_now + FormHandler.RATE_LIMIT_WINDOW_MS * 2 - 1);
        assertTrue(FormHandler.RATE_LIMIT_MAP.containsKey("edge"));
        FormHandler.cleanupRateLimits(_now + FormHandler.RATE_LIMIT_WINDOW_MS * 2);
        assertFalse(FormHandler.RATE_LIMIT_MAP.containsKey("edge"));
    }
}
