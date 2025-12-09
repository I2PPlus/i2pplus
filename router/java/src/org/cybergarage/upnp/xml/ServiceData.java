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
    public ServiceData() {}

    ////////////////////////////////////////////////
    // controlActionListenerList
    ////////////////////////////////////////////////

    private ListenerList controlActionListenerList = new ListenerList();

    public ListenerList getControlActionListenerList() {
        return controlActionListenerList;
    }

    ////////////////////////////////////////////////
    // scpdNode
    ////////////////////////////////////////////////

    private Node scpdNode = null;

    public Node getSCPDNode() {
        return scpdNode;
    }

    public void setSCPDNode(Node node) {
        scpdNode = node;
    }

    ////////////////////////////////////////////////
    // SubscriberList
    ////////////////////////////////////////////////

    private SubscriberList subscriberList = new SubscriberList();

    public SubscriberList getSubscriberList() {
        return subscriberList;
    }

    ////////////////////////////////////////////////
    // SID
    ////////////////////////////////////////////////

    private String descriptionURL = "";

    public String getDescriptionURL() {
        return descriptionURL;
    }

    public void setDescriptionURL(String descriptionURL) {
        this.descriptionURL = descriptionURL;
    }

    ////////////////////////////////////////////////
    // SID
    ////////////////////////////////////////////////

    private String sid = "";

    public String getSID() {
        return sid;
    }

    public void setSID(String id) {
        sid = id;
    }

    ////////////////////////////////////////////////
    // Timeout
    ////////////////////////////////////////////////

    private long timeout = 0;

    public long getTimeout() {
        return timeout;
    }

    public void setTimeout(long value) {
        timeout = value;
    }
}
