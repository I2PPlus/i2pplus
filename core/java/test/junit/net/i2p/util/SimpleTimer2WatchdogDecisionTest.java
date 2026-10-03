package net.i2p.util;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

import net.i2p.util.SimpleTimer2.WatchdogDecision;

/**
 * Tests the saturation watchdog decision logic in {@link SimpleTimer2}.
 *
 * <p>The watchdog exists because a ScheduledThreadPoolExecutor never grows past
 * its core size: two blocked events stop every periodic event on the timer and
 * report nothing at all. These tests pin the properties that make its WARN
 * worth having - it must stay quiet on a busy-but-healthy pool, it must fire on
 * a wedged one, and it must fire <b>once per episode</b> however long the wedge
 * lasts, or it just becomes log noise that gets ignored.
 *
 * <p>Pure decision functions only. The watchdog thread, its interval and the
 * executor it samples are exercised by a running router, not by a unit test.
 *
 * @since 0.9.71+
 */
public class SimpleTimer2WatchdogDecisionTest {

    private static final int POOL = 2;
    private static final int BACKLOG = SimpleTimer2.WATCHDOG_MIN_QUEUE;
    /** Deeper than any backlog worth reporting: only occupancy can make it a signal. */
    private static final int DEEP_QUEUE = SimpleTimer2.WATCHDOG_MIN_QUEUE + 4;
    private static final int SAT_THRESHOLD = SimpleTimer2.WATCHDOG_SATURATION_SAMPLES;
    private static final int STALL_THRESHOLD = SimpleTimer2.WATCHDOG_STALL_SAMPLES;

    /**
     * Replay a sample sequence through the same bookkeeping
     * SimpleTimer2.SaturationWatchdog.checkSaturation() performs, and count the
     * samples that report. The pure function alone cannot express the throttle,
     * since "was this episode reported" is state; this is the state machine.
     *
     * @param saturated per-sample saturation state, true meaning a full pool with a backlog
     * @return the number of samples that returned REPORT
     */
    private static int countSaturationReports(boolean[] saturated) {
        int reports = 0;
        int samples = 0;
        boolean reported = false;
        for (boolean isSaturated : saturated) {
            WatchdogDecision rv = SimpleTimer2.evaluateSaturation(POOL, POOL,
                            isSaturated ? BACKLOG : 0, samples, SAT_THRESHOLD, reported);
            if (rv == WatchdogDecision.OK) {
                samples = 0;
                reported = false;
                continue;
            }
            samples++;
            if (rv == WatchdogDecision.REPORT) {
                reports++;
                reported = true;
            }
        }
        return reports;
    }

    /**
     * Replay a sequence of completed-task counts through the bookkeeping
     * SimpleTimer2.SaturationWatchdog.checkStall() performs, and count the
     * samples that report.
     *
     * @param completed per-sample completed-task count
     * @return the number of samples that returned REPORT
     */
    private static int countStallReports(long[] completed) {
        int reports = 0;
        int samples = 0;
        boolean reported = false;
        long last = 0;
        for (long count : completed) {
            WatchdogDecision rv = SimpleTimer2.evaluateStall(count, last, samples,
                                                             STALL_THRESHOLD, reported, 10_000L);
            last = count;
            if (rv == WatchdogDecision.OK) {
                samples = 0;
                reported = false;
                continue;
            }
            samples++;
            if (rv == WatchdogDecision.REPORT) {
                reports++;
                reported = true;
            }
        }
        return reports;
    }

    // saturation: what counts as a signal

    /** An idle pool is never saturated, whatever the streak says. */
    @Test
    public void testIdlePoolIsNotSaturated() {
        assertEquals(WatchdogDecision.OK, SimpleTimer2.evaluateSaturation(0, POOL, DEEP_QUEUE, 100, SAT_THRESHOLD, false));
    }

    /** One busy worker of two is ordinary concurrency, not a wedge. */
    @Test
    public void testPartlyBusyPoolIsNotSaturated() {
        assertEquals(WatchdogDecision.OK, SimpleTimer2.evaluateSaturation(POOL - 1, POOL, DEEP_QUEUE, 100, SAT_THRESHOLD, false));
    }

    /**
     * A full pool with only a trivial queue is a coincidence, not a backlog -
     * and the queue normally holds delayed events that are not due yet anyway.
     * This is the boundary just below {@link SimpleTimer2#WATCHDOG_MIN_QUEUE}.
     */
    @Test
    public void testFullPoolBelowMinQueueIsNotSaturated() {
        assertEquals(WatchdogDecision.OK, SimpleTimer2.evaluateSaturation(POOL, POOL, SimpleTimer2.WATCHDOG_MIN_QUEUE - 1, 100, SAT_THRESHOLD, false));
    }

