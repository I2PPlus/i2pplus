/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.control;

import java.net.*;
import org.cybergarage.http.*;
import org.cybergarage.soap.*;
import org.cybergarage.upnp.*;

/**
 * Base class for UPnP control requests. Extends SOAPRequest to provide functionality for handling
 * both action and query control requests.
 */
public class ControlRequest extends SOAPRequest {
    // I2P see setRequestHost();
    /** Service type for WAN IPv6 firewall control (used for I2P) */
    private static final String WAN_IPV6_CONNECTION =
            "urn:schemas-upnp-org:service:WANIPv6FirewallControl:1";

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /** Default constructor */
    public ControlRequest() {}

    /**
     * Constructs a ControlRequest from an HTTP request.
     *
     * @param httpReq the HTTP request to wrap
     */
    public ControlRequest(HTTPRequest httpReq) {
        set(httpReq);
    }

    ////////////////////////////////////////////////
    //	Query
    ////////////////////////////////////////////////

    /**
     * Checks if this is a query control request.
     *
     * @return true if this is a query request, false otherwise
     */
    public boolean isQueryControl() {
        return isSOAPAction(Control.QUERY_SOAPACTION);
    }

    /**
     * Checks if this is an action control request.
     *
     * @return true if this is an action request, false otherwise
     */
    public boolean isActionControl() {
        return !isQueryControl();
    }

    ////////////////////////////////////////////////
    //	setRequest
    ////////////////////////////////////////////////

    /**
     * Sets the request host and port for the specified service. Handles various edge cases
     * including absolute URLs, missing URLBase, and IPv6 service types.
     *
     * @param service the service to set the host for
     */
    protected void setRequestHost(Service service) {
        String ctrlURL = service.getControlURL();

        // Thanks for Thomas Schulz (2004/03/20)
        String urlBase = service.getRootDevice().getURLBase();
        if (urlBase != null && 0 < urlBase.length()) {
            try {
                URL url = new URL(urlBase);
                String basePath = url.getPath();
                int baseLen = basePath.length();
                if (0 < baseLen) {
                    if (1 < baseLen || (basePath.charAt(0) != '/')) ctrlURL = basePath + ctrlURL;
                }
            } catch (MalformedURLException e) {
            }
        }

        // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (05/21/03)
        setURI(ctrlURL, true);

        // Thanks for Giordano Sassaroli <sassarol@cefriel.it> and Suzan Foster (09/02/03)
        // Thanks for Andre <andre@antiheld.net> (02/18/04)
        String postURL = "";
        if (HTTP.isAbsoluteURL(ctrlURL) == true) postURL = ctrlURL;

        if (postURL == null || postURL.length() <= 0)
            postURL = service.getRootDevice().getURLBase();

        // Thanks for Rob van den Boomen <rob.van.den.boomen@philips.com> (02/17/04)
        // BUGFIX, set urlbase from location string if not set in description.xml
        if (postURL == null || postURL.length() <= 0) {
            // I2P
            // if service is ipv6 service...
            String type = service.getServiceType();
            boolean preferIPv6 = WAN_IPV6_CONNECTION.equals(type);
            postURL = service.getRootDevice().getLocation(preferIPv6);
        }

        String reqHost = HTTP.getHost(postURL);
        int reqPort = HTTP.getPort(postURL);

        setHost(reqHost, reqPort);
        setRequestHost(reqHost);
        setRequestPort(reqPort);
    }
}
