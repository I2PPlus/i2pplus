/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.ssdp;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Vector;
import org.cybergarage.net.HostInterface;
import org.cybergarage.upnp.device.SearchListener;
import org.cybergarage.util.Debug;

/**
 * A collection of SSDP search sockets.
 *
 * <p>This class extends Vector to manage multiple SSDPSearchSocket objects that handle outgoing
 * SSDP search requests. It provides type-safe management of the socket pool used for device
 * discovery operations.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Multi-socket management for search requests
 *   <li>Control request broadcasting to all sockets
 *   <li>Network interface support
 *   <li>Socket lifecycle management
 *   <li>Efficient search request distribution
 * </ul>
 *
 * <p>This class is used by UPnP control points to manage multiple sockets for sending SSDP M-SEARCH
 * requests across different network interfaces, enabling comprehensive device discovery in
 * multi-homed environments.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SSDPSearchSocketList extends Vector<SSDPSearchSocket> {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    private InetAddress[] binds = null;
    private String multicastIPv4 = SSDP.ADDRESS;
    private String multicastIPv6 = SSDP.getIPv6Address();
    private int port = SSDP.PORT;

    public SSDPSearchSocketList() {}

    /**
     * @param binds The IP address that we will used for bindind the service
     */
    public SSDPSearchSocketList(InetAddress[] binds) {
        this.binds = binds;
    }

    /**
     * @param binds The IP address that we will used for bindind the service
     * @param port The port that we will used for bindind the service
     * @param multicastIPv4 The IPv4 address that we will used for multicast comunication
     * @param multicastIPv6 The IPv6 address that we will used for multicast comunication
     * @since 1.8
     */
    public SSDPSearchSocketList(
            InetAddress[] binds, int port, String multicastIPv4, String multicastIPv6) {
        this.binds = binds;
        this.port = port;
        this.multicastIPv4 = multicastIPv4;
        this.multicastIPv6 = multicastIPv6;
    }

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    public SSDPSearchSocket getSSDPSearchSocket(int n) {
        return get(n);
    }

    public void addSearchListener(SearchListener listener) {
        int nServers = size();
        for (int n = 0; n < nServers; n++) {
            SSDPSearchSocket sock = getSSDPSearchSocket(n);
            sock.addSearchListener(listener);
        }
    }

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    public boolean open() {
        InetAddress[] binds = this.binds;
        String[] bindAddresses;
        if (binds != null) {
            bindAddresses = new String[binds.length];
            for (int i = 0; i < binds.length; i++) {
                bindAddresses[i] = binds[i].getHostAddress();
            }
        } else {
            int nHostAddrs = HostInterface.getNHostAddresses();
            bindAddresses = new String[nHostAddrs];
            for (int n = 0; n < nHostAddrs; n++) {
                bindAddresses[n] = HostInterface.getHostAddress(n);
            }
        }

        for (int i = 0; i < bindAddresses.length; i++) {
            if (bindAddresses[i] != null) {
                try {
                    SSDPSearchSocket ssdpSearchSocket;
                    if (HostInterface.isIPv6Address(bindAddresses[i]))
                        ssdpSearchSocket =
                                new SSDPSearchSocket(bindAddresses[i], port, multicastIPv6);
                    else
                        ssdpSearchSocket =
                                new SSDPSearchSocket(bindAddresses[i], port, multicastIPv4);
                    add(ssdpSearchSocket);
                } catch (IOException ioe) {
                    Debug.warning("Failed bind to " + bindAddresses[i], ioe);
                }
            }
        }
        return true;
    }

    public void close() {
        int nSockets = size();
        for (int n = 0; n < nSockets; n++) {
            SSDPSearchSocket sock = getSSDPSearchSocket(n);
            sock.close();
        }
        clear();
    }

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    public void start() {
        int nSockets = size();
        for (int n = 0; n < nSockets; n++) {
            SSDPSearchSocket sock = getSSDPSearchSocket(n);
            sock.start();
        }
    }

    public void stop() {
        int nSockets = size();
        for (int n = 0; n < nSockets; n++) {
            SSDPSearchSocket sock = getSSDPSearchSocket(n);
            sock.stop();
        }
    }
}
