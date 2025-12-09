/******************************************************************
 * CyberLink for Java
 * Copyright (C) Satoshi Konno 2002-2004
 ******************************************************************/

package org.cybergarage.upnp.device;

import org.cybergarage.upnp.Device;

/**
 * Interface for receiving UPnP device change notifications.
 *
 * <p>This interface defines the contract for objects that wish to receive notifications when UPnP
 * devices are added to or removed from a control point's device list. It provides callbacks for
 * tracking device presence changes.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Device addition notification
 *   <li>Device removal notification
 *   <li>Real-time device tracking
 *   <li>Control point integration
 * </ul>
 *
 * <p>Implementations of this interface can register with UPnP control points to receive
 * notifications when devices become available or unavailable, enabling responsive application
 * behavior and dynamic device management.
 *
 * @author Satoshi Konno
 * @author Oliver Newell
 * @since 1.0
 */
public interface DeviceChangeListener {
    /**
     * Called when a UPnP device is added to the control point.
     *
     * @param dev the device that was added
     */
    public void deviceAdded(Device dev);

    /**
     * Called when a UPnP device is removed from the control point.
     *
     * @param dev the device that was removed
     */
    public void deviceRemoved(Device dev);
}
