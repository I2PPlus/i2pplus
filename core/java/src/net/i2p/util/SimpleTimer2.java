package net.i2p.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * A high-concurrency event scheduler built on {@link ScheduledThreadPoolExecutor}
 * with dynamically adjustable thread pool size.
 *
 * <p>Initially, the thread pool contains a single thread and can grow as workload increases,
 * up to a maximum size defined by {@code Math.max(SystemVersion.getCores() * 4, 32)}.
 * Threads that remain idle for more than 5 seconds are expired and removed, allowing
 * the pool to shrink during low activity periods.
 *
 * <p>This design ensures efficient resource utilization by scaling the thread count based
 * on workload, with quick shrinking to free resources when demand drops.
 *
 * <p>Note: The thread pool dynamically grows when queued tasks exceed active thread count,
 * and shrinks automatically via thread timeout. Use this for workloads with intermittent
 * bursts or high levels of timers.</p>
 *
 * @author zzz, modified by dr|z3d to allocate from a dynamically allocated pool
 * @since 0.9 (modified 2025)
 */
public class SimpleTimer2 {

    /**
     * Get the global instance.
     * Prefer {@code context.simpleTimer2()} if you have an {@link I2PAppContext}.
     */
    public static SimpleTimer2 getInstance() {
        return I2PAppContext.getGlobalContext().simpleTimer2();
    }

    private static final int MAX_THREADS = 1024;
    private static final int INITIAL_THREADS = 1;
    private static final long THREAD_KEEP_ALIVE_SECONDS = 5;

    private final ScheduledThreadPoolExecutor _executor;
    private final String _name;
    private final AtomicInteger _count = new AtomicInteger();
    private final int _threadsMax;
    private ScheduledFuture<?> _resizeThreadPool;
    private final I2PAppContext _context;
    private final Runnable _shutdown;

    /**
     * Construct a timer tied to the given context.
     * @param context non-null context
     */
    public SimpleTimer2(I2PAppContext context) {
        this(context, "SimpleTimer2");
    }

    /**
     * Construct a named timer.
     * @param context non-null context
     * @param name thread pool name
     */
    protected SimpleTimer2(I2PAppContext context, String name) {
        this(context, name, true);
    }

