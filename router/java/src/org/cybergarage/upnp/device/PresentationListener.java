/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.device;

import org.cybergarage.http.HTTPRequest;

/**
 * Interface for receiving UPnP device presentation requests.
 *
 * <p>This interface defines the contract for objects that wish to receive HTTP requests for UPnP
 * device presentation pages. It provides a callback mechanism for handling presentation URL
 * requests from control points or users.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Presentation request callback method
 *   <li>HTTP request access for presentation data
 *   <li>Device presentation page handling
 *   <li>User interface request processing
 *   <li>Device web interface integration
 * </ul>
 *
 * <p>Implementations of this interface can register with UPnP devices to handle presentation page
 * requests, enabling custom device web interfaces and user interaction through standard HTTP
 * protocols.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public interface PresentationListener {
    /**
     * Called when an HTTP request is received for device presentation.
     *
     * @param httpReq the HTTP request for presentation page
     */
    public void httpRequestRecieved(HTTPRequest httpReq);
}
