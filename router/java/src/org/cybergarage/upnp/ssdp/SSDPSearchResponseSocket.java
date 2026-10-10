/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.ssdp;

import java.net.DatagramSocket;
import org.cybergarage.upnp.*;

/**
 * Socket for receiving SSDP search responses from UPnP devices.
 *
 * <p>This class extends HTTPUSocket and implements Runnable to provide asynchronous handling of
 * SSDP search responses. It listens for responses to M-SEARCH messages sent during device discovery
 * operations.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Asynchronous response reception
 *   <li>SSDP search response parsing
 *   <li>Multi-threaded operation
 *   <li>Network interface binding
 *   <li>Control point notification
 * </ul>
 *
 * <p>This class is used by UPnP control points to receive responses from devices when performing
 * device discovery searches. It runs in a separate thread to continuously listen for incoming
 * search responses without blocking the main application.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SSDPSearchResponseSocket extends HTTPUSocket implements Runnable {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Bind a search response socket to the default address and port.
     */
    public SSDPSearchResponseSocket() {
        setControlPoint(null);
    }

    /**
     * Bind a search response socket to the given address and port.
     *
     * @param bindAddr the address to bind to
     * @param port the port to bind to
     */
    public SSDPSearchResponseSocket(String bindAddr, int port) {
        super(bindAddr, port);
        setControlPoint(null);
    }

    ////////////////////////////////////////////////
    //	ControlPoint
    ////////////////////////////////////////////////

    private ControlPoint controlPoint = null;

    /**
     * Attach the control point this socket reports search results to.
     *
     * @param ctrlp the control point, may be null
     */
    public void setControlPoint(ControlPoint ctrlp) {
        this.controlPoint = ctrlp;
    }

    /**
     * The control point this socket reports search results to.
     *
     * @return the control point, null when none is attached
     */
    public ControlPoint getControlPoint() {
        return controlPoint;
    }

    ////////////////////////////////////////////////
    //	run
    ////////////////////////////////////////////////

    private Thread deviceSearchResponseThread = null;

    /**
     * Watch for inbound search requests and answer them.
     */
    public void run() {
        Thread thisThread = Thread.currentThread();

        ControlPoint ctrlPoint = getControlPoint();

        while (deviceSearchResponseThread == thisThread) {
            Thread.yield();
            SSDPPacket packet = receive();
            if (packet == null) break;
            if (ctrlPoint != null) ctrlPoint.searchResponseReceived(packet);
        }
    }

    /**
     * Start the response thread.
     */
    public void start() {

        StringBuffer name = new StringBuffer("Cyber.SSDPSearchResponseSocket/");
        DatagramSocket s = getDatagramSocket();
        // localAddr is null on Android m3-rc37a (01/30/08)
        // I2P hide address from thread dumps
        // InetAddress localAddr = s.getLocalAddress();
        // if (localAddr != null) {
        //	name.append(s.getLocalAddress()).append(':');
        //	name.append(s.getLocalPort());
        // }
        deviceSearchResponseThread = new Thread(this, name.toString());
        deviceSearchResponseThread.setDaemon(true);
        deviceSearchResponseThread.start();
    }

    /**
     * Stop the response thread.
     */
    public void stop() {
        deviceSearchResponseThread = null;
    }

    ////////////////////////////////////////////////
    //	post
    ////////////////////////////////////////////////

    /**
     * Send a search response back to the requester.
     *
     * @param addr the address to send to
     * @param port the port to send to
     * @param res the response to send
     * @return true if the datagram was sent
     */
    public boolean post(String addr, int port, SSDPSearchResponse res) {
        return post(addr, port, res.getHeader());
    }

    ////////////////////////////////////////////////
    //	post
    ////////////////////////////////////////////////

    /**
     * Send a search request to the given address.
     *
     * @param addr the address to send to
     * @param port the port to send to
     * @param req the request to send
     * @return true if the datagram was sent
     */
    public boolean post(String addr, int port, SSDPSearchRequest req) {
        return post(addr, port, req.toString());
    }
}
