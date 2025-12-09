/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.control;

/**
 * Constants and utility values for UPnP control operations. Contains XML namespace definitions,
 * SOAP action strings, and element names used in UPnP control messages.
 */
public class Control {
    /** Default namespace prefix for UPnP control */
    public static final String NS = "u";

    /** SOAP action for querying state variables */
    public static final String QUERY_SOAPACTION =
            "urn:schemas-upnp-org:control-1-0#QueryStateVariable";

    /** XML namespace for UPnP control */
    public static final String XMLNS = "urn:schemas-upnp-org:control-1-0";

    /** Element name for state variable query requests */
    public static final String QUERY_STATE_VARIABLE = "QueryStateVariable";

    /** Element name for state variable query responses */
    public static final String QUERY_STATE_VARIABLE_RESPONSE = "QueryStateVariableResponse";

    /** Element name for variable name in queries */
    public static final String VAR_NAME = "varName";

    /** Element name for return value in responses */
    public static final String RETURN = "return";
}
