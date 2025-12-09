/*
 * CyberSOAP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.soap;

import org.cybergarage.http.HTTP;
import org.cybergarage.http.HTTPRequest;
import org.cybergarage.http.HTTPResponse;
import org.cybergarage.util.Debug;
import org.cybergarage.xml.Node;
import org.cybergarage.xml.Parser;
import org.cybergarage.xml.ParserException;

import java.io.ByteArrayInputStream;

/**
 * Represents a SOAP (Simple Object Access Protocol) request message.
 *
 * <p>This class extends HTTPRequest to handle SOAP protocol messages used in UPnP control
 * operations. It provides functionality for creating and parsing SOAP requests with proper XML
 * formatting and SOAP action headers.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>SOAP action header management
 *   <li>XML content parsing and generation
 *   <li>SOAP envelope and body handling
 *   <li>UTF-8 encoding support
 *   <li>HTTP protocol integration
 * </ul>
 *
 * <p>This class is used by UPnP control points to send action invocation requests to UPnP devices,
 * enabling remote procedure calls through SOAP messaging over HTTP.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SOAPRequest extends HTTPRequest {
    private static final String SOAPACTION = HTTP.SOAP_ACTION;

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public SOAPRequest() {
        setContentType(SOAP.CONTENT_TYPE);
        setMethod(HTTP.POST);
    }

    public SOAPRequest(HTTPRequest httpReq) {
        set(httpReq);
    }

    ////////////////////////////////////////////////
    // SOAPACTION
    ////////////////////////////////////////////////

    public void setSOAPAction(String action) {
        setStringHeader(SOAPACTION, action);
    }

    public String getSOAPAction() {
        return getStringHeaderValue(SOAPACTION);
    }

    public boolean isSOAPAction(String value) {
        String headerValue = getHeaderValue(SOAPACTION);
        if (headerValue == null) return false;
        if (headerValue.equals(value) == true) return true;
        String soapAction = getSOAPAction();
        if (soapAction == null) return false;
        return soapAction.equals(value);
    }

    ////////////////////////////////////////////////
    //	post
    ////////////////////////////////////////////////

    public SOAPResponse postMessage(String host, int port) {
        HTTPResponse httpRes = post(host, port);

        SOAPResponse soapRes = new SOAPResponse(httpRes);

        byte content[] = soapRes.getContent();
        if (content.length <= 0) return soapRes;

        try {
            ByteArrayInputStream byteIn = new ByteArrayInputStream(content);
            Parser xmlParser = SOAP.getXMLParser();
            Node rootNode = xmlParser.parse(byteIn);
            soapRes.setEnvelopeNode(rootNode);
        } catch (Exception e) {
            Debug.warning(e);
        }

        return soapRes;
    }

    ////////////////////////////////////////////////
    //	Node
    ////////////////////////////////////////////////

    private Node rootNode;

    private void setRootNode(Node node) {
        rootNode = node;
    }

    private synchronized Node getRootNode() {
        if (rootNode != null) return rootNode;

        try {
            byte content[] = getContent();
            ByteArrayInputStream contentIn = new ByteArrayInputStream(content);
            Parser parser = SOAP.getXMLParser();
            rootNode = parser.parse(contentIn);
        } catch (ParserException e) {
            Debug.warning(e);
        }

        return rootNode;
    }

    ////////////////////////////////////////////////
    //	XML
    ////////////////////////////////////////////////

    public void setEnvelopeNode(Node node) {
        setRootNode(node);
    }

    public Node getEnvelopeNode() {
        return getRootNode();
    }

    public Node getBodyNode() {
        Node envNode = getEnvelopeNode();
        if (envNode == null) return null;
        if (envNode.hasNodes() == false) return null;
        return envNode.getNode(0);
    }

    ////////////////////////////////////////////////
    //	XML Contents
    ////////////////////////////////////////////////

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

    public void print() {
        Debug.message(toString());
        if (hasContent() == true) return;
        Node rootElem = getRootNode();
        if (rootElem == null) return;
        Debug.message(rootElem.toString());
    }
}
