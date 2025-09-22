/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.util.Objects;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import org.apache.commons.io.IOUtils;

/**
 * The SocketClient provides the basic operations that are required of client objects accessing sockets. It is meant to be subclassed to avoid having to rewrite
 * the same code over and over again to open a socket, close a socket, set timeouts, etc. Of special note is the {@link #setSocketFactory setSocketFactory}
 * method, which allows you to control the type of Socket the SocketClient creates for initiating network connections. This is especially useful for adding SSL
 * or proxy support as well as better support for applets. For example, you could create a {@link javax.net.SocketFactory} that requests browser security
 * capabilities before creating a socket. All classes derived from SocketClient should use the {@link #_socketFactory_ _socketFactory_} member variable to
 * create Socket and ServerSocket instances rather than instantiating them by directly invoking a constructor. By honoring this contract you guarantee that a
 * user will always be able to provide his own Socket implementations by substituting his own SocketFactory.
 *
 * @see SocketFactory
 */
public abstract class SocketClient {

    /**
     * The end of line character sequence used by most IETF protocols. That is a carriage return followed by a newline: "\r\n"
     */
    public static final String NETASCII_EOL = "\r\n";

    /** The default SocketFactory shared by all SocketClient instances. */
    private static final SocketFactory DEFAULT_SOCKET_FACTORY = SocketFactory.getDefault();

    /** The default {@link ServerSocketFactory} */
    private static final ServerSocketFactory DEFAULT_SERVER_SOCKET_FACTORY = ServerSocketFactory.getDefault();

    /** The socket's connect timeout (0 = infinite timeout) */
    private static final int DEFAULT_CONNECT_TIMEOUT = 60000;

    /**
     * Gets the IP address string of the given Socket in textual presentation.
     *
     * @param socket the socket to query.
     * @return  the raw IP address in a string format.
     * @since 3.12.0
     */
    protected static String getHostAddress(final Socket socket) {
        return getHostAddress(socket.getInetAddress());
    }

    /**
     * Gets the IP address string of the given InetAddress in textual presentation.
     *
     * @param inetAddress Internet Protocol (IP) address to query.
     * @return  the raw IP address in a string format.
     * @since 3.12.0
     */
    protected static String getHostAddress(final InetAddress inetAddress) {
        return inetAddress != null ? inetAddress.getHostAddress() : null;
    }

    /**
     * A ProtocolCommandSupport object used to manage the registering of ProtocolCommandListeners and the firing of ProtocolCommandEvents.
     */
    private ProtocolCommandSupport commandSupport;

    /** The timeout to use after opening a socket. */
    protected int _timeout_;

    /** The socket used for the connection. */
    protected Socket _socket_;

    /** The hostname used for the connection (null = no hostname supplied). */
    protected String _hostname_;

    /** The remote socket address used for the connection. */
    protected InetSocketAddress remoteInetSocketAddress;

    /** The default port the client should connect to. */
    protected int _defaultPort_;

    /** The socket's InputStream. */
    protected InputStream _input_;

    /** The socket's OutputStream. */
    protected OutputStream _output_;

    /** The socket's SocketFactory. */
    protected SocketFactory _socketFactory_;

    /** The socket's ServerSocket Factory. */
    protected ServerSocketFactory _serverSocketFactory_;

    /**
     * Defaults to {@link #DEFAULT_CONNECT_TIMEOUT}.
     */
    protected int connectTimeout = DEFAULT_CONNECT_TIMEOUT;

    /** Hint for SO_RCVBUF size */
    private int receiveBufferSize = -1;

    /** Hint for SO_SNDBUF size */
    private int sendBufferSize = -1;

    /** The proxy to use when connecting. */
    private Proxy connProxy;

    /**
     * Charset to use for byte IO.
     */
    private Charset charset = Charset.defaultCharset();

    /**
     * Default constructor for SocketClient. Initializes _socket_ to null, _timeout_ to 0, _defaultPort to 0, _isConnected_ to false, charset to
     * {@code Charset.defaultCharset()} and _socketFactory_ to a shared instance of {@link org.apache.commons.net.DefaultSocketFactory}.
     */
    public SocketClient() {
        _socket_ = null;
        _hostname_ = null;
        _input_ = null;
        _output_ = null;
        _timeout_ = 0;
        _defaultPort_ = 0;
        _socketFactory_ = DEFAULT_SOCKET_FACTORY;
        _serverSocketFactory_ = DEFAULT_SERVER_SOCKET_FACTORY;
    }

