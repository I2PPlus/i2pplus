/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

/**
 * Simple logging interface for I2PTunnel components.
 * <p>
 * This interface provides a unified logging mechanism used throughout the I2PTunnel
 * system to output status messages, errors, and operational information.
 * It abstracts the underlying logging implementation, allowing consistent
 * log formatting and output across all tunnel types and components.
 * <p>
 * Implementations typically route messages to console, file, or GUI
 * displays depending on the I2P client configuration.
 */

public interface Logging {
    public void log(String s);
}