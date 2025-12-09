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
 * Represents a UPnP action control request. Extends ControlRequest to provide action-specific
 * functionality.
 */
public class ActionRequest extends ControlRequest {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /** Default constructor */
    public ActionRequest() {}

    /**
     * Constructs an ActionRequest from an HTTP request.
     *
     * @param httpReq the HTTP request to wrap
     */
    public ActionRequest(HTTPRequest httpReq) {
        set(httpReq);
    }

    ////////////////////////////////////////////////
    //	Action
    ////////////////////////////////////////////////

    /**
     * Gets the action node from the request.
     *
     * @return the action node
     */
    public Node getActionNode() {
        Node bodyNode = getBodyNode();
        if (bodyNode == null) return null;
        if (bodyNode.hasNodes() == false) return null;
        return bodyNode.getNode(0);
    }

    /**
     * Gets the action name from the request.
     *
     * @return the action name
     */
    public String getActionName() {
        Node node = getActionNode();
        if (node == null) return "";
        String name = node.getName();
        if (name == null) return "";
        int idx = name.indexOf(SOAP.DELIM) + 1;
        if (idx < 0) return "";
        return name.substring(idx, name.length());
    }

    /**
     * Gets the argument list from the request.
     *
     * @return the argument list
     */
    public ArgumentList getArgumentList() {
        Node actNode = getActionNode();
        int nArgNodes = actNode.getNNodes();
        ArgumentList argList = new ArgumentList();
        for (int n = 0; n < nArgNodes; n++) {
            Argument arg = new Argument();
            Node argNode = actNode.getNode(n);
            arg.setName(argNode.getName());
            arg.setValue(argNode.getValue());
            argList.add(arg);
        }
        return argList;
    }

    ////////////////////////////////////////////////
    //	setRequest
    ////////////////////////////////////////////////

    /**
     * Sets the request with the specified action and argument list.
     *
     * @param action the action to set
     * @param argList the argument list
     */
    public void setRequest(Action action, ArgumentList argList) {
        Service service = action.getService();

        setRequestHost(service);

        setEnvelopeNode(SOAP.createEnvelopeBodyNode());
        Node envNode = getEnvelopeNode();
        Node bodyNode = getBodyNode();
        Node argNode = createContentNode(service, action, argList);
        bodyNode.addNode(argNode);
        setContent(envNode);

        String serviceType = service.getServiceType();
        String actionName = action.getName();
        String soapAction = "\"" + serviceType + "#" + actionName + "\"";
        setSOAPAction(soapAction);
    }

    ////////////////////////////////////////////////
    //	Contents
    ////////////////////////////////////////////////

    /**
     * @param service the service
     * @param action the action
     * @param argList the argument list
     * @return the created content node
     */
    private Node createContentNode(Service service, Action action, ArgumentList argList) {
        String actionName = action.getName();
        String serviceType = service.getServiceType();

        Node actionNode = new Node();
        actionNode.setName(Control.NS, actionName);
        actionNode.setNameSpace(Control.NS, serviceType);

        int argListCnt = argList.size();
        for (int n = 0; n < argListCnt; n++) {
            Argument arg = argList.getArgument(n);
            Node argNode = new Node();
            argNode.setName(arg.getName());
            argNode.setValue(arg.getValue());
            actionNode.addNode(argNode);
        }

        return actionNode;
    }

    ////////////////////////////////////////////////
    //	post
    ////////////////////////////////////////////////

    /**
     * Posts the action request and gets the response.
     *
     * @return the action response
     */
    public ActionResponse post() {
        SOAPResponse soapRes = postMessage(getRequestHost(), getRequestPort());
        return new ActionResponse(soapRes);
    }
}
