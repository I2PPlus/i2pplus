package net.i2p.stat;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

import java.util.Properties;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple rate calculator for periodically sampled data points - determining an
 * average value over a period, the number of events in that period, the maximum number
 * of events (using the interval between events), and lifetime data.
 *
 * If value is always a constant, you should be using Frequency instead.
 *
 * <p><b>Step arithmetic.</b> A rate's RRD database is created with an archive step
 * of {@code period/1000}, so the archive step index a sample belongs to is exactly
 * {@link #archiveStep archiveStep}(timestampMs, period). {@link #coalesce()}
 * stamps each sample with the step it is actually <em>due</em> for rather than
 * with the current wall clock, so consecutive coalesces land in consecutive steps
 * even when the coalesce timer drifts relative to the period; see
 * {@link #coalesceDueMs}. A backlog too large to replay is collapsed into the
 * current step and counted by {@link #getCoalesceBacklogCollapses()}, because a
 * rate keeps only the current partial period and cannot reconstruct the steps it
 * missed.
 *
 * <p><b>What a listener receives.</b> {@link RateSummaryListener#add} cannot carry
 * a timestamp — its signature is fixed by implementors outside this package — so a
 * listener that writes to storage derives the sample's step from
 * <em>delivery</em> time, not from the step this class assigned it. The authoritative
 * step stamp therefore travels on the two paths that can use it: the delivery
 * queue, for same-step supersession (see {@link RateSampleDelivery}), and the
 * retained-sample ring read back by {@link #getRecentSamples(int)}, which is what
 * a healed listener backfills from.
 */
public class Rate {

    /**
     * One coalesced sample, retained so a healed listener can backfill missed steps.
     *
     * <p>The {@link #timestampMs} field is the sample's archive step stamp and is
     * the reason this class exists: {@code RateSummaryListener.add} reports no
     * timestamp, so without it a listener has no way to write the sample into the
     * step it belongs to. Fields are final because instances are published to the
     * delivery thread and to console readers.
     *
     * @since 0.9.71+
     */
    public static final class CoalescedSample {
        /** Wall-clock ms stamped at coalesce; the sample's step is this over {@link #period}. */
        public final long timestampMs;
        /** Total value accrued over the period, as handed to the summary listener. */
        public final double totalValue;
        /** Number of events in the period. */
        public final long eventCount;
        /** Accumulated event time for the period. */
        public final double totalEventTime;
        /** The rate's period in ms. */
        public final long period;

        /**
         * @param timestampMs wall-clock ms stamped at coalesce
         * @param totalValue total value accrued over the period
         * @param eventCount number of events in the period
         * @param totalEventTime accumulated event time for the period
         * @param period the rate period in ms
         */
        CoalescedSample(long timestampMs, double totalValue, long eventCount,
                        double totalEventTime, long period) {
            this.timestampMs = timestampMs;
            this.totalValue = totalValue;
            this.eventCount = eventCount;
            this.totalEventTime = totalEventTime;
            this.period = period;
        }

        @Override
        public String toString() {
            return "CoalescedSample[step=" + timestampMs / period
                 + ", ts=" + timestampMs + ", value=" + totalValue
                 + ", events=" + eventCount + ", eventTime=" + totalEventTime
                 + ", period=" + period + ']';
        }
    }

    /**
     * How many successful coalesces are retained for backfill. A fixed ring: the
     * console backfills at most a few steps after a heal, so a longer history
     * would only hold references longer without ever being read.
     */
    private static final int RECENT_SAMPLE_RING = 8;

    /*
     * Field visibility is split three ways. The four per-event fields are plain:
     * every writer (addData, coalesce, load) holds this monitor and the fields
     * are private, so their getters take it too - trading a barrier on every
     * event for one uncontended acquisition by the reader. The per-period fields
     * below stay volatile so that polled readers such as getLastEventCount()
     * remain lock-free.
     */
    private float _currentTotalValue;
    private int _currentEventCount;
    private volatile int _currentTotalEventTime;
    private volatile float _lastTotalValue;
    private volatile int _lastEventCount;
    private volatile int _lastTotalEventTime;
    private volatile float _extremeTotalValue;
    private volatile int _extremeEventCount;
    private volatile int _extremeTotalEventTime;
    private float _lifetimeTotalValue;
    private long _lifetimeEventCount;
    private volatile long _lifetimeTotalEventTime;
    /**
     * Volatile because the registering thread (console startup) writes it with the
     * unsynchronized {@link #setSummaryListener} while the coalesce and
     * clearSummaryListener threads read and write it under this rate's monitor.
     * Without volatile a clear on one thread can be invisible to a register on
     * another, and the listener can end up registered but never notified.
     */
    private volatile RateSummaryListener _graphListener;
    private RateStat _stat;

    private volatile long _lastCoalesceDate;
    /**
     * Wall clock of the last successful coalesce, kept apart from
     * {@link #_lastCoalesceDate} on purpose.
     *
     * <p>{@code _lastCoalesceDate} is now the point this rate's period grid has
     * reached, so {@code now - _lastCoalesceDate} is only the grid error — normally
     * a few milliseconds of timer jitter. It is not how long the data accrued, and
     * using it to rescale {@code _lastTotalEventTime} would divide by roughly zero
     * and inflate every event-time statistic by orders of magnitude. This field
     * carries the real elapsed span the rescale is defined against.
     */
    private volatile long _lastCoalesceNow;
    private volatile long _creationDate;
    private volatile int _period;
    /**
     * How many times {@link #coalesce()} declined to coalesce because the period
     * had not elapsed. A rapidly climbing value on a rate that should be
     * coalescing means the period or the last-coalesce timestamp is wrong; see
     * {@link #getCoalesceSkips()}.
     */
    private volatile long _coalesceSkips;
    /**
     * Successful coalesces since construction. This is the numerator of the
     * ledger invariant a graph listener checks against the steps it actually
     * wrote: a positive drift is a lost step. Counted whether or not a listener
     * is registered, so a listener that attaches mid-flight must take its own
     * baseline rather than assume the counter starts at zero.
     */
    private volatile long _coalesceCount;
    /**
     * Coalesces that abandoned a backlog of more than one missed step and
     * collapsed it into the current step instead. Each one is a step the graph
     * will never receive, so this is a permanent, countable gap rather than a
     * transient the delivery queue can absorb.
     */
    private volatile long _coalesceBacklogCollapses;
    /**
     * Retained coalesced samples, oldest at {@link #_recentNext} minus
     * {@link #_recentCount}. Guarded by this rate's monitor, like every other
     * mutable field here, so the coalesce path adds entries without ever
     * allocating beyond the slot it takes. Fixed length by design: see
     * {@link #RECENT_SAMPLE_RING}.
     */
    private final CoalescedSample[] _recentSamples = new CoalescedSample[RECENT_SAMPLE_RING];
    /** Index the next retained sample is written to, wrapping. */
    private int _recentNext;
    /** How many of the ring's slots are populated, capped at the ring size. */
    private int _recentCount;
    /**
     * Off-timer delivery for the summary listener, owned by {@link StatManager}
     * and injected into every rate it creates. Null in tests and in standalone use,
     * in which case the listener is called inline from {@link #coalesce()}.
     */
    private volatile RateSampleDelivery _delivery;

    /**
     * Number of {@link #coalesce()} calls that declined to coalesce because the
     * rate period had not yet elapsed.
     *
     * <p>One skip per cycle is normal — the coalesce timer does not divide evenly
     * into the rate period, so most periods are visited once before they are due
     * and are skipped. A count that grows much faster than that indicates the rate
     * is not coalescing at all.
     *
     * @return the running skip count since construction
     * @since 0.9.71+
     */
    public long getCoalesceSkips() {
        return _coalesceSkips;
    }

    /**
     * Number of {@link #coalesce()} calls that actually coalesced.
     *
     * <p>Paired with a storage listener's own written-step count, this is the
     * ledger invariant: the two must agree over the window since that listener
     * attached, and any positive drift is a step that was never recorded.
     *
     * @return the running coalesce count since construction
     * @since 0.9.71+
     */
    public long getCoalesceCount() {
        return _coalesceCount;
    }

    /**
     * How many steps {@link #coalesce()} gave up on because the timer had been
     * starved for more than one whole period.
     *
     * <p>Each one is a whole period of aggregate data that will never reach
     * storage: a rate holds only the current partial period, so the steps it
     * missed cannot be reconstructed afterwards. Zero is the healthy state, and
     * a nonzero value means the coalesce timer was not keeping up with the
     * period — a router-wide condition, since every rate shares that timer.
     *
     * @return the running collapse count since construction
     * @since 0.9.71+
     */
    public long getCoalesceBacklogCollapses() {
        return _coalesceBacklogCollapses;
    }

    /**
     * Retained coalesced samples, oldest first, for a listener to backfill from.
     *
     * <p>Only the last {@link #RECENT_SAMPLE_RING} successful coalesces are kept,
     * in a fixed ring: this runs on the coalesce path under two monitors and
     * must not grow the heap. Never null.
     *
     * @param max the most samples to return; clamped to the ring size, and
     *            non-positive values yield an empty list
     * @return an immutable list of at most {@code max} samples, oldest first
     * @since 0.9.71+
     */
    public synchronized List<CoalescedSample> getRecentSamples(int max) {
        int count = Math.min(Math.max(max, 0), _recentCount);
        if (count == 0)
            return Collections.emptyList();
        List<CoalescedSample> rv = new ArrayList<>(count);
        int start = (_recentNext - _recentCount + RECENT_SAMPLE_RING) % RECENT_SAMPLE_RING;
        for (int i = 0; i < count; i++)
            rv.add(_recentSamples[(start + i) % RECENT_SAMPLE_RING]);
        return Collections.unmodifiableList(rv);
    }

    /**
     * The archive step a sample stamped {@code timestampMs} belongs to.
     *
     * <p>A rate's RRD is created with an archive step of {@code period/1000} (see
     * {@code GraphListener.startListening}), so the step index is simply
     * {@code timestampMs / period} and nothing else needs to know {@code arcStep}.
     * Shared with {@link RateSampleDelivery} so there is one formula for
     * "which step is this sample for".
     *
     * @param timestampMs the sample's step stamp in ms
     * @param period the rate period in ms, must be positive
     * @return the archive step index
     * @since 0.9.71+
     */
    static long archiveStep(long timestampMs, long period) {
        return timestampMs / period;
    }

    /**
     * The timestamp a coalesce due after {@code lastCoalesce} must stamp its
     * sample with: the next point on this rate's own period grid.
     *
     * <p>Stamping {@code now} instead puts a late tick's aggregate into whichever
     * step the current wall clock falls in, not the step whose data it is. With a
     * 50s coalesce timer and a 60s period that is the common case, and the
     * effect is a sample written into a step a later period also writes into.
     * Deriving the stamp from the grid makes consecutive coalesces exactly
     * {@code period} apart, hence exactly one step apart.
     *
     * @param lastCoalesce the previous coalesce timestamp on this rate's grid
     * @param period the rate period in ms
     * @return {@code lastCoalesce + period}
     * @since 0.9.71+
     */
    static long coalesceDueMs(long lastCoalesce, long period) {
        return lastCoalesce + period;
    }

    /**
     * Whether a coalesce arriving at {@code now} is further than one whole
     * period behind its grid, and must therefore collapse its backlog into the
     * current step rather than stamp the step it is nominally due for.
     *
     * <p>Replaying the missed steps is not an option: a rate retains only the
     * current partial period, so the aggregates for steps already gone were never
     * held anywhere. Writing the backlog into the current step is the only
     * honest choice, and {@link #getCoalesceBacklogCollapses()} makes the gap
     * visible instead of leaving it as a hole in the graph.
     *
     * @param now wall-clock ms of this coalesce visit
     * @param lastCoalesce the previous coalesce timestamp on this rate's grid
     * @param period the rate period in ms, must be positive
     * @return true if the backlog is more than one period deep
     * @since 0.9.71+
     */
    static boolean isBacklogCollapse(long now, long lastCoalesce, long period) {
        return now - coalesceDueMs(lastCoalesce, period) >= period;
    }

    /** Add a sample to the retained ring, evicting the oldest when full. */
    private void retain(CoalescedSample sample) {
        _recentSamples[_recentNext] = sample;
        _recentNext = (_recentNext + 1) % RECENT_SAMPLE_RING;
        if (_recentCount < RECENT_SAMPLE_RING)
            _recentCount++;
    }

    /**
     * In current (partial) period, what is the total value acrued through all events?
     *
     * @return the current period's total value
     */
    public synchronized double getCurrentTotalValue() {
        return _currentTotalValue;
    }

    /**
     * In current (partial) period, how many events have occurred?
     *
     * @return the current period's event count
     */
    public synchronized long getCurrentEventCount() {
        return _currentEventCount;
    }

    /** In current (partial) period, how much of the time has been spent doing the events? */
    public long getCurrentTotalEventTime() {
        return _currentTotalEventTime;
    }

    /** In the last full period, what was the total value acrued through all events? */
    public double getLastTotalValue() {
        return _lastTotalValue;
    }

    /** In the last full period, how many events occurred? */
    public long getLastEventCount() {
        return _lastEventCount;
    }

    /** In the last full period, how much of the time was spent doing the events? */
    public long getLastTotalEventTime() {
        return _lastTotalEventTime;
    }

    /** What was the max total value acrued in any period? */
    public double getExtremeTotalValue() {
        return _extremeTotalValue;
    }

    /**
     * When the max(totalValue) was achieved, how many events occurred in that period?
     * Note that this is not necesarily the highest event count; that isn't tracked.
     * @return the extreme event count
     */
    public long getExtremeEventCount() {
        return _extremeEventCount;
    }

    /** When the max(totalValue) was achieved, how much of the time was spent doing the events? */
    public long getExtremeTotalEventTime() {
        return _extremeTotalEventTime;
    }

    /**
     * Since rate creation, what was the total value acrued through all events?
     *
     * @return the lifetime total value
     */
    public synchronized double getLifetimeTotalValue() {
        return _lifetimeTotalValue;
    }

    /**
     * Since rate creation, how many events have occurred?
     *
     * @return the lifetime event count
     */
    public synchronized long getLifetimeEventCount() {
        return _lifetimeEventCount;
    }

    /** Since rate creation, how much of the time was spent doing the events? */
    public long getLifetimeTotalEventTime() {
        return _lifetimeTotalEventTime;
    }

    /**
     * The instant this rate's period grid has reached, which is the step the next
     * coalesce is due for. Advances by exactly one period per coalesce so the
     * archive steps stay aligned; see {@link #getLastCoalesceNow()} for the wall
     * clock of the visit itself.
     *
     * @return the current grid instant in ms
     */
    public long getLastCoalesceDate() {
        return _lastCoalesceDate;
    }

    /** When was this rate created? */
    public long getCreationDate() {
        return _creationDate;
    }

    /**
     * Wall clock of the last successful coalesce, as distinct from the period grid
     * instant that {@link #getLastCoalesceDate()} now reports.
     *
     * @return the wall clock ms of the last successful coalesce
     * @since 0.9.71+
     */
    public long getLastCoalesceNow() {
        return _lastCoalesceNow;
    }

    /** How large should this rate's cycle be? */
    public long getPeriod() {
        return _period;
    }

    /**
     * Return the rate stat this rate belongs to, or null.
     *
     * @return the rate stat this rate belongs to, or null
     */
    public RateStat getRateStat() {
        return _stat;
    }

    /**
     * The rate stat this rate belongs to.
     *
     * @param rs the rate stat this rate belongs to
     */
    public void setRateStat(RateStat rs) {
        _stat = rs;
    }

    /**
     * A rate with period shorter than Router.COALESCE_TIME = 50*1000 has to
     * be manually coalesced before values are fetched from it.
     *
     * @param period number of milliseconds in the period this rate deals with, min 1, max Integer.MAX_VALUE
     * @throws IllegalArgumentException if the period is invalid
     */
    public Rate(long period) throws IllegalArgumentException {
        if (period <= 0 || period > Integer.MAX_VALUE) throw new IllegalArgumentException();

        _creationDate = now();
        _lastCoalesceDate = _creationDate;
        _lastCoalesceNow = _creationDate;
        _period = (int) period;
    }

    /**
     * Create a new rate and load its state from the properties, taking data
     * from the data points underneath the given prefix.  <p>
     * (e.g. prefix = "profile.dbIntroduction.60m", this will load the associated data points such
     * as "profile.dbIntroduction.60m.lifetimeEventCount").  The data can be exported
     * through store(outputStream, "profile.dbIntroduction.60m").
     *
     * @param prefix prefix to the property entries (should NOT end with a period)
     * @param treatAsCurrent if true, we'll treat the loaded data as if no time has
     *                       elapsed since it was written out, but if it is false, we'll
     *                       treat the data with as much freshness (or staleness) as appropriate.
     *
     * @throws IllegalArgumentException if the data was formatted incorrectly
     */
    public Rate(Properties props, String prefix, boolean treatAsCurrent) throws IllegalArgumentException {
        this(1);
        load(props, prefix, treatAsCurrent);
    }

    /**
     * Accrue the data in current period as an instantaneous event.
     * If value is always a constant, you should be using Frequency instead.
     * If you always use this call, eventDuration is always zero,
     * and the various get*Saturation*() and get*EventTime() methods will return zero.
     */
    public synchronized void addData(long value) {
        _currentTotalValue += value;
        _currentEventCount++;
        _lifetimeTotalValue += value;
        _lifetimeEventCount++;
    }

    /**
     * Accrue the data in current period as if the event took the specified amount of time
     * If value is always a constant, you should be using Frequency instead.
     * If eventDuration is nonzero, then the various get*Saturation*() and get*EventTime()
     * methods will also return nonzero.
     *
     * <pre>
     * There are at least 4 possible strategies for eventDuration:
     *
     * 1) eventDuration is always zero.
     *      The various get*Saturation*() and get*EventTime() methods will return zero.
     *
     * 2) Each eventDuration is relatively small, and reflects processing time.
     *      This is probably the original meaning of "saturation", as it allows you
     *      to track how much time is spent gathering the stats.
     *      get*EventTime() will be close to 0.
     *      get*EventSaturation() will return values close to 0,
     *      get*SaturationLimit() will return adjusted values for the totals.
     *
     * 3) The total of the eventDurations are approximately equal to total elapsed time.
     *      get*EventTime() will be close to the period.
     *      get*EventSaturation() will return values close to 1,
     *      get*SaturationLimit() will return adjusted values for the totals.
     *
     * 4) Each eventDuration is not a duration at all, but someother independent data.
     *      get*EventTime() may be used to retrieve the data.
     *      get*EventSaturation() are probably useless.
     *      get*SaturationLimit() are probably useless.
     * </pre>
     *
     * @param value value to accrue in current period
     * @param eventDuration how long it took to accrue this data (set to 0 if it was instantaneous)
     */
    public synchronized void addData(long value, long eventDuration) {
        _currentTotalValue += value;
        _currentEventCount++;
        _currentTotalEventTime = (int)(_currentTotalEventTime + eventDuration);

        _lifetimeTotalValue += value;
        _lifetimeEventCount++;
        _lifetimeTotalEventTime += eventDuration;
    }

    /** 2s is plenty of slack to deal with slow coalescing (across many stats) */
    private static final int SLACK = 2000;

    /**
     * Coalesce the current period's data into the last period.
     * If the measured period is less than the rate period minus slack, this is a no-op.
     *
     * <p>The no-op path is counted ({@link #getCoalesceSkips}) because it is
     * otherwise invisible: no log, no counter, no exception. A rate stuck skipping
     * stops feeding its summary listener, and therefore its RRD database, while
     * every other health check still passes. That is precisely the failure this
     * count exists to make observable. Slack guards early visits only — it decides
     * whether a period is due yet, and nothing about what the sample is stamped
     * with, so it cannot make a late visit land in the wrong step.
     *
     * <p>The sample is stamped with the step it is due for
     * ({@link #coalesceDueMs}) and the grid is advanced to that same instant, so
     * the steps stay exactly one period apart however much the timer drifts. A
     * backlog deeper than one period cannot be replayed and is collapsed into the
     * current step instead, counted by {@link #getCoalesceBacklogCollapses()}.
     *
     * <p>Being on the grid makes a rate coalesce once per period even when the
     * timer visits more often than the period elapses, which is the point: an RRD
     * holds one value per step, so the samples that used to land two steps apart
     * left every intervening step empty.
     *
     * <p>This runs on the shared coalesce timer while {@link StatManager} holds its
     * monitor, so it does no I/O and takes no lock: the counters are updated under
     * this rate's monitor, the ring entry is a fixed array write, and the listener
     * goes to the delivery queue.
     */
    public synchronized void coalesce() {
        long now = now();
        // Time since the previous visit reached the grid instant. Positive and small
        // once the rate is running on the grid, which is what the due test wants.
        long gridError = now - _lastCoalesceDate;
        if (gridError < _period - SLACK) {
            _coalesceSkips++;
            return;
        }

        // ok ok, lets coalesce

        // How much were we off by, over the time the data actually accrued: the
        // rescale is defined against real elapsed time, not against the grid error
        // above, so a rate whose period divides the timer interval does not divide
        // its event time by a rounding error.
        float periodFactor = (now - _lastCoalesceNow) / (float) _period;
        // no, we can't scale totalValue/eventCount by periodFactor,
        // because eventCount is an int so only totalValue scales accurately,
        // resulting in scaling errors in getAverageValue()
        _lastTotalValue = _currentTotalValue;
        _lastEventCount = _currentEventCount;
        _lastTotalEventTime = (int) (_currentTotalEventTime / periodFactor);

        long stamp;
        if (isBacklogCollapse(now, _lastCoalesceDate, _period)) {
            // Realign to now: leaving the grid where it was would make every
            // subsequent visit look equally starved and collapse again.
            _coalesceBacklogCollapses++;
            stamp = now;
        } else {
            stamp = coalesceDueMs(_lastCoalesceDate, _period);
        }
        _lastCoalesceDate = stamp;
        _lastCoalesceNow = now;
        _coalesceCount++;

        if (_lastTotalValue >= _extremeTotalValue) { // get the most recent if identical
            _extremeTotalValue = _lastTotalValue;
            _extremeEventCount = _lastEventCount;
            _extremeTotalEventTime = _lastTotalEventTime;
        }

        _currentTotalValue = 0.0f;
        _currentEventCount = 0;
        _currentTotalEventTime = 0;

        // Retained before delivery: a listener that is healed later backfills from
        // this ring, and the sample is only useful if it survives the delivery.
        retain(new CoalescedSample(stamp, _lastTotalValue, _lastEventCount,
                                   _lastTotalEventTime, _period));

        // Hand the sample to the delivery queue rather than calling the listener
        // here. This runs on the shared two-thread coalesce timer while
        // StatManager holds its monitor, and a listener writes to an RRD database,
        // so calling inline turns a slow disk into a stalled router timer. The
        // counters above are already updated under this rate's monitor, so nothing
        // observable through the rate's getters changes.
        RateSummaryListener rsl = _graphListener;
        if (rsl == null)
            return;
        RateSampleDelivery delivery = _delivery;
        if (delivery != null) {
            delivery.submit(rsl, _lastTotalValue, _lastEventCount,
                            _lastTotalEventTime, _period, stamp);
        } else {
            rsl.add(_lastTotalValue, _lastEventCount, _lastTotalEventTime, _period);
        }
    }

    /**
     * The listener to notify on coalesce.
     *
     * @param listener the listener to notify on coalesce, or null to clear
     */
    public void setSummaryListener(RateSummaryListener listener) {
        _graphListener = listener;
    }

    /**
     * Route coalesced samples through {@code delivery} instead of calling the
     * summary listener inline.
     *
     * <p>Owned by {@link StatManager} so that one delivery thread serves the whole
     * rate table; passing it in per rate would mean a thread per rate. Pass null
     * to restore inline delivery.
     *
     * @param delivery the shared delivery queue, or null for inline delivery
     * @since 0.9.71+
     */
    void setSampleDelivery(RateSampleDelivery delivery) {
        _delivery = delivery;
    }

    /**
     * Clear the summary listener, but only if it is still the one registered.
     *
     * <p>A plain {@link #setSummaryListener} clear is not safe here. Listeners
     * outlive the registration that created them — a console torn down after a
     * replacement has already registered its own listener would clear the
     * <em>new</em> one, because it cannot tell the two apart. The survivor keeps
     * its database open, so every health check still reports it as attached and
     * nothing re-arms it: the rate goes permanently unrecorded with nothing logged.
     * Tearing down something you no longer own must be a no-op, which is what the
     * identity test below provides.
     *
     * <p>Identity, not equality: two distinct listeners for one rate compare equal
     * (they implement {@code equals} by rate), but only one of them is ever the
     * registered instance, so {@code equals} would let the wrong one clear.
     *
     * @param expected the listener believed to be registered; may be null
     * @return true if the listener was cleared by this call, false if the
     *         registration was already absent or held by someone else
     * @since 0.9.71+
     */
    public synchronized boolean clearSummaryListener(RateSummaryListener expected) {
        if (_graphListener == null || _graphListener != expected)
            return false;
        _graphListener = null;
        return true;
    }

    /**
     * Return the current summary listener, or null.
     *
     * @return the current summary listener, or null
     */
    public RateSummaryListener getSummaryListener() {
        return _graphListener;
    }

    /**
     * The last N data points from the underlying storage (RRD).
     * Delegates to the summary listener if it supports historical data.
     * Returns NaN-padded array if the listener is null or data is unavailable.
     *
     * @param count number of data points
     * @return array of length count
     * @since 0.9.70+
     */
    public double[] getLastValues(int count) {
        if (_graphListener != null)
            return _graphListener.getLastValues(count);
        double[] result = new double[count];
        Arrays.fill(result, Double.NaN);
        return result;
    }

    /**
     * What was the average value across the events in the last period?
     * @return the average value
     */
    public synchronized double getAverageValue() {
        int lec = _lastEventCount; // avoid race NPE
        if ((_lastTotalValue != 0) && (lec > 0)) return _lastTotalValue / lec;

        return 0.0D;
    }

    /**
     * During the extreme period (i.e. the period with the highest total value),
     * what was the average value?
     * @return the extreme average value
     */
    public synchronized double getExtremeAverageValue() {
        if ((_extremeTotalValue != 0) && (_extremeEventCount > 0)) return _extremeTotalValue / _extremeEventCount;

        return 0.0D;
    }

    /**
     * What was the average value across the events since the stat was created?
     * @return the lifetime average value
     */
    public synchronized double getLifetimeAverageValue() {
        if ((_lifetimeTotalValue != 0) && (_lifetimeEventCount > 0)) return _lifetimeTotalValue / _lifetimeEventCount;

        return 0.0D;
    }

    /**
     * The average value, or the lifetime average if no recent data.
     *
     * @return the average or lifetime average depending on last event count
     * @since 0.9.4
     */
    public synchronized double getAvgOrLifetimeAvg() {
        if (getLastEventCount() > 0) return getAverageValue();
        return getLifetimeAverageValue();
    }

    /**
     * During the last period, how much of the time was spent actually processing events in proportion
     * to how many events could have occurred if there were no intervals?
     *
     * @return ratio, or 0 if event times aren't used
     */
    public synchronized double getLastEventSaturation() {
        if ((_lastEventCount > 0) && (_lastTotalEventTime > 0)) {
            return ((double) _lastTotalEventTime) / (double) _period;
        }

        return 0.0D;
    }

    /**
     * During the extreme period (i.e. the period with the highest total value),
     * how much of the time was spent actually processing events
     * in proportion to how many events could have occurred if there were no intervals?
     *
     * @return ratio, or 0 if the statistic doesn't use event times
     */
    public synchronized double getExtremeEventSaturation() {
        if ((_extremeEventCount > 0) && (_extremeTotalEventTime > 0)) {
            double eventTime = (double) _extremeTotalEventTime / (double) _extremeEventCount;
            double maxEvents = _period / eventTime;
            return _extremeEventCount / maxEvents;
        }
        return 0.0D;
    }

    /**
     * During the lifetime of this stat, how much of the time was spent actually processing events in proportion
     * to how many events could have occurred if there were no intervals?
     *
     * @return ratio, or 0 if event times aren't used
     */
    public synchronized double getLifetimeEventSaturation() {
        if ((_lastEventCount > 0) && (_lifetimeTotalEventTime > 0)) {
            double eventTime = (double) _lifetimeTotalEventTime / (double) _lifetimeEventCount;
            double maxEvents = _period / eventTime;
            double numPeriods = getLifetimePeriods();
            double avgEventsPerPeriod = _lifetimeEventCount / numPeriods;
            return avgEventsPerPeriod / maxEvents;
        }
        return 0.0D;
    }

    /** How many periods have we already completed? */
    public synchronized long getLifetimePeriods() {
        long lifetime = now() - _creationDate;
        double periods = lifetime / (double) _period;
        return (long) Math.floor(periods);
    }

    /**
     * Using the last period's rate, what is the total value that could have been sent
     * if events were constant?
     *
     * @return max total value, or 0 if event times aren't used
     */
    public synchronized double getLastSaturationLimit() {
        if ((_lastTotalValue != 0) && (_lastEventCount > 0) && (_lastTotalEventTime > 0)) {
            double saturation = getLastEventSaturation();
            if (saturation != 0.0D) return _lastTotalValue / saturation;

            return 0.0D;
        }
        return 0.0D;
    }

    /**
     * During the extreme period (i.e. the period with the highest total value),
     * what is the total value that could have been
     * sent if events were constant?
     *
     * @return event total at saturation, or 0 if no event times are measured
     */
    public synchronized double getExtremeSaturationLimit() {
        if ((_extremeTotalValue != 0) && (_extremeEventCount > 0) && (_extremeTotalEventTime > 0)) {
            double saturation = getExtremeEventSaturation();
            if (saturation != 0.0d) return _extremeTotalValue / saturation;

            return 0.0D;
        }

        return 0.0D;
    }

    /**
     * What was the total value, compared to the total value in
     * the extreme period (i.e. the period with the highest total value),
     * Warning- returns ratio, not percentage (i.e. it is not multiplied by 100 here)
     *
     * @return ratio of last total to extreme total, or 0 if no data
     */
    public synchronized double getPercentageOfExtremeValue() {
        if ((_lastTotalValue != 0) && (_extremeTotalValue != 0)) return _lastTotalValue / _extremeTotalValue;

        return 0.0D;
    }

    /**
     * How large was the last period's value as compared to the lifetime average value?
     * Warning- returns ratio, not percentage (i.e. it is not multiplied by 100 here)
     *
     * @return ratio of last period value to lifetime average period value, or 0 if no data
     */
    public synchronized double getPercentageOfLifetimeValue() {
        if ((_lastTotalValue != 0) && (_lifetimeTotalValue != 0)) {
            double lifetimePeriodValue = _period * (_lifetimeTotalValue / (now() - _creationDate));
            return _lastTotalValue / lifetimePeriodValue;
        }

        return 0.0D;
    }

    /**
     * Computes the averages for this rate.
     *
     * @return a thread-local temp object containing computed averages.
     * @since 0.9.4
     */
    public RateAverages computeAverages() {
        return computeAverages(RateAverages.getTemp(), false);
    }

    /**
     * Computes the averages and stores them in the provided object.
     *
     * @param out where to store the computed averages.
     * @param useLifetime whether the lifetime average should be used if
     * there are no events.
     *
     * @return the same RateAverages object for chaining
     * @since 0.9.4
     */
    public synchronized RateAverages computeAverages(RateAverages out, boolean useLifetime) {
        out.reset();

        final long total = (long) _currentEventCount + _lastEventCount;
        out.setTotalEventCount(total);

        if (total <= 0) {
            final double avg = useLifetime ? getLifetimeAverageValue() : getAverageValue();
            out.setAverage(avg);
        } else {

            if (_currentEventCount > 0) out.setCurrent(getCurrentTotalValue() / _currentEventCount);
            if (_lastEventCount > 0) out.setLast(getLastTotalValue() / _lastEventCount);

            out.setTotalValues(getCurrentTotalValue() + getLastTotalValue());
            out.setAverage(out.getTotalValues() / total);
        }
        return out;
    }

    /**
     *  Stores the rate data to a string builder.
     *  Includes comment lines
     */
    public synchronized void store(String prefix, StringBuilder buf) {
        store(prefix, buf, true);
    }

    /**
     * Stores the rate data to a string builder.
     *
     * @param addComments add comment lines to the output
     * @since 0.9.41
     */
    public synchronized void store(String prefix, StringBuilder buf, boolean addComments) {
        PersistenceHelper.add(buf, addComments, prefix, ".period", "Period for this rate:", _period);
        PersistenceHelper.addDate(buf, addComments, prefix, ".creationDate", "Rate creation time:", _creationDate);
        PersistenceHelper.addDate(buf, addComments, prefix, ".lastCoalesceDate", "Last time rate was coalesced:", _lastCoalesceDate);
        PersistenceHelper.addTime(buf, addComments, prefix, ".currentTotalEventTime", "Total time used by events in current period (uncoalesced):", _currentTotalEventTime);
        PersistenceHelper.addTime(buf, addComments, prefix, ".lastTotalEventTime", "Total time used by events in most recent period (coalesced):", _lastTotalEventTime);
        PersistenceHelper.addTime(buf, addComments, prefix, ".extremeTotalEventTime", "Total time used by events in most extreme period:", _extremeTotalEventTime);
        PersistenceHelper.addTime(buf, addComments, prefix, ".lifetimeTotalEventTime", "Total time used by events since this stat was created:", _lifetimeTotalEventTime);
        if (addComments) {
            PersistenceHelper.add(buf, true, prefix, ".currentEventCount", "Total events in current period (uncoalesced): " + _currentEventCount, _currentEventCount);
            PersistenceHelper.add(buf, true, prefix, ".lastEventCount", "Total events in most recent period (coalesced): " + _lastEventCount, _lastEventCount);
            PersistenceHelper.add(buf, true, prefix, ".extremeEventCount", "Total events in most extreme period: " + _extremeEventCount, _extremeEventCount);
            PersistenceHelper.add(buf, true, prefix, ".lifetimeEventCount", "Total events since this stat was created: " + _lifetimeEventCount, _lifetimeEventCount);
            PersistenceHelper.add(buf, true, prefix, ".currentTotalValue", "Total value of data points in current period (uncoalesced): " + _currentTotalValue, _currentTotalValue);
            PersistenceHelper.add(buf, true, prefix, ".lastTotalValue", "Total value of data points in most recent period (coalesced): " + _lastTotalValue, _lastTotalValue);
            PersistenceHelper.add(buf, true, prefix, ".extremeTotalValue", "Total value of data points in most extreme period: " + _extremeTotalValue, _extremeTotalValue);
            PersistenceHelper.add(buf, true, prefix, ".lifetimeTotalValue", "Total value of data points since this stat was created: " + _lifetimeTotalValue, _lifetimeTotalValue);
        } else {
            PersistenceHelper.add(buf, false, prefix, ".currentEventCount", null, _currentEventCount);
            PersistenceHelper.add(buf, false, prefix, ".lastEventCount", null, _lastEventCount);
            PersistenceHelper.add(buf, false, prefix, ".extremeEventCount", null, _extremeEventCount);
            PersistenceHelper.add(buf, false, prefix, ".lifetimeEventCount", null, _lifetimeEventCount);
            PersistenceHelper.add(buf, false, prefix, ".currentTotalValue", null, _currentTotalValue);
            PersistenceHelper.add(buf, false, prefix, ".lastTotalValue", null, _lastTotalValue);
            PersistenceHelper.add(buf, false, prefix, ".extremeTotalValue", null, _extremeTotalValue);
            PersistenceHelper.add(buf, false, prefix, ".lifetimeTotalValue", null, _lifetimeTotalValue);
        }
    }

    /**
     * Load this rate from the properties, taking data from the data points underneath the given prefix.
     *
     * @param prefix prefix to the property entries (should NOT end with a period)
     * @param treatAsCurrent if true, we'll treat the loaded data as if no time has elapsed since it was
     * written out, but if it is false, we'll treat the data with as much freshness
     * (or staleness) as appropriate.
     *
     * @throws IllegalArgumentException if the data was formatted incorrectly
     */
    public final synchronized void load(Properties props, String prefix, boolean treatAsCurrent) throws IllegalArgumentException {
        int origPeriod = _period;
        _period = PersistenceHelper.getInt(props, prefix, ".period");
        _creationDate = PersistenceHelper.getLong(props, prefix, ".creationDate");
        _currentEventCount = PersistenceHelper.getInt(props, prefix, ".currentEventCount");
        _currentTotalEventTime = (int) PersistenceHelper.getLong(props, prefix, ".currentTotalEventTime");
        _currentTotalValue = (float) PersistenceHelper.getDouble(props, prefix, ".currentTotalValue");
        _extremeEventCount = PersistenceHelper.getInt(props, prefix, ".extremeEventCount");
        _extremeTotalEventTime = (int) PersistenceHelper.getLong(props, prefix, ".extremeTotalEventTime");
        _extremeTotalValue = (float) PersistenceHelper.getDouble(props, prefix, ".extremeTotalValue");
        _lastCoalesceDate = PersistenceHelper.getLong(props, prefix, ".lastCoalesceDate");
        _lastEventCount = PersistenceHelper.getInt(props, prefix, ".lastEventCount");
        _lastTotalEventTime = (int) PersistenceHelper.getLong(props, prefix, ".lastTotalEventTime");
        _lastTotalValue = (float) PersistenceHelper.getDouble(props, prefix, ".lastTotalValue");
        _lifetimeEventCount = PersistenceHelper.getLong(props, prefix, ".lifetimeEventCount");
        _lifetimeTotalEventTime = PersistenceHelper.getLong(props, prefix, ".lifetimeTotalEventTime");
        _lifetimeTotalValue = (float) PersistenceHelper.getDouble(props, prefix, ".lifetimeTotalValue");

        if (treatAsCurrent) {
            _lastCoalesceDate = now();
        }
        // The grid anchor is restored, not the real visit instant, so seed the real
        // instant from it. Otherwise the coalesce() below would rescale the loaded
        // event time against the time this rate happened to be reloaded.
        _lastCoalesceNow = _lastCoalesceDate;

        if (_period <= 0) {
            // .period was not stored prior to 0.9.71; preserve the constructor-set period
            // rather than using a hardcoded fallback which would corrupt multi-period RateStats
            if (origPeriod > 0) {
                _period = origPeriod;
            } else {
                _period = prefix.contains("tunnelCreateResponse") ? 60 * 60 * 1000 : 60 * 1000;
            }
            Log _log = I2PAppContext.getGlobalContext().logManager().getLog(Rate.class);
            if (_log.shouldInfo()) {
                _log.warn("Period for " + prefix + " missing or invalid, using value: " + _period);
            }
        }
        coalesce();
    }

    /**
     * This is used in GraphGenerator and GraphListener.
     * We base it on the stat we are tracking, not the stored data.
     */
    @Override
    public synchronized boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof Rate)) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        Rate r = (Rate) obj;
        if (_period != r.getPeriod()) {
            return false;
        }
        if (_stat == null && r._stat == null) {
            return true;
        }
        if (_stat != null && r._stat != null) {
            return _stat.nameGroupDescEquals(r._stat);
        }
        return false;
    }

    /**
     * It doesn't appear that Rates are ever stored in a Set or Map
     * (RateStat stores in an array) so let's make this easy.
     * @return whether h code is present
     */
    @Override
    public synchronized int hashCode() {
        return DataHelper.hashCode(_stat) ^ _period;
    }

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder(2048);
        if (_stat != null) buf.append("\n\t stat: ").append(_stat.getName());
        buf.append("\n\t period: ").append(_period);
        buf.append("\n\t total value: ").append(getLastTotalValue());
        buf.append("\n\t highest total value: ").append(getExtremeTotalValue());
        buf.append("\n\t lifetime total value: ").append(getLifetimeTotalValue());
        buf.append("\n\t # periods: ").append(getLifetimePeriods());
        buf.append("\n\t average value: ").append(getAverageValue());
        buf.append("\n\t highest average value: ").append(getExtremeAverageValue());
        buf.append("\n\t lifetime average value: ").append(getLifetimeAverageValue());
        buf.append("\n\t % of lifetime rate: ").append(100.0d * getPercentageOfLifetimeValue());
        buf.append("\n\t % of highest rate: ").append(100.0d * getPercentageOfExtremeValue());
        buf.append("\n\t # events: ").append(getLastEventCount());
        buf.append("\n\t lifetime events: ").append(getLifetimeEventCount());
        if (getLifetimeTotalEventTime() > 0) {
            // we have some actual event durations
            buf.append("\n\t % of time spent processing events: ").append(100.0d * getLastEventSaturation());
            buf.append("\n\t total value if we were always processing events: ").append(getLastSaturationLimit());
            buf.append("\n\t max % of time spent processing events: ").append(100.0d * getExtremeEventSaturation());
            buf.append("\n\t max total value if we were always processing events: ").append(getExtremeSaturationLimit());
        }
        return buf.toString();
    }

    private static final long now() {
        // "event time" is in the stat log (and uses Clock).
        // we just want sequential and stable time here, so use the OS time, since it doesn't skew periodically
        return System.currentTimeMillis();
    }
}
