package net.i2p.router.web;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the console nonce lifetime that decides whether a button on a rendered
 * page still works.
 *
 * <p>A nonce is single-use, and the queue that remembers issued nonces is
 * bounded, so a control breaks the moment its nonce is used by something else
 * or leaves the queue. The console sidebar re-renders every
 * {@code routerconsole.summaryRefresh} seconds (3 by default), issuing a nonce
 * for the restart form and, when shown, the reseed and update forms — 20 to 60
 * nonces a minute. With the queue sized for a few form submits, and eviction
 * by count, a page's own nonce was gone after a couple of minutes of sidebar
 * churn, and a rejected nonce is a silent no-op at every call site: the log
 * pages' Clear logs link and the sidebar Restart button simply stopped doing
 * anything while a user read the page they clicked it on.
 *
 * <p>So the age bound, not the count bound, has to govern, and the count bound
 * has to sit far above what the age bound leaves room for. Both are pinned
 * here, along with the single-use and preserve semantics the call sites rely
 * on.
 *
 * @since 0.9.72+
 */
public class ConsoleNonceQueueTest {

    /** The window a rendered page's controls are good for. */
    private static final long MAX_AGE = ConsoleNonceQueue.MAX_AGE_MS;

    /** One sidebar refresh interval, the default {@code summaryRefresh}. */
    private static final long REFRESH_MS = 3000;

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the class carries no per-test state, only the clock the pure
     * queue is driven with.
     *
     * @since 0.9.72+
     */
    public ConsoleNonceQueueTest() {}

    /**
     * A clock starting at a fixed epoch, so the age policy is exercised without
     * sleeping. Not a real wall clock — the queue takes {@code nowMs} as a
     * parameter for exactly that reason.
     *
     * @param offsetMs milliseconds after the fixed epoch
     * @return the instant to pass as {@code nowMs}
     */
    private static long at(long offsetMs) {
        return 1_000_000L + offsetMs;
    }

    // ---------- a nonce is good for one use ----------

