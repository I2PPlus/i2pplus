/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.http;

import java.net.InetAddress;
import java.util.Set;
import java.util.Vector;
import net.i2p.router.transport.UPnP;
import org.cybergarage.upnp.Device;

/**
 * A collection of HTTPServer instances that extends Vector. This class manages multiple HTTP
 * servers, allowing them to be started, stopped, and configured as a group.
 */
public class HTTPServerList extends Vector<HTTPServer> {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    private InetAddress[] binds = null;
    private int port = Device.HTTP_DEFAULT_PORT;

    /** Creates a new empty HTTPServerList. */
    public HTTPServerList() {}

    /**
     * Creates a new HTTPServerList with specified bind addresses and port.
     *
     * @param list array of InetAddress objects to bind to
     * @param port the port number for all servers
     */
    public HTTPServerList(InetAddress[] list, int port) {
        this.binds = list;
        this.port = port;
    }

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    /**
     * Adds a request listener to all HTTP servers in this list.
     *
     * @param listener the request listener to add
     */
    public void addRequestListener(HTTPRequestListener listener) {
        int nServers = size();
        for (int n = 0; n < nServers; n++) {
            HTTPServer server = getHTTPServer(n);
            server.addRequestListener(listener);
        }
    }

    /**
     * Gets the HTTPServer at the specified index.
     *
     * @param n the index of the server to retrieve
     * @return the HTTPServer at the specified index
     */
    public HTTPServer getHTTPServer(int n) {
        return get(n);
    }

    ////////////////////////////////////////////////
    //	open/close
    ////////////////////////////////////////////////

    /** Closes all HTTP servers in this list. */
    public void close() {
        int nServers = size();
        for (int n = 0; n < nServers; n++) {
            HTTPServer server = getHTTPServer(n);
            server.close();
        }
    }

    /**
     * Opens HTTP servers on all configured bind addresses. If no bind addresses are specified, uses
     * I2P local addresses.
     *
     * @return the number of successfully opened servers
     */
    public int open() {
        InetAddress[] binds = this.binds;
        String[] bindAddresses;
        if (binds != null) {
            bindAddresses = new String[binds.length];
            for (int i = 0; i < binds.length; i++) {
                bindAddresses[i] = binds[i].getHostAddress();
            }
        } else {
            // I2P non-public addresses only
            Set<String> addrs = UPnP.getLocalAddresses();
            bindAddresses = addrs.toArray(new String[addrs.size()]);
        }
        int j = 0;
        for (int i = 0; i < bindAddresses.length; i++) {
            HTTPServer httpServer = new HTTPServer();
            if ((bindAddresses[i] == null) || (httpServer.open(bindAddresses[i], port) == false)) {
                close();
                clear();
            } else {
                add(httpServer);
                j++;
            }
        }
        return j;
    }

    /**
     * Opens HTTP servers on the specified port.
     *
     * @param port the port number to use
     * @return true if at least one server was opened successfully
     */
    public boolean open(int port) {
        this.port = port;
        return open() != 0;
    }

    ////////////////////////////////////////////////
    //	start/stop
    ////////////////////////////////////////////////

    /** Starts all HTTP servers in this list. */
    public void start() {
        int nServers = size();
        for (int n = 0; n < nServers; n++) {
            HTTPServer server = getHTTPServer(n);
            server.start();
        }
    }

    /** Stops all HTTP servers in this list. */
    public void stop() {
        int nServers = size();
        for (int n = 0; n < nServers; n++) {
            HTTPServer server = getHTTPServer(n);
            server.stop();
        }
    }
}
