/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.ssdp;

import java.io.InputStream;
import org.cybergarage.http.*;

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
    /**
     * Construct an empty request announcing the HTTP/1.1 version SSDP requires.
     */
    public SSDPRequest() {
        setVersion(HTTP.VERSION_11);
    }

    /**
     * Construct a request from headers already read off the wire.
     *
     * @param in the stream to parse the request headers from
     */
    public SSDPRequest(InputStream in) {
        super(in);
    }

    /**
     * Set the NT header, naming the notification type being advertised.
     *
     * @param value the notification type, such as &quot;upnp:rootdevice&quot; or &quot;ssdp:all&quot;
     */
    public void setNT(String value) {
        setHeader(HTTP.NT, value);
    }

    /**
     * Return the NT header.
     *
     * @return the NT header value, or empty string if not set
     */
    public String getNT() {
        return getHeaderValue(HTTP.NT);
    }

    /**
     * Set the NTS header, naming the notification sub type.
     *
     * @param value the notification sub type, either &quot;ssdp:alive&quot; or &quot;ssdp:byebye&quot;
     */
    public void setNTS(String value) {
        setHeader(HTTP.NTS, value);
    }

    /**
     * Return the NTS header.
     *
     * @return the NTS header value, or empty string if not set
     */
    public String getNTS() {
        return getHeaderValue(HTTP.NTS);
    }

    /**
     * Set the Location header, giving the URL of the device description document.
     *
     * @param value the URL of the device description document this request points at
     */
    public void setLocation(String value) {
        setHeader(HTTP.LOCATION, value);
    }

    /**
     * Return the Location header.
     *
     * @return the Location header value, or empty string if not set
     */
    public String getLocation() {
        return getHeaderValue(HTTP.LOCATION);
    }

    /**
     * Set the USN header, naming the service being notified.
     *
     * @param value the unique service name identifying the service being notified
     */
    public void setUSN(String value) {
        setHeader(HTTP.USN, value);
    }

    /**
     * Return the USN header.
     *
     * @return the USN header value, or empty string if not set
     */
    public String getUSN() {
        return getHeaderValue(HTTP.USN);
    }

    /**
     * Set the Cache-Control header, carrying this advertisement's max-age lease.
     *
     * @param len how many seconds this advertisement stays valid, written as max-age
     */
    public void setLeaseTime(int len) {
        setHeader(HTTP.CACHE_CONTROL, "max-age=" + Integer.toString(len));
    }

    /**
     * Return the max-age lease carried by the Cache-Control header.
     *
     * @return the max-age lease in seconds, or 0 if the header is absent or unparseable
     */
    public int getLeaseTime() {
        String cacheCtrl = getHeaderValue(HTTP.CACHE_CONTROL);
        return SSDP.getLeaseTime(cacheCtrl);
    }

    /**
     * Set the BOOTID.UPNP.ORG header, recording the sender's boot generation.
     *
     * @param bootId the device boot ID, incremented on each reboot so stale advertisements are rejected
     */
    public void setBootId(int bootId) {
        setHeader(HTTP.BOOTID_UPNP_ORG, bootId);
    }

    /**
     * Return the BOOTID.UPNP.ORG header.
     *
     * @return the BOOTID.UPNP.ORG value, or 0 if not set
     */
    public int getBootId() {
        return getIntegerHeaderValue(HTTP.BOOTID_UPNP_ORG);
    }
}
