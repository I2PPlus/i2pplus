package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import net.i2p.i2ptunnel.TunnelControllerGroup;

import org.junit.Test;

/**
 * Tests per-server handler-pool sizing from each pool's own demand.
 *
 * <p>The defect: handler threads came out of one global budget that was
 * divided across every open server tunnel, with a floor charged to each before
 * any load weighting. On a router with twenty eepsites the floor consumed the
 * budget, so the pool actually serving requests was left with a ceiling of
 * about a dozen threads and started refusing connections outright — while
 * nineteen idle eepsites held reservations they were not using, since
 * {@code allowCoreThreadTimeOut(true)} reclaims idle threads anyway.
 *
 * <p>Each pool is now sized from its own queue depth and active count, and the
 * global value is only a proportional safety trim.
 *
 * @since 0.9.71+
 */
public class ServerHandlerDemandTest {

    private static final int CAP = 168;   // per-tunnel ceiling on a 21-core host
    private static final int IDLE = TunnelControllerGroup.SERVER_HANDLER_IDLE;

    // ---- per-pool demand sizing ----

    @Test
    public void dormantPoolCostsOneThread() {
        assertEquals(1, TunnelControllerGroup.serverThreadsForDemand(0, 0, CAP));
        assertEquals(IDLE, TunnelControllerGroup.serverThreadsForDemand(0, 0, CAP));
    }

    @Test
    public void busyPoolGetsItsLoadPlusHeadroom() {
        // The reported case: 84 queued with 12 active refused connections.
        assertEquals("84 queued + 12 active + 1 headroom",
                     97, TunnelControllerGroup.serverThreadsForDemand(84, 12, CAP));
    }

    @Test
    public void sizingGrowsMonotonicallyWithLoad() {
        int prev = 0;
        int[] loads = {0, 1, 2, 5, 10, 20, 50, 100, 167, 400};
        for (int load : loads) {
            int t = TunnelControllerGroup.serverThreadsForDemand(load, 0, CAP);
            assertTrue("load " + load + " -> " + t + " must not shrink from " + prev, t >= prev);
            prev = t;
        }
    }

    @Test
    public void neverExceedsThePerTunnelCeiling() {
        int[] caps = {1, 2, 4, 12, 168, 4096};
        int[] loads = {0, 1, 50, 1000, 100000};
        for (int cap : caps) {
            for (int load : loads) {
                int t = TunnelControllerGroup.serverThreadsForDemand(load, load, cap);
                assertTrue("cap " + cap + " load " + load + " -> " + t, t >= 1 && t <= cap);
            }
        }
    }

    @Test
    public void aSaturatedPoolCanStillAcceptOneMore() {
        // Exactly full plus headroom, so the connection being admitted is not
        // the one that gets refused.
        int t = TunnelControllerGroup.serverThreadsForDemand(12, 12, CAP);
        assertTrue("a full pool must grow past its own load: " + t, t > 12);
    }

    @Test
    public void negativeInputsAreTreatedAsNoLoad() {
        assertEquals(IDLE, TunnelControllerGroup.serverThreadsForDemand(-5, -5, CAP));
    }

    @Test
    public void degenerateCapStillYieldsOneThread() {
        assertEquals(1, TunnelControllerGroup.serverThreadsForDemand(50, 50, 0));
        assertEquals(1, TunnelControllerGroup.serverThreadsForDemand(50, 50, -3));
    }

    // ---- independence: the reported scenario ----

    @Test
    public void busyServerIsNoLongerStarvedByIdleOnes() {
        int[] pools = new int[20];
        for (int i = 0; i < 19; i++) {
            pools[i] = TunnelControllerGroup.serverThreadsForDemand(0, 0, CAP);
        }
        // The eepsite proxy under load.
        pools[19] = TunnelControllerGroup.serverThreadsForDemand(84, 12, CAP);
        int[] alloc = TunnelControllerGroup.scaleDemandsToCeiling(pools, 96);
        int total = 0;
        for (int a : alloc) {total += a;}
        assertEquals("allocation must fit the ceiling", true, total <= 96);
        assertTrue("the busy pool must not be cut to a handful of threads: " + alloc[19],
                   alloc[19] >= 12);
        assertEquals("19 idle pools must not consume the budget: " + total,
                     true, 19 * IDLE < 96);
    }

    @Test
    public void idlePoolsDoNotConsumeTheCeiling() {
        int[] idle = new int[19];
        java.util.Arrays.fill(idle, TunnelControllerGroup.serverThreadsForDemand(0, 0, CAP));
        int[] alloc = TunnelControllerGroup.scaleDemandsToCeiling(idle, 96);
        int total = 0;
        for (int a : alloc) {total += a;}
        assertEquals("19 dormant pools must total well under the ceiling",
                     19 * IDLE, total);
    }

    // ---- the safety trim ----

    @Test
    public void trimIsProportionalToDemand() {
        int[] demand = {100, 10, 1};
        int[] alloc = TunnelControllerGroup.scaleDemandsToCeiling(demand, 60);
        assertEquals(111, demand[0] + demand[1] + demand[2]);
        assertTrue("busiest keeps the largest share: " + alloc[0], alloc[0] > alloc[1]);
        assertTrue(alloc[1] > alloc[2]);
    }

    @Test
    public void trimNeverDropsBelowTheIdleMinimum() {
        int[] demand = {50, 1, 1, 1};
        int[] alloc = TunnelControllerGroup.scaleDemandsToCeiling(demand, 4);
        for (int a : alloc) {
            assertTrue("pool got " + a, a >= IDLE);
        }
    }

    @Test
    public void demandUnderTheCeilingIsUntouched() {
        int[] demand = {1, 2, 3};
        assertArrayEquals(demand, TunnelControllerGroup.scaleDemandsToCeiling(demand, 100));
    }

    @Test
    public void zeroOrNegativeCeilingStillYieldsUsablePools() {
        int[] demand = {10, 10};
        int[] alloc = TunnelControllerGroup.scaleDemandsToCeiling(demand, 0);
        for (int a : alloc) {assertTrue(a >= IDLE);}
    }

    @Test
    public void emptyInputIsHandled() {
        assertEquals(0, TunnelControllerGroup.scaleDemandsToCeiling(new int[0], 50).length);
        assertEquals(0, TunnelControllerGroup.scaleDemandsToCeiling(null, 50).length);
    }
}
