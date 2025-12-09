/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.event;

import java.util.*;

/**
 * A collection of UPnP event subscribers.
 *
 * <p>This class extends Vector to manage a list of Subscriber objects that represent control points
 * subscribed to receive event notifications from a UPnP service. It provides type-safe access and
 * management of the subscriber pool.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Type-safe subscriber collection management
 *   <li>Exception-safe subscriber retrieval
 *   <li>Vector-based implementation for efficient access
 *   <li>Subscriber lookup and management operations
 * </ul>
 *
 * <p>This class is used by UPnP services to maintain a registry of all control points that have
 * subscribed to receive event notifications, enabling efficient broadcast of state variable changes
 * to interested parties.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SubscriberList extends Vector<Subscriber> {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public SubscriberList() {}

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    public Subscriber getSubscriber(int n) {
        Object obj = null;
        try {
            obj = get(n);
        } catch (Exception e) {
            // Return null if get() fails
        }
        return (Subscriber) obj;
    }
}
