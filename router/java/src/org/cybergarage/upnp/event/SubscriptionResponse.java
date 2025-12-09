/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.upnp.event;

import org.cybergarage.http.*;
import org.cybergarage.upnp.*;

/**
 * Represents a UPnP event subscription response.
 *
 * <p>This class extends HTTPResponse to handle GENA (General Event Notification Architecture)
 * subscription responses for UPnP event notifications. It manages the response to SUBSCRIBE and
 * UNSUBSCRIBE requests, including success and error handling.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Subscription response status management
 *   <li>SID (Subscription ID) header handling
 *   <li>Timeout response management
 *   <li>Error response generation
 *   <li>UPnP server identification
 * </ul>
 *
 * <p>This class is used by UPnP devices to respond to subscription requests from control points,
 * providing subscription confirmation, timeout information, and error status when necessary.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SubscriptionResponse extends HTTPResponse {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public SubscriptionResponse() {
        setServer(UPnP.getServerName());
    }

    public SubscriptionResponse(HTTPResponse httpRes) {
        super(httpRes);
    }

    ////////////////////////////////////////////////
    //	Error
    ////////////////////////////////////////////////

    public void setResponse(int code) {
        setStatusCode(code);
        setContentLength(0);
    }

    ////////////////////////////////////////////////
    //	Error
    ////////////////////////////////////////////////

    public void setErrorResponse(int code) {
        setStatusCode(code);
        setContentLength(0);
    }

    ////////////////////////////////////////////////
    //	SID
    ////////////////////////////////////////////////

    public void setSID(String id) {
        setHeader(HTTP.SID, Subscription.toSIDHeaderString(id));
    }

    public String getSID() {
        return Subscription.getSID(getHeaderValue(HTTP.SID));
    }

    ////////////////////////////////////////////////
    //	Timeout
    ////////////////////////////////////////////////

    public void setTimeout(long value) {
        setHeader(HTTP.TIMEOUT, Subscription.toTimeoutHeaderString(value));
    }

    public long getTimeout() {
        return Subscription.getTimeout(getHeaderValue(HTTP.TIMEOUT));
    }
}
