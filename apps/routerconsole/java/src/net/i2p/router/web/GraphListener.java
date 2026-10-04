package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.RateSummaryListener;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.Archive;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.RrdException;
import org.rrd4j.core.RrdMemoryBackendFactory;
import org.rrd4j.core.Sample;
import org.rrd4j.core.FetchRequest;
import org.rrd4j.core.FetchData;
import java.util.Arrays;
import java.util.List;

/**
 *  Creates and updates the in-memory or on-disk RRD database,
 *  and provides methods to generate graphs of the data
 *
 *  @since 0.6.1.13
 */
public class GraphListener implements RateSummaryListener {
    /** @since 0.9.33 */
    public static final String PROP_PERSISTENT = "routerconsole.graphPersistent";
    /** note that .jrb files are NOT compatible with .rrd files */
    static final String RRD_DIR = "rrd";
    /** Prefix for RRD file names. */
    static final String RRD_PREFIX = "rrd-";
    /** Suffix for RRD file names. */
    static final String RRD_SUFFIX = ".jrb";
    /** Consolidation function used for RRD archives. */
    static final ConsolFun CF = ConsolFun.AVERAGE;
    /** Data source type used for RRD datasources. */
    static final DsType DS = DsType.GAUGE;
    /** X-factor (allowed fraction of unknown data) for RRD consolidation. */
    private static final double XFF = 0.9d;
    /** Number of primary steps per consolidation. */
    private static final int STEPS = 1;

    private final I2PAppContext _context;
    private final Log _log;
    private final Rate _rate;
    private final boolean _isPersistent;
    /** RRD datasource name for the primary stat value. */
    private String _name;
    /** RRD datasource name for the event count. */
    private String _eventName;
    /** The RRD database instance. */
    private RrdDb _db;
    /** Current sample being populated. */
    private Sample _sample;
    /** Renderer for generating graph images. */
    private GraphRenderer _renderer;
    /** Number of rows in the RRD archive. */
    private int _rows;
    /**
     *  Consecutive write failures at which the report stops being routine.
     *
     *  <p>No longer a teardown threshold. A momentary I/O error used to detach the
     *  listener here, which converted a transient fault into a permanent hole in the
     *  graph and then left it unfilled for up to a full sync interval. Retirement is
     *  now only ever the heal ladder's explicit REBUILD.
     */
    private static final int MAX_CONSECUTIVE_ERRORS = 10;
    /** Current consecutive error count. */
    private volatile int _consecutiveErrors;
    /** Wall-clock ms of the last successful RRD write, or 0 if none has succeeded. */
    private volatile long _lastUpdateSuccess;
    /** Total successful RRD writes. */
    private volatile long _updateCount;
    /** Wall-clock ms of the newest step stored, live or backfilled; 0 if none. */
    private volatile long _lastStoredTimeMs;
    /** Steps written by {@link #backfill(List)} since this listener attached. */
    private volatile long _backfillCount;
    /**
     *  Coalesce count of the Rate when this listener attached.
     *
     *  <p>{@link Rate#getCoalesceCount()} counts from the Rate's construction, and a
     *  stat registered at router startup has been coalescing long before the console
     *  attaches to it. Comparing that absolute count against this listener's own write
     *  count would report the entire pre-attach history as drift, on every listener,
     *  forever. The baseline is what makes the ledger an actual invariant rather than
     *  a number that only ever grows.
     */
    private volatile long _coalesceBaseline;
    /** Write count of this listener when it attached; 0 for a fresh instance. */
    private volatile long _updateBaseline;
    /**
     *  Steps proven lost because the Rate no longer retains them.
     *
     *  <p>The retained-sample ring holds a fixed handful of coalesces, so a listener
     *  that was unreachable for longer than that can never recover the intervening
     *  steps. Those are reported once rather than retried forever, which needs a
     *  counter as well as a flag: "drift is positive" says a step was lost, but only
     *  a count says how many are gone for good.
     *
     *  @see #chargePermanentLoss(long)
     */
    private volatile long _unrecoverableSteps;
    /**
     *  Coalesce delta at which {@link #_unrecoverableSteps} was last charged.
     *
     *  <p>Keyed on the coalesce delta rather than on the drift because the coalesce
     *  delta only ever grows, so each lost step is charged exactly once even when the
     *  drift dips back to zero after a heal and climbs again.
     */
    private volatile long _unrecoverableCoalesceDelta;

    /** When the most recent RRD write succeeded, or 0 if none has. */
    long getLastUpdateSuccess() { return _lastUpdateSuccess; }

