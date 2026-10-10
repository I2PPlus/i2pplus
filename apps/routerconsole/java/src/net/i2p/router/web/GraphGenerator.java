package net.i2p.router.web;

import static net.i2p.router.web.GraphConstants.*;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppState;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.core.RrdLog;
import org.rrd4j.core.RrdNioBackendFactory;

/**
 * A thread started by RouterConsoleRunner that checks the configuration for
 * stats to be tracked via jrobin, and adds or deletes RRDs as necessary.
 *
 * This also contains methods to generate xml or graph image output.
 * The rendering for graphs is in GraphRenderer.
 *
 * To control memory, the number of simultaneous renderings is limited.
 */
public class GraphGenerator implements Runnable, ClientApp {
    private final RouterContext _context;
    private final Log _log;
    /** list of GraphListener instances, preserved for iteration by UI helpers */
    private final List<GraphListener> _listeners;
    /** index of listener by rate for O(1) lookup during rendering */
    private final Map<Rate, GraphListener> _listenerByRate = new ConcurrentHashMap<>();
    private static final int MAX_CONCURRENT_RENDER = SystemVersion.isARM() ? Math.max(2, SystemVersion.getCores() / 2) :
                                                  SystemVersion.getMaxMemory() < 256*1024*1024 ? Math.max(8, SystemVersion.getCores() / 2) :
                                                  Math.max(12, SystemVersion.getCores());
    private final Semaphore _sem;
    private volatile boolean _isRunning;
    private ScheduledExecutorService _scheduler;
    /** Health watchdog scheduler, separate from {@link #_scheduler} so a stuck
     * sync task cannot stop the thing that is meant to notice it is stuck. */
    private volatile ScheduledExecutorService _healthScheduler;
    /** Guards the watchdog against being scheduled twice; see {@link #startHealthWatchdog()}. */
    private boolean _healthStarted;
    /**
     * Whether this instance configured the shared RRD backend factory.
     *
     * <p>{@code RrdBackendFactory.getDefaultFactory()} is a process-wide singleton, so
     * closing it from a {@link Shutdown} that never configured it would pull the
     * backend out from under every other graph listener still recording. Set at the
     * point this instance calls {@code setSyncPoolSize}, which is the only place it
     * takes responsibility for the shared configuration.
     *
     * @see #closeBackendFactory()
     */
    private volatile boolean _ownsBackendFactory;
    /** Throttles the stall report so the watchdog and the sync task cannot double-log it. */
    private final ReportThrottle _stallThrottle = new ReportThrottle();
    /** Throttles the ledger-drift report independently of the stall report. */
    private final ReportThrottle _driftThrottle = new ReportThrottle();
    /**
     * Set when a stall or drift report is logged, so the matching recovery line is
     * emitted exactly once when the condition clears. Without it a healthy router
     * would log a recovery on every watchdog tick, and a router that never stalled
     * would log one at all.
     */
    private boolean _stallReported;
    private boolean _driftReported;
    /** Coalesce delta across all tracked listeners at the previous watchdog tick. */
    private long _lastCoalesceSum;
    /** Consecutive watchdog ticks on which no tracked rate coalesced at all. */
    private int _coalesceStalledTicks;
    private static final String NAME = "GraphGen";

    /** Emit a liveness heartbeat every N sync ticks (~27min at the 90s period). */
    private static final int HEARTBEAT_TICKS = 20;
    /**
     * Watchdog period.
     *
     * <p>Short enough that the next occurrence of a silently dead data path names its
     * own cause while somebody is still looking at the log, and long enough that a
     * healthy router does no measurable work. Deliberately separate from the 90s sync
     * task: a fault severe enough to wedge that task is exactly the fault the watchdog
     * exists to report, so sharing a thread would hide the symptom it is looking for.
     *
     * @since 0.9.71+
     */
    private static final int HEALTH_INTERVAL_MS = 10_000;
    /** Longest gap between coalesces on any tracked rate before "the rates stopped". */
    private static final long COALESCE_STALL_MS = 120_000L;
    /** {@link #COALESCE_STALL_MS} expressed in watchdog ticks. */
    private static final int COALESCE_STALL_TICKS = (int) (COALESCE_STALL_MS / HEALTH_INTERVAL_MS);
    /** Upper bound on how many retained samples one backfill is asked for. */
    static final int MAX_BACKFILL_STEPS = 8;
    /** Repeat period for a report whose numbers have not changed. @since 0.9.71+ */
    private static final long REPORT_REPEAT_MS = 120_000L;
    /** Wall-clock time when graph generation started; lets the never-yet-written staleness
     * test distinguish an empty startup window from a genuinely stalled writer. */
    private final long _startedMs = System.currentTimeMillis();
    private int _ticks;

    /**
     * Installs the rrd4j log bridge and the shutdown hook. Recording starts in run().
     *
     * @param ctx the router context, for properties, stats, the log and the app manager
     */
    public GraphGenerator(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(getClass());
        _listeners = new CopyOnWriteArrayList<>();
        _sem = new Semaphore(MAX_CONCURRENT_RENDER, true);
        installRrdLogging();
        _context.addShutdownTask(new Shutdown());
    }

    /**
     * Route rrd4j diagnostics into the router log.
     *
     * <p>The vendored rrd4j in apps/jrobin is built without a classpath, so it
     * cannot log through I2P itself and ships with all of its own logging
     * stripped. That is why a permanently cancelled flush schedule produced no
     * output at all: the .jrb files simply stopped changing. This installs the
     * bridge so that failure is visible from now on.
     */
    private void installRrdLogging() {
        RrdLog.setDelegate((severity, message, cause) -> {
            if ("error".equals(severity)) {
                _log.error("rrd4j: " + message, cause);
            } else if ("warn".equals(severity)) {
                _log.warn("rrd4j: " + message, cause);
            } else if (_log.shouldDebug()) {
                _log.debug("rrd4j: " + message);
            }
        });
    }

    /**
     * The generator registered with the app manager, which only exists while it is running.
     *
     * @param ctx the context whose app manager holds the registration
     * @return null if disabled
     * @since 0.9.38
     */
    public static GraphGenerator instance(I2PAppContext ctx) {
        ClientApp app = ctx.clientAppManager().getRegisteredApp(NAME);
        return (app != null) ? (GraphGenerator) app : null;
    }

    /**
     * run.
     */
    @Override
    public void run() {
        try {
            runGenerator();
        } catch (Throwable t) {
            // Registration happens partway through, so a failure before that point leaves the app
            // unregistered and the console reports graphing as unavailable with nothing in the log
            // to explain it. Make that state explicit instead of letting the thread die quietly.
            _isRunning = false;
            _log.error("Graph generation failed to start - "
                       + "graphs will be unavailable until restart", t);
            setDisabled();
        }
    }