    /**
     * Construct a named timer with optional prestart.
     * @param context non-null context
     * @param name thread pool name
     * @param prestartAllThreads if true, start all core threads immediately (ignored here)
     * @since 0.9
     */
    protected SimpleTimer2(I2PAppContext context, String name, boolean prestartAllThreads) {
        _context = context;
        _name = name;
        _threadsMax = MAX_THREADS;
        _executor = new CustomScheduledThreadPoolExecutor(INITIAL_THREADS, new CustomThreadFactory());
        // Schedule periodic thread pool resizing housekeeping every 5 seconds
        _resizeThreadPool = _executor.scheduleAtFixedRate(this::adjustThreadPoolSize, 5, 5, TimeUnit.SECONDS);
        // Allow core threads to time out after inactivity so pool can shrink
        _executor.setKeepAliveTime(THREAD_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
        _executor.allowCoreThreadTimeOut(true);
        // We do not prestart all threads since we start with 1 thread and grow as needed
        _shutdown = new Shutdown();
        context.addShutdownTask(_shutdown);
    }

    private class Shutdown implements Runnable {
        public void run() {
            stop(false);
        }
    }

    /**
     * Stops the timer and discards pending tasks.
     * Cannot be restarted.
     */
    public void stop() {
        if (_resizeThreadPool != null)
            _resizeThreadPool.cancel(true);
        stop(true);
    }

    private void stop(boolean removeTask) {
        if (removeTask)
            _context.removeShutdownTask(_shutdown);
        _executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        _executor.shutdownNow();
    }

    private class CustomScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
        public CustomScheduledThreadPoolExecutor(int threads, ThreadFactory factory) {
            super(threads, factory);
            setRemoveOnCancelPolicy(true);
            // Set max pool size (though ScheduledThreadPoolExecutor bases worker threads on corePoolSize)
            // We will adjust corePoolSize dynamically to grow/shrink thread count
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t != null) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(SimpleTimer2.class);
                log.log(Log.CRIT, "Event borked: " + r, t);
            }
            // Dynamically adjust thread pool size after task execution
            adjustThreadPoolSize();
        }
    }

    private class CustomThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName(_name + ' ' + _count.incrementAndGet() + '/' + _threadsMax);
            rv.setDaemon(true);
            // Use normal priority to avoid starving other threads under load
            rv.setPriority(Thread.NORM_PRIORITY - 2);
            return rv;
        }
    }

    /**
     * Dynamically adjust the thread pool size based on current workload.
     *
     * Logic:
     * - If queue size grows and active thread count equals current pool size,
     *   increase core pool size up to max.
     * - If queue is empty and threads are idle, pool will shrink automatically due to timeout.
     */
    private synchronized void adjustThreadPoolSize() {
        int active = _executor.getActiveCount();
        int poolSize = _executor.getCorePoolSize();
        int queueSize = _executor.getQueue().size();

        // Grow pool if needed
        if (queueSize > 0 && active >= poolSize && poolSize < _threadsMax) {
            int newSize = Math.min(poolSize + 1, _threadsMax);
            _executor.setCorePoolSize(newSize);
            if (_context != null) {
                Log log = _context.logManager().getLog(SimpleTimer2.class);
                if (log.shouldInfo())
                    log.info("Increased thread pool size to " + newSize + " due to workload");
            }
        }

        // Shrink pool if possible:
        // When there is no queued work and active threads are much less than pool size,
        // reduce core pool size to free threads
        else if (queueSize == 0 && active < poolSize && poolSize > INITIAL_THREADS) {
            int newSize = Math.max(active, INITIAL_THREADS);
            if (newSize < poolSize) {
                _executor.setCorePoolSize(newSize);
                if (_context != null) {
                    Log log = _context.logManager().getLog(SimpleTimer2.class);
                    if (log.shouldInfo())
                        log.info("Decreased thread pool size to " + newSize + " due to low workload");
                }
            }
        }
    }

    private ScheduledFuture<?> schedule(TimedEvent t, long timeoutMs) {
        return _executor.schedule(t, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedule a one-time event (uncancellable).
     * For new code, extend {@link TimedEvent} instead.
     * @param event non-null event
     * @param timeoutMs delay in milliseconds
     * @since 0.9.20
     */
    public void addEvent(final SimpleTimer.TimedEvent event, final long timeoutMs) {
        if (event == null)
            throw new IllegalArgumentException("addEvent null");

        new TimedEvent(this, timeoutMs) {
            @Override
            public void timeReached() {
                event.timeReached();
            }

            @Override
            public String toString() {
                return event.toString();
            }
        };
    }

    /**
     * Schedule a periodic event (uncancellable).
     * @param event non-null event
     * @param timeoutMs period (≥5000 ms)
     * @since 0.9.20
     */
    public void addPeriodicEvent(final SimpleTimer.TimedEvent event, final long timeoutMs) {
        addPeriodicEvent(event, timeoutMs, timeoutMs);
    }

    /**
     * Schedule a periodic event with initial delay.
     * @param delay initial delay in ms
     * @param timeoutMs period in ms (≥5000)
     * @since 0.9.20
     */
    public void addPeriodicEvent(final SimpleTimer.TimedEvent event, final long delay, final long timeoutMs) {
        if (timeoutMs < 5000)
            throw new IllegalArgumentException("timeout minimum 5000");

        new PeriodicTimedEvent(this, delay, timeoutMs) {
            @Override
            public void timeReached() {
                event.timeReached();
            }

            @Override
            public String toString() {
                return event.toString();
            }
        };
    }

    // Rest of class unchanged below: TimedEvent, PeriodicTimedEvent, etc.

    private enum TimedEventState {
        IDLE, SCHEDULED, RUNNING, CANCELLED
    }

    /**
     * Base class for scheduled events.
     *
     * <p><strong>Important:</strong> {@code timeReached()} must not block.
     * Long-running or blocking tasks will delay other timers.</p>
     *
     * <p>Usage:
     * <pre>
     * new SimpleTimer2.TimedEvent(timer, 5000) {
     *     public void timeReached() {
     *         // do non-blocking work
     *     }
     * };
     * </pre>
     */
    public static abstract class TimedEvent implements Runnable {
        private final Log _log;
        private final SimpleTimer2 _pool;
        private int _fuzz = DEFAULT_FUZZ;
        protected static final int DEFAULT_FUZZ = 100;

        private ScheduledFuture<?> _future;
        protected TimedEventState _state;
        private long _nextRun;
        private boolean _rescheduleAfterRun;
        private boolean _cancelAfterRun;

        public TimedEvent(SimpleTimer2 pool) {
            _pool = pool;
            _log = I2PAppContext.getGlobalContext().logManager().getLog(SimpleTimer2.class);
            _state = TimedEventState.IDLE;
        }

        public TimedEvent(SimpleTimer2 pool, long timeoutMs) {
            this(pool);
            schedule(timeoutMs);
        }

        public synchronized void setFuzz(int fuzz) {
            _fuzz = fuzz;
        }

        /**
         * Schedule this event to run after {@code timeoutMs}.
         * If already scheduled or running, does nothing (safe for use in {@code timeReached()}).
         */
        public synchronized void schedule(long timeoutMs) {
            if (_log.shouldDebug())
                _log.debug("Scheduling: " + this + " (timeout: " + timeoutMs + "ms) [" + _state + "]");
            if (timeoutMs <= 0) {
                if (timeoutMs < 0 && _log.shouldDebug())
                    _log.warn("Scheduled timeout < 0ms (" + timeoutMs + "ms): " + this + " [" + _state + "]");
                timeoutMs = 1;
            }

            _nextRun = System.currentTimeMillis() + timeoutMs;
            _cancelAfterRun = false;

            switch (_state) {
                case RUNNING:
                    _rescheduleAfterRun = true;
                    break;
                case IDLE:
                case CANCELLED:
                    _future = _pool.schedule(this, timeoutMs);
                    _state = TimedEventState.SCHEDULED;
                    break;
                case SCHEDULED:
                    // Already scheduled; do nothing
                    break;
            }
        }

        /**
         * Reschedule using earliest (or latest) of old/new time.
         * May lose timer if called too close to execution.
         */
        public void reschedule(long timeoutMs) {
            reschedule(timeoutMs, true);
        }

        public synchronized void reschedule(long timeoutMs, boolean useEarliestTime) {
            if (timeoutMs <= 0) {
                if (timeoutMs < 0 && _log.shouldInfo())
                    _log.info("Resched. timeout < 0: " + this + " (timeout: " + timeoutMs + "ms) [" + _state + "]");
                timeoutMs = 1;
            }

            final long now = System.currentTimeMillis();
            boolean scheduled = _state == TimedEventState.SCHEDULED;
            long oldTimeout = scheduled ? _nextRun - now : timeoutMs;

            // Grace window → 20ms to reduce race under load
            if ((oldTimeout <= 20) && scheduled) {
                if (_log.shouldDebug())
                    _log.debug("Not rescheduling to " + timeoutMs + "ms, about to execute " + this + " in " + oldTimeout + "ms");
                return;
            }

            if ((!scheduled) ||
                (useEarliestTime && oldTimeout - _fuzz > timeoutMs) ||
                (!useEarliestTime && oldTimeout + _fuzz < timeoutMs)) {
                if (scheduled) {
                    cancel();
                }
                schedule(timeoutMs);
            }
        }

        /**
         * Always use the new time, ignoring fuzz and current schedule.
         */
        public synchronized void forceReschedule(long timeoutMs) {
            if (_state == TimedEventState.SCHEDULED) {
                cancel();
            }
            schedule(timeoutMs);
        }

        /**
         * Cancel this event.
         * If called during execution, cancellation takes effect after {@code timeReached()}.
         * @return true if successfully cancelled
         */
        public synchronized boolean cancel() {
            _rescheduleAfterRun = false;

            switch (_state) {
                case CANCELLED:
                case IDLE:
                    return false;
                case RUNNING:
                    _cancelAfterRun = true;
                    return true;
                case SCHEDULED:
                    boolean cancelled = _future.cancel(true);
                    if (cancelled) {
                        _state = TimedEventState.CANCELLED;
                    } else if (_log.shouldWarn()) {
                        _log.warn("Could not cancel " + this + " (next run in " + (_nextRun - System.currentTimeMillis()) + "ms)", new Exception());
                    }
                    return cancelled;
            }
            return false;
        }

        public void run() {
            try {
                run2();
            } catch (RuntimeException | OutOfMemoryError e) {
                _log.error("Timer error", e);
                throw e;
            }
        }

        private void run2() {
            if (_log.shouldDebug())
                _log.debug("Running: " + this);

            long before = System.currentTimeMillis();
            synchronized (this) {
                if (Thread.currentThread().isInterrupted()) {
                    if (_log.shouldWarn())
                        _log.warn("Interrupted in run, state " + _state + " event " + this);
                    return;
                }

                if (_rescheduleAfterRun)
                    throw new IllegalStateException(this + " rescheduleAfterRun cannot be true here");

                if (_state == TimedEventState.CANCELLED) {
                    if (_log.shouldInfo())
                        _log.info("Not actually running: CANCELLED " + this);
                    return;
                }

                if (_state != TimedEventState.SCHEDULED)
                    throw new IllegalStateException(this + " invalid state: " + _state);

                long difference = _nextRun - before;
                if (difference > _fuzz) {
                    _state = TimedEventState.IDLE;
                    if (_log.shouldInfo())
                        _log.info("Early execution, rescheduling for " + difference + "ms later: " + this);
                    schedule(difference);
                    return;
                }

                _state = TimedEventState.RUNNING;
            }

            try {
                timeReached();
            } catch (Throwable t) {
                _log.log(Log.CRIT, _pool + ": Timed task " + this + " exited unexpectedly, please report", t);
            } finally {
                synchronized (this) {
                    switch (_state) {
                        case CANCELLED:
                            break;
                        case RUNNING:
                            if (_cancelAfterRun) {
                                _cancelAfterRun = false;
                                _state = TimedEventState.CANCELLED;
                            } else {
                                _state = TimedEventState.IDLE;
                                if (_rescheduleAfterRun) {
                                    _rescheduleAfterRun = false;
                                    if (_log.shouldInfo())
                                        _log.info("Rescheduling after run: " + this);
                                    schedule(_nextRun - System.currentTimeMillis());
                                }
                            }
                            break;
                        default:
                            throw new IllegalStateException(this + " invalid final state: " + _state);
                    }
                }
            }

            long time = System.currentTimeMillis() - before;
            if (time > 500 && _log.shouldWarn())
                _log.warn(_pool + " event execution took " + time + "ms: " + this);
            else if (_log.shouldDebug())
                _log.debug("Execution finished in " + time + "ms: " + this);

            // Reduce debug logging frequency to avoid overhead under load
            if (_log.shouldInfo()) {
                long completed = _pool.getCompletedTaskCount();
                if (completed % 10_000 == 0)
                    _log.info(_pool.debug());
            }
        }

        @Override
        public String toString() {
            return getClass().getSimpleName();
        }

        /**
         * Called when the scheduled time is reached.
         * <strong>Must not block.</strong>
         */
        public abstract void timeReached();
    }

    @Override
    public String toString() {
        return _name;
    }

    private long getCompletedTaskCount() {
        return _executor.getCompletedTaskCount();
    }

    private String debug() {
        _executor.purge();
        return
            "\n* Pool: " + _name +
            "; Active: " + _executor.getActiveCount() + '/' + _executor.getCorePoolSize() +
            "; Completed: " + _executor.getCompletedTaskCount() +
            "; Queued: " + _executor.getQueue().size();
    }

    /**
     * Uncancellable periodic event.
     */
    private static abstract class PeriodicTimedEvent extends TimedEvent {
        private final long _timeoutMs;

        public PeriodicTimedEvent(SimpleTimer2 pool, long delay, long timeoutMs) {
            super(pool, delay);
            if (timeoutMs < 5000)
                throw new IllegalArgumentException("timeout minimum 5000");
            _timeoutMs = timeoutMs;
        }

        @Override
        public void run() {
            // Run the event (including timeReached())
            super.run();
            // After it completes, auto-reschedule ONLY if still in IDLE state
            // (i.e., user didn't cancel or reschedule during timeReached())
            synchronized (this) {
                if (_state == TimedEventState.IDLE) {
                    schedule(_timeoutMs);
                }
            }
        }
    }
}