    /** Number of successful RRD writes since startup. */
    long getUpdateCount() { return _updateCount; }

    /**
     *  Steps written by {@link #backfill(List)} since this listener attached.
     *
     *  @return backfilled step count
     *  @since 0.9.71+
     */
    long getBackfillCount() { return _backfillCount; }

    /**
     *  Newest archive step stored, live or backfilled.
     *
     *  @return wall-clock ms of the newest stored step, whole seconds, or 0 if none
     *  @since 0.9.71+
     */
    long getLastStoredTimeMs() { return _lastStoredTimeMs; }

    /**
     *  Consecutive failed writes, reset by any successful live write.
     *
     *  @return current streak length
     *  @since 0.9.71+
     */
    int getConsecutiveErrors() { return _consecutiveErrors; }

    /**
     *  Steps proven lost beyond the Rate's retained-sample ring.
     *
     *  @return cumulative permanently lost step count
     *  @since 0.9.71+
     */
    long getUnrecoverableSteps() { return _unrecoverableSteps; }

    /**
     *  Coalesces on this Rate since this listener attached.
     *
     *  @see #_coalesceBaseline
     *  @return coalesce delta since attach, never negative
     *  @since 0.9.71+
     */
    long getCoalesceDelta() {
        long delta = _rate.getCoalesceCount() - _coalesceBaseline;
        return delta > 0 ? delta : 0;
    }

    /**
     *  Steps this listener has stored since it attached.
     *
     *  @return stored delta since attach, never negative
     *  @since 0.9.71+
     */
    long getStoredDelta() {
        long delta = _updateCount - _updateBaseline;
        return delta > 0 ? delta : 0;
    }

    /**
     *  The ledger invariant: coalesces the Rate produced, minus steps this listener stored.
     *
     *  <p>Positive means a coalesce produced a sample this listener never put into the
     *  RRD, which is a provably lost step rather than an inference from a stalled
     *  write. Zero is the only healthy value; negative is not reachable, because a
     *  step is only stored after its coalesce has already been counted.
     *
     *  @return coalesces minus stored steps
     *  @since 0.9.71+
     */
    long getCoalesceDrift() { return getCoalesceDelta() - getStoredDelta(); }

    /**
     *  Record that the drift on this listener cannot be backfilled.
     *
     *  <p>Charged against the coalesce delta rather than the drift, which only grows,
     *  so every lost step is charged exactly once. A later heal that drops the drift
     *  back to zero and a further outage both still get reported.
     *
     *  @param coalesceDelta coalesce delta from {@link #getCoalesceDelta()}
     *  @return steps newly charged, 0 when they were already charged
     *  @since 0.9.71+
     */
    synchronized long chargePermanentLoss(long coalesceDelta) {
        long added = coalesceDelta - _unrecoverableCoalesceDelta;
        if (added <= 0) {
            return 0;
        }
        _unrecoverableCoalesceDelta = coalesceDelta;
        _unrecoverableSteps += added;
        return added;
    }

    /** Number of periods in one day (1440 = 60 minutes * 24 hours at 1-minute resolution). */
    static final int PERIODS = 60 * 24;  // 1440
    /**
     *  Offset (seconds) subtracted from "now" when computing the fetch window end,
     *  so a clock skewed slightly ahead of system time does not yield NaNs.
     *  Shared with GraphRenderer, which applies the same window for plotting.
     *
     *  @since 0.9.70+
     */
    static final int GRAPH_END_OFFSET_SECONDS = 75;
    /** Minimum number of rows to keep in the archive. */
    private static final int MIN_ROWS = PERIODS;
    /** @since 0.9.33 */
    public static final int MAX_ROWS = 91 * MIN_ROWS;
    /** Three months in milliseconds (used to compute max rows for persistent RRDs). */
    private static final long THREE_MONTHS = 91L * 24 * 60 * 60 * 1000;

    /**
     * Create a listener for the given rate stat.
     *
     * @param r the rate to track
     */
    public GraphListener(Rate r) {
        _context = I2PAppContext.getGlobalContext();
        _rate = r;
        _log = _context.logManager().getLog(GraphListener.class);
        _isPersistent = _context.getBooleanPropertyDefaultTrue(PROP_PERSISTENT);
    }