    /**
     * The other half of the boundary: a full pool with exactly the required
     * backlog is a signal, but not yet a reportable one.
     */
    @Test
    public void testFullPoolAtMinQueueIsASignal() {
        assertEquals(WatchdogDecision.PENDING, SimpleTimer2.evaluateSaturation(POOL, POOL, SimpleTimer2.WATCHDOG_MIN_QUEUE, 0, SAT_THRESHOLD, false));
    }

    /**
     * A worker can be counted above the pool size while one is being replaced,
     * and that is still a full pool.
     */
    @Test
    public void testActiveAbovePoolSizeIsSaturated() {
        assertEquals(WatchdogDecision.PENDING, SimpleTimer2.evaluateSaturation(POOL + 1, POOL, BACKLOG, 0, SAT_THRESHOLD, false));
    }

    /**
     * A shut-down executor reports a pool size of zero. Reporting that as
     * saturation would fire on every stopped timer.
     */
    @Test
    public void testShutDownPoolIsNotSaturated() {
        assertEquals(WatchdogDecision.OK, SimpleTimer2.evaluateSaturation(0, 0, 0, 100, SAT_THRESHOLD, false));
    }

    // saturation: the threshold

    /** Every sample short of the threshold only accumulates the streak. */
    @Test
    public void testNotReportedBelowThreshold() {
        for (int samples = 0; samples < SAT_THRESHOLD; samples++)
            assertEquals("sample " + samples + " is within the threshold",
                         WatchdogDecision.PENDING,
                         SimpleTimer2.evaluateSaturation(POOL, POOL, BACKLOG, samples, SAT_THRESHOLD, false));
    }

    /**
     * The threshold is exact: "more than N samples" reports on the N+1th, so a
     * streak of exactly N preceding samples is the first that reports.
     */
    @Test
    public void testReportedAtExactThreshold() {
        assertEquals(WatchdogDecision.REPORT, SimpleTimer2.evaluateSaturation(POOL, POOL, BACKLOG, SAT_THRESHOLD, SAT_THRESHOLD, false));
    }

    /**
     * A permanently wedged pool must produce one WARN, not one per sample. This
     * is the throttle edge the episode bookkeeping exists for.
     */
    @Test
    public void testReportedOnceThenThrottled() {
        for (int samples = SAT_THRESHOLD; samples < SAT_THRESHOLD + 1000; samples++)
            assertEquals("streak of " + samples + " must not re-report an episode",
                         WatchdogDecision.PENDING,
                         SimpleTimer2.evaluateSaturation(POOL, POOL, BACKLOG, samples, SAT_THRESHOLD, true));
    }

    /** A long saturation episode reports exactly once. */
    @Test
    public void testOneReportPerSaturationEpisode() {
        boolean[] wedged = new boolean[1000];
        Arrays.fill(wedged, true);
        assertEquals(1, countSaturationReports(wedged));
    }

    /**
     * A burst that clears before the threshold is reached never reports, which
     * is what keeps ordinary concurrency chatter out of the log. The threshold
     * is "more than N samples", so N samples is the longest silent burst.
     */
    @Test
    public void testShortBurstNeverReports() {
        boolean[] burst = new boolean[SAT_THRESHOLD];
        Arrays.fill(burst, true);
        assertEquals(0, countSaturationReports(burst));
    }

    /** One sample longer than the threshold is reportable: that is the boundary. */
    @Test
    public void testOneSampleLongerThanThresholdReports() {
        boolean[] burst = new boolean[SAT_THRESHOLD + 1];
        Arrays.fill(burst, true);
        assertEquals(1, countSaturationReports(burst));
    }

    /** A healthy pool that is briefly full reports nothing at all. */
    @Test
    public void testHealthyPoolNeverReports() {
        boolean[] busy = new boolean[1000];
        for (int i = 0; i < busy.length; i++)
            busy[i] = (i % 3 == 0);  // full now and then, never for long
        assertEquals(0, countSaturationReports(busy));
    }

    /**
     * One healthy sample ends the episode, so a second wedge reports again - a
     * pool that wedges twice must not be reported once.
     */
    @Test
    public void testNewEpisodeReportsAgain() {
        boolean[] twoEpisodes = new boolean[(SAT_THRESHOLD + 1) * 2 + 1];
        Arrays.fill(twoEpisodes, 0, SAT_THRESHOLD + 1, true);
        twoEpisodes[SAT_THRESHOLD + 1] = false;   // one healthy sample between
        Arrays.fill(twoEpisodes, SAT_THRESHOLD + 2, twoEpisodes.length, true);
        assertEquals(2, countSaturationReports(twoEpisodes));
    }

