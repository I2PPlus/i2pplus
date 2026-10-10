/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

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
    /**
     * The header constants and the timeout parsers below are all static, so an instance
     * carries nothing.
     */
    public Subscription() {}

    /**
     * XMLNS.
     */
    public static final String XMLNS = "urn:schemas-upnp-org:event-1-0";
    /**
     * TIMEOUT_HEADER.
     */
    public static final String TIMEOUT_HEADER = "Second-";
    /**
     * INFINITE_STRING.
     */
    public static final String INFINITE_STRING = "infinite";
    /**
     * INFINITE_VALUE.
     */
    public static final int INFINITE_VALUE = -1;
    /**
     * UUID.
     */
    public static final String UUID = "uuid:";
    /**
     * SUBSCRIBE_METHOD.
     */
    public static final String SUBSCRIBE_METHOD = "SUBSCRIBE";
    /**
     * UNSUBSCRIBE_METHOD.
     */
    public static final String UNSUBSCRIBE_METHOD = "UNSUBSCRIBE";

    ////////////////////////////////////////////////
    //	Timeout
    ////////////////////////////////////////////////

    /**
     * toTimeoutHeaderString.
     *
     * @param time the subscription lifetime in seconds, or INFINITE_VALUE for unlimited
     * @return the TIMEOUT header value, "infinite" when time is INFINITE_VALUE
     */
    public static final String toTimeoutHeaderString(long time) {
        if (time == Subscription.INFINITE_VALUE) return Subscription.INFINITE_STRING;
        return Subscription.TIMEOUT_HEADER + Long.toString(time);
    }

    /**
     * getTimeout.
     *
     * @param headerValue the TIMEOUT header contents, whose seconds follow a '-'
     * @return the lifetime in seconds, or INFINITE_VALUE if absent or unparseable
     */
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

    /**
     * createSID.
     *
     * @return a freshly generated UUID to serve as a subscription identifier
     */
    public static final String createSID() {
        return UPnP.createUUID();
    }

    /**
     * toSIDHeaderString.
     *
     * @param id the bare subscription identifier, without the uuid: prefix
     * @return the SID header value, the identifier with the uuid: prefix prepended
     */
    public static final String toSIDHeaderString(String id) {
        return Subscription.UUID + id;
    }

    /**
     * getSID.
     *
     * @param headerValue the SID header contents to strip the prefix from
     * @return the bare identifier, unchanged without the uuid: prefix, or "" when absent
     */
    public static final String getSID(String headerValue) {
        if (headerValue == null) return "";
        if (headerValue.startsWith(Subscription.UUID) == false) return headerValue;
        return headerValue.substring(Subscription.UUID.length(), headerValue.length());
    }
}
