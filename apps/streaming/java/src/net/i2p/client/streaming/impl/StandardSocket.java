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

    /** Wraps an I2PSocket in a java.net.Socket. */
    StandardSocket(I2PSocket socket) {
        _socket = socket;
    }

    /**
     *  Binding is not supported.
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void bind(SocketAddress bindpoint) {
        throw new UnsupportedOperationException();
    }

    /**
     * Closes the socket and the underlying I2PSocket.
     */
    @Override
    public void close() throws IOException {
        _closed = true;
        _socket.close();
    }

    /**
     *  Connecting is not supported.
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void connect(SocketAddress endpoint) {
        throw new UnsupportedOperationException();
    }

    /**
     *  Connecting is not supported.
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void connect(SocketAddress endpoint, int timeout) {
        throw new UnsupportedOperationException();
    }

    /**
     *  Channel is not supported.
     *  @return null always, unimplemented
     */
    @Override
    public SocketChannel getChannel() {
        return null;
    }

    /**
     *  No remote address.
     *  @return null always
     */
    @Override
    public InetAddress getInetAddress() {
        return null;
    }

    /**
     * Input stream from the underlying I2PSocket.
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
     * Keep-alive setting.
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
     *  No local address.
     *  @return null always
     */
    @Override
    public InetAddress getLocalAddress() {
        return null;
    }

    /**
     *  Local port.
     *  @return the port or 0 if unknown
     */
    @Override
    public int getLocalPort() {
        return _socket.getLocalPort();
    }

    /**
     *  Local socket address.
     *  @return an I2PSocketAddress as of 0.9.26; prior to that, returned null
     *  @since implemented in 0.9.26
     */
    @Override
    public SocketAddress getLocalSocketAddress() {
        return new I2PSocketAddress(_socket.getThisDestination(), _socket.getLocalPort());
    }

    /**
     *  OOB inline is not supported.
     *  @return false always
     */
    @Override
    public boolean getOOBInline() {
        return false;
    }

    /**
     * Output stream from the underlying I2PSocket.
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
     *  Remote port.
     *  @return the port or 0 if unknown
     */
    @Override
    public int getPort() {
        return _socket.getPort();
    }

    /**
     * Inbound buffer size.
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
     *  Remote socket address.
     *  @return an I2PSocketAddress as of 0.9.26; prior to that, threw UnsupportedOperationException
     *  @since implemented in 0.9.26
     */
    @Override
    public SocketAddress getRemoteSocketAddress() {
        return new I2PSocketAddress(_socket.getPeerDestination(), _socket.getPort());
    }

    /**
     *  Reuse address is not supported.
     *  @return false always
     */
    @Override
    public boolean getReuseAddress() {
        return false;
    }

    /**
     * Outbound buffer size.
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
     * SO_LINGER is not implemented.
     * @return -1 always (not implemented)
     */
    @Override
    public int getSoLinger() { return -1; }

    /**
     * Socket timeout.
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
     *  TCP_NODELAY is not supported.
     *  @return false always
     */
    @Override
    public boolean getTcpNoDelay() {
        // No option yet. See ConnectionDataReceiver
        return false;
    }

    /**
     *  Traffic class is not supported.
     *  @return 0 always
     */
    @Override
    public int getTrafficClass() {
        return 0;
    }

    /**
     *  Always bound.
     *  @return true always
     */
    @Override
    public boolean isBound() {
        return true;
    }

    /**
     * Whether closed.
     * @return whether closed
     */
    @Override
    public boolean isClosed() {
        return _closed;
    }

    /**
     * Whether connected.
     * @return whether connected
     */
    @Override
    public boolean isConnected() {
        return _connected;
    }

    /**
     * Whether input shutdown.
     * @return whether input shutdown
     */
    @Override
    public boolean isInputShutdown() {
        return _inputShutdown;
    }

    /**
     * Whether output shutdown.
     * @return whether output shutdown
     */
    @Override
    public boolean isOutputShutdown() {
        return _outputShutdown;
    }

    /**
     *  Urgent data is not supported.
     *  @throws UnsupportedOperationException always
     */
    @Override
    public void sendUrgentData(int data) {
        throw new UnsupportedOperationException();
    }

    /**
     * Keep-alive setting.
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
     * OOB inline setting.
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
     * Socket timeout.
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

    /**
     * Shuts down the input side, closing the underlying I2PSocket.
     */
    @Override
    public void shutdownInput() throws IOException {
        _inputShutdown = true;
        _socket.close();
    }

    /**
     * Shuts down the output side, closing the underlying I2PSocket.
     */
    @Override
    public void shutdownOutput() throws IOException {
        _outputShutdown = true;
        _socket.close();
    }

    /**
     * String representation of the underlying I2PSocket.
     */
    @Override
    public String toString() {
        return _socket.toString();
    }
}
