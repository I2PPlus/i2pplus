package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the tunnel test failure window ({@link TestJob#computeTestPeriod}).
 *
 * The window is how long a tunnel test may run before the tunnel is declared
 * failed. Measured successful tests (~2.3s on a typical router) must not be
 * answered within tens of seconds: a window of 2x the measured success RTT
 * detects dead tunnels in ~4.6s instead of ~23s, which directly cuts the
 * build timeout rate by letting pools fail and rebuild quickly.
 *
 * @since 0.9.70+
 */
public class TestJobPeriodTest {

    // =====================================================================
    // Measured-success path: window = 2x average success time
    // =====================================================================

    @Test
    public void testWindowIsTwoTimesMeasuredSuccess() {
        // observed avg ~2.3s -> ~4.6s window, within the default [3s, 15s] range
        int period = TestJob.computeTestPeriod(2258, 1241, 4, 4, 3000, 15000);
        assertEquals(4516, period);
    }

    @Test
    public void testWindowRoundsToNearestMs() {
        assertEquals(2001, TestJob.computeTestPeriod(1000.4, 500, 3, 3, 1000, 15000));
    }

    @Test
    public void testWindowScalesUpOnSlowNetwork() {
        // 8s measured success -> 16s window, capped by the 15s max
        assertEquals(15000, TestJob.computeTestPeriod(8000, 1241, 4, 4, 3000, 15000));
    }

    @Test
    public void testWindowFlooredByMinPeriod() {
        // 1.2s measured success -> 2.4s target, below the 3s floor
        assertEquals(3000, TestJob.computeTestPeriod(1200, 1241, 4, 4, 3000, 15000));
    }

    // =====================================================================
    // Fallback path: no measurements yet
    // =====================================================================

    @Test
    public void testFallbackUsesTransportFormula() {
        // mainline formula: 3x sendProcessing + 2.5s per hop
        // 3*1241 + 2500*8 = 23723, clamped to the 15s max
        assertEquals(15000, TestJob.computeTestPeriod(Double.NaN, 1241, 4, 4, 3000, 15000));
    }

    @Test
    public void testFallbackWithoutTransportStat() {
        // no transport stat -> 15s base + per-hop allowance, clamped to max
        assertEquals(15000, TestJob.computeTestPeriod(Double.NaN, Double.NaN, 4, 4, 3000, 15000));
    }

    @Test
    public void testFallbackWithinRangeOnShortTunnel() {
        // 3*100 + 2500*2 = 5300, inside [3000, 15000]
        assertEquals(5300, TestJob.computeTestPeriod(Double.NaN, 100, 1, 1, 3000, 15000));
    }

    // =====================================================================
    // No-tunnel path and robust clamps
    // =====================================================================

    @Test
    public void testNoTunnelsUsesFifteenSeconds() {
        assertEquals(15000, TestJob.computeTestPeriod(2258, 1241, 0, 0, 3000, 15000));
        assertEquals(15000, TestJob.computeTestPeriod(Double.NaN, Double.NaN, -1, 0, 3000, 15000));
    }

    @Test
    public void testCrossedConfigFloorsAtSmallerValue() {
        // min(20000) > max(5000): effective floor is the smaller value, so a
        // fast net is not forced into a 20s window by the crossed config
        assertEquals(5000, TestJob.computeTestPeriod(2258, 1241, 4, 4, 20000, 5000));
    }

    @Test
    public void testCrossedConfigCapsAtLargerValue() {
        // min(10000) > max(7000): effective range [7000, 10000], floor wins here
        assertEquals(7000, TestJob.computeTestPeriod(2258, 1241, 4, 4, 10000, 7000));
    }

    @Test
    public void testZeroSuccessTimeFallsBack() {
        // measured stat exists but is 0 -> fall back to the transport formula
        assertEquals(15000, TestJob.computeTestPeriod(0, 1241, 4, 4, 3000, 15000));
    }

    @Test
    public void testNegativeSuccessTimeFallsBack() {
        assertEquals(15000, TestJob.computeTestPeriod(-1, 1241, 4, 4, 3000, 15000));
    }
}