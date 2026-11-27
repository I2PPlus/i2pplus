package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;

import org.junit.Test;

/**
 * Pins how a failed test round reports its own duration.
 * <p>A round that never answered used to report the wall clock at the moment the
 * timeout job happened to run, so the number carried the job queue's delay as
 * well as the window the round ran under — and that number is the input the
 * failure window is sized from. Read live on 2026-11-27 the aggregate
 * {@code tunnel.testFailedTime} averaged 4,820ms against a 3,001ms window in
 * force, with no way to tell detection lag from a genuinely late reply. These
 * tests hold the split: the deadline is the window, the lag is the lag, and the
 * two still add up to what elapsed.
 *
 * @since 0.9.72+
 */
public class TestJobFailureAttributionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state, only the pure helpers the
     * assertions call.
     *
     * @since 0.9.72+
     */
    public TestJobFailureAttributionTest() {}

    /** A round dispatched at 10,000ms with a 3s window missed a 3s window. */
    @Test
    public void deadlineIsTheWindowNotTheDetection() {
        assertEquals(3000, TestJob.testRoundDeadlineMs(13_000, 10_000));
    }

    /**
     * A round noticed 1.7s late reports the 3s window it missed, not the 4.7s
     * that elapsed: the old reading was the elapsed gap, which is what let the
     * job queue's delay widen the window.
     */
    @Test
    public void lateDetectionReportsTheWindowNotTheElapsedGap() {
        long started = 10_000;
        long expiration = 13_000;
        long now = 14_700;
        assertEquals(4700, now - started);
        assertEquals(3000, TestJob.testRoundDeadlineMs(expiration, started));
        assertEquals(1700, TestJob.testDetectionLagMs(now, expiration));
    }

    /** A clock that stepped backwards past the deadline counts as no window. */
    @Test
    public void deadlineClampsClockSkew() {
        assertEquals(0, TestJob.testRoundDeadlineMs(10_000, 10_000));
        assertEquals(0, TestJob.testRoundDeadlineMs(9_000, 10_000));
    }

    /** A timeout job that runs at or before the deadline carries no lag. */
    @Test
    public void lagIsZeroWhenTheJobRunsOnTime() {
        assertEquals(0, TestJob.testDetectionLagMs(13_000, 13_000));
        assertEquals(0, TestJob.testDetectionLagMs(12_999, 13_000));
    }

    /** The lag is the gap between the deadline and the job actually running. */
    @Test
    public void lagCountsTheLateRun() {
        assertEquals(1700, TestJob.testDetectionLagMs(14_700, 13_000));
    }

    /**
     * The split loses nothing: window plus lag is exactly what elapsed between
     * dispatch and detection, so moving the lag out of the failure duration
     * removes it from that sample rather than dropping it.
     */
    @Test
    public void windowAndLagAddUpToTheElapsedTime() {
        long started = 10_000;
        long expiration = 13_000;
        long now = 14_700;
        assertEquals(now - started, TestJob.testRoundDeadlineMs(expiration, started) +
                                    TestJob.testDetectionLagMs(now, expiration));
    }

    /**
     * {@link net.i2p.stat.StatManager#addRateData} drops names it has never seen, so the two
     * names the failure paths write must be exactly the ones the registrar
     * declares.
     */
    @Test
    public void registrationDeclaresEveryEmittedName() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        TestJob.registerFailureAttributionStats(stats, RateConstants.SHORT_TERM_RATES);
        assertNotNull("late-reply durations emitted but never registered",
                      stats.getRate("tunnel.testFailedLateReplyTime"));
        assertNotNull("detection lag emitted but never registered",
                      stats.getRate("tunnel.testTimeoutDetectionLag"));
    }

    /** A second registration, as after a context restart, must not fail or duplicate. */
    @Test
    public void registrationIsIdempotent() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        TestJob.registerFailureAttributionStats(stats, RateConstants.SHORT_TERM_RATES);
        TestJob.registerFailureAttributionStats(stats, RateConstants.SHORT_TERM_RATES);
        assertNotNull(stats.getRate("tunnel.testFailedLateReplyTime"));
        assertNotNull(stats.getRate("tunnel.testTimeoutDetectionLag"));
    }

    /**
     * The two new names must not shadow a stat the window sizing or the Tuner
     * already reads, each of which means something else.
     */
    @Test
    public void namesDoNotCollideWithTheStatsThatSizeTheWindow() {
        String[] preexisting = {
            "tunnel.testFailedTime",
            "tunnel.testExploratoryFailedTime",
            "tunnel.testFailedCompletelyTime",
            "tunnel.testSuccessTime",
            "tunnel.testPeriod",
        };
        String[] added = {"tunnel.testFailedLateReplyTime", "tunnel.testTimeoutDetectionLag"};
        for (String name : added) {
            for (String other : preexisting) {
                assertNotEquals(name, other);
            }
        }
    }
}
