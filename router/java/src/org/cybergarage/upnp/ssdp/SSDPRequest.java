/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.ssdp;

import org.cybergarage.http.*;

import java.io.InputStream;

/**
 * Base class for SSDP request messages.
 *
 * <p>This class extends HTTPRequest to provide common functionality for SSDP (Simple Service
 * Discovery Protocol) request messages used in UPnP device discovery and advertisement. It serves
 * as the foundation for specific SSDP request types.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>SSDP request header management
 *   <li>HTTP/1.1 version compliance
 *   <li>Common SSDP header operations
 *   <li>Request parsing and creation
 *   <li>Multicast communication support
 * </ul>
 *
 * <p>This class provides the base functionality for SSDP M-SEARCH and NOTIFY requests, handling
 * common header management and HTTP protocol compliance for SSDP operations in UPnP networks.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SSDPRequest extends HTTPRequest {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public SSDPRequest() {
        setVersion(HTTP.VERSION_11);
    }

    public SSDPRequest(InputStream in) {
        super(in);
    }

    ////////////////////////////////////////////////
    //	NT
    ////////////////////////////////////////////////

    public void setNT(String value) {
        setHeader(HTTP.NT, value);
    }

    public String getNT() {
        return getHeaderValue(HTTP.NT);
    }

    ////////////////////////////////////////////////
    //	NTS
    ////////////////////////////////////////////////

    public void setNTS(String value) {
        setHeader(HTTP.NTS, value);
    }

    public String getNTS() {
        return getHeaderValue(HTTP.NTS);
    }

    ////////////////////////////////////////////////
    //	Location
    ////////////////////////////////////////////////

    public void setLocation(String value) {
        setHeader(HTTP.LOCATION, value);
    }

    public String getLocation() {
        return getHeaderValue(HTTP.LOCATION);
    }

    ////////////////////////////////////////////////
    //	USN
    ////////////////////////////////////////////////

    public void setUSN(String value) {
        setHeader(HTTP.USN, value);
    }

    public String getUSN() {
        return getHeaderValue(HTTP.USN);
    }

    ////////////////////////////////////////////////
    //	CacheControl
    ////////////////////////////////////////////////

    public void setLeaseTime(int len) {
        setHeader(HTTP.CACHE_CONTROL, "max-age=" + Integer.toString(len));
    }

    public int getLeaseTime() {
        String cacheCtrl = getHeaderValue(HTTP.CACHE_CONTROL);
        return SSDP.getLeaseTime(cacheCtrl);
    }

    ////////////////////////////////////////////////
    //	BootId
    ////////////////////////////////////////////////

    public void setBootId(int bootId) {
        setHeader(HTTP.BOOTID_UPNP_ORG, bootId);
    }

    public int getBootId() {
        return getIntegerHeaderValue(HTTP.BOOTID_UPNP_ORG);
    }
}
