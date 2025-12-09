/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 ******************************************************************/

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
    public StateVariableData() {}

    ////////////////////////////////////////////////
    // value
    ////////////////////////////////////////////////

    private String value = "";

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    ////////////////////////////////////////////////
    // QueryListener
    ////////////////////////////////////////////////

    private QueryListener queryListener = null;

    public QueryListener getQueryListener() {
        return queryListener;
    }

    public void setQueryListener(QueryListener queryListener) {
        this.queryListener = queryListener;
    }

    ////////////////////////////////////////////////
    // QueryResponse
    ////////////////////////////////////////////////

    private QueryResponse queryRes = null;

    public QueryResponse getQueryResponse() {
        return queryRes;
    }

    public void setQueryResponse(QueryResponse res) {
        queryRes = res;
    }
}
