package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Size-scaled retry limits for body-resume: {@link I2PTunnelRunner.stallCycleLimit(long)}
 * and {@link I2PTunnelRunner.totalCycleLimit(long)} add one cycle per 4MB of
 * verified Content-Length above the baseline, hard-capped at 8x baseline.
 *
 * <p>Pins the ramp unit, the sub-4MB no-op, real-world installer sizes, and
 * overflow safety for pathological Content-Length values.
 *
 * @since 0.9.71+
 */
public class BodyResumeScaleTest {

    private static final long MB = 1024L * 1024L;

    /** Unknown, zero, and sub-4MB entities keep the exact baseline caps. */
    @Test
    public void testBaselineBelowRampUnit() {
        assertEquals(4, I2PTunnelRunner.stallCycleLimit(-1));
        assertEquals(4, I2PTunnelRunner.stallCycleLimit(0));
        assertEquals(4, I2PTunnelRunner.stallCycleLimit(4 * MB - 1));
        assertEquals(32, I2PTunnelRunner.totalCycleLimit(-1));
        assertEquals(32, I2PTunnelRunner.totalCycleLimit(0));
        assertEquals(32, I2PTunnelRunner.totalCycleLimit(4 * MB - 1));
    }

    /** One extra cycle per full 4MB unit, including the installer size. */
    @Test
    public void testRampAddsOnePerUnit() {
        assertEquals(5, I2PTunnelRunner.stallCycleLimit(4 * MB));
        assertEquals(33, I2PTunnelRunner.totalCycleLimit(4 * MB));
        assertEquals(14, I2PTunnelRunner.stallCycleLimit(40 * MB));
        assertEquals(42, I2PTunnelRunner.totalCycleLimit(40 * MB));
        // current installer entity: 45415943 / 4MB == 10
        assertEquals(14, I2PTunnelRunner.stallCycleLimit(45415943L));
        assertEquals(42, I2PTunnelRunner.totalCycleLimit(45415943L));
    }

    /** Ramp is monotonic across the unit boundary. */
    @Test
    public void testMonotonicAcrossUnitBoundary() {
        int prev = I2PTunnelRunner.stallCycleLimit(4 * MB - 1);
        for (long size = 4 * MB; size <= 64 * MB; size += MB / 2) {
            int cur = I2PTunnelRunner.stallCycleLimit(size);
            assertTrue("limit must never shrink with size", cur >= prev);
            prev = cur;
        }
    }

    /** Very large and pathological sizes clamp at 8x baseline without overflow. */
    @Test
    public void testCapsBoundVeryLargeEntities() {
        assertEquals(32, I2PTunnelRunner.stallCycleLimit(4L * 1024 * MB));
        assertEquals(256, I2PTunnelRunner.totalCycleLimit(4L * 1024 * MB));
        assertEquals(32, I2PTunnelRunner.stallCycleLimit(Long.MAX_VALUE));
        assertEquals(256, I2PTunnelRunner.totalCycleLimit(Long.MAX_VALUE));
    }
}
