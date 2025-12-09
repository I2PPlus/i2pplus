/******************************************************************
 *
 *	CyberUPnP for Java
 *
 *	Copyright (C) Satoshi Konno 2002-2004
 *
 *	File: Action.java
 *
 *	Revision;
 *
 *	12/05/02
 *		- first revision.
 *	08/30/03
 *		- Gordano Sassaroli <sassarol@cefriel.it>
 *		- Problem    : When invoking an action that has at least one out parameter, an error message is returned
 *		- Error      : The action post method gets the entire list of arguments instead of only the in arguments
 *	01/04/04
 *		- Added UPnP status methods.
 *		- Changed about new ActionListener interface.
 *	01/05/04
 *		- Added clearOutputAgumentValues() to initialize the output values before calling performActionListener().
 *	07/09/04
 *		- Thanks for Dimas <cyberrate@users.sourceforge.net> and Stefano Lenzi <kismet-sl@users.sourceforge.net>
 *		- Changed postControlAction() to set the status code to the UPnPStatus.
 *	04/12/06
 *		- Added setUserData() and getUserData() to set a user original data object.
 *
 ******************************************************************/

package org.cybergarage.upnp;

import org.cybergarage.upnp.control.ActionListener;
import org.cybergarage.upnp.control.ActionRequest;
import org.cybergarage.upnp.control.ActionResponse;
import org.cybergarage.upnp.control.ControlResponse;
import org.cybergarage.upnp.xml.ActionData;
import org.cybergarage.util.Debug;
import org.cybergarage.util.Mutex;
import org.cybergarage.xml.Node;

import java.util.Iterator;

/**
 * Represents a UPnP action that can be invoked on a service.
 *
 * <p>This class provides functionality for UPnP action management:
 *
 * <ul>
 *   <li>Action description and argument management
 *   <li>Input and output argument handling
 *   <li>Local and remote action execution
 *   <li>Action listener integration
 *   <li>Control request/response handling
 * </ul>
 *
 * <p>Actions can be invoked locally on the device or remotely by control points through SOAP
 * control messages. Each action has a set of input arguments and may return output arguments upon
 * execution.
 *
 * @author Satoshi Konno
 * @author Stefano Lenzi
 * @version 1.8
 * @since 1.0
 */
public class Action {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    /** The element name for action nodes in XML */
    public static final String ELEM_NAME = "action";

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    /** The service node this action belongs to */
    private Node serviceNode;

    /** The action node containing action definition */
    private final Node actionNode;

    /**
     * Gets the service node this action belongs to.
     *
     * @return the service node
     */
    private Node getServiceNode() {
        return serviceNode;
    }

    /**
     * Gets the service this action belongs to.
     *
     * @return the service object
     */
    public Service getService() {
        return new Service(getServiceNode());
    }

    /**
     * Sets the service this action belongs to.
     *
     * @param s the service to set
     */
    void setService(Service s) {
        serviceNode = s.getServiceNode();
        /*To ensure integrity of the XML structure*/
        Iterator<Argument> i = getArgumentList().iterator();
        while (i.hasNext()) {
            Argument arg = i.next();
            arg.setService(s);
        }
    }

    /**
     * Gets the action node.
     *
     * @return the action node
     */
    public Node getActionNode() {
        return actionNode;
    }

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////
    /**
     * Creates a new Action with a given service node.
     *
     * @param serviceNode service node this action belongs to
     */
    public Action(Node serviceNode) {
        this.serviceNode = serviceNode;
        this.actionNode = new Node(Action.ELEM_NAME);
    }

    /**
     * Creates a new Action with given service and action nodes.
     *
     * @param serviceNode service node this action belongs to
     * @param actionNode action node containing action definition
     */
    public Action(Node serviceNode, Node actionNode) {
        this.serviceNode = serviceNode;
        this.actionNode = actionNode;
    }

    /**
     * Creates a copy of an existing Action.
     *
     * @param action Action to copy
     */
    public Action(Action action) {
        this.serviceNode = action.getServiceNode();
        this.actionNode = action.getActionNode();
    }

    ////////////////////////////////////////////////
    // Mutex
    ////////////////////////////////////////////////