    // helper method to allow code to be shared with connect(String,...) methods
    private void _connect(final InetSocketAddress remoteInetSocketAddress, final InetAddress localAddr, final int localPort) throws IOException {
        this.remoteInetSocketAddress = remoteInetSocketAddress;
        _socket_ = _socketFactory_.createSocket();
        if (receiveBufferSize != -1) {
            _socket_.setReceiveBufferSize(receiveBufferSize);
        }
        if (sendBufferSize != -1) {
            _socket_.setSendBufferSize(sendBufferSize);
        }
        if (localAddr != null) {
            _socket_.bind(new InetSocketAddress(localAddr, localPort));
        }
        _socket_.connect(remoteInetSocketAddress, connectTimeout);
        _connectAction_();
    }

    /**
     * Because there are so many connect() methods, the _connectAction_() method is provided as a means of performing some action immediately after establishing
     * a connection, rather than reimplementing all the connect() methods. The last action performed by every connect() method after opening a socket is to
     * call this method.
     * <p>
     * This method sets the timeout on the just opened socket to the default timeout set by {@link #setDefaultTimeout setDefaultTimeout()}, sets _input_ and
     * _output_ to the socket's InputStream and OutputStream respectively, and sets _isConnected_ to true.
     * <p>
     * Subclasses overriding this method should start by calling {@code super._connectAction_()} first to ensure the initialization of the aforementioned
     * protected variables.
     *
     * @throws IOException (SocketException) if a problem occurs with the socket
     */
    protected void _connectAction_() throws IOException {
        applySocketAttributes();
        _input_ = _socket_.getInputStream();
        _output_ = _socket_.getOutputStream();
    }

    /**
     * Adds a ProtocolCommandListener.
     *
     * @param listener The ProtocolCommandListener to add.
     * @since 3.0
     */
    public void addProtocolCommandListener(final ProtocolCommandListener listener) {
        getCommandSupport().addProtocolCommandListener(listener);
    }

    /**
     * Applies socket attributes.
     *
     * @throws SocketException if there is an error in the underlying protocol, such as a TCP error.
     * @since 3.8.0
     */
    protected void applySocketAttributes() throws SocketException {
        _socket_.setSoTimeout(_timeout_);
    }

    /**
     * Gets the non-null OutputStream or throws {@link NullPointerException}.
     *
     * <p>
     * This method does not allocate resources.
     * </p>
     *
     * @return the non-null OutputStream.
     * @since 3.11.0
     */
    protected OutputStream checkOpenOutputStream() {
        return Objects.requireNonNull(_output_, "OutputStream");
    }

    /**
     * Opens a Socket connected to a remote host at the current default port and originating from the current host at a system assigned port. Before returning,
     * {@link #_connectAction_ _connectAction_()} is called to perform connection initialization actions.
     *
     * @param host The remote host.
     * @throws SocketException If the socket timeout could not be set.
     * @throws IOException     If the socket could not be opened. In most cases you will only want to catch IOException since SocketException is derived from
     *                         it.
     */
    public void connect(final InetAddress host) throws SocketException, IOException {
        _hostname_ = null;
        connect(host, _defaultPort_);
    }

    /**
     * Opens a Socket connected to a remote host at the specified port and originating from the current host at a system assigned port. Before returning,
     * {@link #_connectAction_ _connectAction_()} is called to perform connection initialization actions.
     *
     * @param host The remote host.
     * @param port The port to connect to on the remote host.
     * @throws SocketException If the socket timeout could not be set.
     * @throws IOException     If the socket could not be opened. In most cases you will only want to catch IOException since SocketException is derived from
     *                         it.
     */
    public void connect(final InetAddress host, final int port) throws SocketException, IOException {
        _hostname_ = null;
        _connect(new InetSocketAddress(host, port), null, -1);
    }

