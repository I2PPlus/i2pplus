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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Shared NIO selector-based handler pool for SAM v3 control connections.
 *
 * Replaces the thread-per-connection model for SAM v3 handlers with a single
 * selector thread reading all handler sockets and dispatching complete command
 * lines to a small worker thread pool. PING/PONG keepalive and idle timeout
 * are handled at the pool level so individual handlers do not consume threads
 * while waiting for input.
 *
 * @since 0.9.62
 */
class SAMHandlerPool {

    private static final int PING_INTERVAL = 3 * 60 * 1000;
    private static final int PONG_TIMEOUT = 3 * 60 * 1000;
    private static final int FIRST_READ_TIMEOUT = 60 * 1000;
    private static final int IDLE_CHECK_INTERVAL = 30 * 1000;

    private final Log _log;
    private final Selector _selector;
    private final ThreadPoolExecutor _workers;
    private final Map<SocketChannel, ConnContext> _contexts;
    private volatile boolean _running;
    private I2PAppThread _selectThread;

    /**
     * Thread factory for worker threads with sequential numbering.
     */
    private static class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger _count = new AtomicInteger();
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "SAM-PoolWkr-" + _count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Per-connection context tracked by the pool.
     */
    private static class ConnContext {
        final SAMv3Handler handler;
        final StringBuilder buf = new StringBuilder(1024);
        boolean gotFirstLine;
        long lastDataTime;
        long lastPingTime;
        long created;

        ConnContext(SAMv3Handler handler) {
            this.handler = handler;
            this.created = System.currentTimeMillis();
        }
    }

    SAMHandlerPool() {
        _log = I2PAppContext.getGlobalContext().logManager().getLog(SAMHandlerPool.class);
        try {
            _selector = Selector.open();
        } catch (IOException ioe) {
            throw new RuntimeException("Cannot open selector", ioe);
        }
        _workers = new ThreadPoolExecutor(
            2, 4,
            60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(8),
            new NamedThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy());
        _workers.prestartCoreThread();
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
        try {
            _workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            _workers.shutdownNow();
        }
        try {
            _selector.close();
        } catch (IOException ioe) {
            _log.error("Error closing selector", ioe);
        }
        _contexts.clear();
    }

    /**
     * NIO selector loop. Reads available data from all registered handler
     * sockets, splits on newlines, dispatches complete lines to workers,
     * and handles idle timeouts and PING/PONG.
     */
    private class SelectorRunnable implements Runnable {
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
                                if (ctx.lastDataTime == 0) {
                                    // no data ever received, check absolute timeout
                                    if (now - ctx.created >= PONG_TIMEOUT) {
                                        sendPing(ctx, now);
                                    }
                                } else if (ctx.lastPingTime == 0) {
                                    // data was received but no PING sent yet
                                    if (now - ctx.lastDataTime >= PONG_TIMEOUT) {
                                        sendPing(ctx, now);
                                    }
                                } else if (ctx.lastDataTime < ctx.lastPingTime) {
                                    // PING sent, waiting for PONG
                                    if (now - ctx.lastPingTime >= PONG_TIMEOUT) {
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
                        ctx.handler.stopHandling();
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
         * Dispatch a complete command line. PING/PONG are handled inline
         * to avoid worker queuing latency; all other commands go to the
         * worker pool.
         */
        private void dispatchLine(final ConnContext ctx, final String line) {
            if (line.equals("PING") || line.startsWith("PING ") ||
                line.equals("PONG") || line.startsWith("PONG ")) {
                handlePing(ctx, line);
                return;
            }
            _workers.execute(new Runnable() {
                public void run() {
                    try {
                        ctx.handler.processLine(line);
                    } catch (RuntimeException e) {
                        _log.error("Error processing SAM command: " + line, e);
                        ctx.handler.writeString("SESSION STATUS RESULT=I2P_ERROR", "internal error");
                        ctx.handler.stopHandling();
                    }
                }
            });
        }

        /**
         * Handle a PING or PONG from the client inline.
         */
        private void handlePing(ConnContext ctx, String line) {
            if (line.startsWith("PING")) {
                // client sent PING, respond with PONG
                String msg = line.length() > 5 ? line.substring(5) : "";
                SAMHandler.writeString("PONG" + (msg.isEmpty() ? "" : " " + msg) + '\n',
                                       ctx.handler.getClientSocket());
            } else {
                // client sent PONG, clear pending ping flag
                ctx.lastPingTime = 0;
            }
        }

        /**
         * Send a PING to the client and record the timestamp.
         */
        private void sendPing(ConnContext ctx, long now) {
            if (!SAMHandler.writeString("PING " + now + '\n', ctx.handler.getClientSocket())) {
                disconnect(ctx, "PING write failed");
                return;
            }
            ctx.lastPingTime = now;
            if (ctx.lastDataTime == 0)
                ctx.lastDataTime = now;
        }

        /**
         * Remove the context from the pool and stop the handler.
         * Called when a read error or timeout is detected.
         */
        private void disconnect(ConnContext ctx, String reason) {
            synchronized (_contexts) {
                _contexts.remove(ctx.handler.getClientSocket());
            }
            if (_log.shouldWarn())
                _log.warn("Disconnecting SAM client: " + reason);
            ctx.handler.stopHandling();
        }
    }
}