    /** Mutex for thread synchronization */
    private Mutex mutex = new Mutex();

    /** Locks the mutex for this action. */
    public void lock() {
        mutex.lock();
    }

    /** Unlocks the mutex for this action. */
    public void unlock() {
        mutex.unlock();
    }

    ////////////////////////////////////////////////
    //	isActionNode
    ////////////////////////////////////////////////

    /**
     * Checks if the given node is an action node.
     *
     * @param node node to check
     * @return true if the node is an action node, false otherwise
     */
    public static boolean isActionNode(Node node) {
        return Action.ELEM_NAME.equals(node.getName());
    }

    ////////////////////////////////////////////////
    //	name
    ////////////////////////////////////////////////

    /** The name attribute for action nodes */
    private static final String NAME = "name";

    /**
     * Sets name of this action.
     *
     * @param value name to set
     */
    public void setName(String value) {
        getActionNode().setNode(NAME, value);
    }

    /**
     * Gets name of this action.
     *
     * @return action name
     */
    public String getName() {
        return getActionNode().getNodeValue(NAME);
    }

    ////////////////////////////////////////////////
    //	argumentList
    ////////////////////////////////////////////////

    /**
     * Gets list of all arguments for this action.
     *
     * @return argument list
     */
    public ArgumentList getArgumentList() {
        ArgumentList argumentList = new ArgumentList();
        Node argumentListNode = getActionNode().getNode(ArgumentList.ELEM_NAME);
        if (argumentListNode == null) return argumentList;
        int nodeCnt = argumentListNode.getNNodes();
        for (int n = 0; n < nodeCnt; n++) {
            Node node = argumentListNode.getNode(n);
            if (Argument.isArgumentNode(node) == false) continue;
            Argument argument = new Argument(getServiceNode(), node);
            argumentList.add(argument);
        }
        return argumentList;
    }

    /**
     * Sets the argument list for this action.
     *
     * @param al the argument list to set
     */
    public void setArgumentList(ArgumentList al) {
        Node argumentListNode = getActionNode().getNode(ArgumentList.ELEM_NAME);
        if (argumentListNode == null) {
            argumentListNode = new Node(ArgumentList.ELEM_NAME);
            getActionNode().addNode(argumentListNode);
        } else {
            argumentListNode.removeAllNodes();
        }
        Iterator<Argument> i = al.iterator();
        while (i.hasNext()) {
            Argument a = i.next();
            a.setService(getService());
            argumentListNode.addNode(a.getArgumentNode());
        }
    }

    /**
     * Gets the list of input arguments for this action.
     *
     * @return the input argument list
     */
    public ArgumentList getInputArgumentList() {
        ArgumentList allArgList = getArgumentList();
        int allArgCnt = allArgList.size();
        ArgumentList argList = new ArgumentList();
        for (int n = 0; n < allArgCnt; n++) {
            Argument arg = allArgList.getArgument(n);
            if (arg.isInDirection() == false) continue;
            argList.add(arg);
        }
        return argList;
    }

    /**
     * Gets the list of output arguments for this action.
     *
     * @return the output argument list
     */
    public ArgumentList getOutputArgumentList() {
        ArgumentList allArgList = getArgumentList();
        int allArgCnt = allArgList.size();
        ArgumentList argList = new ArgumentList();
        for (int n = 0; n < allArgCnt; n++) {
            Argument arg = allArgList.getArgument(n);
            if (arg.isOutDirection() == false) continue;
            argList.add(arg);
        }
        return argList;
    }

    /**
     * Gets an argument by name.
     *
     * @param name the name of the argument to find
     * @return the argument if found, null otherwise
     */
    public Argument getArgument(String name) {
        ArgumentList argList = getArgumentList();
        int nArgs = argList.size();
        for (int n = 0; n < nArgs; n++) {
            Argument arg = argList.getArgument(n);
            String argName = arg.getName();
            if (argName == null) continue;
            if (name.equals(argName) == true) return arg;
        }
        return null;
    }