    /**
     * Add a new data point to the RRD database.
     *
     * @param totalValue the total value for the period
     * @param eventCount the number of events in the period
     * @param totalEventTime the total event time for the period
     * @param period the period duration in milliseconds
     */
    public void add(double totalValue, long eventCount, double totalEventTime, long period) {
        long now = now();
        long when = now / 1000;
        if (_db != null) {
            // add one value to the db (the average value for the period)
            try {
                _sample.setTime(when);
                _sample.setValue(_name, sampleValue(totalValue, eventCount));
                _sample.setValue(_eventName, eventCount);
                _sample.update();
                _consecutiveErrors = 0;
                _lastUpdateSuccess = System.currentTimeMillis();
                noteWrite(when);
            } catch (IllegalArgumentException iae) {
                String msg = iae.getMessage();
                if (msg != null && msg.startsWith("Bad sample time:")) {
                    if (_log.shouldWarn()) {
                        _log.warn("RRD time skew", iae);
                    }
                } else {
                    noteWriteFailure(iae);
                }
            } catch (RrdException re) {
                // this can happen after the time slews backwards, so don't make it an error
                // org.jrobin.core.RrdException: Bad sample timestamp 1264343107. Last update time was 1264343172, at least one second step is required
                if (_log.shouldWarn()) {
                    _log.warn("Error adding", re);
                }
            } catch (RuntimeException rte) {
                // Broad guard: any unchecked throwable from the RRD library must be counted the
                // same way as IOException, or it escapes the periodic rate tick, kills that rate
                // silently, and nothing ever notices that the listener stopped recording.
                noteWriteFailure(rte);
            } catch (IOException ioe) {
                noteWriteFailure(ioe);
            }
        }
    }

    /**
     *  Value to store for a period, given the totals the rate hands its listeners.
     *
     *  <p>Pure so the backfill path and the live path cannot drift apart: a backfilled
     *  step recorded with a different rule than the live steps around it would put a
     *  spike in the middle of an otherwise consistent trace.
     *
     *  @param totalValue the total value for the period
     *  @param eventCount the number of events in the period
     *  @return the primary datasource value for the step
     *  @since 0.9.71+
     */
    static double sampleValue(double totalValue, long eventCount) {
        if (eventCount <= 0) {
            return 0d;
        }
        // Event-counter stats (e.g. inNetPool.dropped, tunnel.rejectHopThrottle)
        // record a value of 1 per event, so totalValue / eventCount is always
        // 1.0 — a binary flat line when plotted. In that case store the event
        // count itself as the primary series, so the graph shows a true
        // count-per-period instead of a useless 0/1 trace.
        return totalValue == (double) eventCount ? eventCount : totalValue / eventCount;
    }

    /**
     *  Record a step that is now in the RRD, advancing the write ledger.
     *
     *  <p>Synchronized because the count is the numerator of the drift invariant the
     *  watchdog reads: a lost increment here is reported as a lost step, so the ledger
     *  has to be exact rather than a volatile increment that the delivery thread and
     *  the watchdog's backfill can race. The monitor covers the field updates only,
     *  never the RRD write that precedes them.
     *
     *  @param whenSeconds archive step written, in seconds
     */
    private synchronized void noteWrite(long whenSeconds) {
        long stamp = whenSeconds * 1000L;
        if (stamp > _lastStoredTimeMs) {
            _lastStoredTimeMs = stamp;
        }
        _updateCount++;
    }

    /**
     *  Count a failed write and report it, without giving up the listener.
     *
     *  <p>Below the threshold this is routine and rate limited; from the threshold on it
     *  is an ERROR naming the rate, because a streak this long is not going to clear
     *  itself and the operator has to know which graph is affected. Reported every
     *  {@link #MAX_CONSECUTIVE_ERRORS} failures rather than on every one, so a rate with
     *  a persistently failing RRD cannot turn the log into the stall it used to avoid.
     *
     *  <p>Deliberately does not call {@link #stopListening()}. Retiring on a transient
     *  I/O error left a hole in the graph that nothing refilled for up to a full sync
     *  interval, and the detach was indistinguishable from a real fault in the logs.
     *  Recording now stops on its own - the ledger drift grows - and the heal ladder
     *  reopens or rebuilds the handle when it can.
     *
     *  @param t the failure
     */
    private void noteWriteFailure(Throwable t) {
        int errors = ++_consecutiveErrors;
        if (errors < MAX_CONSECUTIVE_ERRORS) {
            if (_log.shouldWarn()) {
                _log.warn("RRD write error (" + errors + "/" + MAX_CONSECUTIVE_ERRORS + ") for "
                          + rateName(), t);
            }
            return;
        }
        if (errors % MAX_CONSECUTIVE_ERRORS == 0) {
            _log.error("RRD write failed " + errors + " times for " + rateName()
                       + ", keeping the listener open for the heal ladder (history preserved)", t);
        }
    }

