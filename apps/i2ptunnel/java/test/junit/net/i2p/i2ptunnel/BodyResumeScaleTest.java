package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Size-scaled retry limits for body-resume: {@link I2PTunnelRunner.stallCycleLimit(long)}
 * and {@link I2PTunnelRunner.totalCycleLimit(long)} add one cycle per 1MB of
 * verified Content-Length above the baseline, hard-capped at 29x baseline.
 *
 * <p>Pins the ramp unit, the sub-1MB no-op, real-world installer sizes, and
 * overflow safety for pathological Content-Length values.
 *
 * @since 0.9.71+
 */
public class BodyResumeScaleTest {

    private static final long MB = 1024L * 1024L;

    /** Unknown, zero, and sub-1MB entities keep the exact baseline caps. */
    @Test
    public void testBaselineBelowRampUnit() {
        assertEquals(4, I2PTunnelRunner.stallCycleLimit(-1));
        assertEquals(4, I2PTunnelRunner.stallCycleLimit(0));
        assertEquals(4, I2PTunnelRunner.stallCycleLimit(MB - 1));
        assertEquals(32, I2PTunnelRunner.totalCycleLimit(-1));
        assertEquals(32, I2PTunnelRunner.totalCycleLimit(0));
        assertEquals(32, I2PTunnelRunner.totalCycleLimit(MB - 1));
    }

    /** One extra cycle per full 1MB unit, including the installer size. */
    @Test
    public void testRampAddsOnePerUnit() {
        assertEquals(5, I2PTunnelRunner.stallCycleLimit(MB));
        assertEquals(33, I2PTunnelRunner.totalCycleLimit(MB));
        assertEquals(14, I2PTunnelRunner.stallCycleLimit(10 * MB));
        assertEquals(42, I2PTunnelRunner.totalCycleLimit(10 * MB));
        // current installer entity: 45415943 / 1MB == 43
        assertEquals(47, I2PTunnelRunner.stallCycleLimit(45415943L));
        assertEquals(75, I2PTunnelRunner.totalCycleLimit(45415943L));
    }

    /** Ramp is monotonic across the unit boundary. */
    @Test
    public void testMonotonicAcrossUnitBoundary() {
        int prev = I2PTunnelRunner.stallCycleLimit(MB - 1);
        for (long size = MB; size <= 64 * MB; size += MB / 2) {
            int cur = I2PTunnelRunner.stallCycleLimit(size);
            assertTrue("limit must never shrink with size", cur >= prev);
            prev = cur;
        }
    }

    /** Very large and pathological sizes clamp at 29x baseline without overflow. */
    @Test
    public void testCapsBoundVeryLargeEntities() {
        assertEquals(116, I2PTunnelRunner.stallCycleLimit(4L * 1024 * MB));
        assertEquals(928, I2PTunnelRunner.totalCycleLimit(4L * 1024 * MB));
        assertEquals(116, I2PTunnelRunner.stallCycleLimit(Long.MAX_VALUE));
        assertEquals(928, I2PTunnelRunner.totalCycleLimit(Long.MAX_VALUE));
    }

    /**
     * A Content-Length beyond {@link I2PTunnelRunner#RETRY_RAMP_MAX_BYTES} is
     * an upstream claim, not a long transfer. The ramp is the only thing that
     * converts that claim into extra retry attempts, so the input is clamped
     * before it is used. The cap is already saturated at 1GB, so this costs no
     * real budget while denying a hostile destination the top of the ladder.
     */
    @Test
    public void testRampInputIsClampedBeforeUse() {
        long max = I2PTunnelRunner.RETRY_RAMP_MAX_BYTES;
        assertEquals(max, 1024L * MB);
        // everything at or above the clamp yields the identical budget
        int stall = I2PTunnelRunner.stallCycleLimit(max);
        int total = I2PTunnelRunner.totalCycleLimit(max);
        for (long size : new long[] {max + 1, 2 * max, 4L * 1024 * MB, Long.MAX_VALUE}) {
            assertEquals("stall limit for " + size,
                    stall, I2PTunnelRunner.stallCycleLimit(size));
            assertEquals("total limit for " + size,
                    total, I2PTunnelRunner.totalCycleLimit(size));
        }
        // and it is still the saturated hard cap, not something larger
        assertEquals(I2PTunnelRunner.MAX_SCALED_STALL_CYCLES, stall);
        assertEquals(I2PTunnelRunner.MAX_SCALED_RESUME_CYCLES, total);
    }

    /** A negative length is treated as unknown, never as a negative ramp. */
    @Test
    public void testNegativeLengthIsUnknownNotNegativeRamp() {
        for (long bad : new long[] {-1, -2, Long.MIN_VALUE}) {
            assertEquals("stall for " + bad, 4, I2PTunnelRunner.stallCycleLimit(bad));
            assertEquals("total for " + bad, 32, I2PTunnelRunner.totalCycleLimit(bad));
        }
    }
}
