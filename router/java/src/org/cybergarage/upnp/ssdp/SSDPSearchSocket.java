/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.ssdp;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.cybergarage.net.*;
import org.cybergarage.upnp.device.*;
import org.cybergarage.util.*;

/**
 * Socket for sending SSDP search requests and receiving responses.
 *
 * <p>This class extends HTTPMUSocket and implements Runnable to provide asynchronous handling of
 * SSDP search operations. It manages both sending M-SEARCH requests and receiving device responses.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>IPv4 and IPv6 support
 *   <li>Multicast search request broadcasting
 *   <li>Asynchronous response reception
 *   <li>Multi-threaded operation
 *   <li>Network interface binding
 * </ul>
 *
 * <p>This class is used by UPnP control points to perform device discovery by sending M-SEARCH
 * requests to the SSDP multicast address and receiving responses from available devices on the
 * network.
 *
 * @since 1.0
 */
public class SSDPSearchSocket extends HTTPMUSocket implements Runnable {
    /** True when this socket binds an IPv6 address. */
    private boolean useIPv6Address;

    /**
     * Binds the search socket for SSDP multicast traffic.
     *
     * @param bindAddr The address to bind the service
     * @param port The port used for accepting message
     * @param multicast The multicast address to use as destination
     * @throws IOException if the socket cannot be bound to bindAddr
     * @since 1.8
     */
    public SSDPSearchSocket(String bindAddr, int port, String multicast) throws IOException {
        boolean ok = open(bindAddr, multicast);
        if (!ok) throw new IOException("Bind to " + bindAddr + " failed");
    }

    /**
     * Binds the search socket to an IPv4 or IPv6 local address.
     *
     * @param bindAddr the binding address for sending multicast packet
     * @throws IOException if the socket cannot be bound to bindAddr
     * @since 1.8
     */
    public SSDPSearchSocket(InetAddress bindAddr) throws IOException {
        boolean ok;
        if (bindAddr.getAddress().length != 4) {
            ok = this.open((Inet6Address) bindAddr);
        } else {
            ok = this.open((Inet4Address) bindAddr);
        }
        if (!ok) throw new IOException("Bind to " + bindAddr + " failed");
    }

    /**
     * Opens the socket joined to the IPv4 SSDP multicast group.
     *
     * @param bindAddr the IPv4 local address to send multicast packets from
     * @return true if and only if it open the socket
     */
    public boolean open(Inet4Address bindAddr) {
        useIPv6Address = false;
        return open(SSDP.ADDRESS, SSDP.PORT, bindAddr);
    }

    /**
     * Opens the socket joined to the IPv6 SSDP multicast group.
     *
     * @param bindAddr the IPv6 local address to send multicast packets from
     * @return true if and only if it open the socket
     */
    public boolean open(Inet6Address bindAddr) {
        useIPv6Address = true;
        return open(SSDP.getIPv6Address(), SSDP.PORT, bindAddr);
    }

    /**
     * Opens the socket on the given local address and multicast group.
     *
     * @param bind the local address or hostname to send multicast packets from
     * @param multicast the destination multicast address, which must be of the same IP version as bind
     * @return true if and only if it open the socket
     */
    public boolean open(String bind, String multicast) {
        if ((HostInterface.isIPv6Address(bind)) && (HostInterface.isIPv6Address(multicast))) {
            useIPv6Address = true;
        } else if (HostInterface.isIPv4Address(bind) && (HostInterface.isIPv4Address(multicast))) {
            useIPv6Address = false;
        } else {
            throw new IllegalArgumentException(
                    "Cannot open a UDP Socket for IPv6 address on IPv4 interface or viceversa");
        }
        return open(multicast, SSDP.PORT, bind);
    }

    /**
     * Opens the socket, picking the IP version from the bind hostname.
     *
     * @param bindAddr the hostname of the interface to use for sending multicast packet
     * @return true if and only if it open the socket
     * @see org.cybergarage.upnp.ssdp for default multicast and port destination of the packets
     */
    public boolean open(String bindAddr) {
        String addr = SSDP.ADDRESS;
        useIPv6Address = false;
        if (HostInterface.isIPv6Address(bindAddr) == true) {
            addr = SSDP.getIPv6Address();
            useIPv6Address = true;
        }
        return open(addr, SSDP.PORT, bindAddr);
    }

    /** Listeners notified of each device search. */
    private ListenerList deviceSearchListenerList = new ListenerList();

    /**
     * Registers a listener for incoming device searches.
     *
     * @param listener the listener notified for every received search request
     */
    public void addSearchListener(SearchListener listener) {
        deviceSearchListenerList.add(listener);
    }

    /**
     * Unregisters a previously added search listener.
     *
     * @param listener the listener to stop notifying
     */
    public void removeSearchListener(SearchListener listener) {
        deviceSearchListenerList.remove(listener);
    }

    /**
     * Hands a received search request to every registered listener.
     *
     * @param ssdpPacket the received search request to hand to every registered listener
     */
    public void performSearchListener(SSDPPacket ssdpPacket) {
        int listenerSize = deviceSearchListenerList.size();
        for (int n = 0; n < listenerSize; n++) {
            SearchListener listener = (SearchListener) deviceSearchListenerList.get(n);
            listener.deviceSearchReceived(ssdpPacket);
        }
    }

    /** The thread listening for searches. */
    private Thread deviceSearchThread = null;

    /**
     * Receives and dispatches search packets until the thread is replaced.
     */
    public void run() {
        Thread thisThread = Thread.currentThread();

        while (deviceSearchThread == thisThread) {
            Thread.yield();

            SSDPPacket packet = null;
            try {
                packet = receive();
            } catch (IOException e) {
                break;
            }

            if (packet == null) continue;

            // TODO perform delegation with Thread Pooling
            if (packet.isDiscover() == true) performSearchListener(packet);
        }
    }

    /**
     * Launches the receive thread under a name naming the multicast group.
     */
    public void start() {
        StringBuffer name = new StringBuffer("Cyber.SSDPSearchSocket/");
        String localAddr = this.getLocalAddress();
        // localAddr is null on Android m3-rc37a (01/30/08)
        if (localAddr != null && 0 < localAddr.length()) {
            // I2P hide address from thread dumps
            // name.append(this.getLocalAddress()).append(':');
            // name.append(this.getLocalPort()).append(" -> ");
            name.append(this.getMulticastAddress()).append(':');
            name.append(this.getMulticastPort());
        }
        deviceSearchThread = new Thread(this, name.toString());
        deviceSearchThread.setDaemon(true);
        deviceSearchThread.start();
    }

    /**
     * Closes the socket and unhooks the receive thread.
     */
    public void stop() {
        close();

        deviceSearchThread = null;
    }
}
