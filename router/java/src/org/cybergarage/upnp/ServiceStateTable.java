/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.upnp;

import java.util.Vector;

/**
 * A collection of UPnP service state variables.
 *
 * <p>This class extends Vector to manage multiple StateVariable objects that represent the state
 * variables of UPnP services. It provides type-safe collection management for service state
 * tracking and event notification.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Type-safe state variable collection
 *   <li>XML element name constant
 *   <li>Vector-based implementation for efficiency
 *   <li>Service state management
 *   <li>Event notification integration
 * </ul>
 *
 * <p>This class is used by UPnP services to maintain collections of state variables, enabling
 * organized state management and event notification for service monitoring and control
 * applications.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class ServiceStateTable extends Vector<StateVariable> {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    public static final String ELEM_NAME = "serviceStateTable";

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public ServiceStateTable() {}

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    public StateVariable getStateVariable(int n) {
        return get(n);
    }
}
