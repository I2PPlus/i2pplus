package net.i2p.client.streaming.impl;

import net.i2p.I2PException;

/**
 * We attempted to have more open streams than we are willing to put up with
 *
 */
public class TooManyStreamsException extends I2PException {
    /**
     * Creates a new TooManyStreamsException with a message and a cause.
     *
     * @param message the detail message
     * @param parent the cause
     */
    public TooManyStreamsException(String message, Throwable parent) {
        super(message, parent);
    }

    /**
     * Creates a new TooManyStreamsException with a message.
     *
     * @param message the detail message
     */
    public TooManyStreamsException(String message) {
        super(message);
    }

    public TooManyStreamsException() {
        super();
    }
}
