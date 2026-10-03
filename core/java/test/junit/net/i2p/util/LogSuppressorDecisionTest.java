package net.i2p.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link LogSuppressor}, which collapses repeated occurrences of one log
 * condition.
 *
 * <p>The policy is pinned as a pure function so the edges can be tested without a
 * clock or a logger: what matters is that a first occurrence always survives, a slow
 * trickle is never hidden, and a flood is reduced without losing the fact that it was a
 * flood.
 *
 * @since 0.9.71+
 */
public class LogSuppressorDecisionTest {

    private static final int BURST = 1000;
    private static final long WINDOW = 60_000L;
    private static final long T0 = 1_700_000_000_000L;

    private static boolean due(long occurrences, long lastCount, long lastMs, long nowMs) {
        return LogSuppressor.isDue(occurrences, lastCount, lastMs, BURST, WINDOW, nowMs);
    }

    /////////////// the pure policy

    @Test
    public void firstOccurrenceAlwaysReports() {
        assertTrue("a condition seen once must never be hidden", due(1, 0, 0, T0));
    }

    @Test
    public void repeatsInsideTheWindowAreSuppressed() {
        assertFalse(due(2, 1, T0, T0 + 1000));
    }

    @Test
    public void repeatsInsideTheBurstAndWindowAreSuppressed() {
        assertFalse(due(999, 1, T0, T0 + 59_000));
    }

    @Test
    public void burstTriggersAReporDespiteTheWindow() {
        assertTrue("1000 repeats must report even inside the window", due(1001, 1, T0, T0 + 1000));
    }

    @Test
    public void windowTriggersAReporDespiteFewRepeats() {
        assertTrue("a slow trickle must stay visible", due(2, 1, T0, T0 + WINDOW));
    }

    @Test
    public void windowEdgeIsInclusive() {
        assertFalse("one ms short of the window is still suppressed",
                    due(2, 1, T0, T0 + WINDOW - 1));
        assertTrue(due(2, 1, T0, T0 + WINDOW));
    }

    /////////////// the recorded decision

    @Test
    public void firstRecordReportsWithNothingSuppressed() {
        LogSuppressor s = new LogSuppressor(BURST, WINDOW);
        LogSuppressor.Decision d = s.record("k", T0);
        assertTrue(d.log);
        assertEquals(0L, d.suppressed);
    }

    @Test
    public void floodIsCollapsedAndTheCountIsReported() {
        LogSuppressor s = new LogSuppressor(BURST, WINDOW);
        s.record("k", T0);
        for (int i = 2; i <= 1000; i++) {
            LogSuppressor.Decision d = s.record("k", T0 + i);
            assertFalse("occurrence " + i + " inside the burst must be suppressed", d.log);
        }
        // The 1001st crosses the burst threshold and reports the 999 it collapsed.
        LogSuppressor.Decision d = s.record("k", T0 + 1001);
        assertTrue(d.log);
        assertEquals("every occurrence since the first must be accounted for",
                     999L, d.suppressed);
    }

    @Test
    public void windowReportAlsoCarriesTheCollapsedCount() {
        LogSuppressor s = new LogSuppressor(BURST, WINDOW);
        s.record("k", T0);
        s.record("k", T0 + 1000);
        LogSuppressor.Decision d = s.record("k", T0 + WINDOW);
        assertTrue(d.log);
        assertEquals(1L, d.suppressed);
    }

    @Test
    public void keysAreIndependent() {
        LogSuppressor s = new LogSuppressor(BURST, WINDOW);
        assertTrue(s.record("a", T0).log);
        assertTrue("a second key must not be suppressed by the first",
                   s.record("b", T0).log);
        assertFalse(s.record("a", T0 + 1).log);
        assertFalse(s.record("b", T0 + 1).log);
    }

    @Test
    public void resetForgetsEverything() {
        LogSuppressor s = new LogSuppressor(BURST, WINDOW);
        s.record("k", T0);
        s.record("k", T0 + 1);
        assertFalse(s.record("k", T0 + 2).log);
        s.reset();
        assertEquals(0, s.size());
        assertTrue("after a reset the next occurrence is a first occurrence again",
                   s.record("k", T0 + 3).log);
    }

    /////////////// bounded memory

    @Test
    public void perOccurrenceKeysDoNotGrowForever() {
        LogSuppressor s = new LogSuppressor(1, 1L);
        // Every key is unique, which is the pathological usage a caller could get wrong.
        for (int i = 0; i < LogSuppressor.MAX_KEYS * 4; i++) {
            s.record("key-" + i, T0 + i);
        }
        assertTrue("the key table must stay bounded, was " + s.size(),
                   s.size() <= LogSuppressor.MAX_KEYS);
    }

    @Test
    public void degenerateParametersAreClamped() {
        // A burst or window of zero would otherwise suppress everything after the first
        // occurrence, or never suppress anything.
        LogSuppressor s = new LogSuppressor(0, 0L);
        assertTrue(s.record("k", T0).log);
        assertTrue("a clamped burst of 1 means the next occurrence reports",
                   s.record("k", T0).log);
    }
}