package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests how the Tuner divides the I2PTunnel server handler-thread budget across
 * the server tunnels that have to share it.
 *
 * <p>{@code i2ptunnel.serverHandler.threads} is the summed ceiling for every
 * server tunnel's private handler pool; {@code i2ptunnel.server.threads} is the
 * ceiling for one of them. Both used to be clamped to the same
 * memory-derived thread count, so on a 4 GiB heap each tunnel could be sized at
 * the entire 2,048-thread budget and the ceilings summed to N times it. A live
 * router showed the consequence directly: both parameters walked 2,044 to 2,048
 * and back in lockstep, on the same cycle, because both read the same aggregate
 * queue depth - the per-tunnel ceiling had stopped being a ceiling, and the only
 * thing holding the total down was a proportional trim applied after the pools
 * had already been sized.
 *
 * @since 0.9.72+
 */
public class TunerServerThreadShareTest {

    /** The summed budget a 4 GiB heap derives, one thread per 2 MB. */
    private static final int BUDGET_4G = 2048;
    /** The parameter's own hard ceiling. */
    private static final int HARD_MAX = 4096;
    /** The parameter's floor, shared with the global budget param. */
    private static final int MIN = 2;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class holds no per-test state, only the budget and bounds the
     * decisions are called with.
     *
     * @since 0.9.72+
     */
    public TunerServerThreadShareTest() {}

    /**
     * One server tunnel owns the whole budget: there is nothing to divide it
     * between, and shrinking it would cost headroom for no benefit.
     */
    @Test
    public void testSinglePoolGetsTheWholeBudget() {
        assertEquals(BUDGET_4G, Tuner.perTunnelThreadCap(BUDGET_4G, 1, MIN, HARD_MAX));
    }

    /**
     * No pools yet, or a tunnel-count lookup that failed and returned -1, leaves
     * the budget undivided: the Tuner must not shrink a ceiling because it could
     * not count, and a router with no server tunnels has nothing to protect.
     */
    @Test
    public void testUnknownPoolCountLeavesTheBudget() {
        assertEquals(BUDGET_4G, Tuner.perTunnelThreadCap(BUDGET_4G, 0, MIN, HARD_MAX));
        assertEquals(BUDGET_4G, Tuner.perTunnelThreadCap(BUDGET_4G, -1, MIN, HARD_MAX));
        int none = Integer.MIN_VALUE;
        assertEquals(BUDGET_4G, Tuner.perTunnelThreadCap(BUDGET_4G, none, MIN, HARD_MAX));
    }

    /**
     * Each tunnel's ceiling is the budget divided by the number of tunnels, so
     * the ceilings sum to the budget rather than each reaching all of it.
     */
    @Test
    public void testBudgetIsDividedAcrossPools() {
        assertEquals(BUDGET_4G / 2, Tuner.perTunnelThreadCap(BUDGET_4G, 2, MIN, HARD_MAX));
        assertEquals(BUDGET_4G / 4, Tuner.perTunnelThreadCap(BUDGET_4G, 4, MIN, HARD_MAX));
        assertEquals(BUDGET_4G / 8, Tuner.perTunnelThreadCap(BUDGET_4G, 8, MIN, HARD_MAX));
    }

    /**
     * The floor wins when the division would starve a tunnel: a router running
     * more pools than it has threads to spare still gives every one of them
     * enough to serve a connection.
     */
    @Test
    public void testFloorBeatsTheDivision() {
        assertEquals(MIN, Tuner.perTunnelThreadCap(BUDGET_4G, BUDGET_4G, MIN, HARD_MAX));
        assertEquals(MIN, Tuner.perTunnelThreadCap(BUDGET_4G, BUDGET_4G * 8, MIN, HARD_MAX));
        assertEquals(64, Tuner.perTunnelThreadCap(BUDGET_4G, BUDGET_4G, 64, HARD_MAX));
    }

    /**
     * The parameter's own ceiling still applies on top of the share, so a
     * one-tunnel router cannot be pushed past what the parameter allows.
     */
    @Test
    public void testHardMaxStillBoundsTheShare() {
        assertEquals(HARD_MAX, Tuner.perTunnelThreadCap(16384, 1, MIN, HARD_MAX));
        assertEquals(HARD_MAX, Tuner.perTunnelThreadCap(16384, 2, MIN, HARD_MAX));
        assertEquals(4096, Tuner.perTunnelThreadCap(16384, 4, MIN, HARD_MAX));
    }

    /**
     * The invariant the old pair broke: for any number of tunnels above one, the
     * ceilings cannot sum past the budget they share. Integer division leaves a
     * remainder unallocated, which is the safe direction.
     */
    @Test
    public void testCeilingsNeverSumPastTheBudget() {
        for (int pools = 1; pools <= 64; pools++) {
            int cap = Tuner.perTunnelThreadCap(BUDGET_4G, pools, MIN, HARD_MAX);
            long sum = (long) cap * pools;
            if (pools == 1) {
                assertTrue("single pool " + cap, cap <= BUDGET_4G);
            } else {
                assertTrue(pools + " pools x " + cap, sum <= BUDGET_4G);
            }
        }
    }

    /**
     * The live case, measured on a 4 GiB heap with six server tunnels running:
     * the ceiling falls from the whole 2,048 to 341, and the router's busiest
     * moment at the time was 36 handler threads active in total, so the share
     * costs nothing at the load it was tuned for while removing the ability of
     * one flooded port to claim the entire budget.
     */
    @Test
    public void testMeasuredSixTunnelRouterKeepsHeadroom() {
        int cap = Tuner.perTunnelThreadCap(BUDGET_4G, 6, MIN, HARD_MAX);
        assertEquals(341, cap);
        assertTrue("headroom over the measured 36 active", cap > 36);
        assertEquals(2046, cap * 6);
    }
}
