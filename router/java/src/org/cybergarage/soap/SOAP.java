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
     * The envelope builders below and the element constants are all static, so an
     * instance carries nothing.
     */
    public SOAP() {}

    /**
     * Name of the SOAP Envelope element.
     */
    public static final String ENVELOPE = "Envelope";
    /**
     * Name of the SOAP Body element.
     */
    public static final String BODY = "Body";
    /**
     * Name of the SOAP Response element.
     */
    public static final String RESPONSE = "Response";
    /**
     * Name of the SOAP Fault element.
     */
    public static final String FAULT = "Fault";
    /**
     * Name of the faultcode element inside a Fault.
     */
    public static final String FAULT_CODE = "faultcode";
    /**
     * Name of the faultstring element inside a Fault.
     */
    public static final String FAULT_STRING = "faultstring";
    /**
     * Name of the faultactor element inside a Fault.
     */
    public static final String FAULTACTOR = "faultactor";
    /**
     * Name of the detail element inside a Fault.
     */
    public static final String DETAIL = "detail";

    /**
     * Name of the UPnP ResultStatus element.
     */
    public static final String RESULTSTATUS = "ResultStatus";
    /**
     * Name of the UPnP error element.
     */
    public static final String UPNP_ERROR = "UPnPError";
    /**
     * Name of the UPnP error code element.
     */
    public static final String ERROR_CODE = "errorCode";
    /**
     * Name of the UPnP error description element.
     */
    public static final String ERROR_DESCRIPTION = "errorDescription";

    // public static final String XMLNS = "SOAP-ENV";
    /**
     * Short prefix declared for the SOAP envelope namespace.
     */
    public static final String XMLNS = "s";
    /**
     * Short prefix declared for the SOAP method-call namespace.
     */
    public static final String METHODNS = "u";
    /**
     * Separator between a namespace prefix and the element name.
     */
    public static final String DELIM = ":";

    /**
     * Namespace URL declared for the SOAP envelope.
     */
    public static final String XMLNS_URL = "http://schemas.xmlsoap.org/soap/envelope/";
    /**
     * Encoding style URL declared on the SOAP envelope.
     */
    public static final String ENCSTYLE_URL = "http://schemas.xmlsoap.org/soap/encoding/";

    /**
     * Content-Type sent with a SOAP message.
     */
    public static final String CONTENT_TYPE = "text/xml; charset=\"utf-8\"";
    /**
     * XML declaration prefixing every SOAP message sent.
     */
    public static final String VERSION_HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";

    ////////////////////////////////////////////////
    //	createEnvelopeBodyNode
    ////////////////////////////////////////////////

    /**
     * Builds an empty SOAP envelope with a Body child.
     *
     * @return the SOAP Envelope element, carrying an empty Body child, ready to
     *         have a SOAP Action element added to it
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
     * Installs the shared XML parser used to parse SOAP messages.
     *
     * @param parser the parser to install as the shared XML parser used by this
     *               class when it has to parse a SOAP message itself
     */
    public static final void setXMLParser(Parser parser) {
        xmlParser = parser;
    }

    /**
     * Returns the shared XML parser.
     *
     * @return the shared XML parser installed by setXMLParser, or null if none
     *         has been set
     */
    public static final Parser getXMLParser() {
        return xmlParser;
    }
}
