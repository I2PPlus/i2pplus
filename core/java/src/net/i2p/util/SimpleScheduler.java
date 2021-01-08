package net.i2p.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;

import net.i2p.I2PAppContext;

/**
 * Simple event scheduler - toss an event on the queue and it gets fired at the
 * appropriate time.  The method that is fired however should NOT block (otherwise
 * they b0rk the timer).
 *
 * This is like SimpleTimer but addEvent() for an existing event adds a second
 * job. Unlike SimpleTimer, events cannot be cancelled or rescheduled.
 *
 * For events that cannot or will not be cancelled or rescheduled -
 * for example, a call such as:
 *       SimpleTimer.getInstance().addEvent(new FooEvent(bar), timeoutMs);
 * use SimpleScheduler instead to reduce lock contention in SimpleTimer...
 *
 * For periodic events, use addPeriodicEvent(). Unlike SimpleTimer,
 * uncaught Exceptions will not prevent subsequent executions.
 *
 * @deprecated in 0.9.20, use SimpleTimer2 instead
 *
 * @author zzz
 */
@Deprecated
public class SimpleScheduler {

    /**
     *  If you have a context, use context.simpleScheduler() instead
     *  @deprecated in 0.9.20, replaced by SimpleTimer2
     */
    @Deprecated
    public static SimpleScheduler getInstance() {
        return I2PAppContext.getGlobalContext().simpleScheduler();
    }

    private static final int MIN_THREADS = 2;
    private static final int MAX_THREADS = 4;
    private final Log _log;
    private final ScheduledThreadPoolExecutor _executor;
    private final String _name;
    private int _count;
    private final int _threads;

    /**
     *  To be instantiated by the context.
     *  Others should use context.simpleTimer() instead
     *  @deprecated in 0.9.20, replaced by SimpleTimer2
     */
    @Deprecated
    public SimpleScheduler(I2PAppContext context) {
        this(context, "SimpleScheduler");
    }

    /**
     *  To be instantiated by the context.
     *  Others should use context.simpleTimer() instead
     *  @deprecated in 0.9.20, replaced by SimpleTimer2
     */
    @Deprecated
    private SimpleScheduler(I2PAppContext context, String name) {
        _log = context.logManager().getLog(SimpleScheduler.class);
        _name = name;
        long maxMemory = SystemVersion.getMaxMemory();
        _threads = (int) Math.max(MIN_THREADS, Math.min(MAX_THREADS, 1 + (maxMemory / (32*1024*1024))));
        _executor = new ScheduledThreadPoolExecutor(_threads, new CustomThreadFactory());
        _executor.prestartAllCoreThreads();
        // don't bother saving ref to remove hook if somebody else calls stop
        context.addShutdownTask(new Shutdown());
    }

    /**
     * @since 0.8.8
     */
    private class Shutdown implements Runnable {
        public void run() {
            stop();
        }
    }

    /**
     * Stops the SimpleScheduler.
     * Subsequent executions should not throw a RejectedExecutionException.
     */
    public void stop() {
        _executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        _executor.shutdownNow();
    }

    /**
     * Queue up the given event to be fired no sooner than timeoutMs from now.
     *
     * @param event
     * @param timeoutMs
     */
    public void addEvent(SimpleTimer.TimedEvent event, long timeoutMs) {
        if (event == null)
            throw new IllegalArgumentException("addEvent null");
        RunnableEvent re = new RunnableEvent(event, timeoutMs);
        re.schedule();
    }

    /**
     * Queue up the given event to be fired after timeoutMs and every
     * timeoutMs thereafter. The TimedEvent must not do its own rescheduling.
     * As all Exceptions are caught in run(), these will not prevent
     * subsequent executions (unlike SimpleTimer, where the TimedEvent does
     * its own rescheduling).
     */
    public void addPeriodicEvent(SimpleTimer.TimedEvent event, long timeoutMs) {
        addPeriodicEvent(event, timeoutMs, timeoutMs);
    }

