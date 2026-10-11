package org.cybergarage.upnp;

/**
 * Root description for UPnP devices.
 *
 * @author Stefano "Kismet" Lenzi - kismet-sl@users.sourceforge.net <br>
 *         Copyright (c) 2005
 */
public interface RootDescription {

    /**
     * Name of the XML element wrapping a UPnP device description.
     */
    public final String ROOT_ELEMENT = "root";
    /**
     * Namespace URI qualifying the root element.
     */
    public final String ROOT_ELEMENT_NAMESPACE = "urn:schemas-upnp-org:device-1-0";

    /**
     * Name of the element carrying the specification version.
     */
    public final String SPECVERSION_ELEMENT = "specVersion";
    /**
     * Name of the element holding the major version number.
     */
    public final String MAJOR_ELEMENT = "major";
    /**
     * Name of the element holding the minor version number.
     */
    public final String MINOR_ELEMENT = "minor";
    /**
     * Name of the element wrapping the device's service list.
     */
    public final String SERVICE_LIST_ELEMENT = "serviceList";
}
