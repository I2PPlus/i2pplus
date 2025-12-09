/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp;

import java.util.Vector;

/**
 * A collection of UPnP devices.
 *
 * <p>This class extends Vector to manage multiple Device objects that represent UPnP devices
 * discovered on the network. It provides type-safe collection management for device enumeration and
 * organization.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Type-safe device collection
 *   <li>XML element name constant
 *   <li>Vector-based implementation for efficiency
 *   <li>Device discovery result management
 *   <li>Control point integration
 * </ul>
 *
 * <p>This class is used by UPnP control points to maintain collections of discovered devices,
 * enabling organized device management and enumeration for application use.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class DeviceList extends Vector<Device> {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    public static final String ELEM_NAME = "deviceList";

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public DeviceList() {}

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    public Device getDevice(int n) {
        return get(n);
    }
}
