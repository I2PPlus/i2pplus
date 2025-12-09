/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.upnp.control;

import org.cybergarage.upnp.*;

/** Interface for handling UPnP action control events. */
public interface ActionListener {
    /**
     * Called when an action control is received.
     *
     * @param action the action received
     * @return true if the action was handled, false otherwise
     */
    public boolean actionControlReceived(Action action);
}
