package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the clause the streaming ceiling logs when it moves.
 *
 * <p>The ceiling's direction is decided by three gated signals, and there was a live
 * case where it stepped down for three quarters of a run while every one of the three
 * measured below its threshold. No amount of reading the stats page from outside
 * could say which gate was open, because the stats page reports a different period
 * than the tuner reads and the two can disagree.
 *
 * <p>So the decision names the verdict and the values behind it, and those are pinned
 * here: a clause that named the wrong gate would be worse than no diagnostic.
 *
 * @since 0.9.71+
 */
public class TunerMaxWindowDecisionTest {

    private static final long CLEAN_MS = 1_000L;
    private static final double CLEAN_LOSS = 0.05;
    /** The heap stat is a percentage, so a healthy box is about 9 percent. */
    private static final double CLEAN_HEAP = 9.0;

    /** A clean path with heap headroom says so, and says climb. */
    @Test
    public void cleanWithHeadroomReportsClimb() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, CLEAN_HEAP);
        assertTrue("should name the climb: " + r, r.endsWith("climb"));
        assertTrue("should report the heap: " + r, r.contains("9% heap"));
        assertFalse("no gate should be named: " + r, r.contains("loss"));
    }

    /** Loss above the gate names loss and the shrink verdict. */
    @Test
    public void lossyNamesLossAndShrinks() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, 0.35, CLEAN_HEAP);
        assertTrue("should name loss: " + r, r.startsWith("loss 35%"));
        assertTrue("should say shrink: " + r, r.endsWith("shrink"));
    }

    /** Congestion names the duration in seconds, not raw milliseconds. */
    @Test
    public void congestedNamesSeconds() {
        String r = Tuner.maxWindowDecision(12_500L, CLEAN_LOSS, CLEAN_HEAP);
        assertTrue("should name congestion with a readable duration: " + r,
                   r.contains("congestion 12.5s"));
        assertTrue("should say shrink: " + r, r.endsWith("shrink"));
    }

    /** Memory above the gate names memory, expressed as a percent. */
    @Test
    public void memoryPressureNamesMemory() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, 71.0);
        assertTrue("should name memory: " + r, r.startsWith("memory 71%"));
        assertTrue("should say shrink: " + r, r.endsWith("shrink"));
    }

    /**
     * More than one gate open is reported joined, and the verdict is still shrink.
     * This is the case a single-signal diagnostic would silently misreport.
     */
    @Test
    public void multipleGatesAreAllNamed() {
        String r = Tuner.maxWindowDecision(12_500L, 0.35, 71.0);
        assertTrue("should name all three: " + r,
                   r.contains("loss 35%") && r.contains("congestion 12.5s")
                   && r.contains("memory 71%"));
        assertTrue("should still say shrink once: " + r, r.endsWith("shrink"));
        assertEquals("exactly one verdict", 1, countOf(r, "shrink"));
    }

    /**
     * Tight heap with no gate open is a hold, not a climb. Reporting it as a climb
     * would send a reader looking for a gate that never opened.
     */
    @Test
    public void tightHeapHolds() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, 55.0);
        assertTrue("should say hold: " + r, r.endsWith("hold"));
        assertTrue("should say tight, not clean: " + r, r.startsWith("tight 55%"));
    }

    /** Moderate heap is the two-step climb band. */
    @Test
    public void moderateHeapClimbs() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, 45.0);
        assertTrue("should say moderate: " + r, r.startsWith("moderate 45%"));
        assertTrue("should say climb: " + r, r.endsWith("climb"));
    }

    /**
     * A NaN signal is "no signal", not a zero. Reading a NaN heap as 0% would climb on
     * maximum headroom that was never measured, which is how a missing reading gets
     * treated as an abundant one.
     */
    @Test
    public void nanHeapNeverReadsAsMaximumHeadroom() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, Double.NaN);
        assertTrue("NaN heap must read as no signal: " + r, r.contains("no signal"));
        assertTrue("an unmeasured heap must hold, not climb: " + r, r.endsWith("hold"));
    }

    /** A NaN loss rate is not lossy, so the path climbs rather than shrinking. */
    @Test
    public void nanLossIsNotLossy() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, Double.NaN, CLEAN_HEAP);
        assertTrue("a missing loss signal must not shrink: " + r, r.endsWith("climb"));
    }

    /** The threshold is inclusive, matching isStreamLossy's >= comparison. */
    @Test
    public void lossThresholdIsInclusive() {
        assertTrue("exactly at the gate is lossy",
                   Tuner.maxWindowDecision(CLEAN_MS, 0.20, CLEAN_HEAP).endsWith("shrink"));
        assertTrue("just below is not",
                   Tuner.maxWindowDecision(CLEAN_MS, 0.1999, CLEAN_HEAP).endsWith("climb"));
    }

    /** The congestion gate is strictly greater-than, in milliseconds. */
    @Test
    public void congestionGateIsStrictlyAbove() {
        assertTrue("at the gate is not congested",
                   !Tuner.maxWindowDecision(8_000L, CLEAN_LOSS, CLEAN_HEAP).contains("congestion"));
        assertTrue("one ms above is",
                   Tuner.maxWindowDecision(8_001L, CLEAN_LOSS, CLEAN_HEAP).contains("congestion"));
    }

    /** The memory gate is strictly greater-than, at 60 percent. */
    @Test
    public void memoryGateIsStrictlyAbove() {
        assertTrue("at 60 is not pressure",
                   !Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, 60.0).contains("memory"));
        assertTrue("above 60 is",
                   Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, 60.1).contains("memory"));
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
