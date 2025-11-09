/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * HTTP client runner that filters HTTP response headers to enforce the
 * "Connection: close" header, ensuring browsers treat the connection as non-persistent,
 * even if the server doesn't comply.
 *
 * <p>This class is internal and not part of a stable public API.
 *
 * <p>Implements proper resource cleanup, supports optional keep-alive for both I2P and browser sockets,
 * and uses background threading for non-blocking socket closure when appropriate.
 *
 * <p><b>Thread Safety:</b> This class is not thread-safe. It is expected to be used by a single connection thread.
 */
public class I2PTunnelHTTPClientRunner extends I2PTunnelRunner {
    private static final int CLOSE_SOCKET_TIMEOUT = 5000;
    private static final int SOCKET_CLOSE_DELAY = 100;

    private HTTPResponseOutputStream _hout;
    private final boolean _isHead;

    /**
     * Constructs a new HTTP client runner.
     *
     * @param s local socket (browser side)
     * @param i2ps connected I2P socket
     * @param slock synchronization lock for shared resources
     * @param initialI2PData initial data to send over I2P
     * @param sockList list of active I2P sockets
     * @param onFail callback for failure events
     * @param allowKeepAliveI2P if true, optionally keep I2P socket alive after response
     * @param allowKeepAliveSocket if true, optionally keep browser socket alive after response
     * @param isHead true if responding to an HTTP HEAD request (no body expected)
     * @throws IllegalArgumentException if keep-alive I2P is requested but browser socket keep-alive is disabled
     */
    public I2PTunnelHTTPClientRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                                     List<I2PSocket> sockList, FailCallback onFail,
                                     boolean allowKeepAliveI2P, boolean allowKeepAliveSocket,
                                     boolean isHead) {
        super(s, i2ps, slock, initialI2PData, null, sockList, onFail, allowKeepAliveI2P, allowKeepAliveSocket);
        if (allowKeepAliveI2P && !allowKeepAliveSocket)
            throw new IllegalArgumentException("Cannot keep I2P alive if browser socket is not kept alive");
        _isHead = isHead;
    }

    /**
     * Returns a filtered HTTP response OutputStream that enforces the "Connection: close" header.
     * Should be called only once per instance.
     *
     * @return an output stream wrapping the socket's output stream that filters HTTP headers
     * @throws IllegalStateException if called more than once
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected OutputStream getSocketOut() throws IOException {
        if (_hout != null)
            throw new IllegalStateException("getSocketOut called multiple times");
        final OutputStream raw = super.getSocketOut();
        _hout = new HTTPResponseOutputStream(raw, super.getKeepAliveI2P(), super.getKeepAliveSocket(), _isHead, this);
        return _hout;
    }

    /**
     * Helper method to close resources quietly.
     */
    private void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {resource.close();}
            catch (Exception ignored) {}
        }
    }

    /**
     * Closes all streams and sockets, respecting keep-alive settings.
     * Optionally closes I2P socket in a background thread if keep-alive is enabled on the browser socket.
     */
    @Override
    protected void close(OutputStream out, InputStream in, OutputStream i2pout, InputStream i2pin,
                         Socket s, I2PSocket i2ps, Thread t1, Thread t2) throws InterruptedException {

        final boolean keepaliveSocket = getKeepAliveSocket();
        final boolean keepaliveI2P = getKeepAliveI2P();
        final boolean threadI2PClose = keepaliveSocket && !keepaliveI2P && i2pout != null && !i2ps.isClosed();

        if (_log.shouldDebug()) {
            _log.debug("Closing HTTPClientRunner: keepaliveI2P=" + keepaliveI2P +
                       ", keepaliveSocket=" + keepaliveSocket +
                       ", threadedClose=" + threadI2PClose);
        }

        // Close I2P resources in background if needed
        if (threadI2PClose) {
            I2PSocketCloser closer = new I2PSocketCloser(i2pin, i2pout, i2ps);
            TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
            if (tcg != null) {
                try {
                    tcg.getClientExecutor().execute(closer);
                } catch (RejectedExecutionException e) {
                    if (_log.shouldWarn()) {
                        _log.warn("Executor rejected I2P socket closer task, falling back to thread", e);
                    }
                    closer.start();
                }
            } else {
                closer.start();
            }
        } else {
            if (!keepaliveI2P) closeQuietly(i2pin);
            try {
                if (keepaliveI2P) {i2pout.flush();}
                else {closeQuietly(i2pout);}
            } catch (IOException ignored) {}
        }

        // Close local socket resources
        if (!keepaliveSocket) {closeQuietly(in);}

        try {
            if (keepaliveSocket) {out.flush();}
            else {
                if (_hout != null) {_hout.flush();} // Ensure all buffered data is written before closing
                closeQuietly(out);
            }
        } catch (IOException ignored) {}

        // Final close of I2P socket and browser socket if not kept alive
        if (!threadI2PClose && !keepaliveI2P) {closeQuietly(i2ps);}

        if (!keepaliveSocket) {closeQuietly(s);}

        // Wait for upstream thread to finish
        if (t1 != null) {
            t1.join(CLOSE_SOCKET_TIMEOUT);
            if (t1.isAlive() && _log.shouldWarn()) {
                _log.warn("Upstream thread did not finish within timeout");
            }
        }
    }

    /**
     * Background thread to close I2P socket without blocking the main thread.
     */
    private class I2PSocketCloser extends I2PAppThread {
        private final InputStream in;
        private final OutputStream out;
        private final I2PSocket s;

        public I2PSocketCloser(InputStream i2pin, OutputStream i2pout, I2PSocket i2ps) {
            this.in = i2pin;
            this.out = i2pout;
            this.s = i2ps;
            setName("SocketCloser");
            setDaemon(true);
        }

        @Override
        public void run() {
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(s);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(SOCKET_CLOSE_DELAY));
        }
    }
}