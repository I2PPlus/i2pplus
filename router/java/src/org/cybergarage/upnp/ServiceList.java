/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp;

import java.util.Vector;

/**
 * A collection of UPnP services.
 *
 * <p>This class extends Vector to manage multiple Service objects that represent services provided
 * by UPnP devices. It provides type-safe collection management for service enumeration and
 * organization.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Type-safe service collection
 *   <li>XML element name constant
 *   <li>Vector-based implementation for efficiency
 *   <li>Service discovery result management
 *   <li>Device service organization
 * </ul>
 *
 * <p>This class is used by UPnP devices and control points to maintain collections of services,
 * enabling organized service management and enumeration for application use and XML description
 * generation.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class ServiceList extends Vector<Service> {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    public static final String ELEM_NAME = "serviceList";

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public ServiceList() {}

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    /**
     * Return the service held at the given position in the list.
     *
     * @param n the zero-based index of the wanted service
     * @return the service at that position, or null if the index is out of range
     * or the underlying element could not be read
     */
    public Service getService(int n) {
        Object obj = null;
        try {
            obj = get(n);
        } catch (Exception e) {
            // Return null if get() fails
        }
        return (Service) obj;
    }
}
