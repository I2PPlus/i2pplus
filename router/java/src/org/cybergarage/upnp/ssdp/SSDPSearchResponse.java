/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.ssdp;

import org.cybergarage.http.*;
import org.cybergarage.upnp.*;

/**
 * Represents an SSDP search response message.
 *
 * <p>This class extends SSDPResponse to handle responses to M-SEARCH requests sent by UPnP control
 * points. It provides specialized functionality for creating device discovery responses with
 * appropriate headers and timing.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Automatic OK status code setting
 *   <li>Cache-control header management
 *   <li>Server identification
 *   <li>EXT header for UPnP compliance
 *   <li>Default lease time configuration
 * </ul>
 *
 * <p>This class is used by UPnP devices to respond to discovery searches from control points,
 * providing standardized response format with required UPnP headers and device information to
 * enable successful device discovery and connection establishment.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SSDPSearchResponse extends SSDPResponse {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public SSDPSearchResponse() {
        setStatusCode(HTTPStatus.OK);
        setCacheControl(Device.DEFAULT_LEASE_TIME);
        setHeader(HTTP.SERVER, UPnP.getServerName());
        setHeader(HTTP.EXT, "");
    }
}
