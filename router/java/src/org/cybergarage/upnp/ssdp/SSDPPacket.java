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
 * <p>This class is used by {@link SSDP} components to handle incoming UDP packets containing discovery and
 * advertisement messages, providing convenient access to both network information and message
 * content.
 *
 * @since 1.0
 */
public class SSDPPacket {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Wraps a received SSDP datagram without copying its bytes.
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
     * Returns the underlying datagram.
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
     * Records the local IP address that received this datagram.
     *
     * @param addr the local IP address that received the datagram
     */
    public void setLocalAddress(String addr) {
        localAddr = addr;
    }

    /**
     * Returns the local IP address that received this datagram.
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
     * Records when this datagram was received.
     *
     * @param value the receipt time in milliseconds since the epoch
     */
    public void setTimeStamp(long value) {
        timeStamp = value;
    }

    /**
     * Returns when this datagram was received.
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
     * Returns the address this datagram was sent from.
     *
     * @return the InetAddress this datagram was sent from
     */
    public InetAddress getRemoteInetAddress() {
        return getDatagramPacket().getAddress();
    }

    /**
     * Returns the IP address this datagram was sent from.
     *
     * @return the IP address string this datagram was sent from
     */
    public String getRemoteAddress() {
        // Thanks for Theo Beisch (11/09/04)
        return getDatagramPacket().getAddress().getHostAddress();
    }

    /**
     * Returns the UDP port this datagram was sent from.
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
     * Cached copy of the message bytes, filled in by the first read of the data.
     */
    public byte[] packetBytes = null;

    /**
     * Returns the message bytes, caching them on the first call.
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
     * Returns the HOST header value.
     *
     * @return the HOST header value, or an empty string if the message carries none
     */
    public String getHost() {
        return HTTPHeader.getValue(getData(), HTTP.HOST);
    }

    /**
     * Returns the CACHE-CONTROL header value.
     *
     * @return the CACHE-CONTROL header value, or an empty string if the message
     *         carries none
     */
    public String getCacheControl() {
        return HTTPHeader.getValue(getData(), HTTP.CACHE_CONTROL);
    }

    /**
     * Returns the LOCATION header value.
     *
     * @return the LOCATION header value, or an empty string if the message carries none
     */
    public String getLocation() {
        return HTTPHeader.getValue(getData(), HTTP.LOCATION);
    }

    /**
     * Returns the MAN header value.
     *
     * @return the MAN header value, or an empty string if the message carries none
     */
    public String getMAN() {
        return HTTPHeader.getValue(getData(), HTTP.MAN);
    }

    /**
     * Returns the ST header value.
     *
     * @return the ST header value, or an empty string if the message carries none
     */
    public String getST() {
        return HTTPHeader.getValue(getData(), HTTP.ST);
    }

    /**
     * Returns the NT header value.
     *
     * @return the NT header value, or an empty string if the message carries none
     */
    public String getNT() {
        return HTTPHeader.getValue(getData(), HTTP.NT);
    }

    /**
     * Returns the NTS header value.
     *
     * @return the NTS header value, or an empty string if the message carries none
     */
    public String getNTS() {
        return HTTPHeader.getValue(getData(), HTTP.NTS);
    }

    /**
     * Returns the SERVER header value.
     *
     * @return the SERVER header value, or an empty string if the message carries none
     */
    public String getServer() {
        return HTTPHeader.getValue(getData(), HTTP.SERVER);
    }

    /**
     * Returns the USN header value.
     *
     * @return the USN header value, or an empty string if the message carries none
     */
    public String getUSN() {
        return HTTPHeader.getValue(getData(), HTTP.USN);
    }

    /**
     * Returns the MX header as a number of seconds.
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
     * Resolves the HOST header to an address.
     *
     * @return the InetAddress named in the HOST header, or the loopback address if
     *         that header carries no port
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
     * Returns whether NT, ST or USN identifies the root device.
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
     * Returns whether this is a discover advertisement.
     *
     * @return true if the MAN header carries the discover advertisement
     */
    public boolean isDiscover() {
        return MAN.isDiscover(getMAN());
    }

    /**
     * Returns whether this is an ssdp:alive advertisement.
     *
     * @return true if the NTS header is ssdp:alive
     */
    public boolean isAlive() {
        return NTS.isAlive(getNTS());
    }

    /**
     * Returns whether this is an ssdp:byebye advertisement.
     *
     * @return true if the NTS header is ssdp:byebye
     */
    public boolean isByeBye() {
        return NTS.isByeBye(getNTS());
    }

    /**
     * Returns the advertised lease duration.
     *
     * @return the lease time in seconds, or 0 if the CACHE-CONTROL header carries no
     *         usable max-age
     */
    public int getLeaseTime() {
        return SSDP.getLeaseTime(getCacheControl());
    }

    ////////////////////////////////////////////////
    //	toString
    ////////////////////////////////////////////////

    /**
     * Renders the raw message text.
     */
    public String toString() {
        return new String(getData(), StandardCharsets.UTF_8);
    }
}
