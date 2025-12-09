/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002-2003
 */

package org.cybergarage.upnp.control;

import org.cybergarage.upnp.*;

/** Interface for handling UPnP query control events. */
public interface QueryListener {
    /**
     * Called when a query control is received for a state variable.
     *
     * @param stateVar the state variable being queried
     * @return true if the query was handled, false otherwise
     */
    public boolean queryControlReceived(StateVariable stateVar);
}
