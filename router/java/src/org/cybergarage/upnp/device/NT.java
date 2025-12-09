/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.device;

/**
 * Utility class for SSDP NT (Notification Type) header values and validation.
 *
 * <p>This class provides constants and utility methods for handling NT header used in SSDP NOTIFY
 * messages. The NT header specifies the type of notification being sent for device advertisement.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Notification type constants
 *   <li>Root device notification support
 *   <li>Event notification type handling
 *   <li>Notification type validation
 *   <li>Null-safe value checking
 * </ul>
 *
 * <p>This class is used by SSDP components to validate and process NT headers in notification
 * messages, enabling proper classification of device advertisements and event notifications.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class NT {
    public static final String ROOTDEVICE = "upnp:rootdevice";
    public static final String EVENT = "upnp:event";

    public static final boolean isRootDevice(String ntValue) {
        if (ntValue == null) return false;
        return ntValue.startsWith(ROOTDEVICE);
    }
}
