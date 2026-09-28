package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Adaptive build timeout arithmetic in {@link BuildExecutor#computeAdaptiveTimeout}.
 *
 * The per-config inputs are tunnel length and direction; CPU load and the RTT
 * floor are hoisted once per scan, so the arithmetic must be identical whether
 * they arrive pre-fetched or are looked up per config.
 *
 * @since 0.9.71+
 */
public class AdaptiveTimeoutTest {

    private static final long BASE = 20 * 1000L;
    private static final long CEILING = 45 * 1000L;

    /** lengths 0..5: lengths above 3 add 5s per extra hop */
    private static final int[] LENGTHS = {0, 1, 2, 3, 4, 5};
    /** tiers bracketing the >80 (+2s) and >90 (+3s) boundaries */
    private static final int[] CPU_LOADS = {0, 80, 81, 90, 91};
    private static final boolean[] DIRECTIONS = {true, false};

    /**
     * Verbatim copy of the pre-hoisting inline logic, used as the oracle: the
     * extracted arithmetic must be bit-identical to it for every combination
     * of length, direction, load tier and floor.
     */
    private static long referenceAdaptiveTimeout(long baseTimeout, int length, boolean isInbound,
                                                 int cpuLoad, long rttFloor) {
        long base = baseTimeout;
        if (length > 3) {
            base += (long) (length - 3) * 5 * 1000L;
        }
        if (cpuLoad > 90) {
            base += 3 * 1000;
        } else if (cpuLoad > 80) {
            base += 2 * 1000;
        }
        if (!isInbound) {
            base += 8 * 1000L;
        }
        if (rttFloor > base) {
            base = rttFloor;
        }
        return Math.min(base, 45 * 1000L);
    }

    @Test
    public void testMatchesPreHoistingLogicAcrossMatrix() {
        long[] floors = {0, 5000L, 20000L, 30000L, 60000L};
        for (int length : LENGTHS) {
            for (boolean inbound : DIRECTIONS) {
                for (int cpuLoad : CPU_LOADS) {
                    for (long rttFloor : floors) {
                        assertEquals("length=" + length + " inbound=" + inbound +
                                     " cpuLoad=" + cpuLoad + " rttFloor=" + rttFloor,
                                     referenceAdaptiveTimeout(BASE, length, inbound, cpuLoad, rttFloor),
                                     BuildExecutor.computeAdaptiveTimeout(BASE, length, inbound, cpuLoad, rttFloor));
                    }
                }
            }
        }
    }

    /** Hoisting the invariant inputs must not change the result for any base. */
    @Test
    public void testMatchesPreHoistingLogicAcrossBaseTimeouts() {
        long[] bases = {0, 5000L, 10000L, 20000L, 45000L, 60000L};
        for (long base : bases) {
            for (int length : LENGTHS) {
                for (boolean inbound : DIRECTIONS) {
                    for (int cpuLoad : CPU_LOADS) {
                        assertEquals("base=" + base + " length=" + length + " inbound=" + inbound +
                                     " cpuLoad=" + cpuLoad,
                                     referenceAdaptiveTimeout(base, length, inbound, cpuLoad, 0),
                                     BuildExecutor.computeAdaptiveTimeout(base, length, inbound, cpuLoad, 0));
                    }
                }
            }
        }
    }

    /** Lengths at or below the 3-hop baseline add nothing. */
    @Test
    public void testShortTunnelsUnadjusted() {
        for (int length = 0; length <= 3; length++) {
            assertEquals(BASE, BuildExecutor.computeAdaptiveTimeout(BASE, length, true, 0, 0));
        }
    }

    /** Each hop past the baseline adds exactly 5s. */
    @Test
    public void testLengthTermIsFiveSecondsPerExtraHop() {
        assertEquals(BASE, BuildExecutor.computeAdaptiveTimeout(BASE, 3, true, 0, 0));
        assertEquals(BASE + 5 * 1000L, BuildExecutor.computeAdaptiveTimeout(BASE, 4, true, 0, 0));
        assertEquals(BASE + 10 * 1000L, BuildExecutor.computeAdaptiveTimeout(BASE, 5, true, 0, 0));
    }

    /** Load tiers are exclusive: >90 takes +3s and does not also take +2s. */
    @Test
    public void testCpuLoadTiers() {
        assertEquals(BASE, BuildExecutor.computeAdaptiveTimeout(BASE, 3, true, 0, 0));
        assertEquals(BASE, BuildExecutor.computeAdaptiveTimeout(BASE, 3, true, 80, 0));
        assertEquals(BASE + 2 * 1000L, BuildExecutor.computeAdaptiveTimeout(BASE, 3, true, 81, 0));
        assertEquals(BASE + 2 * 1000L, BuildExecutor.computeAdaptiveTimeout(BASE, 3, true, 90, 0));
        assertEquals(BASE + 3 * 1000L, BuildExecutor.computeAdaptiveTimeout(BASE, 3, true, 91, 0));
    }

    /** Outbound reply path costs 8s more than the same inbound build. */
    @Test
    public void testOutboundSurcharge() {
        long inbound = BuildExecutor.computeAdaptiveTimeout(BASE, 3, true, 0, 0);
        long outbound = BuildExecutor.computeAdaptiveTimeout(BASE, 3, false, 0, 0);
        assertEquals(8 * 1000L, outbound - inbound);
    }

    /** A floor at or below the computed timeout changes nothing. */
    @Test
    public void testFloorBelowTimeoutIsIgnored() {
        assertEquals(BASE + 8 * 1000L, BuildExecutor.computeAdaptiveTimeout(BASE, 3, false, 0, BASE + 8 * 1000L));
        assertEquals(BASE + 8 * 1000L, BuildExecutor.computeAdaptiveTimeout(BASE, 3, false, 0, BASE + 7 * 1000L));
    }

    /** A larger floor wins, including over the length, load and outbound terms. */
    @Test
    public void testFloorWinsWhenLarger() {
        // 20s base + 10s length + 3s load + 8s outbound = 41s already exceeds
        // the 33s floor, so the floor cannot be observed here
        assertEquals(41 * 1000L, BuildExecutor.computeAdaptiveTimeout(BASE, 5, false, 91, 33 * 1000L));
        // 20s base + 8s outbound = 28s; a 30s floor raises it
        assertEquals(30 * 1000L, BuildExecutor.computeAdaptiveTimeout(BASE, 3, false, 0, 30 * 1000L));
    }

    /** The 45s ceiling clamps both the computed value and an oversized floor. */
    @Test
    public void testSafetyCeiling() {
        // 30s base + 10s length + 3s load + 8s outbound = 51s, clamped
        assertEquals(CEILING, BuildExecutor.computeAdaptiveTimeout(30 * 1000L, 5, false, 91, 0));
        assertEquals(CEILING, BuildExecutor.computeAdaptiveTimeout(BASE, 3, true, 0, 60 * 1000L));
        assertEquals(CEILING, BuildExecutor.computeAdaptiveTimeout(60 * 1000L, 0, true, 0, 0));
    }

    /** Every combination of the documented input ranges stays within the ceiling. */
    @Test
    public void testCeilingHoldsAcrossMatrix() {
        for (int length : LENGTHS) {
            for (boolean inbound : DIRECTIONS) {
                for (int cpuLoad : CPU_LOADS) {
                    for (long rttFloor : new long[]{0, 20000L, 60000L}) {
                        long result = BuildExecutor.computeAdaptiveTimeout(BASE, length, inbound, cpuLoad, rttFloor);
                        assertTrue("exceeded ceiling: " + result, result <= CEILING);
                        assertTrue("floor ignored: " + result, result >= Math.min(rttFloor, CEILING));
                    }
                }
            }
        }
    }
}