    /**
     *  Name the rate this listener records, for log lines that must say which graph.
     *
     *  @return the rate's stat name and period
     */
    private String rateName() {
        RateStat rs = _rate.getRateStat();
        return (rs != null ? rs.getName() : "?") + '.' + _rate.getPeriod();
    }

    /**
     *  Replay retained samples whose steps this listener never stored, oldest first.
     *
     *  <p>Recovery for a listener that missed a few steps while nothing was obviously
     *  wrong: the values are still in the Rate's retained-sample ring, and an archive
     *  step is empty forever once nothing writes it, so writing them late beats losing
     *  them.
     *
     *  <p>{@code RrdDb.store} rejects any timestamp not strictly newer than the last
     *  one stored, so a sample that lands on or before {@link #getLastStoredTimeMs()} is
     *  skipped rather than attempted: the write path has already covered the recent
     *  steps and the ring can overlap them.
     *
     *  @param samples retained samples, oldest first; may be null or empty
     *  @return number of steps written
     *  @since 0.9.71+
     */
    public int backfill(List<Rate.CoalescedSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        RrdDb db = _db;
        if (db == null || db.isClosed()) {
            return 0;
        }
        int written = 0;
        for (Rate.CoalescedSample sample : samples) {
            if (sample == null) {
                continue;
            }
            long when = sample.timestampMs / 1000;
            if (toArchiveSecondMs(sample.timestampMs) <= _lastStoredTimeMs) {
                // Already stored, or on the same step as the newest stored one: the
                // archive holds one value per step and the newer value is the right one.
                continue;
            }
            try {
                // createSample(when), not the no-arg form: the no-arg sample stamps
                // itself with the current time, which is not the step being recovered.
                Sample s = db.createSample(when);
                s.setValue(_name, sampleValue(sample.totalValue, sample.eventCount));
                s.setValue(_eventName, sample.eventCount);
                s.update();
                noteWrite(when);
                written++;
            } catch (IllegalArgumentException iae) {
                // Non-monotonic timestamp, including a live write that got there first
                // between the check above and this store. Not a fault and not worth a
                // log line: the step is either already held or now held by that write.
                if (_log.shouldDebug()) {
                    _log.debug("Backfill skipped a non-monotonic step for " + rateName(), iae);
                }
            } catch (RuntimeException re) {
                if (_log.shouldWarn()) {
                    _log.warn("Backfill write error for " + rateName(), re);
                }
            } catch (IOException ioe) {
                if (_log.shouldWarn()) {
                    _log.warn("Backfill write error for " + rateName(), ioe);
                }
            }
        }
        if (written > 0) {
            _backfillCount += written;
            if (_log.shouldInfo()) {
                _log.info("Backfilled " + written + " step(s) into " + rateName());
            }
        }
        return written;
    }

    /**
     *  Truncate a wall-clock stamp to the whole second an RRD actually stores.
     *
     *  <p>Pure because the whole skip rule is this truncation applied against
     *  {@link #getLastStoredTimeMs()}: comparing raw milliseconds would let a sample
     *  999ms into a step the listener already stored look newer than it is, and the
     *  write would then be rejected by the archive as non-monotonic.
     *
     *  @param timestampMs wall-clock ms
     *  @return the same instant truncated down to a whole second, in ms
     *  @since 0.9.71+
     */
    static long toArchiveSecondMs(long timestampMs) { return timestampMs / 1000 * 1000; }

    /**
     *  Can a listener still recover its drift, or are the missing steps gone?
     *
     *  <p>The Rate retains a fixed, small ring of coalesces. Once every retained sample
     *  is already stored, the steps the listener missed are older than anything the
     *  Rate remembers, and no amount of retrying will produce them. Telling those two
     *  cases apart is what keeps the watchdog from re-attempting a hopeless backfill
     *  every ten seconds and from calling a permanent gap a transient one.
     *
     *  <p>Pure, so the boundary is unit tested without a Rate, an RRD or a clock.
     *
     *  @param lastStoredTimeMs newest stored step from {@link #getLastStoredTimeMs()}
     *  @param retained the Rate's retained samples, oldest first; may be null
     *  @return true if at least one retained sample is newer than the last stored step
     *  @since 0.9.71+
     */
    static boolean recoverable(long lastStoredTimeMs, List<Rate.CoalescedSample> retained) {
        if (retained == null) {
            return false;
        }
        for (Rate.CoalescedSample sample : retained) {
            if (sample != null && toArchiveSecondMs(sample.timestampMs) > lastStoredTimeMs) {
                return true;
            }
        }
        return false;
    }

