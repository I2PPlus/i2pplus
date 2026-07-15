package net.i2p.sam;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.Set;

import javax.net.ssl.SSLSocket;

/**
 * Simple wrapper for a SSLSocket.
 * Cannot be used for asynch ops.
 *
 * @since 0.9.24
 */
class SSLSocketChannel extends SocketChannel {

    private final SSLSocket _socket;

    /**
     * Create a new SSLSocketChannel wrapping the given SSL socket.
     *
     * @param socket the SSL socket to wrap
     */
    public SSLSocketChannel(SSLSocket socket) {
        super(SelectorProvider.provider());
        _socket = socket;
    }

    //// SocketChannel abstract methods

    /**
     * Return the underlying SSL socket.
     *
     * @return the wrapped SSLSocket
     */
    public Socket socket() {
        return _socket;
    }

    /**
     * Connect is not supported by this wrapper.
     *
     * @param remote the remote address to connect to (ignored)
     * @return true (never reached)
     * @throws UnsupportedOperationException always
     */
    public boolean connect(SocketAddress remote) {
        throw new UnsupportedOperationException();
    }

    /**
     * Finish connecting. This wrapper assumes the connection is already established.
     *
     * @return true always
     */
    public boolean finishConnect() {
        return true;
    }

    /**
     * Check if the underlying SSL socket is connected.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return _socket.isConnected();
    }

    /**
     * Check if a connection operation is pending. Always returns false.
     *
     * @return false always
     */
    public boolean isConnectionPending() {
        return false;
    }

    /**
     * Get the remote socket address of the underlying SSL socket.
     *
     * @return the remote socket address, or null if not connected
     */
    public SocketAddress getRemoteAddress() {
        return _socket.getRemoteSocketAddress();
    }

    /**
     * Shut down the input stream of the underlying SSL socket.
     *
     * @return this channel
     * @throws IOException if an I/O error occurs
     */
    public SocketChannel shutdownInput() throws IOException {
        _socket.getInputStream().close();
        return this;
    }

    /**
     * Shut down the output stream of the underlying SSL socket.
     *
     * @return this channel
     * @throws IOException if an I/O error occurs
     */
    public SocketChannel shutdownOutput() throws IOException {
        _socket.getOutputStream().close();
        return this;
    }

    /**
     * Set a socket option. This is a no-op for this wrapper.
     *
     * @param name the socket option name (ignored)
     * @param value the socket option value (ignored)
     * @param <T> the type of the socket option value
     * @return this channel
     */
    public <T> SocketChannel setOption(SocketOption<T> name, T value) {
        return this;
    }

    /**
     * Bind is not supported by this wrapper.
     *
     * @param local the socket address to bind to (ignored)
     * @return this channel (never returned)
     * @throws UnsupportedOperationException always
     */
    public SocketChannel bind(SocketAddress local) {
        throw new UnsupportedOperationException();
    }

    //// SocketChannel abstract methods

    /**
     * Read bytes from the underlying SSL socket into the given buffer.
     * The buffer must have a backing array.
     *
     * @param src the buffer to read into
     * @return the number of bytes read, or -1 if end of stream
     * @throws IOException if an I/O error occurs
     * @throws UnsupportedOperationException if the buffer has no backing array
     */
    public int read(ByteBuffer src) throws IOException {
        if (!src.hasArray())
            throw new UnsupportedOperationException();
       int pos = src.position();
       int len = src.remaining();
       int read = _socket.getInputStream().read(src.array(), src.arrayOffset() + pos, len);
       if (read > 0)
           src.position(pos + read);
       return read;
    }

    /**
     * Scatter read is not supported by this wrapper.
     *
     * @param srcs the buffers to read into (ignored)
     * @param offset the offset in the buffer array (ignored)
     * @param length the number of buffers to use (ignored)
     * @return the number of bytes read (never reached)
     * @throws UnsupportedOperationException always
     */
    public long read(ByteBuffer[] srcs, int offset, int length) {
        throw new UnsupportedOperationException();
    }

    /**
     * Write bytes from the given buffer to the underlying SSL socket.
     * The buffer must have a backing array.
     *
     * @param src the buffer to write from
     * @return the number of bytes written
     * @throws IOException if an I/O error occurs
     * @throws UnsupportedOperationException if the buffer has no backing array
     */
    public int write(ByteBuffer src) throws IOException {
        if (!src.hasArray())
            throw new UnsupportedOperationException();
       int pos = src.position();
       int len = src.remaining();
       _socket.getOutputStream().write(src.array(), src.arrayOffset() + pos, len);
       src.position(pos + len);
       return len;
    }

    /**
     * Gather write is not supported by this wrapper.
     *
     * @param srcs the buffers to write from (ignored)
     * @param offset the offset in the buffer array (ignored)
     * @param length the number of buffers to use (ignored)
     * @return the number of bytes written (never reached)
     * @throws UnsupportedOperationException always
     */
    public long write(ByteBuffer[] srcs, int offset, int length) {
        throw new UnsupportedOperationException();
    }

    //// AbstractSelectableChannel abstract methods

    /**
     * Close the underlying SSL socket.
     *
     * @throws IOException if an I/O error occurs while closing
     */
    public void implCloseSelectableChannel() throws IOException {
        _socket.close();
    }

    /**
     * Configure the blocking mode. Only blocking mode is supported.
     *
     * @param block true for blocking mode (accepted), false throws
     * @throws IOException if an I/O error occurs
     * @throws UnsupportedOperationException if block is false
     */
    public void implConfigureBlocking(boolean block) throws IOException {
        if (!block)
            throw new UnsupportedOperationException();
    }


    //// NetworkChannel interface methods

    /**
     * Get the local socket address of the underlying SSL socket.
     *
     * @return the local socket address, or null if not bound
     */
    public SocketAddress getLocalAddress() {
        return _socket.getLocalSocketAddress();
    }

    /**
     * Get the value of a socket option. Not supported, always returns null.
     *
     * @param name the socket option name (ignored)
     * @param <T> the type of the socket option value
     * @return null always
     */
    public <T> T getOption(SocketOption<T> name) {
        return null;
    }

    /**
     * Get the set of supported socket options. Returns an empty set.
     *
     * @return an empty set of supported socket options
     */
    public Set<SocketOption<?>> supportedOptions() {
        return Collections.emptySet();
    }
}
