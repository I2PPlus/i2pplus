/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 ******************************************************************/

package org.cybergarage.upnp.event;

import org.cybergarage.upnp.*;

/**
 * Utility class for UPnP event subscription management.
 *
 * <p>This class provides constants and utility methods for managing UPnP event subscriptions,
 * including timeout handling, subscription methods, and UUID generation. It serves as a helper
 * class for the GENA (General Event Notification Architecture) protocol used in UPnP.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Subscription timeout management and conversion
 *   <li>SUBSCRIBE and UNSUBSCRIBE method constants
 *   <li>UUID generation and management
 *   <li>XML namespace definitions for event messages
 *   <li>Infinite timeout support
 * </ul>
 *
 * <p>This class provides the foundational constants and utility methods used throughout the UPnP
 * event notification system for managing subscription lifecycles.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class Subscription {
    public static final String XMLNS = "urn:schemas-upnp-org:event-1-0";
    public static final String TIMEOUT_HEADER = "Second-";
    public static final String INFINITE_STRING = "infinite";
    public static final int INFINITE_VALUE = -1;
    public static final String UUID = "uuid:";
    public static final String SUBSCRIBE_METHOD = "SUBSCRIBE";
    public static final String UNSUBSCRIBE_METHOD = "UNSUBSCRIBE";

    ////////////////////////////////////////////////
    //	Timeout
    ////////////////////////////////////////////////

    public static final String toTimeoutHeaderString(long time) {
        if (time == Subscription.INFINITE_VALUE) return Subscription.INFINITE_STRING;
        return Subscription.TIMEOUT_HEADER + Long.toString(time);
    }

    public static final long getTimeout(String headerValue) {
        int minusIdx = headerValue.indexOf('-');
        long timeout = Subscription.INFINITE_VALUE;
        try {
            String timeoutStr = headerValue.substring(minusIdx + 1, headerValue.length());
            timeout = Long.parseLong(timeoutStr);
        } catch (Exception e) {
            // Use default timeout value INFINITE_VALUE
        }
        return timeout;
    }

    ////////////////////////////////////////////////
    //	SID
    ////////////////////////////////////////////////

    public static final String createSID() {
        return UPnP.createUUID();
    }

    public static final String toSIDHeaderString(String id) {
        return Subscription.UUID + id;
    }

    public static final String getSID(String headerValue) {
        if (headerValue == null) return "";
        if (headerValue.startsWith(Subscription.UUID) == false) return headerValue;
        return headerValue.substring(Subscription.UUID.length(), headerValue.length());
    }
}
