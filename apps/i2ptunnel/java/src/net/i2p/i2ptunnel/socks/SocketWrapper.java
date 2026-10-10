package net.i2p.i2ptunnel.socks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;

/**
 * I2PSocket implementation wrapping a standard TCP Socket obtained from an
 * outproxy, which is itself a wrapper around the Orchid stream.
 * <p>
 * SOCKS and HTTP tunnels use this so the tunnel framework can treat an
 * outproxy connection as an I2P connection and handle it uniformly. All
 * I2PSocket operations are delegated to the underlying Socket, and the
 * destination is a fixed dummy: there is no I2P destination to report.
 *
 * @since 0.9.27
 */
class SocketWrapper implements I2PSocket {

    private final Socket socket;

    private static final Destination DUMMY_DEST = new Destination();
    static {
        try {
           DUMMY_DEST.fromByteArray(new byte[387]);
        } catch (DataFormatException dfe) {
           throw new RuntimeException(dfe);
        }
    }

    /**
     * Wraps a TCP socket obtained from an outproxy so that the tunnel
     * framework can treat it as an I2P connection.
     *
     * @param sock the TCP socket to wrap, obtained from the outproxy
     */
    public SocketWrapper(Socket sock) {
        socket = sock;
    }

    /**
     * @return the Destination of this side of the socket.
     */
    public Destination getThisDestination() {
        return DUMMY_DEST;
    }

    /**
     * @return the destination of the peer.
     */
    @Override
    public Destination getPeerDestination() {
        return DUMMY_DEST;
    }

    /**
     * Gets the input stream from the underlying socket.
     *
     * @return the socket's input stream
     * @throws IOException if the socket is closed
     */
    @Override
    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    /**
     * Gets the output stream from the underlying socket.
     *
     * @return the socket's output stream
     * @throws IOException if the socket is closed
     */
    @Override
    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    /**
     * Returns no socket options, because the outproxy has already configured
     * the wrapped TCP socket.
     * @return null always
     */
    @Override
    public I2PSocketOptions getOptions() {
        return null;
    }

    /**
     * Discards the options without applying them, because the outproxy has
     * already configured the wrapped TCP socket.
     */
    @Override
    public void setOptions(I2PSocketOptions options) { /* no-op */ }

    /**
     * @return the read timeout
     */
    public long getReadTimeout() {
        return -1;
    }

    /**
     * Ignores the requested timeout, because the read timeout belongs to the
     * wrapped outproxy socket.
     *
     * @param ms the requested timeout in milliseconds, discarded
     */
    @Override
    public void setReadTimeout(long ms) { /* no-op */ }

    public void close() throws IOException {
        socket.close();
    }

    /**
     * Closes the wrapper by closing the wrapped outproxy socket.
     * @since 0.9.30
     */
    @Override
    public void reset() throws IOException {
        close();
    }

    /**
     * @return whether closed
     */
    public boolean isClosed() {
        return socket.isClosed();
    }

    /**
     * Gets the remote port of the wrapped outproxy socket, which the wrapper
     * always reports as unspecified.
     * @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     */
    public int getPort() {
        try {
            return socket.getPort();
        } catch (UnsupportedOperationException uoe) {
            // prior to 1.2.2-0.2
            return 0;
        }
    }

    /**
     * Gets the local port of the wrapped outproxy socket, which the wrapper
     * always reports as unspecified.
     * @return 0 always
     */
    public int getLocalPort() {
        return 0;
    }
}
