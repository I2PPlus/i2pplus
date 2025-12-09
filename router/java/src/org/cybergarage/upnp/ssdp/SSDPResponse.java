/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.ssdp;

import org.cybergarage.http.*;

import java.io.InputStream;

/**
 * Represents an SSDP response message.
 *
 * <p>This class extends HTTPResponse to handle SSDP (Simple Service Discovery Protocol) response
 * messages used in UPnP device discovery. It manages the creation and parsing of responses to
 * M-SEARCH requests from control points.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>SSDP response header management
 *   <li>ST (Search Target) header handling
 *   <li>USN (Unique Service Name) header management
 *   <li>MYNAME header support for Intel compatibility
 *   <li>HTTP/1.1 version compliance
 * </ul>
 *
 * <p>This class is used by UPnP devices to respond to discovery searches from control points,
 * providing information about device capabilities and availability. It handles both standard UPnP
 * responses and vendor-specific extensions.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SSDPResponse extends HTTPResponse {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public SSDPResponse() {
        setVersion(HTTP.VERSION_11);
    }

    public SSDPResponse(InputStream in) {
        super(in);
    }

    ////////////////////////////////////////////////
    //	ST (SearchTarget)
    ////////////////////////////////////////////////

    public void setST(String value) {
        setHeader(HTTP.ST, value);
    }

    public String getST() {
        return getHeaderValue(HTTP.ST);
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
    //	MYNAME
    ////////////////////////////////////////////////

    public void setMYNAME(String value) {
        setHeader(HTTP.MYNAME, value);
    }

    public String getMYNAME() {
        return getHeaderValue(HTTP.MYNAME);
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

    ////////////////////////////////////////////////
    //	getHeader (Override)
    ////////////////////////////////////////////////

    public String getHeader() {
        StringBuffer str = new StringBuffer();

        str.append(getStatusLineString());
        str.append(getHeaderString());
        str.append(HTTP.CRLF); // for Intel UPnP control points.

        return str.toString();
    }
}