    /**
     *  Whether the open RRD handle can still take a write.
     *
     *  <p>Distinct from {@link #isDetached()}: a detached listener has no handle at
     *  all, whereas this can be false for a listener that still holds a handle which
     *  something else has already closed. That is the cheapest fault to heal, since
     *  the listener, its registration and its RRD file all survive and only the handle
     *  has to be re-obtained.
     *
     *  @return true if the handle is open and can accept a write
     *  @since 0.9.71+
     */
    boolean isWritable() {
        RrdDb db = _db;
        return db != null && !db.isClosed();
    }

    /**
     *  Whether this listener has stopped recording.
     *
     *  A detached listener keeps its entry in GraphGenerator's rate-to-listener
     *  map, so the generator uses this to notice that it needs re-creating.
     *
     *  @return true if the RRD database is closed and no longer receiving samples
     *  @since 0.9.71+
     */
    boolean isDetached() { return _db == null; }

    /**
     *  Whether this listener is still the one its Rate notifies on coalesce.
     *
     *  <p>Distinct from {@link #isDetached()}: a Rate holds exactly one summary
     *  listener, so registering a replacement silently orphans this instance while its
     *  RRD stays open. That instance is attached - so it renders fine and the console
     *  shows a plausible graph - but it can never be called again, and nothing about
     *  that state is visible without asking the Rate.
     *
     *  @return true if {@link Rate#getSummaryListener()} is this instance
     *  @since 0.9.71+
     */
    boolean isRegistered() { return ownsRegistration(_rate.getSummaryListener(), this); }

    /**
     *  Does {@code self} still own a Rate's summary-listener registration?
     *
     *  <p>Reference identity, deliberately not {@link #equals(Object)}: two listeners
     *  for the same Rate are {@code equals} but only one of them is ever called, so
     *  only reference equality answers "is this instance the live one".
     *
     *  @param registered current registration, as returned by {@link Rate#getSummaryListener()}
     *  @param self the listener asking
     *  @return true only if {@code self} is the registered instance
     *  @since 0.9.71+
     */
    static boolean ownsRegistration(RateSummaryListener registered, RateSummaryListener self) {
        return registered == self;
    }

    /**
     * JRobin can only deal with 20 character data source names, so we need to create a unique,
     * munged version from the user/developer-visible name.
     *
     */
    static String createName(I2PAppContext ctx, String wanted) {
        return ctx.sha().calculateHash(DataHelper.getUTF8(wanted)).toBase64().substring(0,20);
    }

    /**
     * Retrieve the tracked rate.
     *
     * @return the rate instance
     */
    public Rate getRate() { return _rate; }

    /**
     *  Open (or re-open) the RRD database and take the Rate's summary registration.
     *
     *  <p>The ledger baseline is taken before the file is touched, not after: a
     *  coalesce that happens while this method is opening the database is a step the
     *  listener was never registered for, so it is lost and the drift has to say so.
     *  Baselineing afterwards would quietly forgive it.
     *
     *  @return success
     */
    public boolean startListening() {
        _coalesceBaseline = _rate.getCoalesceCount();
        _updateBaseline = _updateCount;
        if (!openRdd()) {
            return false;
        }
        _rate.setSummaryListener(this);
        return true;
    }

    /**
     *  Re-obtain the RRD handle, keeping this listener registered and its ledger intact.
     *
     *  <p>The cheapest heal there is, for the case where the handle is closed but the
     *  listener itself, its Rate registration and its recorded history all survive.
     *  Rebuilding instead would work too, at the cost of a new listener, a new
     *  renderer and a window in which the rate has no listener at all - so the ladder
     *  tries this first.
     *
     *  <p>Neither the ledger baseline nor the write count is reset: the drift is the
     *  fault being healed, and a heal that quietly rebased it would hide the very loss
     *  it is repairing.
     *
     *  @return true if a fresh, writable handle is in place
     *  @since 0.9.71+
     */
    boolean reopen() {
        closeHandle();
        return openRdd();
    }

