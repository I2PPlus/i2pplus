package org.cybergarage.upnp;

/**
 * Root description for UPnP devices.
 *
 * @author Stefano "Kismet" Lenzi - kismet-sl@users.sourceforge.net <br>
 *     Copyright (c) 2005
 */
public interface RootDescription {

    /**
     * ROOT_ELEMENT.
     */
    public final String ROOT_ELEMENT = "root";
    /**
     * ROOT_ELEMENT_NAMESPACE.
     */
    public final String ROOT_ELEMENT_NAMESPACE = "urn:schemas-upnp-org:device-1-0";

    /**
     * SPECVERSION_ELEMENT.
     */
    public final String SPECVERSION_ELEMENT = "specVersion";
    /**
     * MAJOR_ELEMENT.
     */
    public final String MAJOR_ELEMENT = "major";
    /**
     * MINOR_ELEMENT.
     */
    public final String MINOR_ELEMENT = "minor";
    /**
     * SERVICE_LIST_ELEMENT.
     */
    public final String SERVICE_LIST_ELEMENT = "serviceList";
}
