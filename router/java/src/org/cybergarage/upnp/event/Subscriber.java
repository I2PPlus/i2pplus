/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.event;

import java.net.*;

/**
 * Represents a UPnP event subscriber.
 *
 * <p>This class encapsulates information about a control point that has subscribed to receive event
 * notifications from a UPnP service. It manages the subscription lifecycle including timeout,
 * renewal, and delivery URL information.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Subscription ID (SID) management
 *   <li>Delivery URL and callback address handling
 *   <li>Subscription timeout and renewal tracking
 *   <li>Expiration checking and management
 *   <li>Sequence number tracking for notifications
 * </ul>
 *
 * <p>This class is used by UPnP services to maintain information about each subscriber, enabling
 * targeted delivery of event notifications and management of subscription lifecycles including
 * automatic cleanup of expired subscriptions.
 *
 * @since 1.0
 */
public class Subscriber {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Creates a subscription that is renewed as of now.
     */
    public Subscriber() {
        renew();
    }

    ////////////////////////////////////////////////
    //	SID
    ////////////////////////////////////////////////

    private String SID = null;

    /**
     * Returns the subscription ID.
     *
     * @return the subscription ID assigned by the service, null until one is set
     */
    public String getSID() {
        return SID;
    }

    /**
     * Records the subscription ID.
     *
     * @param sid the subscription ID the service issued
     */
    public void setSID(String sid) {
        SID = sid;
    }

    ////////////////////////////////////////////////
    //	deliveryURL
    ////////////////////////////////////////////////

    private String ifAddr = "";

    /**
     * Records the local interface address notifications go out from.
     *
     * @param addr the local interface address notifications are sent from
     */
    public void setInterfaceAddress(String addr) {
        ifAddr = addr;
    }

    /**
     * Returns the local interface address.
     *
     * @return the local interface address, empty until one is set
     */
    public String getInterfaceAddress() {
        return ifAddr;
    }

    ////////////////////////////////////////////////
    //	deliveryURL
    ////////////////////////////////////////////////

    private String deliveryURL = "";

    /**
     * Returns the callback URL notifications are POSTed to.
     *
     * @return the callback URL notifications are POSTed to, empty until one is set
     */
    public String getDeliveryURL() {
        return deliveryURL;
    }

    /**
     * Records the callback URL and caches its host, path and port.
     *
     * @param deliveryURL the callback URL to POST to, also split into the cached host, path and
              port; an unparsable URL leaves those at their defaults
     */
    public void setDeliveryURL(String deliveryURL) {
        this.deliveryURL = deliveryURL;
        try {
            URL url = new URL(deliveryURL);
            deliveryHost = url.getHost();
            deliveryPath = url.getPath();
            deliveryPort = url.getPort();
        } catch (Exception e) {
            // Use default deliveryHost, deliveryPath, deliveryPort values
        }
    }

    /** Host notifications are sent to. */
    private String deliveryHost = "";
    /** Path notifications are sent to. */
    private String deliveryPath = "";
    /** Port notifications are sent to. */
    private int deliveryPort = 0;

    /**
     * Returns the host parsed from the delivery URL.
     *
     * @return the host parsed out of the delivery URL, empty until one is set
     */
    public String getDeliveryHost() {
        return deliveryHost;
    }

    /**
     * Returns the path parsed from the delivery URL.
     *
     * @return the path parsed out of the delivery URL, empty until one is set
     */
    public String getDeliveryPath() {
        return deliveryPath;
    }

    /**
     * Returns the port parsed from the delivery URL.
     *
     * @return the port parsed out of the delivery URL, 0 until one is set
     */
    public int getDeliveryPort() {
        return deliveryPort;
    }

    ////////////////////////////////////////////////
    //	Timeout
    ////////////////////////////////////////////////

    private long timeOut = 0;

    /**
     * Returns the subscription duration.
     *
     * @return the subscription duration in seconds, or {@link Subscription#INFINITE_VALUE} for none
     */
    public long getTimeOut() {
        return timeOut;
    }

    /**
     * Records how long the subscription lasts.
     *
     * @param value the duration in seconds from now, or INFINITE_VALUE for no expiry
     */
    public void setTimeOut(long value) {
        timeOut = value;
    }

    /**
     * Returns whether the subscription duration has run out.
     *
     * @return true once the timeout has elapsed, false if it is infinite or still unexpired
     */
    public boolean isExpired() {
        long currTime = System.currentTimeMillis();

        if (timeOut == Subscription.INFINITE_VALUE) return false;

        long expiredTime = getSubscriptionTime() + getTimeOut() * 1000;
        if (expiredTime < currTime) return true;

        return false;
    }

    ////////////////////////////////////////////////
    //	SubscriptionTIme
    ////////////////////////////////////////////////

    private long subscriptionTime = 0;

    /**
     * Returns when the subscription was last renewed.
     *
     * @return when the subscription was last renewed, in milliseconds since the epoch
     */
    public long getSubscriptionTime() {
        return subscriptionTime;
    }

    /**
     * Records when the subscription started.
     *
     * @param time when the subscription starts, in milliseconds since the epoch
     */
    public void setSubscriptionTime(long time) {
        subscriptionTime = time;
    }

    ////////////////////////////////////////////////
    //	SEQ
    ////////////////////////////////////////////////

    private long notifyCount = 0;

    /**
     * Returns the notification count since the last renewal.
     *
     * @return the notification count since the last renewal, which wraps to 1 rather than overflow
     */
    public long getNotifyCount() {
        return notifyCount;
    }

    /**
     * Records the notification count.
     *
     * @param cnt the notification count to record, as zeroed again by {@link #renew()}
     */
    public void setNotifyCount(int cnt) {
        notifyCount = cnt;
    }

    /**
     * Increments the notification count, wrapping back to 1 at the maximum.
     */
    public void incrementNotifyCount() {
        if (notifyCount == Long.MAX_VALUE) {
            notifyCount = 1;
            return;
        }
        notifyCount++;
    }

    ////////////////////////////////////////////////
    //	renew
    ////////////////////////////////////////////////

    /**
     * Restarts the subscription clock and zeroes the notification count.
     */
    public void renew() {
        setSubscriptionTime(System.currentTimeMillis());
        setNotifyCount(0);
    }
}