    /**
     *  Build the RRD handle, sample and renderer for this listener.
     *
     *  <p>Split out of {@link #startListening()} so {@link #reopen()} can obtain a new
     *  handle without touching the Rate. Does no I/O on any rate's monitor: it is
     *  called from the sync task and from the health watchdog, never from a
     *  Rate-synchronized callback.
     *
     *  <p>The three fields are published together, at the end and only on success, so
     *  a failure part-way through leaves the listener detached rather than attached
     *  with a null sample that the delivery thread would dereference.
     *
     *  @return true if {@link #_db} is set and writable
     */
    private boolean openRdd() {
        RateStat rs = _rate.getRateStat();
        long period = _rate.getPeriod();
        String baseName = (rs != null ? rs.getName() : "?") + "." + period;
        _name = createName(_context, baseName);
        _eventName = createName(_context, baseName + ".events");
        File rrdFile = null;
        RrdDb db = null;
        boolean existing = false;
        int rows = 0;
        try {
            RrdBackendFactory factory = getBackendFactory();
            String rrdDefName;
            if (_isPersistent) {
                // generate full path for persistent RRD files
                File rrdDir = new SecureFile(_context.getRouterDir(), RRD_DIR);
                rrdFile = new File(rrdDir, RRD_PREFIX + _name + RRD_SUFFIX);
                rrdDefName = rrdFile.getAbsolutePath();
                if (rrdFile.exists()) {
                    existing = true;
                    db = RrdDb.getBuilder().setPath(rrdDefName).setBackendFactory(factory).build();
                    Archive arch = db.getArchive(CF, STEPS);
                    if (arch == null) {
                        throw new IOException("No average CF in " + rrdDefName);
                    }
                    rows = arch.getRows();
                    if (_log.shouldInfo()) {
                        _log.info("Existing RRD " + baseName + " (" + rrdDefName + ") with " + rows +
                                  " rows consuming " + db.getRrdBackend().getLength() + " bytes");
                    }
                } else {
                    rrdDir.mkdir();
                }
            } else {
                rrdDefName = _name;
            }
            if (db == null) {
                // not persistent or not previously existing
                RrdDef def = new RrdDef(rrdDefName, now()/1000, period/1000);
                // for info on the heartbeat, xff, steps, etc, see the rrdcreate man page, aka
                // http://www.jrobin.org/support/man/rrdcreate.html
                long heartbeat = period*10/1000;
                def.addDatasource(_name, DS, heartbeat, Double.NaN, Double.NaN);
                def.addDatasource(_eventName, DS, heartbeat, 0, Double.NaN);
                if (_isPersistent) {
                    rows = (int) Math.max(MIN_ROWS, Math.min(MAX_ROWS, THREE_MONTHS / period));
                } else {
                    rows = MIN_ROWS;
                }
                def.addArchive(CF, XFF, STEPS, rows);
                db = RrdDb.getBuilder().setRrdDef(def).setBackendFactory(factory).build();
                if (_isPersistent) {
                    SecureFileOutputStream.setPerms(new File(rrdDefName));
                }
                if (_log.shouldInfo()) {
                    _log.info("New RRD " + baseName + " (" + rrdDefName + ") with " + rows +
                              " rows consuming " + db.getRrdBackend().getLength() + " bytes");
                }
            }
            Sample sample = db.createSample();
            GraphRenderer renderer = new GraphRenderer(_context, this);
            _rows = rows;
            _db = db;
            _sample = sample;
            _renderer = renderer;
            if (existing) {
                // Seed the ledger from the archive's own high-water mark, so a backfill
                // after a restart cannot try to write a step this file already holds and
                // be rejected as non-monotonic. A database created just now is left at
                // zero: its header carries the creation second, which is not proof that
                // any value was ever written there.
                _lastStoredTimeMs = toArchiveSecondMs(db.getHeader().getLastUpdateTime() * 1000L);
            }
            return true;
        } catch (OutOfMemoryError oom) {
            closeQuietly(db, baseName);
            _log.error("Error starting RRD for stat " + baseName, oom);
        } catch (RrdException re) {
            closeQuietly(db, baseName);
            _log.error("Error starting RRD for stat " + baseName, re);
            // corrupt file?
            if (_isPersistent && rrdFile != null) {
                rrdFile.delete();
            }
        } catch (IOException ioe) {
            closeQuietly(db, baseName);
            _log.error("Error starting RRD for stat " + baseName, ioe);
        } catch (IllegalArgumentException iae) {
            closeQuietly(db, baseName);
            // No backend from RrdBackendFactory
            _log.error("Error starting RRD for stat " + baseName, iae);
            _log.log(Log.WARN, "RRD4J backend error for " + baseName + " (not disabling all graphs)");
        } catch (NoSuchMethodError nsme) {
            closeQuietly(db, baseName);
            // Covariant fail Java 8/9/10
            // see e.g. https://jira.mongodb.org/browse/JAVA-2559
            _log.error("Error starting RRD for stat " + baseName, nsme);
            String s = "Error:" +
                       "\nCompiler JDK mismatch with JRE version " + System.getProperty("java.version") +
                       " and no bootclasspath specified when building." +
                       "\nContact packager.";
            _log.warn(s);
        } catch (Throwable t) {
            closeQuietly(db, baseName);
            _log.error("Error starting RRD for stat " + baseName, t);
        }
        return false;
    }

