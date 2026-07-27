package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PException;

/**
 * Represent an error serializing or deserializing an APIMessage
 *
 * @author jrandom
 */
public class I2NPMessageException extends I2PException {

    /**
     * Create an exception with a message and cause
     *
     * @param message the detail message
     * @param parent the cause
     */
    public I2NPMessageException(String message, Throwable parent) {
        super(message, parent);
    }

    /**
     * Create an exception with a message
     *
     * @param message the detail message
     */
    public I2NPMessageException(String message) {
        super(message);
    }
}
