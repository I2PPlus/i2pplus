package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * {@link TunnelControllerGroup#allocateServerThreads(int, int[], int)} budget
 * sharing across per-tunnel server handler pools.
 *
 * <p>Regression guard for the cross-destination starvation bug: a single
 * saturated server tunnel used to consume the entire shared handler pool (its
 * own threads plus the full queue), leaving other ports with no handler
 * threads and dropping their connections. The fix gives each server tunnel a
 * private pool whose size is a share of the global budget; this test pins the
 * pure allocation math (ceilings, floor, exact-total rounding, tiny-budget
 * splitting).
 *
 * @since 0.9.71+
 */
public class ServerHandlerAllocationTest {

    private static long total(int[] a) {
        long s = 0;
        for (int x : a) {s += x;}
        return s;
    }

    /** Ceilings that fit under the budget are granted verbatim. */
    @Test
    public void testWithinBudgetGrantsDesired() {
        int[] alloc = TunnelControllerGroup.allocateServerThreads(2048, new int[]{104, 104, 104}, 2);
        assertArrayEquals(new int[]{104, 104, 104}, alloc);
        assertEquals(312, total(alloc));
    }

    /** Ceilings above budget are cut proportionally, preserving floor and exact total. */
    @Test
    public void testOverBudgetSumsToBudgetPreservingFloor() {
        int[] alloc = TunnelControllerGroup.allocateServerThreads(100, new int[]{60, 60, 60}, 2);
        assertEquals(100, total(alloc));
        for (int x : alloc) {assertTrue("entry below floor: " + x, x >= 2);}
    }

    /** A single tunnel gets the whole budget (its cap clamped), no more. */
    @Test
    public void testSingleHostGetsEntireBudget() {
        int[] alloc = TunnelControllerGroup.allocateServerThreads(2048, new int[]{4096}, 2);
        assertArrayEquals(new int[]{2048}, alloc);
        int[] small = TunnelControllerGroup.allocateServerThreads(2048, new int[]{64}, 2);
        assertArrayEquals(new int[]{64}, small);
    }

    /** Zero or negative budget yields all zeros, matching "no handler threads yet". */
    @Test
    public void testZeroBudget() {
        assertArrayEquals(new int[]{0, 0}, TunnelControllerGroup.allocateServerThreads(0, new int[]{8, 8}, 2));
        assertArrayEquals(new int[]{0, 0}, TunnelControllerGroup.allocateServerThreads(-5, new int[]{8, 8}, 2));
    }

    /** No live tunnels yields no allocation. */
    @Test
    public void testEmpty() {
        assertArrayEquals(new int[]{}, TunnelControllerGroup.allocateServerThreads(2048, new int[]{}, 2));
    }

    /** Desired below the floor is raised to the floor. */
    @Test
    public void testDesiredBelowFloorRaisedToFloor() {
        int[] alloc = TunnelControllerGroup.allocateServerThreads(100, new int[]{1, 1, 50}, 2);
        assertArrayEquals(new int[]{2, 2, 50}, alloc);
    }

    /**
     * Budget too small to floor every tunnel splits evenly with at least one
     * thread per live tunnel (a soft global ceiling during tunnel startup).
     */
    @Test
    public void testBudgetBelowFloorTotalSplitsEvenly() {
        int[] alloc = TunnelControllerGroup.allocateServerThreads(4, new int[]{16, 16, 16}, 2);
        assertEquals(3, alloc.length);
        for (int x : alloc) {assertTrue("entry below 1: " + x, x >= 1);}
        int[] exact = TunnelControllerGroup.allocateServerThreads(6, new int[]{16, 16, 16}, 2);
        assertArrayEquals(new int[]{2, 2, 2}, exact);
    }

    /** Big-cap flood tunnel + small siblings: total exact, siblings keep floor. */
    @Test
    public void testCascadeIsolation() {
        int[] alloc = TunnelControllerGroup.allocateServerThreads(2048, new int[]{4096, 64, 64, 64}, 2);
        assertEquals(2048, total(alloc));
        for (int x : alloc) {assertTrue("entry below floor: " + x, x >= 2);}
        assertTrue("high-cap tunnel should get the lion's share", alloc[0] > alloc[1] * 10);
    }

    /** Large budgets/ceilings must not overflow integer math. */
    @Test
    public void testNoOverflowAtCeilings() {
        int[] alloc = TunnelControllerGroup.allocateServerThreads(4096, new int[]{4096, 4096}, 2);
        assertEquals(4096, total(alloc));
        for (int x : alloc) {assertTrue("entry below floor: " + x, x >= 2);}
    }

    /** Negative floor treated as zero. */
    @Test
    public void testNegativeFloor() {
        int[] alloc = TunnelControllerGroup.allocateServerThreads(10, new int[]{20, 20}, -1);
        assertArrayEquals(new int[]{5, 5}, alloc);
    }

    /** Cap eligibility: only 2..4096 counts as an override; everything else is "use default". */
    @Test
    public void testNormalizeThreadOverride() {
        assertEquals(2, TunnelControllerGroup.normalizeThreadOverride(2));
        assertEquals(4096, TunnelControllerGroup.normalizeThreadOverride(4096));
        assertEquals(64, TunnelControllerGroup.normalizeThreadOverride(64));
        assertEquals(-1, TunnelControllerGroup.normalizeThreadOverride(1));
        assertEquals(-1, TunnelControllerGroup.normalizeThreadOverride(4097));
        assertEquals(-1, TunnelControllerGroup.normalizeThreadOverride(-1));
        assertEquals(-1, TunnelControllerGroup.normalizeThreadOverride(0));
    }
}