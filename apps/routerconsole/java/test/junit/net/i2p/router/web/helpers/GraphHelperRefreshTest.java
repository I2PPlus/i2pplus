package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for {@link GraphHelper#effectiveRefresh(int, boolean)}.
 *
 * <p>The graphs page used to persist a suppressed refresh delay into the config whenever graph
 * generation was found unavailable. That survived restarts, so the page froze on its first paint
 * and never refreshed again even once graphing recovered. These tests pin the rule that the
 * suppression is per-render only.
 */
public class GraphHelperRefreshTest {

    /** A normal configured delay passes through untouched. */
    @Test
    public void testNormalDelayPassesThrough() {
        assertEquals(60, GraphHelper.effectiveRefresh(60, false));
    }

    /** Any delay, including an unusual one, is preserved while graphing works. */
    @Test
    public void testAnyDelayPreservedWhenEnabled() {
        assertEquals(5, GraphHelper.effectiveRefresh(5, false));
        assertEquals(3600, GraphHelper.effectiveRefresh(3600, false));
    }

    /** Refreshing is suppressed for the render while graphing is unavailable. */
    @Test
    public void testDisabledSuppressesRefresh() {
        assertEquals(-1, GraphHelper.effectiveRefresh(60, true));
    }

    /**
     * The regression: a suppressed render must not become the saved value, otherwise the next
     * render inherits -1 and the graphs never refresh again even after graphing recovers.
     */
    @Test
    public void testSuppressionIsNotSticky() {
        int saved = 60;
        int duringOutage = GraphHelper.effectiveRefresh(saved, true);
        assertEquals(-1, duringOutage);
        // the configured value is untouched, so once graphing returns the page refreshes again
        assertEquals("saved delay must not be modified", 60, saved);
        assertEquals(saved, GraphHelper.effectiveRefresh(saved, false));
    }

    /** An already-negative saved value stays negative rather than being reinterpreted. */
    @Test
    public void testNegativeSavedValueStaysNegative() {
        assertEquals(-1, GraphHelper.effectiveRefresh(-1, false));
    }
}
