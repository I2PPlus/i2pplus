/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

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

    /**
     * Creates an empty response naming this library as the server.
     */
    public SubscriptionResponse() {
        setServer(UPnP.getServerName());
    }

    /**
     * Wraps the response received from the subscriber.
     *
     * @param httpRes the response received from the subscriber, holding the headers
     */
    public SubscriptionResponse(HTTPResponse httpRes) {
        super(httpRes);
    }

    ////////////////////////////////////////////////
    //	Error
    ////////////////////////////////////////////////

    /**
     * Sets the status code and clears the body length.
     *
     * @param code the HTTP status code to record, sent with an empty body
     */
    public void setResponse(int code) {
        setStatusCode(code);
        setContentLength(0);
    }

    ////////////////////////////////////////////////
    //	Error
    ////////////////////////////////////////////////

    /**
     * Sets the error status code and clears the body length.
     *
     * @param code the HTTP error status code to record, sent with an empty body
     */
    public void setErrorResponse(int code) {
        setStatusCode(code);
        setContentLength(0);
    }

    ////////////////////////////////////////////////
    //	SID
    ////////////////////////////////////////////////

    /**
     * Writes the subscription identifier to the SID header.
     *
     * @param id the bare subscription identifier to publish in the SID header
     */
    public void setSID(String id) {
        setHeader(HTTP.SID, Subscription.toSIDHeaderString(id));
    }

    /**
     * Returns the subscription identifier from the SID header.
     *
     * @return the subscription identifier from the SID header, or the empty string when absent
     */
    public String getSID() {
        return Subscription.getSID(getHeaderValue(HTTP.SID));
    }

    ////////////////////////////////////////////////
    //	Timeout
    ////////////////////////////////////////////////

    /**
     * Writes the subscription lifetime to the TIMEOUT header.
     *
     * @param value the subscription lifetime in seconds, or INFINITE_VALUE for unlimited
     */
    public void setTimeout(long value) {
        setHeader(HTTP.TIMEOUT, Subscription.toTimeoutHeaderString(value));
    }

    /**
     * Returns the subscription lifetime from the TIMEOUT header.
     *
     * @return the subscription lifetime in seconds from the TIMEOUT header, or INFINITE_VALUE
     *         when the header is absent or unparseable
     */
    public long getTimeout() {
        return Subscription.getTimeout(getHeaderValue(HTTP.TIMEOUT));
    }
}
