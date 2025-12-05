/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

/**
 * Unified logging interface for I2PTunnel components.
 * <p>
 * Provides consistent logging across all tunnel types and components,
 * abstracting the underlying logging implementation. Routes messages
 * to console, file, or GUI based on I2P client configuration.
 */

public interface Logging {
    public void log(String s);
}