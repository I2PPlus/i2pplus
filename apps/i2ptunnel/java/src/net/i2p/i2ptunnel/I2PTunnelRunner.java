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
 * I2P and TCP network connections. It spawns two StreamForwarder threads:
 * one for each direction of communication (I2P to TCP and TCP to I2P).
 * </p>
 * <p>
 * <b>Connection Flow:</b>
 * <ol>
 *   <li>Runner is created with connected I2PSocket and TCP Socket</li>
 *   <li>Initial data may be sent immediately via initialI2PData/initialSocketData</li>
 *   <li>Two StreamForwarder threads are spawned for bidirectional streaming</li>
 *   <li>Runner monitors both connections for errors or disconnection</li>
 *   <li>On completion or failure, callbacks may be invoked and sockets are closed</li>
 * </ol>
 * </p>
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
    static int MAX_PACKET_SIZE = 4 * 1024;
    static final int NETWORK_BUFFER_SIZE = MAX_PACKET_SIZE * 8;
    private final Socket s;
    private final I2PSocket i2ps;
    private final Object slock, finishLock = new Object();
    private volatile boolean finished;
    private final byte[] initialI2PData;
    private final byte[] initialSocketData;
    /** when runner started up */
    private final long startedOn;
    private final List<I2PSocket> sockList;
    /** if we die before receiving any data, run this job */
    private final Runnable onTimeout;
    private final FailCallback _onFail;
    private SuccessCallback _onSuccess;
    // does not include initialI2PData
    private long totalSent;
    // does not include initialSocketData
    private long totalReceived;
    // not final, may be changed by extending classes
    protected volatile boolean _keepAliveI2P, _keepAliveSocket;
    // Track socket streams for cleanup
    private InputStream _socketIn;
    private StreamForwarder toI2P;
    private StreamForwarder fromI2P;

    /**
     *  For use in new constructor
     *  @since 0.9.14
     */
    public interface FailCallback {
        /**
         *  @param e may be null
         */
        public void onFail(Exception e);
    }

    /**
     * Callback interface for successful tunnel operation completion.
     *  @since 0.9.39
     */
    public interface SuccessCallback {public void onSuccess();}

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
        this(s, i2ps, slock, initialI2PData, null, sockList, null, null, true);
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
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, null, true);
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
        this(s, i2ps, slock, initialI2PData, null, sockList, onTimeout, null, true);
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
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, onTimeout, null, true);
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
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, onFail, false);
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
     *  @since 0.9.62
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
                            byte[] initialSocketData, List<I2PSocket> sockList, Runnable onTimeout,
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
     *  @since 0.9.62
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
            setName("I2PTunnelRunner " + _runnerId);
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
     * @since 0.9.39
     */
    public void setSuccessCallback(SuccessCallback sc) {
        _onSuccess = sc;
    }

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
     * @since 0.9.62
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
     * @since 0.9.62
     */
    boolean getKeepAliveSocket() {return _keepAliveSocket;}

    /**
     * The DoneCallback for the I2P socket.
     *
     * @since 0.9.62
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

    private static final byte[] POST = { 'P', 'O', 'S', 'T', ' ' };
    private static final byte[] PUT = { 'P', 'U', 'T', ' ' };

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

            //new BufferedOutputStream(i2ps.getOutputStream(), MAX_PACKET_SIZE);
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
                if (initialI2PData.length <= 1730) {  // ConnectionOptions.DEFAULT_MAX_MESSAGE_SIZE
                    // Don't flush if POST, so we can get POST data into the initial packet
                    if (initialI2PData.length < 5 || !(DataHelper.eq(POST, 0, initialI2PData, 0, 5) ||
                        DataHelper.eq(PUT, 0, initialI2PData, 0, 4)))
                        i2pout.flush();
                }
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
                // Store reference for cleanup
                _socketIn = in;
                toI2P = new StreamForwarder(in, i2pout, true, null);
                toI2P.start();
            }
            fromI2P = new StreamForwarder(i2pin, out, false, _onSuccess);
            // We are already a thread, so run the second one inline
            //fromI2P.start();
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
                // Run even if totalSent > 0, as that's probably POST data.
                // This will be run even if initialSocketData != null, it's the timeout job's
                // responsibility to know that and decide whether or not to write to the socket.
                // HTTPClient never sets initialSocketData.
                if (_onFail != null) {
                    Exception e = fromI2P.getFailure();
                    if (e == null && toI2P != null) {e = toI2P.getFailure();}
                    _onFail.onFail(e);
                } else {onTimeout.run();}
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
        } catch (IOException ex) {
            if (_log.shouldLog(Log.DEBUG)) {_log.debug("Error forwarding (" + ex.getMessage() + ")");}
            _keepAliveI2P = false;
            _keepAliveSocket = false;
        } catch (IllegalStateException ise) {
            if (_log.shouldWarn()) {_log.warn("gnu?", ise);}
            _keepAliveI2P = false;
            _keepAliveSocket = false;
        } catch (RuntimeException e) {
            if (_log.shouldLog(Log.ERROR)) {_log.error("Internal error", e);}
            _keepAliveI2P = false;
            _keepAliveSocket = false;
        } finally {
            removeRef();
            if (i2pReset) {
                if (_log.shouldInfo()) {_log.warn("Received I2P reset, resetting socket...");}
                try {s.setSoLinger(true, 0);}
                catch (IOException ioe) {}
                try {s.close();}
                catch (IOException ioe) {}
                try {i2ps.close();}
                catch (IOException ioe) {}
                _keepAliveI2P = false;
                _keepAliveSocket = false;
            } else if (sockReset) {
                if (_log.shouldInfo()) {_log.warn("Received socket reset, resetting I2P socket...");}
                try {i2ps.reset();}
                catch (IOException ioe) {}
                try {s.close();}
                catch (IOException ioe) {}
                _keepAliveI2P = false;
                _keepAliveSocket = false;
            } else {
                // Now one connection is dead - kill the other as well, after making sure we flush
                try {close(out, in, i2pout, i2pin, s, i2ps, toI2P, fromI2P);}
                catch (InterruptedException ie) {}
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
            catch (IOException ioe) {}
        }
        if (i2pout != null) {
            try {i2pout.flush();}
            catch (IOException ioe) {}
        }
        if (in != null) {
            try {in.close();}
            catch (IOException ioe) {}
        }
        if (i2pin != null) {
            try {i2pin.close();}
            catch (IOException ioe) {}
        }
        // There's a race here in theory, if data comes in after flushing and before closing, but it's better than before...
        try {s.close();}
        catch (IOException ioe) {}
        try {i2ps.close();}
        catch (IOException ioe) {}
        if (t1 != null) {t1.join(30*1000);}
    }

    private void removeRef() {
        if (sockList != null) {
            synchronized (slock) {sockList.remove(i2ps);}
        }
    }

    /**
     *  Forward data in one direction
     */
    private class StreamForwarder extends I2PAppThread {

        private final InputStream in;
        private final OutputStream out;
        private final String direction;
        private final boolean _toI2P;
        private final ByteCache _cache;
        private final SuccessCallback _callback;
        private volatile Exception _failure;
        public boolean done; // Does not need to be volatile, will be set from same thread

        /**
         *  Does not start itself. Caller must start()
         *  @param cb may be null, only used for toI2P == false
         */
        public StreamForwarder(InputStream in, OutputStream out, boolean toI2P, SuccessCallback cb) {
            this.in = in;
            this.out = out;
            _toI2P = toI2P;
            _callback = cb;
            direction = (toI2P ? "[To I2P]" : "[From I2P]");
            _cache = ByteCache.getInstance(32, NETWORK_BUFFER_SIZE);
            if (toI2P) {setName("StreamForwarder " + _runnerId + '.' + direction);}
        }

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
                        out.write(buffer, 0, len);
                        if (_toI2P) {totalSent += len;}
                        else {
                            if (totalReceived == 0 && _callback != null) {_callback.onSuccess();}
                            totalReceived += len;
                        }
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
                // Handle other IO errors
            } finally {
                _cache.release(ba);
                boolean keepAliveFrom, keepAliveTo;
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
         *  @since 0.9.14
         */
        public Exception getFailure() {return _failure;}
    }

    /**
     * Deprecated, unimplemented in streaming, never called.
     * @deprecated unused
     */
    @Deprecated
    public void errorOccurred() {
        synchronized (finishLock) {
            finished = true;
            finishLock.notifyAll();
        }
    }

}
