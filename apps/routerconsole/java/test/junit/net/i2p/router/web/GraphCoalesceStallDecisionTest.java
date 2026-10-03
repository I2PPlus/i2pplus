package net.i2p.router.web;

import net.i2p.router.web.GraphGenerator.StaleCause;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the coalesce-stall discriminator, {@link StaleCause#COALESCE_STALLED} and
 * {@link GraphGenerator#isCoalesceSweepStalled}.
 *
 * <p>Why this cause exists: the staleness report could name the symptom
 * ({@code writes_stopped}) but not the mechanism. During a real stall it reported
 * {@code writes_stopped=25} with an empty error log, because nothing in the stats
 * package throws when the coalesce <em>task</em> simply never runs. An operator reading
 * that line would go looking for 25 listener faults instead of one saturated timer.
 *
 * @since 0.9.71+
 */
public class GraphCoalesceStallDecisionTest {

    private static final long NOW = 2_000_000L;
    private static final long STARTED = NOW - 3_600_000L;
    private static final long PERIOD = 60_000L;
    private static final long MIN_STALENESS = GraphGenerator.MIN_STALENESS_PERIOD_MS;

    /////////////// isCoalesceSweepStalled

    @Test
    public void neverSweptIsStalled() {
        assertTrue("a sweep that has never run is the extreme case",
                   GraphGenerator.isCoalesceSweepStalled(-1L));
    }

    @Test
    public void freshSweepIsNotStalled() {
        assertFalse(GraphGenerator.isCoalesceSweepStalled(0L));
    }

    @Test
    public void oneMissedCycleIsNotStalled() {
        assertFalse("one late sweep is not yet a fault",
                    GraphGenerator.isCoalesceSweepStalled(MIN_STALENESS));
    }

    @Test
    public void exactlyTwoCyclesIsNotStalled() {
        assertFalse(GraphGenerator.isCoalesceSweepStalled(2 * MIN_STALENESS));
    }

    @Test
    public void pastTwoCyclesIsStalled() {
        assertTrue(GraphGenerator.isCoalesceSweepStalled(2 * MIN_STALENESS + 1));
    }

    @Test
    public void aLongStallIsDetected() {
        assertTrue(GraphGenerator.isCoalesceSweepStalled(3_600_000L));
    }

    /////////////// classifyStaleness precedence

    @Test
    public void coalesceStalledOutranksWritesStopped() {
        // A listener that wrote for hours and then stopped, while the sweep is dead:
        // the cause is the sweep, not this listener.
        long lastWrite = NOW - 10 * PERIOD;
        assertEquals(StaleCause.COALESCE_STALLED,
                     GraphGenerator.classifyStaleness(false, true, lastWrite, NOW, STARTED,
                                                      PERIOD, true));
    }

    @Test
    public void coalesceStalledOutranksNeverWritten() {
        assertEquals(StaleCause.COALESCE_STALLED,
                     GraphGenerator.classifyStaleness(false, true, 0L, NOW, STARTED, PERIOD, true));
    }

    @Test
    public void healthySweepFallsBackToThePerListenerCause() {
        long lastWrite = NOW - 10 * PERIOD;
        assertEquals(StaleCause.WRITES_STOPPED,
                     GraphGenerator.classifyStaleness(false, true, lastWrite, NOW, STARTED,
                                                      PERIOD, false));
        assertEquals(StaleCause.NEVER_WRITTEN,
                     GraphGenerator.classifyStaleness(false, true, 0L, NOW, STARTED,
                                                      PERIOD, false));
    }

    @Test
    public void unregisteredStillOutranksCoalesceStalled() {
        // Re-arming is still the fix for a lost registration, so that cause keeps
        // priority: it is actionable from here, whereas the sweep is not.
        assertEquals(StaleCause.UNREGISTERED,
                     GraphGenerator.classifyStaleness(false, false, NOW - 10 * PERIOD, NOW,
                                                      STARTED, PERIOD, true));
    }

    @Test
    public void detachedIsStillNotReported() {
        // A detached listener is rebuilt on this same tick.
        assertEquals(StaleCause.OK,
                     GraphGenerator.classifyStaleness(true, true, NOW - 10 * PERIOD, NOW,
                                                      STARTED, PERIOD, true));
    }

    @Test
    public void aFreshWriteWithADeadSweepIsStillReported() {
        // Nothing here is broken individually, but the table is starving.
        assertEquals(StaleCause.COALESCE_STALLED,
                     GraphGenerator.classifyStaleness(false, true, NOW - 1000L, NOW, STARTED,
                                                      PERIOD, true));
    }

    @Test
    public void healthyEverythingIsOk() {
        assertEquals(StaleCause.OK,
                     GraphGenerator.classifyStaleness(false, true, NOW - 1000L, NOW, STARTED,
                                                      PERIOD, false));
    }

    @Test
    public void theLegacyOverloadAssumesAHealthySweep() {
        long lastWrite = NOW - 10 * PERIOD;
        assertEquals(StaleCause.WRITES_STOPPED,
                     GraphGenerator.classifyStaleness(false, true, lastWrite, NOW, STARTED, PERIOD));
    }

    /////////////// the reported line

    /** A tally with {@code count} entries, built through the real record path. */
    private static GraphGenerator.CauseTally tally(StaleCause cause, int count, long ageMs,
                                                  boolean sinceLastWrite) {
        GraphGenerator.CauseTally t = new GraphGenerator.CauseTally(cause);
        for (int i = 0; i < count; i++)
            t.record(ageMs, sinceLastWrite);
        return t;
    }

    @Test
    public void theTallyNamesTheCoalesceCauseFirst() {
        String msg = GraphGenerator.formatStaleness(
                25,
                new GraphGenerator.CauseTally(StaleCause.NEVER_WRITTEN),
                new GraphGenerator.CauseTally(StaleCause.UNREGISTERED),
                new GraphGenerator.CauseTally(StaleCause.WRITES_STOPPED),
                tally(StaleCause.COALESCE_STALLED, 25, 300_000L, true));
        assertTrue("the report must lead with the mechanism that explains a table-wide stop",
                   msg.startsWith("RRD data stalled: 25/25")
                   && msg.contains("coalesce_stalled=25"));
        assertTrue("the prefix must survive for existing greps", msg.startsWith("RRD data stalled:"));
    }

    @Test
    public void countsAcrossCausesSumToTheTotal() {
        String msg = GraphGenerator.formatStaleness(
                30,
                tally(StaleCause.NEVER_WRITTEN, 2, 1000L, false),
                tally(StaleCause.UNREGISTERED, 3, 2000L, true),
                tally(StaleCause.WRITES_STOPPED, 4, 3000L, true),
                tally(StaleCause.COALESCE_STALLED, 21, 4000L, true));
        assertTrue(msg.startsWith("RRD data stalled: 30/30"));
        assertTrue(msg.contains("coalesce_stalled=21"));
        assertTrue(msg.contains("writes_stopped=4"));
    }

    @Test
    public void anEmptyCoalesceTallyRendersAsZeroWithoutAnAge() {
        String msg = GraphGenerator.formatStaleness(
                5,
                new GraphGenerator.CauseTally(StaleCause.NEVER_WRITTEN),
                new GraphGenerator.CauseTally(StaleCause.UNREGISTERED),
                new GraphGenerator.CauseTally(StaleCause.WRITES_STOPPED),
                new GraphGenerator.CauseTally(StaleCause.COALESCE_STALLED));
        assertTrue("a zero cause must not render a misleading age",
                   msg.contains("coalesce_stalled=0 (") == false);
    }

    @Test
    public void theLegacyFormatterOmitsTheCoalesceCause() {
        String msg = GraphGenerator.formatStaleness(
                10,
                new GraphGenerator.CauseTally(StaleCause.NEVER_WRITTEN),
                new GraphGenerator.CauseTally(StaleCause.UNREGISTERED),
                new GraphGenerator.CauseTally(StaleCause.WRITES_STOPPED));
        assertTrue(msg.startsWith("RRD data stalled:"));
    }

    /////////////// isCoalesceStalledNow: the start-up window

    /**
     * The bug this pins: the coalesce timer fires every {@code Router.COALESCE_TIME}
     * (50s), so seconds after start-up no sweep can have run yet. The sweep age is -1
     * ("none ever"), the pure threshold calls that stalled, and the watchdog logged
     * {@code COALESCE_STALLED} on every single boot - blaming the coalesce timer for a
     * sweep that was merely still due.
     */
    @Test
    public void noSweepYetIsNotStalledSecondsAfterStartup() {
        assertFalse("a sweep that has not come round yet is not a fault",
                    GraphGenerator.isCoalesceStalledNow(false, -1L, NOW, NOW + 32_000L));
    }

    @Test
    public void noSweepYetIsNotStalledInsideTheGraceWindow() {
        long grace = GraphGenerator.COALESCE_STALL_GRACE_MS;
        assertFalse(GraphGenerator.isCoalesceStalledNow(false, -1L, NOW, NOW + grace));
        assertFalse(GraphGenerator.isCoalesceStalledNow(false, -1L, NOW, NOW + grace - 1));
    }

    @Test
    public void noSweepEverIsStalledOnceTheGraceWindowHasPassed() {
        long grace = GraphGenerator.COALESCE_STALL_GRACE_MS;
        assertTrue("past the grace window a missing sweep is a real fault",
                   GraphGenerator.isCoalesceStalledNow(false, -1L, NOW, NOW + grace + 1));
    }

    /**
     * Once a sweep has completed the grace window no longer applies: a sweep that then
     * goes stale is a genuine stall, judged on its own age.
     */
    @Test
    public void aStaleSweepIsStalledEvenSoonAfterStartup() {
        long age = 3 * GraphGenerator.MIN_STALENESS_PERIOD_MS;
        assertTrue(GraphGenerator.isCoalesceStalledNow(true, age, NOW, NOW + 1_000L));
    }

    @Test
    public void aFreshSweepIsNotStalledEvenLongAfterStartup() {
        assertFalse(GraphGenerator.isCoalesceStalledNow(true, 0L, NOW, NOW + 3_600_000L));
    }

    /////////////// the rendered absence must never leak the -1 sentinel

    @Test
    public void aKnownSweepAgeIsRenderedInSeconds() {
        assertEquals("300s", GraphGenerator.formatCoalesceSweepAbsence(300_000L, STARTED, NOW));
    }

    /**
     * The exact nonsense this replaces: the accessor means "none ever" with -1, and
     * interpolating it produced "has not completed for -1s".
     */
    @Test
    public void theUnknownSentinelNeverReachesTheMessage() {
        String s = GraphGenerator.formatCoalesceSweepAbsence(-1L, STARTED, NOW);
        assertFalse("a negative duration is not a duration", s.startsWith("-"));
        assertFalse(s.contains("-1s"));
    }

    @Test
    public void theUnknownCaseIsLabelledAndBounded() {
        String s = GraphGenerator.formatCoalesceSweepAbsence(-1L, STARTED, NOW);
        assertTrue("the unknown case must say so", s.contains("none has ever run"));
        assertTrue("and must quote the graphing window as a real bound", s.endsWith("s (none has ever run)"));
        assertTrue(s.startsWith(String.valueOf((NOW - STARTED) / 1000L)));
    }

    @Test
    public void aZeroAgeFallsBackRatherThanRenderingZero() {
        // Age 0 means "just swept"; the stalled path cannot see it, but be safe.
        assertTrue(GraphGenerator.formatCoalesceSweepAbsence(0L, STARTED, NOW).contains("s"));
    }

    @Test
    public void aFutureStartedMsDoesNotRenderNegative() {
        String s = GraphGenerator.formatCoalesceSweepAbsence(-1L, NOW + 60_000L, NOW);
        assertFalse("a start time in the future must clamp, not go negative", s.startsWith("-"));
    }

    @Test
    public void everyRenderedAbsenceStartsWithANonNegativeNumber() {
        long[] ages = { -1L, 0L, 1L, 999L, 60_000L, 3_600_000L };
        long[] starts = { STARTED, NOW, NOW + 10_000L };
        for (long age : ages) {
            for (long start : starts) {
                String s = GraphGenerator.formatCoalesceSweepAbsence(age, start, NOW);
                int i = 0;
                while (i < s.length() && Character.isDigit(s.charAt(i)))
                    i++;
                assertTrue("must start with digits, got: " + s, i > 0);
            }
        }
    }

    @Test
    public void theErrorOnlyFiresBeyondTheGracePeriod() {
        // The cadence gate: inside the grace window a never-run sweep is not a fault.
        assertFalse(GraphGenerator.isCoalesceStalledNow(false, -1L, STARTED,
                                                          STARTED + GraphGenerator.COALESCE_STALL_GRACE_MS));
        assertTrue(GraphGenerator.isCoalesceStalledNow(false, -1L, STARTED,
                                                         STARTED + GraphGenerator.COALESCE_STALL_GRACE_MS + 1));
    }
}
