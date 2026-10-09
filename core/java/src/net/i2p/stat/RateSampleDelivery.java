package net.i2p.stat;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Delivers coalesced rate samples to {@link RateSummaryListener}s off the coalesce thread.
 *
 * <p>This exists because the natural place to notify a summary listener — inline in
 * {@link Rate#coalesce()} — puts two things on one thread that must not be there.
 * {@code coalesce()} runs on the shared {@code SimpleTimer2} pool, which has a
 * fixed two threads for every periodic event in the router, and it is reached from
 * {@link StatManager#coalesceStats()} while that method holds the {@code StatManager}
 * monitor. A listener that touches storage does I/O: the graph listeners write a
 * sample into an RRD database, which ultimately writes a {@code FileChannel} to a
 * shared scratch file. A slow or stalled disk therefore blocks a pool thread while
 * it holds two monitors, and the whole router's periodic scheduling degrades with
 * it — silently, because a blocked thread neither throws nor logs.
 *
 * <p>Moving delivery here decouples the two concerns. The counters a rate exposes
 * ({@code getLastTotalValue()} and friends) are still updated synchronously under
 * the rate's own monitor, so nothing that reads coalesced statistics changes
 * behaviour; only the listener callback moves. A stalled disk now costs graph
 * history and nothing else.
 *
 * <p>The queue is bounded, and what happens when it fills is the whole point of
 * this class. Storage can only hold one value per archive step, so two samples
 * for the same step are not two pieces of history — only the newer one is
 * writable. A same-step sample therefore <em>supersedes</em> the one behind it
 * (see {@link #submit}) instead of queueing behind it: that is not a loss, and
 * counting it as one would be a lie. Only when the hard capacity is reached is
 * something genuinely evicted, which is counted by {@link #getOverruns()}.
 *
 * <p>Samples for a single rate are delivered in order because delivery is
 * single-threaded and the queue is FIFO, which {@code Rate} relies on: an RRD
 * rejects a sample whose timestamp does not advance.
 *
 * <p>The consumer holds no monitor when it calls a listener, and never takes a
 * {@code Rate}, {@code RateStat} or {@code StatManager} lock, so a listener
 * blocked in I/O cannot keep a rate from coalescing.
 *
 * @since 0.9.71+
 */
class RateSampleDelivery {

    /** Pending samples retained before the oldest is evicted. Sized to cover a
     * few coalesce cycles of the whole rate table at once; the graph listeners
     * are the only consumers in practice. */
    static final int DEFAULT_CAPACITY = 4096;

    /** How long {@link #shutdown()} waits for the consumer before abandoning
     * whatever is still queued. */
    private static final long SHUTDOWN_JOIN_MS = 2000;

    /** One coalesced sample, detached from the {@link Rate} that produced it. */
    private static final class Sample {
        final RateSummaryListener listener;
        final double totalValue;
        final long eventCount;
        final double totalEventTime;
        final long period;
        /** The sample's archive step stamp, carried from {@link Rate#coalesce()}. */
        final long timestampMs;
        /** {@code timestampMs / period}: the archive step this sample is for. */
        final long step;

        Sample(RateSummaryListener listener, double totalValue, long eventCount,
               double totalEventTime, long period, long timestampMs) {
            this.listener = listener;
            this.totalValue = totalValue;
            this.eventCount = eventCount;
            this.totalEventTime = totalEventTime;
            this.period = period;
            this.timestampMs = timestampMs;
            this.step = Rate.archiveStep(timestampMs, period);
        }
    }

    private final LinkedBlockingDeque<Sample> _queue;
    /** Retained capacity, kept for the overrun report. */
    private final int _capacity;
    private final AtomicBoolean _running = new AtomicBoolean(true);
    /** Overruns reported so far; see {@link #reportOverruns()} for the consumer-only reader. */
    private final AtomicLong _overruns = new AtomicLong();
    private final AtomicLong _superseded = new AtomicLong();
    private final AtomicLong _delivered = new AtomicLong();
    /**
     * Overruns already written to the log. Touched only by the consumer thread,
     * so it needs no synchronisation.
     */
    private long _reportedOverruns;
    private final I2PAppContext _context;
    private final Thread _thread;

    /**
     * @param context router context, used for the logger; may be null
     */
    RateSampleDelivery(I2PAppContext context) {
        this(context, DEFAULT_CAPACITY);
    }

    /**
     * @param context router context, used for the logger; may be null
     * @param capacity pending samples to retain before the oldest is evicted
     */
    RateSampleDelivery(I2PAppContext context, int capacity) {
        _context = context;
        _capacity = Math.max(1, capacity);
        _queue = new LinkedBlockingDeque<>(_capacity);
        _thread = new Thread(this::run, "StatSampleDelivery");
        _thread.setDaemon(true);
        _thread.start();
        if (context != null)
            context.addShutdownTask(new Shutdown());
    }

    /**
     * Queue one coalesced sample for delivery.
     *
     * <p>Never blocks and never throws: this is on the coalesce path, where a
     * stall is the failure being guarded against.
     *
     * <p>If the tail entry is the same listener and belongs to the same archive
     * step, it is replaced rather than appended to. Storage holds one value per
     * step, so the older of the two can never be written and the newer is the
     * only one worth delivering; the replacement is counted by
     * {@link #getSuperseded()} so the decision is visible rather than silent.
     * Only a hard capacity overflow evicts anything, and that is counted by
     * {@link #getOverruns()}.
     *
     * @param listener the listener to notify; a null listener is refused rather
     * than queued, because most rates have no listener and an
     * entry that fails on the consumer thread costs a logged
     * exception per rate per period
     * @param totalValue the period's total value
     * @param eventCount the period's event count
     * @param totalEventTime the period's accumulated event time
     * @param period the rate period in ms
     * @param timestampMs the sample's archive step stamp, as stamped by
     * {@link Rate#coalesce()}
     * @return true if the sample was queued or superseded an existing entry,
     * false if the listener was null, if it was rejected after
     * {@link #shutdown()}, or if an eviction could not be completed
     * @since 0.9.71+
     */
    boolean submit(RateSummaryListener listener, double totalValue, long eventCount,
                   double totalEventTime, long period, long timestampMs) {
        // The listener check is deliberate belt-and-braces: Rate.coalesce() already
        // skips listener-less rates, and most rates have no listener, but a caller
        // that forgets would otherwise enqueue a null listener and fail on the
        // consumer thread once per coalesce cycle per rate. That failure mode was
        // observed in production as thousands of logged NullPointerExceptions.
        if (!_running.get() || listener == null)
            return false;
        Sample s = new Sample(listener, totalValue, eventCount, totalEventTime, period, timestampMs);
        if (supersedeTail(s))
            return true;
        if (_queue.offerLast(s))
            return true;
        // Hard capacity. This is real loss and must never be absorbed silently,
        // so it is counted; the report is written by the consumer thread (see
        // reportOverruns) because this runs under the Rate and StatManager
        // monitors, where nothing that can block is allowed.
        _queue.pollFirst();
        boolean queued = _queue.offerLast(s);
        if (queued)
            _overruns.incrementAndGet();
        return queued;
    }

    /**
     * Replace the tail entry if it is the same listener for the same archive
     * step. O(1): one peek, no scan, and identity rather than equality on the
     * listener because two distinct listeners for one rate compare equal but
     * only one of them is the registered instance.
     *
     * @param s the incoming sample
     * @return true if the tail was replaced by {@code s}
     */
    private boolean supersedeTail(Sample s) {
        Sample tail = _queue.peekLast();
        if (tail == null || tail.listener != s.listener || tail.step != s.step)
            return false;
        // The consumer only ever removes from the head, so this returns the
        // element just peeked unless the queue held exactly that one entry and
        // the consumer took it in between; a null then simply means there is
        // nothing to supersede and the caller falls through to a plain append.
        Sample removed = _queue.pollLast();
        if (removed == null)
            return false;
        if (_queue.offerLast(s)) {
            _superseded.incrementAndGet();
            return true;
        }
        // Unreachable unless a second producer refilled the freed slot; put the
        // original back rather than losing it.
        _queue.offerLast(removed);
        return false;
    }

    /**
     * Samples evicted because the queue was at capacity and the next sample
     * could not be held. This is the counter that must stay zero: nonzero means
     * graph history has a hole in it, and the router's own rate counters are
     * unaffected because they are updated before delivery.
     *
     * @return the running overrun count
     * @since 0.9.71+
     */
    long getOverruns() {
        return _overruns.get();
    }

    /**
     * Samples replaced by a newer sample for the same archive step and listener.
     *
     * <p>Not a loss: storage keeps one value per step, so the replaced sample
     * could not have been written. Counted because "how many samples were merged
     * away" is otherwise unanswerable from the outside.
     *
     * @return the running supersession count
     * @since 0.9.71+
     */
    long getSuperseded() {
        return _superseded.get();
    }

    /** Samples handed to a listener. */
    long getDelivered() {
        return _delivered.get();
    }

    /** Samples waiting to be delivered. */
    int getPending() {
        return _queue.size();
    }

    /**
     * How long the oldest pending sample has been waiting, from the step stamp
     * {@link Rate#coalesce()} gave it.
     *
     * <p>This is the live signal that the consumer has stopped making progress,
     * and it keeps rising while a listener is wedged — which is exactly when
     * {@link #getOverruns()} stops being able to report anything useful.
     *
     * @return the age in ms of the head of the queue, or 0 when nothing is pending
     * @since 0.9.71+
     */
    long getOldestPendingAgeMs() {
        Sample head = _queue.peekFirst();
        if (head == null)
            return 0;
        // A stamp from the step just past the current wall clock is normal for a
        // rate whose period has only just turned over, so clamp rather than
        // report a negative age.
        long age = System.currentTimeMillis() - head.timestampMs;
        return age > 0 ? age : 0;
    }

    /** True once {@link #shutdown()} has been called. */
    boolean isStopped() {
        return !_running.get();
    }

    /**
     * Stop accepting samples, then wait briefly for the consumer to finish what it
     * already holds.
     *
     * <p>The join is what makes teardown ordering meaningful. Without it, a caller
     * that closes the listeners' databases immediately after this returns can race
     * an in-flight delivery and fail a write against a closed backend. The wait is
     * bounded because the consumer may be parked inside a listener's I/O, and a
     * shutdown must not hang on a stalled disk; anything still queued after the
     * timeout is abandoned, which is the correct trade at shutdown.
     */
    void shutdown() {
        if (!_running.compareAndSet(true, false))
            return;
        _thread.interrupt();
        try {
            _thread.join(SHUTDOWN_JOIN_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (_running.get() || !_queue.isEmpty()) {
            reportOverruns();
            Sample s;
            try {
                s = _queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                if (!_running.get())
                    continue;
                Thread.currentThread().interrupt();
                return;
            }
            if (s == null)
                continue;
            RateSummaryListener lsnr = s.listener;
            if (lsnr == null)
                continue;
            // No monitor is held here, and none is taken: a listener that blocks
            // in I/O must not be able to stop a rate coalescing.
            // A listener that throws must not take the consumer down either: the
            // remaining samples are other rates' history, and losing the thread
            // would silently stop delivery for all of them.
            try {
                lsnr.add(s.totalValue, s.eventCount, s.totalEventTime, s.period);
                _delivered.incrementAndGet();
            } catch (Throwable t) {
                Log log = _context != null ? _context.logManager().getLog(RateSampleDelivery.class) : null;
                if (log != null && log.shouldWarn())
                    log.warn("Rate summary listener threw; delivery continues: " + t, t);
            }
        }
    }

    /**
     * Write any overrun the consumer has not yet reported, at ERROR.
     *
     * <p>Reported here rather than from {@link #submit} because submit runs under
     * the {@code Rate} and {@code StatManager} monitors on the shared coalesce
     * timer, where a log call that can block is not allowed. The cost is that a
     * wedged consumer delays its own report until it unblocks — acceptable, because
     * {@link #getOldestPendingAgeMs()} and {@link #getOverruns()} expose the same
     * condition to the console's ledger continuously and without needing a log
     * line to have been written first. Not rate-limited: an overrun must never be
     * absorbed, so every advance of the counter produces a record.
     */
    private void reportOverruns() {
        long total = _overruns.get();
        if (total <= _reportedOverruns)
            return;
        _reportedOverruns = total;
        Log log = _context != null ? _context.logManager().getLog(RateSampleDelivery.class) : null;
        if (log != null && log.shouldError()) {
            log.error("Rate sample delivery queue exceeded " + _capacity
                      + " pending samples: " + total + " oldest samples evicted, "
                      + _superseded.get() + " superseded, oldest pending age "
                      + getOldestPendingAgeMs() + "ms. Graph history has gaps; the"
                      + " rate counters are unaffected because they are updated"
                      + " before delivery.");
        }
    }

    /** Stops the consumer on router shutdown. */
    private class Shutdown implements Runnable {
        @Override
        public void run() {
            shutdown();
        }
    }
}
