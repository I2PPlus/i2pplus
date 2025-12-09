/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.device;

import org.cybergarage.upnp.ssdp.*;

/**
 * Interface for receiving SSDP search response messages.
 *
 * <p>This interface defines the contract for objects that wish to receive SSDP search responses
 * from UPnP devices. It provides a callback mechanism for handling responses to M-SEARCH discovery
 * requests.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Device search response callback method
 *   <li>Access to raw SSDP packet data
 *   <li>Real-time response handling
 *   <li>Device discovery result processing
 * </ul>
 *
 * <p>Implementations of this interface can register with UPnP control points to receive
 * asynchronous responses when devices reply to discovery searches, enabling comprehensive device
 * discovery and network mapping.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public interface SearchResponseListener {
    /**
     * Called when an SSDP search response is received from a UPnP device.
     *
     * @param ssdpPacket the received SSDP packet containing search response data
     */
    public void deviceSearchResponseReceived(SSDPPacket ssdpPacket);
}
