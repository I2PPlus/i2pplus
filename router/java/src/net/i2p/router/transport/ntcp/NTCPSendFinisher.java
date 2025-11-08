package net.i2p.router.transport.ntcp;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.router.OutNetMessage;
import net.i2p.util.Log;

/**
 * Handles asynchronous post-send processing of OutNetMessage using a
 * fixed-size thread pool executor with a bounded queue and backpressure.
 * Replaces previous abuse of SimpleTimer with efficient, lockless execution.
 */
class NTCPSendFinisher {
    private static final int MIN_THREADS = 1;
    private static final int MAX_THREADS = 2;
    private static final int QUEUE_CAPACITY = 1000;
    private final I2PAppContext _context;
    private final NTCPTransport _transport;
    private final Log _log;
    private static final AtomicInteger _count = new AtomicInteger();
    private ThreadPoolExecutor _executor;
    private static final int THREADS = MAX_THREADS;

    public NTCPSendFinisher(I2PAppContext context, NTCPTransport transport) {
        _context = context;
        _log = _context.logManager().getLog(NTCPSendFinisher.class);
        _transport = transport;
    }

    /**
     * Starts the thread pool executor for processing send finish tasks.
     */
    public synchronized void start() {
        if (_executor == null || _executor.isShutdown() || _executor.isTerminated()) {
            _executor = new CustomThreadPoolExecutor(THREADS);
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
     * Adds a message to the finishing queue to call afterSend asynchronously.
     * If the executor is stopped or saturated, falls back to caller running the task.
     */
    public void add(OutNetMessage msg) {
        if (_executor == null || _executor.isShutdown() || _executor.isTerminated()) {
            _log.warn("NTCP Send Finisher not running, invoking afterSend inline");
            _transport.afterSend(msg, true, false, msg.getSendTime());
            return;
        }
        try {
            _executor.execute(new RunnableEvent(msg));
        } catch (RejectedExecutionException ree) {
            // Pool saturated or shutdown, fallback to caller thread for backpressure
            _log.warn("NTCP Send Finisher saturated, running afterSend inline");
            _transport.afterSend(msg, true, false, msg.getSendTime());
        }
    }

    private static class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        public CustomThreadPoolExecutor(int num) {
            super(num, num, 10_000, TimeUnit.MILLISECONDS,
                  new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                  new CustomThreadFactory(),
                  new ThreadPoolExecutor.CallerRunsPolicy());
        }
    }

    private static class CustomThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName("NTCPTXFinis " + _count.incrementAndGet() + '/' + THREADS);
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
            try {
                _transport.afterSend(_msg, true, false, _msg.getSendTime());
            } catch (Throwable t) {
                _log.log(Log.CRIT, "afterSend failed", t);
            }
        }
    }
}