    /**
     * @deprecated You should use one of the following methods instead:<br>
     *     - {@link #setInArgumentValues(ArgumentList)} <br>
     *     - {@link #setOutArgumentValues(ArgumentList)}
     * @param argList the argument list to set
     */
    @Deprecated
    public void setArgumentValues(ArgumentList argList) {
        getArgumentList().set(argList);
    }

    /**
     * Sets the values of input arguments.
     *
     * @param argList the argument list containing input values
     * @since 1.8.0
     */
    public void setInArgumentValues(ArgumentList argList) {
        getArgumentList().setReqArgs(argList);
    }

    /**
     * Sets the values of output arguments.
     *
     * @param argList the argument list containing output values
     * @since 1.8.0
     */
    public void setOutArgumentValues(ArgumentList argList) {
        getArgumentList().setResArgs(argList);
    }

    /**
     * Sets the value of an argument by name.
     *
     * @param name the name of the argument
     * @param value the value to set
     */
    public void setArgumentValue(String name, String value) {
        Argument arg = getArgument(name);
        if (arg == null) return;
        arg.setValue(value);
    }

    /**
     * Sets the value of an argument by name using an integer value.
     *
     * @param name the name of the argument
     * @param value the integer value to set
     */
    public void setArgumentValue(String name, int value) {
        setArgumentValue(name, Integer.toString(value));
    }

    /** Clears all output argument values by setting them to empty strings. */
    private void clearOutputAgumentValues() {
        ArgumentList allArgList = getArgumentList();
        int allArgCnt = allArgList.size();
        for (int n = 0; n < allArgCnt; n++) {
            Argument arg = allArgList.getArgument(n);
            if (arg.isOutDirection() == false) continue;
            arg.setValue("");
        }
    }

    /**
     * Gets the value of an argument by name.
     *
     * @param name the name of the argument
     * @return the argument value, or empty string if argument not found
     */
    public String getArgumentValue(String name) {
        Argument arg = getArgument(name);
        if (arg == null) return "";
        return arg.getValue();
    }

    /**
     * Gets the integer value of an argument by name.
     *
     * @param name the name of the argument
     * @return the argument integer value, or 0 if argument not found
     */
    public int getArgumentIntegerValue(String name) {
        Argument arg = getArgument(name);
        if (arg == null) return 0;
        return arg.getIntegerValue();
    }

    ////////////////////////////////////////////////
    //	UserData
    ////////////////////////////////////////////////

    /**
     * Gets the action data associated with this action.
     *
     * @return the action data object
     */
    private ActionData getActionData() {
        Node node = getActionNode();
        ActionData userData = (ActionData) node.getUserData();
        if (userData == null) {
            userData = new ActionData();
            node.setUserData(userData);
            userData.setNode(node);
        }
        return userData;
    }

    ////////////////////////////////////////////////
    //	controlAction
    ////////////////////////////////////////////////

    /**
     * Gets the action listener for this action.
     *
     * @return the action listener
     */
    public ActionListener getActionListener() {
        return getActionData().getActionListener();
    }

    /**
     * Sets the action listener for this action.
     *
     * @param listener the action listener to set
     */
    public void setActionListener(ActionListener listener) {
        getActionData().setActionListener(listener);
    }

    /**
     * Performs the action listener for this action.
     *
     * @param actionReq the action request
     * @return true if the listener was called, false if no listener is set
     */
    public boolean performActionListener(ActionRequest actionReq) {
        ActionListener listener = getActionListener();
        if (listener == null) return false;
        ActionResponse actionRes = new ActionResponse();
        setStatus(UPnPStatus.INVALID_ACTION);
        clearOutputAgumentValues();
        if (listener.actionControlReceived(this) == true) {
            actionRes.setResponse(this);
        } else {
            UPnPStatus upnpStatus = getStatus();
            actionRes.setFaultResponse(upnpStatus.getCode(), upnpStatus.getDescription());
        }
        if (Debug.isOn() == true) actionRes.print();
        actionReq.post(actionRes);
        return true;
    }

    ////////////////////////////////////////////////
    //	ActionControl
    ////////////////////////////////////////////////

    /**
     * Gets the control response for this action.
     *
     * @return the control response
     */
    private ControlResponse getControlResponse() {
        return getActionData().getControlResponse();
    }

