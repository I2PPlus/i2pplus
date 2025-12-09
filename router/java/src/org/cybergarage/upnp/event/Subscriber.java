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
 * @author Satoshi Konno
 * @since 1.0
 */
public class Subscriber {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public Subscriber() {
        renew();
    }

    ////////////////////////////////////////////////
    //	SID
    ////////////////////////////////////////////////

    private String SID = null;

    public String getSID() {
        return SID;
    }

    public void setSID(String sid) {
        SID = sid;
    }

    ////////////////////////////////////////////////
    //	deliveryURL
    ////////////////////////////////////////////////

    private String ifAddr = "";

    public void setInterfaceAddress(String addr) {
        ifAddr = addr;
    }

    public String getInterfaceAddress() {
        return ifAddr;
    }

    ////////////////////////////////////////////////
    //	deliveryURL
    ////////////////////////////////////////////////

    private String deliveryURL = "";

    public String getDeliveryURL() {
        return deliveryURL;
    }

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

    private String deliveryHost = "";
    private String deliveryPath = "";
    private int deliveryPort = 0;

    public String getDeliveryHost() {
        return deliveryHost;
    }

    public String getDeliveryPath() {
        return deliveryPath;
    }

    public int getDeliveryPort() {
        return deliveryPort;
    }

    ////////////////////////////////////////////////
    //	Timeout
    ////////////////////////////////////////////////

    private long timeOut = 0;

    public long getTimeOut() {
        return timeOut;
    }

    public void setTimeOut(long value) {
        timeOut = value;
    }

    public boolean isExpired() {
        long currTime = System.currentTimeMillis();

        // Thanks for Oliver Newell (10/26/04)
        if (timeOut == Subscription.INFINITE_VALUE) return false;

        // Thanks for Oliver Newell (10/26/04)
        long expiredTime = getSubscriptionTime() + getTimeOut() * 1000;
        if (expiredTime < currTime) return true;

        return false;
    }

    ////////////////////////////////////////////////
    //	SubscriptionTIme
    ////////////////////////////////////////////////

    private long subscriptionTime = 0;

    public long getSubscriptionTime() {
        return subscriptionTime;
    }

    public void setSubscriptionTime(long time) {
        subscriptionTime = time;
    }

    ////////////////////////////////////////////////
    //	SEQ
    ////////////////////////////////////////////////

    private long notifyCount = 0;

    public long getNotifyCount() {
        return notifyCount;
    }

    public void setNotifyCount(int cnt) {
        notifyCount = cnt;
    }

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

    public void renew() {
        setSubscriptionTime(System.currentTimeMillis());
        setNotifyCount(0);
    }
}
