/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.device;

/**
 * Utility class for SSDP NTS (Notification Sub-Type) header values.
 *
 * <p>This class provides constants and utility methods for handling NTS header used in SSDP NOTIFY
 * messages. The NTS header specifies the sub-type of notification being sent.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>SSDP notification type constants
 *   <li>Alive notification detection
 *   <li>Bye-bye notification detection
 *   <li>Property change notification support
 *   <li>Null-safe value checking
 * </ul>
 *
 * <p>This class is used by SSDP components to classify and process notification messages, enabling
 * proper handling of device presence announcements and departure notifications.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class NTS {
    /** default constructor */
    public NTS() {}

    /** Alive notification */
    public static final String ALIVE = "ssdp:alive";
    /** Bye-bye notification */
    public static final String BYEBYE = "ssdp:byebye";
    /** Property change notification */
    public static final String PROPCHANGE = "upnp:propchange";

    /** @param ntsValue the NTS value to check */
    public static final boolean isAlive(String ntsValue) {
        if (ntsValue == null) return false;
        return ntsValue.startsWith(NTS.ALIVE);
    }

    /** @param ntsValue the NTS value to check */
    public static final boolean isByeBye(String ntsValue) {
        if (ntsValue == null) return false;
        return ntsValue.startsWith(NTS.BYEBYE);
    }
}
