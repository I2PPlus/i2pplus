package net.i2p.router;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the pure predicate {@link Tuner#rttPinnedToClamp}, used by the
 * {@code i2p.streaming.maxRtt} and {@code i2p.streaming.maxRTO} parameters in
 * place of a fixed "RTT above 7000 ms" test.
 *
 * <p>{@code ConnectionOptions.clampRttSample()} reins every RTT sample to
 * {@code i2p.streaming.maxRtt} before it enters the smoother, and
 * {@code stream.rtt} - the stat these parameters read - reports that smoothed
 * value. A sample can therefore never exceed the clamp, so a threshold above
 * the clamp is unsatisfiable. The clamp's own floor is 5000 ms and the old
 * threshold was 7000 ms: once the tighten branch had pulled the clamp to its
 * floor, the raise branch could never fire again, and a path slower than the
 * clamp stayed measured at the ceiling for the life of the router.
 *
 * <p>The clamp is a streaming value the router test cannot call into, so the
 * cases model it as {@code Math.min(sample, clamp)}, which is what
 * {@code clampRttSample} does for a positive clamp.
 *
 * @since 0.9.72+
 */
public class TunerRttClampPressureTest {

    /**
     * The clamp-pressure cases. Every case calls the static predicate, so the
     * instance holds no state and needs no router or streaming connection.
     */
    public TunerRttClampPressureTest() {
    }

    /** The floor of {@code i2p.streaming.maxRtt}, as declared in Tuner. */
    private static final int CLAMP_FLOOR = 5000;

    /** The threshold the two parameters used before this predicate. */
    private static final int OLD_THRESHOLD = 7000;

    /**
     * The clamp as streaming applies it: a positive clamp caps the sample.
     *
     * @param sample the measured RTT in ms
     * @param clamp the live {@code i2p.streaming.maxRtt} in ms
     * @return the sample that reaches the smoother, in ms
     */
    private static int clamped(int sample, int clamp) {
        return Math.max(0, Math.min(sample, clamp));
    }

    /**
     * The defect. With the clamp on its 5000 ms floor, no measurement of any
     * size reaches the old 7000 ms threshold, so the branch that exists to
     * raise the cap for a slow path is dead - and the cap is already at the
     * floor it cannot climb off.
     */
    @Test
    public void oldThresholdIsUnreachableAtTheClampFloor() {
        for (int measured : new int[] {5000, 8000, 20000, 120000}) {
            int observed = clamped(measured, CLAMP_FLOOR);
            assertFalse("a " + measured + " ms path reads " + observed,
                        observed > OLD_THRESHOLD);
            assertTrue("the same sample must count as pinned",
                       Tuner.rttPinnedToClamp(observed, CLAMP_FLOOR));
        }
    }

    /**
     * A sample at the clamp counts as pinned at every clamp value, which is
     * what lets the cap climb back off its floor: each raise lifts the clamp,
     * the sample follows it while the path really is that slow, and the climb
     * stops when the sample stops pressing.
     */
    @Test
    public void pinnedSampleCountsAtAnyClamp() {
        for (int clamp : new int[] {CLAMP_FLOOR, 8000, 10000, 60000}) {
            assertTrue("clamp " + clamp,
                       Tuner.rttPinnedToClamp(clamped(120000, clamp), clamp));
        }
    }

    /**
     * The boundary is the pressure fraction of the clamp, derived from the
     * Tuner's own constant so the case cannot drift from it.
     */
    @Test
    public void boundaryIsThePressureFractionOfTheClamp() {
        int clamp = 20000;
        double edge = clamp * Tuner.RTT_CLAMP_PRESSURE;
        assertTrue(Tuner.rttPinnedToClamp(edge, clamp));
        assertFalse(Tuner.rttPinnedToClamp(edge - 1, clamp));
    }

    /**
     * The fast path is unchanged. This router's live {@code stream.rtt} average
     * is 1,586 ms against a 10,000 ms clamp, and neither the old threshold nor
     * the new predicate calls that pinned, so the fix does not retune a healthy
     * router.
     */
    @Test
    public void healthyRouterIsNotCalledPinned() {
        assertFalse(Tuner.rttPinnedToClamp(1586, 10000));
        assertFalse(Tuner.rttPinnedToClamp(1586, CLAMP_FLOOR));
        assertFalse(Tuner.rttPinnedToClamp(4000, 10000));
    }

    /**
     * Nothing measured is never pinned, and a clamp of zero or less disables
     * clamping in streaming, where a sample is free to be any size and cannot
     * report the clamp being too low.
     */
    @Test
    public void unmeasuredOrUnclampedIsNeverPinned() {
        assertFalse(Tuner.rttPinnedToClamp(Double.NaN, 10000));
        assertFalse(Tuner.rttPinnedToClamp(Double.NaN, CLAMP_FLOOR));
        assertFalse(Tuner.rttPinnedToClamp(9000, 0));
        assertFalse(Tuner.rttPinnedToClamp(9000, -1));
    }

    /**
     * A sample well above a small clamp still counts, because the smoother
     * settles below the clamp rather than on it - the fraction exists to catch
     * exactly that approach.
     */
    @Test
    public void approachingSampleCounts() {
        int clamp = CLAMP_FLOOR;
        double justUnder = clamp * Tuner.RTT_CLAMP_PRESSURE;
        assertTrue(Tuner.rttPinnedToClamp(clamped(8000, clamp), clamp));
        assertTrue(Tuner.rttPinnedToClamp(justUnder, clamp));
        assertFalse(Tuner.rttPinnedToClamp(justUnder - 100, clamp));
    }
}
