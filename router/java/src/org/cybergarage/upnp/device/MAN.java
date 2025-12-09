/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.device;

/**
 * Utility class for SSDP MAN (Method Application Namespace) header values.
 *
 * <p>This class provides constants and utility methods for handling the MAN header used in SSDP
 * M-SEARCH requests. The MAN header specifies the method application namespace for the discovery
 * operation.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>SSDP discover constant definition
 *   <li>MAN header value validation
 *   <li>Quoted and unquoted value support
 *   <li>Null-safe value checking
 * </ul>
 *
 * <p>This class is used by SSDP components to validate and process MAN headers in M-SEARCH
 * requests, ensuring proper discovery protocol compliance.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class MAN {
    public static final String DISCOVER = "ssdp:discover";

    public static final boolean isDiscover(String value) {
        if (value == null) return false;
        if (value.equals(MAN.DISCOVER) == true) return true;
        return value.equals("\"" + MAN.DISCOVER + "\"");
    }
}
