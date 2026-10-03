package net.i2p.client.streaming.impl;

import org.junit.Test;

import static net.i2p.client.streaming.impl.Connection.StallState.NONE;
import static net.i2p.client.streaming.impl.Connection.StallState.STALLED;
import static net.i2p.client.streaming.impl.Connection.StallState.THROTTLED;
import static org.junit.Assert.*;

/**
 * Tests for {@link Connection#stallVerdict}'s application-limited guard.
 *
 * <p>The defect this guards against: the periodic floor check always passes
 * {@code observedBps = 0}, so an ACK-only stall rule declared a download STALLED after
 * {@code STALL_GRACE_MS} purely because it was waiting on a slow peer. That published
 * the destination to {@code StallRegistry}, and outbound tunnel selection then preferred
 * a different tunnel for the whole rotation cooldown - churning the path of a transfer
 * that was never broken. With nothing in flight there is nothing to be stuck behind.
 *
 * @since 0.9.71+
 */
public class ConnectionStallVerdictOutstandingTest {

    private static final long NOW = 5_000_000L;
    private static final long GRACE = 30_000L;
    private static final double RATIO = 0.25d;
    private static final int SAMPLES = 10;
    private static final double BASELINE = 100_000d;
    private static final double ABS_FLOOR = 1_000d;

    /** Verdict with data known to be in flight. */
    private static Connection.StallState withData(long lastProgressAt, double bps) {
        return Connection.stallVerdict(NOW, lastProgressAt, bps, RATIO, SAMPLES, BASELINE,
                                       ABS_FLOOR, GRACE, true);
    }

    /** Verdict for an application-limited connection: nothing in flight. */
    private static Connection.StallState noData(long lastProgressAt, double bps) {
        return Connection.stallVerdict(NOW, lastProgressAt, bps, RATIO, SAMPLES, BASELINE,
                                       ABS_FLOOR, GRACE, false);
    }

    @Test
    public void dataInFlightAndLongSilenceIsStalled() {
        assertEquals(STALLED, withData(NOW - GRACE - 1, 0d));
    }

    @Test
    public void dataInFlightAndRecentProgressIsNotStalled() {
        assertEquals(NONE, withData(NOW - 1000, 0d));
    }

    /** The false positive that caused the churn. */
    @Test
    public void applicationLimitedIsNeverStalled() {
        assertEquals("a connection with nothing in flight cannot be stalled",
                     NONE, noData(NOW - GRACE - 1, 0d));
    }

    @Test
    public void applicationLimitedIsNeverStalledHoweverLongTheSilence() {
        assertEquals(NONE, noData(NOW - 10 * GRACE, 0d));
        assertEquals(NONE, noData(1L, 0d));
    }

    @Test
    public void applicationLimitedIsNeverThrottled() {
        // Low goodput relative to baseline would otherwise read as throttling.
        assertEquals(NONE, noData(NOW - GRACE, 1d));
    }

    @Test
    public void theGuardIsWhatChangesTheVerdict() {
        // Same inputs, differing only in whether anything is in flight.
        long lastProgress = NOW - GRACE - 1;
        assertEquals(STALLED, withData(lastProgress, 0d));
        assertEquals(NONE, noData(lastProgress, 0d));
    }

    @Test
    public void noDataNeverStalledEvenAtTheGraceBoundary() {
        assertEquals("exactly at the grace is still not stalled without data",
                     NONE, noData(NOW - GRACE, 0d));
    }

    @Test
    public void noDataWithZeroBpsIsSafe() {
        assertEquals(NONE, noData(0L, 0d));
    }

    @Test
    public void noDataWithHealthyBpsIsSafe() {
        assertEquals(NONE, noData(NOW - 1, BASELINE));
    }

    /** The original four-argument form must keep its old behaviour for existing callers. */
    @Test
    public void legacyOverloadAssumesDataInFlight() {
        assertEquals(STALLED, Connection.stallVerdict(NOW, NOW - GRACE - 1, 0d, RATIO, SAMPLES,
                                                      BASELINE, ABS_FLOOR, GRACE));
        assertEquals(NONE, Connection.stallVerdict(NOW, NOW - 1, 0d, RATIO, SAMPLES,
                                                   BASELINE, ABS_FLOOR, GRACE));
    }

    @Test
    public void legacyOverloadStillThrottlesOnLowGoodput() {
        assertEquals(THROTTLED, Connection.stallVerdict(NOW, NOW - 1, 1d, RATIO, SAMPLES,
                                                       BASELINE, ABS_FLOOR, GRACE));
    }
}