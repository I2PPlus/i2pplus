/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.event;

/**
 * Interface for receiving UPnP event notifications.
 *
 * <p>This interface defines the contract for objects that wish to receive event notifications from
 * UPnP services. It provides a callback mechanism for handling incoming event messages containing
 * state variable changes.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Event notification callback method
 *   <li>Access to subscription UUID and sequence number
 *   <li>Variable name and value information
 *   <li>Real-time event handling capability
 * </ul>
 *
 * <p>Implementations of this interface can register with UPnP control points to receive
 * asynchronous notifications when subscribed services publish state variable changes, enabling
 * responsive application behavior.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public interface EventListener {
    /**
     * Called when an event notification is received from a UPnP service.
     *
     * @param uuid the subscription UUID identifying the subscription
     * @param seq the sequence number of this event notification
     * @param varName the name of the state variable that changed
     * @param value the new value of the state variable
     */
    public void eventNotifyReceived(String uuid, long seq, String varName, String value);
}