    /**
     * Opens a Socket connected to a remote host at the specified port and originating from the specified local address and port. Before returning,
     * {@link #_connectAction_ _connectAction_()} is called to perform connection initialization actions.
     *
     * @param host      The remote host.
     * @param port      The port to connect to on the remote host.
     * @param localAddr The local address to use.
     * @param localPort The local port to use.
     * @throws SocketException If the socket timeout could not be set.
     * @throws IOException     If the socket could not be opened. In most cases you will only want to catch IOException since SocketException is derived from
     *                         it.
     */
    public void connect(final InetAddress host, final int port, final InetAddress localAddr, final int localPort) throws SocketException, IOException {
        _hostname_ = null;
        _connect(new InetSocketAddress(host, port), localAddr, localPort);
    }

    /**
     * Opens a Socket connected to a remote host at the current default port and originating from the current host at a system assigned port. Before returning,
     * {@link #_connectAction_ _connectAction_()} is called to perform connection initialization actions.
     *
     * @param hostname The name of the remote host.
     * @throws SocketException               If the socket timeout could not be set.
     * @throws IOException                   If the socket could not be opened. In most cases you will only want to catch IOException since SocketException is
     *                                       derived from it.
     * @throws java.net.UnknownHostException If the hostname cannot be resolved.
     */
    public void connect(final String hostname) throws SocketException, IOException {
        connect(hostname, _defaultPort_);
    }

    /**
     * Opens a Socket connected to a remote host at the specified port and originating from the current host at a system assigned port. Before returning,
     * {@link #_connectAction_ _connectAction_()} is called to perform connection initialization actions.
     *
     * @param hostname The name of the remote host.
     * @param port     The port to connect to on the remote host.
     * @throws SocketException               If the socket timeout could not be set.
     * @throws IOException                   If the socket could not be opened. In most cases you will only want to catch IOException since SocketException is
     *                                       derived from it.
     * @throws java.net.UnknownHostException If the hostname cannot be resolved.
     */
    public void connect(final String hostname, final int port) throws SocketException, IOException {
        connect(hostname, port, null, -1);
    }

    /**
     * Opens a Socket connected to a remote host at the specified port and originating from the specified local address and port. Before returning,
     * {@link #_connectAction_ _connectAction_()} is called to perform connection initialization actions.
     *
     * @param hostname  The name of the remote host.
     * @param port      The port to connect to on the remote host.
     * @param localAddr The local address to use.
     * @param localPort The local port to use.
     * @throws SocketException               If the socket timeout could not be set.
     * @throws IOException                   If the socket could not be opened. In most cases you will only want to catch IOException since SocketException is
     *                                       derived from it.
     * @throws java.net.UnknownHostException If the hostname cannot be resolved.
     */
    public void connect(final String hostname, final int port, final InetAddress localAddr, final int localPort) throws SocketException, IOException {
        _hostname_ = hostname;
        _connect(new InetSocketAddress(hostname, port), localAddr, localPort);
    }

    /**
     * Create the CommandSupport instance if required
     */
    protected void createCommandSupport() {
        commandSupport = new ProtocolCommandSupport(this);
    }

    /**
     * Disconnects the socket connection. You should call this method after you've finished using the class instance and also before you call {@link #connect
     * connect()} again. _isConnected_ is set to false, _socket_ is set to null, _input_ is set to null, and _output_ is set to null.
     *
     * @throws IOException not thrown, subclasses may throw.
     */
    @SuppressWarnings("unused") // subclasses may throw IOException
    public void disconnect() throws IOException {
        IOUtils.closeQuietly(_socket_);
        IOUtils.closeQuietly(_input_);
        IOUtils.closeQuietly(_output_);
        _socket_ = null;
        _hostname_ = null;
        _input_ = null;
        _output_ = null;
    }

    /**
     * If there are any listeners, send them the command details.
     *
     * @param command the command name
     * @param message the complete message, including command name
     * @since 3.0
     */
    protected void fireCommandSent(final String command, final String message) {
        getCommandSupport().fireCommandSent(command, message);
    }

    /**
     * If there are any listeners, send them the reply details.
     *
     * @param replyCode the code extracted from the reply
     * @param reply     the full reply text
     * @since 3.0
     */
    protected void fireReplyReceived(final int replyCode, final String reply) {
        getCommandSupport().fireReplyReceived(replyCode, reply);
    }

