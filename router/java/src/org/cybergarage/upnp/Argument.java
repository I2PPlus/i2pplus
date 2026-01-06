/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp;

import org.cybergarage.upnp.xml.ArgumentData;
import org.cybergarage.xml.Node;

public class Argument {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    /** Element name for argument */
    public static final String ELEM_NAME = "argument";

    /** Direction constant for input arguments */
    public static final String IN = "in";

    /** Direction constant for output arguments */
    public static final String OUT = "out";

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    private Node argumentNode;
    private Node serviceNode;

    /**
     * Gets the argument node containing argument definition.
     *
     * @return argument node
     */
    public Node getArgumentNode() {
        return argumentNode;
    }

    /**
     * Gets the service node this argument belongs to.
     *
     * @return service node
     */
    private Node getServiceNode() {
        return serviceNode;
    }

    /**
     * Gets the service this argument belongs to.
     *
     * @return service object
     */
    public Service getService() {
        return new Service(getServiceNode());
    }

    /**
     * Sets the service this argument belongs to.
     *
     * @param s service to set
     */
    void setService(Service s) {
        s.getServiceNode();
    }

    /**
     * Gets the action node this argument belongs to.
     *
     * @return action node, or null if not found
     */
    public Node getActionNode() {
        Node argumentLinstNode = getArgumentNode().getParentNode();
        if (argumentLinstNode == null) return null;
        Node actionNode = argumentLinstNode.getParentNode();
        if (actionNode == null) return null;
        if (Action.isActionNode(actionNode) == false) return null;
        return actionNode;
    }

    /**
     * Gets the action this argument belongs to.
     *
     * @return action object
     */
    public Action getAction() {
        return new Action(getServiceNode(), getActionNode());
    }

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /**
     * Creates a new Argument with no associated service.
     */
    public Argument() {
        argumentNode = new Node(ELEM_NAME);
        serviceNode = null;
    }

    /**
     * Creates a new Argument associated with the specified service node.
     *
     * @param servNode service node to associate
     */
    public Argument(Node servNode) {
        argumentNode = new Node(ELEM_NAME);
        serviceNode = servNode;
    }

    /**
     * Creates a new Argument associated with the specified service node and argument node.
     *
     * @param servNode service node to associate
     * @param argNode argument node containing definition
     */
    public Argument(Node servNode, Node argNode) {
        serviceNode = servNode;
        argumentNode = argNode;
    }

    /**
     * Creates a new Argument with the specified name and value.
     *
     * @param name argument name
     * @param value argument value
     */
    public Argument(String name, String value) {
        this();
        setName(name);
        setValue(value);
    }

    ////////////////////////////////////////////////
    //	isArgumentNode
    ////////////////////////////////////////////////

    /**
     * Checks if the specified node is an argument node.
     *
     * @param node node to check
     * @return true if the node is an argument node, false otherwise
     */
    public static boolean isArgumentNode(Node node) {
        return Argument.ELEM_NAME.equals(node.getName());
    }

    ////////////////////////////////////////////////
    //	name
    ////////////////////////////////////////////////

    private static final String NAME = "name";

    /**
     * Sets the name of this argument.
     *
     * @param value name to set
     */
    public void setName(String value) {
        getArgumentNode().setNode(NAME, value);
    }

    /**
     * Gets the name of this argument.
     *
     * @return argument name
     */
    public String getName() {
        return getArgumentNode().getNodeValue(NAME);
    }

    ////////////////////////////////////////////////
    //	direction
    ////////////////////////////////////////////////

    private static final String DIRECTION = "direction";

    /**
     * Sets the direction of this argument (in or out).
     *
     * @param value direction to set
     */
    public void setDirection(String value) {
        getArgumentNode().setNode(DIRECTION, value);
    }

    /**
     * Gets the direction of this argument.
     *
     * @return direction string ("in" or "out")
     */
    public String getDirection() {
        return getArgumentNode().getNodeValue(DIRECTION);
    }

    /**
     * Checks if this argument is an input argument.
     *
     * @return true if direction is "in", false otherwise
     */
    public boolean isInDirection() {
        String dir = getDirection();
        if (dir == null) return false;
        return dir.equalsIgnoreCase(IN);
    }

    /**
     * Checks if this argument is an output argument.
     *
     * @return true if direction is "out", false otherwise
     */
    public boolean isOutDirection() {
        return !isInDirection();
    }

    ////////////////////////////////////////////////
    //	relatedStateVariable
    ////////////////////////////////////////////////

    private static final String RELATED_STATE_VARIABLE = "relatedStateVariable";

    /**
     * Sets the name of the related state variable for this argument.
     *
     * @param value state variable name to set
     */
    public void setRelatedStateVariableName(String value) {
        getArgumentNode().setNode(RELATED_STATE_VARIABLE, value);
    }

    /**
     * Gets the name of the related state variable for this argument.
     *
     * @return related state variable name
     */
    public String getRelatedStateVariableName() {
        return getArgumentNode().getNodeValue(RELATED_STATE_VARIABLE);
    }

    /**
     * Gets the related state variable for this argument.
     *
     * @return state variable object, or null if not found
     */
    public StateVariable getRelatedStateVariable() {
        Service service = getService();
        if (service == null) return null;
        String relatedStatVarName = getRelatedStateVariableName();
        return service.getStateVariable(relatedStatVarName);
    }

    ////////////////////////////////////////////////
    //	UserData
    ////////////////////////////////////////////////

    /**
     * Gets the argument data associated with this argument.
     *
     * @return argument data object
     */
    private ArgumentData getArgumentData() {
        Node node = getArgumentNode();
        ArgumentData userData = (ArgumentData) node.getUserData();
        if (userData == null) {
            userData = new ArgumentData();
            node.setUserData(userData);
            userData.setNode(node);
        }
        return userData;
    }

    ////////////////////////////////////////////////
    //	value
    ////////////////////////////////////////////////

    /**
     * Sets the string value of this argument.
     *
     * @param value value to set
     */
    public void setValue(String value) {
        getArgumentData().setValue(value);
    }

    /**
     * Sets the integer value of this argument.
     *
     * @param value integer value to set
     */
    public void setValue(int value) {
        setValue(Integer.toString(value));
    }

    /**
     * Gets the string value of this argument.
     *
     * @return argument value as string
     */
    public String getValue() {
        return getArgumentData().getValue();
    }

    /**
     * Gets the integer value of this argument.
     *
     * @return argument value as integer, or 0 if parsing fails
     */
    public int getIntegerValue() {
        String value = getValue();
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
        }
        return 0;
    }

    ////////////////////////////////////////////////
    //	userData
    ////////////////////////////////////////////////

    /** User-defined data associated with this argument */
    private Object userData = null;

    /**
     * Sets user-defined data for this argument.
     *
     * @param data data to set
     */
    public void setUserData(Object data) {
        userData = data;
    }

    /**
     * Gets the user-defined data associated with this argument.
     *
     * @return user data object
     */
    public Object getUserData() {
        return userData;
    }
}
