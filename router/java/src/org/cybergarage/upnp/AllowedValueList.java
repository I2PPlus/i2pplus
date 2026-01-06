/*
 * CyberLink for Java
 * Copyright (C) Satoshi Konno 2002-2004
 */

package org.cybergarage.upnp;

import java.util.Iterator;
import java.util.Vector;

/**
 * A collection of UPnP allowed value constraints.
 *
 * <p>This class extends Vector to manage multiple AllowedValue objects that represent allowed value
 * constraints for state variables in UPnP services. It provides type-safe collection management for
 * allowed value definitions.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Type-safe allowed value collection
 *   <li>XML element name constant
 *   <li>Vector-based implementation for efficiency
 *   <li>Iterator support for traversal
 *   <li>Service description integration
 * </ul>
 *
 * <p>This class is used by UPnP services to manage collections of allowed value constraints for
 * state variables, enabling proper XML description generation and validation of variable value
 * sets.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class AllowedValueList extends Vector<AllowedValue> {
    ////////////////////////////////////////////////
    //  Constants
    ////////////////////////////////////////////////

    /** XML element name for allowed value list in UPnP descriptions. */
    public static final String ELEM_NAME = "allowedValueList";

    ////////////////////////////////////////////////
    //  Constructor
    ////////////////////////////////////////////////

    /** Creates an empty allowed value list. */
    public AllowedValueList() {}

    /**
     * Creates an allowed value list from an array of value strings.
     *
     * @param values the array of value strings to add to the list
     */
    public AllowedValueList(String[] values) {
        for (int i = 0; i < values.length; i++) {
            add(new AllowedValue(values[i]));
        }
        ;
    }

    ////////////////////////////////////////////////
    //  Methods
    ////////////////////////////////////////////////

    /**
     * Gets the allowed value at the specified index.
     *
     * @param n the index of the allowed value to retrieve
     * @return the allowed value at the specified index
     */
    public AllowedValue getAllowedValue(int n) {
        return get(n);
    }

    /**
     * Checks if the specified value is in the allowed value list.
     *
     * @param v the value to check
     * @return true if the value is in the list
     */
    public boolean isAllowed(String v) {
        for (Iterator<AllowedValue> i = this.iterator(); i.hasNext(); ) {
            AllowedValue av = i.next();
            if (av.getValue().equals(v)) return true;
        }
        return false;
    }
}
