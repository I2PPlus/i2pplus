/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.device;

/**
 * Utility class for SSDP ST (Search Target) header values and validation.
 *
 * <p>This class provides constants and utility methods for handling ST header used in SSDP M-SEARCH
 * requests and responses. The ST header specifies the search target for device discovery
 * operations.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Search target constants for different device types
 *   <li>All device search support
 *   <li>Root device detection
 *   <li>UUID-based device identification
 *   <li>URN device and service validation
 *   <li>Quoted and unquoted value support
 * </ul>
 *
 * <p>This class is used by SSDP components to validate and process ST headers in search requests
 * and responses, enabling targeted device discovery and proper search target classification.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class ST {
    /**
     * The search-target constants and the matching tests below are all static, so an
     * instance carries nothing.
     */
    public ST() {}

    /**
     * ALL_DEVICE.
     */
    public static final String ALL_DEVICE = "ssdp:all";
    /**
     * ROOT_DEVICE.
     */
    public static final String ROOT_DEVICE = "upnp:rootdevice";
    /**
     * UUID_DEVICE.
     */
    public static final String UUID_DEVICE = "uuid";
    /**
     * URN_DEVICE.
     */
    public static final String URN_DEVICE = "urn:schemas-upnp-org:device:";
    /**
     * URN_SERVICE.
     */
    public static final String URN_SERVICE = "urn:schemas-upnp-org:service:";

    /**
     * isAllDevice.
     *
     * @param value the UPnP search target to test, quoted or unquoted
     * @return true if the target matches the all-device search target
     */
    public static final boolean isAllDevice(String value) {
        if (value == null) return false;
        if (value.equals(ALL_DEVICE) == true) return true;
        return value.equals("\"" + ALL_DEVICE + "\"");
    }

    /**
     * isRootDevice.
     *
     * @param value the UPnP search target to test, quoted or unquoted
     * @return true if the target matches the root-device search target
     */
    public static final boolean isRootDevice(String value) {
        if (value == null) return false;
        if (value.equals(ROOT_DEVICE) == true) return true;
        return value.equals("\"" + ROOT_DEVICE + "\"");
    }

    /**
     * isUUIDDevice.
     *
     * @param value the UPnP search target to test, quoted or unquoted
     * @return true if the target begins with the UUID-device search target
     */
    public static final boolean isUUIDDevice(String value) {
        if (value == null) return false;
        if (value.startsWith(UUID_DEVICE) == true) return true;
        return value.startsWith("\"" + UUID_DEVICE);
    }

    /**
     * isURNDevice.
     *
     * @param value the UPnP search target to test, quoted or unquoted
     * @return true if the target begins with the device URN prefix
     */
    public static final boolean isURNDevice(String value) {
        if (value == null) return false;
        if (value.startsWith(URN_DEVICE) == true) return true;
        return value.startsWith("\"" + URN_DEVICE);
    }

    /**
     * isURNService.
     *
     * @param value the UPnP search target to test, quoted or unquoted
     * @return true if the target begins with the service URN prefix
     */
    public static final boolean isURNService(String value) {
        if (value == null) return false;
        if (value.startsWith(URN_SERVICE) == true) return true;
        return value.startsWith("\"" + URN_SERVICE);
    }
}