    /**
     * Body of {@link #run()}, separated so any failure before the app registers itself is logged.
     */
    private void runGenerator() {
        // JRobin 1.5.9 crashes these JVMs
        if (SystemVersion.isApache() /* Harmony */ || SystemVersion.isGNU()) /* JamVM or gij */ {
            _log.logAlways(Log.WARN, "Graphing not supported with this JVM: " +
                                     System.getProperty("java.vendor") + ' ' +
                                     System.getProperty("java.version") + " (" +
                                     System.getProperty("java.runtime.name") + ' ' +
                                     System.getProperty("java.runtime.version") + ')');
            return;
        }
        _isRunning = true;
        boolean isPersistent = _context.getBooleanPropertyDefaultTrue(GraphListener.PROP_PERSISTENT);
        int syncThreads;
        if (isPersistent) {
            String spec = _context.getProperty("stat.summaries", DEFAULT_DATABASES);
            String[] rates = DataHelper.split(spec, ",");
            syncThreads = 1;
            // delete files for unconfigured rates
            Set<String> configured = new HashSet<>(rates.length);
            for (String r : rates) {configured.add(GraphListener.createName(_context, r));}
            File rrdDir = new File(_context.getRouterDir(), GraphListener.RRD_DIR);
            FileFilter filter = new FileSuffixFilter(GraphListener.RRD_PREFIX, GraphListener.RRD_SUFFIX);
            File[] files = rrdDir.listFiles(filter);
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File f = files[i];
                    String name = f.getName();
                    String hash = name.substring(GraphListener.RRD_PREFIX.length(), name.length() - GraphListener.RRD_SUFFIX.length());
                    if (!configured.contains(hash)) {f.delete();}
                }
            }
        } else {
            syncThreads = 0;
            deleteOldRRDs();
        }
        RrdNioBackendFactory.setSyncPoolSize(syncThreads);
        // Configuring the shared singleton is what makes this instance responsible for
        // it at shutdown; see _ownsBackendFactory.
        _ownsBackendFactory = true;
        // Reported because the RRD backends share one scratch buffer file, which
        // is only safe while flushes are serialised - and rrd4j snapshots the
        // pool size into a static singleton at class-init, so this call only wins
        // if nothing touched rrd4j first. Worth seeing rather than assuming.
        if (_log.shouldInfo()) {
            _log.info("RRD4J sync pool requested=" + syncThreads + " effective="
                      + RrdNioBackendFactory.getSyncPoolSize() + " period="
                      + RrdNioBackendFactory.getSyncPeriod() + "s backend="
                      + RrdBackendFactory.getDefaultFactory().getClass().getSimpleName());
        }
        RrdNioBackendFactory.setThreadFactory(r -> {
            Thread t = new Thread(r, "RRD4JSync");
            t.setDaemon(true);
            return t;
        });
        _context.clientAppManager().register(this);
        if (_log.shouldInfo()) {
            _log.info("Graph generation registered; persistent=" + isPersistent
                      + " listeners=" + _listeners.size());
        }
        _scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StatWriter");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        final String[] specsHolder = {""};
        try {
            _scheduler.scheduleAtFixedRate(() -> syncSpecsHolder(specsHolder), 0, 90, TimeUnit.SECONDS);
        } catch (Exception e) {
            _log.error("Failed to sync RRD4J stats to disk", e);
        }
        startHealthWatchdog();
    }

    /**
     * Start the 10s health watchdog, at most once.
     *
     * <p>Idempotent on purpose: {@code runGenerator()} is the only caller and runs
     * once, but a second scheduler would mean two threads applying the heal ladder to
     * the same listeners concurrently, which is how a REOPEN ends up racing a REBUILD
     * into a half-closed handle. Guarded here rather than trusted to the caller.
     *
     * @since 0.9.71+
     */
    private synchronized void startHealthWatchdog() {
        if (_healthStarted) {
            return;
        }
        _healthStarted = true;
        _healthScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RRDHealth");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        try {
            _healthScheduler.scheduleAtFixedRate(this::healthCheck, HEALTH_INTERVAL_MS,
                                                HEALTH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            _log.error("Failed to start the RRD health watchdog", e);
            stopHealthWatchdog();
        }
    }

    /**
     * Stop the health watchdog if one was started.
     *
     * <p>Never called from a watchdog task: a scheduler that awaits its own
     * termination from inside a task cannot terminate, and the bounded wait would
     * become an unbounded stall.
     *
     * @since 0.9.71+
     */
    private synchronized void stopHealthWatchdog() {
        ScheduledExecutorService health = _healthScheduler;
        _healthScheduler = null;
        _healthStarted = false;
        if (health == null) {
            return;
        }
        health.shutdown(); // Disable new tasks, let the running tick finish
        try {
            if (!health.awaitTermination(10, TimeUnit.SECONDS)) {
                health.shutdownNow(); // Force if not terminated in time
                health.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            health.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sync RRD4J stats to disk on a fixed schedule.
     * Stops the generator if the router is no longer alive.
     * @param specsHolder single-element holder for the active specs string
     * @since 0.9.70+
     */
    private void syncSpecsHolder(String[] specsHolder) {
        try {
            if (!_isRunning || !_context.router().isAlive()) {
                stop();
                return;
            }
            specsHolder[0] = adjustDatabases(specsHolder[0]);
            reviveDetachedListeners();
            if (_log.shouldDebug() && (++_ticks % HEARTBEAT_TICKS) == 0) {
                int detached = 0;
                for (GraphListener lsnr : _listeners) {
                    if (lsnr.isDetached()) {detached++;}
                }
                _log.debug("graph heartbeat: tick " + _ticks + ", listeners=" + _listeners.size()
                           + " detached=" + detached + " running=" + _isRunning);
            }
            // Health check: silently stopping the data path leaves the .jrb files
            // frozen, which only shows up as graphs going stale many minutes later. Log it
            // the moment writes stop so the next occurrence is explained instead of masked.
            reportWriteStaleness();
        } catch (Throwable t) {
            // Throwable incl. Error: an escape silences a fixed-rate scheduled task forever.
            _log.error("Failed to sync RRD4J stats to disk", t);
        }
    }

/**
 * Why a graph listener is not producing RRD writes.
 *
 * <p>The non-OK values are distinct faults with distinct fixes, so the staleness
 * report names them individually: a single "nothing is being written" count
 * cannot tell an operator which one to go and fix.
 *
 * @since 0.9.71+
 */
    enum StaleCause {
        /** Not stalled: attached, registered, and writing within 2x its rate period. */
        OK,
        /**
         * Attached and registered, but no write has ever succeeded. The meaningful age is
         * how long the listener instance has existed, since there is no earlier write to
         * measure back from.
         */
        NEVER_WRITTEN,
        /**
         * Attached, registered, and no write, but the rate itself has not coalesced since
         * the listener attached, so there was never a sample to write. Nothing is being
         * lost and nothing is broken, which is why this is separated from
         * {@link #NEVER_WRITTEN}: a rate nobody updates is idle by definition, and
         * reporting it as a data-path fault produces an ERROR that repeats forever and
         * describes no action an operator can take.
         */
        RATE_IDLE,
        /**
         * Attached to an open RRD, but the Rate no longer points at this listener, so it
         * can never be notified again. Recording is dead with no write error, no detach
         * and no exception: the state a newer listener's registration leaves behind.
         */
        UNREGISTERED,
        /**
         * Attached, registered, and written to before: writes stopped while every part of
         * the data path still looks healthy. The meaningful age is time since the last
         * write.
         */
        WRITES_STOPPED,
        /**
         * The coalesce sweep itself is not running, so no rate is being asked to
         * coalesce and every listener starves at once. This outranks every other
         * cause: it is the only one that explains a whole table stopping together,
         * and it is invisible from inside the stats package because the symptom is
         * that the coalesce task is queued behind other work and never executes.
         * The fix is never in a listener.
         */
        COALESCE_STALLED;

        /**
         * Whether this cause is a fault that loses recorded data.
         *
         * <p>Everything except {@link #OK} and {@link #RATE_IDLE}: an idle rate records
         * nothing because nothing is happening, not because recording is broken.
         *
         * @return true unless the cause is OK or an idle rate
         */
        boolean isFault() {return this != OK && this != RATE_IDLE;}
    }

    /**
     * Floor applied to a rate's period when deciding whether its listener is stale.
     *
     * <p>Sub-minute rates can legitimately go minutes between coalesces, and 2x a few
     * hundred milliseconds would flag them from their first tick onwards.
     *
     * @since 0.9.71+
     */
    static final long MIN_STALENESS_PERIOD_MS = 60000L;

    /**
     * Count and oldest age for one {@link StaleCause}.
     *
     * <p>Deliberately free of router context and of synchronization: a tally is built
     * and read entirely within the single-threaded sync task, and keeping it plain lets
     * the report wording be unit tested without a running router.
     *
     * @since 0.9.71+
     */
    static final class CauseTally {
        private final StaleCause _cause;
        private int _count;
        /** Largest age recorded, in ms. */
        private long _ageMs;
        /** Whether {@link #_ageMs} is measured from the last write rather than from graphing start. */
        private boolean _sinceLastWrite;
        /** Names of the listeners in this tally, bounded by {@link #MAX_NAMED}. */
        private final List<String> _names = new ArrayList<>(4);
        private int _unnamed;

        /**
         * An empty tally for one cause.
         *
         * @param cause the cause this tally counts
         */
        CauseTally(StaleCause cause) {
            _cause = cause;
        }

        /**
         * Add one listener to this tally.
         *
         * @param ageMs age from {@link GraphGenerator#staleAge}
         * @param sinceLastWrite true if measured from the last successful write
         */
        void record(long ageMs, boolean sinceLastWrite) {
            record(ageMs, sinceLastWrite, null);
        }

        /**
         * Add one listener to this tally, naming it.
         *
         * <p>The name is what makes the report actionable: a count of stalled listeners
         * with no names leaves the operator to diff every rate on the page against the
         * graphs, which is exactly the work the report exists to save.
         *
         * @param ageMs age from {@link GraphGenerator#staleAge}
         * @param sinceLastWrite true if measured from the last successful write
         * @param name the listener's stat name, or null when not known
         */
        void record(long ageMs, boolean sinceLastWrite, String name) {
            _count++;
            if (ageMs > _ageMs) {
                _ageMs = ageMs;
                _sinceLastWrite = sinceLastWrite;
            }
            if (name == null) {
                _unnamed++;
            } else if (_names.size() < MAX_NAMED) {
                _names.add(name);
            } else {
                _unnamed++;
            }
        }

        /**
         * How many listeners this cause has been blamed for.
         *
         * @return number of listeners attributed to this cause
         */
        int count() { return _count; }

        /**
         * Render the names recorded for this cause, or an empty string when none were.
         *
         * @return the trailing "name[, name]" clause, without a leading separator
         */
        String names() {
            if (_names.isEmpty()) {return "";}
            StringBuilder buf = new StringBuilder();
            for (String name : _names) {
                if (buf.length() > 0) {buf.append(", ");}
                buf.append(name);
            }
            if (_unnamed > 0) {
                buf.append(_names.size() == 1 ? " and " : " and ").append(_unnamed).append(" more");
            }
            return buf.toString();
        }

        /**
         * Render this cause for the log line.
         *
         * @return {@code cause=count} when empty, otherwise {@code cause=count (oldest Ns origin)}
         */
        String describe() {
            String name = _cause.name().toLowerCase();
            if (_count <= 0) {
                return name + "=0";
            }
            String listed = names();
            return name + "=" + _count + " (oldest " + (_ageMs / 1000) + "s "
                   + staleAgeOrigin(_sinceLastWrite) + ")"
                   + (listed.isEmpty() ? "" : ": " + listed);
        }
    }

    /** Most listener names a single tally will list before summarising the rest as a count. */
    static final int MAX_NAMED = 5;

    /** Origin wording for an age measured from the last successful write. @since 0.9.71+ */
    private static final String staleAgeSinceLastWrite = "since last write";
    /**
     * Origin wording for an age with no earlier write to measure back from.
     *
     * <p>The listener's own attach time, not the router's graphing start: only the
     * listener knows how long it has had the chance to write.
     *
     * @since 0.9.71+
     */
    private static final String staleAgeSinceLastWriteAttach = "since the listener attached";

    /**
     * Decide whether a listener is stalled, and why, assuming the coalesce sweep has
     * run recently enough to have fed these rates.
     *
     * @param detached {@link GraphListener#isDetached()} - the RRD is closed
     * @param registered {@link GraphListener#isRegistered()} - the Rate still points at the listener
     * @param lastUpdateSuccess wall-clock ms of the last successful write, or 0 if none
     * @param now current wall-clock ms
     * @param startedMs wall-clock ms when graphing began
     * @param ratePeriod the rate's period in ms
     * @return {@link StaleCause#OK} when there is nothing to report, otherwise the cause
     * @see #classifyStaleness(boolean, boolean, long, long, long, long, boolean)
     * @since 0.9.71+
     */
    static StaleCause classifyStaleness(boolean detached, boolean registered, long lastUpdateSuccess,
                                        long now, long startedMs, long ratePeriod) {
        return classifyStaleness(detached, registered, lastUpdateSuccess, now, startedMs,
                                 ratePeriod, false);
    }

    /**
     * Decide whether a listener is stalled, and why.
     *
     * <p>Pure decision logic, split out of the sync task so the thresholds can be pinned
     * by unit tests without a router or an RRD file.
     *
     * @param detached {@link GraphListener#isDetached()} - the RRD is closed
     * @param registered {@link GraphListener#isRegistered()} - the Rate still points at the listener
     * @param lastUpdateSuccess wall-clock ms of the last successful write, or 0 if none
     * @param now current wall-clock ms
     * @param startedMs wall-clock ms when graphing began
     * @param ratePeriod the rate's period in ms
     * @param coalesceStalled true when the coalesce sweep has not run recently enough to
     * have fed these rates; see {@link net.i2p.stat.StatManager#getCoalesceSweepAgeMs(long)}
     * @return {@link StaleCause#OK} when there is nothing to report, otherwise the cause
     * @since 0.9.71+
     */
    static StaleCause classifyStaleness(boolean detached, boolean registered, long lastUpdateSuccess,
                                        long now, long startedMs, long ratePeriod,
                                        boolean coalesceStalled) {
        // A detached listener is rebuilt by reviveDetachedListeners() on this same tick, so
        // counting it here would report a fault that is already being handled.
        if (detached) {
            return StaleCause.OK;
        }
        // Tested before the write ages because it outranks them: an unregistered listener
        // cannot write no matter what its history looks like, and re-arming is the fix.
        // No age threshold applies - the fault exists the moment the registration is lost.
        if (!registered) {
            return StaleCause.UNREGISTERED;
        }
        // Outranks the write ages, and is checked before them for that reason: if the
        // sweep is not running then nothing can write, whatever this listener's own
        // history says. Attributing a table-wide stoppage to a per-listener cause is
        // what sends an operator hunting in the wrong subsystem.
        if (coalesceStalled) {
            return StaleCause.COALESCE_STALLED;
        }
        long lag = staleAge(now, startedMs, lastUpdateSuccess);
        // Strictly greater: at exactly 2x the period the next sample is still merely due.
        if (lag <= 2 * Math.max(ratePeriod, MIN_STALENESS_PERIOD_MS)) {
            return StaleCause.OK;
        }
        return lastUpdateSuccess > 0 ? StaleCause.WRITES_STOPPED : StaleCause.NEVER_WRITTEN;
    }

    /**
     * Age of a listener's data path, measured from whichever origin exists.
     *
     * @param now current wall-clock ms
     * @param startedMs wall-clock ms when graphing began
     * @param lastUpdateSuccess wall-clock ms of the last successful write, or 0 if none
     * @return ms since the last write, or ms since graphing began when nothing was ever written
     * @since 0.9.71+
     */
    static long staleAge(long now, long startedMs, long lastUpdateSuccess) {
        return lastUpdateSuccess > 0 ? now - lastUpdateSuccess : Math.max(0, now - startedMs);
    }

    /**
     * Wording for a {@link #staleAge} value: which event the age is measured from.
     *
     * @param sinceLastWrite true if a successful write has ever happened
     * @return the origin to quote alongside the age
     * @since 0.9.71+
     */
    static String staleAgeOrigin(boolean sinceLastWrite) {
        return sinceLastWrite ? staleAgeSinceLastWrite : staleAgeSinceLastWriteAttach;
    }

    /**
     * Split "the rate has nothing to say" out of the causes that lose data.
     *
     * <p>A listener that has never written, or has stopped writing, looks identical
     * whether its RRD writes are failing or whether nothing in the router is feeding its
     * rate at all. Only the first loses data. Event count settles it: a rate with no
     * events in its last completed period had nothing to hand the listener, so there is
     * nothing being lost.
     *
     * <p>Event count rather than coalesce count, deliberately. Coalescing is driven by the
     * StatManager sweep and happens on schedule whether or not any data arrives, so it
     * keeps advancing for a rate nobody is updating. That made the coalesce form unable
     * to distinguish "idle" from "fed but unwritten" for any listener that had been
     * running, which is precisely the case a stopped service produces.
     *
     * @param cause classification from {@link #classifyStaleness}
     * @param lastEventCount events the rate accrued in its last completed period, or 0
     * when it accrued none
     * @return {@link StaleCause#RATE_IDLE} when the rate produced no events in its last
     * period, otherwise cause unchanged
     * @since 0.9.71+
     */
    static StaleCause refineForIdleRate(StaleCause cause, long lastEventCount) {
        if (cause != StaleCause.NEVER_WRITTEN && cause != StaleCause.WRITES_STOPPED) {
            return cause;
        }
        if (lastEventCount > 0) {return cause;}
        return StaleCause.RATE_IDLE;
    }

    /**
     * Compose the write-stall log line.
     *
     * <p>Pure: a function of the three tallies, so both the wording and the count
     * attribution can be unit tested without a router.
     *
     * @param totalListeners number of listeners being tracked
     * @param neverWritten tally for {@link StaleCause#NEVER_WRITTEN}
     * @param unregistered tally for {@link StaleCause#UNREGISTERED}
     * @param writesStopped tally for {@link StaleCause#WRITES_STOPPED}
     * @return a single-line message beginning "RRD data stalled:"
     * @since 0.9.71+
     */
    static String formatStaleness(int totalListeners, CauseTally neverWritten,
                                  CauseTally unregistered, CauseTally writesStopped) {
        return formatStaleness(totalListeners, neverWritten, unregistered, writesStopped,
                               new CauseTally(StaleCause.COALESCE_STALLED));
    }

    /**
     * Compose the write-stall log line, counting the coalesce fault alongside the
     * per-listener causes.
     *
     * @param totalListeners number of listeners being tracked
     * @param neverWritten tally for {@link StaleCause#NEVER_WRITTEN}
     * @param unregistered tally for {@link StaleCause#UNREGISTERED}
     * @param writesStopped tally for {@link StaleCause#WRITES_STOPPED}
     * @param coalesceStalled tally for {@link StaleCause#COALESCE_STALLED}
     * @return a single-line message beginning "RRD data stalled:"
     */
    static String formatStaleness(int totalListeners, CauseTally neverWritten,
                                  CauseTally unregistered, CauseTally writesStopped,
                                  CauseTally coalesceStalled) {
        int stalled = neverWritten.count() + unregistered.count() + writesStopped.count()
                      + coalesceStalled.count();
        return "RRD data stalled: " + stalled + "/" + totalListeners
               + " graph listeners not writing within 2x their rate period \n* "
               + coalesceStalled.describe() + ", "
               + neverWritten.describe() + ", "
               + unregistered.describe() + ", "
               + writesStopped.describe();
    }

    /**
     * Loud failure for a silently stalled data path: logs an ERROR when any attached
     * listener is not recording, naming the cause per listener class rather than
     * reporting one ambiguous count.
     *
     * <p>Called from both the 90s sync task and the 10s watchdog, so the report is
     * throttled on its own numbers rather than on which task happened to run: an
     * unchanged stall is repeated every {@link #REPORT_REPEAT_MS}, a changed one
     * immediately, and the first occurrence is never throttled at all.
     *
     * <p>A single INFO follows once the stall clears, so the ERROR has a visible
     * ending rather than being indistinguishable from a router still losing data.
     */
    private void reportWriteStaleness() {
        long now = System.currentTimeMillis();
        CauseTally neverWritten = new CauseTally(StaleCause.NEVER_WRITTEN);
        CauseTally rateIdle = new CauseTally(StaleCause.RATE_IDLE);
        // Split from rateIdle by how the listener got here: a rate idle since the
        // listener attached never had a producer, while one idle after writing had one
        // that went away -- usually a stopped service. Same "nothing lost" verdict, but
        // the operator needs to tell the two apart, since only one is a service event.
        // The tally keeps WRITES_STOPPED as its label because that is the cause it was
        // refined from, and CauseTally.describe() prints that label verbatim.
        CauseTally stoppedService = new CauseTally(StaleCause.WRITES_STOPPED);
        CauseTally unregistered = new CauseTally(StaleCause.UNREGISTERED);
        CauseTally writesStopped = new CauseTally(StaleCause.WRITES_STOPPED);
        CauseTally coalesceStalled = new CauseTally(StaleCause.COALESCE_STALLED);
        // Asked of the StatManager rather than inferred per listener: the sweep runs on
        // the shared coalesce timer, so when that timer is saturated every listener starves
        // together and no per-listener evidence can tell that apart from N simultaneous
        // faults. This watchdog runs on its own scheduler, so it still reports while the
        // timer it is watching is wedged.
        boolean sweepStalled = isCoalesceStalled(now);
        for (GraphListener lsnr : _listeners) {
            long lastOk = lsnr.getLastUpdateSuccess();
            // A listener's own attach time is its age origin, not the graphing session's:
            // a listener created seconds ago has had no chance to write yet, and charging
            // it the age of the whole session flags every rebuild as a stall.
            long attached = lsnr.getAttachedMs() > 0 ? lsnr.getAttachedMs() : _startedMs;
            StaleCause cause = classifyStaleness(lsnr.isDetached(), lsnr.isRegistered(), lastOk,
                                                 now, attached, lsnr.getRate().getPeriod(),
                                                 sweepStalled);
            if (cause == StaleCause.OK) {
                continue;
            }
            // Event count, not coalesce count: coalescing runs on schedule whether or not
            // any data arrives, so it cannot tell a stopped service from a live one.
            StaleCause preRefined = cause;
            cause = refineForIdleRate(cause, lsnr.getRate().getLastEventCount());
            CauseTally tally;
            switch (cause) {
                case NEVER_WRITTEN:
                    tally = neverWritten;
                    break;
                case RATE_IDLE:
                    // Which flavour of idle depends on the cause we refined away from.
                    tally = preRefined == StaleCause.WRITES_STOPPED ? stoppedService : rateIdle;
                    break;
                case UNREGISTERED:
                    tally = unregistered;
                    break;
                case COALESCE_STALLED:
                    tally = coalesceStalled;
                    break;
                default:
                    tally = writesStopped;
                    break;
            }
            tally.record(staleAge(now, attached, lastOk), lastOk > 0,
                         GraphListener.statName(lsnr.getName()));
        }
        int stalled = neverWritten.count() + unregistered.count() + writesStopped.count()
                      + coalesceStalled.count();
        if (stalled <= 0) {
            // Announce the end of a stall, once. The ERROR that opened it says only
            // that data stopped; without this the operator sees the error followed by
            // silence and cannot tell a repaired router from one still losing data.
            if (_stallReported) {
                _stallReported = false;
                if (_log.shouldInfo()) {
                    _log.info("RRD data stalled: recovered, all " + _listeners.size()
                              + " graph listeners recording");
                }
            }
            return;
        }
        String msg = formatStaleness(_listeners.size(), neverWritten, unregistered, writesStopped,
                                  coalesceStalled);
          if (rateIdle.count() > 0) {
              // Appended, not counted in the headline: a rate nothing updates is idle, not
              // broken, but the operator still needs to see which ones are holding the
              // remaining "never written" graphs empty.
              msg += "\n* " + rateIdle.describe() + " (rate never fed, nothing lost)";
          }
          if (stoppedService.count() > 0) {
              // Same verdict, different cause: this rate was being written and stopped,
              // which is a service going away rather than a stat that was never wired up.
              // Named so the operator can tell "nothing to graph" from "nothing running".
              msg += "\n* " + stoppedService.describe()
                          + " (service stopped, nothing lost)";
          }
        if (_stallThrottle.allow(now, stalled, REPORT_REPEAT_MS)) {
            _stallReported = true;
            _log.error(msg);
            // Named separately, and first, because it is the only cause whose remedy is
            // not in this subsystem. An operator reading the tally line sees counts and
            // has to infer the mechanism; this line states it and points at the timer.
            if (coalesceStalled.count() > 0) {
                _log.error("Stat coalesce sweep has not completed for "
                           + coalesceSweepAbsence(now)
                           + "\n* No rate is being coalesced and every graph listener starves"
                           + " regardless of its own state \n* Check the router coalesce timer"
                           + " (SimpleTimer2) rather than any listener");
            }
        }
    }

    /**
     * Whether the coalesce sweep has missed enough cycles that no rate can be recording.
     *
     * <p>Pure threshold test, separated from the manager lookup so it can be pinned by
     * unit tests. A sweep is considered stalled once it is older than twice the shortest
     * rate period: the coalesce timer runs every 50s and the shortest period is one
     * minute, so two missed sweeps is already unambiguous.
     *
     * @param sweepAgeMs ms since the last completed sweep, or -1 if none ever has
     * @return true when the sweep is stalled
     * @since 0.9.71+
     */
    static boolean isCoalesceSweepStalled(long sweepAgeMs) {
        return sweepAgeMs < 0 || sweepAgeMs > 2 * MIN_STALENESS_PERIOD_MS;
    }

    /**
     * Grace period before a coalesce sweep that has never run counts as a stall.
     *
     * <p>One coalesce interval ({@code Router.COALESCE_TIME}, 50s) plus one shortest
     * rate period, rounded up to two staleness periods.
     *
     * @since 0.9.71+
     */
    static final long COALESCE_STALL_GRACE_MS = 2 * MIN_STALENESS_PERIOD_MS;

    /**
     * Whether the shared coalesce sweep should be reported as stalled right now.
     *
     * <p>Separate from {@link #isCoalesceSweepStalled(long)} so the start-up window can
     * be pinned by tests. Until a sweep has ever completed its age is reported as -1,
     * meaning "none ever", which that function quite correctly calls stalled. At
     * start-up, though, that is not a fault - it is a sweep that has not come round yet,
     * because the coalesce timer only fires every {@code Router.COALESCE_TIME}. Judging
     * it as stalled made every boot log an ERROR naming the coalesce timer as the fault
     * within seconds of start-up, and pointed the operator at the one subsystem the
     * evidence could not implicate.
     *
     * @param everSwept whether any sweep has completed since graphing began
     * @param sweepAgeMs ms since the last completed sweep, or -1 if none ever has
     * @param startedMs wall-clock ms when graphing began
     * @param now current wall-clock ms
     * @return true only once a sweep should have come round and has not
     * @since 0.9.71+
     */
    static boolean isCoalesceStalledNow(boolean everSwept, long sweepAgeMs,
                                        long startedMs, long now) {
        if (!everSwept) {
            return now - startedMs > COALESCE_STALL_GRACE_MS;
        }
        return isCoalesceSweepStalled(sweepAgeMs);
    }

    /** Whether the coalesce sweep is currently stalled, per {@link #isCoalesceStalledNow}. */
    private boolean isCoalesceStalled(long now) {
        StatManager sm = _context.statManager();
        if (sm == null) {
            return false;
        }
        return isCoalesceStalledNow(sm.hasCoalesced(), sm.getCoalesceSweepAgeMs(now),
                                    _startedMs, now);
    }

    /**
     * How long the coalesce sweep has been absent, for the report.
     *
     * <p>Always a real, non-negative figure: the caller only reaches this once the
     * absence has already exceeded the normal cadence, so any sentinel would be
     * meaningless noise in an operator-facing line. A sweep that has never run has no
     * age to quote, so the age of the graphing window is reported in its place and
     * labelled as such - a lower bound on the absence, and never the {@code -1} the
     * accessor uses to mean "none ever".
     *
     * @param now current wall-clock ms
     * @return a human-readable duration that always starts with a non-negative number
     */
    private String coalesceSweepAbsence(long now) {
        StatManager sm = _context.statManager();
        long age = sm == null ? -1 : sm.getCoalesceSweepAgeMs(now);
        return formatCoalesceSweepAbsence(age, _startedMs, now);
    }

    /**
     * Render how long the coalesce sweep has been absent.
     *
     * <p>Pure, so the sentinel handling can be pinned by test. The bug this exists to
     * prevent: the accessor reports an unknown age as {@code -1}, and an earlier
     * version interpolated that straight into the operator-facing ERROR, producing
     * the nonsensical {@code "has not completed for -1s"}. A negative duration is not
     * a duration. The unknown case is now reported as the age of the graphing window
     * and labelled, which is a real lower bound on the absence.
     *
     * @param sweepAgeMs ms since the last completed sweep, or negative if none ever has
     * @param startedMs wall-clock ms when graphing began
     * @param now current wall-clock ms
     * @return a duration that always begins with a non-negative number of seconds
     * @since 0.9.71+
     */
    static String formatCoalesceSweepAbsence(long sweepAgeMs, long startedMs, long now) {
        if (sweepAgeMs > 0) {
            return (sweepAgeMs / 1000L) + "s";
        }
        return (Math.max(0L, now - startedMs) / 1000L) + "s (none has ever run)";
    }

    /**
     * Does a configured rate need a listener rebuilt?
     *
     * <p>Three distinct faults, each of which leaves the rate unrecorded for the rest of
     * the router's life while every observable state still reads healthy: the RRD was
     * closed after repeated write failures ({@code detached}), the map lost its listener
     * ({@code mapped} false), or a newer listener took the Rate's single registration so
     * this one can never be called again ({@code registered} false).
     *
     * @param mapped false if the rate has no listener entry at all
     * @param detached true if the listener's RRD is closed
     * @param registered true if the Rate still points at the listener
     * @return true if the rate needs a new listener
     * @since 0.9.71+
     */
    static boolean needsRevive(boolean mapped, boolean detached, boolean registered) {
        return !mapped || detached || !registered;
    }

    /**
     * Re-create any listener that has stopped recording, so neither a transient write
     * failure nor a lost Rate registration can silently retire a graph for the
     * remaining life of the router.
     *
     * <p>A detached listener stays in the rate-to-listener map, so it is never rebuilt
     * by adjustDatabases(): that only adds rates missing from the old spec. The same
     * holds for a listener that kept its open RRD but lost the registration, and for a
     * rate whose mapping lost its listener. This runs on the same tick as the spec
     * sync, reusing the existing scheduled task rather than adding a thread.
     *
     * @since 0.9.71+
     */
    private void reviveDetachedListeners() {
        for (Map.Entry<Rate, GraphListener> entry : _listenerByRate.entrySet()) {
            Rate rate = entry.getKey();
            GraphListener lsnr = entry.getValue();
            if (!needsRevive(lsnr != null,
                             lsnr != null && lsnr.isDetached(),
                             lsnr != null && lsnr.isRegistered())) {
                continue;
            }
            rebuildListener(rate, lsnr, "re-attach");
        }
    }

    /**
     * Replace a listener for a rate, releasing the old one first.
     *
     * <p>Shared with the health watchdog's REBUILD step so there is exactly one
     * definition of what retiring a listener means: the old handle is released before
     * the replacement re-opens the same file, and the stale entry leaves the listener
     * list so it keeps counting only what actually records.
     *
     * @param rate the series being rebuilt; re-opens its RRD file and names
     * the rate in the log line
     * @param lsnr the listener being replaced, or null if the map lost it
     * @param why short reason for the log line
     * @return true if a replacement listener is now recording
     * @since 0.9.71+
     */
    private boolean rebuildListener(Rate rate, GraphListener lsnr, String why) {
        if (_log.shouldWarn()) {
            _log.warn("Rebuilding RRD listener (" + why + ") for " + rateName(rate));
        }
        if (lsnr != null) {
            // stopListening() leaves the registration alone unless this instance still
            // owns it, so this cannot silence a listener we are not replacing.
            lsnr.stopListening();
            _listeners.remove(lsnr);
        }
        // Re-open the existing database file rather than creating a new one,
        // which preserves the recorded history.
        return addDb(rate);
    }

    /**
     * Name a rate for a log line, tolerating a Rate with no stat behind it.
     *
     * @param rate the rate to name, which may be null or have no stat behind it
     * @return the rate's stat name and period
     */
    private static String rateName(Rate rate) {
        if (rate == null) {
            return "?";
        }
        RateStat rs = rate.getRateStat();
        return (rs != null ? rs.getName() : "?") + '.' + rate.getPeriod();
    }

    /**
     * What the watchdog may do about one rate's listener.
     *
     * <p>Ordered by cost, and the order is the whole point: every rung keeps more of
     * the existing recording than the one above it.
     *
     * @since 0.9.71+
     */
    enum HealAction {
        /** Healthy, or nothing worth doing. */
        NONE,
        /**
         * The Rate no longer points at this listener but the listener itself is fine.
         *
         * <p>Never returned: a lost registration is repaired by REBUILD, which also
         * covers a listener that was never attached in the first place. Re-pointing the
         * Rate at a listener another instance may have claimed would make two listeners
         * fight over one registration, so the constant is kept for the ladder's shape
         * and is deliberately not a rung.
         */
        REARM,
        /** The handle is closed but the listener, registration and file all survive. */
        REOPEN,
        /** Coalesces produced steps this listener never stored, and they are still retained. */
        BACKFILL,
        /** Nothing usable is left; build a fresh listener from the same file. */
        REBUILD
    }

    /**
     * Cheapest action that can restore recording for one rate.
     *
     * <p>Pure decision logic, ordered cheapest first, so the cost ordering is pinned by
     * unit tests rather than by reading the ladder at the call site.
     *
     * <p>The first row is also the spec-churn guard: a listener that is mapped, attached
     * and registered can only ever reach BACKFILL or NONE, never REOPEN or REBUILD, so
     * re-reading a changed {@code stat.summaries} string cannot close and reopen a
     * healthy RRD.
     *
     * @param mapped false when the rate has no listener entry
     * @param detached true when the listener's RRD is closed
     * @param registered true when the Rate still points at the listener
     * @param writable true when the handle can still take a write (not closed)
     * @param backfillable true when the rate still retains unstored samples
     * @return the action to take, cheapest first
     * @since 0.9.71+
     */
    static HealAction chooseHeal(boolean mapped, boolean detached, boolean registered,
                                 boolean writable, boolean backfillable) {
        if (!mapped || detached || !registered) {
            return HealAction.REBUILD;
        }
        if (!writable) {
            return HealAction.REOPEN;
        }
        if (backfillable) {
            return HealAction.BACKFILL;
        }
        return HealAction.NONE;
    }

    /**
     * How many retained samples one backfill attempt should ask for.
     *
     * <p>Bounded by the drift, since asking for more steps than are missing wastes a
     * list copy every tick, and capped at {@link #MAX_BACKFILL_STEPS} so a listener
     * that fell thousands of steps behind does not ask for a list that long - the
     * accessor's own clamp would discard the excess anyway, and the excess is exactly
     * the part that is permanently gone.
     *
     * @param drift coalesces minus stored steps
     * @return number of retained samples to request, never below one
     * @since 0.9.71+
     */
    static int backfillWindow(long drift) {
        return (int) Math.min(Math.max(drift, 1L), MAX_BACKFILL_STEPS);
    }

    /**
     * Why coalesces are turning into samples that never reach a listener.
     *
     * @since 0.9.71+
     */
    enum DriftCause {
        /** The counters say the data path is fine; the loss is in the write itself. */
        WRITE_PATH,
        /** Samples are queuing and the oldest has been waiting longer than a tick. */
        DELIVERY_BACKED_UP,
        /** The queue hit its hard cap, so samples were evicted outright. */
        DELIVERY_OVERRUN,
        /** No tracked rate coalesced at all, so nothing is being produced to lose. */
        COALESCING_STOPPED;

        /**
         * Name this mechanism for the operator.
         *
         * @return the wording to put in the report for this mechanism
         */
        String describe() {
            switch (this) {
                case DELIVERY_BACKED_UP:
                    return "Delivery queue backed up (consumer not draining)";
                case DELIVERY_OVERRUN:
                    return "Delivery queue overran its capacity (samples evicted)";
                case COALESCING_STOPPED:
                    return "No tracked rate coalesced (coalesce path stopped)";
                default:
                    return "Write path (delivery drained, step still not stored)";
            }
        }
    }

    /**
     * Name the mechanism behind positive ledger drift.
     *
     * <p>These are the three candidates that were indistinguishable from the console
     * during the incident this exists for: a coalesce that never happened, a sample
     * that queued and never drained, and a sample that arrived and was not written.
     * Each has a different fix, so the report has to pick one.
     *
     * <p>Pure, so the mapping from counters to mechanism is unit tested without a
     * router, a StatManager or a clock.
     *
     * @param pending samples waiting for delivery
     * @param oldestPendingMs age of the oldest waiting sample
     * @param overruns samples evicted at the queue's hard cap
     * @param coalesceAdvancing whether any tracked rate coalesced recently
     * @return the most specific cause the counters support
     * @since 0.9.71+
     */
    static DriftCause classifyDrift(int pending, long oldestPendingMs, long overruns,
                                    boolean coalesceAdvancing) {
        if (overruns > 0) {
            return DriftCause.DELIVERY_OVERRUN;
        }
        if (pending > 0 && oldestPendingMs > HEALTH_INTERVAL_MS) {
            return DriftCause.DELIVERY_BACKED_UP;
        }
        if (!coalesceAdvancing) {
            return DriftCause.COALESCING_STOPPED;
        }
        return DriftCause.WRITE_PATH;
    }

    /**
     * Compose the ledger-drift log line.
     *
     * <p>Pure and single-line, so the wording and the six StatManager counters can be
     * asserted in a unit test. The counters are the point of the line: an operator
     * reading "pending climbing with oldest-pending-age climbing" knows the consumer is
     * wedged, "pending zero with a flat coalesce count" knows the rate stopped
     * coalescing, and a climbing skip count points at the coalesce early return.
     *
     * @param totalListeners listeners being tracked
     * @param drifters number of listeners with positive drift
     * @param maxDrift largest positive drift
     * @param permanentSteps steps proven beyond the retained-sample ring
     * @param healedSteps steps backfilled since the previous report
     * @param rebuilt listeners rebuilt this tick
     * @param reopened listeners reopened this tick
     * @param coalesced coalesce delta across the tracked listeners
     * @param mechanism the named cause from {@link #classifyDrift}
     * @param overruns StatManager sample overruns
     * @param superseded StatManager same-step supersessions
     * @param pending StatManager delivery queue depth
     * @param oldestPendingMs StatManager age of the oldest queued sample
     * @param backlogCollapses StatManager coalesce backlog collapses
     * @param coalesceSkips StatManager coalesce skips
     * @return a single-line message beginning "RRD ledger drift:"
     * @since 0.9.71+
     */
    static String formatLedgerDrift(int totalListeners, int drifters, long maxDrift, long permanentSteps,
                                    long healedSteps, int rebuilt, int reopened, long coalesced,
                                    DriftCause mechanism, long overruns, long superseded, int pending,
                                    long oldestPendingMs, long backlogCollapses, long coalesceSkips) {
        return "RRD ledger drift: " + drifters + "/" + totalListeners
               + " listeners have coalesced steps they never stored (max " + maxDrift
               + ", permanent " + permanentSteps + ", backfilled " + healedSteps
               + ") [mechanism=" + mechanism.describe()
               + ", rebuilt=" + rebuilt + ", reopened=" + reopened + ", coalesced=" + coalesced
               + ", overruns=" + overruns + ", superseded=" + superseded
               + ", pending=" + pending + ", oldest_pending_ms=" + oldestPendingMs
               + ", backlog_collapses=" + backlogCollapses + ", coalesce_skips=" + coalesceSkips + ']';
    }

    /**
     * One watchdog tick: report a stalled data path, then apply the heal ladder.
     *
     * <p>Holds no lock across I/O and does no RRD work itself beyond the backfill the
     * ladder asks for. Wrapped in a Throwable catch because an escaping exception
     * silences a fixed-rate scheduled task permanently, which would remove the very
     * watchdog that is supposed to notice the data path dying.
     */
    private void healthCheck() {
        try {
            if (!_isRunning) {
                return;
            }
            // Same diagnosis the 90s sync task produces, so a stall is named within one
            // watchdog period instead of one sync period.
            reportWriteStaleness();
            applyHealLadder();
        } catch (Throwable t) {
            _log.error("RRD health watchdog failed", t);
        }
    }

    /**
     * Walk every listener, heal what can be healed, and report what could not be.
     *
     * <p>Iterates {@link #_listeners} rather than the rate map so that "mapped" is a
     * real question: a listener that is alive but has lost its map entry is exactly the
     * REBUILD case the ladder exists for, and iterating the map would never see it.
     * The map's own dead entries are handled by {@link #reviveDetachedListeners()}.
     *
     * <p>The ledger reports at WARN, not ERROR, because it describes drift the ladder
     * has usually already repaired, and a single INFO marks its clearing.
     */
    private void applyHealLadder() {
        int drifters = 0, rebuilt = 0, reopened = 0;
        long maxDrift = 0, permanentSteps = 0, healedSteps = 0, coalesced = 0;
        for (GraphListener lsnr : _listeners) {
            Rate rate = lsnr.getRate();
            if (rate == null) {
                continue;
            }
            long drift = lsnr.getCoalesceDrift();
            coalesced += lsnr.getCoalesceDelta();
            switch (chooseHeal(_listenerByRate.containsKey(rate), lsnr.isDetached(),
                               lsnr.isRegistered(), lsnr.isWritable(), drift > 0)) {
                case REBUILD:
                    if (rebuildListener(rate, lsnr, "watchdog")) {
                        rebuilt++;
                    }
                    break;
                case REOPEN:
                    if (lsnr.reopen()) {
                        reopened++;
                    }
                    break;
                case BACKFILL: {
                    List<Rate.CoalescedSample> retained = rate.getRecentSamples(backfillWindow(drift));
                    if (GraphListener.recoverable(lsnr.getLastStoredTimeMs(), retained)) {
                        healedSteps += lsnr.backfill(retained);
                    } else {
                        // Everything the Rate still remembers is already stored, so the
                        // missing steps are older than the ring: charge them once here
                        // rather than asking again on every one of these ticks.
                        lsnr.chargePermanentLoss(lsnr.getCoalesceDelta());
                    }
                    break;
                }
                default:
                    break;
            }
            if (drift > 0) {
                drifters++;
                maxDrift = Math.max(maxDrift, drift);
                // Read after the ladder has run, so a step charged as permanently lost
                // on this very tick is in the figure this tick reports.
                permanentSteps += lsnr.getUnrecoverableSteps();
            }
        }
        if (drifters <= 0) {
            _coalesceStalledTicks = 0;
            _lastCoalesceSum = coalesced;
            if (_driftReported) {
                _driftReported = false;
                if (_log.shouldInfo()) {
                    _log.info("RRD ledger drift: cleared, coalesced and stored step counts agree"
                              + " across " + _listeners.size() + " graph listeners");
                }
            }
            return;
        }
        boolean coalesceAdvancing = noteCoalesceProgress(coalesced);
        long now = System.currentTimeMillis();
        StatManager sm = _context.statManager();
        DriftCause mechanism = classifyDrift(sm.getSamplePending(), sm.getSampleOldestPendingAgeMs(),
                                             sm.getSampleOverruns(), coalesceAdvancing);
        // Keyed on the numbers an operator reads, so a growing backlog still reports and
        // an unchanged one does not re-log every ten seconds forever.
        long signature = drifters * 31L + maxDrift * 7L + permanentSteps;
        if (_driftThrottle.allow(now, signature, REPORT_REPEAT_MS)) {
            _driftReported = true;
            // WARN, not ERROR: this fires on drift the ladder has usually already
            // repaired, and the message carries healedSteps/rebuilt/reopened/coalesced.
            // An ERROR that says "everything was fixed" teaches operators to ignore
            // ERROR, which is how the real stall report above stops being read.
            // Still visible because this class is explicitly configured at DEBUG.
            _log.warn(formatLedgerDrift(_listeners.size(), drifters, maxDrift, permanentSteps,
                                        healedSteps, rebuilt, reopened, coalesced, mechanism,
                                        sm.getSampleOverruns(), sm.getSampleSuperseded(),
                                        sm.getSamplePending(), sm.getSampleOldestPendingAgeMs(),
                                        sm.getCoalesceBacklogCollapses(), sm.getCoalesceSkips()));
        }
    }

    /**
     * Track whether the tracked rates are still coalescing at all.
     *
     * <p>Tested over a window rather than a single tick: a 60s rate coalesces on one
     * tick in six, so comparing against the previous tick alone would report "the rates
     * stopped" five times out of six.
     *
     * <p>Not synchronized, and deliberately so: only the single watchdog thread ever
     * calls it, whereas {@link #stop()} holds this instance's monitor across a bounded
     * scheduler shutdown wait. Synchronizing here would let a shutdown stall the
     * watchdog for the length of that wait.
     *
     * @param coalesced coalesce delta summed across the tracked listeners
     * @return true if some rate coalesced within {@link #COALESCE_STALL_MS}
     */
    private boolean noteCoalesceProgress(long coalesced) {
        if (coalesced > _lastCoalesceSum) {
            _lastCoalesceSum = coalesced;
            _coalesceStalledTicks = 0;
        } else if (_coalesceStalledTicks < COALESCE_STALL_TICKS) {
            _coalesceStalledTicks++;
        }
        return _coalesceStalledTicks < COALESCE_STALL_TICKS;
    }

    /**
     * Decide whether a repeated report is due, and remember the decision.
     *
     * <p>Pure enough to unit test and free of router context, so the "first report is
     * never throttled, a changed one is never delayed, an unchanged one repeats on a
     * timer" rule is pinned by tests rather than by reading the caller.
     *
     * @since 0.9.71+
     */
    static final class ReportThrottle {
        /** One per reported condition, so each carries its own last report and signature. */
        ReportThrottle() {}

        private long _lastMs;
        private long _lastSignature;

        /**
         * Decide whether this report is due, and remember the decision.
         *
         * <p>Synchronized because the stall report is reached from the 90s sync task and
         * the 10s watchdog, which run on different threads.
         *
         * @param now current wall-clock ms
         * @param signature value identifying the state being reported
         * @param minIntervalMs shortest gap between two identical reports
         * @return true if the caller should log now
         */
        synchronized boolean allow(long now, long signature, long minIntervalMs) {
            if (signature != _lastSignature || now - _lastMs >= minIntervalMs) {
                _lastSignature = signature;
                _lastMs = now;
                return true;
            }
            return false;
        }
    }

    /**
     * stop.
     *
     * <p>The watchdog goes first: it is the task that would notice the sync task
     * hanging, so it has to be shut down before the thing it watches, not after.
     */
    public synchronized void stop() {
        _isRunning = false;
        _context.clientAppManager().unregister(this);
        stopHealthWatchdog();
        if (_scheduler != null) {
            _scheduler.shutdown(); // Disable new tasks, let running finish
            try {
                if (!_scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    _scheduler.shutdownNow(); // Force if not terminated in time
                    _scheduler.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                _scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            _scheduler = null;
        }
    }

    /**
     * Whether graph generation is currently switched off.
     *
     * @param ctx the context whose app manager holds the registration
     * @return true if no generator is registered, so nothing is being recorded
     * @since 0.9.38
     */
    public static boolean isDisabled(I2PAppContext ctx) {
        return ctx.clientAppManager().getRegisteredApp(NAME) == null;
    }

    /**
     * Disable graph generation until restart
     * @param ctx the context whose registered generator is stopped
     * @since 0.9.6
     */
    static void setDisabled(I2PAppContext ctx) {
        GraphGenerator ss = instance(ctx);
        if (ss != null) {ss.setDisabled();}
    }

    /**
     * Disable graph generation until restart
     * @since 0.9.38
     */
    synchronized void setDisabled() {
        if (_isRunning) {
            _isRunning = false;
            stop();
        }
    }

    /////// ClientApp methods

    /**
     * Does nothing, we aren't tracked
     * @since 0.9.38
     */
    @Override
    public void startup() {
        // TODO
    }
    /**
     * Does nothing, we aren't tracked
     * @since 0.9.38
     */
    @Override
    public void shutdown(String[] args) {
        // TODO
    }
    /** @since 0.9.38 */
    @Override
    public ClientAppState getState() {return ClientAppState.RUNNING;}

    /** @since 0.9.38 */
    @Override
    public String getName() {return NAME;}

    /** @since 0.9.38 */
    @Override
    public String getDisplayName() {return "I2P+ Graph Generator";}

    /////// End ClientApp methods

    /**
     * List of GraphListener instances
     * @return the listeners
     * @since 0.9.33
     */
    public List<GraphListener> getListeners() { return _listeners; }

    /**
     * The stats graphed when stat.summaries is unset, as statName.period pairs.
     *
     * @since 0.9.33
     */
    public static final String DEFAULT_DATABASES = "bw.sendRate.60000" +
                                                   ",bw.recvRate.60000" +
                                                   ",jobQueue.jobLag.60000" +
                                                   ",router.activePeers.60000" +
                                                   ",router.cpuLoad.60000" +
                                                   ",router.memoryUsed.60000" +
                                                   ",tunnel.participatingTunnels.60000" +
                                                   ",tunnel.tunnelBuildSuccessAvg.60000" +
                                                   ",tunnel.testSuccessTime.60000";

    /**
     * How many stats are being recorded right now.
     *
     * @return the number of live listeners
     * @since 0.9.62+
     */
    public int countGraphs() {return _listeners.size();}

    private String adjustDatabases(String oldSpecs) {
        // Read the property every tick so config changes take effect; the string
        // compare is the common case, parsing only runs after a change
        String spec = _context.getProperty("stat.summaries", DEFAULT_DATABASES);
        if (spec.equals(oldSpecs)) {
            return oldSpecs;
        }

        Set<Rate> old = parseSpecs(oldSpecs);
        Set<Rate> newSpecs = parseSpecs(spec);

        // remove old ones
        for (Rate r : old) {
            if (!newSpecs.contains(r)) {removeDb(r);}
        }
        // add new ones, rebuilding the canonical spec string so the next tick short-circuits
        StringBuilder buf = new StringBuilder();
        boolean comma = false;
        for (Rate r : newSpecs) {
            if (!old.contains(r)) {addDb(r);}
            if (comma) {buf.append(',');}
            else {comma = true;}
            buf.append(r.getRateStat().getName()).append(".").append(r.getPeriod());
        }
        return buf.toString();
    }

    /**
     * Remove a rate from tracking and stop its listener.
     *
     * @param r the rate to remove
     */
    private void removeDb(Rate r) {
        GraphListener lsnr = _listenerByRate.remove(r);
        if (lsnr != null) {
            _listeners.remove(lsnr); // no iter.remove() in COWAL
            lsnr.stopListening();
        }
    }

    /**
     * Start tracking a rate by creating a new GraphListener for it.
     *
     * <p>Single choke point for every listener creation, so it is also where "one live
     * listener per rate" is enforced. Both the 90s sync task and the 10s watchdog can
     * decide to rebuild the same rate within one interval; without this the second
     * rebuild would register a second listener on the rate, orphan the first into the
     * listener list, and leave an attached-but-never-called listener accumulating one
     * per tick - which is precisely the fault the ladder exists to remove.
     *
     * @param r the rate to track
     * @return true if a new listener was created and is recording
     */
    private boolean addDb(Rate r) {
        if (r == null) {
            return false;
        }
        GraphListener existing = _listenerByRate.get(r);
        if (existing != null && !existing.isDetached()) {
            // Someone already rebuilt this rate while this rebuild was in flight.
            return false;
        }
        GraphListener lsnr = new GraphListener(r);
        boolean success = lsnr.startListening();
        if (success) {
            _listeners.add(lsnr);
            _listenerByRate.put(r, lsnr);
        } else {_log.error("Failed to add RRD for rate " + r.getRateStat().getName() + '.' + r.getPeriod());}
        return success;
    }

    /**
     * Render a single-data graph with the specified options.
     * For the two-data bandwidth graph see renderCombinedGraph().
     * Synchronized to conserve memory.
     *
     * @param rate the rate to graph
     * @param out the output stream to write the graph image to
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, draw event markers
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, show the I2P+ credit line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @return success
     * @throws IOException if rendering fails
     * @since 0.9.70+
     */
    public boolean renderGraph(Rate rate, OutputStream out, int width, int height, boolean hideLegend,
                                          boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount,
                                          int end, boolean showCredit, boolean showRestarts) throws IOException {
        return renderGraph(rate, out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                           periodCount, end, showCredit, showRestarts, false);
    }

    /**
     * A single stat's metadata, as JSON.
     *
     * @param rate the rate to graph
     * @param out the output stream to write the metadata to
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, plot the event count rather than the stat
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, keep the signature line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @return true on success, false if the stat is not currently renderable
     * @throws IOException if rendering fails
     * @since 0.9.71+
     */
    public boolean renderGraphMeta(Rate rate, OutputStream out, int width, int height, boolean hideLegend,
                                          boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount,
                                          int end, boolean showCredit, boolean showRestarts)
                                   throws IOException {
        return renderGraph(rate, out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                           periodCount, end, showCredit, showRestarts, true);
    }

    /**
     * As {@link #renderGraph}, but emitting the plot geometry and series as JSON.
     *
     * @param rate the rate to graph
     * @param out the output stream to write the image or the metadata to
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, plot the event count rather than the stat
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, keep the signature line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @param meta true to write the metadata instead of the image
     * @return true on success, false if the stat is not currently renderable
     * @throws IOException if rendering fails
     * @since 0.9.71+
     */
    public boolean renderGraph(Rate rate, OutputStream out, int width, int height, boolean hideLegend,
                                          boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount,
                                          int end, boolean showCredit, boolean showRestarts,
                                          boolean meta) throws IOException {
        try {
            try {_sem.acquire();}
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
            try {
                return locked_renderGraph(rate, out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                         periodCount, end, showCredit, showRestarts, meta);
            } catch (NoClassDefFoundError ncdfe) {
                setDisabled();
                String s = "Error rendering - disabling graph generation.";
                _log.logAlways(Log.WARN, s);
                IOException ioe = new IOException(s);
                ioe.initCause(ncdfe);
                throw ioe;
            } catch (NullPointerException npe) {
                // RRD4J internal NPE — log but don't disable all graphs
                _log.error("RRD4J render error (transient)", npe);
                throw new IOException("Error rendering graph", npe);
            } catch (Error e) {
                // Only disable on classpath/linkage errors, not runtime Errors
                if (e instanceof OutOfMemoryError || e instanceof StackOverflowError) {
                    _log.error("RRD4J render error (transient)", e);
                    throw new IOException("Error rendering graph", e);
                }
                setDisabled();
                String s = "Error rendering - disabling graph generation.";
                _log.logAlways(Log.WARN, s);
                IOException ioe = new IOException(s);
                ioe.initCause(e);
                throw ioe;
            }
        } finally {_sem.release();}
    }

    /**
     * Render a single-data graph under the semaphore lock.
     *
     * @param rate the rate to graph
     * @param out the output stream to write to
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, draw event markers
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, show the I2P+ credit line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @return success
     * @throws IOException if rendering fails
     */
    private boolean locked_renderGraph(Rate rate, OutputStream out, int width, int height, boolean hideLegend,
                                        boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount,
                                        int end, boolean showCredit, boolean showRestarts,
                                        boolean meta) throws IOException {
        if (width > MAX_X) {width = MAX_X;}
        else if (width <= 0) {width = DEFAULT_X;}
        if (height > MAX_Y) {height = MAX_Y;}
        else if (height <= 0) {height = DEFAULT_Y;}
        if (end < 0) {end = 0;}
GraphListener lsnr = _listenerByRate.get(rate);
        if (lsnr != null && !lsnr.isDetached()) {
            // Drawn as a dotted line rather than a filled area, so a single stat looks
            // the same as the same stat plotted beside another. An area under a lone line
            // carries no extra information and made one-stat and two-stat graphs read as
            // different kinds of chart.
            lsnr.renderGraphMetaLines(out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                    periodCount, end, showCredit, Collections.emptyList(), null, showRestarts, meta);
            return true;
        }
        // A detached listener would throw from renderGraph, which propagates out
        // of this method and is reported as a generic render failure. Returning
        // false instead lets the caller show "stat not available", which is the
// accurate description while reviveDetachedListeners() re-attaches it.
        return false;
    }

    /**
     * Export rate data as XML.
     *
     * @param rate the rate to export
     * @param out the output stream to write the XML to
     * @return true if the data was exported successfully
     * @throws IOException if export fails
     */
    public boolean getXML(Rate rate, OutputStream out) throws IOException {
        try {
            try {_sem.acquire();}
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
            return locked_getXML(rate, out);
        } finally {_sem.release();}
    }

    /**
     * Export rate data as XML under the semaphore lock.
     *
     * @param rate the rate to export
     * @param out the output stream to write to
     * @return true if the data was exported successfully
     * @throws IOException if export fails
     */
    private boolean locked_getXML(Rate rate, OutputStream out) throws IOException {
        GraphListener lsnr = _listenerByRate.get(rate);
        if (lsnr != null) {
            lsnr.getData().exportXml(out);
            out.write(DataHelper.getUTF8("<!-- Rate: " + lsnr.getRate().getRateStat().getName() + " for period " + lsnr.getRate().getPeriod() + " -->\n"));
            out.write(DataHelper.getUTF8("<!-- Average data source name: " + lsnr.getName() + " event count data source name: " + lsnr.getEventName() + " -->\n"));
            return true;
        }
        return false;
    }

    /**
     * Render the two-data bandwidth graph with the specified options.
     * For all other graphs see renderGraph() above.
     * Synchronized to conserve memory.
     *
     * @param out the output stream to write the graph image to
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, draw event markers
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, show the I2P+ credit line
     * @return success
     * @throws IOException if rendering fails
     */
    public boolean renderCombinedGraph(OutputStream out, int width, int height, boolean hideLegend,
                                   boolean hideGrid, boolean hideTitle, boolean showEvents,
                                   int periodCount, int end, boolean showCredit) throws IOException {
        return renderCombinedGraph(out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                   periodCount, end, showCredit, true);
    }

    /**
     * Render the two-data bandwidth graph with the specified options.
     *
     * @param out the output stream to write to
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, draw event markers
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, show the I2P+ credit line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @return success
     * @throws IOException if rendering fails
     * @since 0.9.70+
     */
    public boolean renderCombinedGraph(OutputStream out, int width, int height, boolean hideLegend,
                                   boolean hideGrid, boolean hideTitle, boolean showEvents,
                                   int periodCount, int end, boolean showCredit, boolean showRestarts) throws IOException {
        return renderCombinedGraph(out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                  periodCount, end, showCredit, showRestarts, false);
    }

    /**
     * The two-data bandwidth graph's metadata, as JSON.
     *
     * @param out the output stream to write the metadata to
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, plot the event count rather than the stat
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, keep the signature line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @return true on success
     * @throws IOException if rendering fails
     * @since 0.9.71+
     */
    public boolean renderCombinedGraphMeta(OutputStream out, int width, int height, boolean hideLegend,
                                   boolean hideGrid, boolean hideTitle, boolean showEvents,
                                   int periodCount, int end, boolean showCredit, boolean showRestarts)
                                   throws IOException {
        return renderCombinedGraph(out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                  periodCount, end, showCredit, showRestarts, true);
    }

    /**
     * As {@link #renderCombinedGraph}, but emitting the plot geometry and series as JSON.
     *
     * @param out the output stream to write the image or the metadata to
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, plot the event count rather than the stat
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, keep the signature line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @param meta true to write the metadata instead of the image
     * @return true on success
     * @throws IOException if rendering fails
     * @since 0.9.71+
     */
    public boolean renderCombinedGraph(OutputStream out, int width, int height, boolean hideLegend,
                                   boolean hideGrid, boolean hideTitle, boolean showEvents,
                                   int periodCount, int end, boolean showCredit, boolean showRestarts,
                                   boolean meta) throws IOException {
        try {
            try {_sem.acquire();}
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
            try {return locked_renderCombinedGraph(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit, showRestarts, meta);}
            catch (NoClassDefFoundError ncdfe) {
                setDisabled();
                String s = "Error rendering - disabling graph generation.";
                _log.logAlways(Log.WARN, s);
                IOException ioe = new IOException(s);
                ioe.initCause(ncdfe);
                throw ioe;
            } catch (NullPointerException npe) {
                _log.error("RRD4J combined render error (transient)", npe);
                throw new IOException("Error rendering combined graph", npe);
            } catch (Error e) {
                if (e instanceof OutOfMemoryError || e instanceof StackOverflowError) {
                    _log.error("RRD4J combined render error (transient)", e);
                    throw new IOException("Error rendering combined graph", e);
                }
                setDisabled();
                String s = "Error rendering - disabling graph generation.";
                _log.logAlways(Log.WARN, s);
                IOException ioe = new IOException(s);
                ioe.initCause(e);
                throw ioe;
            }
        } finally {_sem.release();}
    }

    /**
     * Render the two-data bandwidth graph under the semaphore lock.
     *
     * @param out the output stream to write to
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, draw event markers
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, show the I2P+ credit line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @return success
     * @throws IOException if rendering fails
     */
    private boolean locked_renderCombinedGraph(OutputStream out, int width, int height, boolean hideLegend,
                                           boolean hideGrid, boolean hideTitle, boolean showEvents,
                                           int periodCount, int end, boolean showCredit, boolean showRestarts,
                                           boolean meta) throws IOException {

        // go to some trouble to see if we have the data for the combined bw graph
        GraphListener txLsnr = null;
        GraphListener rxLsnr = null;
        for (GraphListener lsnr : getListeners()) {
            String title = lsnr.getRate().getRateStat().getName();
            if (title.equals("bw.sendRate")) {txLsnr = lsnr;}
            else if (title.equals("bw.recvRate")) {rxLsnr = lsnr;}
        }
        if (txLsnr == null || rxLsnr == null) {throw new IOException("No rates for combined bandwidth graph");}

        if (width > MAX_X) {width = MAX_X;}
        else if (width <= 0) {width = DEFAULT_X;}
        if (height > MAX_Y) {height = MAX_Y;}
        else if (height <= 0) {height = DEFAULT_Y;}
        // Sent and received are independent quantities - neither contains the other - so
        // both are drawn as lines. A fill between them would read as "the rest of",
        // which is not a relationship these two have. Two lines render dotted, which
        // keeps them separable where they cross, and the colours are the same ones every
        // other multi-series graph uses because this goes down the same path.
        List<GraphListener> rxSeries = Collections.singletonList(rxLsnr);
        if (hideTitle) {
            txLsnr.renderGraphMetaLines(out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                    periodCount, end, showCredit, rxSeries, null, showRestarts, meta);
        } else {
            txLsnr.renderGraphMetaLines(out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                    periodCount, end, showCredit, rxSeries,
                                    "[" + _t("Router") + "] " + _t("Bandwidth usage").replace("usage", "Usage"), showRestarts, meta);
        }
        return true;
    }

    /**
     * Resolve a stat.summaries string to the Rates it names.
     *
     * <p>A token that is not statName.period, names an unknown stat or carries a
     * non-numeric period is skipped rather than failing the sync tick.
     *
     * @param specs statName.period,statName.period,statName.period
     * @return list of Rate objects
     * @since 0.9.33
     */
    public Set<Rate> parseSpecs(String specs) {
        if (specs == null) {return Collections.emptySet();}
        StringTokenizer tok = new StringTokenizer(specs, ",");
        Set<Rate> rv = new HashSet<>();
        while (tok.hasMoreTokens()) {
            String spec = tok.nextToken();
            int split = spec.lastIndexOf('.');
            if ((split <= 0) || (split + 1 >= spec.length())) {continue;}
            String name = spec.substring(0, split);
            String per = spec.substring(split+1);
            long period = -1;
            try {
                period = Long.parseLong(per);
                RateStat rs = _context.statManager().getRate(name);
                if (rs != null) {
                    Rate r = rs.getRate(period);
                    if (r != null) {rv.add(r);}
                }
            } catch (NumberFormatException nfe) { /* ignored */ }
        }
        return rv;
    }

    /**
     * Collect the listeners for the enabled members of a graph group.
     *
     * <p>Members are returned in the group's legend order, not in listener order, so the
     * colours and legend entries stay put as stats are enabled and disabled. Members whose
     * RRD has not been created yet are skipped: a stat that has never been sampled has no
     * series to draw, and including it would silently stretch the axis to zero.
     *
     * @param groupId a group id from {@link GraphGroups}
     * @param enabledStats stat names enabled by the user, without period suffixes
     * @return the members that have data, in legend order; empty when fewer than two
     * @since 0.9.71+
     */
    public List<GraphListener> getGroupListeners(String groupId, Set<String> enabledStats) {
        List<GraphListener> found = new ArrayList<>(GraphGroups.MAX_SERIES);
        Map<String, GraphListener> byName = new HashMap<>();
        for (GraphListener lsnr : getListeners()) {
            byName.put(lsnr.getRate().getRateStat().getName(), lsnr);
        }
        for (String stat : GraphGroups.enabledMembers(groupId, enabledStats)) {
            GraphListener lsnr = byName.get(stat);
            if (lsnr != null) {
                found.add(lsnr);
            }
        }
        return found;
    }

    /**
     * Render a combined graph for one group.
     *
     * @param out the output stream to write the graph image to
     * @param groupId a group id from {@link GraphGroups}
     * @param enabledStats stat names enabled by the user, without period suffixes
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, plot the event count rather than the stat
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, keep the signature line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @return true if a graph was written; false when the group had too few usable members
     * @throws IOException if rendering fails
     * @since 0.9.71+
     */
    public boolean renderGroupedGraph(OutputStream out, String groupId, Set<String> enabledStats,
                                      int width, int height, boolean hideLegend, boolean hideGrid,
                                      boolean hideTitle, boolean showEvents, int periodCount,
                                      int end, boolean showCredit, boolean showRestarts)
                                      throws IOException {
        renderGroupedGraph(out, groupId, enabledStats, width, height, hideLegend, hideGrid, hideTitle,
                           showEvents, periodCount, end, showCredit, showRestarts, false);
        return true;
    }

    /**
     * A grouped graph's metadata, as JSON.
     *
     * @param out the output stream to write the metadata to
     * @param groupId a group id from {@link GraphGroups}
     * @param enabledStats stat names enabled by the user, without period suffixes
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, plot the event count rather than the stat
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, keep the signature line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @return true on success
     * @throws IOException if rendering fails
     * @since 0.9.71+
     */
    public boolean renderGroupedGraphMeta(OutputStream out, String groupId, Set<String> enabledStats,
                                      int width, int height, boolean hideLegend, boolean hideGrid,
                                      boolean hideTitle, boolean showEvents, int periodCount,
                                      int end, boolean showCredit, boolean showRestarts)
                                      throws IOException {
        renderGroupedGraph(out, groupId, enabledStats, width, height, hideLegend, hideGrid, hideTitle,
                           showEvents, periodCount, end, showCredit, showRestarts, true);
        return true;
    }

    /**
     * As {@link #renderGroupedGraph}, but emitting the plot geometry and series as JSON.
     *
     * @param out the output stream to write the image or the metadata to
     * @param groupId a group id from {@link GraphGroups}
     * @param enabledStats stat names enabled by the user, without period suffixes
     * @param width image width in pixels
     * @param height image height in pixels
     * @param hideLegend if true, omit the legend
     * @param hideGrid if true, omit the grid lines
     * @param hideTitle if true, omit the title
     * @param showEvents if true, plot the event count rather than the stat
     * @param periodCount number of time periods to display, or -1 for default
     * @param end number of periods before now to end at
     * @param showCredit if true, keep the signature line
     * @param showRestarts if true, draw the vertical restart lines and &quot;Router restarted&quot; label
     * @param meta true to write the metadata instead of the image
     * @return true on success
     * @throws IOException if rendering fails
     * @since 0.9.71+
     */
    public boolean renderGroupedGraph(OutputStream out, String groupId, Set<String> enabledStats,
                                      int width, int height, boolean hideLegend, boolean hideGrid,
                                      boolean hideTitle, boolean showEvents, int periodCount,
                                      int end, boolean showCredit, boolean showRestarts, boolean meta)
                                      throws IOException {
        List<GraphListener> members = getGroupListeners(groupId, enabledStats);
        if (members.size() < 2) {
            return false;
        }
        GraphListener primary = members.remove(0);
        // The group's own name, not the primary stat's description: a combined graph is
        // titled by what its members have in common, and a stat description reads as
        // "Number of <one member>", which is both wrong for a group and not the name the
        // graphs page lists it under.
        String title = GraphGroups.displayPrefixOf(groupId) + _t(GraphGroups.titleOf(groupId));
        // Every series is a dotted line, including a single-stat graph and a single-member
        // group. A filled area under a lone line carries no extra information, and mixing
        // filled and line rendering made one-stat and multi-stat graphs read as different
        // kinds of chart rather than as the same chart at different sizes.
        primary.renderGraphMetaLines(out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                periodCount, end, showCredit, members, title, showRestarts, meta);
        return true;
    }

    /**
     * Delete the old rrd dir if we are no longer persistent
     * @since 0.8.7
     */
    private void deleteOldRRDs() {
        File rrdDir = new File(_context.getRouterDir(), GraphListener.RRD_DIR);
        FileUtil.rmdir(rrdDir, false);
    }

    private static final boolean IS_WIN = SystemVersion.isWindows();

    /**
     * Translate a string for display on graphs.
     * Falls back to the original string for CJK on Windows where fonts may lack glyphs.
     *
     * @param s the string to translate
     * @return the translated string, or the original if translation is unavailable
     */
    private String _t(String s) {
        // The RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context))) {return s;}
        return Messages.getString(s, _context);
    }

    /**
     * Make sure any persistent RRDs are closed
     * @since 0.8.7
     */
    private class Shutdown implements Runnable {
        /**
         * Close all persistent RRDs and clean up.
         *
         * <p>Ordered: watchdog, sync task, listeners, backend factory. The factory is
         * last because closing it while a listener still holds a handle leaves that
         * handle half-closed, and it is only closed at all by the instance that
         * configured it.
         */
        @Override
        public void run() {
            // setDisabled() clears the running flag and stops both schedulers, watchdog
            // first, so no tick can be in flight while the listeners are being closed.
            setDisabled();
            for (GraphListener lsnr : _listeners) {lsnr.stopListening();} // FIXME could cause exceptions if rendering?
            _listeners.clear();
            // The map has to go too, not just the list. A sync tick still in flight walks
            // _listenerByRate, and a stale mapping would make it re-open the RRDs that were
            // just closed - after the backend factory is closed below - leaving half-closed
            // backends behind. A mapping that outlived the shutdown also serves a detached
            // listener to getXML(), which dereferences its closed database.
            _listenerByRate.clear();
            stop();
            closeBackendFactory();
        }
    }

    /**
     * Close the shared RRD backend factory, but only if this instance configured it.
     *
     * <p>{@code RrdBackendFactory.getDefaultFactory()} is a singleton, so a second
     * {@link GraphGenerator} shutting down after the one that configured it would
     * otherwise stop the flush pool out from under every listener still recording.
     *
     * @since 0.9.71+
     */
    private void closeBackendFactory() {
        if (!_ownsBackendFactory) {
            if (_log.shouldInfo()) {
                _log.info("Skipping RRD backend factory close: this GraphGenerator instance "
                          + Integer.toHexString(System.identityHashCode(this))
                          + " did not configure it");
            }
            return;
        }
        // Cleared first so a second shutdown cannot close it again.
        _ownsBackendFactory = false;
        // Stops the sync thread pool in NIO; noop if not persistent, we set num threads to zero in run() above
        try {RrdBackendFactory.getDefaultFactory().close();}
        catch (IOException ioe) { /* ignored */ }
    }

}
