/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.event;

import org.cybergarage.http.*;
import org.cybergarage.upnp.*;
import org.cybergarage.upnp.device.*;

/**
 * Represents a UPnP event subscription request.
 *
 * <p>This class extends HTTPRequest to handle GENA (General Event Notification Architecture)
 * subscription requests for UPnP event notifications. It manages the subscription process including
 * SUBSCRIBE and UNSUBSCRIBE operations for UPnP services.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>SUBSCRIBE and UNSUBSCRIBE request handling
 *   <li>Service URL resolution and management
 *   <li>Subscription ID (SID) management
 *   <li>Callback URL handling
 *   <li>Timeout and renewal management
 * </ul>
 *
 * <p>This class is used by UPnP control points to subscribe to event notifications from UPnP
 * services, allowing them to receive updates when service state variables change.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SubscriptionRequest extends HTTPRequest {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Create an empty subscription request with no content body.
     */
    public SubscriptionRequest() {
        setContentLength(0);
    }

    /**
     * Copy the method, URI and headers of an existing HTTP request.
     *
     * @param httpReq the request to copy this one's request line and headers from
     */
    public SubscriptionRequest(HTTPRequest httpReq) {
        this();
        set(httpReq);
    }

    ////////////////////////////////////////////////
    //	setRequest
    ////////////////////////////////////////////////

    private void setService(Service service) {
        String eventSubURL = service.getEventSubURL();

        // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (05/21/03)
        setURI(eventSubURL, true);

        String urlBaseStr = "";
        Device dev = service.getDevice();
        if (dev != null) urlBaseStr = dev.getURLBase();

        if (urlBaseStr == null || urlBaseStr.length() <= 0) {
            Device rootDev = service.getRootDevice();
            if (rootDev != null) urlBaseStr = rootDev.getURLBase();
        }

        // Thansk for Markus Thurner <markus.thurner@fh-hagenberg.at> (06/11/2004)
        if (urlBaseStr == null || urlBaseStr.length() <= 0) {
            Device rootDev = service.getRootDevice();
            if (rootDev != null) urlBaseStr = rootDev.getLocation();
        }

        // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (09/02/03)
        if (urlBaseStr == null || urlBaseStr.length() <= 0) {
            if (HTTP.isAbsoluteURL(eventSubURL)) urlBaseStr = eventSubURL;
        }

        String reqHost = HTTP.getHost(urlBaseStr);
        int reqPort = HTTP.getPort(urlBaseStr);

        setHost(reqHost, reqPort);
        setRequestHost(reqHost);
        setRequestPort(reqPort);
    }

    /**
     * Turn this request into an initial SUBSCRIBE for the given service.
     *
     * @param service the service to subscribe to, supplying the event URL and host
     * @param callback the event callback URL in angle brackets, where NOTIFYs go
     * @param timeout the subscription duration in seconds
     */
    public void setSubscribeRequest(Service service, String callback, long timeout) {
        setMethod(Subscription.SUBSCRIBE_METHOD);
        setService(service);
        setCallback(callback);
        setNT(NT.EVENT);
        setTimeout(timeout);
    }

    /**
     * Turn this request into a renewal of an existing subscription.
     *
     * @param service the service holding the subscription, supplying the event URL
     * @param uuid the subscription ID of the subscription being renewed
     * @param timeout the requested subscription duration in seconds
     */
    public void setRenewRequest(Service service, String uuid, long timeout) {
        setMethod(Subscription.SUBSCRIBE_METHOD);
        setService(service);
        setSID(uuid);
        setTimeout(timeout);
    }

    /**
     * Turn this request into an UNSUBSCRIBE, using the service's current
     * subscription ID.
     *
     * @param service the service to cancel for, supplying the event URL and SID
     */
    public void setUnsubscribeRequest(Service service) {
        setMethod(Subscription.UNSUBSCRIBE_METHOD);
        setService(service);
        setSID(service.getSID());
    }

    ////////////////////////////////////////////////
    //	NT
    ////////////////////////////////////////////////

    /**
     * Set the NT (notification type) header.
     *
     * @param value the notification type, e.g. upnp:event for a subscription
     */
    public void setNT(String value) {
        setHeader(HTTP.NT, value);
    }

    /**
     * Get the NT (notification type) header value.
     *
     * @return the notification type, or null if the header is not set
     */
    public String getNT() {
        return getHeaderValue(HTTP.NT);
    }

    /**
     * Test whether an NT header is present and non-empty.
     *
     * @return true if a notification type has been set
     */
    public boolean hasNT() {
        String nt = getNT();
        return (nt != null && 0 < nt.length()) ? true : false;
    }

    ////////////////////////////////////////////////
    //	CALLBACK
    ////////////////////////////////////////////////

    private static final String CALLBACK_START_WITH = "<";
    private static final String CALLBACK_END_WITH = ">";

    /**
     * Set the CALLBACK header, enclosing the value in angle brackets.
     *
     * @param value the event callback URL where NOTIFY requests are delivered
     */
    public void setCallback(String value) {
        setStringHeader(HTTP.CALLBACK, value, CALLBACK_START_WITH, CALLBACK_END_WITH);
    }

    /**
     * Get the CALLBACK header value with the angle brackets stripped.
     *
     * @return the event callback URL, or null if the header is not set
     */
    public String getCallback() {
        return getStringHeaderValue(HTTP.CALLBACK, CALLBACK_START_WITH, CALLBACK_END_WITH);
    }

    /**
     * Test whether a CALLBACK header is present and non-empty.
     *
     * @return true if an event callback URL has been set
     */
    public boolean hasCallback() {
        String callback = getCallback();
        return (callback != null && 0 < callback.length()) ? true : false;
    }

    ////////////////////////////////////////////////
    //	SID
    ////////////////////////////////////////////////

    /**
     * Set the SID header from a bare subscription ID.
     *
     * @param id the subscription ID to record in the header
     */
    public void setSID(String id) {
        setHeader(HTTP.SID, Subscription.toSIDHeaderString(id));
    }

    /**
     * Get the subscription ID from the SID header, dropping the uuid: prefix.
     *
     * @return the bare subscription ID, or an empty string if the header is
     *     unset or carries no SID
     */
    public String getSID() {
        // Thanks for Grzegorz Lehmann and Stefano Lenzi(12/06/04)
        String sid = Subscription.getSID(getHeaderValue(HTTP.SID));
        if (sid == null) return "";
        return sid;
    }

    /**
     * Test whether a SID header is present and non-empty.
     *
     * @return true if a subscription ID has been set
     */
    public boolean hasSID() {
        String sid = getSID();
        return (sid != null && 0 < sid.length()) ? true : false;
    }

    ////////////////////////////////////////////////
    //	Timeout
    ////////////////////////////////////////////////

    /**
     * Set the TIMEOUT header naming the subscription duration.
     *
     * @param value the subscription duration in seconds
     */
    public final void setTimeout(long value) {
        setHeader(HTTP.TIMEOUT, Subscription.toTimeoutHeaderString(value));
    }

    /**
     * Get the subscription duration from the TIMEOUT header.
     *
     * @return the subscription duration in seconds, or 0 if the header is unset
     */
    public long getTimeout() {
        return Subscription.getTimeout(getHeaderValue(HTTP.TIMEOUT));
    }

    ////////////////////////////////////////////////
    //	post (Response)
    ////////////////////////////////////////////////

    /**
     * Send this request and read the reply as a subscription response.
     *
     * @param subRes the response object to populate from the reply
     */
    public void post(SubscriptionResponse subRes) {
        super.post(subRes);
    }

    ////////////////////////////////////////////////
    //	post
    ////////////////////////////////////////////////

    /**
     * Send this request to its host and port and wrap the reply.
     *
     * @return the reply wrapped as a subscription response, never null
     */
    public SubscriptionResponse post() {
        HTTPResponse httpRes = post(getRequestHost(), getRequestPort());
        return new SubscriptionResponse(httpRes);
    }
}
