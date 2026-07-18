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

    private final ScheduledThreadPoolExecutor _executor;
    private final String _name;
    private final AtomicInteger _count = new AtomicInteger();
    private final I2PAppContext _context;
    private final Runnable _onShutdown = () -> stop(false);
    private final AtomicLong _completed = new AtomicLong();

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

    private ScheduledFuture<?> schedule(TimedEvent t, long timeoutMs) {
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
     * The event self-reschedules via schedule() in timeReached().
     *
     * @param event the event to run periodically
     * @param timeoutMs period in ms between executions
     * @throws IllegalArgumentException if timeoutMs less than 5000
     * @since 0.9.70+
     */
    public void addPeriodicEvent(final TimedEvent event, final long timeoutMs) {
        addPeriodicEvent(event, timeoutMs, timeoutMs);
    }

    /**
     * Schedule a periodic event backed by SimpleTimer2.TimedEvent.
     *
     * @param event the event to run periodically
     * @param delay run the first iteration after delay ms
     * @param timeoutMs period in ms between executions
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
     * state of a given TimedEvent
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
        protected static final int DEFAULT_FUZZ = 100;
        private ScheduledFuture<?> _future;

        /** state of the current event.  All access should be under lock. */
        protected TimedEventState _state;
        /** absolute time this event should run next time. LOCKING: this */
        private long _nextRun;
        /** whether this was scheduled during RUNNING state.  LOCKING: this */
        private boolean _rescheduleAfterRun;
        /** whether this was cancelled during RUNNING state.  LOCKING: this */
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
         * Set reschedule threshold in ms. Rescheduling is skipped if the
         * existing and new timeouts differ by less than this value.
         * Default 100ms.
         */
        public synchronized void setFuzz(int fuzz) {
            _fuzz = fuzz;
        }

        /**
         * Set the timer pool. Must be called before schedule() for events created
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
     * @return the timer name
     */
    @Override
    public String toString() {
        return _name;
    }

    /** warning - slow */
    private String debug() {
        _executor.purge();  // Remove cancelled tasks from the queue so we get a good queue size stat
        return
            "\n* Pool: " + _name +
            "; Active: " + _executor.getActiveCount() + '/' + _executor.getPoolSize() +
            "; Completed: " + _executor.getCompletedTaskCount() +
            "; Queued: " + _executor.getQueue().size();
    }

}
