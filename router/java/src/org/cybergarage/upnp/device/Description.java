/*
 * CyberUPnP for Java
 * Copyright (C) Satoshi Konno 2002
 */

package org.cybergarage.upnp.device;

/**
 * Utility class for UPnP device description error messages.
 *
 * <p>This class provides constant error messages used during UPnP device description processing and
 * validation. It centralizes error message strings for consistent error reporting across the
 * framework.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Standardized error message constants
 *   <li>Description loading error messages
 *   <li>Root node validation errors
 *   <li>Root device validation errors
 *   <li>Consistent error reporting
 * </ul>
 *
 * <p>This class is used by UPnP device parsing components to provide consistent error messages when
 * encountering issues with device description documents, improving debugging and error handling.
 *
 * @author Satoshi Konno
 * @since 1.0
 */
public class Description {

    /** Error message when description file cannot be loaded */
    public static final String LOADING_EXCEPTION = "Couldn't load a specified description file ";
    /** Error message when root node is not found */
    public static final String NOROOT_EXCEPTION = "Couldn't find a root node";
    /** Error message when root device node is not found */
    public static final String NOROOTDEVICE_EXCEPTION = "Couldn't find a root device node";
}
