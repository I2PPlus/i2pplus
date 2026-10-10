/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.xml;

import org.cybergarage.upnp.control.*;

/**
 * Data container for UPnP state variable information.
 *
 * <p>This class extends NodeData to represent state variable definitions from UPnP service
 * descriptions. It encapsulates metadata about state variables including their current values, data
 * types, and query listeners.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>State variable value storage
 *   <li>Query listener management
 *   <li>XML node data inheritance
 *   <li>Service description integration
 *   <li>Variable metadata handling
 * </ul>
 *
 * <p>This class is used by UPnP services to manage state variable definitions and their current
 * values, enabling proper service description generation and query response handling.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class StateVariableData extends NodeData {
    /**
     * StateVariableData.
     */
    public StateVariableData() {}

    ////////////////////////////////////////////////
    // value
    ////////////////////////////////////////////////

    private String value = "";

    /**
     * getValue.
     *
     * @return the current value, empty until a query response supplies one
     */
    public String getValue() {
        return value;
    }

    /**
     * setValue.
     *
     * @param value the value to hold for this variable
     */
    public void setValue(String value) {
        this.value = value;
    }

    ////////////////////////////////////////////////
    // QueryListener
    ////////////////////////////////////////////////

    private QueryListener queryListener = null;

    /**
     * getQueryListener.
     *
     * @return the listener notified when this variable is queried, or null if
     * none is registered
     */
    public QueryListener getQueryListener() {
        return queryListener;
    }

    /**
     * setQueryListener.
     *
     * @param queryListener the listener to notify on each query
     */
    public void setQueryListener(QueryListener queryListener) {
        this.queryListener = queryListener;
    }

    ////////////////////////////////////////////////
    // QueryResponse
    ////////////////////////////////////////////////

    private QueryResponse queryRes = null;

    /**
     * getQueryResponse.
     *
     * @return the response from the last query, or null if never queried
     */
    public QueryResponse getQueryResponse() {
        return queryRes;
    }

    /**
     * setQueryResponse.
     *
     * @param res the response to retain for this variable
     */
    public void setQueryResponse(QueryResponse res) {
        queryRes = res;
    }
}
