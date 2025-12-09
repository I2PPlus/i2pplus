/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.upnp.ssdp;

import org.cybergarage.http.HTTP;

/**
 * Represents an SSDP NOTIFY request for device advertisement.
 *
 * <p>This class extends SSDPRequest to handle NOTIFY messages used by UPnP devices to advertise
 * their presence and capabilities on the network. It manages the creation of notification messages
 * for device discovery and presence announcement.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>NOTIFY method and URI configuration
 *   <li>Device advertisement management
 *   <li>Presence announcement support
 *   <li>Bye-bye notification handling
 *   <li>Multicast destination configuration
 * </ul>
 *
 * <p>This class is used by UPnP devices to send NOTIFY messages announcing their availability,
 * capabilities, and departure from the network. It enables control points to discover and track
 * device presence automatically.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SSDPNotifyRequest extends SSDPRequest {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public SSDPNotifyRequest() {
        setMethod(HTTP.NOTIFY);
        setURI("*");
    }
}
