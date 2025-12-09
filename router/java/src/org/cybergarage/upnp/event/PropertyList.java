/******************************************************************
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 ******************************************************************/

package org.cybergarage.upnp.event;

import java.util.*;

/**
 * A collection of UPnP event properties.
 *
 * <p>This class extends Vector to manage a list of Property objects that represent state variable
 * changes in UPnP event notifications. It provides a typed container for properties that are
 * included in event messages sent to subscribers.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Type-safe property collection management
 *   <li>XML element name definition for serialization
 *   <li>Vector-based implementation for efficient access
 *   <li>Property retrieval by index
 * </ul>
 *
 * <p>This class is used in UPnP event notifications to group multiple property changes that occur
 * simultaneously, allowing subscribers to receive batched updates about service state variable
 * changes.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class PropertyList extends Vector<Property> {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    public static final String ELEM_NAME = "PropertyList";

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public PropertyList() {}

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    public Property getProperty(int n) {
        return get(n);
    }
}
