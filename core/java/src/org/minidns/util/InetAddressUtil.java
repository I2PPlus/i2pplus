/*
 * Copyright 2015-2020 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package org.minidns.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.minidns.dnsname.DnsName;

/**
 * Utility class for IP address manipulation and validation.
 */
public class InetAddressUtil {

    /**
     * Creates an Inet4Address from the given CharSequence.
     * 
     * @param cs the CharSequence containing the IPv4 address
     * @return the Inet4Address
     * @throws IllegalArgumentException if the input is not a valid IPv4 address
     */
    public static Inet4Address ipv4From(CharSequence cs) {
        InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getByName(cs.toString());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
        if (inetAddress instanceof Inet4Address) {
            return (Inet4Address) inetAddress;
        }
        throw new IllegalArgumentException();
    }

    /**
     * Creates an Inet6Address from the given CharSequence.
     * 
     * @param cs the CharSequence containing the IPv6 address
     * @return the Inet6Address
     * @throws IllegalArgumentException if the input is not a valid IPv6 address
     */
    public static Inet6Address ipv6From(CharSequence cs) {
        InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getByName(cs.toString());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
        if (inetAddress instanceof Inet6Address) {
            return (Inet6Address) inetAddress;
        }
        throw new IllegalArgumentException();
    }

    // IPV4_REGEX from http://stackoverflow.com/a/46168/194894 by Kevin Wong (http://stackoverflow.com/users/4792/kevin-wong) licensed under
    // CC BY-SA 3.0.
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\A(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}\\z");

    /**
     * Checks if the given CharSequence represents a valid IPv4 address.
     * 
     * @param address the CharSequence to validate
     * @return true if the input is a valid IPv4 address, false otherwise
     */
    public static boolean isIpV4Address(CharSequence address) {
        if (address == null) {
            return false;
        }
        return IPV4_PATTERN.matcher(address).matches();
    }

    // IPv6 Regular Expression from http://stackoverflow.com/a/17871737/194894 by David M. Syzdek
    // (http://stackoverflow.com/users/903194/david-m-syzdek) licensed under CC BY-SA 3.0.
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))");

    /**
     * Checks if the given CharSequence represents a valid IPv6 address.
     * 
     * @param address the CharSequence to validate
     * @return true if the input is a valid IPv6 address, false otherwise
     */
    public static boolean isIpV6Address(CharSequence address) {
        if (address == null) {
            return false;
        }
        return IPV6_PATTERN.matcher(address).matches();
    }

    /**
     * Checks if the given CharSequence represents a valid IP address (IPv4 or IPv6).
     * 
     * @param address the CharSequence to validate
     * @return true if the input is a valid IP address, false otherwise
     */
    public static boolean isIpAddress(CharSequence address) {
        return isIpV6Address(address) || isIpV4Address(address);
    }

    /**
     * Converts a CharSequence to an InetAddress if it represents a valid IP address.
     * 
     * @param address the CharSequence to convert
     * @return the InetAddress if the input is a valid IP address, null otherwise
     */
    public static InetAddress convertToInetAddressIfPossible(CharSequence address) {
        if (!isIpAddress(address)) {
            return null;
        }

        String addressString = address.toString();
        try {
            return InetAddress.getByName(addressString);
        } catch (UnknownHostException e) {
            // Should never happen.
            throw new AssertionError(e);
        }
    }

    /**
     * Creates the reverse DNS name for the given IPv6 address.
     * 
     * @param inet6Address the IPv6 address
     * @return the reverse DNS name
     */
    public static DnsName reverseIpAddressOf(Inet6Address inet6Address) {
        final String ipAddress = inet6Address.getHostAddress();
        final String[] ipAddressParts = ipAddress.split(":");

        String[] parts = new String[32];
        int currentPartNum = 0;
        for (int i = ipAddressParts.length - 1; i >= 0; i--) {
            final String currentPart = ipAddressParts[i];
            final int missingPlaces = 4 - currentPart.length();
            for (int j = 0; j < missingPlaces; j++) {
                parts[currentPartNum++] = "0";
            }
            for (int j = 0; j < currentPart.length(); j++) {
                parts[currentPartNum++] = Character.toString(currentPart.charAt(j));
            }
        }

        return DnsName.from(parts);
    }

    /**
     * Creates the reverse DNS name for the given IPv4 address.
     * 
     * @param inet4Address the IPv4 address
     * @return the reverse DNS name
     */
    public static DnsName reverseIpAddressOf(Inet4Address inet4Address) {
        final String[] ipAddressParts = inet4Address.getHostAddress().split("\\.");
        assert ipAddressParts.length == 4;

        return DnsName.from(ipAddressParts);
    }
}
