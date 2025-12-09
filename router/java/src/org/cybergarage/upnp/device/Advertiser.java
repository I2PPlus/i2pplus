/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.upnp.device;

import org.cybergarage.upnp.*;
import org.cybergarage.util.*;

/**
 * Thread for advertising UPnP device presence on the network.
 *
 * <p>This class extends ThreadCore to provide periodic device advertisement through SSDP NOTIFY
 * messages. It manages the announcement cycle for device presence and implements NMPR (Network
 * Media Profile Requirements) compliance for notification timing.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Periodic SSDP notification sending
 *   <li>NMPR mode compliance
 *   <li>Device announcement management
 *   <li>Multi-threaded operation
 *   <li>Network presence broadcasting
 * </ul>
 *
 * <p>This class is used by UPnP devices to automatically announce their presence on the network at
 * regular intervals, ensuring discovery by control points and maintaining network visibility.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class Advertiser extends ThreadCore {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Constructor for advertiser.
     *
     * @param dev device to advertise
     */
    public Advertiser(Device dev) {
        setDevice(dev);
    }

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    /** Device being advertised */
    private Device device;

    /**
     * Sets the device to advertise.
     *
     * @param dev device to advertise
     */
    public void setDevice(Device dev) {
        device = dev;
    }

    /**
     * Gets the device being advertised.
     *
     * @return current device
     */
    public Device getDevice() {
        return device;
    }

    ////////////////////////////////////////////////
    //	Thread
    ////////////////////////////////////////////////

    public void run() {
        Device dev = getDevice();
        long leaseTime = dev.getLeaseTime();
        long notifyInterval;
        while (isRunnable() == true) {
            notifyInterval = (leaseTime / 4) + (long) ((float) leaseTime * (Math.random() * 0.25f));
            notifyInterval *= 1000;
            try {
                Thread.sleep(notifyInterval);
            } catch (InterruptedException e) {
            }
            dev.announce();
        }
    }
}
