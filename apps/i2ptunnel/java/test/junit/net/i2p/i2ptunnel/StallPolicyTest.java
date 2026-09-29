package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the two pure helpers behind the async body stall policy:
 * {@link I2PTunnelHTTPServer#computeStallWindowMs} and
 * {@link I2PTunnelHTTPServer#classifyStalledSide}.
 *
 * <p>Background: a 45MB download stalled after 3.2MB and was logged as
 * "local backend silent" while the same backend served the whole file in 4ms
 * on a fresh connection. The window was also a flat 5s regardless of how much
 * had already transferred. Both are pinned here.
 *
 * @since 0.9.71+
 */
public class StallPolicyTest {

    private static final long BASE = I2PTunnelHTTPServer.STALL_WINDOW_FLOOR_MS;

    // ---- computeStallWindowMs ----

    @Test
    public void floorIsSixtySeconds() {
        assertEquals(60_000L, I2PTunnelHTTPServer.STALL_WINDOW_FLOOR_MS);
    }

    @Test
    public void smallBodyUsesBaseWindow() {
        assertEquals(BASE, I2PTunnelHTTPServer.computeStallWindowMs(BASE, 0));
        assertEquals(BASE, I2PTunnelHTTPServer.computeStallWindowMs(BASE, 9_000));
        assertEquals(BASE, I2PTunnelHTTPServer.computeStallWindowMs(BASE, 1_048_575));
    }

    @Test
    public void oneMegabyteDoublesTheWindow() {
        assertEquals(BASE * 2, I2PTunnelHTTPServer.computeStallWindowMs(BASE, 1_048_576));
    }

    @Test
    public void eightMegabytesQuadruplesTheWindow() {
        assertEquals(BASE * 4, I2PTunnelHTTPServer.computeStallWindowMs(BASE, 8L * 1024 * 1024));
    }

    @Test
    public void thirtyTwoMegabytesIsEightTimes() {
        assertEquals(BASE * 8, I2PTunnelHTTPServer.computeStallWindowMs(BASE, 32L * 1024 * 1024));
    }

    @Test
    public void installerSizeGetsTheFullEightTimes() {
        // 45,447,097 bytes: the file that kept failing.
        assertEquals(BASE * 8, I2PTunnelHTTPServer.computeStallWindowMs(BASE, 45_447_097L));
    }

    @Test
    public void scalingStopsAtTheTopTier() {
        // The top tier is 8x the base window; the cap is a ceiling above that,
        // not the value a large body reaches.
        long huge = 10_000L * 1024 * 1024;
        assertEquals(BASE * 8, I2PTunnelHTTPServer.computeStallWindowMs(BASE, huge));
        assertTrue("the top tier must stay under the hard ceiling",
                   I2PTunnelHTTPServer.computeStallWindowMs(BASE, huge)
                       <= I2PTunnelHTTPServer.MAX_STALL_WINDOW_MS);
    }

    @Test
    public void nonPositiveBaseDisablesStallDetection() {
        assertEquals(0, I2PTunnelHTTPServer.computeStallWindowMs(0, 45_447_097L));
        assertEquals(0, I2PTunnelHTTPServer.computeStallWindowMs(-1, 0));
    }

    @Test
    public void windowIsMonotonicInBytesTransferred() {
        long previous = 0;
        long[] samples = { 0, 1024, 1_048_576, 4_000_000, 8_388_608, 32_000_000, 45_447_097, 1L << 40 };
        for (long bytes : samples) {
            long window = I2PTunnelHTTPServer.computeStallWindowMs(BASE, bytes);
            assertTrue("window shrank at " + bytes + ": " + previous + " -> " + window,
                       window >= previous);
            previous = window;
        }
    }

    // ---- classifyStalledSide ----

    @Test
    public void fresherWriteMeansSourceWentQuiet() {
        assertEquals(I2PTunnelHTTPServer.STALL_SIDE_SOURCE,
                     I2PTunnelHTTPServer.classifyStalledSide(9_000, 5_000, 3_211_264));
    }

    @Test
    public void fresherReadMeansEgressBackedUp() {
        assertEquals(I2PTunnelHTTPServer.STALL_SIDE_DESTINATION,
                     I2PTunnelHTTPServer.classifyStalledSide(5_000, 9_000, 3_211_264));
    }

    @Test
    public void equallyStaleWithProgressIsIndeterminate() {
        // The exact 7465/7465 observation from the failing transfer. The body
        // had moved 3.2MB, so it was sitting in a blocked write, but the
        // stamps cannot prove that and must not claim the local backend is dead.
        assertEquals(I2PTunnelHTTPServer.STALL_SIDE_INDETERMINATE,
                     I2PTunnelHTTPServer.classifyStalledSide(7_465, 7_465, 3_211_264));
    }

    @Test
    public void equallyStaleBeforeAnyProgressFallsBackToDestination() {
        // A body that never moved cannot be "blocked writing": a blocked read
        // is the only thing that fits, and the conservative answer avoids
        // telling the operator to go inspect a healthy backend.
        assertEquals(I2PTunnelHTTPServer.STALL_SIDE_DESTINATION,
                     I2PTunnelHTTPServer.classifyStalledSide(5_000, 5_000, 0));
    }

    @Test
    public void indeterminateThresholdIsInclusive() {
        assertEquals(I2PTunnelHTTPServer.STALL_SIDE_DESTINATION,
                     I2PTunnelHTTPServer.classifyStalledSide(5_000, 5_000, 1_023));
        assertEquals(I2PTunnelHTTPServer.STALL_SIDE_INDETERMINATE,
                     I2PTunnelHTTPServer.classifyStalledSide(5_000, 5_000, 1_024));
    }

    @Test
    public void classificationNeverReturnsNull() {
        long[] ages = { 0, 1, 5_000, Long.MAX_VALUE / 4 };
        for (long read : ages) {
            for (long write : ages) {
                for (long bytes : new long[] { 0, 1_024, 1L << 30 }) {
                    assertNotNull(I2PTunnelHTTPServer.classifyStalledSide(read, write, bytes));
                }
            }
        }
    }
}
