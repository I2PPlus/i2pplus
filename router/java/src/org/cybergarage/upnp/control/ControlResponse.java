/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.control;

import org.cybergarage.http.*;
import org.cybergarage.soap.*;
import org.cybergarage.upnp.*;
import org.cybergarage.xml.*;

/**
 * Represents a UPnP control response. Extends SOAPResponse to provide UPnP-specific error handling
 * and fault response functionality.
 */
public class ControlResponse extends SOAPResponse {
    /** Default fault code for UPnP errors */
    public static final String FAULT_CODE = "Client";

    /** Default fault string for UPnP errors */
    public static final String FAULT_STRING = "UPnPError";

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /** Default constructor */
    public ControlResponse() {
        setServer(UPnP.getServerName());
    }

    /**
     * Constructs a ControlResponse from a SOAP response.
     *
     * @param soapRes the SOAP response to wrap
     */
    public ControlResponse(SOAPResponse soapRes) {
        super(soapRes);
    }

    ////////////////////////////////////////////////
    //	FaultResponse
    ////////////////////////////////////////////////

    /**
     * Sets a fault response with the specified error code and description.
     *
     * @param errCode the UPnP error code
     * @param errDescr the error description
     */
    public void setFaultResponse(int errCode, String errDescr) {
        setStatusCode(HTTPStatus.INTERNAL_SERVER_ERROR);

        Node bodyNode = getBodyNode();
        Node faultNode = createFaultResponseNode(errCode, errDescr);
        bodyNode.addNode(faultNode);

        Node envNode = getEnvelopeNode();
        setContent(envNode);
    }

    /**
     * Sets a fault response with the specified error code. The error description is automatically
     * generated from the error code.
     *
     * @param errCode the UPnP error code
     */
    public void setFaultResponse(int errCode) {
        setFaultResponse(errCode, UPnPStatus.code2String(errCode));
    }

    ////////////////////////////////////////////////
    //	createFaultResponseNode
    ////////////////////////////////////////////////

    private Node createFaultResponseNode(int errCode, String errDescr) {
        // <s:Fault>
        Node faultNode = new Node(SOAP.XMLNS + SOAP.DELIM + SOAP.FAULT);

        // <faultcode>s:Client</faultcode>
        Node faultCodeNode = new Node(SOAP.FAULT_CODE);
        faultCodeNode.setValue(SOAP.XMLNS + SOAP.DELIM + FAULT_CODE);
        faultNode.addNode(faultCodeNode);

        // <faultstring>UPnPError</faultstring>
        Node faultStringNode = new Node(SOAP.FAULT_STRING);
        faultStringNode.setValue(FAULT_STRING);
        faultNode.addNode(faultStringNode);

        // <detail>
        Node detailNode = new Node(SOAP.DETAIL);
        faultNode.addNode(detailNode);

        // <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
        Node upnpErrorNode = new Node(FAULT_STRING);
        upnpErrorNode.setAttribute("xmlns", Control.XMLNS);
        detailNode.addNode(upnpErrorNode);

        // <errorCode>error code</errorCode>
        Node errorCodeNode = new Node(SOAP.ERROR_CODE);
        errorCodeNode.setValue(errCode);
        upnpErrorNode.addNode(errorCodeNode);

        // <errorDescription>error string</errorDescription>
        Node errorDesctiprionNode = new Node(SOAP.ERROR_DESCRIPTION);
        errorDesctiprionNode.setValue(errDescr);
        upnpErrorNode.addNode(errorDesctiprionNode);

        return faultNode;
    }

    private Node createFaultResponseNode(int errCode) {
        return createFaultResponseNode(errCode, UPnPStatus.code2String(errCode));
    }

    ////////////////////////////////////////////////
    //	UPnP Error
    ////////////////////////////////////////////////

    private UPnPStatus upnpErr = new UPnPStatus();

    /**
     * Gets the UPnP error node from the fault detail.
     *
     * @return the UPnP error node, or null if not found
     */
    private Node getUPnPErrorNode() {
        Node detailNode = getFaultDetailNode();
        if (detailNode == null) return null;
        return detailNode.getNodeEndsWith(SOAP.UPNP_ERROR);
    }

    /**
     * Gets the UPnP error code node.
     *
     * @return the error code node, or null if not found
     */
    private Node getUPnPErrorCodeNode() {
        Node errorNode = getUPnPErrorNode();
        if (errorNode == null) return null;
        return errorNode.getNodeEndsWith(SOAP.ERROR_CODE);
    }

    /**
     * Gets the UPnP error description node.
     *
     * @return the error description node, or null if not found
     */
    private Node getUPnPErrorDescriptionNode() {
        Node errorNode = getUPnPErrorNode();
        if (errorNode == null) return null;
        return errorNode.getNodeEndsWith(SOAP.ERROR_DESCRIPTION);
    }

    /**
     * Gets the UPnP error code from the response.
     *
     * @return the error code, or -1 if not found or invalid
     */
    public int getUPnPErrorCode() {
        Node errorCodeNode = getUPnPErrorCodeNode();
        if (errorCodeNode == null) return -1;
        String errorCodeStr = errorCodeNode.getValue();
        try {
            return Integer.parseInt(errorCodeStr);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Gets the UPnP error description from the response.
     *
     * @return the error description, or empty string if not found
     */
    public String getUPnPErrorDescription() {
        Node errorDescNode = getUPnPErrorDescriptionNode();
        if (errorDescNode == null) return "";
        return errorDescNode.getValue();
    }

    /**
     * Gets the complete UPnP error status from the response.
     *
     * @return a UPnPStatus object containing the error code and description
     */
    public UPnPStatus getUPnPError() {
        int code = 0;
        String desc = "";
        code = getUPnPErrorCode();
        desc = getUPnPErrorDescription();
        upnpErr.setCode(code);
        upnpErr.setDescription(desc);
        return upnpErr;
    }
}
