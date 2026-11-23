package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Tests the dead-band {@link Tuner#confirmReversal(int, int, int, int, boolean)}
 * now sits in front of every param's value change.
 *
 * <p>A param's {@code computeTarget()} compares one observed stat against one
 * threshold, so a stat that hovers on that threshold sends the param one step
 * up on one pass and one step down on the next, every five seconds, forever.
 * Over one log rotation on a live router {@code netdb.maxSearchTime} moved 44
 * times and 10 of those moves went straight back to where the previous pass
 * had just put it; {@code i2p.streaming.minPacingRate} moved 50 times with 4
 * such reversals and {@code i2ptunnel.serverIO.threads} 67 times - 374 param
 * writes, each one rewriting autotune.config and writing an INFO line, in
 * 17 minutes.
 *
 * @since 0.9.72+
 */
public class TunerReversalConfirmTest {

    /**
     * The reversal-confirmation cases. Every case calls the static decision, so
     * the instance holds no state and needs no router or param.
     */
    public TunerReversalConfirmTest() {
    }

    /** One cycle of a stat is noise, so a reversal has to survive two. */
    @Test
    public void reversalNeedsTwoCycles() {
        assertEquals(2, Tuner.REVERSAL_CONFIRM_CYCLES);
    }

    /** A param that has not moved yet is never held - startup is not a reversal. */
    @Test
    public void firstMoveIsNeverHeld() {
        assertEquals(11, Tuner.confirmReversal(10, 11, 0, 0, false));
        assertEquals(9, Tuner.confirmReversal(10, 9, 0, 0, false));
    }

    /**
     * A move in the direction the param already moved is applied on the cycle it
     * is asked for: a genuine trend is not slowed by the dead-band.
     */
    @Test
    public void trendMovesEveryCycle() {
        assertEquals(9, Tuner.confirmReversal(10, 9, -1, 0, false));
        assertEquals(9, Tuner.confirmReversal(10, 9, -1, 5, false));
        assertEquals(11, Tuner.confirmReversal(10, 11, 1, 0, false));
    }

    /**
     * The defect. A fresh reversal away from the last move is held on the cycle
     * it appears, so a stat that crosses its threshold for one pass out of two
     * leaves the param where it was instead of walking it back and forth.
     */
    @Test
    public void freshReversalIsHeld() {
        assertEquals(10, Tuner.confirmReversal(10, 11, -1, 0, false));
        assertEquals(10, Tuner.confirmReversal(10, 9, 1, 0, false));
    }

    /** A reversal that persists is applied on the confirming cycle. */
    @Test
    public void sustainedReversalLands() {
        assertEquals(11, Tuner.confirmReversal(10, 11, -1, 1, false));
        assertEquals(9, Tuner.confirmReversal(10, 9, 1, 1, false));
    }

    /**
     * The recovery path is exempt: a param that has diverged from its default
     * and has the stat turn against that divergence snaps back on the cycle it
     * is asked for, exactly as before the dead-band.
     */
    @Test
    public void recoveryIsNeverHeld() {
        assertEquals(9, Tuner.confirmReversal(10, 9, 1, 0, true));
        assertEquals(11, Tuner.confirmReversal(10, 11, -1, 0, true));
    }

    /** A converged cycle is not a move and is never rewritten. */
    @Test
    public void convergedCycleChangesNothing() {
        assertEquals(10, Tuner.confirmReversal(10, 10, 1, 0, false));
        assertEquals(10, Tuner.confirmReversal(10, 10, -1, 3, true));
    }

    /**
     * The live oscillation, replayed. {@code netdb.maxSearchTime} was asked for
     * 29000, 28000, 27000, 26000, then back up 27000, 28000, 29000, 30000, then
     * down again - 44 asks in one rotation, 10 of them immediate reversals.
     * Run through the same state machine {@code applyTarget()} now keeps, the
     * immediate reversals stop landing and the value stops walking.
     */
    @Test
    public void liveOscillationStopsWalking() {
        int[] asked = {28000, 27000, 26000, 27000, 28000, 29000, 30000, 29000,
                       28000, 27000, 26000, 27000, 28000, 29000, 28000, 27000};
        List<Integer> applied = apply(asked, 29000, false);
        assertEquals("every ask in the live sequence is a step, none is a recovery",
                     0, immediateReversals(applied));
        assertTrue("the dead-band moved fewer values than it was asked for",
                   applied.size() < asked.length);

        List<Integer> before = apply(asked, 29000, true);
        assertTrue("without the dead-band the same asks reverse themselves",
                   immediateReversals(before) > 0);
        assertEquals("no ask is dropped when nothing is held", asked.length, before.size());
    }

    /**
     * Replay of the per-cycle state {@code applyTarget()} keeps.
     *
     * @param asked the target each cycle asked for, in order
     * @param start the value the param holds at the first cycle
     * @param noDeadBand true to apply every ask, the way the Tuner did before
     *                   the confirmation existed
     * @return the values actually applied, in order
     */
    private static List<Integer> apply(int[] asked, int start, boolean noDeadBand) {
        List<Integer> applied = new ArrayList<>();
        int current = start;
        int lastDir = 0;
        int held = 0;
        for (int target : asked) {
            if (target == current) {
                lastDir = 0;
                held = 0;
                continue;
            }
            int next = noDeadBand ? target
                                  : Tuner.confirmReversal(current, target, lastDir, held, false);
            if (next == current) {
                held++;
            } else {
                applied.add(next);
                lastDir = next > current ? 1 : -1;
                held = 0;
                current = next;
            }
        }
        return applied;
    }

    /**
     * @param values the applied values in order, each one step from the last
     * @return how many moves went straight back to the value the move before
     *         them came from
     */
    private static int immediateReversals(List<Integer> values) {
        int rev = 0;
        for (int i = 2; i < values.size(); i++) {
            int a = values.get(i - 2);
            int b = values.get(i - 1);
            int c = values.get(i);
            if (c == a && b != a) {rev++;}
        }
        return rev;
    }
}
