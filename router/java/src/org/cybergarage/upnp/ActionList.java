/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.upnp;

import java.util.Vector;

/** A list of UPnP actions. Extends Vector to provide action-specific functionality. */
public class ActionList extends Vector<Action> {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    /** The element name for action list in XML */
    public static final String ELEM_NAME = "actionList";

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    /** Constructs a new ActionList. */
    public ActionList() {}

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    /**
     * Gets the action at the specified index.
     *
     * @param n the index
     * @return the action at the specified index
     */
    public Action getAction(int n) {
        return get(n);
    }
}
