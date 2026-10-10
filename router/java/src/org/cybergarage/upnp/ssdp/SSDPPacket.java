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
     *
     * @param buf the buffer holding the received datagram, wrapped rather than copied
     * @param length how many bytes of buf this packet spans, in bytes
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
     *
     * @return the wrapped DatagramPacket carrying this SSDP message
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
     *
     * @param addr the local IP address that received the datagram
     */
    public void setLocalAddress(String addr) {
        localAddr = addr;
    }

    /**
     * getLocalAddress.
     *
     * @return the local IP address that received this datagram
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
     *
     * @param value the receipt time in milliseconds since the epoch
     */
    public void setTimeStamp(long value) {
        timeStamp = value;
    }

    /**
     * getTimeStamp.
     *
     * @return when this datagram was received, in milliseconds since the epoch, or 0
     */
    public long getTimeStamp() {
        return timeStamp;
    }

    ////////////////////////////////////////////////
    //	Remote host
    ////////////////////////////////////////////////

    /**
     * getRemoteInetAddress.
     *
     * @return the InetAddress this datagram was sent from
     */
    public InetAddress getRemoteInetAddress() {
        return getDatagramPacket().getAddress();
    }

    /**
     * getRemoteAddress.
     *
     * @return the IP address string this datagram was sent from
     */
    public String getRemoteAddress() {
        // Thanks for Theo Beisch (11/09/04)
        return getDatagramPacket().getAddress().getHostAddress();
    }

    /**
     * getRemotePort.
     *
     * @return the UDP port number this datagram was sent from
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
     *
     * @return the UTF-8 message bytes of this datagram, cached in packetBytes once read
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
     *
     * @return the HOST header value, or an empty string if the message carries none
     */
    public String getHost() {
        return HTTPHeader.getValue(getData(), HTTP.HOST);
    }

    /**
     * getCacheControl.
     *
     * @return the CACHE-CONTROL header value, or an empty string if the message
     * carries none
     */
    public String getCacheControl() {
        return HTTPHeader.getValue(getData(), HTTP.CACHE_CONTROL);
    }

    /**
     * getLocation.
     *
     * @return the LOCATION header value, or an empty string if the message carries none
     */
    public String getLocation() {
        return HTTPHeader.getValue(getData(), HTTP.LOCATION);
    }

    /**
     * getMAN.
     *
     * @return the MAN header value, or an empty string if the message carries none
     */
    public String getMAN() {
        return HTTPHeader.getValue(getData(), HTTP.MAN);
    }

    /**
     * getST.
     *
     * @return the ST header value, or an empty string if the message carries none
     */
    public String getST() {
        return HTTPHeader.getValue(getData(), HTTP.ST);
    }

    /**
     * getNT.
     *
     * @return the NT header value, or an empty string if the message carries none
     */
    public String getNT() {
        return HTTPHeader.getValue(getData(), HTTP.NT);
    }

    /**
     * getNTS.
     *
     * @return the NTS header value, or an empty string if the message carries none
     */
    public String getNTS() {
        return HTTPHeader.getValue(getData(), HTTP.NTS);
    }

    /**
     * getServer.
     *
     * @return the SERVER header value, or an empty string if the message carries none
     */
    public String getServer() {
        return HTTPHeader.getValue(getData(), HTTP.SERVER);
    }

    /**
     * getUSN.
     *
     * @return the USN header value, or an empty string if the message carries none
     */
    public String getUSN() {
        return HTTPHeader.getValue(getData(), HTTP.USN);
    }

    /**
     * getMX.
     *
     * @return the MX header in seconds, or 0 if it is missing or unparseable
     */
    public int getMX() {
        return HTTPHeader.getIntegerValue(getData(), HTTP.MX);
    }

    ////////////////////////////////////////////////
    //	Access Methods
    ////////////////////////////////////////////////

    /**
     * getHostInetAddress.
     *
     * @return the InetAddress named in the HOST header, or the loopback address if
     * that header carries no port
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
     *
     * @return true if the NT, ST or USN header identifies this as the root device
     */
    public boolean isRootDevice() {
        if (NT.isRootDevice(getNT()) == true) return true;
        // Thanks for Theo Beisch (11/01/04)
        if (ST.isRootDevice(getST()) == true) return true;
        return USN.isRootDevice(getUSN());
    }

    /**
     * isDiscover.
     *
     * @return true if the MAN header carries the discover advertisement
     */
    public boolean isDiscover() {
        return MAN.isDiscover(getMAN());
    }

    /**
     * isAlive.
     *
     * @return true if the NTS header is ssdp:alive
     */
    public boolean isAlive() {
        return NTS.isAlive(getNTS());
    }

    /**
     * isByeBye.
     *
     * @return true if the NTS header is ssdp:byebye
     */
    public boolean isByeBye() {
        return NTS.isByeBye(getNTS());
    }

    /**
     * getLeaseTime.
     *
     * @return the lease time in seconds, or 0 if the CACHE-CONTROL header carries no
     * usable max-age
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
