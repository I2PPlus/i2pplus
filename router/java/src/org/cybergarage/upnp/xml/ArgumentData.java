/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 ******************************************************************/

package org.cybergarage.upnp.xml;

/**
 * Data container for UPnP action argument information.
 *
 * <p>This class extends NodeData to represent action argument definitions from UPnP service
 * descriptions. It encapsulates metadata about action arguments including their values, directions,
 * and related state variables.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Argument value storage
 *   <li>Direction (in/out) management
 *   <li>Related state variable association
 *   <li>XML node data inheritance
 *   <li>Argument metadata handling
 * </ul>
 *
 * <p>This class is used by UPnP actions to manage argument definitions and their values during
 * action invocation, enabling proper parameter handling and response generation.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class ArgumentData extends NodeData {
    public ArgumentData() {}

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
}
