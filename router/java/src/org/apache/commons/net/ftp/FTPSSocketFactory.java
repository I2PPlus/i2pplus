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

package org.apache.commons.net.ftp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;

/**
 *
 * Socket factory for FTPS connections.
 *
 * @since 2.0
 */
public class FTPSSocketFactory extends SocketFactory {

    private final SSLContext context;

    /**
     * Constructs a new instance.
     *
     * @param context The SSL context.
     */
    public FTPSSocketFactory(final SSLContext context) {
        this.context = context;
    }

    /**
     * @param port the port
     * @return the socket
     * @throws IOException on error
     * @deprecated (2.2) use {@link FTPSServerSocketFactory#createServerSocket(int)}.
     */
    @Deprecated
    public java.net.ServerSocket createServerSocket(final int port) throws IOException {
        return init(context.getServerSocketFactory().createServerSocket(port));
    }

    /**
     * @param port    the port
     * @param backlog the backlog
     * @return the socket
     * @throws IOException on error
     * @deprecated (2.2) use {@link FTPSServerSocketFactory#createServerSocket(int, int)}.
     */
    @Deprecated
    public java.net.ServerSocket createServerSocket(final int port, final int backlog) throws IOException {
        return init(context.getServerSocketFactory().createServerSocket(port, backlog));
    }

    /**
     * @param port      the port
     * @param backlog   the backlog
     * @param ifAddress the interface
     * @return the socket
     * @throws IOException on error
     * @deprecated (2.2) use {@link FTPSServerSocketFactory#createServerSocket(int, int, InetAddress)}.
     */
    @Deprecated
    public java.net.ServerSocket createServerSocket(final int port, final int backlog, final InetAddress ifAddress) throws IOException {
        return init(context.getServerSocketFactory().createServerSocket(port, backlog, ifAddress));
    }

    // Override the default implementation
    @Override
    public Socket createSocket() throws IOException {
        return context.getSocketFactory().createSocket();
    }

    @Override
    public Socket createSocket(final InetAddress address, final int port) throws IOException {
        return context.getSocketFactory().createSocket(address, port);
    }

    // DEPRECATED METHODS - for API compatibility only - DO NOT USE

    @Override
    public Socket createSocket(final InetAddress address, final int port, final InetAddress localAddress, final int localPort) throws IOException {
        return context.getSocketFactory().createSocket(address, port, localAddress, localPort);
    }

    @Override
    public Socket createSocket(final String address, final int port) throws UnknownHostException, IOException {
        return context.getSocketFactory().createSocket(address, port);
    }

    @Override
    public Socket createSocket(final String address, final int port, final InetAddress localAddress, final int localPort)
            throws UnknownHostException, IOException {
        return context.getSocketFactory().createSocket(address, port, localAddress, localPort);
    }

    /**
     * @param socket the socket
     * @return the socket
     * @throws IOException on error
     * @deprecated (2.2) use {@link FTPSServerSocketFactory#init(java.net.ServerSocket)}
     */
    @Deprecated
    public java.net.ServerSocket init(final java.net.ServerSocket socket) throws IOException {
        ((javax.net.ssl.SSLServerSocket) socket).setUseClientMode(true);
        return socket;
    }

}
