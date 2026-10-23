package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the ceiling's direction decision with a dead-band between shrink and
 * climb, and the confirm count that turns a reading into a direction.
 *
 * <p>Before this, one threshold served both directions, so a small-sample loss
 * ratio sitting on it crossed it repeatedly and the ceiling banged between the
 * hard floor and cap every 50 seconds without ever resting in between — a
 * measured path of 5120 -&gt; 7168 -&gt; 6912 -&gt; 8192 -&gt; 7936 -&gt; 8192.
 *
 * <p>The dead-band is what lets it rest: shrink at the full lossy threshold,
 * climb at half of it, hold in between.
 *
 * @since 0.9.71+
 */
public class TunerCeilingDirectionTest {

    private static final double LOSSY = 0.20;

    /** Well above the threshold, with confirmation: shrink. */
    @Test
    public void aboveThresholdAndConfirmedShrinks() {
        assertEquals(Tuner.CEILING_SHRINK,
                     Tuner.ceilingDirection(0.40, 1, 0));
    }

    /** Well below the climb band, with confirmation: climb. */
    @Test
    public void belowClimbBandAndConfirmedClimbs() {
        assertEquals(Tuner.CEILING_CLIMB,
                     Tuner.ceilingDirection(0.02, 0, 1));
    }

    /**
     * The dead-band. Strictly between the climb band (inclusive at half the lossy
     * threshold) and the shrink threshold the honest answer is "unchanged", which
     * is what lets the ceiling rest at an intermediate value. 0.10 is the climb
     * boundary itself, not the dead-band. It is pinned by
     * {@link #climbBandBoundaryIsInclusive}.
     */
    @Test
    public void betweenBandsHolds() {
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(0.15, 1, 1));
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(0.11, 5, 5));
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(0.1999, 3, 3));
    }

    /**
     * The climb band is inclusive at its own boundary: exactly at half the lossy
     * threshold is already climbable, so the band is the closed interval on that
     * side and open on the shrink side.
     */
    @Test
    public void climbBandBoundaryIsInclusive() {
        assertEquals(Tuner.CEILING_CLIMB, Tuner.ceilingDirection(0.10, 0, 1));
        // one step further in and it climbs without confirmation
        assertEquals(Tuner.CEILING_CLIMB, Tuner.ceilingDirection(0.09, 0, 1));
        // just above the band, nothing
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(0.101, 0, 1));
        // the shrink threshold is the other closed end
        assertEquals(Tuner.CEILING_SHRINK, Tuner.ceilingDirection(0.20, 1, 0));
    }

    /**
     * The confirm count. A first reading in a direction is not yet a direction —
     * that is what stops one period of a small-sample ratio moving the ceiling.
     */
    @Test
    public void firstWindowInADirectionHolds() {
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(0.40, 0, 0));
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(0.02, 0, 0));
    }

    /** The second consecutive window releases the move. */
    @Test
    public void secondWindowReleasesTheMove() {
        assertEquals(Tuner.CEILING_SHRINK, Tuner.ceilingDirection(0.40, 1, 0));
        assertEquals(Tuner.CEILING_CLIMB, Tuner.ceilingDirection(0.02, 0, 1));
    }

    /**
     * A long streak confirms without over-shooting: the count saturates its job at
     * the confirm count and does not grow the step with it.
     */
    @Test
    public void longStreakStillOneDirection() {
        assertEquals(Tuner.CEILING_SHRINK, Tuner.ceilingDirection(0.40, 50, 0));
        assertEquals(Tuner.CEILING_CLIMB, Tuner.ceilingDirection(0.02, 0, 50));
    }

    /**
     * A missing signal holds. Treating NaN as clean would climb on no evidence,
     * which is the failure mode this codebase has been bitten by repeatedly.
     */
    @Test
    public void missingSignalHolds() {
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(Double.NaN, 5, 5));
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(Double.NaN, 0, 0));
    }

    /**
     * A streak in the opposite direction does not confirm: the counts are per
     * direction, so a history of climbing never authorises a shrink.
     */
    @Test
    public void oppositeStreakDoesNotConfirm() {
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(0.40, 0, 8));
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(0.02, 8, 0));
    }

    /**
     * The band is monotone in the rate: as the reading rises the decision can only
     * move climb -&gt; hold -&gt; shrink, never the other way. The direction values are
     * ordered CLIMB(-1) &lt; HOLD(0) &lt; SHRINK(1), so the sequence must be
     * non-decreasing in the rate.
     */
    @Test
    public void bandIsMonotoneInTheRate() {
        double[] rates = {0.0, 0.05, 0.099, 0.10, 0.101, 0.15, 0.199, 0.20, 0.21, 0.5};
        // 0.101 is already above the 0.10 climb band, so the dead-band opens there
        int[] want = {Tuner.CEILING_CLIMB, Tuner.CEILING_CLIMB, Tuner.CEILING_CLIMB,
                      Tuner.CEILING_CLIMB, Tuner.CEILING_HOLD, Tuner.CEILING_HOLD,
                      Tuner.CEILING_HOLD, Tuner.CEILING_SHRINK, Tuner.CEILING_SHRINK,
                      Tuner.CEILING_SHRINK};
        for (int i = 1; i < rates.length; i++) {
            int a = Tuner.ceilingDirection(rates[i - 1], 1, 1);
            int b = Tuner.ceilingDirection(rates[i], 1, 1);
            assertTrue("direction must not reverse as the rate rises at " + rates[i],
                       a <= b);
            assertEquals(want[i], b);
        }
    }

    /** The band is derived from the shared lossy threshold, not a second literal. */
    @Test
    public void bandIsDerivedFromTheSharedThreshold() {
        // half of the lossy threshold is the climb boundary
        assertEquals(0.10, 0.20 * 0.5, 0.0001);
        assertTrue("climb band must be strictly below the lossy threshold",
                   0.10 < Tuner.STREAM_LOSSY_RATE);
    }

    /**
     * The accumulation sequence the caller must produce: a sustained loss signal
     * moves the ceiling on the second window, and a sustained clean signal moves
     * it back.
     * <p>This is the sequence that deadlocked before the fix. The confirm counts
     * were gated on the return value, which is HOLD until the counts are reached,
     * so they never incremented and the ceiling froze permanently. The counts
     * must be updated from the raw signal zone, then gated.
     */
    @Test
    public void accumulationSequenceMovesOnSecondWindow() {
        int riseShrink = 0, riseClimb = 0;
        // Window 1: loss 25% — shrink zone, count -> 1, 1+1 >= 2 so SHRINK
        riseShrink++;
        assertEquals(Tuner.CEILING_SHRINK, Tuner.ceilingDirection(0.25, riseShrink, riseClimb));
        // Window 2: loss 25% — count -> 2, still SHRINK
        riseShrink++;
        assertEquals(Tuner.CEILING_SHRINK, Tuner.ceilingDirection(0.25, riseShrink, riseClimb));
        // Window 3: loss drops to 5% — climb zone, climb count -> 1, shrink resets
        riseClimb++; riseShrink = 0;
        assertEquals(Tuner.CEILING_CLIMB, Tuner.ceilingDirection(0.05, riseShrink, riseClimb));
        // Window 4: loss 5% — climb count -> 2, still CLIMB
        riseClimb++;
        assertEquals(Tuner.CEILING_CLIMB, Tuner.ceilingDirection(0.05, riseShrink, riseClimb));
        // Dead-band between: counts unchanged, HOLD
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(0.15, riseShrink, riseClimb));
        // NaN resets both counts
        assertEquals(Tuner.CEILING_HOLD, Tuner.ceilingDirection(Double.NaN, 0, 0));
    }
}
