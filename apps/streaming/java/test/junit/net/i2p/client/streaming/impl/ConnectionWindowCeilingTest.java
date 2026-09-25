package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests {@link Connection#computeWindowCeiling(int, float, int, int, int)},
 * the pure decision helper behind the per-stream window ceiling: the ceiling
 * is {@code min(globalMax, max(floor, 125% x bwe x rtt))}, falling back to
 * {@code globalMax} when no bandwidth sample exists yet.
 *
 * <p>The invariant that makes the Tuner's OOM response effective on every
 * stream is that the returned value never exceeds {@code globalMax} — the
 * global ceiling (or per-connection override) always binds downward, while
 * the per-stream BDP term only shapes the ceiling below it. The 125% headroom
 * compensates for the Westwood+ estimator's lag and the 500ms RTT floor so a
 * healthy stream can probe past a stale estimate.
 *
 * @since 0.9.71+
 */
public class ConnectionWindowCeilingTest {

    private static final int GLOBAL = 4096;
    private static final int FLOOR = 128;
    private static final int ABS = Connection.ABSOLUTE_MAX_WINDOW;

    /** No estimate yet (NaN / zero / negative bwe, or non-positive RTT):
     *  the ceiling falls back to the global value so an uncalibrated stream
     *  ramps exactly as before. */
    @Test
    public void testNoEstimateFallsBackToGlobal() {
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, Float.NaN, 400, FLOOR, ABS));
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, 0.0f, 400, FLOOR, ABS));
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, -1.0f, 400, FLOOR, ABS));
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, 1.5f, 0, FLOOR, ABS));
    }

    /** A huge BDP never lifts the ceiling above the global (tuner) value. */
    @Test
    public void testGlobalBindsDownOnHugeBdp() {
        assertEquals(GLOBAL, Connection.computeWindowCeiling(GLOBAL, 100.0f, 9000, FLOOR, ABS));
        assertEquals(512, Connection.computeWindowCeiling(512, 10.0f, 4000, FLOOR, ABS));
    }

    /** The BDP term scales with 125% headroom: bwe x rtt x 1.25. */
    @Test
    public void testBdpScalesWithHeadroom() {
        // 1.0 pkt/ms x 1000ms x 1.25 = 1250
        assertEquals(1250, Connection.computeWindowCeiling(GLOBAL, 1.0f, 1000, FLOOR, ABS));
        // same BDP from a different (bwe, rtt) split
        assertEquals(1250, Connection.computeWindowCeiling(GLOBAL, 0.5f, 2000, FLOOR, ABS));
        // 3.2 x 800 x 1.25 = 3200 — below global, so BDP is the binding term
        assertEquals(3200, Connection.computeWindowCeiling(GLOBAL, 3.2f, 800, FLOOR, ABS));
    }

    /** A tiny-but-valid estimate is lifted to the floor (the initial window). */
    @Test
    public void testFloorBindsOnTinyEstimate() {
        // 0.01 x 400 x 1.25 = 50 < floor 128
        assertEquals(FLOOR, Connection.computeWindowCeiling(GLOBAL, 0.01f, 400, FLOOR, ABS));
    }

    /** The absolute cap clamps even an absurd global/BDP combination. */
    @Test
    public void testAbsMaxClamps() {
        assertEquals(ABS, Connection.computeWindowCeiling(Integer.MAX_VALUE, 100.0f, 9000, FLOOR, ABS));
        assertEquals(ABS, Connection.computeWindowCeiling(ABS + 1, Float.NaN, 400, FLOOR, ABS));
    }

    /** Defensive floor: a non-positive global still yields a usable ceiling. */
    @Test
    public void testDefensiveGlobal() {
        assertEquals(1, Connection.computeWindowCeiling(0, Float.NaN, 400, FLOOR, ABS));
        assertEquals(1, Connection.computeWindowCeiling(-5, Float.NaN, 400, FLOOR, ABS));
    }

    /** The invariant: never above globalMax, for a sweep of inputs. */
    @Test
    public void testNeverAboveGlobalForAnyInput() {
        float[] bwes = {Float.NaN, 0.0f, 0.05f, 1.0f, 7.5f, 500.0f};
        int[] rtts = {0, 1, 500, 1000, 9000};
        int[] globals = {1, 128, 768, 4096, 8192};
        for (float bwe : bwes) {
            for (int rtt : rtts) {
                for (int g : globals) {
                    int ceil = Connection.computeWindowCeiling(g, bwe, rtt, FLOOR, ABS);
                    assertTrue("ceiling " + ceil + " > global " + g,
                               ceil <= Math.max(1, g));
                    assertTrue("ceiling " + ceil + " < 1", ceil >= 1);
                }
            }
        }
    }
}
