/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp;

import java.util.Iterator;
import org.cybergarage.upnp.control.QueryListener;
import org.cybergarage.upnp.control.QueryRequest;
import org.cybergarage.upnp.control.QueryResponse;
import org.cybergarage.upnp.xml.NodeData;
import org.cybergarage.upnp.xml.StateVariableData;
import org.cybergarage.util.Debug;
import org.cybergarage.xml.Node;

/**
 * StateVariable.
 */
public class StateVariable extends NodeData {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    /**
     * ELEM_NAME.
     */
    public static final String ELEM_NAME = "stateVariable";

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    private Node stateVariableNode;
    private Node serviceNode;

    /**
     * Gets the service node this state variable belongs to.
     *
     * @return service node
     */
    public Node getServiceNode() {
        return serviceNode;
    }

    /**
     * Sets service node this state variable belongs to.
     *
     * @param n service node
     */
    void setServiceNode(Node n) {
        serviceNode = n;
    }

    /**
     * Gets the service that owns this state variable.
     *
     * @return the owning service, or null if no service node is set
     */
    public Service getService() {
        Node serviceNode = getServiceNode();
        if (serviceNode == null) return null;
        return new Service(serviceNode);
    }

    /**
     * Gets the state variable node containing variable definition.
     *
     * @return state variable node
     */
    public Node getStateVariableNode() {
        return stateVariableNode;
    }

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /** Creates a new StateVariable with default initialization. */
    public StateVariable() {
        this.serviceNode = null;
        this.stateVariableNode = new Node(ELEM_NAME);
    }

    /**
     * Creates a StateVariable with specified service and state variable nodes.
     *
     * @param serviceNode service node this variable belongs to
     * @param stateVarNode state variable node containing definition
     */
    public StateVariable(Node serviceNode, Node stateVarNode) {
        this.serviceNode = serviceNode;
        this.stateVariableNode = stateVarNode;
    }

    ////////////////////////////////////////////////
    //	isStateVariableNode
    ////////////////////////////////////////////////

    /**
     * Tests whether a node names a state variable element.
     *
     * @param node the XML node to test against the stateVariable element name
     * @return true if the node is a stateVariable element, false otherwise
     */
    public static boolean isStateVariableNode(Node node) {
        return StateVariable.ELEM_NAME.equals(node.getName());
    }

    ////////////////////////////////////////////////
    //	name
    ////////////////////////////////////////////////

    private static final String NAME = "name";

    /**
     * Sets name of this state variable.
     *
     * @param value name to set
     */
    public void setName(String value) {
        getStateVariableNode().setNode(NAME, value);
    }

    /**
     * Gets the name of this state variable.
     *
     * @return state variable name
     */
    public String getName() {
        return getStateVariableNode().getNodeValue(NAME);
    }

    ////////////////////////////////////////////////
    //	dataType
    ////////////////////////////////////////////////

    private static final String DATATYPE = "dataType";

    /**
     * Sets the dataType of this state variable.
     *
     * @param value the UPnP data type name, for example "string" or "i4"
     */
    public void setDataType(String value) {
        getStateVariableNode().setNode(DATATYPE, value);
    }

    /**
     * Gets the dataType of this state variable.
     *
     * @return the UPnP data type name, or null if none is set
     */
    public String getDataType() {
        return getStateVariableNode().getNodeValue(DATATYPE);
    }

    ////////////////////////////////////////////////
    // dataType
    ////////////////////////////////////////////////

    private static final String SENDEVENTS = "sendEvents";
    private static final String SENDEVENTS_YES = "yes";
    private static final String SENDEVENTS_NO = "no";

    /**
     * Sets the sendEvents attribute of this state variable.
     *
     * @param state true to notify subscribers when the value changes, false to
     *     suppress notification
     */
    public void setSendEvents(boolean state) {
        getStateVariableNode()
                .setAttribute(SENDEVENTS, (state == true) ? SENDEVENTS_YES : SENDEVENTS_NO);
    }

