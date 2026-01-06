/*
 * CyberLink for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.upnp;

import org.cybergarage.xml.Node;

/**
 * Represents an allowed value constraint for UPnP state variables.
 *
 * <p>This class encapsulates allowed value definitions from UPnP service descriptions. It provides
 * XML node management for state variables that have enumerated or restricted value sets defined in
 * their service descriptions.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>XML node wrapping for allowed values
 *   <li>Element name constant for XML parsing
 *   <li>Service description integration
 *   <li>State variable constraint management
 *   <li>UPnP specification compliance
 * </ul>
 *
 * <p>This class is used by UPnP services to manage allowed value constraints for state variables,
 * enabling proper validation and description generation for variables with restricted value sets.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class AllowedValue {
    ////////////////////////////////////////////////
    //  Constants
    ////////////////////////////////////////////////

    /** XML element name for allowed value in UPnP descriptions. */
    public static final String ELEM_NAME = "allowedValue";

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    /** The underlying XML node containing the allowed value data. */
    private Node allowedValueNode;

    public Node getAllowedValueNode() {
        return allowedValueNode;
    }

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public AllowedValue(Node node) {
        allowedValueNode = node;
    }

    /**
     * Create an AllowedValue by the value String, and will create the Node structure by itself
     *
     * @param value The value that will be associate to this object
     */
    public AllowedValue(String value) {

        // TODO Some test are done not stable
        allowedValueNode = new Node(ELEM_NAME); // better (twa)
        setValue(value); // better (twa)
    }

    ////////////////////////////////////////////////
    //	isAllowedValueNode
    ////////////////////////////////////////////////

    /**
     * Checks if the given node is an allowedValue element.
     *
     * @param node the XML node to check
     * @return true if the node is an allowedValue element
     */
    public static boolean isAllowedValueNode(Node node) {
        return ELEM_NAME.equals(node.getName());
    }

    ////////////////////////////////////////////////
    //  Value
    ////////////////////////////////////////////////

    /**
     * Sets the value for this allowed value element.
     *
     * @param value the value string to set
     */
    public void setValue(String value) {
        getAllowedValueNode().setValue(value);
    }

    public String getValue() {
        return getAllowedValueNode().getValue();
    }
}
