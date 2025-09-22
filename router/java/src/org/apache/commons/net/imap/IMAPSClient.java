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

package org.apache.commons.net.imap;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.apache.commons.net.io.CRLFLineReader;
import org.apache.commons.net.util.SSLContextUtils;
import org.apache.commons.net.util.SSLSocketUtils;

/**
 * The IMAPSClient class provides SSL/TLS connection encryption to IMAPClient. Copied from
 * <a href="https://commons.apache.org/proper/commons-net/apidocs/index.html?org/apache/commons/net/ftp/FTPSClient.html"> FTPSClient</a> and modified to suit
 * IMAP. If implicit mode is selected (NOT the default), SSL/TLS negotiation starts right after the connection has been established. In explicit mode (the
 * default), SSL/TLS negotiation starts when the user calls execTLS() and the server accepts the command.
 *
 * <pre>
 * {@code
 * //Implicit usage:
 *
 *               IMAPSClient c = new IMAPSClient(true);
 *               c.connect("127.0.0.1", 993);
 *
 * //Explicit usage:
 *
 *               IMAPSClient c = new IMAPSClient();
 *               c.connect("127.0.0.1", 143);
 *               if (c.execTLS()) { /rest of the commands here/ }
 * }
 * </pre>
 *
 * <strong>Warning</strong>: the hostname is not verified against the certificate by default, use {@link #setHostnameVerifier(HostnameVerifier)} or
 * {@link #setEndpointCheckingEnabled(boolean)} (on Java 1.7+) to enable verification.
 */
public class IMAPSClient extends IMAPClient {
    /** The default IMAP over SSL port. */
    public static final int DEFAULT_IMAPS_PORT = 993;

    /** Default secure socket protocol name. */
    public static final String DEFAULT_PROTOCOL = "TLS";

    /** The security mode. True - Implicit Mode / False - Explicit Mode. */
    private final boolean isImplicit;
    /** The secure socket protocol to be used, like SSL/TLS. */
    private final String protocol;
    /** The context object. */
    private SSLContext context;
    /**
     * The cipher suites. SSLSockets have a default set of these anyway, so no initialization required.
     */
    private String[] suites;
    /** The protocol versions. */
    private String[] protocols // null;
    ; // {"SSLv2", "SSLv3", "TLSv1", "TLSv1.1", "SSLv2Hello"};

    /** The IMAPS {@link TrustManager} implementation, default null. */
    private TrustManager trustManager;

    /** The {@link KeyManager}, default null. */
    private KeyManager keyManager;

    /** The {@link HostnameVerifier} to use post-TLS, default null (i.e. no verification). */
    private HostnameVerifier hostnameVerifier;

    /** Use Java 1.7+ HTTPS Endpoint Identification Algorithm. */
    private boolean tlsEndpointChecking;

    /**
     * Constructor for IMAPSClient. Sets security mode to explicit (isImplicit = false).
     */
    public IMAPSClient() {
        this(DEFAULT_PROTOCOL, false);
    }

    /**
     * Constructor for IMAPSClient.
     *
     * @param implicit The security mode (Implicit/Explicit).
     */
    public IMAPSClient(final boolean implicit) {
        this(DEFAULT_PROTOCOL, implicit);
    }

    /**
     * Constructor for IMAPSClient.
     *
     * @param implicit The security mode(Implicit/Explicit).
     * @param ctx      A pre-configured SSL Context.
     */
    public IMAPSClient(final boolean implicit, final SSLContext ctx) {
        this(DEFAULT_PROTOCOL, implicit, ctx);
    }

    /**
     * Constructor for IMAPSClient.
     *
     * @param context A pre-configured SSL Context.
     */
    public IMAPSClient(final SSLContext context) {
        this(false, context);
    }

    /**
     * Constructor for IMAPSClient.
     *
     * @param proto the protocol.
     */
    public IMAPSClient(final String proto) {
        this(proto, false);
    }

    /**
     * Constructor for IMAPSClient.
     *
     * @param proto    the protocol.
     * @param implicit The security mode(Implicit/Explicit).
     */
    public IMAPSClient(final String proto, final boolean implicit) {
        this(proto, implicit, null);
    }

    /**
     * Constructor for IMAPSClient.
     *
     * @param proto    the protocol.
     * @param implicit The security mode(Implicit/Explicit).
     * @param ctx      the SSL context
     */
    public IMAPSClient(final String proto, final boolean implicit, final SSLContext ctx) {
        setDefaultPort(DEFAULT_IMAPS_PORT);
        protocol = proto;
        isImplicit = implicit;
        context = ctx;
    }

    /**
     * Because there are so many connect() methods, the _connectAction_() method is provided as a means of performing some action immediately after establishing
     * a connection, rather than reimplementing all the connect() methods.
     *
     * @throws IOException If it is thrown by _connectAction_().
     * @see org.apache.commons.net.SocketClient#_connectAction_()
     */
    @Override
    protected void _connectAction_() throws IOException {
        // Implicit mode.
        if (isImplicit) {
            performSSLNegotiation();
        }
        super._connectAction_();
        // Explicit mode - don't do anything. The user calls execTLS()
    }

    /**
     * The TLS command execution.
     *
     * @throws SSLException If the server reply code is not positive.
     * @throws IOException  If an I/O error occurs while sending the command or performing the negotiation.
     * @return TRUE if the command and negotiation succeeded.
     */
    public boolean execTLS() throws SSLException, IOException {
        if (sendCommand(IMAPCommand.getCommand(IMAPCommand.STARTTLS)) != IMAPReply.OK) {
            return false;
            // throw new SSLException(getReplyString());
        }
        performSSLNegotiation();
        return true;
    }

