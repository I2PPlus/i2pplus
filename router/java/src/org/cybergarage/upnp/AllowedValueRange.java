/******************************************************************
 * CyberLink for Java
 * Copyright (C) Satoshi Konno 2002-2004
 ******************************************************************/

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
    //	Constants
    ////////////////////////////////////////////////

    public static final String ELEM_NAME = "allowedValueRange";

    ////////////////////////////////////////////////
    //	Member
    ////////////////////////////////////////////////

    private Node allowedValueRangeNode;

    public Node getAllowedValueRangeNode() {
        return allowedValueRangeNode;
    }

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public AllowedValueRange(Node node) {
        allowedValueRangeNode = node;
    }

    public AllowedValueRange() {
        // TODO Test
        allowedValueRangeNode = new Node(ELEM_NAME);
    }

    ////////////////////////////////////////////////
    //	isAllowedValueRangeNode
    ////////////////////////////////////////////////

    public AllowedValueRange(Number max, Number min, Number step) {
        // TODO Test
        allowedValueRangeNode = new Node(ELEM_NAME);
        if (max != null) setMaximum(max.toString());
        if (min != null) setMinimum(min.toString());
        if (step != null) setStep(step.toString());
    }

    public static boolean isAllowedValueRangeNode(Node node) {
        return ELEM_NAME.equals(node.getName());
    }

    ////////////////////////////////////////////////
    //	minimum
    ////////////////////////////////////////////////

    private static final String MINIMUM = "minimum";

    public void setMinimum(String value) {
        getAllowedValueRangeNode().setNode(MINIMUM, value);
    }

    public String getMinimum() {
        return getAllowedValueRangeNode().getNodeValue(MINIMUM);
    }

    ////////////////////////////////////////////////
    //	maximum
    ////////////////////////////////////////////////

    private static final String MAXIMUM = "maximum";

    public void setMaximum(String value) {
        getAllowedValueRangeNode().setNode(MAXIMUM, value);
    }

    public String getMaximum() {
        return getAllowedValueRangeNode().getNodeValue(MAXIMUM);
    }

    ////////////////////////////////////////////////
    //	width
    ////////////////////////////////////////////////

    private static final String STEP = "step";

    public void setStep(String value) {
        getAllowedValueRangeNode().setNode(STEP, value);
    }

    public String getStep() {
        return getAllowedValueRangeNode().getNodeValue(STEP);
    }
}
