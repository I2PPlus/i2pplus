package net.i2p.router.tunnel;

import java.util.concurrent.atomic.LongAdder;
import net.i2p.router.RouterContext;

/**
 * Accumulates samples of a <em>mean</em> rate stat and emits one
 * {@code addRateData} per flush, so a hot path that samples a level once per
 * message or per 1KB fragment pays one stat lookup and one monitor per rate
 * period instead of one per sample.
 *
 * <p>Which statistics may be batched this way is a semantic question, not a
 * performance one, and this class only answers it for one kind of stat.
 * {@link net.i2p.stat.Rate#addData} accumulates a total and an event count per
 * period, and every reader divides them:
 *
 * <ul>
 * <li>A <b>mean</b> stat — one that samples a level (queue depth, fragments
 * per message, write delay) — is read as {@code total / eventCount}.  N
 * samples of {@code v} therefore report the mean of those samples, and
 * collapsing them into one sample of that mean leaves the reported
 * quantity the same up to the weighting (samples per flush rather than
 * samples per period).  That is what this class is for.</li>
 * <li>An <b>event counter</b> stat — {@code addRateData(name, 1)} per event —
 * cannot be batched here at all: collapsing N events into one sample
 * would make the period's event <em>count</em> report flushes rather than
 * events, and {@code Rate.getLastEventCount()} feeds adaptive tuning.  Such
 * a stat has to resolve a {@link net.i2p.stat.RateStat} handle once and
 * call {@code addData} per event instead.</li>
 * </ul>
 *
 * <p>Safe for concurrent {@link #add}: producers accumulate into
 * {@link LongAdder}s and only the flushing thread resets them, so a sample can
 * straddle a flush boundary — which for a mean only moves one sample from one
 * period to the next. Both flush methods drain the sample <em>count</em>
 * first, for a reason worth stating: the count is what decides whether there
 * is anything to emit, so resetting it first is what keeps a value sum from
 * outliving its own count. Draining the sums first would let a sample whose
 * count lands in this window but whose value lands in the next one leave a
 * stale value behind with no count to ever flush it, contaminating the mean
 * of some later flush. As written, every value added is consumed by exactly
 * one flush, and a boundary-straddling sample is emitted as a zero for the
 * following flush — an under-report by one sample, which is the direction the
 * truncation contract already accepts.
 *
 * @since 0.9.71+
 */
class RateStatMeanBatch {

    private final LongAdder _valueSum = new LongAdder();
    private final LongAdder _durationSum = new LongAdder();
    private final LongAdder _count = new LongAdder();

    /** Start with no pending samples. */
    RateStatMeanBatch() {}

    /**
     * Sample a level with no event duration.
     *
     * @param value the sampled level
     */
    void add(long value) {
        add(value, 0);
    }

    /**
     * Sample a level together with an event duration (the weight the rate
     * counts alongside the value).
     *
     * @param value the sampled level
     * @param eventDuration weight recorded with the value, 0 for none
     */
    void add(long value, long eventDuration) {
        _valueSum.add(value);
        _durationSum.add(eventDuration);
        _count.increment();
    }

    /**
     * Samples accumulated since the last flush.
     *
     * @return the number of unflushed samples
     */
    long pendingCount() {
        return _count.sum();
    }

    /**
     * Mean of the values sampled since the last flush, truncated toward zero.
     *
     * @return the mean sampled value, 0 when nothing is pending
     */
    long meanValue() {
        return meanOf(_valueSum.sum(), _count.sum());
    }

    /**
     * Mean of the durations sampled since the last flush, truncated toward zero.
     *
     * @return the mean event duration, 0 when nothing is pending
     */
    long meanEventDuration() {
        return meanOf(_durationSum.sum(), _count.sum());
    }

    /**
     * Divide, guarding the empty batch.
     *
     * <p>Integer division, so a mean is truncated toward zero: the error is
     * always in [0, 1) of a unit — under one unit, never a unit in the
     * over-reporting direction — on a quantity that was never more precise
     * than its integer samples. Rounding would let a stat claiming to never
     * over-report a queue depth or a write delay do exactly that.
     *
     * @param sum the accumulated samples
     * @param count how many samples were accumulated
     * @return the mean, or 0 when {@code count} is zero
     */
    static long meanOf(long sum, long count) {
        return count > 0 ? sum / count : 0;
    }

    /**
     * Emit one sample covering everything accumulated so far and start over.
     * Emits nothing when no sample is pending, because a zero-valued sample
     * would still raise the rate's event count and pull its average down.
     *
     * @param ctx the router context
     * @param name the stat to emit into
     * @return true if a sample was emitted
     */
    boolean flush(RouterContext ctx, String name) {
        long count = _count.sumThenReset();
        if (count <= 0) {
            return false;
        }
        long value = _valueSum.sumThenReset() / count;
        _durationSum.sumThenReset();
        ctx.statManager().addRateData(name, value);
        return true;
    }

    /**
     * As {@link #flush(RouterContext, String)}, but records the accumulated
     * event durations against the rate as well.  Use only where the call sites
     * being collapsed passed a duration, so the rate's event time keeps the
     * same meaning.
     *
     * @param ctx the router context
     * @param name the stat to emit into
     * @return true if a sample was emitted
     */
    boolean flushWithEventDuration(RouterContext ctx, String name) {
        long count = _count.sumThenReset();
        if (count <= 0) {
            return false;
        }
        long value = _valueSum.sumThenReset() / count;
        long duration = _durationSum.sumThenReset() / count;
        ctx.statManager().addRateData(name, value, duration);
        return true;
    }

    /**
     * Drop everything accumulated so far.
     *
     * @since 0.9.71+
     */
    void reset() {
        _count.reset();
        _valueSum.reset();
        _durationSum.reset();
    }
}
