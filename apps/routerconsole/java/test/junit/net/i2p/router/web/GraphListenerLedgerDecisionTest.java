package net.i2p.router.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import net.i2p.stat.Rate;
import org.junit.Test;

/**
 * Tests for {@link GraphListener}'s ledger and backfill decision helpers.
 *
 * <p>Two rules carry the whole ledger. The baseline makes drift a delta, because
 * {@link Rate#getCoalesceCount()} counts from the Rate's construction and a stat
 * registered at router startup has been coalescing long before the console attaches -
 * compared raw, it would report the entire pre-attach history as loss on every
 * listener, forever. And the truncation to whole seconds makes the backfill skip rule
 * agree with what the archive will actually accept, so a sample that lands in a step
 * already covered is skipped instead of throwing.
 *
 * <p>{@link Rate} reads the OS clock and needs no router context, so real retained
 * samples can be produced here; {@code GraphListener} itself needs one, so only its
 * static, context-free helpers are under test.
 *
 * @since 0.9.71+
 */
public class GraphListenerLedgerDecisionTest {

    /** A period short enough that {@code period - SLACK} is negative: always due. */
    private static final long ALWAYS_DUE_PERIOD = 1;
    /** A representative 60s graph period. */
    private static final long PERIOD = 60_000L;

    /**
     * Build a rate that has actually coalesced, so its retained-sample ring holds real
     * {@link Rate.CoalescedSample} instances oldest first.
     *
     * @param coalesces how many successful coalesces to perform
     * @return the rate, with {@code coalesces} samples retained
     */
    private static Rate coalescedRate(int coalesces) {
        Rate rate = new Rate(ALWAYS_DUE_PERIOD);
        for (int i = 0; i < coalesces; i++) {
            rate.addData(i + 1, 1);
            rate.coalesce();
        }
        return rate;
    }

    ///////////// toArchiveSecondMs

    /**
     * RRD stores one value per whole second and rejects a timestamp that is not
     * strictly newer than the last one stored, so the skip rule has to be applied at
     * second granularity. Millisecond granularity would let a sample 999ms into an
     * already-covered step look newer than it is and be attempted, and then be
     * rejected by the archive.
     */
    @Test
    public void testTruncationToArchiveSecond() {
        assertEquals(0L, GraphListener.toArchiveSecondMs(0L));
        assertEquals(1000L, GraphListener.toArchiveSecondMs(1000L));
        assertEquals(1000L, GraphListener.toArchiveSecondMs(1999L));
        assertEquals(0L, GraphListener.toArchiveSecondMs(999L));
        assertEquals(60_000L, GraphListener.toArchiveSecondMs(60_999L));
    }

    /** Truncation goes down, never up: a later instant must never look earlier. */
    @Test
    public void testTruncationNeverRoundsUp() {
        for (long ts = 0; ts < 5000; ts += 137) {
            assertTrue("truncation must not move the instant forward", GraphListener.toArchiveSecondMs(ts) <= ts);
        }
    }

    ///////////// recoverable

    /** Nothing retained means nothing to recover: the drift is permanent. */
    @Test
    public void testNothingRetainedIsUnrecoverable() {
        assertFalse(GraphListener.recoverable(0, null));
        assertFalse(GraphListener.recoverable(0, coalescedRate(0).getRecentSamples(8)));
    }

    /**
     * The boundary that matters: a retained sample in a step the listener already
     * stored is not recoverable work, and one in a later step is. Everything after the
     * ring's last remembered step is gone for good.
     */
    @Test
    public void testRecoverableIsNewerThanTheLastStoredStep() {
        Rate rate = coalescedRate(3);
        List<Rate.CoalescedSample> retained = rate.getRecentSamples(8);
        assertEquals(3, retained.size());
        long newest = retained.get(retained.size() - 1).timestampMs;
        long oldest = retained.get(0).timestampMs;
        // stored nothing: every retained step is missing
        assertTrue(GraphListener.recoverable(0, retained));
        // stored the newest retained step exactly: the rest are older and are gone
        assertFalse(GraphListener.recoverable(GraphListener.toArchiveSecondMs(newest), retained));
        // stored something inside the ring but before the newest: still recoverable
        assertTrue(GraphListener.recoverable(GraphListener.toArchiveSecondMs(oldest) - 1000, retained));
    }

    /**
     * A retained sample in the very step the listener last stored is not new work,
     * even though its raw millisecond stamp is larger: the archive holds one value per
     * step and the newer one is the correct value to keep.
     */
    @Test
    public void testSameStepSampleIsNotRecoverable() {
        Rate rate = coalescedRate(1);
        List<Rate.CoalescedSample> retained = rate.getRecentSamples(8);
        long stamp = retained.get(0).timestampMs;
        assertFalse("a sample inside the stored step is not recoverable work",
                    GraphListener.recoverable(GraphListener.toArchiveSecondMs(stamp), retained));
        // but the step immediately before it is
        assertTrue(GraphListener.recoverable(GraphListener.toArchiveSecondMs(stamp) - 1000, retained));
    }

    ///////////// sampleValue

    /**
     * A period with no events records zero rather than dividing by nothing, which is
     * the value the live path has always stored and which a backfilled step has to
     * match or the trace gains a spike.
     */
    @Test
    public void testNoEventsRecordsZero() {
        assertEquals(0d, GraphListener.sampleValue(0d, 0), 0d);
        assertEquals(0d, GraphListener.sampleValue(42d, 0), 0d);
    }

    /**
     * A rate-style stat stores the average over its events; an event-counter stat,
     * whose total always equals its event count, stores the count itself, because
     * dividing those would give a flat 1.0 line.
     */
    @Test
    public void testAveragesAndCountsAreDistinguished() {
        assertEquals(2.5d, GraphListener.sampleValue(250d, 100), 1e-9d);
        assertEquals(7d, GraphListener.sampleValue(7d, 7), 0d);
        assertEquals(1d, GraphListener.sampleValue(1d, 1), 0d);
    }

    /** The live and backfill paths must not disagree about what a period looks like. */
    @Test
    public void testEventCountIsNotAValidValue() {
        // zero is the only stored value for an empty period, whatever the total was
        assertEquals(0d, GraphListener.sampleValue(9d, 0), 0d);
    }

    ///////////// ledger baselines

    /**
     * The attach-time baseline is what stops a pre-attach history from reading as
     * drift. A rate that coalesced 40 times before any listener existed must show a
     * delta of zero to the listener that attaches next, not 40 lost steps.
     */
    @Test
    public void testBaselineMakesDriftADelta() {
        Rate rate = coalescedRate(40);
        long baseline = rate.getCoalesceCount();
        assertEquals(40, baseline);
        // three more coalesces after the baseline, none stored
        rate.addData(1, 1);
        rate.coalesce();
        rate.addData(1, 1);
        rate.coalesce();
        rate.addData(1, 1);
        rate.coalesce();
        assertEquals(3, rate.getCoalesceCount() - baseline);
    }

    /**
     * The recovery window is bounded by the ring, which is why drift beyond it is
     * permanent loss rather than something to keep retrying: the eighth sample is the
     * oldest a later backfill could ever reach.
     */
    @Test
    public void testRetentionIsBounded() {
        Rate rate = coalescedRate(20);
        // 20 coalesces happened, only a fixed handful are still addressable
        assertEquals(20, rate.getCoalesceCount());
        assertTrue("retention must be bounded", rate.getRecentSamples(1000).size() < 20);
        assertTrue(rate.getRecentSamples(1000).size() <= 8);
    }
}
