/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.ssdp;

import java.io.IOException;
import java.net.*;
import org.cybergarage.http.*;
import org.cybergarage.net.*;
import org.cybergarage.upnp.*;
import org.cybergarage.util.Debug;

/**
 * This class identifies a SSDP socket only for <b>notifying packet</b>.
 *
 * @author Satoshi "skonno" Konno
 * @author Stefano "Kismet" Lenzi
 * @version 1.8
 */
public class SSDPNotifySocket extends HTTPMUSocket implements Runnable {
    private boolean useIPv6Address;

    ////////////////////////////////////////////////
    //  Constructor
    ////////////////////////////////////////////////

    /**
     * Opens a notification socket bound to one local address.
     *
     * @param bindAddr the local address the socket is bound to, which also decides
     *                 whether an IPv4 or IPv6 multicast group is joined
     * @throws IOException if the socket cannot be bound to the given address
     */
    public SSDPNotifySocket(String bindAddr) throws IOException {
        String addr = SSDP.ADDRESS;
        useIPv6Address = false;
        if (HostInterface.isIPv6Address(bindAddr) == true) {
            addr = SSDP.getIPv6Address();
            useIPv6Address = true;
        }
        boolean ok = open(addr, SSDP.PORT, bindAddr);
        if (!ok) {
            throw new IOException("Bind to " + bindAddr + " failed");
        }
        Debug.message("Opened SSDP notify socket at " + bindAddr + ':' + SSDP.PORT);
        setControlPoint(null);
    }

    ////////////////////////////////////////////////
    //  ControlPoint
    ////////////////////////////////////////////////

    private ControlPoint controlPoint = null;

    /**
     * Attaches the control point that receives notifications from this socket.
     *
     * @param ctrlp the control point that should receive the notifications sent
     *              through this socket, or null to detach the current one
     */
    public void setControlPoint(ControlPoint ctrlp) {
        this.controlPoint = ctrlp;
    }

    /**
     * Gets the attached control point.
     *
     * @return the control point set by setControlPoint, or null if none is set
     */
    public ControlPoint getControlPoint() {
        return controlPoint;
    }

    /**
     * This method send a {@link SSDPNotifyRequest} over {@link SSDPNotifySocket}
     *
     * @param req the {@link SSDPNotifyRequest} to send
     * @return true if and only if the transmission succeeded<br>
     *         Because it rely on UDP doesn't mean that it's also received
     */
    public boolean post(SSDPNotifyRequest req) {
        String ssdpAddr = SSDP.ADDRESS;
        if (useIPv6Address == true) {
            ssdpAddr = SSDP.getIPv6Address();
        }
        req.setHost(ssdpAddr, SSDP.PORT);
        return post((HTTPRequest) req);
    }

    ////////////////////////////////////////////////
    //  run
    ////////////////////////////////////////////////

    private Thread deviceNotifyThread = null;

    /**
     * Receives multicast notifications and forwards them to the control point.
     */
    public void run() {
        Thread thisThread = Thread.currentThread();
        ControlPoint ctrlPoint = getControlPoint();
        while (deviceNotifyThread == thisThread) {
            Thread.yield();
            SSDPPacket packet = null;
            try {
                packet = receive();
            } catch (IOException e) {
                break;
            }
            if (packet == null) {
                continue;
            }
            InetAddress maddr = getMulticastInetAddress();
            InetAddress pmaddr = packet.getHostInetAddress();
            if (maddr.equals(pmaddr) == false) {
                continue;
            }
            // TODO Must be performed on a different Thread in order to prevent UDP packet losses.
            if (ctrlPoint != null) {
                ctrlPoint.notifyReceived(packet);
            }
        }
    }

    /**
     * Starts the thread that receives notifications on this socket.
     */
    public void start() {
        StringBuffer name = new StringBuffer("Cyber.SSDPNotifySocket/");
        String localAddr = this.getLocalAddress();
        // localAddr is null on Android m3-rc37a (01/30/08)
        if (localAddr != null && 0 < localAddr.length()) {
            // I2P hide address from thread dumps
            name.append(this.getMulticastAddress()).append(':');
            name.append(this.getMulticastPort());
        }
        deviceNotifyThread = new Thread(this, name.toString());
        deviceNotifyThread.setDaemon(true);
        deviceNotifyThread.start();
    }

    /**
     * Stops the receiving thread and closes the socket.
     */
    public void stop() {
        close();
        deviceNotifyThread = null;
    }
}
