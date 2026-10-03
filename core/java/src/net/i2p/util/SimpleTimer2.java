package net.i2p.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;

/**
 * Scheduled event executor backed by ScheduledThreadPoolExecutor.
 * Replaces the legacy SimpleTimer (deleted; had lock contention issues).
 * Supports cancel and reschedule. Events must NOT block.
 *
 * All timer events should extend TimedEvent and use schedule()/cancel() directly.
 *
 * A saturation watchdog samples this pool from a thread of its own and warns
 * when every worker has stayed occupied with a backlog behind it: a
 * ScheduledThreadPoolExecutor never grows past {@link #THREADS}, so two
 * blocked events starve every periodic event on this timer without throwing
 * anything or writing anything to the log.
 *
 * @author zzz
 */
public class SimpleTimer2 {

    /**
     *  If you have a context, use context.simpleTimer2() instead
     *
     *  @return the global SimpleTimer2 instance
     */
    public static SimpleTimer2 getInstance() {
        return I2PAppContext.getGlobalContext().simpleTimer2();
    }

    private static final int THREADS = 2;

    /**
     *  How often the saturation watchdog samples this pool. Long enough to be
     *  free (four counter reads), short enough that a wedge is reported within
     *  a couple of minutes instead of never.
     *
     *  @since 0.9.71+
     */
    static final int WATCHDOG_INTERVAL_MS = 30 * 1000;
    /**
     *  Consecutive saturated samples tolerated before a WARN. The pool is
     *  briefly busy all the time; a pool that is still fully occupied with a
     *  backlog after this many samples is wedged.
     *
     *  @since 0.9.71+
     */
    static final int WATCHDOG_SATURATION_SAMPLES = 3;
    /**
     *  Queue depth that turns full occupancy into a backlog rather than a
     *  coincidence. With {@link #THREADS} workers, this means at least this
     *  many events are waiting behind running ones. Not zero: the queue also
     *  holds delayed events that are not due yet, so depth alone proves nothing.
     *
     *  @since 0.9.71+
     */
    static final int WATCHDOG_MIN_QUEUE = 3;
    /**
     *  Consecutive samples with no completed task before a WARN. Longer than
     *  {@link #WATCHDOG_SATURATION_SAMPLES} so the broader, less specific signal
     *  follows the narrow one rather than pre-empting it: a wedged pool reports
     *  saturation first, and only reaches "nothing is running at all" if it stays
     *  wedged twice as long.
     *
     *  @since 0.9.71+
     */
    static final int WATCHDOG_STALL_SAMPLES = 6;

    private final ScheduledThreadPoolExecutor _executor;
    private final String _name;
    private final AtomicInteger _count = new AtomicInteger();
    private final I2PAppContext _context;
    private final Runnable _onShutdown = () -> stop(false);
    private final AtomicLong _completed = new AtomicLong();
    private final SaturationWatchdog _watchdog;

    /**
     *  Schedules requested with a zero or negative delay since the last watchdog
     *  report. Counted rather than logged per event: an immediate reschedule is
     *  legitimate occasionally, but a connection whose head-of-line packet is
     *  already older than one RTO asks for one on <em>every</em> ACK, and a
     *  population of those will bury a two-thread pool. The count is what tells the
     *  saturation report the queue is being flooded by immediate reschedules rather
     *  than by ordinary periodic work.
     *
     *  <p>Incremented only on the zero-delay path, so the steady-state cost is nil.
     *
     *  @since 0.9.71+
     */
    private final AtomicLong _immediateReschedules = new AtomicLong();

    /**
     *  To be instantiated by the context.
     *  Others should use context.simpleTimer2() instead
     *
     *  @param context the I2P application context
     */
    public SimpleTimer2(I2PAppContext context) {
        this(context, "SimpleTimer");
    }

    /**
     *  To be instantiated by the context.
     *  Others should use context.simpleTimer2() instead, except for
     *  dedicated timers that need a distinct thread name.
     *
     *  @param context the I2P application context
     *  @param name the timer name, used for the timer thread name
     *  @since 0.9.70+ public
     */
    public SimpleTimer2(I2PAppContext context, String name) {
        this(context, name, true);
    }

