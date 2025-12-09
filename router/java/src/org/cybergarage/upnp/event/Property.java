/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 ******************************************************************/

package org.cybergarage.upnp.event;

/**
 * Represents a UPnP event property containing a name-value pair.
 *
 * <p>This class encapsulates a single property change in UPnP event notifications. Each property
 * has a name (corresponding to a state variable) and a value representing the new state of that
 * variable.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Name-value pair storage for state variables
 *   <li>XML representation for event messages
 *   <li>Null-safe value handling
 *   <li>Property name and value management
 * </ul>
 *
 * <p>This class is used in UPnP event notifications to communicate changes in service state
 * variables to subscribed control points, enabling real-time monitoring of device status changes.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class Property {
    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public Property() {}

    ////////////////////////////////////////////////
    //	name
    ////////////////////////////////////////////////

    private String name = "";

    public String getName() {
        return name;
    }

    public void setName(String val) {
        if (val == null) val = "";
        name = val;
    }

    ////////////////////////////////////////////////
    //	value
    ////////////////////////////////////////////////

    private String value = "";

    public String getValue() {
        return value;
    }

    public void setValue(String val) {
        if (val == null) val = "";
        value = val;
    }
}