    /**
     * Gets the names of the cipher suites which could be enabled for use on this connection. When the underlying {@link java.net.Socket Socket} is not an
     * {@link SSLSocket} instance, returns null.
     *
     * @return An array of cipher suite names, or {@code null}.
     */
    public String[] getEnabledCipherSuites() {
        if (_socket_ instanceof SSLSocket) {
            return ((SSLSocket) _socket_).getEnabledCipherSuites();
        }
        return null;
    }

    /**
     * Gets the names of the protocol versions which are currently enabled for use on this connection. When the underlying {@link java.net.Socket Socket} is
     * not an {@link SSLSocket} instance, returns null.
     *
     * @return An array of protocols, or {@code null}.
     */
    public String[] getEnabledProtocols() {
        if (_socket_ instanceof SSLSocket) {
            return ((SSLSocket) _socket_).getEnabledProtocols();
        }
        return null;
    }

    /**
     * Gets the currently configured {@link HostnameVerifier}.
     *
     * @return A HostnameVerifier instance.
     * @since 3.4
     */
    public HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    /**
     * Gets the {@link KeyManager} instance.
     *
     * @return The current {@link KeyManager} instance.
     */
    private KeyManager getKeyManager() {
        return keyManager;
    }

    /**
     * Gets the currently configured {@link TrustManager}.
     *
     * @return A TrustManager instance.
     */
    public TrustManager getTrustManager() {
        return trustManager;
    }

    /**
     * Performs a lazy init of the SSL context.
     *
     * @throws IOException When could not initialize the SSL context.
     */
    private void initSSLContext() throws IOException {
        if (context == null) {
            context = SSLContextUtils.createSSLContext(protocol, getKeyManager(), getTrustManager());
        }
    }

    /**
     * Tests whether or not endpoint identification using the HTTPS algorithm on Java 1.7+ is enabled. The default behavior is for this to be disabled.
     *
     * @return True if enabled, false if not.
     * @since 3.4
     */
    public boolean isEndpointCheckingEnabled() {
        return tlsEndpointChecking;
    }

    /**
     * SSL/TLS negotiation. Acquires an SSL socket of a connection and carries out handshake processing.
     *
     * @throws IOException If server negotiation fails.
     */
    private void performSSLNegotiation() throws IOException {
        initSSLContext();

        final SSLSocketFactory ssf = context.getSocketFactory();
        final String host = _hostname_ != null ? _hostname_ : getRemoteAddress().getHostAddress();
        final int port = getRemotePort();
        final SSLSocket socket = (SSLSocket) ssf.createSocket(_socket_, host, port, true);
        socket.setEnableSessionCreation(true);
        socket.setUseClientMode(true);

        if (tlsEndpointChecking) {
            SSLSocketUtils.enableEndpointNameVerification(socket);
        }

        if (protocols != null) {
            socket.setEnabledProtocols(protocols);
        }
        if (suites != null) {
            socket.setEnabledCipherSuites(suites);
        }
        socket.startHandshake();

        // TODO the following setup appears to duplicate that in the super class methods
        _socket_ = socket;
        _input_ = socket.getInputStream();
        _output_ = socket.getOutputStream();
        _reader = new CRLFLineReader(new InputStreamReader(_input_, __DEFAULT_ENCODING));
        __writer = new BufferedWriter(new OutputStreamWriter(_output_, __DEFAULT_ENCODING));

        if (hostnameVerifier != null && !hostnameVerifier.verify(host, socket.getSession())) {
            throw new SSLHandshakeException("Hostname doesn't match certificate");
        }
    }

    /**
     * Sets which particular cipher suites are enabled for use on this connection. Called before server negotiation.
     *
     * @param cipherSuites The cipher suites.
     */
    public void setEnabledCipherSuites(final String[] cipherSuites) {
        suites = cipherSuites.clone();
    }

    /**
     * Sets which particular protocol versions are enabled for use on this connection. I perform setting before a server negotiation.
     *
     * @param protocolVersions The protocol versions.
     */
    public void setEnabledProtocols(final String[] protocolVersions) {
        protocols = protocolVersions.clone();
    }

    /**
     * Sets automatic endpoint identification checking using the HTTPS algorithm is supported on Java 1.7+. The default behavior is for this to be disabled.
     *
     * @param enable Enable automatic endpoint identification checking using the HTTPS algorithm on Java 1.7+.
     * @since 3.4
     */
    public void setEndpointCheckingEnabled(final boolean enable) {
        tlsEndpointChecking = enable;
    }

    /**
     * Sets to override the default {@link HostnameVerifier} to use.
     *
     * @param newHostnameVerifier The HostnameVerifier implementation to set or {@code null} to disable.
     * @since 3.4
     */
    public void setHostnameVerifier(final HostnameVerifier newHostnameVerifier) {
        hostnameVerifier = newHostnameVerifier;
    }

    /**
     * Sets a {@link KeyManager} to use.
     *
     * @param newKeyManager The KeyManager implementation to set.
     * @see org.apache.commons.net.util.KeyManagerUtils
     */
    public void setKeyManager(final KeyManager newKeyManager) {
        keyManager = newKeyManager;
    }

    /**
     * Sets to override the default {@link TrustManager} to use.
     *
     * @param newTrustManager The TrustManager implementation to set.
     * @see org.apache.commons.net.util.TrustManagerUtils
     */
    public void setTrustManager(final TrustManager newTrustManager) {
        trustManager = newTrustManager;
    }
}

