/*
 * CyberSOAP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.soap;

import org.cybergarage.xml.Node;
import org.cybergarage.xml.Parser;

/**
 * Utility class for SOAP (Simple Object Access Protocol) message handling.
 *
 * <p>This class provides static methods and constants for creating and manipulating SOAP messages
 * used in UPnP control operations. It includes functionality for:
 *
 * <ul>
 *   <li>Creating SOAP envelope and body structures
 *   <li>Generating SOAP fault messages
 *   <li>Handling UPnP-specific SOAP error responses
 *   <li>SOAP XML namespace and content type management
 * </ul>
 *
 * <p>The class primarily serves as a factory for creating SOAP message structures and provides
 * constants for standard SOAP elements and namespaces used throughout the UPnP framework.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class SOAP {
    /**
     * ENVELOPE.
     */
    public static final String ENVELOPE = "Envelope";
    /**
     * BODY.
     */
    public static final String BODY = "Body";
    /**
     * RESPONSE.
     */
    public static final String RESPONSE = "Response";
    /**
     * FAULT.
     */
    public static final String FAULT = "Fault";
    /**
     * FAULT_CODE.
     */
    public static final String FAULT_CODE = "faultcode";
    /**
     * FAULT_STRING.
     */
    public static final String FAULT_STRING = "faultstring";
    /**
     * FAULTACTOR.
     */
    public static final String FAULTACTOR = "faultactor";
    /**
     * DETAIL.
     */
    public static final String DETAIL = "detail";

    /**
     * RESULTSTATUS.
     */
    public static final String RESULTSTATUS = "ResultStatus";
    /**
     * UPNP_ERROR.
     */
    public static final String UPNP_ERROR = "UPnPError";
    /**
     * ERROR_CODE.
     */
    public static final String ERROR_CODE = "errorCode";
    /**
     * ERROR_DESCRIPTION.
     */
    public static final String ERROR_DESCRIPTION = "errorDescription";

    // public static final String XMLNS = "SOAP-ENV";
    /**
     * XMLNS.
     */
    public static final String XMLNS = "s";
    /**
     * METHODNS.
     */
    public static final String METHODNS = "u";
    /**
     * DELIM.
     */
    public static final String DELIM = ":";

    /**
     * XMLNS_URL.
     */
    public static final String XMLNS_URL = "http://schemas.xmlsoap.org/soap/envelope/";
    /**
     * ENCSTYLE_URL.
     */
    public static final String ENCSTYLE_URL = "http://schemas.xmlsoap.org/soap/encoding/";

    /**
     * CONTENT_TYPE.
     */
    public static final String CONTENT_TYPE = "text/xml; charset=\"utf-8\"";
    /**
     * VERSION_HEADER.
     */
    public static final String VERSION_HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";

    ////////////////////////////////////////////////
    //	createEnvelopeBodyNode
    ////////////////////////////////////////////////

    /**
     * createEnvelopeBodyNode.
     */
    public static final Node createEnvelopeBodyNode() {
        // <Envelope>
        Node envNode = new Node(SOAP.XMLNS + SOAP.DELIM + SOAP.ENVELOPE);
        envNode.setAttribute("xmlns" + SOAP.DELIM + SOAP.XMLNS, SOAP.XMLNS_URL);
        envNode.setAttribute(SOAP.XMLNS + SOAP.DELIM + "encodingStyle", SOAP.ENCSTYLE_URL);

        // <Body>
        Node bodyNode = new Node(SOAP.XMLNS + SOAP.DELIM + SOAP.BODY);
        envNode.addNode(bodyNode);

        return envNode;
    }

    ////////////////////////////////////////////////
    // XML Parser
    ////////////////////////////////////////////////

    private static Parser xmlParser;

    /**
     * setXMLParser.
     */
    public static final void setXMLParser(Parser parser) {
        xmlParser = parser;
    }

    /**
     * getXMLParser.
     */
    public static final Parser getXMLParser() {
        return xmlParser;
    }
}
