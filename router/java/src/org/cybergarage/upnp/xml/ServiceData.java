/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.xml;

import org.cybergarage.upnp.event.*;
import org.cybergarage.util.*;
import org.cybergarage.xml.*;

/**
 * Data container for UPnP service information and metadata.
 *
 * <p>This class extends NodeData to represent service definitions from UPnP device descriptions. It
 * encapsulates metadata about services including action listeners, subscribers, and service
 * configuration.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Action listener management
 *   <li>Subscriber list handling
 *   <li>Service configuration data
 *   <li>XML node data inheritance
 *   <li>Event notification support
 * </ul>
 *
 * <p>This class is used by UPnP services to manage their metadata, listener registrations, and
 * subscriber information, enabling proper service operation and event notification functionality.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class ServiceData extends NodeData {
    /**
     * ServiceData.
     */
    public ServiceData() {}

    ////////////////////////////////////////////////
    // controlActionListenerList
    ////////////////////////////////////////////////

    private ListenerList controlActionListenerList = new ListenerList();

    /**
     * getControlActionListenerList.
     *
     * @return the listeners registered to receive this service's control actions
     */
    public ListenerList getControlActionListenerList() {
        return controlActionListenerList;
    }

    ////////////////////////////////////////////////
    // scpdNode
    ////////////////////////////////////////////////

    private Node scpdNode = null;

    /**
     * getSCPDNode.
     *
     * @return the parsed service control protocol description node, null if none was set
     */
    public Node getSCPDNode() {
        return scpdNode;
    }

    /**
     * setSCPDNode.
     *
     * @param node the parsed service control protocol description to hold
     */
    public void setSCPDNode(Node node) {
        scpdNode = node;
    }

    ////////////////////////////////////////////////
    // SubscriberList
    ////////////////////////////////////////////////

    private SubscriberList subscriberList = new SubscriberList();

    /**
     * getSubscriberList.
     *
     * @return the subscribers currently registered for this service
     */
    public SubscriberList getSubscriberList() {
        return subscriberList;
    }

    ////////////////////////////////////////////////
    // SID
    ////////////////////////////////////////////////

    private String descriptionURL = "";

    /**
     * getDescriptionURL.
     *
     * @return the URL of the device description document this service came from
     */
    public String getDescriptionURL() {
        return descriptionURL;
    }

    /**
     * setDescriptionURL.
     *
     * @param descriptionURL the URL of the device description document this service came from
     */
    public void setDescriptionURL(String descriptionURL) {
        this.descriptionURL = descriptionURL;
    }

    ////////////////////////////////////////////////
    // SID
    ////////////////////////////////////////////////

    private String sid = "";

    /**
     * getSID.
     *
     * @return the subscription identifier issued for this service
     */
    public String getSID() {
        return sid;
    }

    /**
     * setSID.
     *
     * @param id the subscription identifier issued for this service
     */
    public void setSID(String id) {
        sid = id;
    }

    ////////////////////////////////////////////////
    // Timeout
    ////////////////////////////////////////////////

    private long timeout = 0;

    /**
     * getTimeout.
     *
     * @return the subscription duration in seconds, 0 when the subscription was never leased
     */
    public long getTimeout() {
        return timeout;
    }

    /**
     * setTimeout.
     *
     * @param value the subscription duration in seconds requested at lease time
     */
    public void setTimeout(long value) {
        timeout = value;
    }
}