    /**
     * Queue up the given event to be fired after initialDelay and every
     * timeoutMs thereafter. The TimedEvent must not do its own rescheduling.
     * As all Exceptions are caught in run(), these will not prevent
     * subsequent executions (unlike SimpleTimer, where the TimedEvent does
     * its own rescheduling)
     *
     * @param event
     * @param initialDelay (ms)
     * @param timeoutMs
     */
    public void addPeriodicEvent(SimpleTimer.TimedEvent event, long initialDelay, long timeoutMs) {
        if (event == null)
            throw new IllegalArgumentException("addEvent null");
        RunnableEvent re = new PeriodicRunnableEvent(event, initialDelay, timeoutMs);
        re.schedule();
    }

    private class CustomThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName(_name +  ' ' + (++_count) + '/' + _threads);
// Uncomment this to test threadgrouping, but we should be all safe now that the constructor preallocates!
//            String name = rv.getThreadGroup().getName();
//            if(!name.equals("main")) {
//                (new Exception("OWCH! DAMN! Wrong ThreadGroup `" + name +"', `" + rv.getName() + "'")).printStackTrace();
//            }
            rv.setPriority(Thread.MAX_PRIORITY - 1);
            rv.setDaemon(true);
            return rv;
        }
    }

    /**
     * Same as SimpleTimer.TimedEvent but use run() instead of timeReached(), and remembers the time
     */
    private class RunnableEvent implements Runnable {
        protected final SimpleTimer.TimedEvent _timedEvent;
        protected long _scheduled;

        public RunnableEvent(SimpleTimer.TimedEvent t, long timeoutMs) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Creating with delay of " + timeoutMs + " : " + t);
            _timedEvent = t;
            _scheduled = timeoutMs + System.currentTimeMillis();
        }
        public void schedule() {
            _executor.schedule(this, _scheduled - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }
        public void run() {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Running: " + _timedEvent);
            long before = System.currentTimeMillis();
            if (_log.shouldLog(Log.WARN) && before < _scheduled - 100)
                _log.warn(_name + " early execution " + (_scheduled - before) + ": " + _timedEvent);
            else if (_log.shouldLog(Log.WARN) && before > _scheduled + 1000)
                _log.warn("Late execution (" + (before - _scheduled) + "ms): " + _timedEvent + debug());
            try {
                _timedEvent.timeReached();
            } catch (Throwable t) {
                _log.log(Log.CRIT, _name + ": Scheduled task " + _timedEvent + " exited unexpectedly, please report", t);
            }
            long time = System.currentTimeMillis() - before;
            if (time > 1000 && _log.shouldLog(Log.WARN))
                _log.warn(_name + " event execution took " + time + "ms: " + _timedEvent);
            if (_log.shouldLog(Log.INFO)) {
                 // this call is slow - iterates through a HashMap -
                 // would be better to have a local AtomicLong if we care
                 long completed = _executor.getCompletedTaskCount();
                 if (completed % 250 == 0)
                     _log.info(debug());
            }
        }
    }

    /** Run every timeoutMs. TimedEvent must not do its own reschedule via addEvent() */
    private class PeriodicRunnableEvent extends RunnableEvent {
        private final long _timeoutMs;
        private final long _initialDelay;
        public PeriodicRunnableEvent(SimpleTimer.TimedEvent t, long initialDelay, long timeoutMs) {
            super(t, timeoutMs);
            _initialDelay = initialDelay;
            _timeoutMs = timeoutMs;
            _scheduled = initialDelay + System.currentTimeMillis();
        }
        @Override
        public void schedule() {
            _executor.scheduleWithFixedDelay(this, _initialDelay, _timeoutMs, TimeUnit.MILLISECONDS);
        }
        @Override
        public void run() {
            super.run();
            _scheduled = _timeoutMs + System.currentTimeMillis();
        }
    }

    private String debug() {
        return
            "\n* Pool: " + _name +
            "; Active: " + _executor.getActiveCount() + '/' + _executor.getPoolSize() +
            "; Completed: " + _executor.getCompletedTaskCount() +
            "; Queued: " + _executor.getQueue().size();
    }
}

