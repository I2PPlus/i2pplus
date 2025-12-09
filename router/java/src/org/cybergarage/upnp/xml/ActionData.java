/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 ******************************************************************/

package org.cybergarage.upnp.xml;

import org.cybergarage.upnp.control.*;

/**
 * Data container for UPnP action information. Extends NodeData to provide action-specific
 * functionality.
 */
public class ActionData extends NodeData {
    /** Default constructor */
    public ActionData() {}

    ////////////////////////////////////////////////
    // ActionListener
    ////////////////////////////////////////////////

    private ActionListener actionListener = null;

    /**
     * Gets the action listener for this action data.
     *
     * @return the action listener
     */
    public ActionListener getActionListener() {
        return actionListener;
    }

    /**
     * Sets the action listener for this action data.
     *
     * @param actionListener the action listener to set
     */
    public void setActionListener(ActionListener actionListener) {
        this.actionListener = actionListener;
    }

    ////////////////////////////////////////////////
    // ControlResponse
    ////////////////////////////////////////////////

    private ControlResponse ctrlRes = null;

    /**
     * Gets the control response for this action data.
     *
     * @return the control response
     */
    public ControlResponse getControlResponse() {
        return ctrlRes;
    }

    /**
     * Sets the control response for this action data.
     *
     * @param res the control response to set
     */
    public void setControlResponse(ControlResponse res) {
        ctrlRes = res;
    }
}
