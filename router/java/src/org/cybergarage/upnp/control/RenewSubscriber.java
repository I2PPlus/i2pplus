/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.control;

import org.cybergarage.upnp.*;
import org.cybergarage.util.*;

/**
 * Thread for automatically renewing UPnP event subscriptions. Runs periodically to refresh
 * subscriptions before they expire.
 */
public class RenewSubscriber extends ThreadCore {
    /** Default renewal interval in seconds (120 seconds = 2 minutes) */
    public static final long INTERVAL = 120;

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Constructs a RenewSubscriber for the specified control point.
     *
     * @param ctrlp the control point whose subscriptions should be renewed
     */
    public RenewSubscriber(ControlPoint ctrlp) {
        setControlPoint(ctrlp);
    }

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    private ControlPoint ctrlPoint;

    /**
     * Sets the control point for this subscriber.
     *
     * @param ctrlp the control point to set
     */
    public void setControlPoint(ControlPoint ctrlp) {
        ctrlPoint = ctrlp;
    }

    /**
     * Gets the control point for this subscriber.
     *
     * @return the control point
     */
    public ControlPoint getControlPoint() {
        return ctrlPoint;
    }

    ////////////////////////////////////////////////
    //	Thread
    ////////////////////////////////////////////////

    /**
     * Main thread loop that periodically renews subscriptions. Sleeps for the configured interval,
     * then calls the control point to renew all active subscriptions.
     */
    public void run() {
        ControlPoint ctrlp = getControlPoint();
        long renewInterval = INTERVAL * 1000;
        while (isRunnable() == true) {
            try {
                Thread.sleep(renewInterval);
            } catch (InterruptedException e) {
            }
            ctrlp.renewSubscriberService();
        }
    }
}
