package net.i2p.util;

import net.i2p.I2PAppContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;

/**
 *  A simple in-JVM Socket using Piped Streams.
 *  We use port numbers just like regular sockets.
 *  Can only connect to InternalServerSocket.
 *
 * @since 0.7.9
 */
@SuppressWarnings("PMD.CloseResource")
public class InternalSocket extends Socket {
    private InputStream _is;
    private OutputStream _os;
    private final int _port;

    /** server side */
    InternalSocket(InputStream is, OutputStream os) {
        _is = is;
        _os = os;
        _port = 1;
    }

    /**
     *  client side
     *
     *  @param port &gt; 0
     */
    public InternalSocket(int port) throws IOException {
        if (port <= 0) throw new IOException("Bad port number");
        _port = port;
        InternalServerSocket.internalConnect(port, this);
    }

    /**
     *  Convenience method to return either a Socket or an InternalSocket
     *
     *  @param port &gt; 0
     */
    public static Socket getSocket(String host, int port) throws IOException {
        if (I2PAppContext.getGlobalContext().isRouterContext() && (host.equals("127.0.0.1") || host.equals("localhost"))) {
            try {
                return new InternalSocket(port);
            } catch (IOException ioe) { /* ignored */ }
            // guess it wasn't really internal...
        }
        return new Socket(host, port);
    }

    @Override
    public synchronized InputStream getInputStream() {
        return _is;
    }

    @Override
    public synchronized OutputStream getOutputStream() {
        return _os;
    }

    void setInputStream(InputStream is) {
        _is = is;
    }

    void setOutputStream(OutputStream os) {
        _os = os;
    }

    @Override
    public synchronized void close() {
        try {
            if (_is != null) {
                _is.close();
                _is = null;
            }
        } catch (IOException ie) { /* ignored */ }
        try {
            if (_os != null) {
                _os.close();
                _os = null;
            }
        } catch (IOException ie) { /* ignored */ }
    }

    @Override
    public synchronized boolean isClosed() {
        return _is == null || _os == null;
    }

    @Override
    public String toString() {
        return ("Internal socket");
    }

    /**
     *  Supported as of 0.9.34, if constructed with TimeoutPipedInputStream
     *  and TimeoutPipedOutputStream. Otherwise, does nothing.
     *
     *  @see TimeoutPipedInputStream
     */
    @Override
    public synchronized void setSoTimeout(int timeout) {
        if (_is != null && _is instanceof TimeoutPipedInputStream) ((TimeoutPipedInputStream) _is).setReadTimeout(timeout);
    }

    // ignored stuff

    /**
     *  Always returns 0, even if setSoTimeout() was called.
     */
    @Override
    public int getSoTimeout() {
        return 0;
    }

    // everything below here unsupported unless otherwise noted








    /**
     * Supported as of 0.9.33, prior to that threw UnsupportedOperationException
     *
     * @return 1 if connected, -1 if not
     */
    @Override
    public int getLocalPort() {
        return isConnected() ? 1 : -1;
    }



    /**
     * Supported as of 0.9.33, prior to that threw UnsupportedOperationException
     *
     * @return if connected: actual port for clients, 1 for servers; -1 if not
     */
    @Override
    public int getPort() {
        return isConnected() ? _port : 0;
    }





    /**
     * Supported as of 0.9.33, prior to that threw UnsupportedOperationException
     *
     * @return -1 always
     */
    @Override
    public int getSoLinger() {
        return -1;
    }




    /**
     * Supported as of 0.9.33, prior to that threw UnsupportedOperationException
     */
    @Override
    public synchronized boolean isConnected() {
        return _is != null || _os != null;
    }

    /**
     * Supported as of 0.9.33, prior to that threw UnsupportedOperationException
     */
    @Override
    public synchronized boolean isInputShutdown() {
        return _is == null;
    }

    /**
     * Supported as of 0.9.33, prior to that threw UnsupportedOperationException
     */
    @Override
    public synchronized boolean isOutputShutdown() {
        return _os == null;
    }







    /**
     * Does nothing as of 0.9.33, prior to that threw UnsupportedOperationException
     */
    @Override
    public void setSoLinger(boolean on, int linger) {}



    /**
     * Supported as of 0.9.33, prior to that threw UnsupportedOperationException
     */
    @Override
    public synchronized void shutdownInput() throws IOException {
        if (_is != null) {
            _is.close();
            _is = null;
        }
    }

    /**
     * Flushes (as the Socket javadocs advise) and closes.
     * Supported as of 0.9.33, prior to that threw UnsupportedOperationException
     */
    @Override
    public void shutdownOutput() throws IOException {
        OutputStream out;
        synchronized (this) {
            out = _os;
        }
        if (out == null) return;
        // PipedOutputStream may not flush on close, not clear from javadocs
        try {
            out.flush();
            out.close();
        } finally {
            synchronized (this) {
                _os = null;
            }
        }
    }
}
