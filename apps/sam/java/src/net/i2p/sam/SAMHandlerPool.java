package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Shared NIO selector-based handler pool for SAM v3 control connections.
 * <p>
 * Replaces the thread-per-connection model for SAM v3 handlers with a single
 * selector thread reading all handler sockets and dispatching complete command
 * lines inline. Commands are processed serially per-connection in the order
 * they arrive. PING/PONG keepalive and idle timeout are handled at the pool
 * level so individual handlers do not consume threads while waiting for input.
 *
 * @since 0.9.62
 */
class SAMHandlerPool {

    /** Time to wait for a pong before disconnecting. */
    private static final int PONG_TIMEOUT = 3 * 60 * 1000;
    /** Time allowed for the first command line. */
    private static final int FIRST_READ_TIMEOUT = 60 * 1000;
    /** Interval between idle connection checks. */
    private static final int IDLE_CHECK_INTERVAL = 30 * 1000;

    /** Logger for the SAM handler pool. */
    private final Log _log;
    /** Selector watching the client connections. */
    private final Selector _selector;
    /** Pool running the command handlers. */
    private final ThreadPoolExecutor _workers;
    /** Pool running the session opens. */
    private final ThreadPoolExecutor _connectors;
    /** Pool running the session closes. */
    private final ThreadPoolExecutor _teardowns;
    /** Connection state, by channel. */
    private final Map<SocketChannel, ConnContext> _contexts;
    /** True while the pool is running. */
    private volatile boolean _running;
    /** The thread running the selector loop. */
    private I2PAppThread _selectThread;

    /**
     * Thread factory for worker threads with sequential numbering.
     */
    private static class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
        /** Names the handler threads. */
        private NamedThreadFactory() {}
        /** Open client connections counted. */
        private final AtomicInteger _count = new AtomicInteger();
        /**
         * Starts a named daemon thread for the password-authentication worker.
         */
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "SAM-PWkr." + _count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Per-connection context tracked by the pool.
     */
    private static class ConnContext {
        /** The handler serving this connection. */
        final SAMv3Handler handler;
        /** Buffer accumulating a partial command line. */
        final StringBuilder buf = new StringBuilder(1024);
        /** True once the first command line arrived. */
        boolean gotFirstLine;
        /** When data last arrived from the client. */
        long lastDataTime;
        /** When a ping was last sent to the client. */
        long lastPingTime;
        /** When the connection opened. */
        long created;

        /**
         * Builds the state for one client connection.
         *
         * @param handler the handler serving the connection
         */
        ConnContext(SAMv3Handler handler) {
            this.handler = handler;
            this.created = System.currentTimeMillis();
        }
    }

    /**
     * SAM ping command prefix.
     *
     * @since 0.9.63
     */
    private static final String PING_PREFIX = "PING ";
    /**
     * SAM pong reply prefix.
     *
     * @since 0.9.63
     */
    private static final String PONG_PREFIX = "PONG ";
    /**
     * SAM line terminator.
     *
     * @since 0.9.63
     */
    private static final String CRLF = "\n";