    /**
     * Sets the control response for this action.
     *
     * @param res the control response to set
     */
    private void setControlResponse(ControlResponse res) {
        getActionData().setControlResponse(res);
    }

    /**
     * Gets the control status for this action.
     *
     * @return the UPnP status
     */
    public UPnPStatus getControlStatus() {
        return getControlResponse().getUPnPError();
    }

    ////////////////////////////////////////////////
    //	postControlAction
    ////////////////////////////////////////////////

    /**
     * Posts this control action without binding to a specific local address.
     *
     * @return true if successful, false otherwise
     */
    public boolean postControlAction() {
        return postControlAction(null);
    }

    /**
     * Posts this control action, optionally binding to a specific local host address. I2P - bind
     * HTTP socket to specified local host address
     *
     * @param fromHost null to not bind to a particular local address
     * @return true if successful, false otherwise
     * @since 0.9.50
     */
    public boolean postControlAction(String fromHost) {
        // Thanks for Giordano Sassaroli <sassarol@cefriel.it> (08/30/03)
        ArgumentList actionArgList = getArgumentList();
        ArgumentList actionInputArgList = getInputArgumentList();
        ActionRequest ctrlReq = new ActionRequest();
        ctrlReq.setRequest(this, actionInputArgList);
        if (fromHost != null) ctrlReq.setBindHost(fromHost);
        if (Debug.isOn() == true) ctrlReq.print();
        ActionResponse ctrlRes = ctrlReq.post();
        if (Debug.isOn() == true) ctrlRes.print();
        setControlResponse(ctrlRes);
        // Thanks for Dimas <cyberrate@users.sourceforge.net> and Stefano Lenzi
        // <kismet-sl@users.sourceforge.net> (07/09/04)
        int statCode = ctrlRes.getStatusCode();
        setStatus(statCode);
        if (ctrlRes.isSuccessful() == false) return false;
        ArgumentList outArgList = ctrlRes.getResponse();
        try {
            actionArgList.setResArgs(outArgList);
        } catch (IllegalArgumentException ex) {
            setStatus(
                    UPnPStatus.INVALID_ARGS,
                    "Action succesfully delivered but invalid arguments returned.");
            return false;
        }
        return true;
    }

    ////////////////////////////////////////////////
    //	Debug
    ////////////////////////////////////////////////

    /** Prints debug information about this action and its arguments. */
    public void print() {
        Debug.message("Action : " + getName());
        ArgumentList argList = getArgumentList();
        int nArgs = argList.size();
        for (int n = 0; n < nArgs; n++) {
            Argument arg = argList.getArgument(n);
            String name = arg.getName();
            String value = arg.getValue();
            String dir = arg.getDirection();
            Debug.message(" [" + n + "] = " + dir + ", " + name + ", " + value);
        }
    }

    ////////////////////////////////////////////////
    //	UPnPStatus
    ////////////////////////////////////////////////

    /** The UPnP status for this action */
    private UPnPStatus upnpStatus = new UPnPStatus();

    /**
     * Sets the status code and description for this action.
     *
     * @param code the status code
     * @param descr the status description
     */
    public void setStatus(int code, String descr) {
        upnpStatus.setCode(code);
        upnpStatus.setDescription(descr);
    }

    /**
     * Sets the status code for this action using the default description.
     *
     * @param code the status code
     */
    public void setStatus(int code) {
        setStatus(code, UPnPStatus.code2String(code));
    }

    /**
     * Gets the UPnP status for this action.
     *
     * @return the UPnP status
     */
    public UPnPStatus getStatus() {
        return upnpStatus;
    }

    ////////////////////////////////////////////////
    //	userData
    ////////////////////////////////////////////////

    /** User-defined data associated with this action */
    private Object userData = null;

    /**
     * Sets user-defined data for this action.
     *
     * @param data the user data to set
     */
    public void setUserData(Object data) {
        userData = data;
    }

    /**
     * Gets the user-defined data associated with this action.
     *
     * @return the user data
     */
    public Object getUserData() {
        return userData;
    }
}