    /**
     *  To be instantiated by the context.
     *  Others should use context.simpleTimer2() instead
     *
     *  @param context the I2P application context
     *  @param name the timer name
     *  @param prestartAllThreads whether to prestart all threads
     *  @since 0.9
     */
    protected SimpleTimer2(I2PAppContext context, String name, boolean prestartAllThreads) {
        _context = context;
        _name = name;
        _executor = new CustomScheduledThreadPoolExecutor(THREADS, new CustomThreadFactory());
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

    private static class CustomScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
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

    private class CustomThreadFactory implements ThreadFactory {
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
     *
     * <p>The pool counts as saturated only when both halves hold: every worker
     * is occupied ({@code activeCount >= poolSize}) <i>and</i> at least
     * {@link #WATCHDOG_MIN_QUEUE} events are waiting behind them. Either half
     * alone is noise - the queue also holds delayed events that are not due yet,
     * and both workers being busy is ordinary whenever two events happen to run
     * at once.
     *
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
     *
     * <p>This is the cheap form of "a periodic event stopped firing": the
     * executor's own completed-task counter not advancing across samples means
     * nothing this timer runs is finishing, regardless of what the pool size
     * says. It deliberately does not track individual events - that would mean
     * bookkeeping on every scheduling and every run.
     *
     * <p>An executor that has never completed a task is treated as idle, not
     * wedged: a timer with nothing scheduled on it is not a failure, and
     * warning about one would bury the real reports. A wedge that starts before
     * the first completion is still caught by {@link #evaluateSaturation}.
     *
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
     *                          back from; zero when no baseline has been established
     * @return REPORT once per episode, OK on progress or an idle timer (ending
     *         the episode), PENDING otherwise
     * @since 0.9.71+
     */
    static WatchdogDecision evaluateStall(long completed, long lastCompleted,
                                          int consecutiveStalledSamples, int threshold,
                                          boolean episodeReported, long maxObservedGapMs) {
        if (completed > lastCompleted || completed <= 0)
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
        private final String _name;

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
     *
     * <p>It cannot run on the pool it watches: a wedged pool has no spare
     * worker to run the watchdog on, which is the whole failure. It also does
     * nothing but read counters, so no event running on this timer can delay
     * it. Every sample is four counter reads and a few field writes - no
     * allocation, no synchronization beyond the monitor it sleeps on - so it is
     * free to leave running for the life of the router.
     *
     * <p>Everything below is touched only by the watchdog thread, except
     * {@code _stopped}, which is guarded by the watchdog monitor.
     */
    private class SaturationWatchdog {

        private int _saturatedSamples;
        private boolean _saturationReported;
        private long _saturatedCompleted;
        private int _stalledSamples;
        private boolean _stallReported;
        private long _lastCompleted;
        /** Wall clock at which we last saw the completion count rise. */
        private long _lastProgressAt;
        /**
         * Longest quiet period this timer has previously come back from.
         * Grows only while the watchdog observes recovery, so it is a floor
         * learned from the timer's own behaviour rather than a guess.
         */
        private long _maxObservedGapMs;
        /** Guarded by this */
        private boolean _stopped;

        SaturationWatchdog() {
            _lastProgressAt = System.currentTimeMillis();
            start();
        }

        /**
         * Start the sampling thread, warning if the JVM will not run it - a
         * watchdog that silently failed to start is the same invisible failure
         * this class exists to prevent. No reference to the thread is kept: it
         * runs until {@link #shutdown()}, and being a daemon it cannot hold up
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
         * {@link #start()} failed. The thread is a daemon, so it cannot hold up
         * JVM exit even if this is never called.
         */
        void shutdown() {
            synchronized (this) {
                _stopped = true;
                notifyAll();
            }
        }

        /**
         * Sample every {@link #WATCHDOG_INTERVAL_MS} until {@link #shutdown()}.
         *
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
         * read after shutdown, so a sample racing stop() degrades to reporting
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
            checkSaturation(active, pool, queued, completed);
            checkStall(completed);
        }

        /**
         * Warn once per episode of a fully occupied pool with a backlog.
         */
        private void checkSaturation(int active, int pool, int queued, long completed) {
            WatchdogDecision rv = evaluateSaturation(active, pool, queued, _saturatedSamples,
                                                     WATCHDOG_SATURATION_SAMPLES, _saturationReported);
            if (rv == WatchdogDecision.OK) {
                if (_saturationReported)
                    log().info("Timer saturation over: " + _name + " recovered after " +
                               (episodeForMs(_saturatedSamples) / 1000) + "s");
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
                       (episodeForMs(_saturatedSamples) / 1000) + "s: " + _name +
                       " active " + active + '/' + pool + ", " + queued +
                       " events queued behind it, " + (completed - _saturatedCompleted) +
                       " tasks completed during the episode, " +
                       _immediateReschedules.getAndSet(0) +
                       " immediate reschedules requested - periodic events are not" +
                       " firing; a high immediate count means something is re-arming itself" +
                       " on every event rather than waiting");
        }

        /**
         * Warn once per episode of a pool that completes nothing at all.
         *
         * <p>The bar is the timer's own longest observed gap, learned as the timer
         * comes and goes, rather than its mean interval. See {@link #evaluateStall}.
         */
        private void checkStall(long completed) {
            long now = System.currentTimeMillis();
            WatchdogDecision rv = evaluateStall(completed, _lastCompleted, _stalledSamples,
                                                WATCHDOG_STALL_SAMPLES, _stallReported,
                                                _maxObservedGapMs);
            _lastCompleted = completed;
            if (rv == WatchdogDecision.OK) {
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
                return;
            }
            _stalledSamples++;
            if (rv != WatchdogDecision.REPORT)
                return;
            _stallReported = true;
            log().warn("No timer event completed for at least " +
                       (episodeForMs(_stalledSamples) / 1000) + "s: " + _name +
                       " completed " + completed + " tasks in total (longest gap it has" +
                       " recovered from before " + _maxObservedGapMs / 1000 + "s) -" +
                       " the scheduler is not running any event");
        }

        /**
         * The timer log, looked up per message rather than cached: the router
         * replaces its LogManager after start-up, and a cached Log would go
         * quiet from then on. Only log paths pay for this, never a sample.
         */
        private Log log() {
            return _context.logManager().getLog(SimpleTimer2.class);
        }
    }

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
     * @param event the event
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
     * @param event the event
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
     *
     * valid transitions:
     * {IDLE,CANCELLED,RUNNING} -&gt; SCHEDULED [ -&gt; SCHEDULED ]* -&gt; RUNNING -&gt; {IDLE,CANCELLED,SCHEDULED}
     * {IDLE,CANCELLED,RUNNING} -&gt; SCHEDULED [ -&gt; SCHEDULED ]* -&gt; CANCELLED
     *
     * anything else is invalid.
     */
    private enum TimedEventState {
        IDLE,
        SCHEDULED,
        RUNNING,
        CANCELLED
    };


    /**
     * Base class for timer events. Extend this and use schedule()/cancel()
     * directly instead of going through SimpleTimer2.
     *
     * Synchronization is on this to avoid queue duplicates.
     * schedule() is idempotent if already scheduled.
     * reschedule() and forceReschedule() replace the existing timer.
     */
    public static abstract class TimedEvent implements Runnable {
        private Log _log;
        private SimpleTimer2 _pool;
        private int _fuzz;
        /** Default fuzz threshold in milliseconds. */
        protected static final int DEFAULT_FUZZ = 100;
        private ScheduledFuture<?> _future;

        /** State of the current event. All access should be under lock. */
        protected TimedEventState _state;
        /** Absolute time this event should run next time. LOCKING: this */
        private long _nextRun;
        /** Whether this was scheduled during RUNNING state. LOCKING: this */
        private boolean _rescheduleAfterRun;
        /** Whether this was cancelled during RUNNING state. LOCKING: this */
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
         * Pool is set later via setPool() or addEvent().
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
         * via the no-arg constructor. Called automatically by addEvent()/addPeriodicEvent().
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
         * Always use the new time - ignores fuzz
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
                    else if (delay < -1000)
                        _log.warn("Late exec " + this + " (" + (0 - delay) + "ms)" +
                                  (delay < -5000 ? _pool.debug() : ""));
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
         *  @return the simple class name for log messages
         *
         *  @since 0.9.57
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
     * @return the timer name
     */
    @Override
    public String toString() {
        return _name;
    }

    /** Warning - slow. */
    private String debug() {
        _executor.purge();  // Remove cancelled tasks from the queue so we get a good queue size stat
        return
            "\n* Pool: " + _name +
            "; Active: " + _executor.getActiveCount() + '/' + _executor.getPoolSize() +
            "; Completed: " + _executor.getCompletedTaskCount() +
            "; Queued: " + _executor.getQueue().size();
    }

}