    /** A wedge followed by a healthy pool reports once, and the healthy tail adds nothing. */
    @Test
    public void testRecoveryEndsEpisode() {
        boolean[] wedgeThenHealthy = new boolean[(SAT_THRESHOLD + 1) + 100];
        Arrays.fill(wedgeThenHealthy, 0, SAT_THRESHOLD + 1, true);
        assertEquals(1, countSaturationReports(wedgeThenHealthy));
    }

    // stall: nothing finishing at all

    /** Progress ends the episode immediately, however long it was. */
    @Test
    public void testProgressEndsStallEpisode() {
        assertEquals(WatchdogDecision.OK, SimpleTimer2.evaluateStall(11, 10, 100, STALL_THRESHOLD, true, 10_000L));
    }

    /**
     * A timer that has never run anything is idle, not wedged. Reporting it
     * would fire on every timer with nothing scheduled and bury the real
     * reports.
     */
    @Test
    public void testNeverRunExecutorIsNotStalled() {
        assertEquals(WatchdogDecision.OK, SimpleTimer2.evaluateStall(0, 0, 100, STALL_THRESHOLD, false, 10_000L));
    }

    /** No progress short of the threshold only accumulates the streak. */
    @Test
    public void testStallNotReportedBelowThreshold() {
        for (int samples = 0; samples < STALL_THRESHOLD; samples++)
            assertEquals("sample " + samples + " is within the threshold",
                         WatchdogDecision.PENDING,
                         SimpleTimer2.evaluateStall(10, 10, samples, STALL_THRESHOLD, false, 10_000L));
    }

    /** The stall threshold is exact, as the saturation threshold is. */
    @Test
    public void testStallReportedAtExactThreshold() {
        assertEquals(WatchdogDecision.REPORT, SimpleTimer2.evaluateStall(10, 10, STALL_THRESHOLD, STALL_THRESHOLD, false, 10_000L));
    }

    /** A stall already reported in this episode is not reported again. */
    @Test
    public void testStallReportedOnceThenThrottled() {
        for (int samples = STALL_THRESHOLD; samples < STALL_THRESHOLD + 1000; samples++)
            assertEquals(WatchdogDecision.PENDING,
                         SimpleTimer2.evaluateStall(10, 10, samples, STALL_THRESHOLD, true, 10_000L));
    }

    /** A long stall reports exactly once. */
    @Test
    public void testOneReportPerStallEpisode() {
        long[] frozen = new long[1000];
        Arrays.fill(frozen, 42L);
        assertEquals(1, countStallReports(frozen));
    }

    /**
     * The stall check must not double-report the wedge the saturation check has
     * already reported: a pool that is blocked but still finishes the odd task
     * is one incident, and the stall threshold is set above the saturation
     * threshold precisely so it stays quiet until the freeze is total.
     */
    @Test
    public void testSlowProgressIsNotAStall() {
        int period = STALL_THRESHOLD - 1;
        long[] creeping = new long[1000];
        for (int i = 0; i < creeping.length; i++)
            creeping[i] = 42 + (i / period);
        assertEquals("one task finishing every " + period + " samples is not a freeze",
                     0, countStallReports(creeping));
    }

    // duration reported to the log

    /** A single sample says nothing about duration, so it reports zero. */
    @Test
    public void testEpisodeForMsNeedsTwoSamples() {
        assertEquals(0, SimpleTimer2.episodeForMs(0));
        assertEquals(0, SimpleTimer2.episodeForMs(1));
    }

    /**
     * N samples are at least one interval apart, so the reported duration is the
     * lower bound (N-1) intervals. Overstating it would send whoever reads the
     * WARN looking for a longer outage than actually happened.
     */
    @Test
    public void testEpisodeForMsIsIntervalCountLessOne() {
        long interval = SimpleTimer2.WATCHDOG_INTERVAL_MS;
        assertEquals(interval, SimpleTimer2.episodeForMs(2));
        assertEquals(2 * interval, SimpleTimer2.episodeForMs(3));
        assertEquals(3 * interval, SimpleTimer2.episodeForMs(4));
        assertEquals(10 * interval, SimpleTimer2.episodeForMs(11));
    }

    /** The threshold must be reachable in a couple of minutes, not hours. */
    @Test
    public void testThresholdIsReachableInMinutes() {
        assertTrue("a wedge must be reported within a couple of minutes",
                   (SAT_THRESHOLD * (long) SimpleTimer2.WATCHDOG_INTERVAL_MS) <= 2 * 60 * 1000L);
    }
}
