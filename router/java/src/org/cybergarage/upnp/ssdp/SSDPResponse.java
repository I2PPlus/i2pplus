/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.ssdp;

import java.io.InputStream;
import org.cybergarage.http.*;

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
 * @since 1.0
 */
public class SSDPResponse extends HTTPResponse {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Creates an empty response declaring HTTP 1.1.
     */
    public SSDPResponse() {
        setVersion(HTTP.VERSION_11);
    }

    /**
     * Creates a response whose headers are parsed from a stream.
     *
     * @param in the response stream to parse the headers from
     */
    public SSDPResponse(InputStream in) {
        super(in);
    }

    ////////////////////////////////////////////////
    //	ST (SearchTarget)
    ////////////////////////////////////////////////

    /**
     * Sets the search target header.
     *
     * @param value the search target, identifying which service answered
     */
    public void setST(String value) {
        setHeader(HTTP.ST, value);
    }

    /**
     * Gets the search target header.
     *
     * @return the ST header, or null if this response carries none
     */
    public String getST() {
        return getHeaderValue(HTTP.ST);
    }

    ////////////////////////////////////////////////
    //	Location
    ////////////////////////////////////////////////

    /**
     * Sets the Location header to a device description URL.
     *
     * @param value the URL of the device description document that was searched
     */
    public void setLocation(String value) {
        setHeader(HTTP.LOCATION, value);
    }

    /**
     * Gets the Location header.
     *
     * @return the Location header, or null if this response carries none
     */
    public String getLocation() {
        return getHeaderValue(HTTP.LOCATION);
    }

    ////////////////////////////////////////////////
    //	USN
    ////////////////////////////////////////////////

    /**
     * Sets the unique service name header.
     *
     * @param value the unique service name identifying the answering device
     */
    public void setUSN(String value) {
        setHeader(HTTP.USN, value);
    }

    /**
     * Gets the unique service name header.
     *
     * @return the USN header, or null if this response carries none
     */
    public String getUSN() {
        return getHeaderValue(HTTP.USN);
    }

    ////////////////////////////////////////////////
    //	MYNAME
    ////////////////////////////////////////////////

    /**
     * Sets the MYNAME header, Intel's friendly-name extension.
     *
     * @param value the friendly device name, for Intel's MYNAME extension
     */
    public void setMYNAME(String value) {
        setHeader(HTTP.MYNAME, value);
    }

    /**
     * Gets the MYNAME header.
     *
     * @return the MYNAME header, or null if this response carries none
     */
    public String getMYNAME() {
        return getHeaderValue(HTTP.MYNAME);
    }

    ////////////////////////////////////////////////
    //	CacheControl
    ////////////////////////////////////////////////

    /**
     * Sets the CACHE-CONTROL max-age header from a lifetime in seconds.
     *
     * @param len the advertisement lifetime in seconds, advertised as max-age
     */
    public void setLeaseTime(int len) {
        setHeader(HTTP.CACHE_CONTROL, "max-age=" + Integer.toString(len));
    }

    /**
     * Gets the advertisement lifetime from the CACHE-CONTROL header.
     *
     * @return the max-age value in seconds, 0 if the header is absent or unparsable
     */
    public int getLeaseTime() {
        String cacheCtrl = getHeaderValue(HTTP.CACHE_CONTROL);
        return SSDP.getLeaseTime(cacheCtrl);
    }

    ////////////////////////////////////////////////
    //	BootId
    ////////////////////////////////////////////////

    /**
     * Sets the boot identifier header.
     *
     * @param bootId the device's boot identifier, bumped on each reboot
     */
    public void setBootId(int bootId) {
        setHeader(HTTP.BOOTID_UPNP_ORG, bootId);
    }

    /**
     * Gets the boot identifier header.
     *
     * @return the BOOTID.UPNP.ORG value, 0 if this response carries none
     */
    public int getBootId() {
        return getIntegerHeaderValue(HTTP.BOOTID_UPNP_ORG);
    }

    ////////////////////////////////////////////////
    //	getHeader (Override)
    ////////////////////////////////////////////////

    /**
     * Renders the whole response as a status line, headers and blank line.
     */
    public String getHeader() {
        StringBuffer str = new StringBuffer();

        str.append(getStatusLineString());
        str.append(getHeaderString());
        str.append(HTTP.CRLF); // for Intel UPnP control points.

        return str.toString();
    }
}
