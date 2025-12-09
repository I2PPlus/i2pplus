/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.upnp.event;

import org.cybergarage.http.*;
import org.cybergarage.soap.*;
import org.cybergarage.upnp.device.*;
import org.cybergarage.xml.*;

/**
 * Represents a UPnP event notification request.
 *
 * <p>This class extends SOAPRequest to handle GENA (General Event Notification Architecture) NOTIFY
 * messages sent by UPnP devices to inform subscribers about state variable changes. It manages the
 * creation and parsing of event notification messages.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Event notification message creation and parsing
 *   <li>Property set and property XML structure handling
 *   <li>SID (Subscription ID) and SEQ (sequence number) management
 *   <li>NT (Notification Type) and NTS (Notification Sub-Type) headers
 *   <li>Multi-property change notifications
 * </ul>
 *
 * <p>This class is used by UPnP devices to send event notifications to subscribed control points,
 * informing them of changes in service state variables. It handles both single and multiple
 * property changes in a single notification message.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class NotifyRequest extends SOAPRequest {
    private static final String XMLNS = "e";
    private static final String PROPERTY = "property";
    private static final String PROPERTYSET = "propertyset";

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public NotifyRequest() {}

    public NotifyRequest(HTTPRequest httpReq) {
        set(httpReq);
    }

    ////////////////////////////////////////////////
    //	NT
    ////////////////////////////////////////////////

    public void setNT(String value) {
        setHeader(HTTP.NT, value);
    }

    ////////////////////////////////////////////////
    //	NTS
    ////////////////////////////////////////////////

    public void setNTS(String value) {
        setHeader(HTTP.NTS, value);
    }

    ////////////////////////////////////////////////
    //	SID
    ////////////////////////////////////////////////

    public void setSID(String id) {
        setHeader(HTTP.SID, Subscription.toSIDHeaderString(id));
    }

    public String getSID() {
        return Subscription.getSID(getHeaderValue(HTTP.SID));
    }

    ////////////////////////////////////////////////
    //	SEQ
    ////////////////////////////////////////////////

    public void setSEQ(long value) {
        setHeader(HTTP.SEQ, Long.toString(value));
    }

    public long getSEQ() {
        return getLongHeaderValue(HTTP.SEQ);
    }

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public boolean setRequest(Subscriber sub, String varName, String value) {
        String callback = sub.getDeliveryURL();
        String sid = sub.getSID();
        long notifyCnt = sub.getNotifyCount();
        String host = sub.getDeliveryHost();
        String path = sub.getDeliveryPath();
        int port = sub.getDeliveryPort();

        setMethod(HTTP.NOTIFY);
        setURI(path);
        setHost(host, port);
        setNT(NT.EVENT);
        setNTS(NTS.PROPCHANGE);
        setSID(sid);
        setSEQ(notifyCnt);

        setContentType(XML.DEFAULT_CONTENT_TYPE);
        Node propSetNode = createPropertySetNode(varName, value);
        setContent(propSetNode);

        return true;
    }

    private Node createPropertySetNode(String varName, String value) {
        Node propSetNode = new Node(/*XMLNS + SOAP.DELIM + */ PROPERTYSET);

        propSetNode.setNameSpace(XMLNS, Subscription.XMLNS);

        Node propNode = new Node(/*XMLNS + SOAP.DELIM + */ PROPERTY);
        propSetNode.addNode(propNode);

        // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (05/22/03)
        // Node varNameNode = new Node(XMLNS + SOAP.DELIM + varName);
        Node varNameNode = new Node(varName);
        varNameNode.setValue(value);
        propNode.addNode(varNameNode);

        return propSetNode;
    }

    private Node getVariableNode() {
        Node rootNode = getEnvelopeNode();
        if (rootNode == null) return null;
        if (rootNode.hasNodes() == false) return null;
        Node propNode = rootNode.getNode(0);
        if (propNode.hasNodes() == false) return null;
        return propNode.getNode(0);
    }

    // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (09/08/03)
    private Property getProperty(Node varNode) {
        Property prop = new Property();
        if (varNode == null) return prop;
        // remove the event namespace
        String variableName = varNode.getName();
        int index = variableName.lastIndexOf(':');
        if (index != -1) variableName = variableName.substring(index + 1);
        prop.setName(variableName);
        prop.setValue(varNode.getValue());
        return prop;
    }

    // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (09/08/03)
    public PropertyList getPropertyList() {
        PropertyList properties = new PropertyList();
        Node varSetNode = getEnvelopeNode();
        // I2P change: ParserException caught in getRootNode() causes
        // getEnvelopeNode() to return null
        if (varSetNode == null) return properties;
        for (int i = 0; i < varSetNode.getNNodes(); i++) {
            Node propNode = varSetNode.getNode(i);
            if (propNode == null) continue;
            Property prop = getProperty(propNode.getNode(0));
            properties.add(prop);
        }
        return properties;
    }
}
