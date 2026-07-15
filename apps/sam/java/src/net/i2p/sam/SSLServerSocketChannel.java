package net.i2p.sam;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.channels.SocketChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.Set;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Simple wrapper for a SSLServerSocket.
 * Cannot be used for asynch ops.
 *
 * @since 0.9.24
 */
class SSLServerSocketChannel extends ServerSocketChannel {

    private final SSLServerSocket _socket;

    /**
     * Create a new SSLServerSocketChannel wrapping the given SSL server socket.
     *
     * @param socket the SSL server socket to wrap
     */
    public SSLServerSocketChannel(SSLServerSocket socket) {
        super(SelectorProvider.provider());
        _socket = socket;
    }

    //// ServerSocketChannel abstract methods

    /**
     * Accept a new SSL connection and wrap it in an SSLSocketChannel.
     *
     * @return a new SSLSocketChannel for the accepted connection
     * @throws IOException if an I/O error occurs
     */
    public SocketChannel accept() throws IOException {
        return new SSLSocketChannel((SSLSocket)_socket.accept());
    }

    /**
     * Return the underlying SSL server socket.
     *
     * @return the wrapped SSLServerSocket
     */
    public ServerSocket socket() {
        return _socket;
    }

    /**
     * Bind is not supported by this wrapper.
     *
     * @param local the socket address to bind to (ignored)
     * @param backlog the backlog length (ignored)
     * @return this channel (never returned)
     * @throws UnsupportedOperationException always
     */
    public ServerSocketChannel bind(SocketAddress local, int backlog) {
        throw new UnsupportedOperationException();
    }

    /**
     * Set a socket option. This is a no-op for this wrapper.
     *
     * @param name the socket option name (ignored)
     * @param value the socket option value (ignored)
     * @param <T> the type of the socket option value
     * @return this channel
     */
    public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) {
        return this;
    }

    //// AbstractSelectableChannel abstract methods

    /**
     * Close the underlying SSL server socket.
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
     * Get the local socket address of the underlying server socket.
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
