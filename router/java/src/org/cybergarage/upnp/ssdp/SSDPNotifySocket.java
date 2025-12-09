/**
 * CyberUPnP for Java Copyright (C) Satoshi Konno 2002-2003 File: SSDPNotifySocket.java
 *
 * <p>This class identifies a SSDP socket only for <b>notifing packet</b>.<br>
 *
 * @author Satoshi "skonno" Konno
 * @author Stefano "Kismet" Lenzi
 * @version 1.8
 */
package org.cybergarage.upnp.ssdp;

import org.cybergarage.http.*;
import org.cybergarage.net.*;
import org.cybergarage.upnp.*;
import org.cybergarage.util.Debug;

import java.io.IOException;
import java.net.*;

public class SSDPNotifySocket extends HTTPMUSocket implements Runnable {
    private boolean useIPv6Address;

    ////////////////////////////////////////////////
    //  Constructor
    ////////////////////////////////////////////////

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

    public void setControlPoint(ControlPoint ctrlp) {
        this.controlPoint = ctrlp;
    }

    public ControlPoint getControlPoint() {
        return controlPoint;
    }

    /**
     * This method send a {@link SSDPNotifyRequest} over {@link SSDPNotifySocket}
     *
     * @param req the {@link SSDPNotifyRequest} to send
     * @return true if and only if the trasmission succeced<br>
     *     Because it rely on UDP doesn't mean that it's also received
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
        deviceNotifyThread.start();
    }

    public void stop() {
        close();
        deviceNotifyThread = null;
    }
}
