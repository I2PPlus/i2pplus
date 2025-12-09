/*
 * CyberHTTP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.http;

import org.cybergarage.util.Debug;
import org.cybergarage.util.ListenerList;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * HTTP server implementation for the CyberLink HTTP framework.
 *
 * <p>This class provides a multithreaded HTTP server that can:
 *
 * <ul>
 *   <li>Accept incoming HTTP connections
 *   <li>Dispatch requests to registered listeners
 *   <li>Handle multiple concurrent connections
 *   <li>Support both IPv4 and IPv6 addresses
 * </ul>
 *
 * <p>The server must be initialized using {@link HTTPServer#open(InetAddress, int)} or {@link
 * HTTPServer#open(String, int)} before starting. Optionally, {@link HTTPRequestListener} instances
 * can be registered to handle incoming requests.
 *
 * @author Satoshi "skonno" Konno
 * @author Stefano "Kismet" Lenzi
 * @version 1.8
 * @since 1.0
 */
public class HTTPServer implements Runnable {

    /** Constants */
    public static final String NAME = "CyberHTTP";

    public static final String VERSION = "1.0";
    public static final int DEFAULT_PORT = 80;

    /**
     * Default timeout connection for HTTP comunication
     *
     * @since 1.8
     */
    public static final int DEFAULT_TIMEOUT = 10 * 1000; // I2P fix

    public static String getName() {
        String osName = System.getProperty("os.name");
        String osVer = System.getProperty("os.version");
        return osName + "/" + osVer + " " + NAME + "/" + VERSION;
    }

    /**
     * Creates a new HTTP server instance. The server must be opened before it can accept
     * connections.
     */
    public HTTPServer() {
        serverSock = null;
    }

    /** ServerSocket */
    private ServerSocket serverSock = null;

    private InetAddress bindAddr = null;
    private int bindPort = 0;

    /**
     * Store the current TCP timeout value The variable should be accessed by getter and setter
     * metho
     */
    protected int timeout = DEFAULT_TIMEOUT;

    /**
     * Gets the server socket.
     *
     * @return the ServerSocket instance
     */
    public ServerSocket getServerSock() {
        return serverSock;
    }

    /**
     * Gets the bind address as a string.
     *
     * @return the bind address string, or empty string if not bound
     */
    public String getBindAddress() {
        if (bindAddr == null) {
            return "";
        }
        return bindAddr.toString();
    }

    /**
     * Gets the bind port.
     *
     * @return bind port number
     */
    public int getBindPort() {
        return bindPort;
    }

    /** open/close */

    /**
     * Gets the current socket timeout value.
     *
     * @return timeout in milliseconds
     * @since 1.8
     */
    public synchronized int getTimeout() {
        return timeout;
    }

    /**
     * Sets the socket timeout value.
     *
     * @param timeout timeout in milliseconds
     * @since 1.8
     */
    public synchronized void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * Opens the server on specified address and port.
     *
     * @param addr bind address for the server
     * @param port port number to bind to
     * @return true if server was opened successfully, false otherwise
     */
    public boolean open(InetAddress addr, int port) {
        if (serverSock != null) {
            return true;
        }
        try {
            bindAddr = addr;
            bindPort = port;
            serverSock = new ServerSocket(bindPort, 0, bindAddr);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Opens the server on specified port with default bind address.
     *
     * @param addr bind address as hostname or IP address string
     * @param port port number to bind to
     * @return true if server was opened successfully, false otherwise
     */
    public boolean open(String addr, int port) {
        try {
            bindAddr = InetAddress.getByName(addr);
            bindPort = port;
            serverSock = new ServerSocket(bindPort, 0, bindAddr);
        } catch (IOException e) {
            Debug.warning("HTTP server open failed " + addr + " " + port, e);
            return false;
        }
        return true;
    }

    /**
     * Closes the server socket and cleans up resources.
     *
     * @return true if server was closed successfully, false otherwise
     */
    public boolean close() {
        if (serverSock == null) {
            return true;
        }
        try {
            serverSock.close();
            serverSock = null;
            bindAddr = null;
            bindPort = 0;
        } catch (Exception e) {
            Debug.warning(e);
            return false;
        }
        return true;
    }

    /**
     * Accepts an incoming connection from the server socket.
     *
     * @return accepted socket with timeout set, or null if no server socket
     */
    public Socket accept() {
        if (serverSock == null) {
            return null;
        }
        try {
            Socket sock = serverSock.accept();
            sock.setSoTimeout(getTimeout());
            return sock;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks if the server socket is opened.
     *
     * @return true if the server socket is not null
     */
    public boolean isOpened() {
        return (serverSock != null) ? true : false;
    }

    /* httpRequest */

    private ListenerList httpRequestListenerList = new ListenerList();

    /**
     * Adds a request listener to receive HTTP request notifications.
     *
     * @param listener the request listener to add
     */
    public void addRequestListener(HTTPRequestListener listener) {
        httpRequestListenerList.add(listener);
    }

    /**
     * Removes a request listener.
     *
     * @param listener the request listener to remove
     */
    public void removeRequestListener(HTTPRequestListener listener) {
        httpRequestListenerList.remove(listener);
    }

    /**
     * Notifies all registered request listeners of an HTTP request.
     *
     * @param httpReq the HTTP request to notify listeners about
     */
    public void performRequestListener(HTTPRequest httpReq) {
        int listenerSize = httpRequestListenerList.size();
        for (int n = 0; n < listenerSize; n++) {
            HTTPRequestListener listener = (HTTPRequestListener) httpRequestListenerList.get(n);
            listener.httpRequestRecieved(httpReq);
        }
    }

    /** run */
    private Thread httpServerThread = null;

    public void run() {
        if (isOpened() == false) {
            return;
        }

        Thread thisThread = Thread.currentThread();

        while (httpServerThread == thisThread) {
            Thread.yield();
            Socket sock;
            try {
                Debug.message("accept ...");
                sock = accept();
                if (sock != null) {
                    Debug.message("sock = " + sock.getRemoteSocketAddress());
                }
            } catch (Exception e) {
                Debug.warning(e);
                break;
            }
            HTTPServerThread httpServThread = new HTTPServerThread(this, sock);
            httpServThread.start();
            Debug.message("httpServThread ...");
        }
    }

    /**
     * Starts the HTTP server thread.
     *
     * @return true if started successfully
     */
    public boolean start() {
        StringBuffer name = new StringBuffer("Cyber.HTTPServer/");
        // I2P hide address from thread dumps
        httpServerThread = new Thread(this, name.toString());
        httpServerThread.start();
        return true;
    }

    /**
     * Stops the HTTP server thread.
     *
     * @return true if stopped successfully
     */
    public boolean stop() {
        httpServerThread = null;
        return true;
    }

    /** I2P */
    /**
     * Returns the bind address as string representation.
     *
     * @return bind address string
     */
    @Override
    public String toString() {
        return getBindAddress();
    }
}
