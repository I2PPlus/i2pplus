package net.i2p.router.transport.ntcp;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.I2PAppContext;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Tuner;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Handles asynchronous post-send processing of OutNetMessage using a
 * dynamically resizable thread pool executor with a bounded queue and backpressure.
 * Provides efficient message serialization and prevents resource exhaustion.
 *
 * @since 0.9.16
 */
class NTCPSendFinisher {
    private static volatile int _maxThreads = SystemVersion.isSlow() ? 2 : 3;
    private static volatile int _queueCapacity = SystemVersion.isSlow() ? 1000 : 4096;
    private final I2PAppContext _context;
    private final NTCPTransport _transport;
    private final Log _log;
    private static final AtomicInteger _count = new AtomicInteger();
    private ThreadPoolExecutor _executor;

    private static final long[] RATES = {60*1000L, 10*60*1000L, 60*60*1000L};

    public NTCPSendFinisher(I2PAppContext context, NTCPTransport transport) {
        _context = context;
        _log = _context.logManager().getLog(NTCPSendFinisher.class);
        _transport = transport;
        context.statManager().createRequiredRateStat("ntcp.sendFinisher.queueSize",
            "NTCP send finisher pending tasks", "Transport [NTCP]", RATES);
        context.statManager().createRequiredRateStat("ntcp.sendFinisher.threads",
            "NTCP send finisher thread count", "Transport [NTCP]", RATES);
    }

    /**
     * Returns the current max send finisher threads.
     * @since 0.9.70+
     */
    public static int getMaxThreads() { return _maxThreads; }

    /**
     * Sets the max send finisher threads for new instances.
     * @since 0.9.70+
     */
    public static void setMaxThreads(int threads) {
        _maxThreads = Math.max(2, Math.min(16, threads));
    }

    /**
     * Returns the current send finisher queue capacity.
     * @since 0.9.70+
     */
    public static int getQueueCapacity() { return _queueCapacity; }

    /**
     * Sets the send finisher queue capacity for new instances.
     * @since 0.9.70+
     */
    public static void setQueueCapacity(int capacity) {
        _queueCapacity = Math.max(256, Math.min(16384, capacity));
    }

    /**
     * Starts the thread pool executor for processing send finish tasks.
     */
    public synchronized void start() {
        if (_executor == null || _executor.isShutdown() || _executor.isTerminated()) {
            _executor = new CustomThreadPoolExecutor(_maxThreads);
        }
    }

    /**
     * Stops the thread pool executor, waiting briefly for termination.
     */
    public synchronized void stop() {
        if (_executor != null) {
            _executor.shutdownNow();
            try {
                if (!_executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    _log.warn("NTCP Send Finisher did not terminate promptly");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                _log.warn("Interrupted while waiting for NTCP Send Finisher shutdown");
            }
            _executor = null;
        }
    }

    /**
     * Adjusts the thread pool size for the running executor.
     * If the pool is not running, this is a no-op (new size takes effect on next start).
     *
     * @param newMax the new max thread count
     * @since 0.9.70+
     */
    public synchronized void adjustThreads(int newMax) {
        ThreadPoolExecutor executor = _executor;
        if (executor != null && !executor.isShutdown() && !executor.isTerminated()) {
            executor.setCorePoolSize(newMax);
            executor.setMaximumPoolSize(newMax);
            _context.statManager().addRateData("ntcp.sendFinisher.threads", newMax);
        }
    }

    /**
     * Returns the current queue size (pending send-finish tasks).
     *
     * @return current queue depth, or 0 if not running
     * @since 0.9.70+
     */
    public int getQueueSize() {
        ThreadPoolExecutor executor = _executor;
        if (executor == null || executor.isShutdown()) return 0;
        return executor.getQueue().size();
    }

    /**
     * Returns the number of threads currently processing tasks.
     *
     * @return active thread count, or 0 if not running
     * @since 0.9.70+
     */
    public int getActiveCount() {
        ThreadPoolExecutor executor = _executor;
        if (executor == null || executor.isShutdown()) return 0;
        return executor.getActiveCount();
    }

    /**
     * Returns the current pool size (total threads).
     *
     * @return pool size, or 0 if not running
     * @since 0.9.70+
     */
    public int getPoolSize() {
        ThreadPoolExecutor executor = _executor;
        if (executor == null || executor.isShutdown()) return 0;
        return executor.getPoolSize();
    }

    /**
     * Get send finisher pool utilization as a ratio (0.0-1.0).
     * Returns NaN if pool not started.
     *
     * @since 0.9.70+
     */
    public double getUtilization() {
        ThreadPoolExecutor executor = _executor;
        if (executor == null || executor.isShutdown()) return Double.NaN;
        int size = executor.getPoolSize();
        return size > 0 ? (double) executor.getActiveCount() / size : Double.NaN;
    }

    /**
     * Adds a message to the finishing queue to call afterSend asynchronously.
     * If the executor is stopped or saturated, falls back to caller running the task.
     */
    public void add(OutNetMessage msg) {
        ThreadPoolExecutor executor;
        synchronized(this) {
            executor = _executor;
        }
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            _log.warn("NTCP Send Finisher not running, invoking afterSend inline");
            _transport.afterSend(msg, true, false, msg.getSendTime());
            return;
        }
        try {
            executor.execute(new RunnableEvent(msg));
            _context.statManager().addRateData("ntcp.sendFinisher.queueSize", executor.getQueue().size());
        } catch (RejectedExecutionException ree) {
            // Pool saturated or shutdown, fallback to caller thread for backpressure
            _log.warn("NTCP Send Finisher saturated, running afterSend inline");
            _transport.afterSend(msg, true, false, msg.getSendTime());
        }
    }

    private static class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        public CustomThreadPoolExecutor(int num) {
            super(num, num, 10_000, TimeUnit.MILLISECONDS,
                  new LinkedBlockingQueue<>(_queueCapacity),
                  new CustomThreadFactory(),
                  new ThreadPoolExecutor.CallerRunsPolicy());
        }
    }

    private static class CustomThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName("NTCPTXFinis." + _count.incrementAndGet());
            rv.setDaemon(true);
            return rv;
        }
    }

    /**
     * Executes the transport's afterSend callback for the message.
     */
    private class RunnableEvent implements Runnable {
        private final OutNetMessage _msg;

        public RunnableEvent(OutNetMessage msg) {
            _msg = msg;
        }

        public void run() {
            Tuner.adjustHandlerPriority();
            try {
                _transport.afterSend(_msg, true, false, _msg.getSendTime());
            } catch (Exception t) {
                _log.log(Log.CRIT, "afterSend failed", t);
            }
        }
    }
}