    /** Create the pool. */
    SAMHandlerPool() {
        _log = I2PAppContext.getGlobalContext().logManager().getLog(SAMHandlerPool.class);
        try {
            _selector = Selector.open();
        } catch (IOException ioe) {
            throw new RuntimeException("Cannot open selector", ioe);
        }
        _workers = new ThreadPoolExecutor(
            2, 4,
            15, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(256),
            new NamedThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
        _connectors = new ThreadPoolExecutor(
            4, 4,
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new java.util.concurrent.ThreadFactory() {
                private final AtomicInteger _count = new AtomicInteger();
                /**
                 * Starts a named daemon thread for a client connection.
                 */
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "SAM-Conn." + _count.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });
        // Handler teardowns get their own pool. A teardown runs
        // SAMv3Handler.die(), which waits up to 10 seconds for a session's
        // threads to exit, and the connector threads are the ones a user-facing
        // STREAM CONNECT or FORWARD is waiting on - sharing them let four slow
        // sessions block every new connection behind them. The queue is
        // unbounded, as for the connectors, so the only rejection the selector
        // thread can see is after stop(), and the depth is bounded by how many
        // clients the pool is holding sockets for.
        _teardowns = new ThreadPoolExecutor(
            2, 2,
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new java.util.concurrent.ThreadFactory() {
                private final AtomicInteger _count = new AtomicInteger();
                /**
                 * Starts a named daemon thread for a handler teardown.
                 */
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "SAM-Teardown." + _count.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });
        _contexts = new HashMap<SocketChannel, ConnContext>();
    }

    /**
     * Register a v3 handler with the pool. The pool will read commands
     * from the handler's socket and dispatch them via processLine().
     *
     * @param handler non-null
     */
    void register(SAMv3Handler handler) {
        SocketChannel ch = handler.getClientSocket();
        ConnContext ctx = new ConnContext(handler);
        synchronized (_contexts) {
            _contexts.put(ch, ctx);
        }
        try {
            ch.configureBlocking(false);
            ch.register(_selector, SelectionKey.OP_READ, ctx);
            _selector.wakeup();
        } catch (ClosedChannelException cce) {
            _log.error("Channel closed before registration", cce);
        } catch (IOException ioe) {
            _log.error("Cannot configure non-blocking", ioe);
        }
    }

    /**
     * Hand a handler teardown to a pool that is allowed to block, instead of
     * running it wherever the disconnect was noticed.
     * <p>
     * stopHandling() runs SAMv3Handler.die(), which waits up to 10 seconds for a
     * session's threads to exit. The selector thread is shared by every pooled
     * connection, so running a teardown on it stalls all of them behind one slow
     * client. Extracted so the hand-off itself is testable.
     *
     * @param pool the executor to hand the teardown to
     * @param teardown the teardown to run
     * @return true if the pool accepted it; false if it was rejected, in which
     *         case the caller must still run the teardown somewhere
     * @since 0.9.72+
     */
    static boolean queueTeardown(Executor pool, Runnable teardown) {
        try {
            pool.execute(teardown);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /**
     * Run a handler teardown off the thread that noticed the disconnect, on the
     * pool built for that work - not on the connector pool, whose four threads
     * are what a user-facing STREAM CONNECT or FORWARD waits on.
     * <p>
     * Falls back to running the teardown on the caller's thread only when the
     * teardown pool has already shut down and there is nowhere left to hand it
     * to. See {@link #queueTeardown(Executor, Runnable)} for why the hand-off
     * matters.
     *
     * @param teardown the teardown to run
     * @since 0.9.72+
     */
    void runTeardown(Runnable teardown) {
        if (queueTeardown(_teardowns, teardown))
            return;
        if (_log.shouldWarn())
            _log.warn("Teardown rejected -> Closing handler on the selector thread");
        teardown.run();
    }

    /**
     * Remove a handler's socket from the pool. Called from stopHandling()
     * or when the socket is stolen.
     *
     * @param handler non-null
     */
    void unregister(SAMv3Handler handler) {
        SocketChannel ch = handler.getClientSocket();
        synchronized (_contexts) {
            _contexts.remove(ch);
        }
        SelectionKey key = ch.keyFor(_selector);
        if (key != null) {
            key.cancel();
            _selector.wakeup();
        }
    }

    /**
     * Start the selector thread.
     */
    void start() {
        _running = true;
        _selectThread = new I2PAppThread(new SelectorRunnable(), "SAM-PoolSel");
        _selectThread.start();
    }

    /**
     * Stop the pool, interrupt selector, wait for workers.
     */
    void stop() {
        _running = false;
        if (_selectThread != null)
            _selectThread.interrupt();
        _workers.shutdown();
        _connectors.shutdown();
        _teardowns.shutdown();
        try {
            _workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            _workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            _connectors.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            _connectors.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            _teardowns.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            _teardowns.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            _selector.close();
        } catch (IOException ioe) {
            _log.error("Error closing selector", ioe);
        }
        _contexts.clear();
    }

    /**
     * Number of handlers currently registered (connected).
     * @return the number of connected SAM handler contexts currently registered
     * @since 0.9.70+
     */
    int getRegisteredCount() {
        synchronized (_contexts) { return _contexts.size(); }
    }

    /**
     * Number of worker threads actively processing a command.
     * @return the number of worker threads currently running a command
     * @since 0.9.70+
     */
    int getActiveCount() { return _workers.getActiveCount(); }

    /**
     * Current worker pool size (may include idle threads).
     * @return the number of worker threads created, including those now idle
     * @since 0.9.70+
     */
    int getPoolSize() { return _workers.getPoolSize(); }

    /**
     * Number of commands waiting in the worker queue.
     * @return the number of commands waiting in the worker's queue
     * @since 0.9.70+
     */
    int getQueueSize() { return _workers.getQueue().size(); }

    /**
     * Thread pool for blocking STREAM CONNECT operations.
     * Fixed pool of 4 daemon threads with an unbounded work queue.
     * Limits concurrent connects to prevent overwhelming the streaming layer
     * while keeping pool workers unblocked. Excess CONNECTs queue up and are
     * dispatched as connector threads become available.
     * <p>
     * Handler teardowns do not run here - they have their own pool, because a
     * teardown waits up to 10 seconds for a session's threads and would take a
     * connect slot with it.
     *
     * @return the shared pool of four daemon threads that run blocking STREAM CONNECT operations
     * @since 0.9.70+
     */
    ThreadPoolExecutor getConnectors() { return _connectors; }

    /**
     * Thread pool that runs handler teardowns off the selector thread.
     * Fixed pool of 2 daemon threads with an unbounded work queue, sized apart
     * from the connector pool so a slow die() cannot stall new connections.
     * runTeardown() hands its teardowns here, so this is the one place that
     * pairing is stated.
     *
     * @return the pool teardowns are handed to
     * @since 0.9.72+
     */
    ThreadPoolExecutor getTeardowns() { return _teardowns; }

    /**
     * NIO selector loop. Reads available data from all registered handler
     * sockets, splits on newlines, dispatches complete lines to workers,
     * and handles idle timeouts and PING/PONG.
     */
    private class SelectorRunnable implements Runnable {
        /**
         * Selects over registered channels and dispatches their lines.
         */
        /** Runs the selector loop for one handler pool. */
        private SelectorRunnable() {}
        public void run() {
            while (_running) {
                try {
                    int n = _selector.select(IDLE_CHECK_INTERVAL);
                    if (!_running) break;

                    if (n > 0) {
                        Set<SelectionKey> keys = _selector.selectedKeys();
                        Iterator<SelectionKey> iter = keys.iterator();
                        while (iter.hasNext()) {
                            SelectionKey key = iter.next();
                            iter.remove();
                            if (!key.isValid()) continue;
                            ConnContext ctx = (ConnContext) key.attachment();
                            if (ctx == null) continue;
                            try {
                                readFromChannel(ctx, (SocketChannel) key.channel());
                            } catch (IOException ioe) {
                                disconnect(ctx, "read error: " + ioe.getMessage());
                            }
                        }
                    }

                    // idle timeouts and PING
                    long now = System.currentTimeMillis();
                    List<ConnContext> toDisconnect = new ArrayList<ConnContext>(4);
                    synchronized (_contexts) {
                        for (Map.Entry<SocketChannel, ConnContext> entry : _contexts.entrySet()) {
                            ConnContext ctx = entry.getValue();
                            if (ctx.handler.sendPorts) {
                                // Only PING handlers that own neither a full
                                // session (session owners) nor a streaming
                                // session (STREAM CONNECT/ACCEPT/FORWARD).
                                // Control and stream handlers may have clients
                                // that don't respond to PING.
                                if (ctx.handler.getSession() == null &&
                                    ctx.handler.getStreamSession() == null) {
                                    if (ctx.lastPingTime == 0) {
                                        // No PING sent yet — send one after idle interval
                                        long idle = ctx.lastDataTime == 0 ?
                                                    now - ctx.created : now - ctx.lastDataTime;
                                        if (idle >= PONG_TIMEOUT) {
                                            sendPing(ctx, now);
                                        }
                                    } else if (now - ctx.lastPingTime >= PONG_TIMEOUT) {
                                        // PING sent, no PONG within timeout
                                        toDisconnect.add(ctx);
                                    }
                                }
                            } else if (!ctx.gotFirstLine && now - ctx.created >= FIRST_READ_TIMEOUT) {
                                toDisconnect.add(ctx);
                            }
                        }
                        for (ConnContext ctx : toDisconnect) {
                            _contexts.remove(ctx.handler.getClientSocket());
                        }
                    }
                    for (ConnContext ctx : toDisconnect) {
                        String reason = ctx.handler.sendPorts ? "PONG timeout" : "command timeout, bye";
                        ctx.handler.writeString(SAMv1Handler.SESSION_ERROR, reason);
                        closeOffSelector(ctx);
                    }
                } catch (IOException ioe) {
                    if (_running) _log.error("Selector error", ioe);
                }
            }
        }

        /**
         * Read available data from a channel, split into lines,
         * and dispatch complete lines. PING/PONG are handled inline;
         * other commands are dispatched to the worker pool.
         *
         * @param ctx the connection's state
         * @param ch the channel to read from
         * @throws IOException if the read fails
         */
        private void readFromChannel(ConnContext ctx, SocketChannel ch) throws IOException {
            ByteBuffer tmp = ByteBuffer.allocate(2048);
            int read = ch.read(tmp);
            if (read < 0) {
                disconnect(ctx, "socket closed by client");
                return;
            }
            ctx.lastDataTime = System.currentTimeMillis();
            tmp.flip();
            while (tmp.hasRemaining()) {
                char c = (char) tmp.get();
                if (c == '\n') {
                    String line = ctx.buf.toString();
                    ctx.buf.setLength(0);
                    ctx.gotFirstLine = true;
                    dispatchLine(ctx, line);
                } else if (c != '\r') {
                    ctx.buf.append(c);
                }
            }
        }

        /**
         * Dispatch a complete command line to a worker thread.
         * All commands from the same handler are synchronized on the handler
         * instance to ensure serial per-connection execution. Commands from
         * different connections run in parallel.
         *
         * @param ctx the connection's state
         * @param line the complete command line to dispatch
         */
        private void dispatchLine(final ConnContext ctx, final String line) {
            if (line.equals("PING") || line.startsWith(PING_PREFIX) ||
                line.equals("PONG") || line.startsWith(PONG_PREFIX)) {
                handlePing(ctx, line);
                return;
            }
            try {
                _workers.execute(new Runnable() {
                    /**
                     * Hands one complete SAM command line to its handler.
                     */
                    @Override
                    public void run() {
                        synchronized (ctx.handler) {
                            try {
                                ctx.handler.processLine(line);
                            } catch (RuntimeException e) {
                                _log.error("Error processing SAM command: " + line, e);
                                ctx.handler.writeString("SESSION STATUS RESULT=I2P_ERROR", "internal error");
                                ctx.handler.stopHandling();
                            }
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                if (_log.shouldWarn())
                    _log.warn("Pool overloaded, disconnecting client");
                ctx.handler.writeString("SESSION STATUS RESULT=I2P_ERROR MESSAGE=\"server busy\"\n");
                closeOffSelector(ctx);
            }
        }

        /**
         * Handle a PING or PONG from the client inline.
         *
         * @param ctx the connection's state
         * @param line the PING or PONG line received
         */
        private void handlePing(ConnContext ctx, String line) {
            if (line.startsWith("PING")) {
                String msg = line.length() > 5 ? line.substring(5) : "";
                SAMHandler.writeString("PONG" + (msg.isEmpty() ? "" : " " + msg) + CRLF,
                                       ctx.handler.getClientSocket());
            } else {
                ctx.lastPingTime = 0;
            }
        }

        /**
         * Send a PING to the client and record the timestamp.
         *
         * @param ctx the connection's state
         * @param now the current time, in ms
         */
        private void sendPing(ConnContext ctx, long now) {
            if (!SAMHandler.writeString(PING_PREFIX + now + '\n', ctx.handler.getClientSocket())) {
                disconnect(ctx, "PING write failed");
                return;
            }
            ctx.lastPingTime = now;
        }

        /**
         * Remove the context from the pool and stop the handler.
         * Called when a read error or timeout is detected.
         *
         * @param ctx the connection's state
         * @param reason why the connection is being dropped, for the log
         */
        private void disconnect(ConnContext ctx, String reason) {
            synchronized (_contexts) {
                _contexts.remove(ctx.handler.getClientSocket());
            }
            if (_log.shouldWarn())
                _log.warn("Disconnecting SAM client: " + reason);
            closeOffSelector(ctx);
        }

        /**
         * Hand this context's teardown to the pool built for that work, so the
         * selector thread stays free for the other connections it serves.
         *
         * @param ctx the connection's state
         */
        private void closeOffSelector(ConnContext ctx) {
            runTeardown(() -> ctx.handler.stopHandling());
        }
    }
}
