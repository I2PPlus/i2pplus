/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.upnp.device;

import org.cybergarage.upnp.ssdp.*;

/**
 * Interface for receiving SSDP search request messages.
 *
 * <p>This interface defines the contract for objects that wish to receive SSDP M-SEARCH messages
 * from UPnP control points. It provides a callback mechanism for handling incoming device discovery
 * requests.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Device search request callback method
 *   <li>Access to raw SSDP packet data
 *   <li>Real-time search request handling
 *   <li>Device discovery request processing
 * </ul>
 *
 * <p>Implementations of this interface can register with UPnP devices to receive asynchronous
 * search requests from control points, enabling responsive device discovery and advertisement
 * functionality.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public interface SearchListener {
    /**
     * Called when an SSDP search request is received from a UPnP control point.
     *
     * @param ssdpPacket received SSDP packet containing search request data
     */
    public void deviceSearchReceived(SSDPPacket ssdpPacket);
}