    /**
     *  Drop a half-built handle so a failed open cannot leak a file descriptor.
     *
     *  @param db the handle to release, or null if none was built
     *  @param baseName the rate's name, for the log line
     */
    private void closeQuietly(RrdDb db, String baseName) {
        if (db == null) {
            return;
        }
        try {
            db.close();
        } catch (IOException ioe) {
            _log.error("Error closing a failed RRD handle for " + baseName, ioe);
        }
    }

    /**
     * Stop listening and close the RRD database.
     *
     * <p>The registration on the Rate is dropped only if this instance still holds it,
     * so tearing down a superseded listener cannot silence its live replacement.
     */
    public void stopListening() {
        if (_db == null) {
            return;
        }
        clearRateRegistration();
        closeHandle();
    }

    /**
     *  Release the RRD handle, leaving the Rate registration alone.
     *
     *  <p>Shared by {@link #stopListening()} and {@link #reopen()}: the only difference
     *  between tearing a listener down and healing it is whether the Rate keeps
     *  pointing at this instance, and that has to be the caller's decision.
     *
     *  <p>The handle is cleared before it is closed, so a delivery thread that is
     *  midway through a write sees a null database and skips rather than writing to a
     *  handle that is on its way out.
     */
    private void closeHandle() {
        RrdDb db = _db;
        _db = null;
        _sample = null;
        _renderer = null;
        if (db == null) {
            return;
        }
        try {
            db.close();
        } catch (IOException ioe) {
            _log.error("Error closing", ioe);
        }
        if (!_isPersistent) {
            // close() does not release resources for memory backend, and a stale
            // entry would make the next open of the same name fail as a duplicate.
            ((RrdMemoryBackendFactory)getBackendFactory(false)).delete(db.getPath());
        }
    }

    /**
     *  Drop our registration on the Rate, but only if we still hold it.
     *
     *  <p>{@link Rate} keeps a single summary listener, so an instance tearing down
     *  after a newer one has registered would otherwise stop the newer one being
     *  called. The newer one keeps its open RRD and still reports itself attached,
     *  so recording dies with no write error, no detach, and no exception anywhere
     *  - every health check keeps saying healthy.
     *
     *  <p>{@link Rate#clearSummaryListener} does the ownership test and the clear as
     *  one atomic operation, so a registration that appears between the two cannot be
     *  dropped. A clear that finds the registration already gone is the ordinary
     *  post-detach case and is not reported; only a registration held by a different
     *  live listener is, because that means two teardowns raced.
     *
     *  @since 0.9.71+
     */
    private void clearRateRegistration() {
        // Rate.clearSummaryListener performs the ownership test and the clear as one
        // atomic operation, so a registration that appears between a test and a clear
        // cannot be dropped.
        if (_rate.clearSummaryListener(this))
            return;
        // The clear did not happen, so anything still registered belongs to somebody
        // else. An absent registration is the ordinary post-detach case and must stay
        // silent or every console teardown would log.
        if (_rate.getSummaryListener() != null && _log.shouldWarn())
            _log.warn("RRD teardown left " + rateName() + " registered to another listener");
    }

    /**
     *  Single graph.
     *
     *  @param end number of periods before now
     */
    public void renderGraph(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                           boolean hideTitle, boolean showEvents, int periodCount,
                           int end, boolean showCredit) throws IOException {
        renderGraph(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount,
                   end, showCredit, (GraphListener) null, null, true);
    }

    /**
     *  Single or two-data-source graph.
     *
     *  @param lsnr2 2nd data source to plot on same graph, or null. Not recommended for events.
     *  @param titleOverride If non-null, overrides the title
     *  @param showRestarts if true, draw the vertical restart lines and "Router restarted" label
     *  @since 0.9.6
     */
    public void renderGraph(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                           boolean hideTitle, boolean showEvents, int periodCount,
                            int end, boolean showCredit, GraphListener lsnr2, String titleOverride,
                            boolean showRestarts) throws IOException {
        if (_renderer == null || _db == null) {
            throw new IOException("No RRD, check logs for previous errors");
        }
        _renderer.render(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount,
                         end, showCredit, lsnr2, titleOverride, showRestarts);
    }

