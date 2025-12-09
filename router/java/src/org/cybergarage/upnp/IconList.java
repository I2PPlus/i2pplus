/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp;

import java.util.Vector;

/**
 * A collection of UPnP device icons.
 *
 * <p>This class extends Vector to manage multiple Icon objects that represent icon resources for
 * UPnP devices. It provides type-safe collection management for device icon enumeration and
 * organization.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Type-safe icon collection
 *   <li>XML element name constant
 *   <li>Vector-based implementation for efficiency
 *   <li>Device icon management
 *   <li>Presentation and UI integration
 * </ul>
 *
 * <p>This class is used by UPnP devices to maintain collections of icons, enabling organized icon
 * management for device presentation and user interface applications that display device
 * information.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class IconList extends Vector<Icon> {
    ////////////////////////////////////////////////
    //	Constants
    ////////////////////////////////////////////////

    public static final String ELEM_NAME = "iconList";

    ////////////////////////////////////////////////
    //	Constructor
    ////////////////////////////////////////////////

    public IconList() {}

    ////////////////////////////////////////////////
    //	Methods
    ////////////////////////////////////////////////

    public Icon getIcon(int n) {
        return get(n);
    }
}
