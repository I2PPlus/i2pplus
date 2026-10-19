/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.ssdp;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import org.cybergarage.net.*;
import org.cybergarage.upnp.*;
import org.cybergarage.util.Debug;

/**
 * A list of SSDP notification sockets for receiving SSDP notify messages.<br>
 * This class manages multiple {@link SSDPNotifySocket} instances to handle SSDP notifications
 * across different network interfaces and addresses.
 *
 * @version 1.8
 */
public class SSDPNotifySocketList extends Vector<SSDPNotifySocket> {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    private InetAddress[] binds = null;

    /**
     * Creates an empty socket list with no explicit bind addresses.
     */
    public SSDPNotifySocketList() {}

    /**
     * Create a socket list bound to the given addresses.
     *
     * @param binds The host to bind the service <code>null</code> means to bind to default.
     * @since 1.8
     */
    public SSDPNotifySocketList(InetAddress[] binds) {
        this.binds = binds;
    }

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    /**
     * Returns the notify socket at the given index.
     *
     * @param n the zero-based index of the socket in this list
     * @return the notify socket bound to that address
     */
    public SSDPNotifySocket getSSDPNotifySocket(int n) {
        return get(n);
    }

    ////////////////////////////////////////////////
    //	ControlPoint
    ////////////////////////////////////////////////

    /**
     * Hands the control point to every socket in this list.
     *
     * @param ctrlPoint the control point handed to every socket in this list
     */
    public void setControlPoint(ControlPoint ctrlPoint) {
        int nSockets = size();
        for (int n = 0; n < nSockets; n++) {
            SSDPNotifySocket sock = getSSDPNotifySocket(n);
            sock.setControlPoint(ctrlPoint);
        }
    }

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    /**
     * Opens a notify socket per bind address.
     *
     * @return true once the sockets are opened, even if some bind addresses failed
     */
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
                    SSDPNotifySocket ssdpNotifySocket = new SSDPNotifySocket(bindAddresses[i]);
                    add(ssdpNotifySocket);
                } catch (IOException ioe) {
                    Debug.warning("Failed bind to " + bindAddresses[i], ioe);
                }
            }
        }
        return true;
    }

    /**
     * Closes every socket in this list and empties it.
     */
    public void close() {
        int nSockets = size();
        for (int n = 0; n < nSockets; n++) {
            SSDPNotifySocket sock = getSSDPNotifySocket(n);
            sock.close();
        }
        clear();
    }

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    /**
     * Starts the notification thread on every socket in this list.
     */
    public void start() {
        int nSockets = size();
        for (int n = 0; n < nSockets; n++) {
            SSDPNotifySocket sock = getSSDPNotifySocket(n);
            sock.start();
        }
    }

    /**
     * Stops the notification thread on every socket in this list.
     */
    public void stop() {
        int nSockets = size();
        for (int n = 0; n < nSockets; n++) {
            SSDPNotifySocket sock = getSSDPNotifySocket(n);
            sock.stop();
        }
    }
}
