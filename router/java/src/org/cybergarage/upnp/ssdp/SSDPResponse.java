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
 * @author Satoshi Konno
 * @since 1.0
 */
public class SSDPResponse extends HTTPResponse {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * SSDPResponse.
     */
    public SSDPResponse() {
        setVersion(HTTP.VERSION_11);
    }

    /**
     * SSDPResponse.
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
     * setST.
     *
     * @param value the search target, identifying which service answered
     */
    public void setST(String value) {
        setHeader(HTTP.ST, value);
    }

    /**
     * getST.
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
     * setLocation.
     *
     * @param value the URL of the device description document that was searched
     */
    public void setLocation(String value) {
        setHeader(HTTP.LOCATION, value);
    }

    /**
     * getLocation.
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
     * setUSN.
     *
     * @param value the unique service name identifying the answering device
     */
    public void setUSN(String value) {
        setHeader(HTTP.USN, value);
    }

    /**
     * getUSN.
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
     * setMYNAME.
     *
     * @param value the friendly device name, for Intel's MYNAME extension
     */
    public void setMYNAME(String value) {
        setHeader(HTTP.MYNAME, value);
    }

    /**
     * getMYNAME.
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
     * setLeaseTime.
     *
     * @param len the advertisement lifetime in seconds, advertised as max-age
     */
    public void setLeaseTime(int len) {
        setHeader(HTTP.CACHE_CONTROL, "max-age=" + Integer.toString(len));
    }

    /**
     * getLeaseTime.
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
     * setBootId.
     *
     * @param bootId the device's boot identifier, bumped on each reboot
     */
    public void setBootId(int bootId) {
        setHeader(HTTP.BOOTID_UPNP_ORG, bootId);
    }

    /**
     * getBootId.
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
     * getHeader.
     */
    public String getHeader() {
        StringBuffer str = new StringBuffer();

        str.append(getStatusLineString());
        str.append(getHeaderString());
        str.append(HTTP.CRLF); // for Intel UPnP control points.

        return str.toString();
    }
}
