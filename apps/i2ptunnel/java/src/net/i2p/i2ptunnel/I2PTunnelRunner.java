/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.i2ptunnel.util.LimitOutputStream.DoneCallback;
import net.i2p.util.ByteCache;
import net.i2p.util.Clock;
import net.i2p.util.I2PAppThread;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;

/**
 * Thread that forwards traffic between an I2PSocket and a TCP Socket.
 * <p>
 * I2PTunnelRunner implements the core bidirectional data forwarding between
 * I2P and TCP network connections. Operates two StreamForwarders: the
 * I2P to TCP direction runs inline and the TCP to I2P direction runs
 * concurrently via a shared executor pool.
 * </p>
 * <p>
 * <b>Connection Flow:</b>
 * <ol>
 *   <li>Runner is created with connected I2PSocket and TCP Socket</li>
 *   <li>Initial data may be sent immediately via initialI2PData/initialSocketData</li>
 *   <li>Two StreamForwarders run for bidirectional streaming; TCP to I2P via executor pool</li>
 *   <li>Runner monitors both connections for errors or disconnection</li>
 *   <li>On completion or failure, callbacks may be invoked and sockets are closed</li>
 * </ol>
 * <p>
 * <b>Keep-Alive Support:</b> When keep-alive is enabled for either connection,
 * the runner may skip spawning one direction of forwarding if no data is expected.
 * This optimization is used for simple GET requests that don't require responses.
 * </p>
 * <p>
 * <b>Thread Safety:</b> This class uses locks (slock) to coordinate socket access
 * and prevent concurrent writes from multiple threads. The finishLock ensures
 * thread-safe state transitions.
 * </p>
 *
 * @see #toI2P
 * @see #fromI2P
 * @see I2PTunnelServer
 */
public class I2PTunnelRunner extends I2PAppThread implements I2PSocket.SocketErrorListener, DoneCallback {
    protected final Log _log;
    private static final AtomicLong __runnerId = new AtomicLong();
    private final long _runnerId;
    /**
     * Max bytes streamed in a packet - smaller ones might be filled up to this size.
     * Larger ones are not split (at least not on Sun's impl of BufferedOutputStream),
     * but that is the streaming api's job...
     */
    static final int MAX_PACKET_SIZE = 4 * 1024;
    /** network buffer size for buffered streams */
    static final int NETWORK_BUFFER_SIZE = MAX_PACKET_SIZE * 8;
    /** Plain TCP socket (local or remote endpoint). */
    private final Socket s;
    /** I2P socket (the tunnel connection). Non-final so an "empty response"
     *  reconnect may replace it with a fresh connection while the local browser
     *  socket stays open. Only ever reassigned within {@link #run()} when a
     *  {@link ReconnectCallback} is installed and yields a new connection. */
    private I2PSocket i2ps;
    /** Synchronization lock for socket access. */
    private final Object slock;
    private final Object finishLock = new Object();
    private volatile boolean finished;
    /** Data to send over I2P before normal forwarding starts. */
    private final byte[] initialI2PData;
    /** Data to send over TCP before normal forwarding starts. */
    private final byte[] initialSocketData;
    /** when runner started up */
    private final long startedOn;
    private final List<I2PSocket> sockList;
    /** if we die before receiving any data, run this job */
    private final Runnable onTimeout;
    private final FailCallback _onFail;
    private SuccessCallback _onSuccess;
    /** Optional reconnect callback for the empty-response retry; null = never retry. */
    private ReconnectCallback _reconnectCallback;
    private volatile long totalSent;
    private volatile long totalReceived;
    /** Prevent the no-data failure callback from firing more than once across
     *  the synchronous completion block and the exception/finally paths. */
    private boolean _noDataHandled;
    /** Keep I2P socket alive after data transfer */
    protected volatile boolean _keepAliveI2P;
    /** Keep local socket alive after data transfer */
    protected volatile boolean _keepAliveSocket;
    /** Executor for submitting tasks; null = fallback to new Thread */
    private volatile Executor _runnerExecutor;
    private volatile StreamForwarder toI2P;
    private volatile StreamForwarder fromI2P;

    /**
     *  For use in new constructor
     *
     */
    public interface FailCallback {
        /**
         *  @param e may be null
         */
        public void onFail(Exception e);
    }

    /**
     * Callback interface for successful tunnel operation completion.
     *
     */
    public interface SuccessCallback {
        /** Called on successful completion */
        public void onSuccess();
    }

