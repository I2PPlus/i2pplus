package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests {@link Connection#computeWindowCeiling(int, float, int, int, int)},
 * the pure decision helper behind the per-stream window ceiling: the ceiling
 * is {@code min(absMax, max(globalMax, floor, 125% x bwe x rtt))}, falling
 * back to {@code min(absMax, max(globalMax, floor))} when no bandwidth sample
 * exists yet.
 *
 * <p>The invariants that matter: the Tuner global is the FLOOR of the cap, so
 * a stream can always ramp to the global base (the regression this suite
 * pins — a measured-goodput BDP term binding downward pinned streams at the
 * 128-message floor in an absorbing state and capped a fast path at ~85
 * KB/s); the per-stream BDP may lift the ceiling above the global toward the
 * absolute cap once the path proves capacity; and no combination of inputs
 * ever exceeds {@code ABSOLUTE_MAX_WINDOW}.
 *
 * @since 0.9.71+
 */
public class ConnectionWindowCeilingTest {

    private static final int GLOBAL = 4096;
    private static final int FLOOR = 128;
    private static final int ABS = Connection.ABSOLUTE_MAX_WINDOW;

    /** No estimate yet (NaN / zero / negative bwe, or non-positive RTT):
     *  the ceiling falls back to the global floor so an uncalibrated stream
     *  ramps exactly as before. */
    @Test
    public void testNoEstimateFallsBackToGlobal() {
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, Float.NaN, 400, FLOOR, ABS));
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, 0.0f, 400, FLOOR, ABS));
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, -1.0f, 400, FLOOR, ABS));
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, 1.5f, 0, FLOOR, ABS));
    }

    /** The regression: a tiny-but-valid (or absent) estimate must NOT pin the
     *  ceiling at the initial-window floor when the Tuner global is higher.
     *  The old {@code min(global, max(floor, bdp))} form absorbed streams at
     *  128 messages — measured goodput could never grow past the pinned
     *  window, so the pinned ceiling became a stable fixed point. */
    @Test
    public void testNoAbsorbingPinBelowGlobal() {
        // the live failure: global 768 (one Tuner shrink), near-zero BDP
        assertEquals(768, Connection.computeWindowCeiling(768, 0.01f, 400, FLOOR, ABS));
        assertEquals(768, Connection.computeWindowCeiling(768, Float.NaN, 400, FLOOR, ABS));
        // any Tuner shrink level is the floor, never the initial window
        assertEquals(512, Connection.computeWindowCeiling(512, 0.01f, 400, FLOOR, ABS));
        assertEquals(1024, Connection.computeWindowCeiling(1024, Float.NaN, 900, FLOOR, ABS));
    }

    /** A proven pipe lifts the ceiling ABOVE the global, up to the absolute
     *  cap — "as high as the connection supports". */
    @Test
    public void testBdpLiftsAboveGlobal() {
        // 1.0 pkt/ms x 900ms x 1.25 = 1125 > global 768
        assertEquals(1125, Connection.computeWindowCeiling(768, 1.0f, 900, FLOOR, ABS));
        // 3.2 x 800 x 1.25 = 3200 > global 512
        assertEquals(3200, Connection.computeWindowCeiling(512, 3.2f, 800, FLOOR, ABS));
        // absurd estimates are capped at the absolute window
        assertEquals(ABS, Connection.computeWindowCeiling(4096, 100.0f, 9000, FLOOR, ABS));
        assertEquals(ABS, Connection.computeWindowCeiling(512, 10.0f, 4000, FLOOR, ABS));
    }

    /** The BDP term scales with 125% headroom; an estimate below the global
     *  floor is irrelevant (the floor wins), which is what keeps discovery
     *  independent of a self-referential measurement. */
    @Test
    public void testBdpScalesWithHeadroom() {
        // 1.0 x 1000 x 1.25 = 1250, above global 512
        assertEquals(1250, Connection.computeWindowCeiling(512, 1.0f, 1000, FLOOR, ABS));
        // same BDP from a different (bwe, rtt) split
        assertEquals(1250, Connection.computeWindowCeiling(512, 0.5f, 2000, FLOOR, ABS));
        // below the global floor: the global wins, not the estimate
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, 1.0f, 1000, FLOOR, ABS));
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, 3.2f, 800, FLOOR, ABS));
    }

    /** When the initial window exceeds the global, the ceiling still never
     *  starts below a connection's starting cwnd. */
    @Test
    public void testFloorBindsBelowGlobalFloor() {
        // 0.01 x 400 x 1.25 = 5 < floor 128 > global 64
        assertEquals(FLOOR, Connection.computeWindowCeiling(64, 0.01f, 400, FLOOR, ABS));
        assertEquals(FLOOR, Connection.computeWindowCeiling(0, Float.NaN, 400, FLOOR, ABS));
    }

    /** The absolute cap clamps even an absurd global/BDP combination. */
    @Test
    public void testAbsMaxClamps() {
        assertEquals(ABS, Connection.computeWindowCeiling(Integer.MAX_VALUE, 100.0f, 9000, FLOOR, ABS));
        assertEquals(ABS, Connection.computeWindowCeiling(ABS + 1, Float.NaN, 400, FLOOR, ABS));
        assertEquals(ABS, Connection.computeWindowCeiling(ABS + 1, 0.01f, 400, FLOOR, ABS));
    }

    /** Defensive floor: non-positive global/floor still yields a usable ceiling. */
    @Test
    public void testDefensiveGlobal() {
        assertEquals(FLOOR, Connection.computeWindowCeiling(0, Float.NaN, 400, FLOOR, ABS));
        assertEquals(FLOOR, Connection.computeWindowCeiling(-5, Float.NaN, 400, FLOOR, ABS));
        assertEquals(1, Connection.computeWindowCeiling(-5, Float.NaN, 400, 0, ABS));
    }

    /** The invariants: never above the absolute cap, never below the
     *  global/floor base, for a sweep of inputs. */
    @Test
    public void testBoundedSweep() {
        float[] bwes = {Float.NaN, 0.0f, 0.01f, 0.05f, 1.0f, 7.5f, 500.0f};
        int[] rtts = {0, 1, 500, 900, 1000, 9000};
        int[] globals = {0, 1, 128, 768, 4096, 8192};
        int[] floors = {0, 4, 128, 512, 1024};
        for (float bwe : bwes) {
            for (int rtt : rtts) {
                for (int g : globals) {
                    for (int f : floors) {
                        int ceil = Connection.computeWindowCeiling(g, bwe, rtt, f, ABS);
                        int base = Math.min(ABS, Math.max(Math.max(1, g), Math.max(1, f)));
                        assertTrue("ceiling " + ceil + " below base " + base, ceil >= base);
                        assertTrue("ceiling " + ceil + " above ABS " + ABS, ceil <= ABS);
                    }
                }
            }
        }
    }
}
