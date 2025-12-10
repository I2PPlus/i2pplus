/*
 * CyberLink for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.ssdp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.cybergarage.util.Debug;

/**
 * UDP socket implementation for SSDP (Simple Service Discovery Protocol) communication.
 *
 * <p>This class provides UDP socket functionality specifically designed for SSDP operations in UPnP
 * networks. It handles both unicast and multicast UDP communication for device discovery and
 * advertisement.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>UDP unicast socket management
 *   <li>IPv4 and IPv6 support
 *   <li>Packet sending and receiving
 *   <li>Timestamp tracking for received packets
 *   <li>Network interface binding
 * </ul>
 *
 * <p>This class is used by SSDP components to send discovery messages (M-SEARCH) and receive device
 * advertisements (NOTIFY messages) over UDP multicast and unicast communication.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class HTTPUSocket {
    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    private DatagramSocket ssdpUniSock = null;

    // private MulticastSocket ssdpUniSock = null;

    public DatagramSocket getDatagramSocket() {
        return ssdpUniSock;
    }

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public HTTPUSocket() {
        open();
    }

    public HTTPUSocket(String bindAddr, int bindPort) {
        open(bindAddr, bindPort);
    }

    public HTTPUSocket(int bindPort) {
        open(bindPort);
    }

    protected void finalize() {
        close();
    }

    ////////////////////////////////////////////////
    //	bindAddr
    ////////////////////////////////////////////////

    private String localAddr = "";

    public void setLocalAddress(String addr) {
        localAddr = addr;
    }

    /**
     * @return {@link DatagramSocket} open for receieving packets
     * @since 1.8
     */
    public DatagramSocket getUDPSocket() {
        return ssdpUniSock;
    }

    public String getLocalAddress() {
        if (0 < localAddr.length()) return localAddr;
        // I2P prevent NPE #1681
        if (ssdpUniSock == null) return "";
        return ssdpUniSock.getLocalAddress().getHostAddress();
    }

    ////////////////////////////////////////////////
    //	open
    ////////////////////////////////////////////////

    public boolean open() {
        close();

        try {
            ssdpUniSock = new DatagramSocket();
        } catch (Exception e) {
            Debug.warning(e);
            return false;
        }

        return true;
    }

    public boolean open(String bindAddr, int bindPort) {
        close();

        try {
            // Changed to bind the specified address and port for Android v1.6 (2009/10/07)
            InetSocketAddress bindInetAddr =
                    new InetSocketAddress(InetAddress.getByName(bindAddr), bindPort);
            ssdpUniSock = new DatagramSocket(bindInetAddr);
        } catch (Exception e) {
            Debug.warning(e);
            return false;
        }

        /*
        try {
        	// Bind only using the port without the interface address. (2003/12/12)
        	InetSocketAddress bindInetAddr = new InetSocketAddress(bindPort);
        	ssdpUniSock = new DatagramSocket(null);
        	ssdpUniSock.setReuseAddress(true);
        	ssdpUniSock.bind(bindInetAddr);
        	return true;
        }
        catch (Exception e) {
        	Debug.warning(e);
        	return false;
        }
        */

        setLocalAddress(bindAddr);

        return true;
    }

    public boolean open(int bindPort) {
        close();

        try {
            InetSocketAddress bindSock = new InetSocketAddress(bindPort);
            ssdpUniSock = new DatagramSocket(null);
            ssdpUniSock.setReuseAddress(true);
            ssdpUniSock.bind(bindSock);
        } catch (Exception e) {
            // Debug.warning(e);
            return false;
        }

        return true;
    }

    ////////////////////////////////////////////////
    //	close
    ////////////////////////////////////////////////

    public boolean close() {
        if (ssdpUniSock == null) return true;

        try {
            ssdpUniSock.close();
            ssdpUniSock = null;
        } catch (Exception e) {
            Debug.warning(e);
            return false;
        }

        return true;
    }

    ////////////////////////////////////////////////
    //	send
    ////////////////////////////////////////////////

    public boolean post(String addr, int port, String msg) {
        try {
            InetAddress inetAddr = InetAddress.getByName(addr);
            DatagramPacket dgmPacket =
                    new DatagramPacket(
                            msg.getBytes(StandardCharsets.UTF_8), msg.length(), inetAddr, port);
            ssdpUniSock.send(dgmPacket);
        } catch (Exception e) {
            // I2P prevent NPE android gitlab #1
            DatagramSocket sock = ssdpUniSock;
            if (sock != null) {
                Debug.warning("addr = " + sock.getLocalAddress().getHostName());
                Debug.warning("port = " + sock.getLocalPort());
            }
            Debug.warning(e);
            return false;
        }
        return true;
    }

    ////////////////////////////////////////////////
    //	reveive
    ////////////////////////////////////////////////

    public SSDPPacket receive() {
        byte ssdvRecvBuf[] = new byte[SSDP.RECV_MESSAGE_BUFSIZE];
        SSDPPacket recvPacket = new SSDPPacket(ssdvRecvBuf, ssdvRecvBuf.length);
        recvPacket.setLocalAddress(getLocalAddress());
        try {
            ssdpUniSock.receive(recvPacket.getDatagramPacket());
            recvPacket.setTimeStamp(System.currentTimeMillis());
            Debug.message(
                    "Received SSDP unicast packet on "
                            + getLocalAddress()
                            + " from "
                            + recvPacket.getRemoteAddress());
        } catch (Exception e) {
            // Debug.warning(e);
            return null;
        }
        return recvPacket;
    }

    ////////////////////////////////////////////////
    //	join/leave
    ////////////////////////////////////////////////

    /*
    	boolean joinGroup(String mcastAddr, int mcastPort, String bindAddr)
    	{
    		try {
    			InetSocketAddress mcastGroup = new InetSocketAddress(InetAddress.getByName(mcastAddr), mcastPort);
    			NetworkInterface mcastIf = NetworkInterface.getByInetAddress(InetAddress.getByName(bindAddr));
    			ssdpUniSock.joinGroup(mcastGroup, mcastIf);
    		}
    		catch (Exception e) {
    			Debug.warning(e);
    			return false;
    		}
    		return true;
    	}

    	boolean leaveGroup(String mcastAddr, int mcastPort, String bindAddr)
    	 {
    		try {
    			InetSocketAddress mcastGroup = new InetSocketAddress(InetAddress.getByName(mcastAddr), mcastPort);
    			NetworkInterface mcastIf = NetworkInterface.getByInetAddress(InetAddress.getByName(bindAddr));
    			ssdpUniSock.leaveGroup(mcastGroup, mcastIf);
    		 }
    		 catch (Exception e) {
    			 Debug.warning(e);
    			 return false;
    		 }
    		 return true;
    	 }
    */

    /** I2P */
    @Override
    public String toString() {
        return localAddr;
    }
}