    /**
     * Reports whether this state variable notifies subscribers on change.
     *
     * @return true if the sendEvents attribute reads "yes", false if it reads
     *     "no" or is absent
     */
    public boolean isSendEvents() {
        String state = getStateVariableNode().getAttributeValue(SENDEVENTS);
        if (state == null) return false;
        if (state.equalsIgnoreCase(SENDEVENTS_YES) == true) return true;
        return false;
    }

    ////////////////////////////////////////////////
    // set
    ////////////////////////////////////////////////

    /**
     * Copies the name, value, dataType and sendEvents flag from another state
     * variable into this one.
     *
     * @param stateVar the state variable to copy the definition from
     */
    public void set(StateVariable stateVar) {
        setName(stateVar.getName());
        setValue(stateVar.getValue());
        setDataType(stateVar.getDataType());
        setSendEvents(stateVar.isSendEvents());
    }

    ////////////////////////////////////////////////
    //	UserData
    ////////////////////////////////////////////////

    /**
     * Gets the user data attached to the state variable node, creating it on
     * first use.
     *
     * @return the StateVariableData attached to the node, never null
     */
    public StateVariableData getStateVariableData() {
        Node node = getStateVariableNode();
        StateVariableData userData = (StateVariableData) node.getUserData();
        if (userData == null) {
            userData = new StateVariableData();
            node.setUserData(userData);
            userData.setNode(node);
        }
        return userData;
    }

    ////////////////////////////////////////////////
    //	Value
    ////////////////////////////////////////////////

    /**
     * Sets the value of this state variable and, when the variable has an
     * owning service and event notification is enabled, notifies subscribers.
     *
     * @param value the new value, or null to clear it
     */
    public void setValue(String value) {
        // Thnaks for Tho Beisch (11/09/04)
        String currValue = getStateVariableData().getValue();
        // Thnaks for Tho Rick Keiner (11/18/04)
        if (currValue != null && currValue.equals(value) == true) return;

        getStateVariableData().setValue(value);

        // notify event
        Service service = getService();
        if (service == null) return;
        if (isSendEvents() == false) return;
        service.notify(this);
    }

    /**
     * Sets the value of this state variable from its decimal string form.
     *
     * @param value the value to convert with Integer.toString()
     */
    public void setValue(int value) {
        setValue(Integer.toString(value));
    }

    /**
     * Sets the value of this state variable from its decimal string form.
     *
     * @param value the value to convert with Long.toString()
     */
    public void setValue(long value) {
        setValue(Long.toString(value));
    }

    /**
     * Gets the current value of this state variable.
     *
     * @return the value stored in the attached user data, or null if none is set
     */
    public String getValue() {
        return getStateVariableData().getValue();
    }

    ////////////////////////////////////////////////
    //	AllowedValueList
    ////////////////////////////////////////////////

    /**
     * Gets the allowed value list of this state variable, wrapped from the
     * allowedValueList child node.
     *
     * @return the allowed values, or null if no allowedValueList node is present
     */
    public AllowedValueList getAllowedValueList() {
        AllowedValueList valueList = new AllowedValueList();
        Node valueListNode = getStateVariableNode().getNode(AllowedValueList.ELEM_NAME);
        if (valueListNode == null) return null;
        int nNode = valueListNode.getNNodes();
        for (int n = 0; n < nNode; n++) {
            Node node = valueListNode.getNode(n);
            if (AllowedValue.isAllowedValueNode(node) == false) continue;
            AllowedValue allowedVal = new AllowedValue(node);
            valueList.add(allowedVal);
        }
        return valueList;
    }