    /**
     * Gets the charset.
     *
     * @return the charset.
     * @since 3.3
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * Gets the charset name.
     *
     * @return the charset.
     * @since 3.3
     * @deprecated Since the code now requires Java 1.6 as a minimum
     */
    @Deprecated
    public String getCharsetName() {
        return charset.name();
    }

    /**
     * Subclasses can override this if they need to provide their own instance field for backwards compatibility.
     *
     * @return the CommandSupport instance, may be {@code null}
     * @since 3.0
     */
    protected ProtocolCommandSupport getCommandSupport() {
        return commandSupport;
    }

    /**
     * Gets the underlying socket connection timeout.
     *
     * @return timeout (in ms)
     * @since 2.0
     */
    public int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Gets the current value of the default port (stored in {@link #_defaultPort_ _defaultPort_}).
     *
     * @return The current value of the default port.
     */
    public int getDefaultPort() {
        return _defaultPort_;
    }

    /**
     * Gets the default timeout in milliseconds that is used when opening a socket.
     *
     * @return The default timeout in milliseconds that is used when opening a socket.
     */
    public int getDefaultTimeout() {
        return _timeout_;
    }

    /**
     * Gets the current value of the SO_KEEPALIVE flag on the currently opened socket. Delegates to {@link Socket#getKeepAlive()}
     *
     * @return True if SO_KEEPALIVE is enabled.
     * @throws SocketException      if there is a problem with the socket
     * @throws NullPointerException if the socket is not currently open
     * @since 2.2
     */
    public boolean getKeepAlive() throws SocketException {
        return _socket_.getKeepAlive();
    }

    /**
     * Gets the local address to which the client's socket is bound. Delegates to {@link Socket#getLocalAddress()}
     *
     * @return The local address to which the client's socket is bound.
     * @throws NullPointerException if the socket is not currently open
     */
    public InetAddress getLocalAddress() {
        return _socket_.getLocalAddress();
    }

    /**
     * Gets the port number of the open socket on the local host used for the connection. Delegates to {@link Socket#getLocalPort()}
     *
     * @return The port number of the open socket on the local host used for the connection.
     * @throws NullPointerException if the socket is not currently open
     */
    public int getLocalPort() {
        return _socket_.getLocalPort();
    }

    /**
     * Gets the proxy for use with all the connections.
     *
     * @return the current proxy for connections.
     */
    public Proxy getProxy() {
        return connProxy;
    }

    /**
     * Gets the current receivedBuffer size
     *
     * @return the size, or -1 if not initialized
     * @since 3.0
     */
    protected int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    /**
     * Gets the address to which the socket is connected.
     *
     * @return The remote address to which the client is connected. Delegates to {@link Socket#getInetAddress()}
     * @throws NullPointerException if the socket is not currently open
     */
    public InetAddress getRemoteAddress() {
        return _socket_.getInetAddress();
    }

    /**
     * Gets the remote socket address used for the connection.
     *
     * @return the remote socket address used for the connection
     * @since 3.10.0
     */
    protected InetSocketAddress getRemoteInetSocketAddress() {
        return remoteInetSocketAddress;
    }

    /**
     * Gets the port number of the remote host to which the client is connected. Delegates to {@link Socket#getPort()}
     *
     * @return The port number of the remote host to which the client is connected.
     * @throws NullPointerException if the socket is not currently open
     */
    public int getRemotePort() {
        return _socket_.getPort();
    }

    /**
     * Gets the current sendBuffer size
     *
     * @return the size, or -1 if not initialized
     * @since 3.0
     */
    protected int getSendBufferSize() {
        return sendBufferSize;
    }

    /**
     * Gets the underlying {@link ServerSocketFactory}
     *
     * @return The server socket factory
     * @since 2.2
     */
    public ServerSocketFactory getServerSocketFactory() {
        return _serverSocketFactory_;
    }

    /**
     * Gets the current SO_LINGER timeout of the currently opened socket.
     *
     * @return The current SO_LINGER timeout. If SO_LINGER is disabled returns -1.
     * @throws SocketException      If the operation fails.
     * @throws NullPointerException if the socket is not currently open
     */
    public int getSoLinger() throws SocketException {
        return _socket_.getSoLinger();
    }

    /**
     * Gets the timeout in milliseconds of the currently opened socket.
     *
     * @return The timeout in milliseconds of the currently opened socket.
     * @throws SocketException      If the operation fails.
     * @throws NullPointerException if the socket is not currently open
     */
    public int getSoTimeout() throws SocketException {
        return _socket_.getSoTimeout();
    }

    /**
     * Gets true if Nagle's algorithm is enabled on the currently opened socket.
     *
     * @return True if Nagle's algorithm is enabled on the currently opened socket, false otherwise.
     * @throws SocketException      If the operation fails.
     * @throws NullPointerException if the socket is not currently open
     */
    public boolean getTcpNoDelay() throws SocketException {
        return _socket_.getTcpNoDelay();
    }

    /**
     * Tests the socket to test if it is available for use. Note that the only sure test is to use it, but these checks may help in some cases.
     *
     * @see <a href="https://issues.apache.org/jira/browse/NET-350">NET-350</a>
     * @return {@code true} if the socket appears to be available for use
     * @since 3.0
     */
    @SuppressWarnings("resource")
    public boolean isAvailable() {
        if (isConnected()) {
            try {
                if (_socket_.getInetAddress() == null) {
                    return false;
                }
                if (_socket_.getPort() == 0) {
                    return false;
                }
                if (_socket_.getRemoteSocketAddress() == null) {
                    return false;
                }
                if (_socket_.isClosed()) {
                    return false;
                }
                /*
                 * these aren't exact checks (a Socket can be half-open), but since we usually require two-way data transfer, we check these here too:
                 */
                if (_socket_.isInputShutdown()) {
                    return false;
                }
                if (_socket_.isOutputShutdown()) {
                    return false;
                }
                /* ignore the result, catch exceptions: */
                // No need to close
                _socket_.getInputStream();
                // No need to close
                _socket_.getOutputStream();
            } catch (final IOException ioex) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Tests whether the client is currently connected to a server.
     *
     * Delegates to {@link Socket#isConnected()}
     *
     * @return True if the client is currently connected to a server, false otherwise.
     */
    public boolean isConnected() {
        if (_socket_ == null) {
            return false;
        }

        return _socket_.isConnected();
    }

    /**
     * Removes a ProtocolCommandListener.
     *
     * @param listener The ProtocolCommandListener to remove.
     * @since 3.0
     */
    public void removeProtocolCommandListener(final ProtocolCommandListener listener) {
        getCommandSupport().removeProtocolCommandListener(listener);
    }

    /**
     * Sets the charset.
     *
     * @param charset the charset.
     * @since 3.3
     */
    public void setCharset(final Charset charset) {
        this.charset = charset;
    }

    /**
     * Sets the connection timeout in milliseconds, which will be passed to the {@link Socket} object's connect() method.
     *
     * @param connectTimeout The connection timeout to use (in ms)
     * @since 2.0
     */
    public void setConnectTimeout(final int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * Sets the default port the SocketClient should connect to when a port is not specified. The {@link #_defaultPort_ _defaultPort_} variable stores this
     * value. If never set, the default port is equal to zero.
     *
     * @param port The default port to set.
     */
    public void setDefaultPort(final int port) {
        _defaultPort_ = port;
    }

    /**
     * Sets the default timeout in milliseconds to use when opening a socket. This value is only used previous to a call to {@link #connect connect()} and
     * should not be confused with {@link #setSoTimeout setSoTimeout()} which operates on the currently opened socket. _timeout_ contains the new timeout value.
     *
     * @param timeout The timeout in milliseconds to use for the socket connection.
     */
    public void setDefaultTimeout(final int timeout) {
        _timeout_ = timeout;
    }

    /**
     * Sets the SO_KEEPALIVE flag on the currently opened socket.
     *
     * From the Javadocs, the default keepalive time is 2 hours (although this is implementation dependent). It looks as though the Windows WSA sockets
     * implementation allows a specific keepalive value to be set, although this seems not to be the case on other systems.
     *
     * @param keepAlive If true, keepAlive is turned on
     * @throws SocketException      if there is a problem with the socket
     * @throws NullPointerException if the socket is not currently open
     * @since 2.2
     */
    public void setKeepAlive(final boolean keepAlive) throws SocketException {
        _socket_.setKeepAlive(keepAlive);
    }

    /**
     * Sets the proxy for use with all the connections. The proxy is used for connections established after the call to this method.
     *
     * @param proxy the new proxy for connections.
     * @since 3.2
     */
    public void setProxy(final Proxy proxy) {
        setSocketFactory(new DefaultSocketFactory(proxy));
        connProxy = proxy;
    }

    /**
     * Sets the underlying socket receive buffer size.
     *
     * @param size The size of the buffer in bytes.
     * @throws SocketException never (but subclasses may wish to do so)
     * @since 2.0
     */
    @SuppressWarnings("unused") // subclasses may throw SocketException
    public void setReceiveBufferSize(final int size) throws SocketException {
        receiveBufferSize = size;
    }

    /**
     * Sets the underlying socket send buffer size.
     *
     * @param size The size of the buffer in bytes.
     * @throws SocketException never thrown, but subclasses might want to do so
     * @since 2.0
     */
    @SuppressWarnings("unused") // subclasses may throw SocketException
    public void setSendBufferSize(final int size) throws SocketException {
        sendBufferSize = size;
    }

    /**
     * Sets the ServerSocketFactory used by the SocketClient to open ServerSocket connections. If the factory value is null, then a default factory is used
     * (only do this to reset the factory after having previously altered it).
     *
     * @param factory The new ServerSocketFactory the SocketClient should use.
     * @since 2.0
     */
    public void setServerSocketFactory(final ServerSocketFactory factory) {
        if (factory == null) {
            _serverSocketFactory_ = DEFAULT_SERVER_SOCKET_FACTORY;
        } else {
            _serverSocketFactory_ = factory;
        }
    }

    /**
     * Sets the SocketFactory used by the SocketClient to open socket connections. If the factory value is null, then a default factory is used (only do this to
     * reset the factory after having previously altered it). Any proxy setting is discarded.
     *
     * @param factory The new SocketFactory the SocketClient should use.
     */
    public void setSocketFactory(final SocketFactory factory) {
        if (factory == null) {
            _socketFactory_ = DEFAULT_SOCKET_FACTORY;
        } else {
            _socketFactory_ = factory;
        }
    }

    /**
     * Sets the SO_LINGER timeout on the currently opened socket.
     *
     * @param on  True if linger is to be enabled, false if not.
     * @param val The {@code linger} timeout (in hundredths of a second?)
     * @throws SocketException      If the operation fails.
     * @throws NullPointerException if the socket is not currently open
     */
    public void setSoLinger(final boolean on, final int val) throws SocketException {
        _socket_.setSoLinger(on, val);
    }

    /**
     * Sets the timeout in milliseconds of a currently open connection. Only call this method after a connection has been opened by {@link #connect connect()}.
     * <p>
     * To set the initial timeout, use {@link #setDefaultTimeout(int)} instead.
     *
     * @param timeout The timeout in milliseconds to use for the currently open socket connection.
     * @throws SocketException      If the operation fails.
     * @throws NullPointerException if the socket is not currently open
     */
    public void setSoTimeout(final int timeout) throws SocketException {
        _socket_.setSoTimeout(timeout);
    }

    /**
     * Enables or disables the Nagle's algorithm (TCP_NODELAY) on the currently opened socket.
     *
     * @param on True if Nagle's algorithm is to be enabled, false if not.
     * @throws SocketException      If the operation fails.
     * @throws NullPointerException if the socket is not currently open
     */
    public void setTcpNoDelay(final boolean on) throws SocketException {
        _socket_.setTcpNoDelay(on);
    }

    /**
     * Verifies that the remote end of the given socket is connected to the same host that the SocketClient is currently connected to. This is useful for
     * doing a quick security check when a client needs to accept a connection from a server, such as an FTP data connection or a BSD R command standard error
     * stream.
     *
     * @param socket the item to check against
     * @return True if the remote hosts are the same, false if not.
     */
    public boolean verifyRemote(final Socket socket) {
        return socket != null && Objects.equals(socket.getInetAddress(), getRemoteAddress());
    }

    /*
     * Fields cannot be pulled up into a super-class without breaking binary compatibility, so the abstract method is needed to pass the instance to the
     * methods which were moved here.
     */
}
