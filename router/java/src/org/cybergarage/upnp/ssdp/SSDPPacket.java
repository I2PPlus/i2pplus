/*
 * CyberLink for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.ssdp;

import java.net.*;
import java.nio.charset.StandardCharsets;
import org.cybergarage.http.*;
import org.cybergarage.upnp.device.*;

/**
 * Represents an SSDP packet received over UDP.
 *
 * <p>This class wraps a DatagramPacket to provide SSDP-specific functionality for parsing and
 * handling UDP packets containing SSDP messages. It provides access to both the raw packet data and
 * parsed SSDP message content.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>DatagramPacket wrapping and management
 *   <li>Remote and local address access
 *   <li>SSDP message parsing
 *   <li>Root device detection
 *   <li>Packet data extraction
 * </ul>
 *
 * <p>This class is used by SSDP components to handle incoming UDP packets containing discovery and
 * advertisement messages, providing convenient access to both network information and message
 * content.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SSDPPacket {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * SSDPPacket.
     */
    public SSDPPacket(byte[] buf, int length) {
        dgmPacket = new DatagramPacket(buf, length);
    }

    ////////////////////////////////////////////////
    //	DatagramPacket
    ////////////////////////////////////////////////

    private DatagramPacket dgmPacket = null;

    /**
     * getDatagramPacket.
     */
    public DatagramPacket getDatagramPacket() {
        return dgmPacket;
    }

    ////////////////////////////////////////////////
    //	addr
    ////////////////////////////////////////////////

    private String localAddr = "";

    /**
     * setLocalAddress.
     */
    public void setLocalAddress(String addr) {
        localAddr = addr;
    }

    /**
     * getLocalAddress.
     */
    public String getLocalAddress() {
        return localAddr;
    }

    ////////////////////////////////////////////////
    //	Time
    ////////////////////////////////////////////////

    private long timeStamp;

    /**
     * setTimeStamp.
     */
    public void setTimeStamp(long value) {
        timeStamp = value;
    }

    /**
     * getTimeStamp.
     */
    public long getTimeStamp() {
        return timeStamp;
    }

    ////////////////////////////////////////////////
    //	Remote host
    ////////////////////////////////////////////////

    /**
     * getRemoteInetAddress.
     */
    public InetAddress getRemoteInetAddress() {
        return getDatagramPacket().getAddress();
    }

    /**
     * getRemoteAddress.
     */
    public String getRemoteAddress() {
        // Thanks for Theo Beisch (11/09/04)
        return getDatagramPacket().getAddress().getHostAddress();
    }

    /**
     * getRemotePort.
     */
    public int getRemotePort() {
        return getDatagramPacket().getPort();
    }

    ////////////////////////////////////////////////
    //	Access Methods
    ////////////////////////////////////////////////

    /**
     * packetBytes.
     */
    public byte[] packetBytes = null;

    /**
     * getData.
     */
    public byte[] getData() {
        if (packetBytes != null) return packetBytes;

        DatagramPacket packet = getDatagramPacket();
        int packetLen = packet.getLength();
        String packetData = new String(packet.getData(), 0, packetLen, StandardCharsets.UTF_8);
        packetBytes = packetData.getBytes(StandardCharsets.UTF_8);

        return packetBytes;
    }

    ////////////////////////////////////////////////
    //	Access Methods
    ////////////////////////////////////////////////

    /**
     * getHost.
     */
    public String getHost() {
        return HTTPHeader.getValue(getData(), HTTP.HOST);
    }

    /**
     * getCacheControl.
     */
    public String getCacheControl() {
        return HTTPHeader.getValue(getData(), HTTP.CACHE_CONTROL);
    }

    /**
     * getLocation.
     */
    public String getLocation() {
        return HTTPHeader.getValue(getData(), HTTP.LOCATION);
    }

    /**
     * getMAN.
     */
    public String getMAN() {
        return HTTPHeader.getValue(getData(), HTTP.MAN);
    }

    /**
     * getST.
     */
    public String getST() {
        return HTTPHeader.getValue(getData(), HTTP.ST);
    }

    /**
     * getNT.
     */
    public String getNT() {
        return HTTPHeader.getValue(getData(), HTTP.NT);
    }

    /**
     * getNTS.
     */
    public String getNTS() {
        return HTTPHeader.getValue(getData(), HTTP.NTS);
    }

    /**
     * getServer.
     */
    public String getServer() {
        return HTTPHeader.getValue(getData(), HTTP.SERVER);
    }

    /**
     * getUSN.
     */
    public String getUSN() {
        return HTTPHeader.getValue(getData(), HTTP.USN);
    }

    /**
     * getMX.
     */
    public int getMX() {
        return HTTPHeader.getIntegerValue(getData(), HTTP.MX);
    }

    ////////////////////////////////////////////////
    //	Access Methods
    ////////////////////////////////////////////////

    /**
     * getHostInetAddress.
     */
    public InetAddress getHostInetAddress() {
        String addrStr = "127.0.0.1";
        String host = getHost();
        int canmaIdx = host.lastIndexOf(":");
        if (0 <= canmaIdx) {
            addrStr = host.substring(0, canmaIdx);
            if (addrStr.charAt(0) == '[') addrStr = addrStr.substring(1, addrStr.length());
            if (addrStr.charAt(addrStr.length() - 1) == ']')
                addrStr = addrStr.substring(0, addrStr.length() - 1);
        }
        InetSocketAddress isockaddr = new InetSocketAddress(addrStr, 0);
        return isockaddr.getAddress();
    }

    ////////////////////////////////////////////////
    //	Access Methods (Extension)
    ////////////////////////////////////////////////

    /**
     * isRootDevice.
     */
    public boolean isRootDevice() {
        if (NT.isRootDevice(getNT()) == true) return true;
        // Thanks for Theo Beisch (11/01/04)
        if (ST.isRootDevice(getST()) == true) return true;
        return USN.isRootDevice(getUSN());
    }

    /**
     * isDiscover.
     */
    public boolean isDiscover() {
        return MAN.isDiscover(getMAN());
    }

    /**
     * isAlive.
     */
    public boolean isAlive() {
        return NTS.isAlive(getNTS());
    }

    /**
     * isByeBye.
     */
    public boolean isByeBye() {
        return NTS.isByeBye(getNTS());
    }

    /**
     * getLeaseTime.
     */
    public int getLeaseTime() {
        return SSDP.getLeaseTime(getCacheControl());
    }

    ////////////////////////////////////////////////
    //	toString
    ////////////////////////////////////////////////

    /**
     * toString.
     */
    public String toString() {
        return new String(getData(), StandardCharsets.UTF_8);
    }
}