    /**
     * Callback for reconnect-a-via-a-second route when the first attempt
     * completes with zero upstream bytes.
     *
     * <p>Used by the HTTP client proxy to transparently retry an idempotent
     * GET/HEAD against a fresh I2P connection without tearing down the browser
     * socket (a silent zero-byte close becomes {@code NS_ERROR_NET_EMPTY_RESPONSE}
     * for the browser). The runner keeps the local socket open across attempts;
     * the callback supplies each new connection (or null to stop).
     *
     * @since 0.9.62
     */
    public interface ReconnectCallback {
        /**
         * @param cause the cause of the empty completion, or null
         * @return a freshly connected I2P socket to retry on, or null to give up
         */
        public I2PSocket reconnect(Exception cause);
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData, List<I2PSocket> sockList) {
        this(s, i2ps, slock, initialI2PData, null, sockList, null, null, false, false, true);
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, null, false, false, true);
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           List<I2PSocket> sockList, Runnable onTimeout) {
        this(s, i2ps, slock, initialI2PData, null, sockList, onTimeout, null, false, false, true);
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList, Runnable onTimeout) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, onTimeout, null, false, false, true);
    }

    /**
     *  Recommended new constructor. Does NOT start itself. Caller must call start().
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onFail May be null. If non-null and no data (except initial data) was received,
     *                it will be run before closing s.
     */
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList, FailCallback onFail) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, onFail, false);
    }

    /**
     *  With keepAlive args. Does NOT start itself. Caller must call start().
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onFail May be null. If non-null and no data (except initial data) was received,
     *                it will be run before closing s.
     *  @param keepAliveI2P Do not close the I2P socket when done.
     *  @param keepAliveSocket Do not close the local socket when done.
     *                         For client side only; must be false for server side.
     *                         NO data will be forwarded from the socket to the i2psocket other than
     *                         initialI2PData if this is true.
     *
     */
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList, FailCallback onFail,
                           boolean keepAliveI2P, boolean keepAliveSocket) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, onFail, keepAliveI2P, keepAliveSocket, false);
    }

    /**
     *  Base constructor
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @param onFail Trumps onTimeout
     *  @param shouldStart should thread be started in constructor (bad, false recommended)
     */
    private I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                            byte[] initialSocketData, List<I2PSocket> sockList,
                            FailCallback onFail, boolean shouldStart) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, onFail, false, false, shouldStart);
    }

    /**
     *  Base constructor with keepAlive args
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @param onFail Trumps onTimeout
     *  @param shouldStart should thread be started in constructor (bad, false recommended)
     *  @param keepAliveI2P Do not close the I2P socket when done.
     *  @param keepAliveSocket Do not close the local socket when done.
     *                         For client side only; must be false for server side.
     *                         NO data will be forwarded from the socket to the i2psocket other than
     *                         initialI2PData if this is true.
     *
     */
    private I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                            byte[] initialSocketData, List<I2PSocket> sockList, Runnable onTimeout,
                            FailCallback onFail,
                            boolean keepAliveI2P, boolean keepAliveSocket,
                            boolean shouldStart) {
        this.sockList = sockList;
        this.s = s;
        this.i2ps = i2ps;
        this.slock = slock;
        this.initialI2PData = initialI2PData;
        this.initialSocketData = initialSocketData;
        this.onTimeout = onTimeout;
        _onFail = onFail;
        startedOn = Clock.getInstance().now();
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
        _keepAliveI2P = keepAliveI2P;
        _keepAliveSocket = keepAliveSocket;
        if (_log.shouldLog(Log.INFO)) {_log.info("I2PTunnelRunner started");}
        _runnerId = __runnerId.incrementAndGet();
        if (shouldStart) {
            setName("TunRunner." + _runnerId);
            start();
        }
    }

    /**
     *  Returns the timestamp when this runner started.
     * <p>
     * This value is set at construction time and represents when the runner
     * was created, not when it started executing.
     * </p>
     *
     * @return the timestamp in milliseconds since epoch when this runner was created
     */
    public long getStartedOn() {return startedOn;}

    /**
     *  Sets a callback to be invoked on successful data transfer.
     * <p>
     * The callback is invoked after the first byte of data is received from
     * the destination, not when the entire transfer completes. Only one of
     * SuccessCallback, onTimeout, or onFail will be called.
     * </p>
     *
     * @param sc the callback to invoke on success, may be null
     *
     */
    public void setSuccessCallback(SuccessCallback sc) {
        _onSuccess = sc;
    }

    /**
     *  Set the reconnect callback used for the empty-response retry.
     *
     *  <p>When set, and this is a no-request-body transfer (GET/HEAD, i.e. the
     *  {@code toI2P} forwarder was never started) that completes with zero
     *  upstream bytes, {@link #run()} will call the callback to obtain a fresh
     *  I2P socket and re-drive the request on it, keeping the local browser
     *  socket open. Only the {@link #onNoDataFailure(Exception)} path triggers a reconnect;
     *  a genuine non-empty failure, a reset, or a {@code totalReceived > 0}
     *  completion never does.
     *
     *  @param rc the callback, or null to disable reconnects
     *  @since 0.9.62
     */
    public void setReconnectCallback(ReconnectCallback rc) {
        _reconnectCallback = rc;
    }

    /**
     *  Set the executor for submitting forwarder tasks.
     *  When null (default), forwarders use a fallback thread.
     */
    public void setExecutor(Executor exec) { _runnerExecutor = exec; }

    /**
     *  Gets the TCP socket input stream.
     * <p>
     * This method is protected to allow subclasses to override socket access
     * for testing or special handling (e.g., SSL unwrapping).
     * </p>
     *
     * @return the TCP socket's input stream
     * @throws IOException if the socket is closed
     */
    protected InputStream getSocketIn() throws IOException { return s.getInputStream(); }

    /**
     *  Gets the TCP socket output stream.
     *
     * @return the TCP socket's output stream
     * @throws IOException if the socket is closed
     */
    protected OutputStream getSocketOut() throws IOException { return s.getOutputStream(); }

    /**
     *  Checks if the I2P socket should be kept open after data transfer.
     * <p>
     * On the client side, this is true only if the browser and server both
     * support HTTP keep-alive. On the server side, it's true only if the
     * client supports keep-alive.
     * </p>
     *
     * @return true if the I2P socket should remain open for reuse
     *
     */
    boolean getKeepAliveI2P() {return _keepAliveI2P;}

    /**
     *  Checks if the local socket should be kept open after data transfer.
     * <p>
     * Usually true for client-side connections (browser to proxy).
     * Always false for server-side connections (I2P to local service).
     * </p>
     *
     * @return true if the local socket should remain open for reuse
     *
     */
    boolean getKeepAliveSocket() {return _keepAliveSocket;}

    /**
     * The DoneCallback for the I2P socket.
     *
     *
     */
    public void streamDone() {
        if (_keepAliveSocket && fromI2P != null) {
            // we are client-side
            // tell the from-I2P runner
            if (_log.shouldInfo()) {
                _log.info("I2P client stream closed by peer -> Total received: " + totalReceived + " bytes");
            }
            fromI2P.done = true;
        } else if (_keepAliveI2P && toI2P != null) {
            // we are server-side - tell the to-I2P runner
            if (_log.shouldInfo()) {
                _log.info("I2P server stream closed by peer -> Total sent: " + totalSent + " bytes");
            }
            toI2P.done = true;
        } else {
            if (_log.shouldInfo()) {_log.info("I2P stream closed prematurely");}
        }
    }

    /**
     *  Invoke the no-data failure callback when a transfer completes (or aborts)
     *  without delivering any bytes from the I2P peer.
     *
     *  <p>This is the single choke point behind the "empty response" class of bugs:
     *  without it, a connection that is established to the proxy but never yields an
     *  upstream byte (server unresponsive, reset before first data, or the executor
     *  rejecting the forwarder) would end by closing the local socket with nothing
     *  written, which the browser reports as {@code NS_ERROR_NET_EMPTY_RESPONSE}.
     *  The HTTP client runner wires its {@link FailCallback} to a handler that
     *  writes a well-formed HTTP error page to the browser socket before closing;
     *  this is what turns a silent empty close into a surfaced 5xx. The base
     *  implementation simply dispatches to the configured {@link FailCallback} or
     *  {@link #onTimeout}.
     *
     *  <p>Safe to call from any completion or exception path; {@link #_noDataHandled}
     *  guarantees the callback runs at most once even when several paths race.
     *  Run even when {@code totalSent > 0} (post body) — the absence of a response
     *  is still a failure. Never run when any upstream bytes were received.
     *
     *  @since 0.9.62
     */
    protected void onNoDataFailure() { onNoDataFailure(null); }

    /**
     *  Invoke the no-data failure callback with an optional cause.
     *
     *  @param e the failure cause, or {@code null} for a clean empty transfer
     *  @since 0.9.62
     */
    protected void onNoDataFailure(Exception e) {
        if (!shouldFireNoDataFailure(totalReceived, _noDataHandled)) {return;}
        synchronized (this) {
            if (_noDataHandled) {return;}
            _noDataHandled = true;
        }
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("No data received from peer" + (e != null ? " (" + e + ")" : "") +
                       " -> invoking failure callback, totalSent=" + totalSent);
        }
        if (_onFail != null) {
            _onFail.onFail(e);
        } else if (onTimeout != null) {
            onTimeout.run();
        }
    }

    /**
     *  Whether a transfer with the given upstream byte count and handling state
     *  should trigger the no-data failure callback.
     *
     *  <p>This is the decision behind the "empty response" fix: the failure callback
     *  must fire iff no upstream bytes were received (an empty transfer is still a
     *  failure even when a POST body was sent upstream) and the callback has not
     *  already fired for this transfer. Extracted as a pure static predicate so the
     *  empty-response contract is unit-testable without a router or live socket.
     *
     *  @param totalReceived upstream bytes received from the I2P peer
     *  @param handled whether the no-data failure has already been signalled
     *  @return true if the failure callback should fire
     *  @since 0.9.62
     */
    static boolean shouldFireNoDataFailure(long totalReceived, boolean handled) {
        return totalReceived <= 0 && !handled;
    }

    /**
     *  Whether an empty-upstream transfer should be retried on a fresh I2P connection.
     *
     *  <p>The HTTP client proxy re-sends an idempotent (GET/HEAD) request against a fresh
     *  I2P connection after a transfer that produced no upstream bytes, instead of closing
     *  the browser socket with nothing (which the browser reports as
     *  {@code NS_ERROR_NET_EMPTY_RESPONSE}). Reconnecting is only justified when the
     *  transfer was genuinely empty: once the peer has reported a complete HTTP response
     *  (even a definitive error such as a 5xx), {@code totalReceived} is positive and a
     *  reconnect is both pointless and harmful - it re-drives a request the outproxy already
     *  answered, contributing to the very congestion behind slow/failed streams. Guarding
     *  on <em>upstream bytes actually received</em> (rather than bytes written to the
     *  browser) is essential: a response whose browser write threw (e.g. {@code Pipe
     *  closed}) still counts as received, so it must terminate, not retry.
     *
     *  @param totalReceived upstream bytes received from the I2P peer since reconnect reset
     *  @param hasReconnectCallback whether a reconnect callback is installed
     *  @param retryableRequest whether the buffered request is an idempotent, body-less GET/HEAD
     *  @return true if the transfer should be retried on a fresh connection
     *  @since 0.9.62
     */
    static boolean shouldReconnectEmptyResponse(long totalReceived, boolean hasReconnectCallback,
                                                boolean retryableRequest) {
        return totalReceived <= 0 && hasReconnectCallback && retryableRequest;
    }

    private static final byte[] GET = { 'G', 'E', 'T', ' ' };
    private static final byte[] HEAD = { 'H', 'E', 'A', 'D', ' ' };
    private static final byte[] POST = { 'P', 'O', 'S', 'T', ' ' };
    private static final byte[] PUT = { 'P', 'U', 'T', ' ' };

    /**
     *  Whether the buffered initial request is safe to re-send on a fresh connection
     *  in the "empty response" retry.
     *
     *  <p>A request is retryable iff it is idempotent and carries no streamed body, so
     *  re-sending it cannot duplicate a submission or split a byte stream mid-body. Only
     *  GET and HEAD qualify: they have no request body by definition and repeating them
     *  is safe. The leading ASCII method token is compared case-insensitively because
     *  {@code initialI2PData} holds the raw request bytes as the browser sent them. This
     *  mirrors the {@link #POST}/{@link #PUT} guard used when deciding whether to flush
     *  the initial packet before the body arrives, and is deliberately independent of the
     *  reconnect callback so a future caller cannot enable re-sends for a POST/PUT.
     *
     *  @param initialData the buffered request (request-line + headers), may be null
     *  @return true if the request starts with {@code GET} or {@code HEAD}
     *  @since 0.9.62
     */
    static boolean isRetryableRequest(byte[] initialData) {
        return startsWithIgnoreCase(initialData, GET) || startsWithIgnoreCase(initialData, HEAD);
    }

    /**
     *  Case-insensitive ASCII prefix match of {@code prefix} against {@code data}.
     *
     *  @param data the bytes to test, may be null
     *  @param prefix the byte sequence to match at the start of {@code data}
     *  @return true if {@code data} is non-null, at least as long as {@code prefix}, and
     *          equals {@code prefix} ignoring ASCII case
     *  @since 0.9.62
     */
    private static boolean startsWithIgnoreCase(byte[] data, byte[] prefix) {
        if (data == null || prefix == null || data.length < prefix.length) {return false;}
        for (int i = 0; i < prefix.length; i++) {
            byte b = data[i];
            // Upper-case an ASCII lower-case letter in place comparison (byte is unsigned).
            if (b >= 'a' && b <= 'z') {b -= 32;}
            if (b != prefix[i]) {return false;}
        }
        return true;
    }

    /**
     * run.
     */
    @Override
    public void run() {
        boolean i2pReset = false;
        boolean sockReset = false;
        InputStream in = null;
        OutputStream out = null;
        InputStream i2pin = null;
        OutputStream i2pout = null;
        try {
            out = getSocketOut();
            i2pin = i2ps.getInputStream();
            i2pout = i2ps.getOutputStream();
            String direction = (toI2P != null ? "[To I2P]" : "[From I2P]");

            if (initialI2PData != null) {
                i2pout.write(initialI2PData);
                /*
                 * Do NOT flush here, it will block and then onTimeout.run() won't happen on fail.
                 * But if we don't flush, then we have to wait for the connectDelay timer to fire
                 * in i2p socket? To be researched and/or fixed.
                 *
                 * AS OF 0.8.1, MessageOutputStream.flush() is fixed to only wait for accept,
                 * not for "completion" (i.e. an ACK from the far end).
                 *
                 * So we now get a fast return from flush(), and can do it here to save 250 ms.
                 * To make sure we are under the initial window size and don't hang waiting for accept,
                 * only flush if it fits in one message.
                 */
                // Don't flush if POST, so we can get POST data into the initial packet
                if (initialI2PData.length <= 1730 && (initialI2PData.length < 5 ||
                    !(DataHelper.eq(POST, 0, initialI2PData, 0, 5) ||
                      DataHelper.eq(PUT, 0, initialI2PData, 0, 4))))
                    i2pout.flush();
            }
            if (initialSocketData != null) {out.write(initialSocketData);} // this does not increment totalReceived
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Initial data -> " + (initialI2PData != null ? initialI2PData.length : 0)
                           + " bytes written to I2P, " + (initialSocketData != null ? initialSocketData.length : 0)
                           + " bytes written to the socket, starting forwarders...");

            }
            if (_keepAliveSocket) {
                // Standard GET or HEAD, no data, do not thread a forwarder because we don't need it
                // and we don't want it to swallow the next request
            } else {
                in = getSocketIn();
                // InternalSocket already has buffering
                if (!(s instanceof InternalSocket)) {in = new BufferedInputStream(in, 2*NETWORK_BUFFER_SIZE);}
                toI2P = new StreamForwarder(in, i2pout, true, null);
                Executor exec = _runnerExecutor;
                if (exec != null) {
                    try {
                        exec.execute(toI2P);
                    } catch (RejectedExecutionException ree) {
                        // All runner threads busy - drop this connection cleanly instead of
                        // proceeding half-forwarded (the old code let the REE escape and the
                        // generic catch in run() logged "Internal error").
                        if (_log.shouldWarn())
                            _log.warn(direction + " Connection dropped: client pool saturated");
                        onNoDataFailure(ree);
                        try {i2ps.close();} catch (IOException ioe) {}
                        try {s.close();} catch (IOException ioe) {}
                        return;
                    }
                } else {
                    Thread t = new Thread(toI2P, "TunFwdI2P." + _runnerId);
                    t.setDaemon(true);
                    t.start();
                }
            }
            fromI2P = new StreamForwarder(i2pin, out, false, _onSuccess);
            // We are already a thread, so run the second one inline
            fromI2P.run();
            synchronized (finishLock) {
                long endTime = System.currentTimeMillis() + 2*60*1000; // 120 second timeout
                while (!finished) {
                    long remaining = endTime - System.currentTimeMillis();
                    if (remaining <= 0) {
                        // Timeout reached
                        if (_log.shouldLog(Log.WARN)) {
                            _log.warn(direction + " Timeout waiting for completion - forcing cleanup");
                        }
                        finished = true;
                        finishLock.notifyAll();
                        break;
                    }
                    try {
                        // Wait for the remaining time or up to 5 seconds, whichever is smaller
                        finishLock.wait(Math.min(remaining, 5000));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Both forwarders completed -> " + totalSent + " bytes sent, " +  totalReceived + " bytes received");
            }

            // This task is useful for the httpclient
            if ((onTimeout != null || _onFail != null) && totalReceived <= 0) {
                // "Empty response" retry: if a reconnect callback is installed and the
                // buffered request is idempotent (GET/HEAD only, verified by
                // isRetryableRequest() so a misconfigured callback can never re-send a
                // POST/PUT body), a transfer that completed with zero upstream bytes is
                // retried against a fresh I2P connection on the SAME browser socket
                // instead of surfacing an NS_ERROR_NET_EMPTY_RESPONSE close. This applies
                // to both keepalive-browser GET/HEAD (no toI2P forwarder) and
                // non-keepalive GET/HEAD (a toI2P forwarder ran but carried no body). We
                // loop as long as the callback offers a new connection, bailing to the
                // normal failure path once it declines (budget exhausted) or a re-drive
                // errors.
                while (shouldReconnectEmptyResponse(totalReceived, _reconnectCallback != null, isRetryableRequest(initialI2PData))) {
                    Exception e = fromI2P.getFailure();
                    if (e == null && toI2P != null) {e = toI2P.getFailure();}
                    I2PSocket fresh = _reconnectCallback.reconnect(e);
                    if (fresh == null) {break;}
                    // The dead connection is done; retire it from the shared socket list
                    // and swap in the fresh one the callback obtained.
                    //
                    // A non-keepalive GET/HEAD may have started a browser->I2P forwarder; it is
                    // deliberately left untouched. For a body-less GET/HEAD that forwarder is
                    // blocked on the browser-input read (the request was already consumed into
                    // initialI2PData), so it is inert and will not write to the closing socket
                    // or interfere with the re-send. Stopping it would be actively harmful:
                    // its finally() closes the browser input stream when !_keepAliveSocket,
                    // which would sever the very browser socket we are trying to keep open.
                    if (sockList != null) {synchronized (slock) {sockList.remove(i2ps);}}
                    try {i2ps.close();} catch (IOException ioe) {/* ignored */}
                    i2ps = fresh;
                    i2pin = i2ps.getInputStream();
                    i2pout = i2ps.getOutputStream();
                    if (initialI2PData != null) {
                        i2pout.write(initialI2PData);
                        // isRetryableRequest() guarantees no POST/PUT body, so a flush is safe.
                        i2pout.flush();
                    }
                    if (_log.shouldInfo()) {
                        _log.info("Empty upstream response, reconnected and re-sending on a fresh I2P socket");
                    }
                    // Re-drive the receive forwarder inline; totalReceived is updated by it.
                    totalReceived = 0;
                    finished = false;
                    fromI2P = new StreamForwarder(i2pin, out, false, _onSuccess);
                    fromI2P.run();
                    synchronized (finishLock) {
                        long endTime = System.currentTimeMillis() + 2*60*1000;
                        while (!finished) {
                            long remaining = endTime - System.currentTimeMillis();
                            if (remaining <= 0) {finished = true; finishLock.notifyAll(); break;}
                            try {finishLock.wait(Math.min(remaining, 5000));}
                            catch (InterruptedException ie) {Thread.currentThread().interrupt(); finished = true; break;}
                        }
                    }
                }
                if (totalReceived <= 0) {
                    Exception e = fromI2P.getFailure();
                    onNoDataFailure(e);
                }
            } else {
                // Detect a reset on one side, and propagate to the other
                Exception e1 = fromI2P.getFailure();
                Exception e2 = toI2P != null ? toI2P.getFailure() : null;
                Throwable c1 = e1 != null ? e1.getCause() : null;
                Throwable c2 = e2 != null ? e2.getCause() : null;
                if (c1 != null && c1 instanceof I2PSocketException) {
                    I2PSocketException ise = (I2PSocketException) c1;
                    int status = ise.getStatus();
                    i2pReset = status == I2PSocketException.STATUS_CONNECTION_RESET;
                }
                if (!i2pReset && c2 != null && c2 instanceof I2PSocketException) {
                    I2PSocketException ise = (I2PSocketException) c2;
                    int status = ise.getStatus();
                    i2pReset = status == I2PSocketException.STATUS_CONNECTION_RESET;
                }
                if (!i2pReset && e1 != null && e1 instanceof SocketException) {
                    String msg = e1.getMessage();
                    sockReset = msg != null && msg.contains("reset");
                }
                if (!sockReset && e2 != null && e2 instanceof SocketException) {
                    String msg = e2.getMessage();
                    sockReset = msg != null && msg.contains("reset");
                }
            }

        } catch (SSLException she) {
            _log.error("SSL error", she);
            _keepAliveI2P = false;
            _keepAliveSocket = false;
            onNoDataFailure(she);
        } catch (IOException ex) {
            if (_log.shouldLog(Log.DEBUG)) {_log.debug("Error forwarding (" + ex.getMessage() + ")");}
            _keepAliveI2P = false;
            _keepAliveSocket = false;
            onNoDataFailure(ex);
        } catch (IllegalStateException ise) {
            if (_log.shouldWarn()) {_log.warn("gnu?", ise);}
            _keepAliveI2P = false;
            _keepAliveSocket = false;
            onNoDataFailure(ise);
        } catch (RuntimeException e) {
            if (_log.shouldLog(Log.ERROR)) {_log.error("Internal error", e);}
            _keepAliveI2P = false;
            _keepAliveSocket = false;
            onNoDataFailure(e);
        } finally {
            removeRef();
            if (i2pReset) {
                if (_log.shouldInfo()) {_log.warn("Received I2P reset, resetting socket...");}
                try {s.setSoLinger(true, 0);}
                catch (IOException ioe) { /* ignored */ }
                try {s.close();}
                catch (IOException ioe) { /* ignored */ }
                try {i2ps.close();}
                catch (IOException ioe) { /* ignored */ }
                _keepAliveI2P = false;
                _keepAliveSocket = false;
            } else if (sockReset) {
                if (_log.shouldInfo()) {_log.warn("Received socket reset, resetting I2P socket...");}
                try {i2ps.reset();}
                catch (IOException ioe) { /* ignored */ }
                try {s.close();}
                catch (IOException ioe) { /* ignored */ }
                _keepAliveI2P = false;
                _keepAliveSocket = false;
            } else {
                // Now one connection is dead - kill the other as well, after making sure we flush
                try {close(out, in, i2pout, i2pin, s, i2ps, null, null);}
                catch (InterruptedException ie) {Thread.currentThread().interrupt(); /* ignored */ }
            }
        }
    }

    /**
     *  Warning - overridden in I2PTunnelHTTPClientRunner.
     *  Here we ignore keepalive and always close both sides.
     *  The HTTP flavor handles keepalive.
     *
     *  @param out may be null
     *  @param in may be null
     *  @param i2pout may be null
     *  @param i2pin may be null
     *  @param t1 may be null
     *  @param t2 may be null, ignored, we only join t1
     */
    protected void close(OutputStream out, InputStream in, OutputStream i2pout, InputStream i2pin,
                         Socket s, I2PSocket i2ps, Thread t1, Thread t2) throws InterruptedException {
        if (out != null) {
            try {out.flush();}
            catch (IOException ioe) { /* ignored */ }
        }
        if (i2pout != null) {
            try {i2pout.flush();}
            catch (IOException ioe) { /* ignored */ }
        }
        if (in != null) {
            try {in.close();}
            catch (IOException ioe) { /* ignored */ }
        }
        if (i2pin != null) {
            try {i2pin.close();}
            catch (IOException ioe) { /* ignored */ }
        }
        // There's a race here in theory, if data comes in after flushing and before closing, but it's better than before...
        try {s.close();}
        catch (IOException ioe) { /* ignored */ }
        try {i2ps.close();}
        catch (IOException ioe) { /* ignored */ }
        if (t1 != null) {t1.join((long) 30*1000);}
    }

    /**
     *  Remove this runner's I2PSocket from the shared socket list.
     */
    private void removeRef() {
        if (sockList != null) {
            synchronized (slock) {sockList.remove(i2ps);}
        }
    }

    /**
     *  Forward data in one direction between two streams.
     *  Reads from the input stream and writes to the output stream
     *  until the stream is closed or an error occurs.
     */
    private class StreamForwarder implements Runnable {

        private final InputStream in;
        private final OutputStream out;
        private final String direction;
        private final boolean _toI2P;
        private final ByteCache _cache;
        private final SuccessCallback _callback;
        private volatile Exception _failure;
        /** flag to signal this forwarder should stop */
        public volatile boolean done;

        /**
         *  @param cb may be null, only used for toI2P == false
         */
        public StreamForwarder(InputStream in, OutputStream out, boolean toI2P, SuccessCallback cb) {
            this.in = in;
            this.out = out;
            _toI2P = toI2P;
            _callback = cb;
            direction = (toI2P ? "[To I2P]" : "[From I2P]");
            _cache = ByteCache.getInstance(32, NETWORK_BUFFER_SIZE);
        }

        /**
         * run.
         */
        @Override
        public void run() {
            String from = i2ps.getThisDestination().calculateHash().toBase64().substring(0,8);
            String to = i2ps.getPeerDestination().calculateHash().toBase64().substring(0,8);

            if (_log.shouldLog(Log.DEBUG)) {_log.debug(direction + " Forwarding between [" + from + "] and [" + to + "]");}

            ByteArray ba = _cache.acquire();
            byte[] buffer = ba.getData();
            try {
                int len;
                while (!done && (len = in.read(buffer)) != -1) {
                    if (len > 0) {
                        if (_toI2P) {totalSent += len;}
                        else {
                            if (totalReceived == 0 && _callback != null) {_callback.onSuccess();}
                            // Count the upstream bytes BEFORE the browser write. If the
                            // browser socket is already closed (e.g. Pipe closed under a
                            // congested stream), the write throws and the bytes would
                            // otherwise be lost from totalReceived. A real upstream
                            // response (even a 502) is not an "empty transfer" - recording
                            // it here stops the empty-response reconnect loop from treating
                            // a definitive answer as a retryable no-data failure.
                            totalReceived += len;
                        }
                        out.write(buffer, 0, len);
                    }
                    try {
                        if (in.available() == 0) {out.flush();}
                    } catch (IOException ioex) {
                        // Ignore flush errors
                    }
                }
            } catch (SocketException ex) {
                // This *will* occur when other threads closes the socket
                if (_log.shouldDebug()) {
                    boolean fnshd;
                    synchronized (finishLock) {fnshd = finished;}
                    if (!fnshd) {_log.debug(direction + " IO Error: Error forwarding -> " + ex.getMessage());}
                    else {_log.debug(direction + " IO Error caused by other direction -> " + ex.getMessage());}
                }
                _failure = ex;
                // Force cleanup to prevent stuck threads
                synchronized (finishLock) {
                    finished = true;
                    finishLock.notifyAll();
                }
            } catch (IOException ex) {
                if (_log.shouldWarn())
                    _log.warn(direction + " IO Error: " + ex);
                _failure = ex;
                synchronized (finishLock) {
                    finished = true;
                    finishLock.notifyAll();
                }
            } finally {
                _cache.release(ba);
                boolean keepAliveFrom;
                boolean keepAliveTo;
                if (_toI2P) {
                    keepAliveFrom = _keepAliveSocket;
                    keepAliveTo = _keepAliveI2P;
                } else {
                    keepAliveFrom = _keepAliveI2P;
                    keepAliveTo = _keepAliveSocket;
                }
                if (_log.shouldLog(Log.INFO)) {
                    _log.info(direction + " Done forwarding " + (_toI2P ? totalSent : totalReceived) + " bytes from [" + from + "] " +
                              (keepAliveFrom ? "(KeepAlive)" : "") + " to [" + to + "] " + (keepAliveTo ? "(KeepAlive)" : ""));
                }
                if (!keepAliveFrom) {
                    try {in.close();}
                    catch (IOException ex) {
                        if (_log.shouldWarn()) {_log.warn(direction + " Error closing input stream (" + ex.getMessage() + ")");}
                    }
                }

                try {
                    /*
                     * Thread must close() before exiting for a PipedOutputStream, or else input end gives up
                     * and we have data loss - techtavern.wordpress.com/2008/07/16/whats-this-ioexception-write-end-dead/
                     *
                     * DON'T close if we have a timeout job and we haven't received anything, or else the timeout job can't
                     * write the error message to the stream.
                     * close() above will close it after the timeout job is run.
                     */
                    if (!((onTimeout != null || _onFail != null) && (!_toI2P) && totalReceived <= 0)) {
                        if (keepAliveTo) {out.flush();}
                        else {out.close();}
                    } else {
                        if (_log.shouldInfo()) {_log.info(direction + " Not closing stream so we can write the error message...");}
                        if (keepAliveTo) {out.flush();}
                    }
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.DEBUG)) {_log.debug(direction + " Error flushing stream before close (" + ioe.getMessage() + ")");}
                }
                synchronized (finishLock) {
                    finished = true;
                    finishLock.notifyAll();
                    // the main thread will close sockets etc. now
                }
            }
        }

        /**
         *
         * @return the failure
         */
        public Exception getFailure() {return _failure;}
    }

    @Override
    public void errorOccurred() {
        synchronized (finishLock) {
            finished = true;
            finishLock.notifyAll();
        }
    }

}
