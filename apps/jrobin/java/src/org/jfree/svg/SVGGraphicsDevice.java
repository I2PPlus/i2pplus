package org.jfree.svg;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;

/** A graphics device for JFreeSVG. */
public class SVGGraphicsDevice extends GraphicsDevice {

    /** The device id */
    private final String id;

    /** The default configuration */
    GraphicsConfiguration defaultConfig;

    /**
     * Creates a new instance.
     *
     * @param id the identifier
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
