package net.i2p.router.tunnel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 *  Tests the per-flush stat accumulator behind the tunnel hot paths.
 *
 *  <p>The point of {@link RateStatMeanBatch} is that collapsing N samples of a
 *  mean-valued stat into one sample of their mean leaves the number a reader
 *  computes ({@code total / eventCount}) unchanged. These pin that arithmetic
 *  and the guards around it, because the alternative — dropping the batching —
 *  puts a stat lookup and a monitor acquisition per rate period back on the
 *  per-fragment path.
 *
 *  <p>No RouterContext is needed: the arithmetic is reached through the pure
 *  accessors, and a flush with nothing pending returns before it would touch
 *  one.
 */
public class RateStatMeanBatchTest {

    /** An empty batch has no mean rather than dividing by zero. */
    @Test
    public void emptyBatchHasNoMean() {
        assertEquals(0, RateStatMeanBatch.meanOf(0, 0));
        assertEquals(0, RateStatMeanBatch.meanOf(999, 0));
    }

    /** The mean of identical samples is that sample, so the graph does not move. */
    @Test
    public void identicalSamplesCollapseToThemselves() {
        RateStatMeanBatch batch = new RateStatMeanBatch();
        for (int i = 0; i < 7; i++) {batch.add(12);}
        assertEquals(7, batch.pendingCount());
        assertEquals(12, batch.meanValue());
    }

    /**
     *  The case that matters for a level stat: a burst of differing samples
     *  collapses to their mean, which is what {@code total / eventCount} said
     *  when each sample was reported on its own.
     */
    @Test
    public void differingSamplesCollapseToTheirMean() {
        RateStatMeanBatch batch = new RateStatMeanBatch();
        batch.add(3);
        batch.add(4);
        batch.add(5);
        long sum = 3 + 4 + 5;
        long count = 3;
        assertEquals("the batched sample must equal the per-sample average",
                     sum / count, batch.meanValue());
    }

    /** The mean is truncated toward zero, never rounded up. */
    @Test
    public void meanTruncatesRatherThanRounds() {
        RateStatMeanBatch batch = new RateStatMeanBatch();
        batch.add(1);
        batch.add(2);
        // 3/2 = 1.5: 1 truncated, 2 rounded half up.
        assertEquals(1, batch.meanValue());
        // 5/2 = 2.5: 2 truncated, 3 rounded half up. The leftover half must be
        // dropped, not kept as a partial unit and not rounded to 3.
        assertEquals(2, RateStatMeanBatch.meanOf(5, 2));
        // 7/2 = 3.5: 3 truncated, 4 rounded half up. Separate case from 5/2 so
        // a round-half-to-even implementation (which would return 2 here)
        // cannot pass on the strength of the 5/2 case alone.
        assertEquals(3, RateStatMeanBatch.meanOf(7, 2));
        // Truncation is toward zero on the full long range, negatives included.
        assertEquals(-2, RateStatMeanBatch.meanOf(-5, 2));
    }

    /** A duration is averaged too, so a weight-carrying stat keeps its meaning. */
    @Test
    public void eventDurationIsAveragedAlongsideTheValue() {
        RateStatMeanBatch batch = new RateStatMeanBatch();
        batch.add(100, 1024);
        batch.add(200, 2048);
        assertEquals(150, batch.meanValue());
        assertEquals(1536, batch.meanEventDuration());
    }

    /** The no-duration form must not silently record a duration. */
    @Test
    public void valueOnlySamplesRecordNoDuration() {
        RateStatMeanBatch batch = new RateStatMeanBatch();
        batch.add(100);
        batch.add(200);
        assertEquals(150, batch.meanValue());
        assertEquals(0, batch.meanEventDuration());
    }

    /**
     *  A flush with nothing pending must emit nothing. A zero-valued sample
     *  still raises the rate's event count, which would drag its average down
     *  for a gateway that pumped but enqueued nothing.
     */
    @Test
    public void flushWithNothingPendingEmitsNothing() {
        RateStatMeanBatch batch = new RateStatMeanBatch();
        assertFalse("an empty flush must not touch the stat manager",
                    batch.flush(null, "tunnel.obgw.queueSize"));
        assertEquals(0, batch.pendingCount());
    }

    /** Samples are counted per add, not per flush. */
    @Test
    public void pendingCountTracksSamplesNotFlushes() {
        RateStatMeanBatch batch = new RateStatMeanBatch();
        for (int i = 0; i < 1000; i++) {batch.add(2);}
        assertEquals(1000, batch.pendingCount());
    }

    /** Reset drops the accumulated samples for a discarded flush. */
    @Test
    public void resetDiscardsPendingSamples() {
        RateStatMeanBatch batch = new RateStatMeanBatch();
        batch.add(50, 7);
        batch.reset();
        assertEquals(0, batch.pendingCount());
        assertEquals(0, batch.meanValue());
        assertEquals(0, batch.meanEventDuration());
    }

    /**
     *  A zero level is a real sample and must not be dropped: an idle gateway
     *  that drained to zero still had that depth observed.
     */
    @Test
    public void zeroIsASampleNotAnAbsence() {
        RateStatMeanBatch batch = new RateStatMeanBatch();
        batch.add(0);
        assertEquals(1, batch.pendingCount());
        assertEquals(0, batch.meanValue());
        assertTrue(batch.pendingCount() > 0);
    }
}
