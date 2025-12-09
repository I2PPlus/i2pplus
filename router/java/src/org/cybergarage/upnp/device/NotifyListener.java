/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.device;

import org.cybergarage.upnp.ssdp.*;

/**
 * Interface for receiving SSDP notification messages.
 *
 * <p>This interface defines the contract for objects that wish to receive SSDP NOTIFY messages from
 * UPnP devices. It provides a callback mechanism for handling incoming device advertisements and
 * presence notifications.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Device notification callback method
 *   <li>Access to raw SSDP packet data
 *   <li>Real-time notification handling
 *   <li>Device presence monitoring
 * </ul>
 *
 * <p>Implementations of this interface can register with UPnP control points to receive
 * asynchronous notifications when devices advertise their presence or announce their departure from
 * the network.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public interface NotifyListener {
    /**
     * Called when an SSDP notification is received from a UPnP device.
     *
     * @param ssdpPacket the received SSDP packet containing notification data
     */
    public void deviceNotifyReceived(SSDPPacket ssdpPacket);
}
