/******************************************************************
 * CyberHTTP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 ******************************************************************/

package org.cybergarage.net;

import org.cybergarage.util.Debug;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Vector;

/**
 * Utility class for network host interface management and configuration.
 *
 * <p>This class provides static methods for discovering and managing network interfaces and IP
 * addresses on the local system. It supports both IPv4 and IPv6 addresses, with configurable
 * options for address selection and interface binding.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Network interface enumeration and discovery
 *   <li>IPv4 and IPv6 address management
 *   <li>Loopback address inclusion/exclusion
 *   <li>Interface-specific binding support
 *   <li>Host name and address resolution
 * </ul>
 *
 * <p>This class is essential for UPnP devices that need to bind to specific network interfaces or
 * discover available network addresses for device advertisement and communication.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class HostInterface {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    public static boolean USE_LOOPBACK_ADDR = false;
    public static boolean USE_ONLY_IPV4_ADDR = false;
    public static boolean USE_ONLY_IPV6_ADDR = false;

    ////////////////////////////////////////////////
    //	Network Interfaces
    ////////////////////////////////////////////////

    private static String ifAddress = "";
    public static final int IPV4_BITMASK = 0x0001;
    public static final int IPV6_BITMASK = 0x0010;
    public static final int LOCAL_BITMASK = 0x0100;

    public static final void setInterface(String ifaddr) {
        ifAddress = ifaddr;
    }

    public static final String getInterface() {
        return ifAddress;
    }

    private static final boolean hasAssignedInterface() {
        return (0 < ifAddress.length()) ? true : false;
    }

    ////////////////////////////////////////////////
    //	Network Interfaces
    ////////////////////////////////////////////////

    // Thanks for Theo Beisch (10/27/04)

    private static final boolean isUsableAddress(InetAddress addr) {
        if (USE_LOOPBACK_ADDR == false) {
            if (addr.isLoopbackAddress() == true) return false;
        }
        if (USE_ONLY_IPV4_ADDR == true) {
            if (addr instanceof Inet6Address) return false;
        }
        if (USE_ONLY_IPV6_ADDR == true) {
            if (addr instanceof Inet4Address) return false;
        }
        return true;
    }

    public static final int getNHostAddresses() {
        if (hasAssignedInterface() == true) return 1;

        int nHostAddrs = 0;
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (isUsableAddress(addr) == false) continue;
                    nHostAddrs++;
                }
            }
        } catch (Exception e) {
            Debug.warning(e);
        }
        ;
        return nHostAddrs;
    }

    /**
     * @param ipfilter
     * @param interfaces
     * @return InetAddress[]
     * @since 1.8.0
     */
    public static final InetAddress[] getInetAddress(int ipfilter, String[] interfaces) {
        Enumeration<NetworkInterface> nis;
        if (interfaces != null) {
            Vector<NetworkInterface> iflist = new Vector<NetworkInterface>();
            for (int i = 0; i < interfaces.length; i++) {
                NetworkInterface ni;
                try {
                    ni = NetworkInterface.getByName(interfaces[i]);
                } catch (SocketException e) {
                    continue;
                }
                if (ni != null) iflist.add(ni);
            }
            nis = iflist.elements();
        } else {
            try {
                nis = NetworkInterface.getNetworkInterfaces();
            } catch (SocketException e) {
                return null;
            }
        }
        ArrayList<InetAddress> addresses = new ArrayList<InetAddress>();
        while (nis.hasMoreElements()) {
            NetworkInterface ni = nis.nextElement();
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress addr = addrs.nextElement();
                if (((ipfilter & LOCAL_BITMASK) == 0) && addr.isLoopbackAddress()) continue;

                if (((ipfilter & IPV4_BITMASK) != 0) && addr instanceof Inet4Address) {
                    addresses.add(addr);
                } else if (((ipfilter & IPV6_BITMASK) != 0) && addr instanceof Inet6Address) {
                    addresses.add(addr);
                }
            }
        }
        return addresses.toArray(new InetAddress[] {});
    }

    public static final String getHostAddress(int n) {
        if (hasAssignedInterface() == true) return getInterface();

        int hostAddrCnt = 0;
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (isUsableAddress(addr) == false) continue;
                    if (hostAddrCnt < n) {
                        hostAddrCnt++;
                        continue;
                    }
                    String host = addr.getHostAddress();
                    // if (addr instanceof Inet6Address)
                    //	host = "[" + host + "]";
                    return host;
                }
            }
        } catch (Exception e) {
        }
        ;
        return "";
    }

    ////////////////////////////////////////////////
    //	isIPv?Address
    ////////////////////////////////////////////////

    public static final boolean isIPv6Address(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr instanceof Inet6Address) return true;
            return false;
        } catch (Exception e) {
        }
        return false;
    }

    public static final boolean isIPv4Address(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr instanceof Inet4Address) return true;
            return false;
        } catch (Exception e) {
        }
        return false;
    }

    ////////////////////////////////////////////////
    //	hasIPv?Interfaces
    ////////////////////////////////////////////////

    public static final boolean hasIPv4Addresses() {
        int addrCnt = getNHostAddresses();
        for (int n = 0; n < addrCnt; n++) {
            String addr = getHostAddress(n);
            if (isIPv4Address(addr) == true) return true;
        }
        return false;
    }

    public static final boolean hasIPv6Addresses() {
        int addrCnt = getNHostAddresses();
        for (int n = 0; n < addrCnt; n++) {
            String addr = getHostAddress(n);
            if (isIPv6Address(addr) == true) return true;
        }
        return false;
    }

    ////////////////////////////////////////////////
    //	hasIPv?Interfaces
    ////////////////////////////////////////////////

    public static final String getIPv4Address() {
        int addrCnt = getNHostAddresses();
        for (int n = 0; n < addrCnt; n++) {
            String addr = getHostAddress(n);
            if (isIPv4Address(addr) == true) return addr;
        }
        return "";
    }

    public static final String getIPv6Address() {
        int addrCnt = getNHostAddresses();
        for (int n = 0; n < addrCnt; n++) {
            String addr = getHostAddress(n);
            if (isIPv6Address(addr) == true) return addr;
        }
        return "";
    }

    ////////////////////////////////////////////////
    //	getHostURL
    ////////////////////////////////////////////////

    public static final String getHostURL(String host, int port, String uri) {
        String hostAddr = host;
        if (isIPv6Address(host) == true) hostAddr = "[" + host + "]";
        return "http://" + hostAddr + ":" + Integer.toString(port) + uri;
    }
}
