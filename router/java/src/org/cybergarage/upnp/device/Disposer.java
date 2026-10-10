/*
 * CyberLink for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.upnp.device;

import org.cybergarage.upnp.*;
import org.cybergarage.util.*;

/**
 * Thread for disposing expired UPnP devices and subscriptions.
 *
 * <p>This class extends ThreadCore to provide periodic cleanup of expired UPnP devices and
 * subscriptions in a control point. It runs in the background to maintain an accurate view of
 * available devices and active subscriptions.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Automatic device expiration handling
 *   <li>Subscription cleanup and renewal
 *   <li>Periodic maintenance operations
 *   <li>Multi-threaded execution
 *   <li>Control point integration
 * </ul>
 *
 * <p>This class is used by UPnP control points to automatically remove devices that have become
 * unavailable and clean up expired subscriptions, ensuring efficient resource management and
 * accurate device tracking.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class Disposer extends ThreadCore {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Creates a disposer that watches the given control point.
     *
     * @param ctrlp the control point whose devices and subscriptions are swept
     */
    public Disposer(ControlPoint ctrlp) {
        setControlPoint(ctrlp);
    }

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    private ControlPoint ctrlPoint;

    /**
     * The value.
     */
    /**
     * Set the control point.
     *
     * @param ctrlp the control point to store for later sweeping
     */
    public void setControlPoint(ControlPoint ctrlp) {
        ctrlPoint = ctrlp;
    }

    /**
     * Get the control point.
     *
     * @return the control point being swept, null until one is set
     */
    public ControlPoint getControlPoint() {
        return ctrlPoint;
    }

    ////////////////////////////////////////////////
    //	Thread
    ////////////////////////////////////////////////

    public void run() {
        Thread.currentThread().setName("UPnPDisp");
        ControlPoint ctrlp = getControlPoint();
        long monitorInterval = ctrlp.getExpiredDeviceMonitoringInterval() * 1000;

        while (isRunnable() == true) {
            try {
                Thread.sleep(monitorInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ctrlp.removeExpiredDevices();
            // ctrlp.print();
        }
    }
}
