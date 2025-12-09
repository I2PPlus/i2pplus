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
 * Represents a UPnP query response. Extends ControlResponse to provide functionality for handling
 * state variable query responses.
 */
public class QueryResponse extends ControlResponse {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /** Default constructor */
    public QueryResponse() {}

    /**
     * Constructs a QueryResponse from a SOAP response.
     *
     * @param soapRes the SOAP response to wrap
     */
    public QueryResponse(SOAPResponse soapRes) {
        super(soapRes);
    }

    ////////////////////////////////////////////////
    //	Query
    ////////////////////////////////////////////////

    /**
     * Gets the return value node from the query response.
     *
     * @return the return value node, or null if not found
     */
    private Node getReturnNode() {
        Node bodyNode = getBodyNode();
        if (bodyNode == null) return null;
        if (bodyNode.hasNodes() == false) return null;
        Node queryResNode = bodyNode.getNode(0);
        if (queryResNode == null) return null;
        if (queryResNode.hasNodes() == false) return null;
        return queryResNode.getNode(0);
    }

    /**
     * Gets the return value from the query response.
     *
     * @return the return value as a string, or empty string if not found
     */
    public String getReturnValue() {
        Node node = getReturnNode();
        if (node == null) return "";
        return node.getValue();
    }

    ////////////////////////////////////////////////
    //	Response
    ////////////////////////////////////////////////

    /**
     * Sets the response using the specified state variable.
     *
     * @param stateVar the state variable containing the value to return
     */
    public void setResponse(StateVariable stateVar) {
        String var = stateVar.getValue();

        setStatusCode(HTTPStatus.OK);

        Node bodyNode = getBodyNode();
        Node resNode = createResponseNode(var);
        bodyNode.addNode(resNode);

        Node envNodee = getEnvelopeNode();
        setContent(envNodee);
    }

    private Node createResponseNode(String var) {
        Node queryResNode = new Node();
        queryResNode.setName(Control.NS, Control.QUERY_STATE_VARIABLE_RESPONSE);
        queryResNode.setNameSpace(Control.NS, Control.XMLNS);

        Node returnNode = new Node();
        returnNode.setName(Control.RETURN);
        returnNode.setValue(var);
        queryResNode.addNode(returnNode);

        return queryResNode;
    }
}
