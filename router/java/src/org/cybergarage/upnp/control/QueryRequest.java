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
 * Represents a UPnP query request for state variables. Extends ControlRequest to provide
 * functionality for querying the value of state variables.
 */
public class QueryRequest extends ControlRequest {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /** Default constructor */
    public QueryRequest() {}

    /**
     * Constructs a QueryRequest from an HTTP request.
     *
     * @param httpReq the HTTP request to wrap
     */
    public QueryRequest(HTTPRequest httpReq) {
        set(httpReq);
    }

    ////////////////////////////////////////////////
    //	Query
    ////////////////////////////////////////////////

    /**
     * Gets the variable name node from the query request.
     *
     * @return the variable name node, or null if not found
     */
    private Node getVarNameNode() {
        Node bodyNode = getBodyNode();
        if (bodyNode == null) return null;
        if (bodyNode.hasNodes() == false) return null;
        Node queryStateVarNode = bodyNode.getNode(0);
        if (queryStateVarNode == null) return null;
        if (queryStateVarNode.hasNodes() == false) return null;
        return queryStateVarNode.getNode(0);
    }

    /**
     * Gets the name of the state variable being queried.
     *
     * @return the variable name, or empty string if not found
     */
    public String getVarName() {
        Node node = getVarNameNode();
        if (node == null) return "";
        return node.getValue();
    }

    ////////////////////////////////////////////////
    //	setRequest
    ////////////////////////////////////////////////

    /**
     * Sets up the query request for the specified state variable. Configures the host, SOAP
     * envelope, and action for the query.
     *
     * @param stateVar the state variable to query
     */
    public void setRequest(StateVariable stateVar) {
        Service service = stateVar.getService();

        // String ctrlURL = service.getControlURL();

        setRequestHost(service);

        setEnvelopeNode(SOAP.createEnvelopeBodyNode());
        Node envNode = getEnvelopeNode();
        Node bodyNode = getBodyNode();
        Node qeuryNode = createContentNode(stateVar);
        bodyNode.addNode(qeuryNode);
        setContent(envNode);

        setSOAPAction(Control.QUERY_SOAPACTION);
    }

    ////////////////////////////////////////////////
    //	Contents
    ////////////////////////////////////////////////

    private Node createContentNode(StateVariable stateVar) {
        Node queryVarNode = new Node();
        queryVarNode.setName(Control.NS, Control.QUERY_STATE_VARIABLE);
        queryVarNode.setNameSpace(Control.NS, Control.XMLNS);

        Node varNode = new Node();
        varNode.setName(Control.NS, Control.VAR_NAME);
        varNode.setValue(stateVar.getName());
        queryVarNode.addNode(varNode);

        return queryVarNode;
    }

    ////////////////////////////////////////////////
    //	post
    ////////////////////////////////////////////////

    /**
     * Posts the query request and returns the response.
     *
     * @return the query response
     */
    public QueryResponse post() {
        SOAPResponse soapRes = postMessage(getRequestHost(), getRequestPort());
        return new QueryResponse(soapRes);
    }
}
