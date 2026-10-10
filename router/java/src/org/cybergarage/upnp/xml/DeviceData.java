/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.xml;

import java.io.*;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import org.cybergarage.http.*;
import org.cybergarage.upnp.*;
import org.cybergarage.upnp.device.*;
import org.cybergarage.upnp.ssdp.*;
import org.cybergarage.util.*;

/** I2P added multiple location support */
/**
 * Data container for UPnP device information and metadata.
 *
 * <p>This class extends NodeData to represent device definitions from UPnP device descriptions. It
 * encapsulates metadata about devices including description files, locations, and advertisement
 * settings.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Device description file management
 *   <li>Multiple location support (I2P enhancement)
 *   <li>Advertisement configuration
 *   <li>XML node data inheritance
 *   <li>Device metadata handling
 * </ul>
 *
 * <p>This class is used by UPnP devices to manage their description data and configuration,
 * enabling proper device advertisement and service discovery functionality.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class DeviceData extends NodeData {
    /** Default constructor */
    public DeviceData() {}

    ////////////////////////////////////////////////
    // description
    ////////////////////////////////////////////////

    private String descriptionURI = null;
    private File descriptionFile = null;

    /**
     * getDescriptionFile.
     *
     * @return the local file backing the description, or null if this device was not registered from a file
     */
    public File getDescriptionFile() {
        return descriptionFile;
    }

    /**
     * getDescriptionURI.
     *
     * @return the URL the description was fetched from, or null if it was parsed from a file
     */
    public String getDescriptionURI() {
        return descriptionURI;
    }

    /**
     * setDescriptionFile.
     *
     * @param descriptionFile the local file this description was parsed from, or null when it was fetched over the network
     */
    public void setDescriptionFile(File descriptionFile) {
        this.descriptionFile = descriptionFile;
    }

    /**
     * setDescriptionURI.
     *
     * @param descriptionURI the URL this description was fetched from, or null when it was parsed from a file
     */
    public void setDescriptionURI(String descriptionURI) {
        this.descriptionURI = descriptionURI;
    }

    ////////////////////////////////////////////////
    // description
    ////////////////////////////////////////////////

    private String location = "";
    private String location_ipv6 = "";

    /**
     * getLocation.
     *
     * @return the control URL of this device, preferring the IPv4 form
     */
    public String getLocation() {
        return getLocation(false);
    }

    /**
     * I2P for multiple location support.
     *
     * @param preferIPv6 whether to prefer IPv6 address
     * @return the location string
     * @since 0.9.50
     */
    public String getLocation(boolean preferIPv6) {
        if (preferIPv6) {
            if (location_ipv6 != null && !location_ipv6.isEmpty()) return location_ipv6;
            return location;
        } else {
            if (location != null && !location.isEmpty()) return location;
            return location_ipv6;
        }
    }

    /**
     * setLocation.
     *
     * @param location the control URL to advertise; a host in brackets is filed as the IPv6 location, and a malformed URL is rejected with a warning
     */
    public void setLocation(String location) {
        if (location != null) {
            try {
                URL url = new URL(location);
                String host = url.getHost();
                if (host != null && host.startsWith("[")) {
                    location_ipv6 = location;
                    return;
                }
            } catch (MalformedURLException me) {
                Debug.warning("Bad location: " + location, me);
                return;
            }
        }
        this.location = location;
    }

    ////////////////////////////////////////////////
    //	LeaseTime
    ////////////////////////////////////////////////

    private int leaseTime = Device.DEFAULT_LEASE_TIME;

    /**
     * getLeaseTime.
     *
     * @return the advertised lease duration in seconds
     */
    public int getLeaseTime() {
        return leaseTime;
    }

    /**
     * setLeaseTime.
     *
     * @param val the advertised lease duration in seconds
     */
    public void setLeaseTime(int val) {
        leaseTime = val;
    }

    ////////////////////////////////////////////////
    //	HTTPServer
    ////////////////////////////////////////////////

    private HTTPServerList httpServerList = null;

    /**
     * getHTTPServerList.
     *
     * @return the HTTP server list, lazily created on first use from the bind addresses and port
     */
    public HTTPServerList getHTTPServerList() {
        if (this.httpServerList == null) {
            this.httpServerList = new HTTPServerList(this.httpBinds, this.httpPort);
        }
        return this.httpServerList;
    }

    private InetAddress[] httpBinds = null;

    /**
     * setHTTPBindAddress.
     *
     * @param inets the addresses the embedded HTTP server binds to, or null for the default behaviour
     */
    public void setHTTPBindAddress(InetAddress[] inets) {
        this.httpBinds = inets;
    }

    /**
     * getHTTPBindAddress.
     *
     * @return the addresses the embedded HTTP server binds to, or null when the default behaviour applies
     */
    public InetAddress[] getHTTPBindAddress() {
        return this.httpBinds;
    }

    ////////////////////////////////////////////////
    //	httpPort
    ////////////////////////////////////////////////

    private int httpPort = Device.HTTP_DEFAULT_PORT;

    /**
     * getHTTPPort.
     *
     * @return the port the embedded HTTP server listens on
     */
    public int getHTTPPort() {
        return httpPort;
    }

    /**
     * setHTTPPort.
     *
     * @param port the port the embedded HTTP server should listen on
     */
    public void setHTTPPort(int port) {
        httpPort = port;
    }

    ////////////////////////////////////////////////
    // controlActionListenerList
    ////////////////////////////////////////////////

    private ListenerList controlActionListenerList = new ListenerList();

    /**
     * getControlActionListenerList.
     *
     * @return the list notified when a control action message arrives
     */
    public ListenerList getControlActionListenerList() {
        return controlActionListenerList;
    }

    /*
    	public void setControlActionListenerList(ListenerList controlActionListenerList) {
    		this.controlActionListenerList = controlActionListenerList;
    	}
    */

    ////////////////////////////////////////////////
    // SSDPSearchSocket
    ////////////////////////////////////////////////

    private SSDPSearchSocketList ssdpSearchSocketList = null;
    private String ssdpMulticastIPv4 = SSDP.ADDRESS;
    private String ssdpMulticastIPv6 = SSDP.getIPv6Address();
    private int ssdpPort = SSDP.PORT;
    private InetAddress[] ssdpBinds = null;

    /**
     * getSSDPSearchSocketList.
     *
     * @return the SSDP search socket list, lazily created on first use from the bind addresses, port and multicast addresses
     */
    public SSDPSearchSocketList getSSDPSearchSocketList() {
        if (this.ssdpSearchSocketList == null) {
            this.ssdpSearchSocketList =
                    new SSDPSearchSocketList(
                            this.ssdpBinds, ssdpPort, ssdpMulticastIPv4, ssdpMulticastIPv6);
        }
        return ssdpSearchSocketList;
    }

    /**
     * Sets the SSDP port for binding.
     *
     * @param port The port to use for binding the SSDP service. The port will be used as source
     *     port for all SSDP messages
     * @since 1.8
     */
    public void setSSDPPort(int port) {
        this.ssdpPort = port;
    }

    /**
     * Returns the SSDP port used for binding.
     *
     * @return The port used for binding the SSDP service. The port will be used as source port for
     *     all SSDP messages
     */
    public int getSSDPPort() {
        return this.ssdpPort;
    }

    /**
     * Sets the SSDP bind addresses.
     *
     * @param inets The <code>InetAddress</code> that will be binded for listing this service. Use
     *     <code>null</code> for the default behaviur.
     * @see org.cybergarage.upnp.ssdp
     * @see org.cybergarage.upnp
     * @see org.cybergarage.net.HostInterface
     * @since 1.8
     */
    public void setSSDPBindAddress(InetAddress[] inets) {
        this.ssdpBinds = inets;
    }

    /**
     * Returns the SSDP bind addresses.
     *
     * @return The <code>InetAddress</code> that will be binded for this service <code>null
     *     </code> means that defulat behaviur will be used
     * @since 1.8
     */
    public InetAddress[] getSSDPBindAddress() {
        return this.ssdpBinds;
    }

    /**
     * Sets the IPv4 multicast address.
     *
     * @param ip The IPv4 address used as destination address for Multicast comunication
     * @since 1.8
     */
    public void setMulticastIPv4Address(String ip) {
        this.ssdpMulticastIPv4 = ip;
    }

    /**
     * Returns the IPv4 multicast address.
     *
     * @return The IPv4 address used for Multicast comunication
     */
    public String getMulticastIPv4Address() {
        return this.ssdpMulticastIPv4;
    }

    /**
     * Sets the IPv6 multicast address.
     *
     * @param ip The IPv6 address used as destination address for Multicast comunication
     * @since 1.8
     */
    public void setMulticastIPv6Address(String ip) {
        this.ssdpMulticastIPv6 = ip;
    }

    /**
     * Returns the IPv6 multicast address.
     *
     * @return The IPv6 address used as destination address for Multicast comunication
     * @since 1.8
     */
    public String getMulticastIPv6Address() {
        return this.ssdpMulticastIPv6;
    }

    ////////////////////////////////////////////////
    // SSDPPacket
    ////////////////////////////////////////////////

    private SSDPPacket ssdpPacket = null;
    private SSDPPacket ssdpPacket_ipv6 = null;

    /**
     * getSSDPPacket.
     *
     * @return the packet describing this device, preferring the IPv4 form
     */
    public SSDPPacket getSSDPPacket() {
        return getSSDPPacket(false);
    }

    /**
     * I2P for multiple location support.
     *
     * @param preferIPv6 whether to prefer IPv6 address
     * @return the SSDP packet
     * @since 0.9.50
     */
    public SSDPPacket getSSDPPacket(boolean preferIPv6) {
        if (preferIPv6) {
            if (ssdpPacket_ipv6 != null) return ssdpPacket_ipv6;
            return ssdpPacket;
        } else {
            if (ssdpPacket != null) return ssdpPacket;
            return ssdpPacket_ipv6;
        }
    }

    /**
     * setSSDPPacket.
     *
     * @param packet the packet to advertise; a bracketed host in its location is filed as the IPv6 packet, and a malformed URL is rejected with a warning
     */
    public void setSSDPPacket(SSDPPacket packet) {
        String location = packet.getLocation();
        if (location != null) {
            try {
                URL url = new URL(location);
                String host = url.getHost();
                if (host != null && host.startsWith("[")) {
                    ssdpPacket_ipv6 = packet;
                    return;
                }
            } catch (MalformedURLException me) {
                Debug.warning("Bad location: " + location, me);
                return;
            }
        }
        ssdpPacket = packet;
    }

    ////////////////////////////////////////////////
    // Advertiser
    ////////////////////////////////////////////////

    private Advertiser advertiser = null;

    /**
     * setAdvertiser.
     *
     * @param adv the advertiser to schedule this device's SSDP announcements with
     */
    public void setAdvertiser(Advertiser adv) {
        advertiser = adv;
    }

    /**
     * getAdvertiser.
     *
     * @return the advertiser scheduling this device's SSDP announcements, or null when none has been set
     */
    public Advertiser getAdvertiser() {
        return advertiser;
    }
}
