package net.i2p.util;

import java.util.concurrent.Executors;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 * Scheduled event executor backed by {@link ScheduledThreadPoolExecutor}.
 * Replaces the legacy SimpleTimer (deleted; had lock contention issues).
 * Supports cancel and reschedule. Events must NOT block.
 * <p>
 * All timer events should extend TimedEvent and use {@link #schedule}/cancel() directly.
 * <p>
 * A saturation watchdog samples this pool from a thread of its own and warns
 * when every worker has stayed occupied with a backlog behind it: a
 * ScheduledThreadPoolExecutor never grows past {@link #DEFAULT_THREADS}, so two
 * blocked events starve every periodic event on this timer without throwing
 * anything or writing anything to the log.
 *
 */
public class SimpleTimer2 {

    /**
     * If you have a context, use context.simpleTimer2() instead.
     *
     * @return the global SimpleTimer2 instance
     */
    public static SimpleTimer2 getInstance() {
        return I2PAppContext.getGlobalContext().simpleTimer2();
    }

    /**
     * Default worker count. Two was the original value and is enough only while
     * the timer's total event volume stays below what two threads can service: the
     * pool never grows past this figure, so a persistent backlog shows up as
     * execution latency rather than as a rejected task or a warning anywhere else.
     * <p>A live router measured a 16s median / 30s p90 execution delay across many
     * event types - ACKTimer, KillTimer, BodyWatchdog, DelayedCloser, Cleaner,
     * RemoveExpired - which is the signature of a volume-saturated pool rather than
     * of the two-blocked-events wedge the saturation watchdog exists to catch. That
     * watchdog samples occupancy and queue depth at an instant, so it reports a wedge
     * but is blind to a backlog whose events merely run back-to-back.
     *
     * @since 0.9.71+
     */
    static final int DEFAULT_THREADS = 4;

    /**
     * How many workers this timer runs. Configurable because the right value is a
     * property of total event volume, which varies with the router's tunnels,
     * peers and services, and is not knowable where the class is written.
     *
     * @since 0.9.71+
     */
    private int _threads;

    /**
     * Resolve the worker count for a context. Pure decision helper for unit tests.
     *
     * @param configured a requested worker count, non-positive meaning unset
     * @return the worker count, never below the original two
     * @since 0.9.71+
     */
    static int resolveThreads(int configured) {
        if (configured <= 0) { return DEFAULT_THREADS; }
        // Two is the floor the class was built around: below it a single blocked
        // event starves everything, which is strictly worse than a backlog.
        return Math.max(2, configured);
    }

    /**
     * How many workers to run for a given backlog. Pure decision helper for unit
     * tests.
     *
     * <p>The trigger and the release are deliberately far apart. A timer that grows
     * at 2s of backlog and shrinks at 1.9s would oscillate between two thread counts
     * for as long as the load sat near the threshold, and every step reallocates
     * threads. Shrinking only on a genuinely clean reading costs a little recovery
     * time and removes the oscillation entirely.
     *
     * @param backlogMs the worst recent execution delay in ms, 0 when nothing is late
     * @param current the worker count now in effect
     * @param max the ceiling; non-positive falls back to {@link #MAX_THREADS} rather
     *            than being read as "no ceiling", so a caller passing a bad value
     *            cannot disable the bound
     * @param growMs backlog at or above which a worker is added
     * @param shrinkMs backlog at or below which a worker is released
     * @return the worker count to move to, never below {@link #MIN_THREADS} and
     *         never above the ceiling in force
     * @since 0.9.71+
     */
    public static int desiredThreads(int backlogMs, int current, int max, int growMs, int shrinkMs) {
        int ceiling = max > 0 ? max : MAX_THREADS;
        int target = (backlogMs >= growMs && backlogMs > 0)
            ? current + 1
            : (backlogMs <= shrinkMs ? current - 1 : current);
        if (target < MIN_THREADS) {target = MIN_THREADS;}
        if (target > ceiling) {target = ceiling;}
        return target;
    }

    /**
     * The floor on worker count. Below two, one blocked event starves every periodic
     * event on this timer, which is a worse failure than any backlog.
     *
     * @since 0.9.71+
     */
    private static final int MIN_THREADS = 2;

    /**
     * Ceiling on automatic worker growth. The timer is shared by everything, so an
     * unbounded count would let one busy subsystem take the process with it. This
     * leaves ample headroom over the observed need without letting a runaway grow
     * without limit.
     *
     * @since 0.9.71+
     */
    private static final int MAX_THREADS = 16;

    /**
     * How often the saturation watchdog samples this pool. Long enough to be
     * free (four counter reads), short enough that a wedge is reported within
     * a couple of minutes instead of never.
     *
     * @since 0.9.71+
     */
    static final int WATCHDOG_INTERVAL_MS = 30 * 1000;
    /**
     * Consecutive saturated samples tolerated before a WARN. The pool is
     * briefly busy all the time; a pool that is still fully occupied with a
     * backlog after this many samples is wedged.
     *
     * @since 0.9.71+
     */
    static final int WATCHDOG_SATURATION_SAMPLES = 3;
    /**
     * Queue depth that turns full occupancy into a backlog rather than a
     * coincidence. With {@link #DEFAULT_THREADS} workers, this means at least this
     * many events are waiting behind running ones. Not zero: the queue also
     * holds delayed events that are not due yet, so depth alone proves nothing.
     *
     * @since 0.9.71+
     */
    static final int WATCHDOG_MIN_QUEUE = 3;
    /**
     * Consecutive samples with no completed task before a WARN. Longer than
     * {@link #WATCHDOG_SATURATION_SAMPLES} so the broader, less specific signal
     * follows the narrow one rather than pre-empting it: a wedged pool reports
     * saturation first, and only reaches "nothing is running at all" if it stays
     * wedged twice as long.
     *
     * @since 0.9.71+
     */
    static final int WATCHDOG_STALL_SAMPLES = 6;

    /**
     * Consecutive samples that must show progress before a quiet episode is called
     * over.
     * <p>Without it a single completing task ends the episode, so a timer that finishes
     * something every few minutes reports the same condition on every threshold window
     * instead of once.
     *
     * @since 0.9.71+
     */
    static final int WATCHDOG_RECOVERY_SAMPLES = 3;

    /** The pool running the scheduled events. */
    private final ScheduledThreadPoolExecutor _executor;

    /**
     * Recent execution delays, newest overwriting oldest. The control signal for
     * worker count is how far behind the timer is running, not how busy it is at an
     * instant: an executor whose events merely run back-to-back looks idle between
     * them while every event is late. That is the failure mode a fixed worker count
     * cannot fix and an instantaneous check cannot see.
     *
     * @since 0.9.71+
     */
    private final long[] _recentDelayMs = new long[DELAY_SAMPLES];

    /** Next slot in {@link #_recentDelayMs}. */
    private int _delayIdx;

    /**
     * Record one execution delay for the backlog reading.
     *
     * @param delayMs how late the event ran in ms; non-positive is not recorded
     * @since 0.9.71+
     */
    private void recordDelay(long delayMs) {
        if (delayMs <= 0) {return;}
        _recentDelayMs[_delayIdx++ % DELAY_SAMPLES] = delayMs;
    }

    /**
     * The worst recent execution delay. This is the control signal for the worker
     * count: an executor whose events run back-to-back looks idle between them, so
     * occupancy and queue depth at an instant prove nothing, while the delay is what
     * the router actually experiences.
     *
     * @return the worst recorded delay in ms, or 0 when nothing has been late
     * @since 0.9.71+
     */
    public long getBacklogMs() {
        long worst = 0;
        for (int i = 0; i < DELAY_SAMPLES; i++) {
            if (_recentDelayMs[i] > worst) {worst = _recentDelayMs[i];}
        }
        return worst;
    }

    /**
     * Move the worker count. Grows and shrinks: shrinking relies on the pool being
     * allowed to reap idle workers, which a scheduled executor does not do by
     * default.
     *
     * @param threads the worker count, below the floor is raised to it
     * @since 0.9.71+
     */
    public synchronized void setThreadCount(int threads) {
        int target = Math.max(MIN_THREADS, threads);
        if (target > MAX_THREADS) {target = MAX_THREADS;}
        if (target == _threads) {return;}
        _threads = target;
        _executor.setCorePoolSize(target);
        // Without this the pool never gives a thread back, so growth would be
        // one-way and a busy spell would be permanent.
        _executor.allowCoreThreadTimeOut(true);
        _executor.setKeepAliveTime(60, TimeUnit.SECONDS);
    }

    /** The worker count now in effect.
     * @return the current worker count */
    public int getThreadCount() {return _threads;}


    /**
     * How many execution delays are kept for the backlog reading.
     *
     * @since 0.9.71+
     */
    private static final int DELAY_SAMPLES = 32;
    /** Name prefix for the timer threads. */
    private final String _name;
    /** Counter numbering the timer threads. */
    private final AtomicInteger _count = new AtomicInteger();
    /** The app context, for the clock and stats. */
    private final I2PAppContext _context;
    /** Hook stopping the timer on JVM exit. */
    private final Runnable _onShutdown = () -> stop(false);
    /** Events completed since startup. */
    private final AtomicLong _completed = new AtomicLong();
    /** Watchdog watching for a saturated timer. */
    private final SaturationWatchdog _watchdog;

    /**
     * Schedules requested with a zero or negative delay since the last watchdog
     * report. Counted rather than logged per event: an immediate reschedule is
     * legitimate occasionally, but a connection whose head-of-line packet is
     * already older than one RTO asks for one on <em>every</em> ACK, and a
     * population of those will bury a two-thread pool. The count is what tells the
     * saturation report the queue is being flooded by immediate reschedules rather
     * than by ordinary periodic work.
     * <p>Incremented only on the zero-delay path, so the steady-state cost is nil.
     *
     * @since 0.9.71+
     */
    private final AtomicLong _immediateReschedules = new AtomicLong();

    /**
     * To be instantiated by the context.
     * Others should use context.simpleTimer2() instead.
     *
     * @param context the I2P application context
     */
    public SimpleTimer2(I2PAppContext context) {
        this(context, "SimpleTimer");
    }

    /**
     * To be instantiated by the context.
     * Others should use context.simpleTimer2() instead, except for
     * dedicated timers that need a distinct thread name.
     *
     * @param context the I2P application context
     * @param name the timer name, used for the timer thread name
     * @since 0.9.70+ public
     */
    public SimpleTimer2(I2PAppContext context, String name) {
        this(context, name, true);
    }

    /**
     * To be instantiated by the context.
     * Others should use context.simpleTimer2() instead.
     *
     * @param context the I2P application context
     * @param name the timer name
     * @param prestartAllThreads whether to prestart all threads
     */
    protected SimpleTimer2(I2PAppContext context, String name, boolean prestartAllThreads) {
        _context = context;
        _name = name;
        // Deliberately no property read here. The tuning console persists this
        // param to autotune.config, which lives in the router module and cannot be
        // read from core; reading router.config instead would both bypass that
        // store and install a value the console never wrote. Automatic sizing starts
        // here and is driven by the measured backlog alone.
        _threads = DEFAULT_THREADS;
        _executor = new CustomScheduledThreadPoolExecutor(_threads, new CustomThreadFactory());
        if (prestartAllThreads)
            _executor.prestartAllCoreThreads();
        context.addShutdownTask(_onShutdown);
        _watchdog = new SaturationWatchdog();
    }

    /**
     * Stops the timer.
     * Subsequent executions will not throw RejectedExecutionException.
     * Cannot be restarted.
     */
    public void stop() {
        stop(true);
    }

    /**
     * Stops the timer.
     * Subsequent executions will not throw RejectedExecutionException.
     * Cannot be restarted.
     *
     * @param removeTask true to unregister the shutdown hook
     * @since 0.9.53
     */
    private void stop(boolean removeTask) {
        if (removeTask)
            _context.removeShutdownTask(_onShutdown);
        // before the pool dies, so the watchdog cannot sample a torn-down executor
        _watchdog.shutdown();
        _executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        _executor.shutdownNow();
    }

    /** Executor that rejects work after shutdown. */
    private static class CustomScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
        /**
         * Builds the timer's executor.
         *
         * @param threads the pool size
         * @param factory the thread factory
         */
        public CustomScheduledThreadPoolExecutor(int threads, ThreadFactory factory) {
            super(threads, factory);
            setRemoveOnCancelPolicy(true);
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t != null) { // shouldn't happen, caught in TimedEvent.run()
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(SimpleTimer2.class);
                log.log(Log.CRIT, "Uncaught: " + r, t);
            }
        }
    }

    /** Factory naming the timer threads. */
    private class CustomThreadFactory implements ThreadFactory {
        /** Names the timer's event threads. */
        private CustomThreadFactory() {}
        @Override
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName(_name + '.' + _count.incrementAndGet());
            rv.setDaemon(true);
            rv.setPriority(Thread.MAX_PRIORITY - 1);
            return rv;
        }
    }

    /**
     * What a single saturation-watchdog sample means. Package-visible so that
     * SimpleTimer2WatchdogDecisionTest can pin the throttle edge without
     * starting a thread or an executor.
     *
     * @since 0.9.71+
     */
    enum WatchdogDecision {
        /** No signal in this sample, so any episode in progress is over. */
        OK,
        /** Signal present, but not yet actionable or already reported this episode. */
        PENDING,
        /** The single sample per episode that is allowed to log. */
        REPORT
    }

    /**
     * Decide what one saturation sample means.
     * <p>The pool counts as saturated only when both halves hold: every worker
     * is occupied ({@code activeCount >= poolSize}) <i>and</i> at least
     * {@link #WATCHDOG_MIN_QUEUE} events are waiting behind them. Either half
     * alone is noise - the queue also holds delayed events that are not due yet,
     * and both workers being busy is ordinary whenever two events happen to run
     * at once.
     * <p>{@code consecutiveSaturatedSamples} counts the samples <i>before</i>
     * this one, so a threshold of N reports on the (N+1)th consecutive
     * saturated sample: "more than N samples", not "N". {@code episodeReported}
     * is the throttle, which is what turns a permanently-wedged pool into one
     * WARN instead of one every {@link #WATCHDOG_INTERVAL_MS}.
     *
     * @param activeCount executor active thread count
     * @param poolSize executor pool size, zero once it is shut down
     * @param queueSize executor queue depth
     * @param consecutiveSaturatedSamples immediately preceding saturated samples
     * @param threshold consecutive saturated samples tolerated before reporting
     * @param episodeReported whether this episode has already been reported
     * @return REPORT once per episode, OK when not saturated (ending the
     *         episode), PENDING otherwise
     * @since 0.9.71+
     */
    static WatchdogDecision evaluateSaturation(int activeCount, int poolSize, int queueSize,
                                                int consecutiveSaturatedSamples, int threshold,
                                                boolean episodeReported) {
        if (poolSize <= 0 || activeCount < poolSize || queueSize < WATCHDOG_MIN_QUEUE)
            return WatchdogDecision.OK;
        if (consecutiveSaturatedSamples < threshold || episodeReported)
            return WatchdogDecision.PENDING;
        return WatchdogDecision.REPORT;
    }

    /**
     * Decide what one "nothing finished" sample means.
     * <p>This is the cheap form of "a periodic event stopped firing": the
     * executor's own completed-task counter not advancing across samples means
     * nothing this timer runs is finishing, regardless of what the pool size
     * says. It deliberately does not track individual events - that would mean
     * bookkeeping on every scheduling and every run.
     * <p>An executor that has never completed a task is treated as idle, not
     * wedged: a timer with nothing scheduled on it is not a failure, and
     * warning about one would bury the real reports. A wedge that starts before
     * the first completion is still caught by {@link #evaluateSaturation}.
     * <p>The quiet period is calibrated against the timer's own worst observed gap
     * rather than its mean interval. A mean is the wrong statistic here: a
     * coalescing timer runs in dense bursts, so its mean interval is dominated by
     * the busy stretches while the real gaps between bursts run far longer.
     * Scaling the bar by a multiple of the mean therefore trips on healthy bursty
     * timers, which trains the reader to ignore the one report that matters. The
     * longest gap a timer has actually come back from is self-calibrating, and it
     * reports only a quiet period that exceeds what that timer has done before.
     *
     * @param completed executor completed task count, now
     * @param lastCompleted executor completed task count, at the previous sample
     * @param consecutiveStalledSamples immediately preceding samples with no progress
     * @param threshold such samples tolerated before reporting
     * @param episodeReported whether this episode has already been reported
     * @param maxObservedGapMs longest quiet period this timer has previously come
     *                         back from; zero when no baseline has been established
     * @return REPORT once per episode, OK on progress or an idle timer (ending
     *         the episode), PENDING otherwise
     * @since 0.9.71+
     */
    static WatchdogDecision evaluateStall(long completed, long lastCompleted,
                                          int consecutiveStalledSamples, int threshold,
                                          boolean episodeReported, long maxObservedGapMs) {
        return evaluateStall(completed, lastCompleted, consecutiveStalledSamples, threshold,
                             episodeReported, maxObservedGapMs, true);
    }

    /**
     * Decide what one "nothing finished" sample means, given whether work was actually due.
     * <p>Added parameter {@code workDue}: when nothing is due there is nothing to be
     * late for, so the sample is {@link WatchdogDecision#OK} regardless of history. The
     * calibration below then only judges timers that genuinely had work and did not run
     * it, which is the case worth a report.
     *
     * @param completed executor completed task count, now
     * @param lastCompleted executor completed task count, at the previous sample
     * @param consecutiveStalledSamples immediately preceding samples with no progress
     * @param threshold such samples tolerated before reporting
     * @param episodeReported whether this episode has already been reported
     * @param maxObservedGapMs longest quiet period this timer has come back from; zero if unmeasured
     * @param workDue true when the timer had a task whose delay had elapsed
     * @return OK when work was not due or progress was made, PENDING below the threshold, REPORT once per episode
     * @see #evaluateStall(long, long, int, int, boolean, long)
     */
    static WatchdogDecision evaluateStall(long completed, long lastCompleted,
                                          int consecutiveStalledSamples, int threshold,
                                          boolean episodeReported, long maxObservedGapMs,
                                          boolean workDue) {
        if (!workDue || completed > lastCompleted || completed <= 0)
            return WatchdogDecision.OK;
        if (consecutiveStalledSamples < threshold || episodeReported)
            return WatchdogDecision.PENDING;
        long quietMs = (long) consecutiveStalledSamples * WATCHDOG_INTERVAL_MS;
        long floorMs = (long) threshold * WATCHDOG_INTERVAL_MS;
        if (quietMs < floorMs)
            return WatchdogDecision.PENDING;
        // With no baseline there is nothing to judge against, and a quiet timer is
        // indistinguishable from an idle one. Staying silent is the only safe answer.
        if (maxObservedGapMs <= 0)
            return WatchdogDecision.PENDING;
        // Past this timer's own history. Twice the longest gap it has recovered from
        // still counts as normal, which keeps ordinary jitter out while still
        // reporting a timer that never comes back.
        if (quietMs <= 2 * maxObservedGapMs)
            return WatchdogDecision.PENDING;
        return WatchdogDecision.REPORT;
    }

    /**
     * Lower bound on how long a run of {@code samples} consecutive watchdog
     * samples of the same thing spans: consecutive samples are at least one
     * interval apart, so N samples cover at least N-1 intervals. The bound is
     * deliberately low, since a sample can be late.
     *
     * @param samples number of consecutive samples of one episode
     * @return conservative duration in ms, zero for fewer than two samples
     * @since 0.9.71+
     */
    static long episodeForMs(int samples) {
        return samples > 1 ? ((long) (samples - 1) * WATCHDOG_INTERVAL_MS) : 0;
    }

    /**
     * Thread factory for the saturation watchdog. Mirrors
     * {@link CustomThreadFactory}'s daemon flag and priority: the watchdog is
     * scheduled just as reliably as the pool threads it watches, because its
     * entire job is to report a pool that cannot report itself. The name is
     * taken whole instead of generated so a thread dump shows the watchdog
     * apart from the pool threads it monitors.
     */
    private static class WatchdogThreadFactory implements ThreadFactory {
        /** Name prefix for the watchdog threads. */
        private final String _name;

        /**
         * Builds the watchdog's thread factory.
         *
         * @param name the name prefix for the threads
         */
        WatchdogThreadFactory(String name) {
            _name = name;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName(_name);
            rv.setDaemon(true);
            rv.setPriority(Thread.MAX_PRIORITY - 1);
            return rv;
        }
    }

    /**
     * Samples this timer on its own daemon thread and reports a pool that has
     * stopped keeping up.
     * <p>It cannot run on the pool it watches: a wedged pool has no spare
     * worker to run the watchdog on, which is the whole failure. It also does
     * nothing but read counters, so no event running on this timer can delay
     * it. Every sample is four counter reads and a few field writes - no
     * allocation, no synchronization beyond the monitor it sleeps on - so it is
     * free to leave running for the life of the router.
     * <p>Everything below is touched only by the watchdog thread, except
     * {@code _stopped}, which is guarded by the watchdog monitor.
     */
    private class SaturationWatchdog {

        /** Samples seen with the queue saturated. */
        private int _saturatedSamples;
        /** True once the saturation was logged. */
        private boolean _saturationReported;
        /** Completed count seen at the last saturated sample. */
        private long _saturatedCompleted;
        /** Samples seen with no completions. */
        private int _stalledSamples;
        /** True once the stall was logged. */
        private boolean _stallReported;
        /** Consecutive samples that completed a task; recovery needs this many. */
        private int _progressSamples;
        /** Whether any task was due during the current quiet episode. */
        private boolean _workDueThisEpisode;
        /** Completion count when the current quiet episode began. */
        private long _stallStartCompleted;
        /** Completed count at the previous sample. */
        private long _lastCompleted;
        /** Wall clock at which we last saw the completion count rise. */
        private long _lastProgressAt;
        /**
         * Longest quiet period this timer has previously come back from.
         * Grows only while the watchdog observes recovery, so it is a floor
         * learned from the timer's own behaviour rather than a guess.
         */
        private long _maxObservedGapMs;
        /** Guarded by this. */
        private boolean _stopped;

        /** Builds the saturation watchdog. */
        SaturationWatchdog() {
            _lastProgressAt = System.currentTimeMillis();
            start();
        }

        /**
         * Start the sampling thread, warning if the JVM will not run it - a
         * watchdog that silently failed to start is the same invisible failure
         * this class exists to prevent. No reference to the thread is kept: it
         * runs until {@link #shutdown}, and being a daemon it cannot hold up
         * JVM exit either way.
         */
        private void start() {
            try {
                new WatchdogThreadFactory(_name + ".WD").newThread(this::run).start();
            } catch (RuntimeException | OutOfMemoryError e) {
                // SecurityManager, thread exhaustion, OOME on stack reservation
                log().warn("Saturation watchdog could not start, timer saturation will " +
                           "go unreported: " + e);
            }
        }

        /**
         * Stop sampling. Idempotent, cannot throw, and safe when
         * {@link #start} failed. The thread is a daemon, so it cannot hold up
         * JVM exit even if this is never called.
         */
        void shutdown() {
            synchronized (this) {
                _stopped = true;
                notifyAll();
            }
        }

        /**
         * Sample every {@link #WATCHDOG_INTERVAL_MS} until {@link #shutdown}.
         * <p>The stopped test and the wait share the monitor, so shutdown()
         * cannot land between them and leave this thread parked for the rest
         * of the interval - hence no interrupt is needed.
         */
        private void run() {
            while (true) {
                synchronized (this) {
                    if (_stopped)
                        return;
                    try {
                        wait(WATCHDOG_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        return;  // nothing interrupts this thread, but honor it anyway
                    }
                    if (_stopped)
                        return;
                }
                try {
                    sample();
                } catch (RuntimeException re) {
                    // a broken sample must not cost us every later sample too
                    log().error("Watchdog sample failed", re);
                }
            }
        }

        /**
         * Take one sample and act on it. All the executor counters are safe to
         * read after shutdown, so a sample racing {@link #stop()} degrades to reporting
         * nothing rather than throwing.
         */
        private void sample() {
            int active = _executor.getActiveCount();
            int pool = _executor.getPoolSize();
            int queued = _executor.getQueue().size();
            // Cancelled events are removed as they are cancelled
            // (setRemoveOnCancelPolicy), so the depth needs no purge() to be
            // trustworthy - and purge() is O(queue).
            long completed = _executor.getCompletedTaskCount();
            boolean workDue = isWorkDue();
            checkSaturation(active, pool, queued, completed);
            checkStall(completed, workDue);
        }

        /**
         * Is a task actually due on this timer right now?
         * <p>A pool that has completed nothing may be idle or wedged, and completion
         * counts cannot tell them apart - only this can. It is a property of the work
         * offered, not of the timer's history, so unlike a calibrated gap threshold it
         * cannot be tuned into uselessness by a timer that has always been quiet: a
         * streaming retransmission shard that simply has nothing to retransmit has no
         * due task and is correctly silent, while a shard with a due task and no
         * progress is the thing worth reporting.
         *
         * @return true if the queue holds a task whose delay has already elapsed
         */
        private boolean isWorkDue() {
            Runnable head = _executor.getQueue().peek();
            if (head == null) {
                return false;
            }
            if (head instanceof RunnableScheduledFuture) {
                return ((RunnableScheduledFuture<?>) head).getDelay(TimeUnit.NANOSECONDS) <= 0;
            }
            // Unrecognised queue element: assume work exists rather than sit on a real stall.
            return true;
        }

        /**
         * Warn once per episode of a fully occupied pool with a backlog.
         *
         * @param active the number of currently active threads
         * @param pool the configured pool size
         * @param queued the number of tasks queued behind the active threads
         * @param completed the total number of tasks completed so far
         */
        private void checkSaturation(int active, int pool, int queued, long completed) {
            WatchdogDecision rv = evaluateSaturation(active, pool, queued, _saturatedSamples,
                                                     WATCHDOG_SATURATION_SAMPLES, _saturationReported);
            if (rv == WatchdogDecision.OK) {
                if (_saturationReported)
                    log().info("Timer saturation over: " + _name + " -> Recovered after " +
                               DataHelper.formatDuration(episodeForMs(_saturatedSamples)));
                _saturatedSamples = 0;
                _saturationReported = false;
                return;
            }
            if (_saturatedSamples == 0)
                _saturatedCompleted = completed;  // start of this episode
            _saturatedSamples++;
            if (rv != WatchdogDecision.REPORT)
                return;
            _saturationReported = true;
            log().warn("Timer pool saturated for at least " +
                       DataHelper.formatDuration(episodeForMs(_saturatedSamples)) + ": " + _name +
                       "\n* Active: " + active + '/' + pool + " * Queued behind it: " + queued +
                       "\n* Completed during episode: " + (completed - _saturatedCompleted) +
                       " * Immediate reschedules: " + _immediateReschedules.getAndSet(0) +
                       "\n* Periodic events are not firing" +
                       "\n* A high immediate count means something is re-arming itself" +
                       " on every event rather than waiting");
        }

        /**
         * Warn once per episode of a pool that completes nothing at all.
         * <p>The bar is the timer's own longest observed gap, learned as the timer
         * comes and goes, rather than its mean interval. See {@link #evaluateStall}.
         *
         * @param completed the total number of tasks completed so far
         * @param workDue true if a task is due to run right now
         */
        private void checkStall(long completed, boolean workDue) {
            long now = System.currentTimeMillis();
            boolean progressed = (completed > _lastCompleted);
            WatchdogDecision rv = evaluateStall(completed, _lastCompleted, _stalledSamples,
                                                WATCHDOG_STALL_SAMPLES, _stallReported,
                                                _maxObservedGapMs, workDue);
            _progressSamples = progressed ? _progressSamples + 1 : 0;
            _lastCompleted = completed;
            if (workDue) {
                _workDueThisEpisode = true;
            }
            if (rv == WatchdogDecision.OK) {
                // Recovery needs sustained progress, not one lucky sample. A timer that
                // completes something every few minutes otherwise ends and restarts the
                // episode each time, and one condition is reported over and over.
                if (_progressSamples < WATCHDOG_RECOVERY_SAMPLES) {
                    return;
                }
                if (_stallReported)
                    log().info("Timer stall over: " + _name + " is completing tasks again");
                // This quiet period turned out to be survivable, so raise the bar
                // to match: a timer with a known multi-minute lull must not be
                // reported for reaching the same lull again.
                long gap = now - _lastProgressAt;
                if (gap > _maxObservedGapMs) {
                    _maxObservedGapMs = gap;
                }
                _lastProgressAt = now;
                _stalledSamples = 0;
                _stallReported = false;
                _progressSamples = 0;
                _workDueThisEpisode = false;
                return;
            }
            if (_stalledSamples == 0) {
                _stallStartCompleted = _lastCompleted;
            }
            _stalledSamples++;
            if (rv != WatchdogDecision.REPORT)
                return;
            _stallReported = true;
            // The episode's own completion delta, not a lifetime total: a flat delta is
            // idleness stated outright, where a lifetime count alone hides it.
            long delta = completed - _stallStartCompleted;
            String trend = "completed " + delta + " of " + completed
                         + " tasks during the episode (" + DataHelper.formatDuration(episodeForMs(_stalledSamples))
                         + ", longest previous gap " + DataHelper.formatDuration(_maxObservedGapMs) + ")";
            String cause = _workDueThisEpisode
                ? "a task was due and nothing ran it - the scheduler is not running its queue"
                : "no task was due for the whole episode, so there was nothing to run";
            // INFO when nothing was ever due: that is a quiet timer, not a fault, and
            // saying so at WARN would train the reader to ignore the report that counts.
            if (_workDueThisEpisode) {
                log().warn("No timer event completed for at least "
                           + DataHelper.formatDuration(episodeForMs(_stalledSamples)) + ": " + _name
                           + " " + trend + " - " + cause);
            } else {
                log().info("No timer event completed for at least "
                           + DataHelper.formatDuration(episodeForMs(_stalledSamples)) + ": " + _name
                           + " " + trend + " - " + cause);
            }
        }

/**
 * The timer log, looked up per message rather than cached: the router
 * replaces its LogManager after start-up, and a cached Log would go
 * quiet from then on. Only log paths pay for this, never a sample.
 *
 * @return the current log for this class
 */
        private Log log() {
            return _context.logManager().getLog(SimpleTimer2.class);
        }
    }

    /**
     * Schedules one event on the executor.
     *
     * @param t the event to run
     * @param timeoutMs the delay before it runs
     * @return the scheduled future
     */
    private ScheduledFuture<?> schedule(TimedEvent t, long timeoutMs) {
        // Only the pathological path is counted, so a healthy timer pays nothing.
        // This is what distinguishes "the queue is full of periodic work" from
        // "the queue is full of immediate reschedules", which is the difference
        // between a busy router and a wedged one.
        if (timeoutMs <= 0)
            _immediateReschedules.incrementAndGet();
        return _executor.schedule(t, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Queue up the given event to be fired no sooner than timeoutMs from now.
     * Pool is set automatically from this timer.
     *
     * @param event to be run once
     * @param timeoutMs run after this delay
     * @since 0.9.70+
     */
    public void addEvent(final TimedEvent event, final long timeoutMs) {
        if (event == null)
            throw new IllegalArgumentException("addEvent null");
        event.setPool(this);
        event.schedule(timeoutMs);
    }

    /**
     * Schedule a periodic event backed by SimpleTimer2.TimedEvent.
     * The event self-reschedules via schedule() in timeReached(); this method
     * only sets the initial delay and the minimum-period check. The period the
     * event actually runs at is whatever its timeReached() passes to
     * schedule()/reschedule().
     *
     * @param event the TimedEvent to run, which reschedules itself from its own timeReached()
     * @param timeoutMs delay to the first run, in ms, and lower bound for the
     *                  period the event chooses for itself; the value is not
     *                  enforced after the first run
     * @throws IllegalArgumentException if timeoutMs less than 5000
     * @since 0.9.70+
     */
    public void addPeriodicEvent(final TimedEvent event, final long timeoutMs) {
        addPeriodicEvent(event, timeoutMs, timeoutMs);
    }

    /**
     * Schedule a periodic event backed by SimpleTimer2.TimedEvent.
     * The event self-reschedules via schedule() in timeReached(); the period
     * this method validates is not passed to the executor as a repetition rate.
     *
     * @param event the TimedEvent to run, which reschedules itself from its own timeReached()
     * @param delay run the first iteration after delay ms
     * @param timeoutMs lower bound in ms for the period the event picks for
     *                  itself, enforced only by the check below
     * @throws IllegalArgumentException if timeoutMs less than 5000
     * @since 0.9.70+
     */
    public void addPeriodicEvent(final TimedEvent event, final long delay, final long timeoutMs) {
        if (event == null)
            throw new IllegalArgumentException("addEvent null");
        if (timeoutMs < 5000)
            throw new IllegalArgumentException("timeout minimum 5000");
        event.setPool(this);
        event.schedule(delay);
    }

    /**
     * State of a given TimedEvent.
     * <p>
     * valid transitions:
     * {IDLE,CANCELLED,RUNNING} -&gt; SCHEDULED [ -&gt; SCHEDULED ]* -&gt; RUNNING -&gt; {IDLE,CANCELLED,SCHEDULED}
     * {IDLE,CANCELLED,RUNNING} -&gt; SCHEDULED [ -&gt; SCHEDULED ]* -&gt; CANCELLED
     * <p>
     * anything else is invalid.
     */
    private enum TimedEventState {
        /** Not scheduled, waiting for schedule(). */
        IDLE,
        /** Queued on the executor, not started yet. */
        SCHEDULED,
        /** Running doPass() right now. */
        RUNNING,
        /** Cancelled before it could run. */
        CANCELLED
    };


    /**
     * Base class for timer events. Extend this and use schedule()/{@link #cancel()}
     * directly instead of going through SimpleTimer2.
     * <p>
     * Synchronization is on this to avoid queue duplicates.
     * schedule() is idempotent if already scheduled.
     * reschedule() and {@link #forceReschedule} replace the existing timer.
     */
    public static abstract class TimedEvent implements Runnable {
        /** Logger for problems running the event. */
        private Log _log;
        /** The timer this event is scheduled on. */
        private SimpleTimer2 _pool;
        /** Random jitter added to the delay, in ms. */
        private int _fuzz;
        /** Default fuzz threshold in milliseconds. */
        protected static final int DEFAULT_FUZZ = 100;
        /** The pending schedule for this event, null if not scheduled. */
        private ScheduledFuture<?> _future;

        /** State of the current event. All access should be under lock. */
        protected TimedEventState _state;
        /** Absolute time this event should run next time. LOCKING: this. */
        private long _nextRun;
        /** Whether this was scheduled during RUNNING state. LOCKING: this. */
        private boolean _rescheduleAfterRun;
        /** Whether this was cancelled during RUNNING state. LOCKING: this. */
        private boolean _cancelAfterRun;

        /**
         * Shared init for all constructors.
         */
        private void init() {
            _log = I2PAppContext.getGlobalContext().logManager().getLog(SimpleTimer2.class);
            _fuzz = DEFAULT_FUZZ;
            _state = TimedEventState.IDLE;
        }

        /**
         * Create a new timed event without scheduling.
         * Pool is set later via {@link #setPool} or addEvent().
         *
         * @since 0.9.70+
         */
        protected TimedEvent() {
            init();
        }

        /**
         * Create a new timed event.
         * Must call schedule() later.
         *
         * @param pool the timer pool
         */
        public TimedEvent(SimpleTimer2 pool) {
            init();
            _pool = pool;
        }

        /**
         * Create a new timed event and automatically schedules it.
         *
         * @param pool the timer pool
         * @param timeoutMs timeout in milliseconds
         */
        public TimedEvent(SimpleTimer2 pool, long timeoutMs) {
            this(pool);
            schedule(timeoutMs);
        }

        /**
         * Reschedule threshold in ms. Rescheduling is skipped if the
         * existing and new timeouts differ by less than this value.
         * Default 100ms.
         *
         * @param fuzz the fuzz threshold in ms
         */
        public synchronized void setFuzz(int fuzz) {
            _fuzz = fuzz;
        }

        /**
         * Timer pool for this event. Must be called before schedule() for events created
         * via the no-arg constructor. Called automatically by {@link #addEvent}/addPeriodicEvent().
         * Not thread-safe to call concurrently with schedule() or cancel().
         *
         * @param pool the timer pool
         * @since 0.9.70+
         */
        public synchronized void setPool(SimpleTimer2 pool) {
            _pool = pool;
        }

        /**
         * Schedule this event. Does nothing if already scheduled.
         * For self-rescheduling periodic events, call from timeReached().
         *
         * @param timeoutMs delay in ms
         */
        public synchronized void schedule(long timeoutMs) {
            if (_pool == null) {
                if (_log.shouldWarn())
                    _log.warn("Cannot schedule, no pool set: " + this);
                return;
            }
            if (_log.shouldDebug())
                _log.debug("Scheduling: " + this + " (Timeout: " + timeoutMs + "ms) [" + _state + "]");
            if (timeoutMs <= 0) {
                // streaming timers do call with timeoutMs == 0
                if (timeoutMs < 0 && _log.shouldWarn())
                    _log.warn("Negative timeout (" + timeoutMs + "ms): " + this);
                timeoutMs = 1; // otherwise we may execute before _future is updated, which is fine
                               // except it triggers 'early execution' warning logging
            }

            // always set absolute time of execution
            _nextRun = timeoutMs + System.currentTimeMillis();
            _cancelAfterRun = false;

            switch (_state) {
                case RUNNING:
                    _rescheduleAfterRun = true;  // signal that we need rescheduling.
                    break;
                case IDLE:  // fall through
                case CANCELLED:
                    _future = _pool.schedule(this, timeoutMs);
                    _state = TimedEventState.SCHEDULED;
                    break;
                case SCHEDULED: // nothing
            }
        }

        /**
         * Use the earliest of the new time and the old time
         * May be called from within timeReached(), but schedule() is
         * better there.
         *
         * @param timeoutMs timeout in milliseconds
         */
        public void reschedule(long timeoutMs) {
            reschedule(timeoutMs, true);
        }

        /**
         * May be called from within timeReached(), but schedule() is
         * better there.
         *
         * @param timeoutMs timeout in milliseconds
         * @param useEarliestTime if true and already scheduled, use the earlier
         *                        timeout; if false and already scheduled, use the later
         */
        public synchronized void reschedule(long timeoutMs, boolean useEarliestTime) {
            if (timeoutMs <= 0) {
                if (timeoutMs < 0 && _log.shouldInfo())
                    _log.info("Negative reschedule (" + timeoutMs + "ms): " + this);
                timeoutMs = 1;
            }
            final long now = System.currentTimeMillis();
            long oldTimeout;
            boolean scheduled = _state == TimedEventState.SCHEDULED;
            if (scheduled)
                oldTimeout = _nextRun - now;
            else
                oldTimeout = timeoutMs;

            // don't bother rescheduling if within _fuzz ms
            if ((oldTimeout - _fuzz > timeoutMs && useEarliestTime) ||
                (oldTimeout + _fuzz < timeoutMs && !useEarliestTime)||
                !scheduled) {
                if (scheduled && oldTimeout <= 5) {
                    if (_log.shouldWarn())
                        _log.warn("Too close to reschedule: " + this + " (" + oldTimeout + "ms away)");
                    return;
                }
                if (scheduled && ((now + timeoutMs) < _nextRun || !useEarliestTime)) {
                    if (_log.shouldInfo())
                        _log.info("Reschedule " + this + ": " + timeoutMs + "ms (was " + oldTimeout + "ms)");
                    cancel();
                }
                schedule(timeoutMs);
            }
        }

        /**
         * Always use the new time - ignores fuzz.
         *
         * @param timeoutMs timeout in milliseconds
         */
        public synchronized void forceReschedule(long timeoutMs) {
            // don't cancel while running!
            if (_state == TimedEventState.SCHEDULED)
                cancel();
            schedule(timeoutMs);
        }

        /**
         * Cancel the timed event.
         * If the event is currently running, cancellation is deferred until timeReached()
         * completes (sets _cancelAfterRun flag).
         *
         * @return true if the event will not execute, false if already idle/cancelled
         */
        public synchronized boolean cancel() {
            // always clear
            _rescheduleAfterRun = false;

            switch (_state) {
                case CANCELLED:  // fall through
                case IDLE:
                    break; // my preference is to throw IllegalState here, but let it be.
                case RUNNING:
                    _cancelAfterRun = true;
                    return true;
                case SCHEDULED:
                        // There's probably a race here, where it's cancelled after it's running
                        // The result (if rescheduled) is a dup on the queue, see tickets 1694, 1705
                        // Mitigated by close-to-execution check in reschedule()
                    boolean cancelled = _future.cancel(true);
                    if (cancelled) {
                        _state = TimedEventState.CANCELLED;
                        _future = null;
                    } else if (_log.shouldWarn()) {
                        long remaining = _nextRun - System.currentTimeMillis();
                        _log.warn("Cancel failed: " + this + " (running in " + remaining + "ms)");
                    }
                    return cancelled;
            }
            return false;

        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            try {
                execute();
            } catch (RuntimeException re) {
                _log.error("Timer error: " + this, re);
                throw re;
            } catch (OutOfMemoryError oome) {
                _log.error("Timer error: " + this, oome);
                throw new RuntimeException("timer error: " + this, oome);
            }
        }

        /**
         * Runs the event's doPass() and reschedules it.
         */
        @SuppressWarnings("PMD.AvoidThrowingNewInstanceOfSameException")
        private void execute() {
            if (_log.shouldDebug())
                _log.debug("Running: " + this);
            long startTime = System.currentTimeMillis();
            synchronized (this) {
                if (!checkAndPrepareRun(startTime))
                    return;
            }
            if (_log.shouldWarn()) {
                if (_future != null) {
                    long delay = _future.getDelay(TimeUnit.MILLISECONDS);
                    if (delay > 100)
                        _log.warn("Early exec " + this + " (" + delay + "ms)");
                    else if (delay < -1000) {
                        // Recorded whether or not anything is logged: the backlog
                        // reading drives the worker count, so it must not depend on a
                        // log level being enabled.
                        _pool.recordDelay(-delay);
                        _log.warn("Late exec " + this + " (" + (0 - delay) + "ms)" +
                                  (delay < -5000 ? _pool.debug() : ""));
                    }
                } else {
                    _log.warn("No future: " + this);
                }
            }
            try {
                timeReached();
            } catch (Throwable t) {
                _log.log(Log.CRIT, "Timer task crashed: " + this, t);
            } finally {
                synchronized (this) {
                    updateStateAfterRun();
                }
            }
            logTimingAndStats(startTime);
        }

        /**
         * Validate state before running. Returns true if execution should proceed,
         * false if the event was cancelled or needs to be rescheduled (already handled).
         * Must be called inside synchronized(this).
         *
         * @param now the current time in ms since the epoch
         * @return true if execution should proceed, false if cancelled or rescheduled
         */
        private boolean checkAndPrepareRun(long now) {
            if (Thread.currentThread().isInterrupted()) {
                if (_log.shouldWarn())
                    _log.warn("Interrupted: " + this + " [" + _state + "]");
                return false;
            }
            if (_rescheduleAfterRun)
                throw new IllegalStateException(this + " rescheduleAfterRun cannot be true here");

            switch (_state) {
                case CANCELLED:
                    return false;
                case IDLE:
                case RUNNING:
                    throw new IllegalStateException(this + " not possible to be in " + _state);
                case SCHEDULED:
            }

            long difference = _nextRun - now;
            if (difference > _fuzz) {
                _state = TimedEventState.IDLE;
                if (_log.shouldInfo())
                    _log.info("Early exec, reschedule " + this + " in " + difference + "ms");
                schedule(difference);
                return false;
            }
            _state = TimedEventState.RUNNING;
            return true;
        }

        /**
         * Update state after timeReached() completes.
         * Must be called inside synchronized(this).
         */
        private void updateStateAfterRun() {
            switch (_state) {
                case RUNNING:
                    if (_cancelAfterRun) {
                        _cancelAfterRun = false;
                        _state = TimedEventState.CANCELLED;
                        _future = null;
                    } else {
                        _state = TimedEventState.IDLE;
                        if (_rescheduleAfterRun) {
                            _rescheduleAfterRun = false;
                            if (_log.shouldInfo())
                                _log.info("Rescheduling after run: " + this);
                            schedule(_nextRun - System.currentTimeMillis());
                        } else {
                            _future = null;
                        }
                    }
                    break;
                default:
                    throw new IllegalStateException(this + " can't be " + _state);
            }
        }

        /**
         * Log execution duration and periodic stats.
         *
         * @param before the time the event started running, in ms since the epoch
         */
        private void logTimingAndStats(long before) {
            long time = System.currentTimeMillis() - before;
            if (time > 500 && _log.shouldWarn())
                _log.warn("Slow event (" + time + "ms): " + this);
            else if (_log.shouldDebug())
                _log.debug("Execution finished in " + time + "ms: " + this);
            if (_log.shouldInfo()) {
                long completed = _pool._completed.incrementAndGet();
                if (completed % 250 == 0)
                    _log.info(_pool.debug());
            }
        }

        /**
         * Timer summary for log messages.
         *
         * @return the simple class name for log messages
         *
         * @since 0.9.57
         */
        @Override
        public String toString() {
            return getClass().getSimpleName();
        }

        /**
         * Called when this event's scheduled time arrives. Must NOT block.
         * For periodic events, call schedule(period) at the end to self-reschedule.
         */
        public abstract void timeReached();
    }

    /**
     * Timer name.
     *
     * @return the name prefix of this timer's threads
     */
    @Override
    public String toString() {
        return _name;
    }

    /**
     * Warning - slow.
     *
     * @return a multi-line string with pool, queue and event state
     */
    private String debug() {
        _executor.purge();  // Remove cancelled tasks from the queue so we get a good queue size stat
        return
            "\n* Pool: " + _name +
            "; Active: " + _executor.getActiveCount() + '/' + _executor.getPoolSize() +
            "; Completed: " + _executor.getCompletedTaskCount() +
            "; Queued: " + _executor.getQueue().size();
    }

}
