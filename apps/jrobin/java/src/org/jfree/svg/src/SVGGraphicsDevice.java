/* ===================================================
 * JFreeSVG : an SVG library for the Java(tm) platform
 * ===================================================
 *
 * (C)opyright 2013-2020, by Object Refinery Limited.  All rights reserved.
 *
 * Project Info:  http://www.jfree.org/jfreesvg/index.html
 *
 * Licensed under the GPL version 3 or later.
 *
 * If you do not wish to be bound by the terms of the GPL, an alternative
 * commercial license can be purchased.  For details, please see visit the
 * JFreeSVG home page:
 *
 * http://www.jfree.org/jfreesvg
 *
 */

package org.jfree.svg;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;

/** A graphics device for JFreeSVG. */
public class SVGGraphicsDevice extends GraphicsDevice {

    private final String id;

    GraphicsConfiguration defaultConfig;

    /**
     * Creates a new instance.
     *
     * @param id the id.
     * @param defaultConfig the default configuration.
     */
    public SVGGraphicsDevice(String id, GraphicsConfiguration defaultConfig) {
        this.id = id;
        this.defaultConfig = defaultConfig;
    }

    /**
     * Returns the device type.
     *
     * @return The device type.
     */
    @Override
    public int getType() {
        return GraphicsDevice.TYPE_PRINTER;
    }

    /**
     * Returns the id string.
     *
     * @return The id string.
     */
    @Override
    public String getIDstring() {
        return this.id;
    }

    /**
     * Returns all configurations for this device.
     *
     * @return All configurations for this device.
     */
    @Override
    public GraphicsConfiguration[] getConfigurations() {
        return new GraphicsConfiguration[] {getDefaultConfiguration()};
    }

    /**
     * Returns the default configuration for this device.
     *
     * @return The default configuration for this device.
     */
    @Override
    public GraphicsConfiguration getDefaultConfiguration() {
        return this.defaultConfig;
    }
}
