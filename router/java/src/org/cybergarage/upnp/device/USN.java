/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.device;

import java.nio.charset.StandardCharsets;

/**
 * Utility class for SSDP USN (Unique Service Name) header values and parsing.
 *
 * <p>This class provides constants and utility methods for handling USN header used in SSDP
 * messages. The USN header provides a unique identifier for devices and services in UPnP networks.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Root device USN validation
 *   <li>UDN (Unique Device Name) extraction
 *   <li>USN parsing and manipulation
 *   <li>UTF-8 encoding support
 *   <li>Null-safe value handling
 * </ul>
 *
 * <p>This class is used by SSDP components to parse and validate USN headers in discovery and
 * advertisement messages, enabling proper device identification and service differentiation in UPnP
 * networks.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class USN {
    public static final String ROOTDEVICE = "upnp:rootdevice";

    public static final boolean isRootDevice(String usnValue) {
        if (usnValue == null) return false;
        return usnValue.endsWith(ROOTDEVICE);
    }

    public static final String getUDN(String usnValue) {
        if (usnValue == null) return "";
        int idx = usnValue.indexOf("::");
        if (idx < 0) return usnValue.trim();
        byte[] usnBytes = usnValue.getBytes(StandardCharsets.UTF_8);
        String udnValue = new String(usnBytes, 0, idx, StandardCharsets.UTF_8);
        return udnValue.trim();
    }
}
