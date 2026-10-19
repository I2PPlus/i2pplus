package net.i2p.router;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the gate that stops the ceiling decision acting on the same reading
 * repeatedly, and the boundary conditions that make it correct.
 *
 * <p>The ceiling decider runs on a 5-second tick but reads a 60-second window,
 * so one reading drove up to twelve identical steps. The gate holds until the
 * window closes. Its correctness rests entirely on what "the window closed"
 * means, which is why these tests pin the identities rather than the equality:
 * two periods can carry identical counts, and an idle period repeats (0, 0)
 * forever, so a gate keyed on the event counts would freeze the window instead
 * of pacing it.
 *
 * @since 0.9.71+
 */
public class TunerSignalFreshnessTest {

    private static final double T0 = 1_000_000.0;
    private static final double T1 = T0 + 60_000.0;

    /** A later close time is a new window: act. */
    @Test
    public void laterStampIsFresh() {
        assertTrue(Tuner.BaseParam.signalIsFresh(T1, T0));
    }

    /** The same close time is the same window: hold. */
    @Test
    public void sameStampIsStale() {
        assertFalse(Tuner.BaseParam.signalIsFresh(T0, T0));
    }

    /** Never acted before, so any readable stamp is fresh. */
    @Test
    public void firstReadingIsFresh() {
        assertTrue(Tuner.BaseParam.signalIsFresh(T0, Double.NaN));
    }

    /**
     * An unreadable signal falls back to the prior behaviour rather than freezing
     * the param. A gate that held here would never move again if the stamp were
     * permanently unavailable.
     */
    @Test
    public void unknownStampActs() {
        assertTrue(Tuner.BaseParam.signalIsFresh(Double.NaN, T0));
        assertTrue(Tuner.BaseParam.signalIsFresh(Double.NaN, Double.NaN));
    }

    /**
     * The case an event-count equality gate fails. Two consecutive windows closing
     * with identical event counts are still two windows, and the second one is new
     * evidence. Keyed on the close time this holds. Keyed on the counts it would
     * not.
     */
    @Test
    public void identicalCountsAcrossPeriodsStillFresh() {
        // same counts, different close times
        assertTrue(Tuner.BaseParam.signalIsFresh(T1, T0));
        // and the following period, with the same counts again
        assertFalse(Tuner.BaseParam.signalIsFresh(T1, T1));
        assertTrue(Tuner.BaseParam.signalIsFresh(T1 + 60_000.0, T1));
    }

    /**
     * The idle-period case. An idle period closes like any other, so the gate must
     * not read (0, 0) as "unchanged" and stop the window ever recovering.
     */
    @Test
    public void idlePeriodStillCloses() {
        // period ended, no events, close time still advanced
        assertTrue(Tuner.BaseParam.signalIsFresh(T1, T0));
        // a second idle period: still new, still must not freeze
        assertTrue(Tuner.BaseParam.signalIsFresh(T1 + 60_000.0, T1));
    }

    /**
     * A window that closes twice without the decision being taken is not missed:
     * the second close time is still different from the stored one.
     */
    @Test
    public void skippedWindowsAreNotLost() {
        assertTrue(Tuner.BaseParam.signalIsFresh(T1 + 120_000.0, T0));
    }

    /**
     * A close time that went backwards is a clock adjustment. It is treated as
     * fresh, which costs one extra action, chosen over the alternative of detecting
     * direction and risking a freeze: the failure mode of inequality is over-acting
     * once, the failure mode of a stricter predicate that misjudges direction is
     * never acting again.
     */
    @Test
    public void backwardsStampActsRatherThanFreezes() {
        assertTrue(Tuner.BaseParam.signalIsFresh(T0, T1));
    }
}