    /**
     * This method ovverride the value of the AllowedValueList Node<br>
     * of this object. <br>
     * <br>
     * Note: This method should be used to create a dynamic<br>
     * Device withtout writing any XML that describe the device<br>
     * . <br>
     * Note2: The enforce the constraint of the SCPD rule the<br>
     * AllowedValueList and AllowedValueRange are mutal exclusive<br>
     * the last set will be the only present<br>
     *
     * @param avl The new AllowedValueList
     */
    public void setAllowedValueList(AllowedValueList avl) {
        // TODO Some test done not stable
        getStateVariableNode().removeNode(AllowedValueList.ELEM_NAME);
        getStateVariableNode().removeNode(AllowedValueRange.ELEM_NAME);
        Node n = new Node(AllowedValueList.ELEM_NAME);
        Iterator<AllowedValue> i = avl.iterator();
        while (i.hasNext()) {
            AllowedValue av = i.next();
            // n.addNode(new Node(AllowedValue.ELEM_NAME,av.getValue())); wrong!
            n.addNode(av.getAllowedValueNode()); // better (twa)
        }
        getStateVariableNode().addNode(n);
    }

    /**
     * Reports whether this state variable carries an allowedValueList node.
     *
     * @return true if an allowedValueList is present, false otherwise
     */
    public boolean hasAllowedValueList() {
        AllowedValueList valueList = getAllowedValueList();
        return (valueList != null) ? true : false;
    }

    ////////////////////////////////////////////////
    //	AllowedValueRange
    ////////////////////////////////////////////////

    /**
     * Gets the allowed value range of this state variable, wrapped from the
     * allowedValueRange child node.
     *
     * @return the allowed range, or null if no allowedValueRange node is present
     */
    public AllowedValueRange getAllowedValueRange() {
        Node valueRangeNode = getStateVariableNode().getNode(AllowedValueRange.ELEM_NAME);
        if (valueRangeNode == null) return null;
        return new AllowedValueRange(valueRangeNode);
    }

    /**
     * This method ovverride the value of the AllowedValueRange Node<br>
     * of this object. <br>
     * <br>
     * Note: This method should be used to create a dynamic<br>
     * Device withtout writing any XML that describe the device<br>
     * . <br>
     * Note2: The enforce the constraint of the SCPD rule the<br>
     * AllowedValueList and AllowedValueRange are mutal exclusive<br>
     * the last set will be the only present<br>
     *
     * @param avr The new AllowedValueRange
     */
    public void setAllowedValueRange(AllowedValueRange avr) {
        // TODO Some test done not stable
        getStateVariableNode().removeNode(AllowedValueList.ELEM_NAME);
        getStateVariableNode().removeNode(AllowedValueRange.ELEM_NAME);
        getStateVariableNode().addNode(avr.getAllowedValueRangeNode());
    }

    /**
     * Reports whether this state variable carries an allowedValueRange node.
     *
     * @return true if an allowedValueRange is present, false otherwise
     */
    public boolean hasAllowedValueRange() {
        return (getAllowedValueRange() != null) ? true : false;
    }

    ////////////////////////////////////////////////
    //	queryAction
    ////////////////////////////////////////////////

    /**
     * Gets the listener notified when a query for this state variable arrives.
     *
     * @return the registered query listener, or null if none is set
     */
    public QueryListener getQueryListener() {
        return getStateVariableData().getQueryListener();
    }

    /**
     * Sets the listener notified when a query for this state variable arrives.
     *
     * @param listener the listener to invoke, or null to clear it
     */
    public void setQueryListener(QueryListener listener) {
        getStateVariableData().setQueryListener(listener);
    }

    /**
     * Runs the query listener against a copy of this variable and posts the
     * listener's outcome back to the requesting peer.
     *
     * @param queryReq the request the response is posted to
     * @return true if a listener was registered, false if none was
     */
    public boolean performQueryListener(QueryRequest queryReq) {
        QueryListener listener = getQueryListener();
        if (listener == null) return false;
        QueryResponse queryRes = new QueryResponse();
        StateVariable retVar = new StateVariable();
        retVar.set(this);
        retVar.setValue("");
        retVar.setStatus(UPnPStatus.INVALID_VAR);
        if (listener.queryControlReceived(retVar) == true) {
            queryRes.setResponse(retVar);
        } else {
            UPnPStatus upnpStatus = retVar.getStatus();
            queryRes.setFaultResponse(upnpStatus.getCode(), upnpStatus.getDescription());
        }
        queryReq.post(queryRes);
        return true;
    }

