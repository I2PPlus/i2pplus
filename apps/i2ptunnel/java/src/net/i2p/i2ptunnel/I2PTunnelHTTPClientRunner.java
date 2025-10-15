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

import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.I2PAppThread;

/**
 * HTTP client runner that filters HTTP response headers to enforce Connection: close header,
 * ensuring browsers handle connections as non-persistent even if servers don't comply.
 * Warning - this class is internal and not a stable external API.
 */
public class I2PTunnelHTTPClientRunner extends I2PTunnelRunner {
    private HTTPResponseOutputStream _hout;
    private final boolean _isHead;

    /**
     * Constructs the runner.
     *
     * @param s local socket (browser side)
     * @param i2ps connected I2P socket
     * @param slock synchronization lock
     * @param initialI2PData initial data to send over I2P
     * @param sockList list of I2P sockets currently active
     * @param onFail callback for failure events
     * @param allowKeepAliveI2P if true, optionally keep I2P socket alive after response
     * @param allowKeepAliveSocket if true, optionally keep browser socket alive after response
     * @param isHead true if responding to an HTTP HEAD request (no body expected)
     * @throws IllegalArgumentException if keep-alive socket is false but keep-alive I2P is true
     */
    public I2PTunnelHTTPClientRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
        List<I2PSocket> sockList, FailCallback onFail, boolean allowKeepAliveI2P, boolean allowKeepAliveSocket,
        boolean isHead) {
        super(s, i2ps, slock, initialI2PData, null, sockList, onFail, allowKeepAliveI2P, allowKeepAliveSocket);
        if (allowKeepAliveI2P && !allowKeepAliveSocket)
            throw new IllegalArgumentException("Cannot keep I2P alive if browser socket is not kept alive");
        _isHead = isHead;
    }

    /**
     * Returns a filtered HTTP response OutputStream that enforces Connection: close header.
     * Should be invoked only once.
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

    // Condensed example of stream close with helper method
    private void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try { resource.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void close(OutputStream out, InputStream in, OutputStream i2pout, InputStream i2pin,
                         Socket s, I2PSocket i2ps, Thread t1, Thread t2) throws InterruptedException {
        final boolean keepaliveSocket = getKeepAliveSocket();
        final boolean keepaliveI2P = getKeepAliveI2P();
        final boolean threadI2PClose = keepaliveSocket && !keepaliveI2P && i2pout != null && !i2ps.isClosed();

        if (_log.shouldInfo()) {
            _log.info(String.format("Closing HTTPClientRunner keepaliveI2P? %b keepaliveSocket? %b threadedClose? %b",
                        keepaliveI2P, keepaliveSocket, threadI2PClose), new Exception("I did it"));
        }

        if (threadI2PClose) {
            I2PSocketCloser t = new I2PSocketCloser(i2pin, i2pout, i2ps);
            TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
            if (tcg != null) {
                try { tcg.getClientExecutor().execute(t); } catch (RejectedExecutionException ignored) {}
            } else {
                t.start();
            }
        } else {
            if (!keepaliveI2P) closeQuietly(i2pin);
            try {
                if (keepaliveI2P) i2pout.flush();
                else closeQuietly(i2pout);
            } catch (IOException ignored) {}
        }

        if (!keepaliveSocket) closeQuietly(in);
        try {
            if (keepaliveSocket) out.flush();
            else closeQuietly(out);
        } catch (IOException ignored) {}

        if (!threadI2PClose && !keepaliveI2P) closeQuietly(i2ps);
        if (!keepaliveSocket) closeQuietly(s);
        if (t1 != null) t1.join(30_000);
    }

    private class I2PSocketCloser extends I2PAppThread {
        private final InputStream in;
        private final OutputStream out;
        private final I2PSocket s;

        public I2PSocketCloser(InputStream i2pin, OutputStream i2pout, I2PSocket i2ps) {
            in = i2pin;
            out = i2pout;
            s = i2ps;
        }

        @Override
        public void run() {
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(s);
        }
    }
}