    /** An issued nonce validates, and only once. */
    @Test
    public void testNonceIsSingleUse() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CN1", at(0));
        assertTrue(q.validate("CN1", at(0), false));
        assertFalse("the second use of the same nonce must fail",
                    q.validate("CN1", at(1000), false));
    }

    /**
     * Two controls that share one nonce leave the second dead once the first is
     * used — the shape of the reported bug, where the log pages' Clear logs
     * link and the sidebar restart form were handed the same nonce.
     */
    @Test
    public void testSharedNonceKillsTheSecondControl() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CNshared", at(0));
        assertTrue(q.validate("CNshared", at(0), false));
        assertFalse(q.validate("CNshared", at(1000), false));
    }

    /**
     * The fix for that: one nonce per control, and using one leaves the other
     * working.
     */
    @Test
    public void testPerControlNoncesSurviveEachOther() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CNclear", at(0));
        q.add("CNrestart", at(0));
        assertTrue(q.validate("CNclear", at(0), false));
        assertTrue(q.validate("CNrestart", at(1000), false));
    }

    /** A preserved nonce keeps validating, for an early read-only JSP check. */
    @Test
    public void testPreserveDoesNotConsume() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CN1", at(0));
        assertTrue(q.validate("CN1", at(0), true));
        assertTrue(q.validate("CN1", at(1000), true));
        assertTrue("the handler may still consume it", q.validate("CN1", at(2000), false));
        assertFalse(q.validate("CN1", at(3000), false));
    }

    // ---------- what never validates ----------

    /** A nonce the queue never issued is refused. */
    @Test
    public void testUnknownNonceRefused() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CN1", at(0));
        assertFalse(q.validate("CN2", at(0), false));
    }

    /** An empty queue refuses anything, in both modes. */
    @Test
    public void testEmptyQueueRefuses() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        assertFalse(q.validate("CN1", at(0), false));
        assertFalse(q.validate("CN1", at(0), true));
    }

    /** A null nonce is refused, in both modes. */
    @Test
    public void testNullNonceRefused() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CN1", at(0));
        assertFalse(q.validate(null, at(0), false));
        assertFalse(q.validate(null, at(0), true));
    }

    // ---------- the age bound ----------

    /** The boundary is exclusive: at exactly the window the nonce is still good. */
    @Test
    public void testValidUpToTheAgeBound() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CN1", at(0));
        assertTrue(q.validate("CN1", at(MAX_AGE), false));
    }

    /** Past the window it is gone, and prune reports dropping it. */
    @Test
    public void testExpiredPastTheAgeBound() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CN1", at(0));
        assertEquals(1, q.prune(at(MAX_AGE + 1)));
        assertEquals(0, q.size());
        assertFalse(q.validate("CN1", at(MAX_AGE + 1), false));
    }

    /** The window is long enough to read a log page, not seconds. */
    @Test
    public void testAgeWindowIsMinutes() {
        assertEquals(30 * 60 * 1000L, MAX_AGE);
    }

    /** A clock that moved backwards cannot expire a nonce. */
    @Test
    public void testClockSkewDoesNotExpire() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CN1", at(10_000));
        assertTrue(q.validate("CN1", at(0), false));
    }

    /** Pruning drops a run of expired nonces and stops at the live ones. */
    @Test
    public void testPruneDropsOnlyExpired() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CNold1", at(0));
        q.add("CNold2", at(1000));
        assertEquals(2, q.size());
        // Issuing a nonce prunes the expired ones first, so the queue stays
        // bounded during the sidebar churn that keeps issuing them.
        q.add("CNnew", at(MAX_AGE + 1000));
        assertEquals(2, q.size());
        assertEquals(1, q.prune(at(MAX_AGE + 1500)));
        assertEquals(1, q.size());
        assertTrue(q.validate("CNnew", at(MAX_AGE + 1500), false));
    }

    // ---------- the size bound ----------

    /**
     * The size ceiling is far above what the age window leaves room for at the
     * default refresh rate, so the age bound is the one that governs and
     * sidebar churn cannot evict a page's nonce early.
     */
    @Test
    public void testSizeCeilingOutlastsTheAgeWindow() {
        long noncesPerMinute = 60_000 / REFRESH_MS * 3;
        long noncesInWindow = noncesPerMinute * (MAX_AGE / 60_000);
        assertTrue("size ceiling " + ConsoleNonceQueue.MAX_SIZE +
                   " must outlast " + noncesInWindow + " nonces in the age window",
                   ConsoleNonceQueue.MAX_SIZE > noncesInWindow);
    }

    /** Past the ceiling the oldest go, and the newest stay valid. */
    @Test
    public void testSizeCeilingDropsTheOldest() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        int over = ConsoleNonceQueue.MAX_SIZE + 250;
        for (int i = 0; i < over; i++) {
            q.add("CN" + i, at(0));
        }
        assertEquals(ConsoleNonceQueue.MAX_SIZE, q.size());
        assertTrue(q.validate("CN" + (over - 1), at(0), false));
        assertFalse(q.validate("CN0", at(0), false));
    }

    // ---------- the reported failure ----------

    /**
     * The measured case: open a log page, read it for ten minutes while the
     * sidebar refreshes every 3s and issues nonces, then click Clear logs. The
     * page's nonce has to still be good, and the sidebar's own form nonce too.
     */
    @Test
    public void testPageNonceSurvivesTenMinutesOfSidebarChurn() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CNpage", at(0));
        q.add("CNform", at(0));
        long now = 0;
        String lastSidebar = "CNform";
        for (int i = 0; i < 200; i++) {
            now += REFRESH_MS;
            // Each render issues a restart nonce, plus reseed and update when shown.
            for (int j = 0; j < 3; j++) {
                lastSidebar = "CNside" + i + "_" + j;
                q.add(lastSidebar, at(now));
            }
        }
        assertTrue("the page's Clear logs link must still work",
                   q.validate("CNpage", at(now), false));
        assertTrue("the sidebar's current form nonce must work",
                   q.validate(lastSidebar, at(now), false));
    }

    /**
     * The same ten minutes under the old count bound: 50 slots fill in 250
     * sidebar renders, so the page nonce is gone long before the user clicks.
     * Pinned as a comment on the arithmetic the fix replaced.
     */
    @Test
    public void testOldCountBoundEvictedThePageNonce() {
        int oldQueueSize = 50;
        int noncesPerRender = 3;
        long rendersToEvict = oldQueueSize / noncesPerRender;
        long secondsToEvict = rendersToEvict * REFRESH_MS / 1000;
        assertTrue("the old bound evicted a page nonce in " + secondsToEvict + "s",
                   secondsToEvict < 300);
    }

    // ---------- duplicates ----------

    /**
     * A repeated nonce value is remembered twice and consumed one occurrence at
     * a time, matching the {@code removeLastOccurrence} semantics the call
     * sites had. Nonces are random, so this only pins the behaviour.
     */
    @Test
    public void testDuplicateValuesConsumedOneAtATime() {
        ConsoleNonceQueue q = new ConsoleNonceQueue();
        q.add("CNdup", at(0));
        q.add("CNdup", at(1000));
        assertTrue(q.validate("CNdup", at(2000), false));
        assertTrue(q.validate("CNdup", at(2000), false));
        assertFalse(q.validate("CNdup", at(2000), false));
    }
}
