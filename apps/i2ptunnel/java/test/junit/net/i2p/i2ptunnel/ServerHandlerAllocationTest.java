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

    /** Cap eligibility: only {@value TunnelControllerGroup#SERVER_HANDLER_FLOOR}..16384 counts as an override; everything else is "use default". */
    @Test
    public void testNormalizeThreadOverride() {
        assertEquals(-1, TunnelControllerGroup.normalizeThreadOverride(1));
        assertEquals(-1, TunnelControllerGroup.normalizeThreadOverride(2));
        assertEquals(-1, TunnelControllerGroup.normalizeThreadOverride(3));
        assertEquals(TunnelControllerGroup.SERVER_HANDLER_FLOOR, TunnelControllerGroup.normalizeThreadOverride(TunnelControllerGroup.SERVER_HANDLER_FLOOR));
        assertEquals(16384, TunnelControllerGroup.normalizeThreadOverride(16384));
        assertEquals(64, TunnelControllerGroup.normalizeThreadOverride(64));
        assertEquals(-1, TunnelControllerGroup.normalizeThreadOverride(16385));
        assertEquals(-1, TunnelControllerGroup.normalizeThreadOverride(-1));
        assertEquals(-1, TunnelControllerGroup.normalizeThreadOverride(0));
    }

    /**
     * The same allocator drives per-tunnel runner pools (client + server) from
     * the clientRunnerMax budget: floors, proportional cut, exact total.
     */
    @Test
    public void testRunnerPoolAllocationSharesBudget() {
        // Two tunnels with large ceilings under a modest budget: exact total, floors kept.
        int[] alloc = TunnelControllerGroup.allocateServerThreads(100, new int[]{80, 80, 80},
                                                                 TunnelControllerGroup.RUNNER_POOL_FLOOR);
        assertEquals(100, total(alloc));
        for (int x : alloc) {
            assertTrue("entry below runner floor: " + x, x >= TunnelControllerGroup.RUNNER_POOL_FLOOR);
        }
        // Ceilings that fit: granted verbatim (isolation reserved, not free-for-all).
        int[] fit = TunnelControllerGroup.allocateServerThreads(1024, new int[]{256, 256},
                                                               TunnelControllerGroup.RUNNER_POOL_FLOOR);
        assertArrayEquals(new int[]{256, 256}, fit);
    }

    // =====================================================================
    // Load-aware claims (claimServerHandlerShare)
    // =====================================================================

    /** Busy tunnel (queued or active work) claims its full ceiling. */
    @Test
    public void testBusyTunnelClaimsFullCeiling() {
        assertEquals(64, TunnelControllerGroup.claimServerHandlerShare(64, 4, 10, 0));
        assertEquals(64, TunnelControllerGroup.claimServerHandlerShare(64, 4, 0, 8));
        assertEquals(64, TunnelControllerGroup.claimServerHandlerShare(64, 4, 3, 5));
    }

    /** Idle tunnel claims only a base share so a busy sibling can take the budget. */
    @Test
    public void testIdleTunnelClaimsBaseShare() {
        assertEquals(32, TunnelControllerGroup.claimServerHandlerShare(64, 4, 0, 0));
        assertEquals(8, TunnelControllerGroup.claimServerHandlerShare(16, 4, 0, 0));
    }

    /** Claim never drops below the floor or rises above the ceiling. */
    @Test
    public void testClaimRespectsFloorAndCeiling() {
        // Cap below floor is raised to floor first.
        assertEquals(4, TunnelControllerGroup.claimServerHandlerShare(2, 4, 0, 0));
        assertEquals(4, TunnelControllerGroup.claimServerHandlerShare(4, 4, 0, 0));
        assertEquals(4, TunnelControllerGroup.claimServerHandlerShare(4, 4, 100, 100));
        // Negative floor treated as 0; idle base is still at least 0 and <= cap.
        int idle = TunnelControllerGroup.claimServerHandlerShare(8, -1, 0, 0);
        assertTrue("idle claim in range: " + idle, idle >= 0 && idle <= 8);
    }

    /**
     * Under a tight budget, a busy tunnel's full claim beats an idle sibling's
     * base share after proportional cutting — the flood port gets the threads.
     */
    @Test
    public void testLoadAwareClaimPrefersBusyUnderTightBudget() {
        int floor = TunnelControllerGroup.SERVER_HANDLER_FLOOR;
        int[] desired = {
            TunnelControllerGroup.claimServerHandlerShare(64, floor, 40, 12), // busy
            TunnelControllerGroup.claimServerHandlerShare(64, floor, 0, 0),   // idle
        };
        assertEquals(64, desired[0]);
        assertEquals(32, desired[1]);
        int[] alloc = TunnelControllerGroup.allocateServerThreads(48, desired, floor);
        assertEquals(48, total(alloc));
        assertTrue("busy tunnel should get more than idle: " + alloc[0] + " vs " + alloc[1],
                   alloc[0] > alloc[1]);
    }

    // =====================================================================
    // Runner-pool claims (claimRunnerShare) — shared clientRunnerMax budget
    // =====================================================================

    /** Busy runner pool claims full ceiling; idle claims half (runner floor). */
    @Test
    public void testClaimRunnerShareBusyVsIdle() {
        assertEquals(256, TunnelControllerGroup.claimRunnerShare(256, 40, 30));
        assertEquals(128, TunnelControllerGroup.claimRunnerShare(256, 0, 0));
        assertEquals(8, TunnelControllerGroup.claimRunnerShare(16, 0, 0));
        // Cap below floor is raised to the floor.
        assertEquals(TunnelControllerGroup.RUNNER_POOL_FLOOR,
                     TunnelControllerGroup.claimRunnerShare(2, 0, 0));
    }

    /**
     * Under a tight clientRunnerMax budget, a busy HTTP-proxy pool's full claim
     * beats idle siblings' base shares so the proxy is not starved into
     * executor-full sheds.
     */
    @Test
    public void testClaimRunnerSharePrefersBusyProxyUnderTightBudget() {
        int floor = TunnelControllerGroup.RUNNER_POOL_FLOOR;
        int[] desired = {
            TunnelControllerGroup.claimRunnerShare(256, 40, 30), // busy proxy
            TunnelControllerGroup.claimRunnerShare(256, 0, 0),   // idle client
            TunnelControllerGroup.claimRunnerShare(256, 0, 0),   // idle client
        };
        assertEquals(256, desired[0]);
        assertEquals(128, desired[1]);
        int[] alloc = TunnelControllerGroup.allocateServerThreads(300, desired, floor);
        assertEquals(300, total(alloc));
        assertTrue("busy proxy should get more than idle: " + alloc[0] + " vs " + alloc[1],
                   alloc[0] > alloc[1]);
    }
}
