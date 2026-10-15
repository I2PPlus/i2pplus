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
        String r = Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, CLEAN_HEAP, Double.NaN, 0, 0);
        assertTrue("should name the climb: " + r, r.endsWith("climb"));
        assertTrue("should report the heap: " + r, r.contains("9% heap"));
        assertTrue("no gate should be named: " + r, r.contains("clean 9% heap -> climb"));
    }

    /** Loss above the gate names loss and the shrink verdict. */
    @Test
    public void lossyNamesLossAndShrinks() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, 0.35, CLEAN_HEAP, Double.NaN, 0, 0);
        assertTrue("should name loss: " + r, r.startsWith("loss 35%"));
        assertTrue("should say shrink: " + r, r.endsWith("shrink"));
    }

    /** Congestion names the duration in seconds, not raw milliseconds. */
    @Test
    public void congestedNamesSeconds() {
        String r = Tuner.maxWindowDecision(12_500L, CLEAN_LOSS, CLEAN_HEAP, Double.NaN, 0, 0);
        assertTrue("should name congestion with a readable duration: " + r,
                   r.contains("congestion 12.5s"));
        assertTrue("should say shrink: " + r, r.endsWith("shrink"));
    }

    /** Memory above the gate names memory, expressed as a percent. */
    @Test
    public void memoryPressureNamesMemory() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, 71.0, Double.NaN, 0, 0);
        assertTrue("should name memory: " + r, r.contains("memory 71%"));
        assertTrue("should say shrink: " + r, r.endsWith("shrink"));
    }

    /**
     * More than one gate open is reported joined, and the verdict is still shrink.
     * This is the case a single-signal diagnostic would silently misreport.
     */
    @Test
    public void multipleGatesAreAllNamed() {
        String r = Tuner.maxWindowDecision(12_500L, 0.35, 71.0, Double.NaN, 0, 0);
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
        String r = Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, 55.0, Double.NaN, 0, 0);
        assertTrue("should say hold: " + r, r.endsWith("hold"));
        assertTrue("should say tight, not clean: " + r, r.contains("tight 55% heap -> hold"));
    }

    /** Moderate heap is the two-step climb band. */
    @Test
    public void moderateHeapClimbs() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, 45.0, Double.NaN, 0, 0);
        assertTrue("should say moderate: " + r, r.contains("moderate 45% heap -> climb"));
        assertTrue("should say climb: " + r, r.endsWith("climb"));
    }

    /**
     * A NaN signal is "no signal", not a zero. Reading a NaN heap as 0% would climb on
     * maximum headroom that was never measured, which is how a missing reading gets
     * treated as an abundant one.
     */
    @Test
    public void nanHeapNeverReadsAsMaximumHeadroom() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, Double.NaN, Double.NaN, 0, 0);
        assertTrue("NaN heap must read as no signal: " + r, r.contains("no signal"));
        assertTrue("an unmeasured heap must hold, not climb: " + r, r.endsWith("hold"));
    }

    /** A NaN loss rate is not lossy, so the path climbs rather than shrinking. */
    @Test
    public void nanLossIsNotLossy() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, Double.NaN, CLEAN_HEAP, Double.NaN, 0, 0);
        assertTrue("a missing loss signal must not shrink: " + r, r.endsWith("climb"));
    }

    /** The threshold is inclusive, matching isStreamLossy's >= comparison. */
    @Test
    public void lossThresholdIsInclusive() {
        assertTrue("exactly at the gate is lossy",
                   Tuner.maxWindowDecision(CLEAN_MS, 0.20, CLEAN_HEAP, Double.NaN, 0, 0).endsWith("shrink"));
        assertTrue("just below is not",
                   Tuner.maxWindowDecision(CLEAN_MS, 0.1999, CLEAN_HEAP, Double.NaN, 0, 0).endsWith("climb"));
    }

    /** The congestion gate is strictly greater-than, in milliseconds. */
    @Test
    public void congestionGateIsStrictlyAbove() {
        assertTrue("at the gate is not congested",
                   !Tuner.maxWindowDecision(8_000L, CLEAN_LOSS, CLEAN_HEAP, Double.NaN, 0, 0).contains("congestion"));
        assertTrue("one ms above is",
                   Tuner.maxWindowDecision(8_001L, CLEAN_LOSS, CLEAN_HEAP, Double.NaN, 0, 0).contains("congestion"));
    }

    /** The memory gate is strictly greater-than, at 60 percent. */
    @Test
    public void memoryGateIsStrictlyAbove() {
        assertTrue("at 60 is not pressure",
                   !Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, 60.0, Double.NaN, 0, 0).contains("memory"));
        assertTrue("above 60 is",
                   Tuner.maxWindowDecision(CLEAN_MS, CLEAN_LOSS, 60.1, Double.NaN, 0, 0).contains("memory"));
    }

    /**
     * The reason carries BOTH signals, always, whichever gate fired. The two use
     * different denominators - the event-count ratio weighs a control packet the
     * same as a data one, the byte-weighted ratio weighs by bytes - so a divergence
     * between them is the evidence that decides which one should drive the ceiling.
     * Emitting only the firing gate would hide exactly that case.
     *
     * <p>Each signal carries its window label, because two heads have argued from a
     * window the tuner does not read: the event-count ratio is the last completed
     * 60 s period and the byte ratio is a 5 min mean, so a bare pair of percentages
     * invites exactly the wrong comparison. The absolute counts ride along because
     * a threshold cannot be set from ratios alone.
     */
    @Test
    public void reasonCarriesBothSignals() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, 0.40, CLEAN_HEAP, 0.10, 370, 545);
        assertTrue("must name the event-count reading: " + r, r.contains("40%"));
        assertTrue("must name the byte reading: " + r, r.contains("10%"));
        assertTrue("must distinguish the two: " + r,
                   r.contains("events") && r.contains("bytes"));
        // The window labels are the point of the change; a bare percent pair passes
        // the assertion above without them, so pin the labels explicitly.
        assertTrue("event signal must carry its window: " + r,
                   r.contains("events(60s)"));
        assertTrue("byte signal must carry its window: " + r,
                   r.contains("bytes(5m)"));
        // Absolute counts ride along, and the derived rate is per second.
        assertTrue("absolute event counts must be logged: " + r,
                   r.contains("dupEvents=370"));
        // The rate is derived from the constant the counts were read on, so the
        // expectation is derived from that constant too. Asserting a literal here
        // would pin the divisor at 60 s by hand and fail the day STAT_PERIOD
        // legitimately changes, which is the drift this is meant to catch.
        String expectedRate = String.format("%.1f", 370.0 / (Tuner.STAT_PERIOD / 1000.0d));
        assertTrue("duplicate rate must be derived from STAT_PERIOD, expected "
                   + expectedRate + ": " + r,
                   r.contains("dupEvents/s=" + expectedRate));
        // The three count pairs are separated, not run together.
        assertTrue("count pairs must be separated: " + r,
                   r.contains("dupEvents=370; dupEvents/s="));
    }

    /**
     * Both signals go out on the climb path too, not only on a lossy one. A quiet
     * period is where a divergence would first become visible.
     */
    @Test
    public void reasonCarriesBothSignalsOnClimb() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, 0.05, CLEAN_HEAP, 0.04, 0, 0);
        assertTrue("climb reason must carry both: " + r,
                   r.contains("5%") && r.contains("4%"));
        assertTrue("climb reason must label the windows too: " + r,
                   r.contains("events(60s)") && r.contains("bytes(5m)"));
        // With no events to report the count clause is dropped rather than
        // emitted as zeros, which would read as a measured no-loss window.
        assertFalse("no event data must not fabricate counts: " + r,
                    r.contains("dupEvents="));
    }

    /**
     * A missing byte signal reads as "no signal" rather than 0%. Rendering it as
     * zero would read as a clean path, which is the failure mode of a missing
     * number disguised as a good one.
     */
    @Test
    public void missingByteSignalIsNotZero() {
        String r = Tuner.maxWindowDecision(CLEAN_MS, 0.40, CLEAN_HEAP, Double.NaN, 0, 0);
        assertTrue("NaN must read as no signal: " + r, r.contains("no signal"));
        // the byte slot must read "no signal", never a percent of its own
        String byteSlot = r.substring(r.indexOf("vs ") + 3);
        assertFalse("byte slot must not carry a percent: " + byteSlot,
                    byteSlot.matches(".*[0-9]+%.*"));
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
