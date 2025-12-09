/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.ssdp;

import org.cybergarage.http.*;
import org.cybergarage.net.*;
import org.cybergarage.upnp.device.*;

/**
 * Represents an SSDP M-SEARCH request for device discovery.
 *
 * <p>This class extends SSDPRequest to handle M-SEARCH messages used by UPnP control points to
 * discover devices and services on the network. It manages the creation and configuration of search
 * requests with specific targets and timing parameters.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>M-SEARCH method and URI configuration
 *   <li>Search target (ST) specification
 *   <li>MX (Maximum wait time) parameter management
 *   <li>Multicast destination configuration
 *   <li>MAN header specification
 * </ul>
 *
 * <p>This class is used by UPnP control points to initiate device discovery searches, allowing them
 * to find devices matching specific search targets such as device types, service types, or unique
 * device identifiers.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SSDPSearchRequest extends SSDPRequest {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public SSDPSearchRequest(String serachTarget, int mx) {
        setMethod(HTTP.M_SEARCH);
        setURI("*");

        setHeader(HTTP.ST, serachTarget);
        setHeader(HTTP.MX, Integer.toString(mx));
        setHeader(HTTP.MAN, "\"" + MAN.DISCOVER + "\"");
    }

    public SSDPSearchRequest(String serachTarget) {
        this(serachTarget, SSDP.DEFAULT_MSEARCH_MX);
    }

    public SSDPSearchRequest() {
        this(ST.ROOT_DEVICE);
    }

    ////////////////////////////////////////////////
    //	HOST
    ////////////////////////////////////////////////

    public void setLocalAddress(String bindAddr) {
        String ssdpAddr = SSDP.ADDRESS;
        if (HostInterface.isIPv6Address(bindAddr) == true) ssdpAddr = SSDP.getIPv6Address();
        setHost(ssdpAddr, SSDP.PORT);
    }
}
