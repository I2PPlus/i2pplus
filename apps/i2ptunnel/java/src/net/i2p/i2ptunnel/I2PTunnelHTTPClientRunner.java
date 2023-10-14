/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

import net.i2p.client.streaming.I2PSocket;

/**
 * Override the response with a stream filtering the HTTP headers
 * received.  Specifically, this makes sure we get Connection: close,
 * so the browser knows they really shouldn't try to use persistent
 * connections.  The HTTP server *should* already be setting this,
 * since the HTTP headers sent by the browser specify Connection: close,
 * and the server should echo it.  However, both broken and malicious
 * servers could ignore that, potentially confusing the user.
 *
 *  Warning - not maintained as a stable API for external use.
 */
public class I2PTunnelHTTPClientRunner extends I2PTunnelRunner {
    private HTTPResponseOutputStream _hout;
    private final boolean _isHead;

    /**
     *  Does NOT start itself. Caller must call start().
     *
     *  @deprecated use other constructor
     */
    @Deprecated
    public I2PTunnelHTTPClientRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                                     List<I2PSocket> sockList, FailCallback onFail) {
        super(s, i2ps, slock, initialI2PData, null, sockList, onFail);
        _isHead = false;
    }

    /**
     *  Does NOT start itself. Caller must call start().
     *
     *  @param allowKeepAliveSocket we may, but are not required to, keep the browser-side socket alive
     *  @param isHead is this a response to a HEAD, and thus no data is expected (RFC 2616 sec. 4.4)
     *  @since 0.9.61
     */
    public I2PTunnelHTTPClientRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                                     List<I2PSocket> sockList, FailCallback onFail,
                                     boolean allowKeepAliveSocket, boolean isHead) {
        super(s, i2ps, slock, initialI2PData, null, sockList, onFail, false, allowKeepAliveSocket);
        _isHead = isHead;
    }

    /**
     *  Only call once!
     *
     *  @return an HTTPResponseOutputStream
     *  @throws IllegalStateException if called again
     */
    @Override
    protected OutputStream getSocketOut() throws IOException {
        if (_hout != null)
            throw new IllegalStateException("already called");
        OutputStream raw = super.getSocketOut();
        _hout = new HTTPResponseOutputStream(raw, super.getKeepAliveSocket(), _isHead);
        return _hout;
    }

    /**
     * Should we keep the local browser/server socket open when done?
     * @since 0.9.61
     */
    @Override
    boolean getKeepAliveSocket() {
        return _hout != null && _hout.getKeepAliveOut() && super.getKeepAliveSocket();
    }

    /**
     *  Why is this overridden?
     *  Why flush in super but not here?
     *  Why do things in different order than in super?
     *
     *  @param out may be null
     *  @param in may be null
     *  @param i2pout may be null
     *  @param i2pin may be null
     *  @param t1 may be null
     *  @param t2 may be null, ignored, we only join t1
     */
    @Override
    protected void close(OutputStream out, InputStream in, OutputStream i2pout, InputStream i2pin,
                         Socket s, I2PSocket i2ps, Thread t1, Thread t2) throws InterruptedException {
        boolean keepalive = getKeepAliveSocket();
        if (_log.shouldInfo())
            _log.info("Closing HTTPClientRunner -> keepalive? " + keepalive);
        if (i2pin != null) { try {
            i2pin.close();
        } catch (IOException ioe) {} }
        if (i2pout != null) { try {
            i2pout.close();
        } catch (IOException ioe) {} }
        if (!keepalive) {
            if (in != null) { try {
                in.close();
            } catch (IOException ioe) {} }
        }
        if (out != null) { try {
            if (keepalive)
                out.flush();
            else
                out.close();
        } catch (IOException ioe) {} }
        if (!keepalive) {
            try {
                s.close();
            } catch (IOException ioe) {}
         }
         if (t1 != null) {
            t1.setPriority(Thread.MAX_PRIORITY);
            t1.join(30*1000);
        }
    }
}