    /**
     *  Render this stat with any number of extra series overlaid as lines.
     *
     *  <p>Every series shares one axis, so the caller must supply stats that measure the
     *  same thing; see {@link GraphGroups} for the sanctioned groupings.
     *
     *  @param extras extra series in legend order, or null for none
     *  @param titleOverride If non-null, overrides the title
     *  @since 0.9.71+
     */
    public void renderGraph(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                           boolean hideTitle, boolean showEvents, int periodCount,
                            int end, boolean showCredit, List<GraphListener> extras, String titleOverride,
                            boolean showRestarts) throws IOException {
        if (_renderer == null || _db == null) {
            throw new IOException("No RRD, check logs for previous errors");
        }
        _renderer.render(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount,
                         end, showCredit, extras, titleOverride, showRestarts);
    }

    /**
     *  Render this stat with any number of extra series, every series drawn as a line.
     *
     *  <p>Identical to {@link #renderGraph(OutputStream, int, int, boolean, boolean, boolean,
     *  boolean, int, int, boolean, List, String, boolean)} except that the primary is a line
     *  rather than a filled area, so no member of the group is hidden behind another.
     *
     *  @param extras extra series in legend order, or null for none
     *  @since 0.9.71+
     */
    public void renderGraphLines(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                                boolean hideTitle, boolean showEvents, int periodCount,
                                int end, boolean showCredit, List<GraphListener> extras, String titleOverride,
                                boolean showRestarts) throws IOException {
        if (_renderer == null || _db == null) {
            throw new IOException("No RRD, check logs for previous errors");
        }
        _renderer.renderLines(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount,
                              end, showCredit, extras, titleOverride, showRestarts);
    }

    /**
     * Render a graph of the stat data using default settings.
     *
     * @param out the output stream to write the graph to
     * @throws IOException if rendering fails
     */
    public void renderGraph(OutputStream out) throws IOException {
        if (_renderer == null || _db == null) {
            throw new IOException("No RRD, check logs for previous errors");
        }
        _renderer.render(out);
    }

    /**
     * Get the RRD datasource name for the primary stat.
     *
     * @return the datasource name
     */
    String getName() {return _name;}
    /**
     * Get the RRD datasource name for the event count.
     *
     * @return the event datasource name
     */
    String getEventName() { return _eventName; }
    /**
     * Get the RRD database instance.
     *
     * @return the RRD database
     */
    RrdDb getData() {return _db;}
    /**
     * Get the current time from the router clock.
     *
     * @return the current time in milliseconds
     */
    long now() {return _context.clock().now();}

    /** @since 0.9.46 */
    RrdBackendFactory getBackendFactory() {return getBackendFactory(_isPersistent);}

    /** @since 0.9.46 */
    @SuppressWarnings("deprecation")
    private static RrdBackendFactory getBackendFactory(boolean isPersistent) {
        // NIO-backed on-disk file for persistent RRDs, in-memory backend otherwise
        return isPersistent ? RrdBackendFactory.getDefaultFactory()
                            : RrdBackendFactory.getFactory("MEMORY");
    }

    /**
     * Get the number of rows in the RRD archive.
     *
     * @return the row count
     * @since 0.8.7
     */
    int getRows() {return _rows;}

    /**
     *  Fetch the last {@code count} data points from the RRD database for use by the
     *  dual-baseline minigraph renderer (data-rx/data-tx attributes on the canvas).
     *  Points are right-aligned; the returned array is NaN-padded on the left when
     *  fewer points are available.
     *
     *  @param count number of most recent data points to retrieve
     *  @return array of average values (units depend on the stat), length = count
     *  @since 0.9.70+
     */
    public double[] getLastValues(int count) {
        double[] result = new double[count];
        if (_db == null) {
            Arrays.fill(result, Double.NaN);
            return result;
        }
        try {
            long period = _rate.getPeriod();
            long now = now() / 1000;
            long end = now - GRAPH_END_OFFSET_SECONDS;
            long start = end - (period / 1000 * count);
            FetchRequest req = _db.createFetchRequest(CF, start, end);
            FetchData data = req.fetchData();
            double[] values = data.getValues(_name);
            if (values != null && values.length > 0) {
                int copyLen = Math.min(values.length, count);
                System.arraycopy(values, 0, result, count - copyLen, copyLen);
            }
        } catch (Exception e) {
            if (_log.shouldWarn()) {
                _log.warn("Error fetching last " + count + " values", e);
            }
            Arrays.fill(result, Double.NaN);
        }
        return result;
    }

    /**
     * Compare with another listener by the underlying rate.
     *
     * @param obj the object to compare with
     * @return true if the listeners track the same rate
     */
    @Override
    public boolean equals(Object obj) {
        return ((obj instanceof GraphListener) && ((GraphListener)obj)._rate.equals(_rate));
    }

    /**
     * Hash code based on the underlying rate.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {return _rate.hashCode();}
}
