/*
 * CyberSOAP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.soap;

import org.cybergarage.http.HTTPResponse;
import org.cybergarage.util.Debug;
import org.cybergarage.xml.Node;
import org.cybergarage.xml.XML;

/**
 * Represents a SOAP (Simple Object Access Protocol) response message.
 *
 * <p>This class extends HTTPResponse to handle SOAP protocol responses used in UPnP control
 * operations. It provides functionality for creating and parsing SOAP responses with proper XML
 * formatting and envelope structure.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>SOAP envelope and body creation
 *   <li>XML content management
 *   <li>UTF-8 encoding support
 *   <li>HTTP response integration
 *   <li>Fault message handling
 * </ul>
 *
 * <p>This class is used by UPnP devices to send action invocation responses to control points,
 * enabling structured XML responses through SOAP messaging over HTTP.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SOAPResponse extends HTTPResponse {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * SOAPResponse.
     */
    public SOAPResponse() {
        setRootNode(SOAP.createEnvelopeBodyNode());
        setContentType(XML.DEFAULT_CONTENT_TYPE);
    }

    /**
     * SOAPResponse.
     */
    public SOAPResponse(HTTPResponse httpRes) {
        super(httpRes);
        setRootNode(SOAP.createEnvelopeBodyNode());
        setContentType(XML.DEFAULT_CONTENT_TYPE);
    }

    /**
     * SOAPResponse.
     */
    public SOAPResponse(SOAPResponse soapRes) {
        super(soapRes);
        setEnvelopeNode(soapRes.getEnvelopeNode());
        setContentType(XML.DEFAULT_CONTENT_TYPE);
    }

    ////////////////////////////////////////////////
    //	Node
    ////////////////////////////////////////////////

    private Node rootNode;

    private void setRootNode(Node node) {
        rootNode = node;
    }

    private Node getRootNode() {
        return rootNode;
    }

    ////////////////////////////////////////////////
    //	SOAP Basic
    ////////////////////////////////////////////////

    /**
     * Set the SOAP envelope node.
     *
     * @param node the envelope node to set
     */
    public void setEnvelopeNode(Node node) {
        setRootNode(node);
    }

    /**
     * Get the SOAP envelope node.
     *
     * @return the envelope node, or null if not set
     */
    public Node getEnvelopeNode() {
        return getRootNode();
    }

    /**
     * Get the SOAP body node.
     *
     * @return the body node, or null if no envelope
     */
    public Node getBodyNode() {
        Node envNode = getEnvelopeNode();
        if (envNode == null) return null;
        return envNode.getNodeEndsWith(SOAP.BODY);
    }

    /**
     * Get the method response node for the given name.
     *
     * @param name the method name
     * @return the response node, or null if not found
     */
    public Node getMethodResponseNode(String name) {
        Node bodyNode = getBodyNode();
        if (bodyNode == null) return null;
        String methodResName = name + SOAP.RESPONSE;
        return bodyNode.getNodeEndsWith(methodResName);
    }

    /**
     * Get the SOAP fault node.
     *
     * @return the fault node, or null if not found
     */
    public Node getFaultNode() {
        Node bodyNode = getBodyNode();
        if (bodyNode == null) return null;
        return bodyNode.getNodeEndsWith(SOAP.FAULT);
    }

    /**
     * Get the SOAP fault code node.
     *
     * @return the fault code node, or null if not found
     */
    public Node getFaultCodeNode() {
        Node faultNode = getFaultNode();
        if (faultNode == null) return null;
        return faultNode.getNodeEndsWith(SOAP.FAULT_CODE);
    }

    /**
     * Get the SOAP fault string node.
     *
     * @return the fault string node, or null if not found
     */
    public Node getFaultStringNode() {
        Node faultNode = getFaultNode();
        if (faultNode == null) return null;
        return faultNode.getNodeEndsWith(SOAP.FAULT_STRING);
    }

    /**
     * Get the SOAP fault actor node.
     *
     * @return the fault actor node, or null if not found
     */
    public Node getFaultActorNode() {
        Node faultNode = getFaultNode();
        if (faultNode == null) return null;
        return faultNode.getNodeEndsWith(SOAP.FAULTACTOR);
    }

    /**
     * Get the SOAP fault detail node.
     *
     * @return the fault detail node, or null if not found
     */
    public Node getFaultDetailNode() {
        Node faultNode = getFaultNode();
        if (faultNode == null) return null;
        return faultNode.getNodeEndsWith(SOAP.DETAIL);
    }

    /**
     * Get the SOAP fault code string.
     *
     * @return the fault code, or empty string if not found
     */
    public String getFaultCode() {
        Node node = getFaultCodeNode();
        if (node == null) return "";
        return node.getValue();
    }

    /**
     * Get the SOAP fault string.
     *
     * @return the fault string, or empty string if not found
     */
    public String getFaultString() {
        Node node = getFaultStringNode();
        if (node == null) return "";
        return node.getValue();
    }

    /**
     * Get the SOAP fault actor string.
     *
     * @return the fault actor, or empty string if not found
     */
    public String getFaultActor() {
        Node node = getFaultActorNode();
        if (node == null) return "";
        return node.getValue();
    }

    ////////////////////////////////////////////////
    //	XML Contents
    ////////////////////////////////////////////////

    /**
     * Set the content from an XML node.
     *
     * @param node the XML node to set as content
     */
    public void setContent(Node node) {
        // Thanks for Ralf G. R. Bergs <Ralf@Ber.gs>, Inma Marin Lopez <inma@dif.um.es>.
        String conStr = "";
        conStr += SOAP.VERSION_HEADER;
        conStr += "\n";
        conStr += node.toString();
        setContent(conStr);
    }

    ////////////////////////////////////////////////
    //	print
    ////////////////////////////////////////////////

    /**
     * print.
     */
    public void print() {
        Debug.message(toString());
        if (hasContent() == true) return;
        Node rootElem = getRootNode();
        if (rootElem == null) return;
        Debug.message(rootElem.toString());
    }
}
