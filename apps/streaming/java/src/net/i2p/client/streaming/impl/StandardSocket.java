package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.client.streaming.I2PSocketOptions;

/**
 * Bridge to I2PSocket.
 *
 * This extends Socket to make porting apps easier.
 * Methods throw IOExceptions like Sockets do, rather than returning
 * null for some methods.
 *
 * StandardSockets are always bound, and always start out connected
 * (unless connectDelay is &gt; 0).
 * You may not create an unbound StandardSocket.
 * Create this through the SocketManager.
 *
 * Todo: Make public and add getPeerDestination() ?
 *
 * @author zzz
 * @since 0.8.4
 */
class StandardSocket extends Socket {
    private final I2PSocket _socket;
    private volatile boolean _connected = true;
    private volatile boolean _inputShutdown;
    private volatile boolean _outputShutdown;
    private volatile boolean _closed;

    StandardSocket(I2PSocket socket) {
        _socket = socket;
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void bind(SocketAddress bindpoint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException {
        _closed = true;
        _socket.close();
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void connect(SocketAddress endpoint) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void connect(SocketAddress endpoint, int timeout) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @return null always, unimplemented
     */
    @Override
    public SocketChannel getChannel() {
        return null;
    }

    /**
     *  @return null always
     */
    @Override
    public InetAddress getInetAddress() {
        return null;
    }

    /**
     * @return the input stream
     * @throws IOException if the socket has no input stream
     */
    @Override
    public InputStream getInputStream() throws IOException {
        InputStream rv = _socket.getInputStream();
        if (rv != null)
            return rv;
        throw new IOException("No stream");
    }

    /**
     * @return true if keep-alive is enabled (inactivity action is SEND)
     */
    @Override
    public boolean getKeepAlive() {
        ConnectionOptions opts = (ConnectionOptions) _socket.getOptions();
        if (opts == null)
            return false;
        return opts.getInactivityAction() == ConnectionOptions.INACTIVITY_ACTION_SEND;
    }

    /**
     *  @return null always
     */
    @Override
    public InetAddress getLocalAddress() {
        return null;
    }

    /**
     *  @return the port or 0 if unknown
     */
    @Override
    public int getLocalPort() {
        return _socket.getLocalPort();
    }

    /**
     *  @return an I2PSocketAddress as of 0.9.26; prior to that, returned null
     *  @since implemented in 0.9.26
     */
    @Override
    public SocketAddress getLocalSocketAddress() {
        return new I2PSocketAddress(_socket.getThisDestination(), _socket.getLocalPort());
    }

    /**
     *  @return false always
     */
    @Override
    public boolean getOOBInline() {
        return false;
    }

    /**
     * @return the output stream
     * @throws IOException if the socket has no output stream
     */
    @Override
    public OutputStream getOutputStream() throws IOException {
        OutputStream rv = _socket.getOutputStream();
        if (rv != null)
            return rv;
        throw new IOException("No stream");
    }

    /**
     *  @return the port or 0 if unknown
     */
    @Override
    public int getPort() {
        return _socket.getPort();
    }

    /**
     * @return the inbound buffer size, or 64KB if options are unavailable
     */
    @Override
    public int getReceiveBufferSize() {
        ConnectionOptions opts = (ConnectionOptions) _socket.getOptions();
        if (opts == null)
            return 64*1024;
        return opts.getInboundBufferSize();
    }

    /**
     *  @return an I2PSocketAddress as of 0.9.26; prior to that, threw UnsupportedOperationException
     *  @since implemented in 0.9.26
     */
    @Override
    public SocketAddress getRemoteSocketAddress() {
        return new I2PSocketAddress(_socket.getPeerDestination(), _socket.getPort());
    }

    /**
     *  @return false always
     */
    @Override
    public boolean getReuseAddress() {
        return false;
    }

    /**
     * @return the inbound buffer size, or 64KB if options are unavailable
     */
    @Override
    public int getSendBufferSize() {
        ConnectionOptions opts = (ConnectionOptions) _socket.getOptions();
        if (opts == null)
            return 64*1024;
        return opts.getInboundBufferSize();
    }

    /**
     * @return -1 always (not implemented)
     */
    @Override
    public int getSoLinger() { return -1; }

    /**
     * @return the socket timeout in milliseconds
     */
    @Override
    public int getSoTimeout() {
        I2PSocketOptions opts = _socket.getOptions();
        if (opts == null)
            return 0;
        long rv = opts.getReadTimeout();
        // Java Socket: 0 is forever, and we don't exactly have nonblocking
        if (rv > Integer.MAX_VALUE)
            rv = Integer.MAX_VALUE;
        else if (rv < 0)
            rv = 0;
        else if (rv == 0)
            rv = 1;
        return (int) rv;
    }

    /**
     *  @return false always
     */
    @Override
    public boolean getTcpNoDelay() {
        // No option yet. See ConnectionDataReceiver
        return false;
    }

    /**
     *  @return 0 always
     */
    @Override
    public int getTrafficClass() {
        return 0;
    }

    /**
     *  @return true always
     */
    @Override
    public boolean isBound() {
        return true;
    }

    @Override
    public boolean isClosed() {
        return _closed;
    }

    @Override
    public boolean isConnected() {
        return _connected;
    }

    @Override
    public boolean isInputShutdown() {
        return _inputShutdown;
    }

    @Override
    public boolean isOutputShutdown() {
        return _outputShutdown;
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void sendUrgentData(int data) {
        throw new UnsupportedOperationException();
    }

    /**
     * @param on true to enable keep-alive (inactivity action SEND), false for NOOP
     */
    @Override
    public void setKeepAlive(boolean on) {
        ConnectionOptions opts = (ConnectionOptions) _socket.getOptions();
        if (opts == null)
            return;
        if (on)
            opts.setInactivityAction(ConnectionOptions.INACTIVITY_ACTION_SEND);
        else
            opts.setInactivityAction(ConnectionOptions.INACTIVITY_ACTION_NOOP);  // DISCONNECT?
    }

    /**
     * @param on true to throw UnsupportedOperationException, false does nothing
     * @throws UnsupportedOperationException if on is true
     */
    @Override
    public void setOOBInline(boolean on) {
        if (on)
            throw new UnsupportedOperationException();
    }

    /**
     *  Does nothing.
     */
    @Override
    public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) { /* no-op */ }

    /**
     *  Does nothing.
     */
    @Override
    public void setReceiveBufferSize(int size) { /* no-op */ }

    /**
     *  Does nothing.
     */
    @Override
    public void setReuseAddress(boolean on) { /* no-op */ }

    /**
     *  Does nothing.
     */
    @Override
    public void setSendBufferSize(int size) { /* no-op */ }

    /**
     *  Does nothing.
     */
    @Override
    public void setSoLinger(boolean on, int linger) { /* no-op */ }

    /**
     * @param timeout the timeout in milliseconds
     * @throws SocketException if the options are unavailable
     */
    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        I2PSocketOptions opts = _socket.getOptions();
        if (opts == null)
            throw new SocketException("No options");
        // Java Socket: 0 is forever
        if (timeout == 0)
            timeout = -1;
        opts.setReadTimeout(timeout);
    }

    /**
     *  Does nothing.
     */
    @Override
    public void setTcpNoDelay(boolean on) { /* no-op */ }

    /**
     *  Does nothing.
     */
    @Override
    public void setTrafficClass(int tc) { /* no-op */ }

    @Override
    public void shutdownInput() throws IOException {
        _inputShutdown = true;
        _socket.close();
    }

    @Override
    public void shutdownOutput() throws IOException {
        _outputShutdown = true;
        _socket.close();
    }

    @Override
    public String toString() {
        return _socket.toString();
    }
}
