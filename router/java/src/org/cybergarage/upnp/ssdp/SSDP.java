/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.ssdp;

import org.cybergarage.util.Debug;

/**
 * Constants and utility methods for Simple Service Discovery Protocol (SSDP).<br>
 * This class defines SSDP protocol constants including multicast addresses, ports, and utility
 * methods for parsing SSDP cache control headers.
 *
 * @author Satoshi "skonno" Konno
 * @version 1.8
 */
public class SSDP {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    /** Default destination port for SSDP multicast messages */
    public static final int PORT = 1900;

    /** Default IPv4 multicast address for SSDP messages */
    public static final String ADDRESS = "239.255.255.250";

    /** IPv6 link-local multicast address for SSDP messages */
    public static final String IPV6_LINK_LOCAL_ADDRESS = "FF02::C";

    /** IPv6 subnet-local multicast address for SSDP messages */
    public static final String IPV6_SUBNET_ADDRESS = "FF03::C";

    /** IPv6 administrative multicast address for SSDP messages */
    public static final String IPV6_ADMINISTRATIVE_ADDRESS = "FF04::C";

    /** IPv6 site-local multicast address for SSDP messages */
    public static final String IPV6_SITE_LOCAL_ADDRESS = "FF05::C";

    /** IPv6 global multicast address for SSDP messages */
    public static final String IPV6_GLOBAL_ADDRESS = "FF0E::C";

    private static String IPV6_ADDRESS;

    /**
     * Sets the IPv6 multicast address to use for SSDP operations.
     *
     * @param addr the IPv6 address to use
     */
    public static final void setIPv6Address(String addr) {
        IPV6_ADDRESS = addr;
    }

    /**
     * Gets the currently configured IPv6 multicast address for SSDP operations.
     *
     * @return the IPv6 address being used
     */
    public static final String getIPv6Address() {
        return IPV6_ADDRESS;
    }

    /** Default MX (Maximum wait time) for M-SEARCH requests in seconds */
    public static final int DEFAULT_MSEARCH_MX = 3;

    /** Buffer size for receiving SSDP messages in bytes */
    public static final int RECV_MESSAGE_BUFSIZE = 1024;

    ////////////////////////////////////////////////
    //	Initialize
    ////////////////////////////////////////////////

    static {
        setIPv6Address(IPV6_LINK_LOCAL_ADDRESS);
    }

    ////////////////////////////////////////////////
    //	LeaseTime
    ////////////////////////////////////////////////

    /**
     * Extracts the lease time (max-age) from a Cache-Control header string. Parses the max-age
     * directive value from SSDP cache control headers.
     *
     * @param cacheCont the Cache-Control header content to parse
     * @return the lease time in seconds, or 0 if not found or invalid
     */
    public static final int getLeaseTime(String cacheCont) {
        /*
         * Search for max-age keyword instead of equals sign Found value of
         * max-age ends at next comma or end of string
         */
        int mx = 0;
        int maxAgeIdx = cacheCont.indexOf("max-age");
        if (maxAgeIdx >= 0) {
            int endIdx = cacheCont.indexOf(',', maxAgeIdx);
            if (endIdx < 0) endIdx = cacheCont.length();
            try {
                maxAgeIdx = cacheCont.indexOf("=", maxAgeIdx);
                String mxStr = cacheCont.substring(maxAgeIdx + 1, endIdx).trim();
                mx = Integer.parseInt(mxStr);
            } catch (Exception e) {
                Debug.warning(e);
            }
        }
        return mx;
    }
}
