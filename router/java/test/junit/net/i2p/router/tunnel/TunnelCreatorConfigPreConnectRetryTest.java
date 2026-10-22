package net.i2p.router.tunnel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

/**
 * The pre-connect retry budget for one tunnel build. BuildRequestor's first-hop
 * fallback re-dispatches the whole build, and nothing bounded it, so a dead first
 * hop retried forever - each pass costing a full request timeout and keeping the
 * tunnel pinned in the pool's in-progress set.
 *
 * @since 0.9.72+
 */
public class TunnelCreatorConfigPreConnectRetryTest {

    private static final int MAX = 1;
    private static final long WINDOW = 120_000L;
    private static final long T0 = 1_000_000L;

    private static RouterContext ctx;

    @BeforeClass
    public static void setUpClass() {
        ctx = RouterTestHelper.newContext();
    }

    /** TunnelCreatorConfig is abstract with no abstract members, so a bare subclass will do. */
    private static TunnelCreatorConfig cfg() {
        return new TunnelCreatorConfig(ctx, 3, true) {};
    }

    /** The first retry in a fresh window is always granted. */
    @Test
    public void testFirstRetryGranted() {
        assertTrue(cfg().claimPreConnectRetry(T0, MAX, WINDOW));
    }

    /** The second retry inside the same window is refused. */
    @Test
    public void testSecondRetryInWindowRefused() {
        TunnelCreatorConfig c = cfg();
        assertTrue(c.claimPreConnectRetry(T0, MAX, WINDOW));
        assertFalse(c.claimPreConnectRetry(T0 + 1000L, MAX, WINDOW));
        assertFalse(c.claimPreConnectRetry(T0 + WINDOW - 1, MAX, WINDOW));
    }

    /** Once the window has passed the budget is available again. */
    @Test
    public void testBudgetRestoresAfterWindow() {
        TunnelCreatorConfig c = cfg();
        assertTrue(c.claimPreConnectRetry(T0, MAX, WINDOW));
        assertFalse(c.claimPreConnectRetry(T0 + 10_000L, MAX, WINDOW));
        assertTrue("a retry a full window later must be granted again",
                   c.claimPreConnectRetry(T0 + WINDOW, MAX, WINDOW));
    }

    /** maxRetries of 0 disables retrying entirely. */
    @Test
    public void testZeroMaxDisablesRetry() {
        assertFalse(cfg().claimPreConnectRetry(T0, 0, WINDOW));
    }

    /** A larger budget grants exactly that many, no more. */
    @Test
    public void testGrantsExactlyMax() {
        for (int max = 1; max <= 5; max++) {
            TunnelCreatorConfig c = cfg();
            for (int i = 0; i < max; i++)
                assertTrue("retry " + i + " of " + max, c.claimPreConnectRetry(T0 + i, max, WINDOW));
            assertFalse("retry " + max + " of " + max + " must be refused",
                        c.claimPreConnectRetry(T0 + max, max, WINDOW));
        }
    }

    /**
     * The runaway case this exists for: a dead first hop retries on every request
     * timeout. Over a long burst only the windowed budget is ever spent.
     */
    @Test
    public void testRunawayLoopIsBounded() {
        TunnelCreatorConfig c = cfg();
        int granted = 0;
        long now = T0;
        for (int i = 0; i < 100; i++) {
            if (c.claimPreConnectRetry(now, MAX, WINDOW))
                granted++;
            now += 15_000L;   // one request timeout per pass
        }
        // 100 passes at 15 s spans 8 windows, so at most 8 grants - never 100.
        assertTrue("granted " + granted, granted > 0);
        assertTrue("granted " + granted, granted <= 100 / (WINDOW / 15_000L) + 1);
    }

    /** A clock step backwards must not hand out extra retries. */
    @Test
    public void testClockBackwardsDoesNotGrantExtra() {
        TunnelCreatorConfig c = cfg();
        assertTrue(c.claimPreConnectRetry(T0, MAX, WINDOW));
        assertFalse(c.claimPreConnectRetry(T0 - 500_000L, MAX, WINDOW));
        assertFalse(c.claimPreConnectRetry(T0 + 1000L, MAX, WINDOW));
    }

    /** Budgets are per tunnel config, never shared. */
    @Test
    public void testBudgetsArePerConfig() {
        TunnelCreatorConfig a = cfg();
        TunnelCreatorConfig b = cfg();
        assertTrue(a.claimPreConnectRetry(T0, MAX, WINDOW));
        assertFalse(a.claimPreConnectRetry(T0, MAX, WINDOW));
        assertTrue("a different tunnel must have its own budget",
                   b.claimPreConnectRetry(T0, MAX, WINDOW));
    }

    /** Boundary: exactly windowMs later the window rolls over. */
    @Test
    public void testWindowBoundaryIsInclusive() {
        TunnelCreatorConfig c = cfg();
        assertTrue(c.claimPreConnectRetry(T0, MAX, WINDOW));
        assertFalse(c.claimPreConnectRetry(T0 + WINDOW - 1, MAX, WINDOW));
        assertTrue(c.claimPreConnectRetry(T0 + WINDOW, MAX, WINDOW));
    }
}
