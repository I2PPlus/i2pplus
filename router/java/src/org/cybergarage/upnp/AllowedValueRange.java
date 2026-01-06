/*
 * CyberLink for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.upnp;

import org.cybergarage.xml.Node;

/**
 * Represents an allowed value range constraint for UPnP state variables.
 *
 * <p>This class encapsulates allowed value range definitions from UPnP service descriptions. It
 * provides XML node management for state variables that have numeric ranges defined as allowed
 * value constraints.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>XML node wrapping for value ranges
 *   <li>Element name constant for XML parsing
 *   <li>Service description integration
 *   <li>State variable constraint management
 *   <li>UPnP specification compliance
 * </ul>
 *
 * <p>This class is used by UPnP services to manage allowed value range constraints for state
 * variables, enabling proper validation and description generation for numeric variables with
 * restricted ranges.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class AllowedValueRange {
    ////////////////////////////////////////////////
    //  Constants
    ////////////////////////////////////////////////

    /** XML element name for allowed value range in UPnP descriptions. */
    public static final String ELEM_NAME = "allowedValueRange";

    ////////////////////////////////////////////////
    //  Member
    ////////////////////////////////////////////////

    /** The underlying XML node containing the allowed value range data. */
    private Node allowedValueRangeNode;

    public Node getAllowedValueRangeNode() {
        return allowedValueRangeNode;
    }

    ////////////////////////////////////////////////
    //  Constructor
    ////////////////////////////////////////////////

    /**
     * Creates an AllowedValueRange from an XML node.
     *
     * @param node the XML node containing the allowed value range data
     */
    public AllowedValueRange(Node node) {
        allowedValueRangeNode = node;
    }

    /**
     * Creates an empty AllowedValueRange with a new node structure.
     */
    public AllowedValueRange() {
        // TODO Test
        allowedValueRangeNode = new Node(ELEM_NAME);
    }

    ////////////////////////////////////////////////
    //  isAllowedValueRangeNode
    ////////////////////////////////////////////////

    /**
     * Checks if the given node is an allowedValueRange element.
     *
     * @param node the XML node to check
     * @return true if the node is an allowedValueRange element
     */
    public static boolean isAllowedValueRangeNode(Node node) {
        return ELEM_NAME.equals(node.getName());
    }

    ////////////////////////////////////////////////
    //  minimum
    ////////////////////////////////////////////////

    /** XML node name for minimum value. */
    private static final String MINIMUM = "minimum";

    /**
     * Sets the minimum value for this allowed value range.
     *
     * @param value the minimum value string to set
     */
    public void setMinimum(String value) {
        getAllowedValueRangeNode().setNode(MINIMUM, value);
    }

    /**
     * Gets the minimum value for this allowed value range.
     *
     * @return the minimum value as a string
     */
    public String getMinimum() {
        return getAllowedValueRangeNode().getNodeValue(MINIMUM);
    }

    ////////////////////////////////////////////////
    //  maximum
    ////////////////////////////////////////////////

    /** XML node name for maximum value. */
    private static final String MAXIMUM = "maximum";

    /**
     * Sets the maximum value for this allowed value range.
     *
     * @param value the maximum value string to set
     */
    public void setMaximum(String value) {
        getAllowedValueRangeNode().setNode(MAXIMUM, value);
    }

    /**
     * Gets the maximum value for this allowed value range.
     *
     * @return the maximum value as a string
     */
    public String getMaximum() {
        return getAllowedValueRangeNode().getNodeValue(MAXIMUM);
    }

    ////////////////////////////////////////////////
    //  width
    ////////////////////////////////////////////////

    /** XML node name for step value. */
    private static final String STEP = "step";

    /**
     * Sets the step value for this allowed value range.
     *
     * @param value the step value string to set
     */
    public void setStep(String value) {
        getAllowedValueRangeNode().setNode(STEP, value);
    }

    /**
     * Gets the step value for this allowed value range.
     *
     * @return the step value as a string
     */
    public String getStep() {
        return getAllowedValueRangeNode().getNodeValue(STEP);
    }
}