    ////////////////////////////////////////////////
    //	ActionControl
    ////////////////////////////////////////////////

    /**
     * Gets the response from the most recent query posted for this variable.
     *
     * @return the last query response, or null if no query has been made
     */
    public QueryResponse getQueryResponse() {
        return getStateVariableData().getQueryResponse();
    }

    private void setQueryResponse(QueryResponse res) {
        getStateVariableData().setQueryResponse(res);
    }

    /**
     * Gets the UPnP error carried by the most recent query response.
     *
     * @return the status of the last query response
     */
    public UPnPStatus getQueryStatus() {
        return getQueryResponse().getUPnPError();
    }

    ////////////////////////////////////////////////
    //	ActionControl
    ////////////////////////////////////////////////

    /**
     * Posts a query for this state variable and stores the reply as both the
     * query response and the variable's value.
     *
     * @return true if the query succeeded, false if it returned a UPnP error
     */
    public boolean postQuerylAction() {
        QueryRequest queryReq = new QueryRequest();
        queryReq.setRequest(this);
        if (Debug.isOn() == true) queryReq.print();
        QueryResponse queryRes = queryReq.post();
        if (Debug.isOn() == true) queryRes.print();
        setQueryResponse(queryRes);
        // Thanks for Dimas <cyberrate@users.sourceforge.net> and Stefano Lenzi
        // <kismet-sl@users.sourceforge.net> (07/09/04)
        if (queryRes.isSuccessful() == false) {
            setValue(queryRes.getReturnValue());
            return false;
        }
        setValue(queryRes.getReturnValue());
        return true;
    }

    ////////////////////////////////////////////////
    //	UPnPStatus
    ////////////////////////////////////////////////

    private UPnPStatus upnpStatus = new UPnPStatus();

    /**
     * Sets the UPnP status reported alongside this state variable.
     *
     * @param code the UPnP error code, for example UPnPStatus.INVALID_VAR (404)
     * @param descr the human readable text to report with the code
     */
    public void setStatus(int code, String descr) {
        upnpStatus.setCode(code);
        upnpStatus.setDescription(descr);
    }

    /**
     * Sets the UPnP status code, deriving the description text from the code.
     *
     * @param code the UPnP error code, for example UPnPStatus.INVALID_VAR (404)
     */
    public void setStatus(int code) {
        setStatus(code, UPnPStatus.code2String(code));
    }

    /**
     * Gets the UPnP status currently held for this state variable.
     *
     * @return the status, which is never null but starts out unset
     */
    public UPnPStatus getStatus() {
        return upnpStatus;
    }

    private static final String DEFAULT_VALUE = "defaultValue";

    ////////////////////////////////////////////////
    /**
     * Gets the defaultValue of this StateVariable.
     *
     * @return the declared default, or null if none is set
     */
    public String getDefaultValue() {
        return getStateVariableNode().getNodeValue(DEFAULT_VALUE);
    }

    /**
     * This method ovverride the value of the DefaultValue of this object. <br>
     * <br>
     * Note: This method should be used to create a dynamic<br>
     * Device withtout writing any XML that describe the device<br>
     * .
     *
     * @param value The new String value
     */
    public void setDefaultValue(String value) {
        getStateVariableNode().setNode(DEFAULT_VALUE, value);
    }

    ////////////////////////////////////////////////
    //	userData
    ////////////////////////////////////////////////

    private Object userData = null;

    /**
     * Sets the object this variable keeps as application private data,
     * separate from the UPnP value and query response.
     *
     * @param data the object to retain, or null to clear it
     */
    public void setUserData(Object data) {
        userData = data;
    }

    /**
     * Gets the application private data retained by this variable.
     *
     * @return the retained object, or null if none has been set
     */
    public Object getUserData() {
        return userData;
    }
}
